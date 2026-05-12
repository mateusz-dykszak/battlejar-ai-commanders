package it.battlejar.commander.strategy;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameConfig;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.OrderSender;
import it.battlejar.commander.GameUtils;
import it.battlejar.commander.tactic.Tactic;
import it.battlejar.commander.tactic.carrier.CarrierBorderEvasionTactic;
import it.battlejar.commander.tactic.carrier.CarrierCornerTactic;
import it.battlejar.commander.tactic.fighter.BorderEvasionTactic;
import it.battlejar.commander.tactic.fighter.DistributedAttackTactic;
import it.battlejar.commander.tactic.fighter.FighterAttackTactic;
import it.battlejar.commander.tactic.fighter.FighterMissileOrientAndFireTactic;
import it.battlejar.commander.tactic.fighter.LaserDefenseTactic;
import it.battlejar.commander.tactic.fighter.MissileInterceptTactic;

import java.util.List;
import java.util.Optional;

/**
 * Active when multiple enemies remain and the carrier has not yet reached a corner. The carrier
 * retreats to the nearest corner to stay out of crossfire while enemies weaken each other.
 * Fighter priority: orient and fire missiles → intercept assigned missile (missiles spent) →
 * attack nearby enemy fighters → attack enemy carriers (distributed) → laser defense.
 * Transitions to {@link MultiEnemyHunterStrategy} once the corner is reached.
 */
public class MultiEnemySurvivalStrategy implements Strategy {

    private final List<Tactic<Entity>> fighterTactics;
    private final List<Tactic<Entity>> carrierTactics;

    public MultiEnemySurvivalStrategy() {
        this.fighterTactics = List.of(
                new BorderEvasionTactic(GameConfig.BORDER_MARGIN),
                new FighterMissileOrientAndFireTactic(),
                new MissileInterceptTactic(),
                new FighterAttackTactic(GameConfig.FIGHTER_INTRUDER_CARRIER_RANGE_SURVIVAL, true),
                new DistributedAttackTactic(),
                new LaserDefenseTactic(GameConfig.FIGHTER_LASER_RANGE)
        );

        this.carrierTactics = List.of(
                new CarrierBorderEvasionTactic(),
                new CarrierCornerTactic(GameConfig.CARRIER_CORNER_MARGIN)
        );
    }

    @Override
    public boolean applies(GameSnapshot snapshot, CommanderState state) {
        if (snapshot.liveEnemyCarriers().size() <= 1 || state.carrierReachedCorner) return false;
        // If the target corner area is already enemy-free, skip retreat and let Control take over
        float[] corner = GameUtils.getTargetCorner(state, snapshot.myCarrier(), snapshot.settings(), GameConfig.CARRIER_CORNER_MARGIN);
        return !GameUtils.isCornerAreaEmpty(corner, snapshot.liveEnemyCarriers(), snapshot.settings(), GameConfig.CONTROL_CORNER_EMPTY_MARGIN);
    }

    @Override
    public void execute(GameSnapshot snapshot, CommanderState state, OrderSender sender) {
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
}
