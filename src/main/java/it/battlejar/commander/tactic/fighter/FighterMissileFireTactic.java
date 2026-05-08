package it.battlejar.commander.tactic.fighter;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.GameUtils;
import it.battlejar.commander.tactic.Tactic;

import java.util.Optional;

/**
 * Fires a fighter's missile at the primary target carrier when the fighter is within missile
 * range and is not between our carrier and the enemy (which would risk locking onto our carrier).
 * Prioritised before laser defence because burst damage on the carrier stops it from
 * launching more missiles entirely.
 */
public class FighterMissileFireTactic implements Tactic<Entity> {

    private final float missileRange;

    public FighterMissileFireTactic(float missileRange) {
        this.missileRange = missileRange;
    }

    @Override
    public Optional<Order> apply(Entity fighter, GameSnapshot snapshot, CommanderState state) {
        Entity target = snapshot.primaryTarget();
        if (target == null || fighter.missiles() <= 0
                || GameUtils.distance(fighter, target) >= missileRange) {
            return Optional.empty();
        }
        // FIRE_MISSILE auto-homes to the nearest carrier. Only fire if the intended target is
        // the closest carrier to the fighter — otherwise the missile locks onto a closer carrier.
        float distToTarget = GameUtils.distance(fighter, target);
        if (GameUtils.distance(fighter, snapshot.myCarrier()) <= distToTarget) {
            return Optional.empty();
        }
        for (Entity carrier : snapshot.liveEnemyCarriers()) {
            if (!carrier.id().equals(target.id())
                    && GameUtils.distance(fighter, carrier) <= distToTarget) {
                return Optional.empty();
            }
        }
        return Optional.of(new Order(fighter.id(), OrderType.FIRE_MISSILE));
    }
}
