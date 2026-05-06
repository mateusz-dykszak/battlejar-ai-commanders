package it.battlejar.commander.strategy;

import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.OrderSender;

/**
 * Decides whether this strategy applies to the current game state and executes orders for all
 * entities when it does. Strategies are evaluated in priority order; the first applicable one wins.
 */
public interface Strategy {
    boolean applies(GameSnapshot snapshot, CommanderState state);
    void execute(GameSnapshot snapshot, CommanderState state, OrderSender sender);
}
