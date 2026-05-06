package it.battlejar.commander.tactic.fighter;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.tactic.Tactic;

import java.util.Optional;

public class MissileInterceptTactic implements Tactic<Entity> {

    @Override
    public Optional<Order> apply(Entity fighter, GameSnapshot snapshot, CommanderState state) {
        int[] mPos = snapshot.interceptMap().get(fighter.id());
        if (mPos != null) {
            return Optional.of(new Order(fighter.id(), OrderType.MOVE, mPos[0] + "|" + mPos[1]));
        }
        return Optional.empty();
    }
}
