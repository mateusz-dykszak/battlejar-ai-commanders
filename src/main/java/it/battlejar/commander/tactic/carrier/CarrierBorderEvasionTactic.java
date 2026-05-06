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

public class CarrierBorderEvasionTactic implements Tactic<Entity> {

    private final float borderMargin;
    private final float safeInset;

    public CarrierBorderEvasionTactic(float borderMargin, float safeInset) {
        this.borderMargin = borderMargin;
        this.safeInset = safeInset;
    }

    @Override
    public Optional<Order> apply(Entity carrier, GameSnapshot snapshot, CommanderState state) {
        if (!GameUtils.isNearBorder(carrier, snapshot.settings(), borderMargin)) {
            return Optional.empty();
        }
        GameSettings s = snapshot.settings();
        float inner = borderMargin + safeInset;
        float safeX = Math.max(inner, Math.min(s.worldWidth() - inner, carrier.px()));
        float safeY = Math.max(inner, Math.min(s.worldHeight() - inner, carrier.py()));
        return Optional.of(new Order(carrier.id(), OrderType.MOVE,
                (int) (safeX - carrier.px()) + "|" + (int) (safeY - carrier.py())));
    }
}
