package it.battlejar.commander;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.client.AbstractCommander;
import it.battlejar.commander.ai.AIAgent;
import it.battlejar.commander.ai.AICommandParser;
import it.battlejar.commander.map.BattleMap;
import it.battlejar.commander.map.ColorSectorStatus;
import it.battlejar.commander.map.Sector;
import it.battlejar.commander.map.ThreatLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class AgenticCommander extends AbstractCommander {
    private static final Logger log = LoggerFactory.getLogger(AgenticCommander.class);

    private BattleMap battleMap;
    private AIAgent aiAgent;
    private long lastAiTick = 0;
    private long currentAiCooldownMs = 2000;
    private static final int EMERGENCY_HEALTH_THRESHOLD = 30; // 30%
    private static final float EMERGENCY_THREAT_DISTANCE = 100.0f;
    private static final float EMERGENCY_SCREEN_DISTANCE = 30.0f;
    private static final long DEFAULT_AI_COOLDOWN_MS = 2000;
    private static final long ENTITY_COOLDOWN_MS = 150; // Per-entity order cooldown
    private final Map<String, Long> entityLastOrderTime = new HashMap<>();

    @Override
    protected void order(Order order) {
        long now = System.currentTimeMillis();
        Long lastOrderTime = entityLastOrderTime.get(order.id());
        if (lastOrderTime == null || now - lastOrderTime >= ENTITY_COOLDOWN_MS) {
            entityLastOrderTime.put(order.id(), now);
            super.order(order);
        } else {
            log.debug("Cooldown active for entity {}, skipping order", order.id());
        }
    }

    @Override
    protected boolean process(Collection<Entity> entities) {
        if (battleMap == null) {
            initializeBattleMap();
        }

        if (aiAgent == null) {
            aiAgent = new AIAgent();
        }

        battleMap.update(entities, myColor);

        // Undock docked fighters and position them defensively immediately
        entities.stream()
                .filter(e -> e.type() == Entity.Type.FIGHTER && 
                              myColor.name().equalsIgnoreCase(e.color()) && 
                              "C".equals(e.status()))
                .forEach(f -> defend(f, entities));

        // Check if our carrier still exists
        boolean carrierAlive = entities.stream()
                .anyMatch(e -> e.type() == Entity.Type.CARRIER && 
                              myColor.name().equalsIgnoreCase(e.color()) &&
                              !"D".equals(e.status()));

        if (!carrierAlive) {
            log.info("Carrier lost! Stopping AI and signaling game end.");
            return false;
        }

        // Missile evasion can also move carrier
        executeMissileEvasion(entities);

        // Periodic collision check for carrier even if no AI command is active
        // But only if we didn't just issue an AI command which already checks maneuver
        if (System.currentTimeMillis() - lastAiTick > 100) {
            applyPassiveCarrierAvoidance(entities);
        }

        // Emergency Screen behavior
        if (isEmergencyScreenRequired(entities)) {
            executeEmergencyScreen(entities);
        }

        long now = System.currentTimeMillis();
        if (now - lastAiTick > currentAiCooldownMs) {
            lastAiTick = now;
            try {
                // Get carrier health and fighter counts
                Entity myCarrier = entities.stream()
                        .filter(e -> e.type() == Entity.Type.CARRIER && myColor.name().equalsIgnoreCase(e.color()))
                        .findFirst().orElse(null);
                String carrierHealth = myCarrier != null ? myCarrier.status() : "0";
                
                int activeFighters = (int) entities.stream()
                        .filter(e -> e.type() == Entity.Type.FIGHTER && myColor.name().equalsIgnoreCase(e.color()) && !"D".equals(e.status()) && !"C".equals(e.status()))
                        .count();
                
                int dockedFighters = (int) entities.stream()
                        .filter(e -> e.type() == Entity.Type.FIGHTER && myColor.name().equalsIgnoreCase(e.color()) && "C".equals(e.status()))
                        .count();

                String aiOutput = aiAgent.getCommandsFromAI(battleMap, myColor, carrierHealth, activeFighters, dockedFighters);
                log.info("AI Output: {}", aiOutput);
                AICommandParser.AIResponse aiResponse = AICommandParser.parse(aiOutput);
                executeAiResponse(aiResponse, entities);
                
                // Update adaptive cooldown for next tick
                currentAiCooldownMs = calculateAdaptiveAiCooldown();
                log.info("Next AI tick in {}ms", currentAiCooldownMs);
            } catch (Exception e) {
                log.error("Error getting or executing AI commands", e);
                executeDefensiveManeuvers(entities);
                currentAiCooldownMs = DEFAULT_AI_COOLDOWN_MS;
            }
        }

        return true; // Keep playing
    }

    private void executeAiResponse(AICommandParser.AIResponse response, Collection<Entity> entities) {
        // Find our carrier
        Entity myCarrier = entities.stream()
                .filter(e -> e.type() == Entity.Type.CARRIER && myColor.name().equalsIgnoreCase(e.color()))
                .findFirst().orElse(null);

        if (myCarrier != null) {
            issueCommand(myCarrier, response.carrierCommand(), entities);
        }

        boolean emergency = isEmergencyScreenRequired(entities);

        // Group my fighters by sector
        Map<String, List<Entity>> fightersBySector = new HashMap<>();
        for (Entity e : entities) {
            if (e.type() == Entity.Type.FIGHTER && myColor.name().equalsIgnoreCase(e.color()) && !"D".equals(e.status())) {
                if (emergency && !"C".equals(e.status())) {
                    continue; // Skip AI commands for active fighters if emergency
                }
                int r = (int) (e.py() / (settings.worldHeight() / battleMap.getRows()));
                int c = (int) (e.px() / (settings.worldWidth() / battleMap.getCols()));
                r = Math.max(0, Math.min(battleMap.getRows() - 1, r));
                c = Math.max(0, Math.min(battleMap.getCols() - 1, c));
                String sectorKey = r + "x" + c;
                fightersBySector.computeIfAbsent(sectorKey, k -> new ArrayList<>()).add(e);
            }
        }

        // Execute sector commands
        for (Map.Entry<String, List<AICommandParser.Command>> entry : response.sectorCommands().entrySet()) {
            String sectorKey = entry.getKey();
            List<AICommandParser.Command> commands = entry.getValue();
            List<Entity> sectorFighters = fightersBySector.get(sectorKey);

            if (sectorFighters != null && !sectorFighters.isEmpty() && !commands.isEmpty()) {
                int groupSize = Math.max(1, sectorFighters.size() / commands.size());
                for (int i = 0; i < commands.size(); i++) {
                    int start = i * groupSize;
                    int end = (i == commands.size() - 1) ? sectorFighters.size() : (i + 1) * groupSize;
                    AICommandParser.Command cmd = commands.get(i);
                    for (int j = start; j < end; j++) {
                        issueCommand(sectorFighters.get(j), cmd, entities);
                    }
                }
            }
        }
        
        boolean globalEmergency = emergency || isEmergencyScreenRequired(entities);

        // Fighters without AI instructions
        fightersBySector.forEach((sectorKey, sectorFighters) -> {
            if (!response.sectorCommands().containsKey(sectorKey)) {
                // Auto-regroup if presence is SMALL
                String[] parts = sectorKey.split("x");
                int r = Integer.parseInt(parts[0]);
                int c = Integer.parseInt(parts[1]);
                Sector sector = battleMap.getSector(r, c);
                ColorSectorStatus status = sector.colorStatuses().get(myColor);
                
                if (status != null && status.presence() == it.battlejar.commander.map.FleetPresence.SMALL && globalEmergency) {
                    // Find a sector with SIGNIFICANT or DOMINANCE presence to regroup to
                    String targetSector = findRegroupTarget(r, c);
                    for (Entity f : sectorFighters) {
                        issueCommand(f, new AICommandParser.Command("REGROUP", targetSector), entities);
                    }
                } else if (!globalEmergency) {
                    // If no emergency, be more aggressive: find nearest enemy and harass or move towards it
                    Entity nearestEnemy = findNearestEnemy(sectorFighters.get(0), entities);
                    for (Entity f : sectorFighters) {
                        if (nearestEnemy != null) {
                            harass(f, nearestEnemy.px(), nearestEnemy.py(), entities);
                        } else {
                            // No enemies? spread out/patrol instead of crowding carrier
                            patrol(f, entities);
                        }
                    }
                } else {
                    // Default to defend only if emergency
                    for (Entity f : sectorFighters) {
                        defend(f, entities);
                    }
                }
            }
        });
    }

    private Entity findNearestEnemy(Entity me, Collection<Entity> allEntities) {
        return allEntities.stream()
                .filter(e -> !myColor.name().equalsIgnoreCase(e.color()) && !"D".equals(e.status()) && !"C".equals(e.status()))
                .filter(e -> e.type() == Entity.Type.CARRIER || e.type() == Entity.Type.FIGHTER)
                .min(Comparator.comparingDouble(e -> (e.px() - me.px()) * (e.px() - me.px()) + (e.py() - me.py()) * (e.py() - me.py())))
                .orElse(null);
    }

    private void patrol(Entity entity, Collection<Entity> allEntities) {
        // Move to a random-ish position far from carrier to avoid crowding, but within world
        int hash = Math.abs(entity.id().hashCode());
        float angle = (float) ((hash % 360) * Math.PI / 180.0);
        float dist = 200 + (hash % 200);
        
        Entity myCarrier = allEntities.stream()
                .filter(e -> e.type() == Entity.Type.CARRIER && myColor.name().equalsIgnoreCase(e.color()))
                .findFirst().orElse(null);
        
        float cx = myCarrier != null ? myCarrier.px() : settings.worldWidth() / 2;
        float cy = myCarrier != null ? myCarrier.py() : settings.worldHeight() / 2;
        
        float tx = cx + (float) Math.cos(angle) * dist;
        float ty = cy + (float) Math.sin(angle) * dist;
        
        // Clamp to world
        tx = Math.max(50, Math.min(settings.worldWidth() - 50, tx));
        ty = Math.max(50, Math.min(settings.worldHeight() - 50, ty));
        
        issueMoveCommand(entity, tx, ty, allEntities);
    }

    private String findRegroupTarget(int currentR, int currentC) {
        // Try to find a sector with SIGNIFICANT or DOMINANCE presence
        for (int r = 0; r < battleMap.getRows(); r++) {
            for (int c = 0; c < battleMap.getCols(); c++) {
                Sector s = battleMap.getSector(r, c);
                ColorSectorStatus status = s.colorStatuses().get(myColor);
                if (status != null && (status.presence() == it.battlejar.commander.map.FleetPresence.SIGNIFICANT 
                        || status.presence() == it.battlejar.commander.map.FleetPresence.DOMINANCE)) {
                    return r + "x" + c;
                }
            }
        }
        // Fallback to carrier sector
        for (int r = 0; r < battleMap.getRows(); r++) {
            for (int c = 0; c < battleMap.getCols(); c++) {
                Sector s = battleMap.getSector(r, c);
                ColorSectorStatus status = s.colorStatuses().get(myColor);
                if (status != null && status.hasCarrier()) {
                    return r + "x" + c;
                }
            }
        }
        return currentR + "x" + currentC; // Stay put if no better place
    }

    void issueCommand(Entity entity, AICommandParser.Command cmd, Collection<Entity> allEntities) {
        if (entity.type() == Entity.Type.CARRIER) {
            float[] maneuverPos = calculateCarrierManeuver(entity, allEntities);
            
            // If the maneuver suggests a change, it means safety (border or avoidance) triggered
            if (maneuverPos[0] != entity.px() || maneuverPos[1] != entity.py()) {
                log.info("Carrier overriding AI command with safety maneuver: [{}, {}]", maneuverPos[0], maneuverPos[1]);
                issueMoveCommand(entity, maneuverPos[0], maneuverPos[1], allEntities);
                return;
            }
        }
        
        if (cmd == null) return;
        
        switch (cmd.type()) {
            case "MOVE" -> {
                if (cmd.target() != null) {
                    float[] targetPos = parseSectorCoords(cmd.target());
                    if (targetPos != null) {
                        issueMoveCommand(entity, targetPos[0], targetPos[1], allEntities);
                    }
                }
            }
            case "ATTACK" -> {
                if (cmd.target() != null) {
                    float[] targetPos = parseSectorCoords(cmd.target());
                    if (targetPos != null) {
                        // Find a target in that sector, priority to carrier
                        Entity target = findTargetInSector(targetPos[0], targetPos[1], allEntities);
                        if (target != null) {
                            order(new Order(entity.id(), OrderType.ATTACK, target.id()));
                        } else {
                            // If no specific target, move there
                            issueMoveCommand(entity, targetPos[0], targetPos[1], allEntities);
                        }
                    }
                }
            }
            case "REGROUP" -> {
                if (cmd.target() != null) {
                    float[] targetPos = parseSectorCoords(cmd.target());
                    if (targetPos != null) {
                        issueMoveCommand(entity, targetPos[0], targetPos[1], allEntities);
                    }
                } else {
                    defend(entity, allEntities);
                }
            }
            case "DEFEND" -> defend(entity, allEntities);
            case "HARASS" -> {
                if (cmd.target() != null) {
                    float[] targetPos = parseSectorCoords(cmd.target());
                    if (targetPos != null) {
                        harass(entity, targetPos[0], targetPos[1], allEntities);
                    }
                }
            }
        }
    }

    private void harass(Entity entity, float targetX, float targetY, Collection<Entity> allEntities) {
        // Find nearest enemy carrier in or near the target sector
        Entity targetCarrier = allEntities.stream()
                .filter(e -> e.type() == Entity.Type.CARRIER && !myColor.name().equalsIgnoreCase(e.color()) && !"D".equals(e.status()))
                .min(Comparator.comparingDouble(e -> Math.hypot(e.px() - targetX, e.py() - targetY)))
                .orElse(null);

        if (targetCarrier != null) {
            float dx = entity.px() - targetCarrier.px();
            float dy = entity.py() - targetCarrier.py();
            float dist = (float) Math.hypot(dx, dy);
            float harassDistance = 70.0f; // Stay at a distance to intercept launches

            if (dist > harassDistance + 10) {
                // Move closer to the carrier
                float nx = targetCarrier.px() + (dx / dist) * harassDistance;
                float ny = targetCarrier.py() + (dy / dist) * harassDistance;
                issueMoveCommand(entity, nx, ny, allEntities);
            } else if (dist < harassDistance - 10) {
                // Back off a bit
                float nx = targetCarrier.px() + (dx / dist) * harassDistance;
                float ny = targetCarrier.py() + (dy / dist) * harassDistance;
                issueMoveCommand(entity, nx, ny, allEntities);
            } else {
                // Maintain position, maybe look for targets to intercept
                Entity nearestFighter = allEntities.stream()
                        .filter(e -> e.type() == Entity.Type.FIGHTER && !myColor.name().equalsIgnoreCase(e.color()) && !"D".equals(e.status()))
                        .filter(e -> Math.hypot(e.px() - targetCarrier.px(), e.py() - targetCarrier.py()) < 50)
                        .min(Comparator.comparingDouble(e -> Math.hypot(e.px() - entity.px(), e.py() - entity.py())))
                        .orElse(null);
                
                if (nearestFighter != null) {
                    order(new Order(entity.id(), OrderType.ATTACK, nearestFighter.id()));
                }
            }
        } else {
            // No carrier found near target, just move to target sector center
            issueMoveCommand(entity, targetX, targetY, allEntities);
        }
    }

    private void issueMoveCommand(Entity entity, float targetX, float targetY, Collection<Entity> allEntities) {
        // MOVE coordinates are relative to carrier
        Entity myCarrier = allEntities.stream()
                .filter(e -> e.type() == Entity.Type.CARRIER && myColor.name().equalsIgnoreCase(e.color()) && !"D".equals(e.status()))
                .findFirst().orElse(null);
        if (myCarrier != null) {
            float relX = targetX - myCarrier.px();
            float relY = targetY - myCarrier.py();
            order(new Order(entity.id(), OrderType.MOVE, relX + "|" + relY));
        }
    }

    private void applyPassiveCarrierAvoidance(Collection<Entity> allEntities) {
        Entity myCarrier = allEntities.stream()
                .filter(e -> e.type() == Entity.Type.CARRIER && myColor.name().equalsIgnoreCase(e.color()) && !"D".equals(e.status()))
                .findFirst().orElse(null);
        if (myCarrier == null) return;

        float[] currentPos = new float[]{myCarrier.px(), myCarrier.py()};
        float[] adjusted = calculateCarrierManeuver(myCarrier, allEntities);

        if (adjusted[0] != currentPos[0] || adjusted[1] != currentPos[1]) {
            log.info("Carrier maneuver: moving from [{}, {}] to [{}, {}]", 
                    currentPos[0], currentPos[1], adjusted[0], adjusted[1]);
            issueMoveCommand(myCarrier, adjusted[0], adjusted[1], allEntities);
        }
    }

    private float[] calculateCarrierManeuver(Entity myCarrier, Collection<Entity> allEntities) {
        float curX = myCarrier.px();
        float curY = myCarrier.py();
        float safeDistance = 15.0f;

        // 1. Priority: Border avoidance (Stay inside the world with a safety margin)
        float triggerDistance = -1;
        float avoidanceTargetX = curX;
        float avoidanceTargetY = curY;

        // Check each border: left, right, top, bottom
        // Left border (x=0)
        float distLeft = curX;
        float tdLeft = calculateBorderTriggerDistance(distLeft, -1, 0, myCarrier.vx(), myCarrier.vy());
        if (tdLeft > 0 && distLeft < tdLeft) {
            triggerDistance = tdLeft;
            avoidanceTargetX = tdLeft + 5.0f; // Move away from left
        }

        // Right border (x=worldWidth)
        float distRight = settings.worldWidth() - curX;
        float tdRight = calculateBorderTriggerDistance(distRight, 1, 0, myCarrier.vx(), myCarrier.vy());
        if (tdRight > 0 && distRight < tdRight && (triggerDistance == -1 || tdRight > triggerDistance)) {
            triggerDistance = tdRight;
            avoidanceTargetX = settings.worldWidth() - tdRight - 5.0f;
        }

        // Top border (y=0)
        float distTop = curY;
        float tdTop = calculateBorderTriggerDistance(distTop, 0, -1, myCarrier.vx(), myCarrier.vy());
        if (tdTop > 0 && distTop < tdTop && (triggerDistance == -1 || tdTop > triggerDistance)) {
            triggerDistance = tdTop;
            avoidanceTargetY = tdTop + 5.0f;
        }

        // Bottom border (y=worldHeight)
        float distBottom = settings.worldHeight() - curY;
        float tdBottom = calculateBorderTriggerDistance(distBottom, 0, 1, myCarrier.vx(), myCarrier.vy());
        if (tdBottom > 0 && distBottom < tdBottom && (triggerDistance == -1 || tdBottom > triggerDistance)) {
            triggerDistance = tdBottom;
            avoidanceTargetY = settings.worldHeight() - tdBottom - 5.0f;
        }

        if (triggerDistance != -1) {
            log.info("Carrier border avoidance triggered! distance: {}, target: [{}, {}]", triggerDistance, avoidanceTargetX, avoidanceTargetY);
            return new float[]{avoidanceTargetX, avoidanceTargetY};
        }

        // 2. Kiting logic using a weighted avoidance vector
        float avoidX = 0, avoidY = 0;

        // A. Avoid enemy carriers (Strong weight)
        List<Entity> enemyCarriers = allEntities.stream()
                .filter(e -> e.type() == Entity.Type.CARRIER && !myColor.name().equalsIgnoreCase(e.color()) && !"D".equals(e.status()))
                .sorted(Comparator.comparingDouble(e -> Math.pow(e.px() - curX, 2) + Math.pow(e.py() - curY, 2)))
                .toList();

        if (enemyCarriers.size() >= 2) {
            // Requirement: find two closest enemy carriers and move away from the line joining them
            Entity e1 = enemyCarriers.get(0);
            Entity e2 = enemyCarriers.get(1);
            
            float x1 = e1.px(), y1 = e1.py();
            float x2 = e2.px(), y2 = e2.py();
            
            // Vector of the line joining them
            float lx = x2 - x1;
            float ly = y2 - y1;
            float lLenSq = lx * lx + ly * ly;
            
            if (lLenSq > 1.0f) {
                // Find projection of current position onto the line
                float t = ((curX - x1) * lx + (curY - y1) * ly) / lLenSq;
                float projX = x1 + t * lx;
                float projY = y1 + t * ly;
                
                // Vector from projection to current position (perpendicular to the line)
                float perpX = curX - projX;
                float perpY = curY - projY;
                float perpDist = (float) Math.sqrt(perpX * perpX + perpY * perpY);
                
                if (perpDist > 0.1f) {
                    // Move away from the line
                    float weight = 3000.0f / (perpDist + 20.0f);
                    avoidX += (perpX / perpDist) * weight;
                    avoidY += (perpY / perpDist) * weight;
                } else {
                    // If we are exactly on the line, move in a perpendicular direction
                    float weight = 3000.0f / 20.0f;
                    avoidX += (-ly / (float) Math.sqrt(lLenSq)) * weight;
                    avoidY += (lx / (float) Math.sqrt(lLenSq)) * weight;
                }
            }
        }

        // Also avoid each carrier individually to ensure we don't get too close to any one of them
        for (Entity enemy : enemyCarriers) {
            float dx = curX - enemy.px();
            float dy = curY - enemy.py();
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist > 0 && dist < 1000) {
                float weight = 2000.0f / (dist + 10.0f);
                avoidX += (dx / dist) * weight;
                avoidY += (dy / dist) * weight;
            }
        }

        // B. Avoid high threat sectors (Medium weight)
        if (battleMap != null) {
            float sectorWidth = settings.worldWidth() / battleMap.getCols();
            float sectorHeight = settings.worldHeight() / battleMap.getRows();

            for (int r = 0; r < battleMap.getRows(); r++) {
                for (int c = 0; c < battleMap.getCols(); c++) {
                    Sector sector = battleMap.getSector(r, c);
                    float sectorCenterX = (c + 0.5f) * sectorWidth;
                    float sectorCenterY = (r + 0.5f) * sectorHeight;

                    float dx = curX - sectorCenterX;
                    float dy = curY - sectorCenterY;
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);

                    for (ColorSectorStatus status : sector.colorStatuses().values()) {
                        if (status.threatLevel() == ThreatLevel.HIGH || status.threatLevel() == ThreatLevel.MEDIUM) {
                            float weight = (status.threatLevel() == ThreatLevel.HIGH ? 1000.0f : 500.0f) / (dist + 50.0f);
                            if (dist > 0) {
                                avoidX += (dx / dist) * weight;
                                avoidY += (dy / dist) * weight;
                            }
                        }
                    }
                }
            }
        }

        // C. Avoid nearby missiles (Highest weight)
        List<Entity> missiles = allEntities.stream()
                .filter(e -> e.type() == Entity.Type.MISSILE && !myColor.name().equalsIgnoreCase(e.color()))
                .toList();

        for (Entity missile : missiles) {
            float dx = curX - missile.px();
            float dy = curY - missile.py();
            float distSq = dx * dx + dy * dy;
            float dist = (float) Math.sqrt(distSq);
            if (dist > 0 && dist < 400) { // Missiles are very dangerous within 400 units
                float weight = 5000.0f / (dist + 5.0f);
                avoidX += (dx / dist) * weight;
                avoidY += (dy / dist) * weight;
            }
        }

        // D. Center bias (Very weak weight to avoid getting stuck in corners)
        float centerX = settings.worldWidth() / 2.0f;
        float centerY = settings.worldHeight() / 2.0f;
        float toCenterX = centerX - curX;
        float toCenterY = centerY - curY;
        float distToCenter = (float) Math.sqrt(toCenterX * toCenterX + toCenterY * toCenterY);
        if (distToCenter > 0) {
            avoidX += (toCenterX / distToCenter) * 5.0f;
            avoidY += (toCenterY / distToCenter) * 5.0f;
        }

        // Final avoidance vector normalization
        float totalAvoid = (float) Math.sqrt(avoidX * avoidX + avoidY * avoidY);
        if (totalAvoid > 0.1f) {
            avoidX /= totalAvoid;
            avoidY /= totalAvoid;
        } else {
            // No significant threats, stay put or move slightly towards center
            return new float[]{curX, curY};
        }

        // Move some distance in the avoid direction
        float moveDist = 60.0f;
        float targetX = curX + avoidX * moveDist;
        float targetY = curY + avoidY * moveDist;

        // Final check for borders
        targetX = Math.max(safeDistance, Math.min(settings.worldWidth() - safeDistance, targetX));
        targetY = Math.max(safeDistance, Math.min(settings.worldHeight() - safeDistance, targetY));

        return new float[]{targetX, targetY};
    }

    private float[] applyCollisionAvoidance(float targetX, float targetY, Collection<Entity> allEntities) {
        float adjustedX = targetX;
        float adjustedY = targetY;

        // 1. Border avoidance
        float margin = 100;
        adjustedX = Math.max(margin, Math.min(settings.worldWidth() - margin, adjustedX));
        adjustedY = Math.max(margin, Math.min(settings.worldHeight() - margin, adjustedY));

        // 2. High density avoidance
        int r = (int) (adjustedY / (settings.worldHeight() / battleMap.getRows()));
        int c = (int) (adjustedX / (settings.worldWidth() / battleMap.getCols()));
        r = Math.max(0, Math.min(battleMap.getRows() - 1, r));
        c = Math.max(0, Math.min(battleMap.getCols() - 1, c));

        if (battleMap.getEntityCount(r, c) > 15) { // Threshold for high density
            log.info("High density detected in sector {}x{}, looking for alternative", r, c);
            // Look at neighbor sectors
            int bestR = r, bestC = c;
            int minCount = battleMap.getEntityCount(r, c);

            for (int dr = -1; dr <= 1; dr++) {
                for (int dc = -1; dc <= 1; dc++) {
                    int nr = r + dr;
                    int nc = c + dc;
                    if (nr >= 0 && nr < battleMap.getRows() && nc >= 0 && nc < battleMap.getCols()) {
                        int count = battleMap.getEntityCount(nr, nc);
                        if (count < minCount) {
                            minCount = count;
                            bestR = nr;
                            bestC = nc;
                        }
                    }
                }
            }

            if (bestR != r || bestC != c) {
                float[] betterPos = parseSectorCoords(bestR + "x" + bestC);
                if (betterPos != null) {
                    adjustedX = betterPos[0];
                    adjustedY = betterPos[1];
                }
            }
        }

        return new float[]{adjustedX, adjustedY};
    }

    Entity findTargetInSector(float centerX, float centerY, Collection<Entity> entities) {
        float sectorW = settings.worldWidth() / battleMap.getCols();
        float sectorH = settings.worldHeight() / battleMap.getRows();
        
        return entities.stream()
                .filter(e -> !myColor.name().equalsIgnoreCase(e.color()) && !"D".equals(e.status()))
                .filter(e -> Math.abs(e.px() - centerX) <= sectorW / 2 && Math.abs(e.py() - centerY) <= sectorH / 2)
                .min(Comparator.comparingInt((Entity e) -> e.type() == Entity.Type.CARRIER ? 0 : 1)
                        .thenComparingDouble(e -> {
                            try {
                                return Double.parseDouble(e.status());
                            } catch (NumberFormatException ex) {
                                return 999.0; // Treat non-numeric as high health
                            }
                        })
                        .thenComparingDouble(e -> Math.pow(e.px() - centerX, 2) + Math.pow(e.py() - centerY, 2)))
                .orElse(null);
    }

    float[] parseSectorCoords(String coords) {
        try {
            String[] parts = coords.split("x");
            int r = Integer.parseInt(parts[0]);
            int c = Integer.parseInt(parts[1]);
            float sectorW = settings.worldWidth() / battleMap.getCols();
            float sectorH = settings.worldHeight() / battleMap.getRows();
            return new float[]{ (c + 0.5f) * sectorW, (r + 0.5f) * sectorH };
        } catch (Exception e) {
            return null;
        }
    }

    private void executeEmergencyScreen(Collection<Entity> entities) {
        log.info("Emergency Screen active - all fighters orbiting carrier tightly");
        entities.stream()
                .filter(e -> e.type() == Entity.Type.FIGHTER && 
                              myColor.name().equalsIgnoreCase(e.color()) && 
                              !"D".equals(e.status()) && 
                              !"C".equals(e.status()))
                .forEach(f -> defend(f, entities, EMERGENCY_SCREEN_DISTANCE));
    }

    private boolean isEmergencyScreenRequired(Collection<Entity> entities) {
        Entity myCarrier = entities.stream()
                .filter(e -> e.type() == Entity.Type.CARRIER && 
                              myColor.name().equalsIgnoreCase(e.color()) && 
                              !"D".equals(e.status()))
                .findFirst().orElse(null);
        if (myCarrier == null) return false;

        // Threshold 1: Health
        try {
            int health = Integer.parseInt(myCarrier.status());
            if (health < EMERGENCY_HEALTH_THRESHOLD) return true;
        } catch (NumberFormatException ignored) {}

        // Threshold 2: Very close high threat
        for (int r = 0; r < battleMap.getRows(); r++) {
            for (int c = 0; c < battleMap.getCols(); c++) {
                Sector sector = battleMap.getSector(r, c);
                boolean hasHighThreat = sector.colorStatuses().values().stream()
                        .anyMatch(s -> s.threatLevel() == ThreatLevel.HIGH);
                
                if (hasHighThreat) {
                    float[] pos = parseSectorCoords(r + "x" + c);
                    float dx = pos[0] - myCarrier.px();
                    float dy = pos[1] - myCarrier.py();
                    float distSq = dx * dx + dy * dy;
                    if (distSq < EMERGENCY_THREAT_DISTANCE * EMERGENCY_THREAT_DISTANCE) {
                        return true;
                    }
                }
            }
        }
        
        return false;
    }

    void defend(Entity entity, Collection<Entity> allEntities) {
        defend(entity, allEntities, -1);
    }

    void defend(Entity entity, Collection<Entity> allEntities, float forcedDistance) {
        // stay in current sector but placed between carrier and closest threat sector
        Entity myCarrier = allEntities.stream()
                .filter(e -> e.type() == Entity.Type.CARRIER && myColor.name().equalsIgnoreCase(e.color()))
                .findFirst().orElse(null);
        if (myCarrier == null) return;

        // Find closest threat
        float closestThreatDistSq = Float.MAX_VALUE;
        float threatX = -1, threatY = -1;

        for (int r = 0; r < battleMap.getRows(); r++) {
            for (int c = 0; c < battleMap.getCols(); c++) {
                Sector sector = battleMap.getSector(r, c);
                boolean hasHighThreat = sector.colorStatuses().values().stream()
                        .anyMatch(s -> s.threatLevel() == ThreatLevel.HIGH || s.threatLevel() == ThreatLevel.MEDIUM);
                
                if (hasHighThreat) {
                    float[] pos = parseSectorCoords(r + "x" + c);
                    float d2 = (pos[0] - myCarrier.px()) * (pos[0] - myCarrier.px()) + (pos[1] - myCarrier.py()) * (pos[1] - myCarrier.py());
                    if (d2 < closestThreatDistSq) {
                        closestThreatDistSq = d2;
                        threatX = pos[0];
                        threatY = pos[1];
                    }
                }
            }
        }

        if (threatX == -1) {
            // No threat sectors, use enemy carriers
            Entity enemyCarrier = allEntities.stream()
                    .filter(e -> e.type() == Entity.Type.CARRIER && !myColor.name().equalsIgnoreCase(e.color()) && !"D".equals(e.status()))
                    .findFirst().orElse(null);
            if (enemyCarrier != null) {
                threatX = enemyCarrier.px();
                threatY = enemyCarrier.py();
            }
        }

        if (threatX != -1) {
            // Position between carrier and threat
            float dirX = threatX - myCarrier.px();
            float dirY = threatY - myCarrier.py();
            float len = (float) Math.sqrt(dirX * dirX + dirY * dirY);
            if (len > 0) {
                float distance;
                if (forcedDistance > 0) {
                    distance = forcedDistance;
                } else {
                    // Determine distance based on entity ID to create layers/variety
                    // We use hash of ID to consistently assign a fighter to a layer
                    int hash = Math.abs(entity.id().hashCode());
                    boolean emergency = isEmergencyScreenRequired(allEntities);
                    
                    if (hash % 2 == 0) {
                        distance = emergency ? 40 : 80; // Inner layer
                    } else {
                        distance = emergency ? 80 : 160; // Outer layer
                    }

                    // Add some small individual offset to avoid overlapping perfectly
                    float offset = (hash % 10 - 5) * (emergency ? 2 : 5); // -10 to 10 or -25 to 25
                    distance += offset;
                }

                float targetX = myCarrier.px() + (dirX / len) * distance;
                float targetY = myCarrier.py() + (dirY / len) * distance;
                order(new Order(entity.id(), OrderType.MOVE, (targetX - myCarrier.px()) + "|" + (targetY - myCarrier.py())));
            }
        }
    }

    private void executeDefensiveManeuvers(Collection<Entity> entities) {
        executeMissileEvasion(entities);
        for (Entity e : entities) {
            if (myColor.name().equalsIgnoreCase(e.color()) && !"D".equals(e.status()) && !"C".equals(e.status())) {
                defend(e, entities);
            }
        }
    }

    private void executeMissileEvasion(Collection<Entity> entities) {
        Entity myCarrier = entities.stream()
                .filter(e -> e.type() == Entity.Type.CARRIER && myColor.name().equalsIgnoreCase(e.color()) && !"D".equals(e.status()))
                .findFirst().orElse(null);

        if (myCarrier == null) return;

        float evadeX = 0;
        float evadeY = 0;
        int missileCount = 0;

        for (Entity entity : entities) {
            if (entity.type() == Entity.Type.MISSILE && !myColor.name().equalsIgnoreCase(entity.color()) && !"D".equals(entity.status())) {
                float toCarrierX = myCarrier.px() - entity.px();
                float toCarrierY = myCarrier.py() - entity.py();
                float distSq = toCarrierX * toCarrierX + toCarrierY * toCarrierY;

                if (distSq < 160000) { // 400 units
                    float dot = entity.vx() * toCarrierX + entity.vy() * toCarrierY;
                    if (dot > 0) {
                        // Missile is moving towards carrier. Move away from missile's current position.
                        float dist = (float) Math.sqrt(distSq);
                        if (dist > 0) {
                            evadeX += (toCarrierX / dist);
                            evadeY += (toCarrierY / dist);
                            missileCount++;
                        }
                    }
                }
            }
        }

        if (missileCount > 0) {
            float moveDist = 100; // Move 100 units away
            float len = (float) Math.sqrt(evadeX * evadeX + evadeY * evadeY);
            if (len > 0) {
                float targetRelX = (evadeX / len) * moveDist;
                float targetRelY = (evadeY / len) * moveDist;

                // Ensure we don't move out of bounds
                float targetAbsX = myCarrier.px() + targetRelX;
                float targetAbsY = myCarrier.py() + targetRelY;

                targetAbsX = Math.max(50, Math.min(settings.worldWidth() - 50, targetAbsX));
                targetAbsY = Math.max(50, Math.min(settings.worldHeight() - 50, targetAbsY));

                log.info("Missile evasion: moving carrier to relative {}|{}", targetAbsX - myCarrier.px(), targetAbsY - myCarrier.py());
                order(new Order(myCarrier.id(), OrderType.MOVE, (targetAbsX - myCarrier.px()) + "|" + (targetAbsY - myCarrier.py())));
            }
        }
    }

    private long calculateAdaptiveAiCooldown() {
        ThreatLevel maxThreat = ThreatLevel.NONE;
        for (int r = 0; r < battleMap.getRows(); r++) {
            for (int c = 0; c < battleMap.getCols(); c++) {
                Sector sector = battleMap.getSector(r, c);
                for (ColorSectorStatus status : sector.colorStatuses().values()) {
                    if (status.threatLevel().ordinal() > maxThreat.ordinal()) {
                        maxThreat = status.threatLevel();
                    }
                }
            }
        }

        return switch (maxThreat) {
            case HIGH -> 1000L;
            case MEDIUM -> 1500L;
            default -> 2000L;
        };
    }

    private float calculateBorderTriggerDistance(float distance, float borderNormalX, float borderNormalY, float vx, float vy) {
        // Dot product to see if we are moving towards the border
        // borderNormal is pointing OUT of the world:
        // Left: (-1, 0), Right: (1, 0), Top: (0, -1), Bottom: (0, 1)
        float dot = vx * borderNormalX + vy * borderNormalY;

        if (dot <= 0) {
            // Moving away from or parallel to border
            return -1;
        }

        // Approach angle: angle between velocity and border normal (0 to 90 degrees)
        float vLen = (float) Math.sqrt(vx * vx + vy * vy);
        if (vLen < 0.001f) return -1;

        float cosTheta = dot / vLen;
        // Clamp cosTheta to [0, 1] just in case
        cosTheta = Math.max(0, Math.min(1, cosTheta));
        double angleRad = Math.acos(cosTheta);
        double angleDeg = Math.toDegrees(angleRad);

        // Approach angle to border:
        // If angle with normal is 0, angle with border is 90 (head-on)
        // If angle with normal is 90, angle with border is 0 (parallel)
        double angleToBorder = 90.0 - angleDeg;

        if (angleToBorder < 5) return 5.0f;
        if (angleToBorder <= 45) return 10.0f;
        if (angleToBorder <= 80) return 15.0f;
        if (angleToBorder <= 90) return 20.0f;

        return -1;
    }

    private void initializeBattleMap() {
        int rows = 3;
        int cols = 3;

        Path confPath = Path.of("battlejar.conf");
        if (Files.exists(confPath)) {
            try (Reader r = Files.newBufferedReader(confPath, StandardCharsets.UTF_8)) {
                Properties props = new Properties();
                props.load(r);
                rows = Integer.parseInt(props.getProperty("map.rows", "3"));
                cols = Integer.parseInt(props.getProperty("map.cols", "3"));
            } catch (IOException | NumberFormatException e) {
                log.warn("Could not read map dimensions from battlejar.conf, using defaults: {}", e.getMessage());
            }
        }

        battleMap = new BattleMap(rows, cols, settings);
        log.info("BattleMap initialized with {} rows and {} columns", rows, cols);
    }
}
