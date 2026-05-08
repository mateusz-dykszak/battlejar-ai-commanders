package it.battlejar.commander.tactic.fighter;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.GameUtils;
import it.battlejar.commander.tactic.Tactic;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Spreads striker fire across all live enemy carriers. Each striker picks a target by
 * {@code fighterIdSuffix % liveEnemyCarriers.size()} (carriers sorted by distance), so damage is
 * distributed rather than piling onto one carrier while others escape unharmed.
 */
public class DistributedAttackTactic implements Tactic<Entity> {

    @Override
    public Optional<Order> apply(Entity fighter, GameSnapshot snapshot, CommanderState state) {
        List<Entity> targets = snapshot.liveEnemyCarriers().stream()
                .sorted(Comparator.comparingDouble(c -> GameUtils.distance(c, snapshot.myCarrier())))
                .toList();
        if (targets.isEmpty()) {
            if (snapshot.hasEnemies()) {
                return Optional.of(new Order(fighter.id(), OrderType.ATTACK));
            }
            return Optional.empty();
        }
        int suffix = parseSuffix(fighter.id());
        Entity target = targets.get(suffix % targets.size());
        return Optional.of(new Order(fighter.id(), OrderType.ATTACK, target.id()));
    }

    private static int parseSuffix(String id) {
        try {
            String[] parts = id.split("-");
            return Integer.parseInt(parts[parts.length - 1]);
        } catch (Exception e) {
            return 0;
        }
    }
}
