package it.battlejar.commander;

import it.battlejar.api.Entity;
import it.battlejar.api.GameSettings;

import java.util.List;
import java.util.Map;

public record GameSnapshot(
        Entity myCarrier,
        List<Entity> myActiveFighters,
        List<Entity> myDockedFighters,
        List<Entity> liveEnemyCarriers,
        List<Entity> activeEnemyFighters,
        List<Entity> armedEnemyMissiles,
        Entity primaryTarget,
        float primaryTargetAngle,
        boolean hasEnemies,
        boolean hasKilledEnemy,
        Map<String, int[]> interceptMap,
        int[][] formation,
        int[][] deploymentFormation,
        GameSettings settings
) {}
