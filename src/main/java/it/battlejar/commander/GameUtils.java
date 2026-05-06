package it.battlejar.commander;

import it.battlejar.api.Entity;
import it.battlejar.api.GameSettings;

public final class GameUtils {

    private GameUtils() {}

    public static int health(Entity e) {
        try {
            return Integer.parseInt(e.status());
        } catch (NumberFormatException ex) {
            return Integer.MAX_VALUE;
        }
    }

    public static float distance(Entity a, Entity b) {
        float dx = a.px() - b.px();
        float dy = a.py() - b.py();
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    public static float distanceTo(Entity e, float x, float y) {
        float dx = e.px() - x;
        float dy = e.py() - y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    public static boolean isNearBorder(Entity e, GameSettings settings, float margin) {
        return e.px() < margin
                || e.py() < margin
                || e.px() > settings.worldWidth() - margin
                || e.py() > settings.worldHeight() - margin;
    }

    public static int[] closestCornerOffset(Entity carrier, GameSettings settings, float margin) {
        float ww = settings.worldWidth(), wh = settings.worldHeight();
        float[][] corners = {
            {margin, margin},
            {ww - margin, margin},
            {margin, wh - margin},
            {ww - margin, wh - margin}
        };
        float bestDistSq = Float.MAX_VALUE;
        float[] best = corners[0];
        for (float[] c : corners) {
            float dx = carrier.px() - c[0];
            float dy = carrier.py() - c[1];
            float dsq = dx * dx + dy * dy;
            if (dsq < bestDistSq) {
                bestDistSq = dsq;
                best = c;
            }
        }
        return new int[]{Math.round(best[0] - carrier.px()), Math.round(best[1] - carrier.py())};
    }
}
