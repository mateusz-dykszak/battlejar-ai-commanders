package it.battlejar.commander.tactic.carrier;

import it.battlejar.api.Entity;
import it.battlejar.api.GameSettings;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.GameUtils;
import it.battlejar.commander.tactic.Tactic;

import java.util.Optional;

/**
 * Moves the carrier slowly along the horizontal border of its target corner. Direction is away
 * from the corner (left for right-side corners, right for left-side corners), so the carrier
 * gradually traverses the border toward the world centre. Vertical correction keeps the carrier
 * at the horizontal border line defined by {@code cornerMargin}.
 *
 * <p>Always returns a MOVE order — place border evasion and missile-fire tactics earlier in the
 * chain so they can preempt this tactic when needed.
 */
public class CarrierBorderCruiseTactic implements Tactic<Entity> {

    private final float cornerMargin;
    private final float cruiseSpeed;

    public CarrierBorderCruiseTactic(float cornerMargin, float cruiseSpeed) {
        this.cornerMargin = cornerMargin;
        this.cruiseSpeed = cruiseSpeed;
    }

    @Override
    public Optional<Order> apply(Entity carrier, GameSnapshot snapshot, CommanderState state) {
        GameSettings s = snapshot.settings();
        float[] corner = GameUtils.getTargetCorner(state, carrier, s, cornerMargin);

        boolean cornerNearRight = corner[0] > s.worldWidth() / 2f;
        boolean cornerNearTop   = corner[1] < s.worldHeight() / 2f;

        // Horizontal: move away from the corner side
        float dx = cornerNearRight ? -cruiseSpeed : cruiseSpeed;

        // Vertical: correct to stay near the horizontal border
        float targetY = cornerNearTop ? cornerMargin : s.worldHeight() - cornerMargin;
        float dy = targetY - carrier.py();

        return Optional.of(new Order(carrier.id(), OrderType.MOVE, (int) dx + "|" + (int) dy));
    }
}
