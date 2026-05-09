package it.battlejar.commander.tactic.carrier;

import it.battlejar.api.Entity;
import it.battlejar.api.GameSettings;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.tactic.Tactic;

import java.util.Optional;

/**
 * Velocity-aware carrier border evasion. Trigger distance scales with approach angle so that
 * gentle corner approaches are not interrupted while head-on border rushes are caught early.
 *
 * <p>Angle is measured from the border line toward the border normal (0° = parallel, 90° = head-on):
 * <ul>
 *   <li>Moving away from border → no trigger</li>
 *   <li>&lt; 5° (nearly parallel) → trigger at 5 units</li>
 *   <li>5°–45° (shallow approach) → trigger at 10 units</li>
 *   <li>45°–80° (moderate approach) → trigger at 15 units</li>
 *   <li>80°–90° (near-perpendicular) → trigger at 20 units</li>
 * </ul>
 * When triggered the carrier is pushed to {@link #SAFE_DISTANCE} units from the offending border.
 */
public class CarrierBorderEvasionTactic implements Tactic<Entity> {

    /** Target distance from any border after a push. */
    private static final float SAFE_DISTANCE = 20f;

    @Override
    public Optional<Order> apply(Entity carrier, GameSnapshot snapshot, CommanderState state) {
        GameSettings s = snapshot.settings();
        float vx = carrier.vx();
        float vy = carrier.vy();

        float safeX = carrier.px();
        float safeY = carrier.py();
        boolean evade = false;

        // left border: approaching when vx < 0  →  approach component = -vx
        float leftT = triggerThreshold(-vx, vy);
        if (leftT > 0 && carrier.px() < leftT) {
            safeX = Math.max(safeX, SAFE_DISTANCE);
            evade = true;
        }
        // right border: approaching when vx > 0
        float rightT = triggerThreshold(vx, vy);
        if (rightT > 0 && carrier.px() > s.worldWidth() - rightT) {
            safeX = Math.min(safeX, s.worldWidth() - SAFE_DISTANCE);
            evade = true;
        }
        // top border: approaching when vy < 0  →  approach component = -vy
        float topT = triggerThreshold(-vy, vx);
        if (topT > 0 && carrier.py() < topT) {
            safeY = Math.max(safeY, SAFE_DISTANCE);
            evade = true;
        }
        // bottom border: approaching when vy > 0
        float bottomT = triggerThreshold(vy, vx);
        if (bottomT > 0 && carrier.py() > s.worldHeight() - bottomT) {
            safeY = Math.min(safeY, s.worldHeight() - SAFE_DISTANCE);
            evade = true;
        }

        if (!evade) return Optional.empty();
        return Optional.of(new Order(carrier.id(), OrderType.MOVE,
                (int) (safeX - carrier.px()) + "|" + (int) (safeY - carrier.py())));
    }

    /**
     * Returns the trigger distance for a border given velocity components.
     *
     * @param approach  velocity component directed toward the border (positive = approaching)
     * @param parallel  velocity component along the border (either sign)
     */
    static float triggerThreshold(float approach, float parallel) {
        if (approach < 0) return 0f;
        double angleDeg = Math.toDegrees(Math.atan2(approach, Math.abs(parallel)));
        if (angleDeg < 5)  return 5f;
        if (angleDeg < 45) return 10f;
        if (angleDeg < 80) return 15f;
        return 20f;
    }
}
