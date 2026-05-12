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
    /** Absolute world corner target chosen once when the carrier first reaches a corner; null = use closest corner. */
    public float[] preferredCorner = null;
    /** Entity ID of the enemy carrier that Control strategy is currently focused on; null = no focus. */
    public String controlFocusTargetId = null;
    /** Carrier world position when Control strategy first executed; used to detect 3/4-border traversal. */
    public float[] controlPatrolStart = null;
    /** Number of live enemy carriers when Control strategy first executed; used to detect a kill during control. */
    public int controlInitialEnemyCount = 0;
    /** True once Control strategy has run its course (enemy killed or 3/4 of border traversed). */
    public boolean controlExhausted = false;
}
