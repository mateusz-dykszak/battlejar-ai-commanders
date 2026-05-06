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

public class CarrierDodgeTactic implements Tactic<Entity> {

    private final float dodgeRange;
    private final float dodgeDistance;
    private final float borderMargin;
    private final float safeInset;

    public CarrierDodgeTactic(float dodgeRange, float dodgeDistance, float borderMargin, float safeInset) {
        this.dodgeRange = dodgeRange;
        this.dodgeDistance = dodgeDistance;
        this.borderMargin = borderMargin;
        this.safeInset = safeInset;
    }

    @Override
    public Optional<Order> apply(Entity carrier, GameSnapshot snapshot, CommanderState state) {
        Entity missile = snapshot.armedEnemyMissiles().stream()
                .filter(e -> GameUtils.distance(e, carrier) < dodgeRange)
                .filter(e -> {
                    float toCx = carrier.px() - e.px();
                    float toCy = carrier.py() - e.py();
                    return e.vx() * toCx + e.vy() * toCy > 0;
                })
                .min((a, b) -> Float.compare(GameUtils.distance(a, carrier), GameUtils.distance(b, carrier)))
                .orElse(null);

        if (missile == null) return Optional.empty();

        float vLen = (float) Math.sqrt(missile.vx() * missile.vx() + missile.vy() * missile.vy());
        if (vLen < 0.001f) return Optional.empty();

        float px1 = -missile.vy() / vLen, py1 = missile.vx() / vLen;
        float px2 =  missile.vy() / vLen, py2 = -missile.vx() / vLen;

        GameSettings s = snapshot.settings();
        float margin = borderMargin + safeInset;
        float ww = s.worldWidth(), wh = s.worldHeight();

        float tx1 = carrier.px() + px1 * dodgeDistance, ty1 = carrier.py() + py1 * dodgeDistance;
        float tx2 = carrier.px() + px2 * dodgeDistance, ty2 = carrier.py() + py2 * dodgeDistance;

        boolean ok1 = tx1 > margin && ty1 > margin && tx1 < ww - margin && ty1 < wh - margin;
        boolean ok2 = tx2 > margin && ty2 > margin && tx2 < ww - margin && ty2 < wh - margin;

        float[] chosen;
        if (ok1 && !ok2) {
            chosen = new float[]{px1, py1};
        } else if (ok2 && !ok1) {
            chosen = new float[]{px2, py2};
        } else if (ok1) {
            float c1 = Math.min(Math.min(tx1 - margin, ww - margin - tx1), Math.min(ty1 - margin, wh - margin - ty1));
            float c2 = Math.min(Math.min(tx2 - margin, ww - margin - tx2), Math.min(ty2 - margin, wh - margin - ty2));
            chosen = c1 >= c2 ? new float[]{px1, py1} : new float[]{px2, py2};
        } else {
            return Optional.empty();
        }

        return Optional.of(new Order(carrier.id(), OrderType.MOVE,
                Math.round(chosen[0] * dodgeDistance) + "|" + Math.round(chosen[1] * dodgeDistance)));
    }
}
