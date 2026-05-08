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
 * Pre-fire positioning for strikers: moves the fighter to a point just past the enemy carrier
 * (on the far side relative to our carrier) so that when FighterMissileFireTactic fires, the
 * enemy is the closest carrier and our missile homes to it reliably.
 *
 * Activates when the fighter has missiles, is within engagementRange of the primary target, and
 * is still on the near side (distance to target >= distance to our carrier). Defers once the
 * fighter crosses to the far side so FighterMissileFireTactic can take the shot.
 */
public class StrikerPositioningTactic implements Tactic<Entity> {

    private static final float OVERSHOOT = 40f;

    private final float engagementRange;

    public StrikerPositioningTactic(float engagementRange) {
        this.engagementRange = engagementRange;
    }

    @Override
    public Optional<Order> apply(Entity fighter, GameSnapshot snapshot, CommanderState state) {
        Entity target = snapshot.primaryTarget();
        if (target == null || fighter.missiles() <= 0) return Optional.empty();

        float distToTarget = GameUtils.distance(fighter, target);
        if (distToTarget > engagementRange) return Optional.empty();

        // Already on the far side — defer to FighterMissileFireTactic
        if (distToTarget < GameUtils.distance(fighter, snapshot.myCarrier())) return Optional.empty();

        // Move to a point OVERSHOOT units past the enemy carrier along the line from our carrier
        Entity myCarrier = snapshot.myCarrier();
        float dx = target.px() - myCarrier.px();
        float dy = target.py() - myCarrier.py();
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 1f) return Optional.empty();

        float ndx = dx / dist;
        float ndy = dy / dist;
        int moveX = Math.round(dx + ndx * OVERSHOOT);
        int moveY = Math.round(dy + ndy * OVERSHOOT);

        return Optional.of(new Order(fighter.id(), OrderType.MOVE, moveX + "|" + moveY));
    }
}
