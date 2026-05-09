package it.battlejar.commander.tactic.fighter;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.GameUtils;
import it.battlejar.commander.tactic.Tactic;

import java.util.Optional;

/**
 * Fires a fighter's missile at the primary target carrier with no distance limit — missiles
 * auto-home and have unlimited range. Only skipped when our carrier or another enemy carrier
 * is closer to the fighter than the intended target, which would cause the missile to lock
 * onto the wrong carrier.
 */
public class FighterMissileFireTactic implements Tactic<Entity> {

    @Override
    public Optional<Order> apply(Entity fighter, GameSnapshot snapshot, CommanderState state) {
        Entity target = snapshot.primaryTarget();
        if (target == null || fighter.missiles() <= 0) {
            return Optional.empty();
        }
        float distToTarget = GameUtils.distance(fighter, target);
        // Skip if our carrier is closer — missile would home on it instead.
        if (GameUtils.distance(fighter, snapshot.myCarrier()) <= distToTarget) {
            return Optional.empty();
        }
        // Skip if any other enemy carrier is closer — missile would lock onto them.
        for (Entity carrier : snapshot.liveEnemyCarriers()) {
            if (!carrier.id().equals(target.id())
                    && GameUtils.distance(fighter, carrier) <= distToTarget) {
                return Optional.empty();
            }
        }
        return Optional.of(new Order(fighter.id(), OrderType.FIRE_MISSILE));
    }
}
