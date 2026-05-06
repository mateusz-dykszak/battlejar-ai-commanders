package it.battlejar.commander.tactic.carrier;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.tactic.Tactic;

import java.util.Optional;

/**
 * Last-resort carrier attack when we have no active fighters. The carrier targets the primary
 * enemy carrier directly, or issues a generic ATTACK if no specific target is available.
 */
public class CarrierFighterlessTactic implements Tactic<Entity> {

    @Override
    public Optional<Order> apply(Entity carrier, GameSnapshot snapshot, CommanderState state) {
        if (!snapshot.myActiveFighters().isEmpty()) return Optional.empty();
        Entity target = snapshot.primaryTarget();
        if (target != null) {
            return Optional.of(new Order(carrier.id(), OrderType.ATTACK, target.id()));
        }
        if (snapshot.hasEnemies()) {
            return Optional.of(new Order(carrier.id(), OrderType.ATTACK));
        }
        return Optional.empty();
    }
}
