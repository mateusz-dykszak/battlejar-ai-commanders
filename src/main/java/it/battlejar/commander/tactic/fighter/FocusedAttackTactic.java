package it.battlejar.commander.tactic.fighter;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.tactic.Tactic;

import java.util.Optional;

/**
 * Sends ATTACK to the entity identified by {@link CommanderState#controlFocusTargetId}.
 * Falls back to {@link GameSnapshot#primaryTarget()} when the focus target is gone or unset.
 */
public class FocusedAttackTactic implements Tactic<Entity> {

    @Override
    public Optional<Order> apply(Entity fighter, GameSnapshot snapshot, CommanderState state) {
        Entity target = null;
        if (state.controlFocusTargetId != null) {
            target = snapshot.liveEnemyCarriers().stream()
                    .filter(e -> e.id().equals(state.controlFocusTargetId))
                    .findFirst().orElse(null);
        }
        if (target == null) {
            target = snapshot.primaryTarget();
        }
        if (target == null) {
            return snapshot.hasEnemies()
                    ? Optional.of(new Order(fighter.id(), OrderType.ATTACK))
                    : Optional.empty();
        }
        return Optional.of(new Order(fighter.id(), OrderType.ATTACK, target.id()));
    }
}
