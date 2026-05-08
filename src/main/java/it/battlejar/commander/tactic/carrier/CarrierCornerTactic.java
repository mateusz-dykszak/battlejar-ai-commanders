package it.battlejar.commander.tactic.carrier;

import it.battlejar.api.Entity;
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
 * {@link it.battlejar.commander.CommanderState#carrierReachedCorner} permanently once within
 * the threshold, which triggers the handoff to
 * {@link it.battlejar.commander.strategy.MultiEnemyHunterStrategy}.
 */
public class CarrierCornerTactic implements Tactic<Entity> {

    private final float cornerMargin;
    private final float cornerThreshold;

    public CarrierCornerTactic(float cornerMargin, float cornerThreshold) {
        this.cornerMargin = cornerMargin;
        this.cornerThreshold = cornerThreshold;
    }

    @Override
    public Optional<Order> apply(Entity carrier, GameSnapshot snapshot, CommanderState state) {
        int[] offset = GameUtils.closestCornerOffset(carrier, snapshot.settings(), cornerMargin);
        float dist = (float) Math.sqrt((float) offset[0] * offset[0] + (float) offset[1] * offset[1]);
        if (dist <= cornerThreshold) {
            state.carrierReachedCorner = true;
        }
        log.debug("corner offset {},{} dist={} carrier=({},{})",
                offset[0], offset[1], dist, (int) carrier.px(), (int) carrier.py());
        // Always MOVE toward the corner (never PATROL). PATROL maintains current velocity and
        // causes the carrier to overshoot the corner, then drift to a different corner.
        return Optional.of(new Order(carrier.id(), OrderType.MOVE, offset[0] + "|" + offset[1]));
    }
}
