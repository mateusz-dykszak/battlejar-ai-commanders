package it.battlejar.commander;

import it.battlejar.api.Entity;
import it.battlejar.api.GameSettings;

import java.util.List;

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
        return isNearBorder(e.px(), e.py(), settings, margin);
    }

    public static boolean isNearBorder(float x, float y, GameSettings settings, float margin) {
        return x < margin || y < margin
                || x > settings.worldWidth() - margin
                || y > settings.worldHeight() - margin;
    }

    /**
     * Returns the world corner (as absolute coords) that has the fewest live enemy carriers in
     * its Voronoi region. Tie-broken by first in list order: TL, TR, BL, BR. When the enemy
     * list is empty, returns the first corner (top-left).
     */
    public static float[] safestCorner(List<Entity> enemies, GameSettings settings, float margin) {
        float ww = settings.worldWidth(), wh = settings.worldHeight();
        float[][] corners = {
            {margin, margin},
            {ww - margin, margin},
            {margin, wh - margin},
            {ww - margin, wh - margin}
        };
        if (enemies.isEmpty()) return corners[0];
        int[] counts = new int[4];
        for (Entity enemy : enemies) {
            int best = 0;
            float bestDsq = Float.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                float dx = enemy.px() - corners[i][0];
                float dy = enemy.py() - corners[i][1];
                float dsq = dx * dx + dy * dy;
                if (dsq < bestDsq) { bestDsq = dsq; best = i; }
            }
            counts[best]++;
        }
        int bestCorner = 0;
        for (int i = 1; i < 4; i++) {
            if (counts[i] < counts[bestCorner]) bestCorner = i;
        }
        return corners[bestCorner];
    }

    /**
     * Returns true when no live enemy carrier is within {@code margin} units of either border
     * that forms the given corner (identified by which world quadrant it sits in).
     */
    public static boolean isCornerAreaEmpty(float[] corner, List<Entity> enemies, GameSettings settings, float margin) {
        float W = settings.worldWidth(), H = settings.worldHeight();
        boolean nearRight = corner[0] > W / 2;
        boolean nearTop   = corner[1] < H / 2;
        for (Entity enemy : enemies) {
            if (nearRight  && enemy.px() > W - margin) return false;
            if (!nearRight && enemy.px() < margin)      return false;
            if (nearTop    && enemy.py() < margin)       return false;
            if (!nearTop   && enemy.py() > H - margin)  return false;
        }
        return true;
    }

    /**
     * Returns the carrier's current target corner as absolute world coordinates.
     * Uses {@link CommanderState#preferredCorner} when set, otherwise the closest corner.
     */
    public static float[] getTargetCorner(CommanderState state, Entity carrier, GameSettings settings, float margin) {
        if (state.preferredCorner != null) return state.preferredCorner;
        float ww = settings.worldWidth(), wh = settings.worldHeight();
        float[][] corners = {
            {margin, margin}, {ww - margin, margin},
            {margin, wh - margin}, {ww - margin, wh - margin}
        };
        float bestDsq = Float.MAX_VALUE;
        float[] best = corners[0];
        for (float[] c : corners) {
            float dx = carrier.px() - c[0], dy = carrier.py() - c[1];
            float dsq = dx * dx + dy * dy;
            if (dsq < bestDsq) { bestDsq = dsq; best = c; }
        }
        return best;
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
