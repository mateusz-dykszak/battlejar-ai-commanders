package it.battlejar.commander.tactic.carrier;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.GameUtils;
import it.battlejar.commander.tactic.Tactic;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Moves the carrier toward the primary target at a configurable push distance. The caller
 * supplies a condition predicate — this tactic does nothing when the predicate is false,
 * letting the next tactic in the chain handle the carrier instead.
 */
public class CarrierPushTactic implements Tactic<Entity> {

    private final float pushDistance;
    private final Predicate<GameSnapshot> condition;

    public CarrierPushTactic(float pushDistance, Predicate<GameSnapshot> condition) {
        this.pushDistance = pushDistance;
        this.condition = condition;
    }

    @Override
    public Optional<Order> apply(Entity carrier, GameSnapshot snapshot, CommanderState state) {
        Entity target = snapshot.primaryTarget();
        if (target == null || !condition.test(snapshot)) return Optional.empty();
        float dist = GameUtils.distance(carrier, target);
        int mx = Math.round((target.px() - carrier.px()) / dist * pushDistance);
        int my = Math.round((target.py() - carrier.py()) / dist * pushDistance);
        return Optional.of(new Order(carrier.id(), OrderType.MOVE, mx + "|" + my));
    }
}
