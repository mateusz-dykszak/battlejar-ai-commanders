package it.battlejar.commander.tactic.fighter;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.GameUtils;
import it.battlejar.commander.tactic.Tactic;

import java.util.Optional;

public class FighterMissileFireTactic implements Tactic<Entity> {

    private final float missileRange;

    public FighterMissileFireTactic(float missileRange) {
        this.missileRange = missileRange;
    }

    @Override
    public Optional<Order> apply(Entity fighter, GameSnapshot snapshot, CommanderState state) {
        Entity target = snapshot.primaryTarget();
        if (target != null && fighter.missiles() > 0
                && GameUtils.distance(fighter, target) < missileRange) {
            return Optional.of(new Order(fighter.id(), OrderType.FIRE_MISSILE));
        }
        return Optional.empty();
    }
}
