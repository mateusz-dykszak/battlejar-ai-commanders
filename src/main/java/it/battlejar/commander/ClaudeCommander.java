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
    private static final float MISSILE_INTERCEPT_RANGE = 150f;
    private static final float CARRIER_DODGE_RANGE = 200f;
    private static final float CARRIER_DODGE_DISTANCE = 60f;
    private static final float FORMATION_RADIUS_TIGHT = 50f;   // used until FORMATION_EXPAND_AT fighters are active
    private static final float FORMATION_RADIUS_WIDE = 150f;   // directional arc once screen is established
    private static final int FORMATION_EXPAND_AT = 8;
    private static final int FORMATION_SLOTS = 8;

    private final Map<String, Long> lastOrderTime = new HashMap<>();
    // fighters we explicitly docked for damage recovery; excluded from deploy orders until RECOVERY_MS passes
    private final Set<String> recovering = new HashSet<>();
    private final Map<String, Long> dockTime = new HashMap<>();
    // last known angle to nearest enemy carrier; reused when no enemy is visible
    private float lastFormationAngle = 0f;

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

        if (nearestEnemyCarrier != null) {
            lastFormationAngle = (float) Math.atan2(
                    nearestEnemyCarrier.py() - myCarrier.py(),
                    nearestEnemyCarrier.px() - myCarrier.px());
        }
        float formRadius = myFighters.size() >= FORMATION_EXPAND_AT
                ? FORMATION_RADIUS_WIDE : FORMATION_RADIUS_TIGHT;
        int[][] formation = buildFormation(lastFormationAngle, formRadius);

        // Assign one fighter per threatening missile to physically intercept it.
        // Keyed by fighter id → carrier-relative offset of the missile's current position.
        Map<String, int[]> intercept = buildInterceptAssignments(entities, myCarrier, myFighters);

        for (Entity fighter : myFighters) {
            int[] offset = formationOffset(fighter, formation);
            boolean inFormation = distanceTo(fighter,
                    myCarrier.px() + offset[0], myCarrier.py() + offset[1]) < FORMATION_THRESHOLD;

            if (isNearBorder(fighter)) {
                sendOrder(new Order(fighter.id(), OrderType.MOVE, "0|0"));
            } else if (health(fighter) <= DOCK_HEALTH_THRESHOLD) {
                recovering.add(fighter.id());
                dockTime.put(fighter.id(), System.currentTimeMillis());
                sendOrder(new Order(fighter.id(), OrderType.DOCK));
            } else if (intercept.containsKey(fighter.id())) {
                int[] mPos = intercept.get(fighter.id());
                sendOrder(new Order(fighter.id(), OrderType.MOVE, mPos[0] + "|" + mPos[1]));
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
                        int[] offset = formationOffset(docked, formation);
                        sendOrder(new Order(docked.id(), OrderType.MOVE, offset[0] + "|" + offset[1]));
                    }
                });

        int[] carrierDodge = computeCarrierDodge(entities, myCarrier);

        if (isNearBorder(myCarrier)) {
            // Move to nearest safe interior point — stays in spawn quadrant, does not rush to center
            float inner = BORDER_MARGIN + SAFE_INSET;
            float safeX = Math.max(inner, Math.min(settings.worldWidth() - inner, myCarrier.px()));
            float safeY = Math.max(inner, Math.min(settings.worldHeight() - inner, myCarrier.py()));
            sendOrder(new Order(myCarrier.id(), OrderType.MOVE,
                    (int) (safeX - myCarrier.px()) + "|" + (int) (safeY - myCarrier.py())));
        } else if (carrierDodge != null) {
            sendOrder(new Order(myCarrier.id(), OrderType.MOVE,
                    carrierDodge[0] + "|" + carrierDodge[1]));
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

    // When an armed missile is within CARRIER_DODGE_RANGE and heading toward our carrier,
    // return a carrier-relative offset perpendicular to the missile's velocity.
    // Returns null if no dodge is needed or both perpendicular directions hit the border.
    private int[] computeCarrierDodge(Collection<Entity> entities, Entity myCarrier) {
        Entity missile = entities.stream()
                .filter(e -> e.type() == Entity.Type.MISSILE)
                .filter(e -> !myColor.name().equals(e.color()))
                .filter(e -> "A".equals(e.status()))
                .filter(e -> distance(e, myCarrier) < CARRIER_DODGE_RANGE)
                .filter(e -> {
                    float toCx = myCarrier.px() - e.px();
                    float toCy = myCarrier.py() - e.py();
                    return e.vx() * toCx + e.vy() * toCy > 0;
                })
                .min((a, b) -> Float.compare(distance(a, myCarrier), distance(b, myCarrier)))
                .orElse(null);

        if (missile == null) return null;

        float vLen = (float) Math.sqrt(missile.vx() * missile.vx() + missile.vy() * missile.vy());
        if (vLen < 0.001f) return null;

        // Unit perpendiculars to missile velocity
        float px1 = -missile.vy() / vLen,  py1 =  missile.vx() / vLen;
        float px2 =  missile.vy() / vLen,  py2 = -missile.vx() / vLen;

        float margin = BORDER_MARGIN + SAFE_INSET;
        float ww = settings.worldWidth(), wh = settings.worldHeight();

        float tx1 = myCarrier.px() + px1 * CARRIER_DODGE_DISTANCE;
        float ty1 = myCarrier.py() + py1 * CARRIER_DODGE_DISTANCE;
        float tx2 = myCarrier.px() + px2 * CARRIER_DODGE_DISTANCE;
        float ty2 = myCarrier.py() + py2 * CARRIER_DODGE_DISTANCE;

        boolean ok1 = tx1 > margin && ty1 > margin && tx1 < ww - margin && ty1 < wh - margin;
        boolean ok2 = tx2 > margin && ty2 > margin && tx2 < ww - margin && ty2 < wh - margin;

        float[] chosen;
        if (ok1 && !ok2) {
            chosen = new float[]{px1, py1};
        } else if (ok2 && !ok1) {
            chosen = new float[]{px2, py2};
        } else if (ok1) {
            // Both clear — pick the one with more interior clearance
            float c1 = Math.min(Math.min(tx1 - margin, ww - margin - tx1),
                                Math.min(ty1 - margin, wh - margin - ty1));
            float c2 = Math.min(Math.min(tx2 - margin, ww - margin - tx2),
                                Math.min(ty2 - margin, wh - margin - ty2));
            chosen = c1 >= c2 ? new float[]{px1, py1} : new float[]{px2, py2};
        } else {
            return null; // Both directions hit the border; skip dodge
        }

        return new int[]{
            Math.round(chosen[0] * CARRIER_DODGE_DISTANCE),
            Math.round(chosen[1] * CARRIER_DODGE_DISTANCE)
        };
    }

    // For each armed enemy missile within MISSILE_INTERCEPT_RANGE of our carrier,
    // assign the closest available fighter to physically move to the missile position.
    // Returns fighter-id → carrier-relative [dx, dy] of the missile.
    private Map<String, int[]> buildInterceptAssignments(
            Collection<Entity> entities, Entity myCarrier, List<Entity> myFighters) {
        List<Entity> threats = entities.stream()
                .filter(e -> e.type() == Entity.Type.MISSILE)
                .filter(e -> !myColor.name().equals(e.color()))
                .filter(e -> "A".equals(e.status()))
                .filter(e -> distance(e, myCarrier) < MISSILE_INTERCEPT_RANGE)
                .sorted((a, b) -> Float.compare(distance(a, myCarrier), distance(b, myCarrier)))
                .toList();

        Map<String, int[]> result = new HashMap<>();
        Set<String> assigned = new HashSet<>();
        for (Entity missile : threats) {
            myFighters.stream()
                    .filter(f -> !assigned.contains(f.id()))
                    .filter(f -> !recovering.contains(f.id()))
                    .filter(f -> !isNearBorder(f))
                    .min((a, b) -> Float.compare(distance(a, missile), distance(b, missile)))
                    .ifPresent(f -> {
                        assigned.add(f.id());
                        result.put(f.id(), new int[]{
                            Math.round(missile.px() - myCarrier.px()),
                            Math.round(missile.py() - myCarrier.py())
                        });
                    });
        }
        return result;
    }

    // 8 slots spread in a 180° arc facing the enemy carrier (carrier-relative offsets)
    private int[][] buildFormation(float angleRad, float radius) {
        int[][] offsets = new int[FORMATION_SLOTS][2];
        for (int i = 0; i < FORMATION_SLOTS; i++) {
            float t = (float) i / (FORMATION_SLOTS - 1);
            float slotAngle = angleRad - (float) Math.PI / 2f + t * (float) Math.PI;
            offsets[i][0] = Math.round(radius * (float) Math.cos(slotAngle));
            offsets[i][1] = Math.round(radius * (float) Math.sin(slotAngle));
        }
        return offsets;
    }

    private int[] formationOffset(Entity fighter, int[][] formation) {
        try {
            int num = Integer.parseInt(fighter.id().split("-")[1]);
            return formation[num % formation.length];
        } catch (NumberFormatException e) {
            return formation[0];
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
