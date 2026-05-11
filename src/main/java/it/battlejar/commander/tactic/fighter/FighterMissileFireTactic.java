package it.battlejar.commander.tactic.fighter;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.GameConfig;
import it.battlejar.commander.GameUtils;
import it.battlejar.commander.tactic.Tactic;

import java.util.Comparator;
import java.util.Optional;

/**
 * Fires a fighter's missile whenever an enemy carrier exists beyond arming range and our
 * carrier is not in the missile's trajectory. Targets the nearest enemy (missile auto-homes).
 * Blocked only if our carrier is both in the forward hemisphere AND closer than the nearest enemy.
 */
public class FighterMissileFireTactic implements Tactic<Entity> {

    @Override
    public Optional<Order> apply(Entity fighter, GameSnapshot snapshot, CommanderState state) {
        if (fighter.missiles() <= 0) return Optional.empty();

        Entity nearestEnemy = snapshot.liveEnemyCarriers().stream()
                .min(Comparator.comparingDouble(c -> GameUtils.distance(fighter, c)))
                .orElse(null);
        if (nearestEnemy == null) return Optional.empty();

        float distToNearest = GameUtils.distance(fighter, nearestEnemy);
        if (distToNearest < GameConfig.FIGHTER_MISSILE_MIN_FIRE_RANGE) return Optional.empty();

        // Block only if our carrier is closer AND in the forward hemisphere (missile would home on it).
        Entity myCarrier = snapshot.myCarrier();
        float distToCarrier = GameUtils.distance(fighter, myCarrier);
        if (distToCarrier <= distToNearest) {
            float dex = nearestEnemy.px() - fighter.px();
            float dey = nearestEnemy.py() - fighter.py();
            float eLen = (float) Math.sqrt(dex * dex + dey * dey);
            float dcx = myCarrier.px() - fighter.px();
            float dcy = myCarrier.py() - fighter.py();
            float cLen = (float) Math.sqrt(dcx * dcx + dcy * dcy);
            float dot = (dex / eLen) * (dcx / cLen) + (dey / eLen) * (dcy / cLen);
            if (dot > 0) return Optional.empty();
        }
        return Optional.of(new Order(fighter.id(), OrderType.FIRE_MISSILE));
    }
}
