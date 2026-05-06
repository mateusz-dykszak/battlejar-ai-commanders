package it.battlejar.commander.tactic.carrier;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.api.GameSettings;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.GameUtils;
import it.battlejar.commander.tactic.Tactic;

import java.util.Optional;

/**
 * Backs the carrier away from the enemy carrier when they are too close. Used in 1v1 to
 * prevent a collision at point-blank range where neither side can manoeuvre or fire effectively.
 */
public class CarrierKiteTactic implements Tactic<Entity> {

    private final float minSeparation;
    private final float kiteDistance;
    private final float borderMargin;
    private final float safeInset;

    public CarrierKiteTactic(float minSeparation, float kiteDistance, float borderMargin, float safeInset) {
        this.minSeparation = minSeparation;
        this.kiteDistance = kiteDistance;
        this.borderMargin = borderMargin;
        this.safeInset = safeInset;
    }

    @Override
    public Optional<Order> apply(Entity carrier, GameSnapshot snapshot, CommanderState state) {
        Entity target = snapshot.primaryTarget();
        if (target == null) return Optional.empty();
        float dist = GameUtils.distance(carrier, target);
        if (dist >= minSeparation) return Optional.empty();
        float retreatX = carrier.px() - (target.px() - carrier.px()) / dist * kiteDistance;
        float retreatY = carrier.py() - (target.py() - carrier.py()) / dist * kiteDistance;
        GameSettings s = snapshot.settings();
        float margin = borderMargin + safeInset;
        retreatX = Math.max(margin, Math.min(s.worldWidth() - margin, retreatX));
        retreatY = Math.max(margin, Math.min(s.worldHeight() - margin, retreatY));
        return Optional.of(new Order(carrier.id(), OrderType.MOVE,
                (int) (retreatX - carrier.px()) + "|" + (int) (retreatY - carrier.py())));
    }
}
