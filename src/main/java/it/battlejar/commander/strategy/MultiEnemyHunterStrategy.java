package it.battlejar.commander.strategy;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameConfig;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.OrderSender;
import it.battlejar.commander.tactic.Tactic;
import it.battlejar.commander.tactic.carrier.CarrierBorderEvasionTactic;
import it.battlejar.commander.tactic.carrier.CarrierCornerTactic;
import it.battlejar.commander.tactic.carrier.CarrierMissileFireTactic;
import it.battlejar.commander.tactic.carrier.CarrierPushTactic;
import it.battlejar.commander.tactic.fighter.BorderEvasionTactic;
import it.battlejar.commander.tactic.fighter.DistributedAttackTactic;
import it.battlejar.commander.tactic.fighter.FighterAttackTactic;
import it.battlejar.commander.tactic.fighter.FighterMissileFireTactic;
import it.battlejar.commander.tactic.fighter.LaserDefenseTactic;
import it.battlejar.commander.tactic.fighter.MissileInterceptTactic;

import java.util.List;
import java.util.Optional;

/**
 * Active when multiple enemies remain and the carrier is already in a corner. Pushes toward a
 * wounded or already-targeted enemy to finish them off; falls back to the corner when push
 * conditions are not met. Fighters are split into interceptors (odd entity-ID suffix) that engage
 * incoming enemy fighters, and strikers (even suffix) that press directly toward the primary
 * enemy carrier. Eliminates enemies one by one until only one is left, at which point
 * {@link OneVsOneStrategy} takes over.
 */
public class MultiEnemyHunterStrategy implements Strategy {

    private final List<Tactic<Entity>> interceptorTactics;
    private final List<Tactic<Entity>> strikerTactics;
    private final List<Tactic<Entity>> carrierTactics;

    public MultiEnemyHunterStrategy() {
        int fullThreshold = GameConfig.AGGRESSION_FIGHTER_THRESHOLD;

        this.interceptorTactics = List.of(
                new BorderEvasionTactic(GameConfig.BORDER_MARGIN_ACTIVE),
                new MissileInterceptTactic(),
                new FighterMissileFireTactic(),
                new LaserDefenseTactic(GameConfig.FIGHTER_LASER_RANGE),
                new FighterAttackTactic(GameConfig.FIGHTER_INTRUDER_CARRIER_RANGE)
        );

        this.strikerTactics = List.of(
                new BorderEvasionTactic(GameConfig.BORDER_MARGIN_ACTIVE),
                new MissileInterceptTactic(),
                new FighterMissileFireTactic(),
                new LaserDefenseTactic(GameConfig.FIGHTER_LASER_RANGE),
                new DistributedAttackTactic()
        );

        this.carrierTactics = List.of(
                new CarrierBorderEvasionTactic(),
                new CarrierMissileFireTactic(GameConfig.CARRIER_MISSILE_FIRE_INTERVAL_MS),
                // Push only after scoring a kill — we have a numbers advantage
                new CarrierPushTactic(GameConfig.CARRIER_PUSH_DISTANCE,
                        s -> s.hasKilledEnemy() && s.myActiveFighters().size() >= fullThreshold),
                new CarrierCornerTactic(GameConfig.CARRIER_CORNER_MARGIN)
        );
    }

    @Override
    public boolean applies(GameSnapshot snapshot, CommanderState state) {
        return snapshot.liveEnemyCarriers().size() > 1 && state.carrierReachedCorner;
    }

    @Override
    public void execute(GameSnapshot snapshot, CommanderState state, OrderSender sender) {
        for (Entity fighter : snapshot.myActiveFighters()) {
            List<Tactic<Entity>> tactics = isInterceptor(fighter) ? interceptorTactics : strikerTactics;
            for (Tactic<Entity> tactic : tactics) {
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

    private static boolean isInterceptor(Entity fighter) {
        try {
            String[] parts = fighter.id().split("-");
            return Integer.parseInt(parts[parts.length - 1]) % 2 != 0;
        } catch (Exception e) {
            return false;
        }
    }
}
