package it.battlejar.commander.tactic.carrier;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.tactic.Tactic;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Issues a targeted ATTACK order against the primary enemy carrier when a caller-supplied
 * condition is met. Used in 1v1 after a kill has been scored but the fighter count is too low
 * to justify a full push — the carrier contributes damage directly instead of just patrolling.
 */
public class CarrierAttackTactic implements Tactic<Entity> {

    private final Predicate<GameSnapshot> condition;

    public CarrierAttackTactic(Predicate<GameSnapshot> condition) {
        this.condition = condition;
    }

    @Override
    public Optional<Order> apply(Entity carrier, GameSnapshot snapshot, CommanderState state) {
        if (!condition.test(snapshot)) return Optional.empty();
        Entity target = snapshot.primaryTarget();
        if (target != null) {
            return Optional.of(new Order(carrier.id(), OrderType.ATTACK, target.id()));
        }
        return Optional.empty();
    }
}
