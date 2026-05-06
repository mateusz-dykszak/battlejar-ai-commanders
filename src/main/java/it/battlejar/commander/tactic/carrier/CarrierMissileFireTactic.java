package it.battlejar.commander.tactic.carrier;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.tactic.Tactic;

import java.util.Optional;

/**
 * Fires the carrier's missile at the primary target. Rate-limited to once per
 * {@link it.battlejar.commander.GameConfig#CARRIER_MISSILE_FIRE_INTERVAL_MS} so that movement
 * orders are not continuously blocked — the carrier still moves on the other ticks.
 */
public class CarrierMissileFireTactic implements Tactic<Entity> {

    private final long fireIntervalMs;

    public CarrierMissileFireTactic(long fireIntervalMs) {
        this.fireIntervalMs = fireIntervalMs;
    }

    @Override
    public Optional<Order> apply(Entity carrier, GameSnapshot snapshot, CommanderState state) {
        Entity target = snapshot.primaryTarget();
        if (target == null || carrier.missiles() <= 0) return Optional.empty();
        long now = System.currentTimeMillis();
        if (now - state.lastCarrierMissileFireMs < fireIntervalMs) return Optional.empty();
        state.lastCarrierMissileFireMs = now;
        float dx = target.px() - carrier.px();
        float dy = target.py() - carrier.py();
        return Optional.of(new Order(carrier.id(), OrderType.FIRE_MISSILE, (int) dx + "|" + (int) dy));
    }
}
