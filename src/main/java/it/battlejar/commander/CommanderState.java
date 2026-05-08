package it.battlejar.commander;

import java.util.HashMap;
import java.util.Map;

public class CommanderState {

    public final Map<String, Long> lastOrderTime = new HashMap<>();
    public int initialEnemyCarrierCount = 0;
    public long lastCarrierMissileFireMs = 0L;
    public float lastFormationAngle = 0f;
    public boolean carrierReachedCorner = false;
    /** missileId → fighterId: persisted across ticks so the same fighter stays on the same missile. */
    public final Map<String, String> missileInterceptAssignments = new HashMap<>();
}
