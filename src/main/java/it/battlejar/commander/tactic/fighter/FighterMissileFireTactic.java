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
        if (isBetweenCarrierAndTarget(fighter, snapshot.myCarrier(), target)) {
            return Optional.empty();
        }
        return Optional.of(new Order(fighter.id(), OrderType.FIRE_MISSILE));
    }

    /**
     * Returns true when the fighter sits between our carrier and the target along the
     * carrier→target axis — i.e. firing would risk the missile locking onto our carrier.
     */
    private static boolean isBetweenCarrierAndTarget(Entity fighter, Entity carrier, Entity target) {
        // Vector from carrier to target
        float axisX = target.px() - carrier.px();
        float axisY = target.py() - carrier.py();
        // Vector from carrier to fighter
        float toFighterX = fighter.px() - carrier.px();
        float toFighterY = fighter.py() - carrier.py();
        // Scalar projection of fighter onto the carrier→target axis
        float axisDotSelf = axisX * axisX + axisY * axisY;
        if (axisDotSelf == 0f) return false;
        float t = (toFighterX * axisX + toFighterY * axisY) / axisDotSelf;
        // Fighter is between carrier (t=0) and target (t=1) on that axis
        return t > 0f && t < 1f;
    }
}
