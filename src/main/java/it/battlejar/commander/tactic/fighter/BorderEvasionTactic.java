package it.battlejar.commander.tactic.fighter;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.GameUtils;
import it.battlejar.commander.tactic.Tactic;

import java.util.Optional;

public class BorderEvasionTactic implements Tactic<Entity> {

    private final float borderMargin;

    public BorderEvasionTactic(float borderMargin) {
        this.borderMargin = borderMargin;
    }

    @Override
    public Optional<Order> apply(Entity fighter, GameSnapshot snapshot, CommanderState state) {
        if (GameUtils.isNearBorder(fighter, snapshot.settings(), borderMargin)) {
            return Optional.of(new Order(fighter.id(), OrderType.MOVE, "0|0"));
        }
        return Optional.empty();
    }
}
