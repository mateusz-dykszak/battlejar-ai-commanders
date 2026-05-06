package it.battlejar.commander.tactic;

import it.battlejar.api.Order;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameSnapshot;

import java.util.Optional;

@FunctionalInterface
public interface Tactic<T> {
    Optional<Order> apply(T subject, GameSnapshot snapshot, CommanderState state);
}
