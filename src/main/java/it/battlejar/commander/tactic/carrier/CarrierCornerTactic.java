package it.battlejar.commander.tactic.carrier;

import it.battlejar.api.Entity;
import it.battlejar.api.GameSettings;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.GameUtils;
import it.battlejar.commander.tactic.Tactic;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j

/**
 * Moves the carrier to the nearest world corner to stay out of crossfire between enemies.
 * Always sends MOVE toward the corner — never PATROL — so the carrier oscillates near
 * the corner rather than overshooting due to approach momentum. Sets
 * {@link it.battlejar.commander.CommanderState#carrierReachedCorner} permanently once the
 * carrier enters the {@code cornerMargin × cornerMargin} square in any corner, which
 * triggers the handoff to {@link it.battlejar.commander.strategy.MultiEnemyHunterStrategy}.
 */
public class CarrierCornerTactic implements Tactic<Entity> {

    private final float cornerMargin;

    public CarrierCornerTactic(float cornerMargin) {
        this.cornerMargin = cornerMargin;
    }

    @Override
    public Optional<Order> apply(Entity carrier, GameSnapshot snapshot, CommanderState state) {
        if (isInCornerSquare(carrier, snapshot.settings()) && !state.carrierReachedCorner) {
            state.carrierReachedCorner = true;
            // Pick the corner sector with the fewest live enemies so the hunter phase starts
            // from the safest position rather than simply the nearest corner.
            state.preferredCorner = GameUtils.safestCorner(
                    snapshot.liveEnemyCarriers(), snapshot.settings(), cornerMargin);
        }
        int[] offset;
        if (state.preferredCorner != null) {
            offset = new int[]{
                Math.round(state.preferredCorner[0] - carrier.px()),
                Math.round(state.preferredCorner[1] - carrier.py())
            };
        } else {
            offset = GameUtils.closestCornerOffset(carrier, snapshot.settings(), cornerMargin);
        }
        log.debug("corner offset {},{} carrier=({},{})",
                offset[0], offset[1], (int) carrier.px(), (int) carrier.py());
        // Always MOVE toward the corner (never PATROL). PATROL maintains current velocity and
        // causes the carrier to overshoot the corner, then drift to a different corner.
        return Optional.of(new Order(carrier.id(), OrderType.MOVE, offset[0] + "|" + offset[1]));
    }

    /** Returns true when the carrier is inside the cornerMargin × cornerMargin square of any corner. */
    private boolean isInCornerSquare(Entity carrier, GameSettings settings) {
        float px = carrier.px(), py = carrier.py();
        float W = settings.worldWidth(), H = settings.worldHeight();
        boolean nearXBorder = px < cornerMargin || px > W - cornerMargin;
        boolean nearYBorder = py < cornerMargin || py > H - cornerMargin;
        return nearXBorder && nearYBorder;
    }
}
