package it.battlejar.commander.tactic.fighter;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.GameUtils;
import it.battlejar.commander.tactic.Tactic;

import java.util.Optional;

public class LaserDefenseTactic implements Tactic<Entity> {

    private final float laserRange;

    public LaserDefenseTactic(float laserRange) {
        this.laserRange = laserRange;
    }

    @Override
    public Optional<Order> apply(Entity fighter, GameSnapshot snapshot, CommanderState state) {
        boolean missileNear = snapshot.armedEnemyMissiles().stream()
                .anyMatch(m -> GameUtils.distance(m, fighter) < laserRange);
        if (missileNear) {
            return Optional.of(new Order(fighter.id(), OrderType.TARGET, "M"));
        }
        return Optional.empty();
    }
}
