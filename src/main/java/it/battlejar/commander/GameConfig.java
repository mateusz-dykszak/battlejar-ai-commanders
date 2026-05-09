package it.battlejar.commander;

public final class GameConfig {

    public static final float BORDER_MARGIN = 50f;
    public static final float SAFE_INSET = 30f;
    public static final float FORMATION_THRESHOLD = 100f;
    public static final float FIGHTER_MISSILE_RANGE = 150f;
    public static final float FIGHTER_LASER_RANGE = 150f;
    public static final float MISSILE_INTERCEPT_RANGE = 150f;
    public static final float FORMATION_RADIUS_TIGHT = 50f;
    public static final float FORMATION_RADIUS_WIDE = 150f;
    public static final int   FORMATION_EXPAND_AT = 1;
    public static final int   FORMATION_SLOTS = 8;
    public static final int   AGGRESSION_FIGHTER_THRESHOLD = 12;
    public static final float CARRIER_PUSH_DISTANCE = 80f;
    public static final float CARRIER_PUSH_DISTANCE_1V1 = 400f;
    public static final float CARRIER_KITE_DISTANCE = 80f;
    public static final float CARRIER_CORNER_MARGIN = 25f;
    public static final int   KILL_FOCUS_HP = 750;
    public static final float KILL_FOCUS_RANGE = 350f;
    public static final float CARRIER_MIN_SEPARATION_1V1 = 80f;
    public static final float CARRIER_HOLD_DISTANCE_1V1  = 250f;
    public static final float FIGHTER_INTRUDER_CARRIER_RANGE = 75f;
    public static final float FIGHTER_INTRUDER_CARRIER_RANGE_SURVIVAL = 150f;
    public static final float FIGHTER_INTRUDER_CARRIER_RANGE_1V1 = 100f;
    public static final long  CARRIER_MISSILE_FIRE_INTERVAL_MS = 800L;
    public static final float DEPLOY_OFFSET = 30f;

    private GameConfig() {}
}
