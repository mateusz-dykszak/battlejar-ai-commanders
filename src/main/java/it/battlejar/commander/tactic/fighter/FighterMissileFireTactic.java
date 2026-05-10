package it.battlejar.commander.tactic.fighter;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.GameConfig;
import it.battlejar.commander.GameUtils;
import it.battlejar.commander.tactic.Tactic;

import java.util.Optional;

/**
 * Fires a fighter's missile at the primary target carrier. Skipped when the target is closer
 * than {@link GameConfig#FIGHTER_MISSILE_MIN_FIRE_RANGE} (missile wouldn't arm in time), when
 * our carrier is closer (missile would home on it), or when another enemy carrier is closer
 * (missile would lock onto the wrong target).
 */
public class FighterMissileFireTactic implements Tactic<Entity> {

    @Override
    public Optional<Order> apply(Entity fighter, GameSnapshot snapshot, CommanderState state) {
        Entity target = snapshot.primaryTarget();
        if (target == null || fighter.missiles() <= 0) {
            return Optional.empty();
        }
        float distToTarget = GameUtils.distance(fighter, target);
        if (distToTarget < GameConfig.FIGHTER_MISSILE_MIN_FIRE_RANGE) return Optional.empty();
        // Skip if our carrier is closer — missile would home on it instead.
        if (GameUtils.distance(fighter, snapshot.myCarrier()) <= distToTarget) {
            return Optional.empty();
        }
        // Skip if any other enemy carrier is closer — missile would lock onto them.
        for (Entity carrier : snapshot.liveEnemyCarriers()) {
            if (!carrier.id().equals(target.id())
                    && GameUtils.distance(fighter, carrier) <= distToTarget) {
                return Optional.empty();
            }
        }
        return Optional.of(new Order(fighter.id(), OrderType.FIRE_MISSILE));
    }
}
