package it.battlejar.commander;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.client.AbstractCommander;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
public class ClaudeCommander extends AbstractCommander {

    private static final long ORDER_COOLDOWN_MS = 150;
    private static final float BORDER_MARGIN = 50f;
    private static final float SAFE_INSET = 30f;      // extra clearance beyond border margin
    private static final float FORMATION_THRESHOLD = 50f;
    private static final int DOCK_HEALTH_THRESHOLD = 3;    // fighters start at 10 HP; dock below 30%
    private static final long RECOVERY_MS = 3_000;         // time a docked fighter is left to heal before redeploying
    private static final float FIGHTER_MISSILE_RANGE = 150f;

    // 8 compass positions at radius 80 around the carrier (carrier-relative offsets)
    private static final int[][] FORMATION = {
        { 80,  0}, { 57, 57}, {  0, 80}, {-57, 57},
        {-80,  0}, {-57,-57}, {  0,-80}, { 57,-57}
    };

    private final Map<String, Long> lastOrderTime = new HashMap<>();
    // fighters we explicitly docked for damage recovery; excluded from deploy orders until RECOVERY_MS passes
    private final Set<String> recovering = new HashSet<>();
    private final Map<String, Long> dockTime = new HashMap<>();

    @Override
    protected boolean process(Collection<Entity> entities) {
        Entity myCarrier = entities.stream()
                .filter(e -> e.type() == Entity.Type.CARRIER)
                .filter(e -> myColor.name().equals(e.color()))
                .findFirst()
                .orElse(null);

        if (myCarrier == null) {
            return true;
        }

        List<Entity> myFighters = entities.stream()
                .filter(e -> e.type() == Entity.Type.FIGHTER)
                .filter(e -> myColor.name().equals(e.color()))
                .filter(e -> !"D".equals(e.status()))
                .filter(e -> !"C".equals(e.status()))
                .toList();

        boolean hasEnemies = entities.stream()
                .filter(e -> !myColor.name().equals(e.color()))
                .anyMatch(e -> !"D".equals(e.status()));

        Entity nearestEnemyCarrier = entities.stream()
                .filter(e -> e.type() == Entity.Type.CARRIER)
                .filter(e -> !myColor.name().equals(e.color()))
                .filter(e -> !"D".equals(e.status()))
                .min((a, b) -> Float.compare(distance(a, myCarrier), distance(b, myCarrier)))
                .orElse(null);

        boolean enemyMissilesNearby = entities.stream()
                .filter(e -> e.type() == Entity.Type.MISSILE)
                .filter(e -> !myColor.name().equals(e.color()))
                .filter(e -> "A".equals(e.status()))
                .anyMatch(e -> distance(e, myCarrier) < 200f);

        for (Entity fighter : myFighters) {
            int[] offset = formationOffset(fighter);
            boolean inFormation = distanceTo(fighter,
                    myCarrier.px() + offset[0], myCarrier.py() + offset[1]) < FORMATION_THRESHOLD;

            if (isNearBorder(fighter)) {
                sendOrder(new Order(fighter.id(), OrderType.MOVE, "0|0"));
            } else if (health(fighter) <= DOCK_HEALTH_THRESHOLD) {
                recovering.add(fighter.id());
                dockTime.put(fighter.id(), System.currentTimeMillis());
                sendOrder(new Order(fighter.id(), OrderType.DOCK));
            } else if (enemyMissilesNearby) {
                sendOrder(new Order(fighter.id(), OrderType.TARGET, "M"));
            } else if (!inFormation) {
                sendOrder(new Order(fighter.id(), OrderType.MOVE, offset[0] + "|" + offset[1]));
            } else if (nearestEnemyCarrier != null && fighter.missiles() > 0
                    && distance(fighter, nearestEnemyCarrier) < FIGHTER_MISSILE_RANGE) {
                sendOrder(new Order(fighter.id(), OrderType.FIRE_MISSILE));
            } else if (nearestEnemyCarrier != null) {
                sendOrder(new Order(fighter.id(), OrderType.ATTACK, nearestEnemyCarrier.id()));
            } else if (hasEnemies) {
                sendOrder(new Order(fighter.id(), OrderType.ATTACK));
            }
        }

        // Send formation orders to docked fighters to trigger undocking at the game's configured rate.
        // Skip fighters still in the recovery window from a damage-retreat DOCK.
        long now = System.currentTimeMillis();
        entities.stream()
                .filter(e -> e.type() == Entity.Type.FIGHTER)
                .filter(e -> myColor.name().equals(e.color()))
                .filter(e -> "C".equals(e.status()))
                .forEach(docked -> {
                    boolean stillRecovering = recovering.contains(docked.id())
                            && now - dockTime.getOrDefault(docked.id(), 0L) < RECOVERY_MS;
                    if (!stillRecovering) {
                        recovering.remove(docked.id());
                        int[] offset = formationOffset(docked);
                        sendOrder(new Order(docked.id(), OrderType.MOVE, offset[0] + "|" + offset[1]));
                    }
                });

        if (isNearBorder(myCarrier)) {
            // Move to nearest safe interior point — stays in spawn quadrant, does not rush to center
            float inner = BORDER_MARGIN + SAFE_INSET;
            float safeX = Math.max(inner, Math.min(settings.worldWidth() - inner, myCarrier.px()));
            float safeY = Math.max(inner, Math.min(settings.worldHeight() - inner, myCarrier.py()));
            sendOrder(new Order(myCarrier.id(), OrderType.MOVE,
                    (int) (safeX - myCarrier.px()) + "|" + (int) (safeY - myCarrier.py())));
        } else if (nearestEnemyCarrier != null && myCarrier.missiles() > 0) {
            float dx = nearestEnemyCarrier.px() - myCarrier.px();
            float dy = nearestEnemyCarrier.py() - myCarrier.py();
            sendOrder(new Order(myCarrier.id(), OrderType.FIRE_MISSILE, (int) dx + "|" + (int) dy));
        } else if (myFighters.isEmpty() && nearestEnemyCarrier != null) {
            sendOrder(new Order(myCarrier.id(), OrderType.ATTACK, nearestEnemyCarrier.id()));
        } else if (myFighters.isEmpty() && hasEnemies) {
            sendOrder(new Order(myCarrier.id(), OrderType.ATTACK));
        } else if (hasEnemies) {
            sendOrder(new Order(myCarrier.id(), OrderType.PATROL));
        }

        return true;
    }

    private void sendOrder(Order order) {
        long now = System.currentTimeMillis();
        if (now - lastOrderTime.getOrDefault(order.id(), 0L) < ORDER_COOLDOWN_MS) {
            return;
        }
        lastOrderTime.put(order.id(), now);
        order(order);
    }

    private int health(Entity e) {
        try {
            return Integer.parseInt(e.status());
        } catch (NumberFormatException ex) {
            return Integer.MAX_VALUE;
        }
    }

    private int[] formationOffset(Entity fighter) {
        try {
            int num = Integer.parseInt(fighter.id().split("-")[1]);
            return FORMATION[num % FORMATION.length];
        } catch (NumberFormatException e) {
            return FORMATION[0];
        }
    }

    private boolean isNearBorder(Entity e) {
        return e.px() < BORDER_MARGIN
                || e.py() < BORDER_MARGIN
                || e.px() > settings.worldWidth() - BORDER_MARGIN
                || e.py() > settings.worldHeight() - BORDER_MARGIN;
    }

    private float distance(Entity a, Entity b) {
        float dx = a.px() - b.px();
        float dy = a.py() - b.py();
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private float distanceTo(Entity e, float x, float y) {
        float dx = e.px() - x;
        float dy = e.py() - y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
