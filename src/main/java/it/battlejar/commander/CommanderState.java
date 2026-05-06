package it.battlejar.commander;

import java.util.HashMap;
import java.util.Map;

public class CommanderState {

    public final Map<String, Long> lastOrderTime = new HashMap<>();
    public int initialEnemyCarrierCount = 0;
    public long lastCarrierMissileFireMs = 0L;
    public float lastFormationAngle = 0f;
    public boolean carrierReachedCorner = false;
}
