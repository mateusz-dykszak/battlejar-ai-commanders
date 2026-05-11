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
 * Switches a fighter to laser mode (TARGET "M") when an armed enemy missile is within laser
 * range of that specific fighter AND heading toward our carrier (within 60° of the carrier
 * direction). Missiles heading toward other players are ignored so fighters keep attacking.
 */
public class LaserDefenseTactic implements Tactic<Entity> {

    private final float laserRange;

    public LaserDefenseTactic(float laserRange) {
        this.laserRange = laserRange;
    }

    @Override
    public Optional<Order> apply(Entity fighter, GameSnapshot snapshot, CommanderState state) {
        Entity myCarrier = snapshot.myCarrier();
        boolean missileNear = snapshot.armedEnemyMissiles().stream()
                .anyMatch(m -> GameUtils.distance(m, fighter) < laserRange
                        && headingTowardCarrier(m, myCarrier));
        if (missileNear) {
            return Optional.of(new Order(fighter.id(), OrderType.TARGET, "M"));
        }
        return Optional.empty();
    }

    private static boolean headingTowardCarrier(Entity missile, Entity carrier) {
        float vMag = (float) Math.sqrt(missile.vx() * missile.vx() + missile.vy() * missile.vy());
        if (vMag < 0.001f) return false;
        float nvx = missile.vx() / vMag;
        float nvy = missile.vy() / vMag;
        float dx = carrier.px() - missile.px();
        float dy = carrier.py() - missile.py();
        float dMag = (float) Math.sqrt(dx * dx + dy * dy);
        if (dMag < 0.001f) return true;
        return (nvx * dx / dMag + nvy * dy / dMag) > 0.5f; // within 60°
    }
}
