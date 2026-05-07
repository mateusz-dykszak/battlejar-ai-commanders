package it.battlejar.commander.tactic.fighter;

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
 * Highest-priority safety net: if a fighter is near the world border, redirects it inward
 * to the margin line on whichever axis is dangerous. Uses a carrier-relative MOVE (not 0|0)
 * so the fighter stays active rather than docking.
 */
public class BorderEvasionTactic implements Tactic<Entity> {

    private final float borderMargin;

    public BorderEvasionTactic(float borderMargin) {
        this.borderMargin = borderMargin;
    }

    @Override
    public Optional<Order> apply(Entity fighter, GameSnapshot snapshot, CommanderState state) {
        if (!GameUtils.isNearBorder(fighter, snapshot.settings(), borderMargin)) {
            return Optional.empty();
        }
        GameSettings s = snapshot.settings();
        // Clamp fighter to the safe zone on whichever axis is near a border.
        float safeX = Math.max(borderMargin, Math.min(s.worldWidth()  - borderMargin, fighter.px()));
        float safeY = Math.max(borderMargin, Math.min(s.worldHeight() - borderMargin, fighter.py()));
        // Express the safe target as an offset from the carrier (MOVE semantics).
        Entity carrier = snapshot.myCarrier();
        int dx = Math.round(safeX - carrier.px());
        int dy = Math.round(safeY - carrier.py());
        return Optional.of(new Order(fighter.id(), OrderType.MOVE, dx + "|" + dy));
    }
}
