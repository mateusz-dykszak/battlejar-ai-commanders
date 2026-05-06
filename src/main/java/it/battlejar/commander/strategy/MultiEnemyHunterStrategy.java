package it.battlejar.commander.strategy;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameConfig;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.GameUtils;
import it.battlejar.commander.OrderSender;
import it.battlejar.commander.tactic.Tactic;
import it.battlejar.commander.tactic.carrier.*;
import it.battlejar.commander.tactic.fighter.*;

import java.util.List;
import java.util.Optional;

public class MultiEnemyHunterStrategy implements Strategy {

    private final List<Tactic<Entity>> fighterTactics;
    private final List<Tactic<Entity>> carrierTactics;

    public MultiEnemyHunterStrategy() {
        int halfThreshold = GameConfig.AGGRESSION_FIGHTER_THRESHOLD / 2;
        int fullThreshold = GameConfig.AGGRESSION_FIGHTER_THRESHOLD;

        this.fighterTactics = List.of(
                new BorderEvasionTactic(GameConfig.BORDER_MARGIN),
                new MissileInterceptTactic(),
                new FighterMissileFireTactic(GameConfig.FIGHTER_MISSILE_RANGE),
                new LaserDefenseTactic(GameConfig.FIGHTER_LASER_RANGE),
                new FormationMoveTactic(GameConfig.FORMATION_THRESHOLD),
                new FighterAttackTactic(GameConfig.FIGHTER_INTRUDER_CARRIER_RANGE)
        );

        this.carrierTactics = List.of(
                new CarrierBorderEvasionTactic(GameConfig.BORDER_MARGIN, GameConfig.SAFE_INSET),
                new CarrierDodgeTactic(GameConfig.CARRIER_DODGE_RANGE, GameConfig.CARRIER_DODGE_DISTANCE,
                        GameConfig.BORDER_MARGIN, GameConfig.SAFE_INSET),
                new CarrierMissileFireTactic(GameConfig.CARRIER_MISSILE_FIRE_INTERVAL_MS),
                // Pre-kill push: wounded enemy, haven't killed anyone yet
                new CarrierPushTactic(GameConfig.CARRIER_PUSH_DISTANCE,
                        s -> !s.hasKilledEnemy()
                                && s.primaryTarget() != null
                                && GameUtils.health(s.primaryTarget()) <= GameConfig.KILL_FOCUS_HP
                                && s.myActiveFighters().size() >= halfThreshold),
                // Post-kill push: already eliminated one, enough fighters to press
                new CarrierPushTactic(GameConfig.CARRIER_PUSH_DISTANCE,
                        s -> s.hasKilledEnemy() && s.myActiveFighters().size() >= fullThreshold),
                new CarrierCornerTactic(GameConfig.BORDER_MARGIN, GameConfig.SAFE_INSET,
                        GameConfig.CARRIER_CORNER_THRESHOLD)
        );
    }

    @Override
    public boolean applies(GameSnapshot snapshot, CommanderState state) {
        return snapshot.liveEnemyCarriers().size() > 1 && state.carrierReachedCorner;
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
