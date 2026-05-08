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
    private static final float FORMATION_THRESHOLD = 100f; // increased from 50: reduces slot-chasing after carrier dodge
    private static final int DOCK_HEALTH_THRESHOLD = 3;    // fighters start at 10 HP; dock below 30%
    private static final long RECOVERY_MS = 1_500;         // time a docked fighter is left to heal before redeploying
    private static final float FIGHTER_MISSILE_RANGE = 150f;
    private static final float FIGHTER_LASER_RANGE = 150f;   // per-fighter proximity for TARGET "M" defense
    private static final float MISSILE_INTERCEPT_RANGE = 80f;  // physical move-to-intercept; beyond this rely on lasers
    private static final float MISSILE_TARGET_RANGE = 300f;    // carrier-relative range for intercept assignments
    private static final float FORMATION_RADIUS_TIGHT = 50f;   // used until FORMATION_EXPAND_AT fighters are active
    private static final float FORMATION_RADIUS_WIDE = 150f;   // directional arc once screen is established
    private static final int FORMATION_EXPAND_AT = 8;
    private static final int FORMATION_SLOTS = 8;
    private static final int AGGRESSION_FIGHTER_THRESHOLD = 12;
    private static final float CARRIER_PUSH_DISTANCE = 80f;
    // In 1v1, an 80-unit target tracks the retreating enemy at constant distance (net closure ≈ 0).
    // A 400-unit target aims past the enemy so the carrier always moves at max speed toward them.
    // CARRIER_MIN_SEPARATION_1V1 catches it at 80 units; combat settles at 80–100 units range.
    private static final float CARRIER_PUSH_DISTANCE_1V1 = 400f;
    private static final float CARRIER_KITE_RANGE = 150f;    // enemy carrier distance that triggers kite-away
    private static final int CARRIER_KITE_FIGHTER_MAX = 8;   // kite only when fighter screen is thin
    private static final float CARRIER_KITE_DISTANCE = 80f;  // how far to move away per kite step
    private static final int KILL_FOCUS_HP = 750;        // target enemy carrier if HP ≤ this; missiles deal ~250 HP so post-barrage target is at 750
    private static final float KILL_FOCUS_RANGE = 350f;  // only focus-fire within this distance
    private static final float CARRIER_MIN_SEPARATION_1V1 = 80f; // floor distance in 1v1 to avoid collision
    // Enemy fighters are only considered intruders when within this distance of OUR CARRIER.
    // Using carrier-relative (not fighter-relative) prevents all fighters from locking into
    // defensive mode when 30+ enemy fighters fill the area at 100–150 units — outside the ring
    // but within fighter-relative 120 units. Fighters outside this ring keep attacking the carrier.
    private static final float FIGHTER_INTRUDER_CARRIER_RANGE = 75f;

    private final Map<String, Long> lastOrderTime = new HashMap<>();
    // fighters we explicitly docked for damage recovery; excluded from deploy orders until RECOVERY_MS passes
    private final Set<String> recovering = new HashSet<>();
    private final Map<String, Long> dockTime = new HashMap<>();
    // last known angle to current target carrier; reused when no enemy is visible
    private float lastFormationAngle = 0f;
    // enemy carrier count at game start; used to detect when we have achieved a kill
    private int initialEnemyCarrierCount = 0;

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

        List<Entity> liveEnemyCarriers = entities.stream()
                .filter(e -> e.type() == Entity.Type.CARRIER)
                .filter(e -> !myColor.name().equals(e.color()))
                .filter(e -> !"D".equals(e.status()))
                .toList();

        if (liveEnemyCarriers.size() > initialEnemyCarrierCount) {
            initialEnemyCarrierCount = liveEnemyCarriers.size();
        }
        boolean hasKilledEnemy = liveEnemyCarriers.size() < initialEnemyCarrierCount;

        // Wounded carrier within range takes priority; otherwise nearest
        Entity nearestEnemyCarrier = selectTarget(liveEnemyCarriers, myCarrier);

        // Threatening missiles: armed and moving toward our carrier (dot product > 0).
        // Ownership does not matter — our own outgoing missiles fly away (dot product < 0)
        // and are filtered out; enemy missiles targeting another player are also ignored.
        // A missile that loses its target can change direction quickly, so direction is
        // re-evaluated every tick regardless of who fired it.
        List<Entity> armedEnemyMissiles = entities.stream()
                .filter(e -> e.type() == Entity.Type.MISSILE)
                .filter(e -> "A".equals(e.status()))
                .filter(e -> {
                    float toCx = myCarrier.px() - e.px();
                    float toCy = myCarrier.py() - e.py();
                    return e.vx() * toCx + e.vy() * toCy > 0;
                })
                .toList();

        if (nearestEnemyCarrier != null) {
            lastFormationAngle = (float) Math.atan2(
                    nearestEnemyCarrier.py() - myCarrier.py(),
                    nearestEnemyCarrier.px() - myCarrier.px());
        }
        // In 1v1, scale wide radius so the farthest forward fighter stays between the two carriers.
        // At fixed 150, when ec < 150, front fighters overshoot past the enemy — no laser/missile cover.
        float effectiveWideRadius = FORMATION_RADIUS_WIDE;
        if (liveEnemyCarriers.size() == 1 && nearestEnemyCarrier != null) {
            float ec = distance(myCarrier, nearestEnemyCarrier);
            if (ec < FORMATION_RADIUS_WIDE) {
                effectiveWideRadius = Math.max(FORMATION_RADIUS_TIGHT, ec / 2f);
            }
        }
        boolean expanded = myFighters.size() >= FORMATION_EXPAND_AT;
        float formRadius = expanded ? effectiveWideRadius : FORMATION_RADIUS_TIGHT;
        // tight (<8 fighters): full 360° ring for all-around early coverage
        // wide in 1v1: 180° forward arc — single threat direction known, directional pressure
        // wide in multi-enemy: full 360° ring — enemies attack from all angles simultaneously;
        //   the 180° arc leaves the rear 180° of the carrier completely unguarded against the
        //   other enemies' fighters approaching from flanks and rear.
        float formArc = (expanded && liveEnemyCarriers.size() == 1)
                ? (float) Math.PI
                : 2f * (float) Math.PI;
        int[][] formation = buildFormation(lastFormationAngle, formRadius, formArc);

        // Assign one fighter per threatening missile to physically intercept it.
        // Keyed by fighter id → carrier-relative offset of the missile's current position.
        Map<String, int[]> intercept = buildInterceptAssignments(armedEnemyMissiles, myCarrier, myFighters);

        // Precompute active enemy fighters once; each fighter independently finds its own nearest
        // within FIGHTER_INTRUDER_RANGE of itself, distributing our squad across the swarm.
        List<Entity> activeEnemyFighters = entities.stream()
                .filter(e -> e.type() == Entity.Type.FIGHTER)
                .filter(e -> !myColor.name().equals(e.color()))
                .filter(e -> !"D".equals(e.status()) && !"C".equals(e.status()))
                .toList();

        for (Entity fighter : myFighters) {
            int[] offset = formationOffset(fighter, formation);
            boolean inFormation = distanceTo(fighter,
                    myCarrier.px() + offset[0], myCarrier.py() + offset[1]) < FORMATION_THRESHOLD;
            // Only this specific fighter defends if a missile is close to IT — others keep attacking.
            boolean missileCloseToFighter = armedEnemyMissiles.stream()
                    .anyMatch(m -> distance(m, fighter) < FIGHTER_LASER_RANGE);

            if (isNearBorder(fighter)) {
                sendOrder(new Order(fighter.id(), OrderType.MOVE, "0|0"));
            } else if (health(fighter) <= DOCK_HEALTH_THRESHOLD) {
                recovering.add(fighter.id());
                dockTime.put(fighter.id(), System.currentTimeMillis());
                sendOrder(new Order(fighter.id(), OrderType.DOCK));
            } else if (intercept.containsKey(fighter.id())) {
                int[] mPos = intercept.get(fighter.id());
                sendOrder(new Order(fighter.id(), OrderType.MOVE, mPos[0] + "|" + mPos[1]));
            } else if (nearestEnemyCarrier != null && fighter.missiles() > 0
                    && distance(fighter, nearestEnemyCarrier) < FIGHTER_MISSILE_RANGE) {
                // Explicit direction required — without it the missile fires toward the fighter's
                // current facing, which is often back toward our carrier (formation slot movement).
                int fdx = Math.round(nearestEnemyCarrier.px() - fighter.px());
                int fdy = Math.round(nearestEnemyCarrier.py() - fighter.py());
                sendOrder(new Order(fighter.id(), OrderType.FIRE_MISSILE, fdx + "|" + fdy));
            } else if (missileCloseToFighter) {
                sendOrder(new Order(fighter.id(), OrderType.TARGET, "M"));
            } else if (!inFormation) {
                sendOrder(new Order(fighter.id(), OrderType.MOVE, offset[0] + "|" + offset[1]));
            } else {
                // In 1v1 the enemy has fewer fighters but clusters them near our carrier:
                // ef_near_100 reaches 17 while fighters at 76–100 units fire unchallenged under
                // the 75-unit multi-enemy gate. Raise to 100 in 1v1 to engage that band.
                // In multi-enemy keep 75 to avoid locking all fighters into defensive mode
                // when 30+ enemies fill the 100-unit zone (task-80 fix).
                float intruderRange = liveEnemyCarriers.size() == 1 ? 100f : FIGHTER_INTRUDER_CARRIER_RANGE;
                Entity nearbyEnemy = activeEnemyFighters.stream()
                        .filter(e -> distance(e, myCarrier) < intruderRange)
                        .min((a, b) -> Float.compare(distance(a, fighter), distance(b, fighter)))
                        .orElse(null);
                if (nearbyEnemy != null) {
                    sendOrder(new Order(fighter.id(), OrderType.ATTACK, nearbyEnemy.id()));
                } else if (nearestEnemyCarrier != null) {
                    sendOrder(new Order(fighter.id(), OrderType.ATTACK, nearestEnemyCarrier.id()));
                } else if (hasEnemies) {
                    sendOrder(new Order(fighter.id(), OrderType.ATTACK));
                }
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

        if (nearestEnemyCarrier != null && myCarrier.missiles() > 0) {
            float dx = nearestEnemyCarrier.px() - myCarrier.px();
            float dy = nearestEnemyCarrier.py() - myCarrier.py();
            sendOrder(new Order(myCarrier.id(), OrderType.FIRE_MISSILE, (int) dx + "|" + (int) dy));
        } else if (isNearBorder(myCarrier)) {
            // Move to nearest safe interior point — stays in spawn quadrant, does not rush to center
            float inner = BORDER_MARGIN + SAFE_INSET;
            float safeX = Math.max(inner, Math.min(settings.worldWidth() - inner, myCarrier.px()));
            float safeY = Math.max(inner, Math.min(settings.worldHeight() - inner, myCarrier.py()));
            sendOrder(new Order(myCarrier.id(), OrderType.MOVE,
                    (int) (safeX - myCarrier.px()) + "|" + (int) (safeY - myCarrier.py())));
        } else if (myFighters.isEmpty() && nearestEnemyCarrier != null) {
            sendOrder(new Order(myCarrier.id(), OrderType.ATTACK, nearestEnemyCarrier.id()));
        } else if (myFighters.isEmpty() && hasEnemies) {
            sendOrder(new Order(myCarrier.id(), OrderType.ATTACK));
        } else if (liveEnemyCarriers.size() == 1 && nearestEnemyCarrier != null
                && distance(myCarrier, nearestEnemyCarrier) < CARRIER_MIN_SEPARATION_1V1) {
            // Prevent carrier collision in 1v1 — aggression push + enemy ATTACK can drive ec to ~23.
            // Retreat to restore working separation where even the tight formation doesn't overshoot.
            float dist = distance(myCarrier, nearestEnemyCarrier);
            float retreatX = myCarrier.px() - (nearestEnemyCarrier.px() - myCarrier.px()) / dist * CARRIER_KITE_DISTANCE;
            float retreatY = myCarrier.py() - (nearestEnemyCarrier.py() - myCarrier.py()) / dist * CARRIER_KITE_DISTANCE;
            float margin = BORDER_MARGIN + SAFE_INSET;
            retreatX = Math.max(margin, Math.min(settings.worldWidth() - margin, retreatX));
            retreatY = Math.max(margin, Math.min(settings.worldHeight() - margin, retreatY));
            sendOrder(new Order(myCarrier.id(), OrderType.MOVE,
                    (int) (retreatX - myCarrier.px()) + "|" + (int) (retreatY - myCarrier.py())));
        } else if (!hasKilledEnemy && nearestEnemyCarrier != null
                && health(nearestEnemyCarrier) <= KILL_FOCUS_HP
                && myFighters.size() >= AGGRESSION_FIGHTER_THRESHOLD / 2
                && liveEnemyCarriers.size() > 1) {
            // Pre-kill push: enemy is already wounded by our opening missiles (hp ≤ KILL_FOCUS_HP).
            // Close in to bring fighters within tighter laser range and finish the kill faster.
            // Guard: only in 4-way (not 1v1) and only with a fighter screen active.
            float dist = distance(myCarrier, nearestEnemyCarrier);
            int mx = Math.round((nearestEnemyCarrier.px() - myCarrier.px()) / dist * CARRIER_PUSH_DISTANCE);
            int my = Math.round((nearestEnemyCarrier.py() - myCarrier.py()) / dist * CARRIER_PUSH_DISTANCE);
            sendOrder(new Order(myCarrier.id(), OrderType.MOVE, mx + "|" + my));
        } else if (hasKilledEnemy && nearestEnemyCarrier != null) {
            // Use a third of the threshold in 1v1: data shows we rarely hit 6 (half) during the 1v1
            // phase, so we defaulted to slower ATTACK auto-move; explicit MOVE push closes faster.
            int effectiveThreshold = liveEnemyCarriers.size() == 1
                    ? AGGRESSION_FIGHTER_THRESHOLD / 3
                    : AGGRESSION_FIGHTER_THRESHOLD;
            if (myFighters.size() >= effectiveThreshold) {
                float dist = distance(myCarrier, nearestEnemyCarrier);
                float pushDist = liveEnemyCarriers.size() == 1 ? CARRIER_PUSH_DISTANCE_1V1 : CARRIER_PUSH_DISTANCE;
                int mx = Math.round((nearestEnemyCarrier.px() - myCarrier.px()) / dist * pushDist);
                int my = Math.round((nearestEnemyCarrier.py() - myCarrier.py()) / dist * pushDist);
                sendOrder(new Order(myCarrier.id(), OrderType.MOVE, mx + "|" + my));
            } else if (liveEnemyCarriers.size() == 1) {
                // In 1v1 with too few fighters to push: ATTACK with carrier lasers while waiting for
                // more fighters to deploy. Carrier auto-moves toward target, supplementing fighter DPS.
                sendOrder(new Order(myCarrier.id(), OrderType.ATTACK, nearestEnemyCarrier.id()));
            } else if (distance(myCarrier, nearestEnemyCarrier) < CARRIER_KITE_RANGE
                    && myFighters.size() < CARRIER_KITE_FIGHTER_MAX
                    && liveEnemyCarriers.size() > 1) {
                // Kite is dead after first kill in multi-enemy because this branch is never reached
                // from the post-kill path — replicate it here as the thin-screen fallback.
                float dist = distance(myCarrier, nearestEnemyCarrier);
                float retreatX = myCarrier.px() - (nearestEnemyCarrier.px() - myCarrier.px()) / dist * CARRIER_KITE_DISTANCE;
                float retreatY = myCarrier.py() - (nearestEnemyCarrier.py() - myCarrier.py()) / dist * CARRIER_KITE_DISTANCE;
                float margin = BORDER_MARGIN + SAFE_INSET;
                retreatX = Math.max(margin, Math.min(settings.worldWidth() - margin, retreatX));
                retreatY = Math.max(margin, Math.min(settings.worldHeight() - margin, retreatY));
                sendOrder(new Order(myCarrier.id(), OrderType.MOVE,
                        (int) (retreatX - myCarrier.px()) + "|" + (int) (retreatY - myCarrier.py())));
            } else {
                sendOrder(new Order(myCarrier.id(), OrderType.PATROL));
            }
        } else if (nearestEnemyCarrier != null
                && distance(myCarrier, nearestEnemyCarrier) < CARRIER_KITE_RANGE
                && myFighters.size() < CARRIER_KITE_FIGHTER_MAX
                && liveEnemyCarriers.size() > 1) {
            // Kite only when multiple enemies present — in 1v1 kite creates an oscillation loop
            // that prevents closing range for decisive damage.
            float dist = distance(myCarrier, nearestEnemyCarrier);
            float retreatX = myCarrier.px() - (nearestEnemyCarrier.px() - myCarrier.px()) / dist * CARRIER_KITE_DISTANCE;
            float retreatY = myCarrier.py() - (nearestEnemyCarrier.py() - myCarrier.py()) / dist * CARRIER_KITE_DISTANCE;
            float margin = BORDER_MARGIN + SAFE_INSET;
            retreatX = Math.max(margin, Math.min(settings.worldWidth() - margin, retreatX));
            retreatY = Math.max(margin, Math.min(settings.worldHeight() - margin, retreatY));
            sendOrder(new Order(myCarrier.id(), OrderType.MOVE,
                    (int) (retreatX - myCarrier.px()) + "|" + (int) (retreatY - myCarrier.py())));
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

    // For each armed enemy missile within MISSILE_INTERCEPT_RANGE of our carrier,
    // assign the closest available fighter to physically move to the missile position.
    // Returns fighter-id → carrier-relative [dx, dy] of the missile.
    private Map<String, int[]> buildInterceptAssignments(
            List<Entity> armedEnemyMissiles, Entity myCarrier, List<Entity> myFighters) {
        List<Entity> threats = armedEnemyMissiles.stream()
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

    // Prefer the lowest-HP wounded enemy carrier within KILL_FOCUS_RANGE; fall back to nearest.
    private Entity selectTarget(List<Entity> liveEnemyCarriers, Entity myCarrier) {
        return liveEnemyCarriers.stream()
                .filter(e -> health(e) <= KILL_FOCUS_HP && distance(e, myCarrier) <= KILL_FOCUS_RANGE)
                .min((a, b) -> Integer.compare(health(a), health(b)))
                .or(() -> liveEnemyCarriers.stream()
                        .min((a, b) -> Float.compare(distance(a, myCarrier), distance(b, myCarrier))))
                .orElse(null);
    }

    private int[][] buildFormation(float angleRad, float radius, float arcRad) {
        int[][] offsets = new int[FORMATION_SLOTS][2];
        for (int i = 0; i < FORMATION_SLOTS; i++) {
            float t = (float) i / FORMATION_SLOTS;  // for full ring: 0 slots overlap at ends
            // for partial arc (π): span [-arc/2, +arc/2] around angleRad, non-overlapping endpoints
            float slotAngle = arcRad < 2f * (float) Math.PI
                    ? angleRad - arcRad / 2f + t * arcRad + arcRad / (2f * FORMATION_SLOTS)
                    : t * arcRad;  // full ring: evenly spaced, no offset needed
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
