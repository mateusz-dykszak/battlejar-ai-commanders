package it.battlejar.commander.strategy;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameConfig;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.GameUtils;
import it.battlejar.commander.OrderSender;
import it.battlejar.commander.tactic.Tactic;
import it.battlejar.commander.tactic.carrier.CarrierBorderCruiseTactic;
import it.battlejar.commander.tactic.carrier.CarrierBorderEvasionTactic;
import it.battlejar.commander.tactic.carrier.CarrierFocusedMissileFireTactic;
import it.battlejar.commander.tactic.fighter.BorderEvasionTactic;
import it.battlejar.commander.tactic.fighter.FighterAttackTactic;
import it.battlejar.commander.tactic.fighter.FighterMissileOrientAndFireTactic;
import it.battlejar.commander.tactic.fighter.FocusedAttackTactic;
import it.battlejar.commander.tactic.fighter.LaserDefenseTactic;
import it.battlejar.commander.tactic.fighter.MissileInterceptTactic;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Activates when multiple enemies remain AND the carrier's target corner is clear: no enemy
 * carrier is within {@link GameConfig#CONTROL_CORNER_EMPTY_MARGIN} units of either border that
 * forms the corner. In this phase the carrier slowly cruises along the free horizontal border
 * while fighters focus concentrated fire on a single selected target.
 *
 * <p>Target selection priority:
 * <ol>
 *   <li>If the strongest or closest enemy has more than half its fighters near our carrier or
 *       heading toward it, attack that one first.</li>
 *   <li>Otherwise, attack the enemy with the most HP.</li>
 *   <li>If the two candidates' HP differ by ≤ 10%, attack the closest instead.</li>
 * </ol>
 *
 * <p>Exits (sets {@link CommanderState#controlExhausted}) when an enemy is destroyed during this
 * phase, or when the carrier has traversed more than 3/4 of the patrol border. After exit,
 * {@link MultiEnemyHunterStrategy} takes over.
 */
public class MultiEnemyControlStrategy implements Strategy {

    private final List<Tactic<Entity>> fighterTactics;
    private final List<Tactic<Entity>> carrierTactics;

    public MultiEnemyControlStrategy() {
        this.fighterTactics = List.of(
                new BorderEvasionTactic(GameConfig.BORDER_MARGIN_ACTIVE),
                new FighterMissileOrientAndFireTactic(),
                new MissileInterceptTactic(),
                new FighterAttackTactic(GameConfig.FIGHTER_INTRUDER_CARRIER_RANGE, true),
                new FocusedAttackTactic(),
                new LaserDefenseTactic(GameConfig.FIGHTER_LASER_RANGE)
        );

        this.carrierTactics = List.of(
                new CarrierBorderEvasionTactic(),
                new CarrierFocusedMissileFireTactic(GameConfig.CARRIER_MISSILE_FIRE_INTERVAL_MS),
                new CarrierBorderCruiseTactic(GameConfig.CARRIER_CORNER_MARGIN, GameConfig.CARRIER_CRUISE_SPEED)
        );
    }

    @Override
    public boolean applies(GameSnapshot snapshot, CommanderState state) {
        if (state.controlExhausted) return false;
        if (snapshot.liveEnemyCarriers().size() <= 1) return false;
        float[] corner = GameUtils.getTargetCorner(state, snapshot.myCarrier(), snapshot.settings(), GameConfig.CARRIER_CORNER_MARGIN);
        return GameUtils.isCornerAreaEmpty(corner, snapshot.liveEnemyCarriers(), snapshot.settings(), GameConfig.CONTROL_CORNER_EMPTY_MARGIN);
    }

    @Override
    public void execute(GameSnapshot snapshot, CommanderState state, OrderSender sender) {
        // Initialise state on the first tick we run
        if (state.controlPatrolStart == null) {
            Entity c = snapshot.myCarrier();
            state.controlPatrolStart = new float[]{c.px(), c.py()};
            state.controlInitialEnemyCount = snapshot.liveEnemyCarriers().size();
            // Signal that we are past the retreat phase so Hunter can take over when we exhaust
            state.carrierReachedCorner = true;
        }

        // Check exit: an enemy was killed while in control
        if (snapshot.liveEnemyCarriers().size() < state.controlInitialEnemyCount) {
            state.controlExhausted = true;
        }

        // Check exit: carrier has traversed > 3/4 of the patrol border
        if (!state.controlExhausted) {
            float[] corner = GameUtils.getTargetCorner(state, snapshot.myCarrier(), snapshot.settings(), GameConfig.CARRIER_CORNER_MARGIN);
            boolean cornerNearRight = corner[0] > snapshot.settings().worldWidth() / 2f;
            float cx = snapshot.myCarrier().px();
            float W = snapshot.settings().worldWidth();
            if (cornerNearRight && cx < W * 0.25f) state.controlExhausted = true;
            if (!cornerNearRight && cx > W * 0.75f) state.controlExhausted = true;
        }

        // Set focused target for this tick (used by FocusedAttackTactic and CarrierFocusedMissileFireTactic)
        Entity focusTarget = selectControlTarget(snapshot);
        state.controlFocusTargetId = focusTarget != null ? focusTarget.id() : null;

        for (Entity fighter : snapshot.myActiveFighters()) {
            for (Tactic<Entity> tactic : fighterTactics) {
                Optional<Order> order = tactic.apply(fighter, snapshot, state);
                if (order.isPresent()) {
                    sender.send(order.get());
                    break;
                }
            }
        }
        for (Tactic<Entity> tactic : carrierTactics) {
            Optional<Order> order = tactic.apply(snapshot.myCarrier(), snapshot, state);
            if (order.isPresent()) {
                sender.send(order.get());
                break;
            }
        }
    }

    private Entity selectControlTarget(GameSnapshot snapshot) {
        List<Entity> enemies = snapshot.liveEnemyCarriers();
        if (enemies.isEmpty()) return null;
        if (enemies.size() == 1) return enemies.get(0);

        Entity myCarrier = snapshot.myCarrier();

        Entity strongest = enemies.stream()
                .max(Comparator.comparingInt(GameUtils::health))
                .orElseThrow();
        Entity closest = enemies.stream()
                .min(Comparator.comparingDouble(e -> (double) GameUtils.distance(e, myCarrier)))
                .orElseThrow();

        // Prefer whichever of the two candidates is actively attacking us
        for (Entity candidate : List.of(strongest, closest)) {
            if (isAttackingUs(candidate, snapshot)) return candidate;
        }

        // Same enemy wins both categories
        if (strongest.id().equals(closest.id())) return strongest;

        // Within 10% HP → attack closest; otherwise attack the stronger one
        int strongestHP = GameUtils.health(strongest);
        int closestHP   = GameUtils.health(closest);
        if (Math.abs(strongestHP - closestHP) * 10 <= strongestHP) return closest;
        return strongest;
    }

    /** True when more than half of the enemy's active fighters are near our carrier or heading toward it. */
    private boolean isAttackingUs(Entity enemy, GameSnapshot snapshot) {
        String color = enemy.color();
        Entity myCarrier = snapshot.myCarrier();
        List<Entity> theirFighters = snapshot.activeEnemyFighters().stream()
                .filter(f -> color.equals(f.color()))
                .toList();
        if (theirFighters.isEmpty()) return false;
        long threatening = theirFighters.stream().filter(f -> {
            if (GameUtils.distance(f, myCarrier) < GameConfig.FIGHTER_INTRUDER_CARRIER_RANGE_SURVIVAL) return true;
            // Velocity dot product: positive = heading toward our carrier
            float dvx = myCarrier.px() - f.px();
            float dvy = myCarrier.py() - f.py();
            float dist = GameUtils.distance(f, myCarrier);
            if (dist < 1f) return false;
            return (f.vx() * dvx + f.vy() * dvy) / dist > 0;
        }).count();
        return threatening * 2 > theirFighters.size();
    }
}
