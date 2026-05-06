package it.battlejar.commander.strategy;

import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.OrderSender;

public interface Strategy {
    boolean applies(GameSnapshot snapshot, CommanderState state);
    void execute(GameSnapshot snapshot, CommanderState state, OrderSender sender);
}
