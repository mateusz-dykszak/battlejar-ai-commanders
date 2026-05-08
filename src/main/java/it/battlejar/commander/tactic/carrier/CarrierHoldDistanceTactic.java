package it.battlejar.commander.tactic.carrier;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.GameUtils;
import it.battlejar.commander.tactic.Tactic;

import java.util.Optional;

/**
 * Closes the gap when the enemy carrier drifts farther than holdDistance units. Sends a MOVE
 * that brings the carrier to exactly holdDistance from the target. Returns empty when already
 * within holdDistance so the next tactic (e.g. patrol) takes over, and CarrierKiteTactic
 * handles the too-close case below. Together these three tactics bracket the carrier in a safe
 * operating range without rushing.
 */
public class CarrierHoldDistanceTactic implements Tactic<Entity> {

    private final float holdDistance;

    public CarrierHoldDistanceTactic(float holdDistance) {
        this.holdDistance = holdDistance;
    }

    @Override
    public Optional<Order> apply(Entity carrier, GameSnapshot snapshot, CommanderState state) {
        Entity target = snapshot.primaryTarget();
        if (target == null) return Optional.empty();
        float dist = GameUtils.distance(carrier, target);
        if (dist <= holdDistance) return Optional.empty();
        float advance = dist - holdDistance;
        int mx = Math.round((target.px() - carrier.px()) / dist * advance);
        int my = Math.round((target.py() - carrier.py()) / dist * advance);
        return Optional.of(new Order(carrier.id(), OrderType.MOVE, mx + "|" + my));
    }
}
