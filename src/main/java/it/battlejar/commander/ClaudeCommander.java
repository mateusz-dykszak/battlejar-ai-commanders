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
    private static final float FORMATION_THRESHOLD = 100f;
    private static final float FIGHTER_MISSILE_RANGE = 150f;
    private static final float FIGHTER_LASER_RANGE = 150f;   // per-fighter proximity for TARGET "M" defense
    private static final float MISSILE_INTERCEPT_RANGE = 80f;  // physical move-to-intercept; beyond this rely on lasers
    private static final float MISSILE_TARGET_RANGE = 300f;    // carrier-relative range for intercept assignments
    private static final float CARRIER_DODGE_RANGE = 200f;
    private static final float CARRIER_DODGE_DISTANCE = 60f;
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
    private static final float CARRIER_KITE_DISTANCE = 80f;  // retreat distance for 1v1 collision avoidance
    private static final float CARRIER_CORNER_THRESHOLD = 40f; // within this distance of corner, switch to patrol
    private static final int KILL_FOCUS_HP = 750;        // target enemy carrier if HP ≤ this; missiles deal ~250 HP so post-barrage target is at 750
    private static final float KILL_FOCUS_RANGE = 350f;  // only focus-fire within this distance
    private static final float CARRIER_MIN_SEPARATION_1V1 = 80f; // floor distance in 1v1 to avoid collision
    // Enemy fighters are only considered intruders when within this distance of OUR CARRIER.
    // Using carrier-relative (not fighter-relative) prevents all fighters from locking into
    // defensive mode when 30+ enemy fighters fill the area at 100–150 units — outside the ring
    // but within fighter-relative 120 units. Fighters outside this ring keep attacking the carrier.
    private static final float FIGHTER_INTRUDER_CARRIER_RANGE = 75f;

    private static final long CARRIER_MISSILE_FIRE_INTERVAL_MS = 800L;

    private final Map<String, Long> lastOrderTime = new HashMap<>();
    // last known angle to current target carrier; reused when no enemy is visible
    private float lastFormationAngle = 0f;
    // enemy carrier count at game start; used to detect when we have achieved a kill
    private int initialEnemyCarrierCount = 0;
    private long lastCarrierMissileFireMs = 0;

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

        // Precompute once; per-fighter proximity check replaces the old shared flag.
        List<Entity> armedEnemyMissiles = entities.stream()
                .filter(e -> e.type() == Entity.Type.MISSILE)
                .filter(e -> !myColor.name().equals(e.color()))
                .filter(e -> "A".equals(e.status()))
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
            } else if (intercept.containsKey(fighter.id())) {
                int[] mPos = intercept.get(fighter.id());
                sendOrder(new Order(fighter.id(), OrderType.MOVE, mPos[0] + "|" + mPos[1]));
            } else if (nearestEnemyCarrier != null && fighter.missiles() > 0
                    && distance(fighter, nearestEnemyCarrier) < FIGHTER_MISSILE_RANGE) {
                // Fire missiles at close enemy carrier before switching to laser defense —
                // burst damage on the carrier stops future missile launches entirely.
                sendOrder(new Order(fighter.id(), OrderType.FIRE_MISSILE));
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
        entities.stream()
                .filter(e -> e.type() == Entity.Type.FIGHTER)
                .filter(e -> myColor.name().equals(e.color()))
                .filter(e -> "C".equals(e.status()))
                .forEach(docked -> {
                    int[] offset = formationOffset(docked, formation);
                    sendOrder(new Order(docked.id(), OrderType.MOVE, offset[0] + "|" + offset[1]));
                });

        int[] carrierDodge = computeCarrierDodge(entities, myCarrier);

        if (isNearBorder(myCarrier)) {
            // Border safety first — carrier near edge must move inward before anything else.
            float inner = BORDER_MARGIN + SAFE_INSET;
            float safeX = Math.max(inner, Math.min(settings.worldWidth() - inner, myCarrier.px()));
            float safeY = Math.max(inner, Math.min(settings.worldHeight() - inner, myCarrier.py()));
            sendOrder(new Order(myCarrier.id(), OrderType.MOVE,
                    (int) (safeX - myCarrier.px()) + "|" + (int) (safeY - myCarrier.py())));
        } else if (carrierDodge != null) {
            sendOrder(new Order(myCarrier.id(), OrderType.MOVE,
                    carrierDodge[0] + "|" + carrierDodge[1]));
        } else if (nearestEnemyCarrier != null && myCarrier.missiles() > 0
                && System.currentTimeMillis() - lastCarrierMissileFireMs >= CARRIER_MISSILE_FIRE_INTERVAL_MS) {
            // Rate-limited to 800 ms so movement orders execute on the other ~5 ticks per second.
            lastCarrierMissileFireMs = System.currentTimeMillis();
            float dx = nearestEnemyCarrier.px() - myCarrier.px();
            float dy = nearestEnemyCarrier.py() - myCarrier.py();
            sendOrder(new Order(myCarrier.id(), OrderType.FIRE_MISSILE, (int) dx + "|" + (int) dy));
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
            } else {
                // Multi-enemy post-kill: return to corner to minimise attack surface.
                sendCornerOrder(myCarrier, liveEnemyCarriers);
            }
        } else if (liveEnemyCarriers.size() > 1 && hasEnemies) {
            // Multi-enemy default: hug closest corner to minimise attack surface and face fighters outward.
            sendCornerOrder(myCarrier, liveEnemyCarriers);
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

    private void sendCornerOrder(Entity carrier, List<Entity> enemies) {
        int[] offset = farthestFromEnemiesCornerOffset(carrier, enemies);
        float dist = (float) Math.sqrt((float) offset[0] * offset[0] + (float) offset[1] * offset[1]);
        if (dist > CARRIER_CORNER_THRESHOLD) {
            sendOrder(new Order(carrier.id(), OrderType.MOVE, offset[0] + "|" + offset[1]));
        } else {
            sendOrder(new Order(carrier.id(), OrderType.PATROL));
        }
    }

    // Pick the corner that maximises the sum of distances to all live enemy carriers.
    // This moves us away from the threat cluster rather than toward the geometrically nearest corner.
    private int[] farthestFromEnemiesCornerOffset(Entity carrier, List<Entity> enemies) {
        float margin = BORDER_MARGIN + SAFE_INSET;
        float ww = settings.worldWidth(), wh = settings.worldHeight();
        float[][] corners = {
            {margin, margin},
            {ww - margin, margin},
            {margin, wh - margin},
            {ww - margin, wh - margin}
        };
        float bestScore = -1f;
        float[] best = corners[0];
        for (float[] c : corners) {
            float score = 0f;
            for (Entity enemy : enemies) {
                float dx = c[0] - enemy.px();
                float dy = c[1] - enemy.py();
                score += (float) Math.sqrt(dx * dx + dy * dy);
            }
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return new int[]{Math.round(best[0] - carrier.px()), Math.round(best[1] - carrier.py())};
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
