package it.battlejar.commander.tactic;

import it.battlejar.api.Order;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameSnapshot;

import java.util.Optional;

@FunctionalInterface
/**
 * A single, focused decision rule for one entity. Returns a non-empty {@link java.util.Optional}
 * with the chosen order to stop the tactic chain, or empty to let the next tactic run.
 *
 * @param <T> the entity type this tactic operates on (typically {@link it.battlejar.api.Entity})
 */
public interface Tactic<T> {
    Optional<Order> apply(T subject, GameSnapshot snapshot, CommanderState state);
}
