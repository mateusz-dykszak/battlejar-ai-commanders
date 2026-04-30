package it.battlejar.commander;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.client.AbstractCommander;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class ClaudeCommander extends AbstractCommander {

    private static final long ORDER_COOLDOWN_MS = 150;
    private static final float BORDER_MARGIN = 50f;
    private static final float CENTER_THRESHOLD = 80f;
    private static final float FORMATION_THRESHOLD = 50f;

    // 8 compass positions at radius 80 around the carrier (carrier-relative offsets)
    private static final int[][] FORMATION = {
        { 80,  0}, { 57, 57}, {  0, 80}, {-57, 57},
        {-80,  0}, {-57,-57}, {  0,-80}, { 57,-57}
    };

    private final Map<String, Long> lastOrderTime = new HashMap<>();

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
            } else if (enemyMissilesNearby) {
                sendOrder(new Order(fighter.id(), OrderType.TARGET, "M"));
                sendOrder(new Order(fighter.id(), OrderType.ATTACK));
            } else if (!inFormation) {
                sendOrder(new Order(fighter.id(), OrderType.MOVE, offset[0] + "|" + offset[1]));
            } else if (hasEnemies) {
                sendOrder(new Order(fighter.id(), OrderType.ATTACK));
            }
        }

        float centerX = settings.worldWidth() / 2f;
        float centerY = settings.worldHeight() / 2f;
        boolean carrierNearCenter = distanceTo(myCarrier, centerX, centerY) < CENTER_THRESHOLD;

        if (isNearBorder(myCarrier) || !carrierNearCenter) {
            float dx = centerX - myCarrier.px();
            float dy = centerY - myCarrier.py();
            sendOrder(new Order(myCarrier.id(), OrderType.MOVE, (int) dx + "|" + (int) dy));
        } else if (myFighters.isEmpty() && hasEnemies) {
            sendOrder(new Order(myCarrier.id(), OrderType.ATTACK));
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
