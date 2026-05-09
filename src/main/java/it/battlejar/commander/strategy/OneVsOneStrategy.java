package it.battlejar.commander.strategy;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameConfig;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.OrderSender;
import it.battlejar.commander.tactic.Tactic;
import it.battlejar.commander.GameUtils;
import it.battlejar.commander.tactic.carrier.CarrierBorderEvasionTactic;
import it.battlejar.commander.tactic.carrier.CarrierFighterlessTactic;
import it.battlejar.commander.tactic.carrier.CarrierHoldDistanceTactic;
import it.battlejar.commander.tactic.carrier.CarrierKiteTactic;
import it.battlejar.commander.tactic.carrier.CarrierMissileFireTactic;
import it.battlejar.commander.tactic.carrier.CarrierPatrolTactic;
import it.battlejar.commander.tactic.carrier.CarrierPushTactic;
import it.battlejar.commander.tactic.fighter.BorderEvasionTactic;
import it.battlejar.commander.tactic.fighter.FighterAttackTactic;
import it.battlejar.commander.tactic.fighter.FighterMissileFireTactic;
import it.battlejar.commander.tactic.fighter.LaserDefenseTactic;
import it.battlejar.commander.tactic.fighter.MissileInterceptTactic;

import java.util.List;
import java.util.Optional;

/**
 * Active when exactly one enemy carrier remains. Fighters hold a forward 180° arc toward the
 * enemy; the carrier kites to avoid collision and pushes aggressively once it has scored a kill.
 */
public class OneVsOneStrategy implements Strategy {

    private final List<Tactic<Entity>> fighterTactics;
    private final List<Tactic<Entity>> carrierTactics;

    public OneVsOneStrategy() {
        this.fighterTactics = List.of(
                new BorderEvasionTactic(GameConfig.BORDER_MARGIN_ACTIVE),
                new MissileInterceptTactic(),
                new FighterMissileFireTactic(),
                new LaserDefenseTactic(GameConfig.FIGHTER_LASER_RANGE),
                new FighterAttackTactic(GameConfig.FIGHTER_INTRUDER_CARRIER_RANGE_1V1)
        );

        this.carrierTactics = List.of(
                new CarrierBorderEvasionTactic(),
                new CarrierMissileFireTactic(GameConfig.CARRIER_MISSILE_FIRE_INTERVAL_MS),
                new CarrierFighterlessTactic(),
                new CarrierKiteTactic(GameConfig.CARRIER_MIN_SEPARATION_1V1, GameConfig.CARRIER_KITE_DISTANCE,
                        GameConfig.BORDER_MARGIN, GameConfig.SAFE_INSET),
                // Ram: all-out charge when overwhelmingly superior — healthy, enemy wounded, fighters ≥6
                new CarrierPushTactic(GameConfig.CARRIER_PUSH_DISTANCE_1V1,
                        s -> s.primaryTarget() != null
                                && GameUtils.health(s.myCarrier()) > 600
                                && GameUtils.health(s.primaryTarget()) < 400
                                && s.myActiveFighters().size() >= 6),
                // Push: advance when clearly winning on both HP and fighter count
                new CarrierPushTactic(GameConfig.CARRIER_PUSH_DISTANCE_1V1,
                        s -> s.hasKilledEnemy()
                                && s.primaryTarget() != null
                                && GameUtils.health(s.myCarrier()) - GameUtils.health(s.primaryTarget()) > 300
                                && s.myActiveFighters().size() > s.activeEnemyFighters().size() * 1.5f),
                // Default: hold at safe distance so fighters do the damage
                new CarrierHoldDistanceTactic(GameConfig.CARRIER_HOLD_DISTANCE_1V1),
                new CarrierPatrolTactic()
        );
    }

    @Override
    public boolean applies(GameSnapshot snapshot, CommanderState state) {
        return snapshot.liveEnemyCarriers().size() == 1;
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
