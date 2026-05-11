package it.battlejar.commander.tactic.fighter;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameConfig;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.GameUtils;
import it.battlejar.commander.tactic.Tactic;

import java.util.Comparator;
import java.util.Optional;

/**
 * While a fighter has missiles, orient it toward the nearest enemy carrier then fire.
 * Missiles launch in the fighter's current flight direction, so firing while pointing the
 * wrong way wastes the shot. Each tick: if the fighter's velocity is already within 45° of
 * the target, fire (subject to arming range and no-friendly-fire checks); otherwise issue
 * ATTACK on the target to build velocity in the right direction. Returns empty once missiles
 * are spent so the next tactic in the chain can take over.
 */
public class FighterMissileOrientAndFireTactic implements Tactic<Entity> {

    private static final float ORIENT_COS = 0.707f; // cos(45°)
    private static final float MIN_SPEED   = 0.1f;

    @Override
    public Optional<Order> apply(Entity fighter, GameSnapshot snapshot, CommanderState state) {
        if (fighter.missiles() <= 0) return Optional.empty();

        Entity target = snapshot.liveEnemyCarriers().stream()
                .min(Comparator.comparingDouble(c -> GameUtils.distance(fighter, c)))
                .orElse(null);
        if (target == null) return Optional.empty();

        if (isOriented(fighter, target) && canFire(fighter, target, snapshot.myCarrier())) {
            return Optional.of(new Order(fighter.id(), OrderType.FIRE_MISSILE));
        }
        return Optional.of(new Order(fighter.id(), OrderType.ATTACK, target.id()));
    }

    private static boolean isOriented(Entity fighter, Entity target) {
        float speed = (float) Math.sqrt(fighter.vx() * fighter.vx() + fighter.vy() * fighter.vy());
        if (speed < MIN_SPEED) return false;
        float nvx = fighter.vx() / speed;
        float nvy = fighter.vy() / speed;
        float dx = target.px() - fighter.px();
        float dy = target.py() - fighter.py();
        float dLen = (float) Math.sqrt(dx * dx + dy * dy);
        return (nvx * dx / dLen + nvy * dy / dLen) >= ORIENT_COS;
    }

    private static boolean canFire(Entity fighter, Entity target, Entity myCarrier) {
        float distToTarget = GameUtils.distance(fighter, target);
        if (distToTarget < GameConfig.FIGHTER_MISSILE_MIN_FIRE_RANGE) return false;

        float distToCarrier = GameUtils.distance(fighter, myCarrier);
        if (distToCarrier > distToTarget) return true;

        // Carrier is closer — block only if it is also in the forward hemisphere.
        float dex = target.px() - fighter.px();
        float dey = target.py() - fighter.py();
        float eLen = (float) Math.sqrt(dex * dex + dey * dey);
        float dcx = myCarrier.px() - fighter.px();
        float dcy = myCarrier.py() - fighter.py();
        float cLen = (float) Math.sqrt(dcx * dcx + dcy * dcy);
        return (dex / eLen) * (dcx / cLen) + (dey / eLen) * (dcy / cLen) <= 0;
    }
}
