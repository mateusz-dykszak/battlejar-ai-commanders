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

    // Missile arming and safety constants
    private static final float FIGHTER_MISSILE_ARMING_TIME_S = 1.0f;
    private static final float CARRIER_MISSILE_ARMING_TIME_S = 2.0f;
    private static final float ESTIMATED_MISSILE_SPEED = 100.0f; // units per second
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

        // Periodic collision check for carrier even if no AI command is active
        applyPassiveCarrierAvoidance(entities);
        // lastAiTick = System.currentTimeMillis(); // DO NOT RESET HERE - Bug fixed

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
                
                // Automatic fighter missile fire
                autoFireFighterMissiles(entities);

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
            case "FIRE_MISSILE" -> {
                if (cmd.target() != null) {
                    float[] targetPos = parseSectorCoords(cmd.target());
                    if (targetPos != null) {
                        if (canFireMissile(entity, targetPos[0], targetPos[1], allEntities)) {
                            if (entity.type() == Entity.Type.CARRIER) {
                                // For carrier, FIRE_MISSILE details can be "x|y" direction
                                float dx = targetPos[0] - entity.px();
                                float dy = targetPos[1] - entity.py();
                                order(new Order(entity.id(), OrderType.FIRE_MISSILE, dx + "|" + dy));
                            }
                            // Fighters do not fire missiles via AI commands anymore
                        } else {
                            log.info("Skipping FIRE_MISSILE for {} - target too close for arming", entity.id());
                        }
                    }
                }
            }
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

    private void autoFireFighterMissiles(Collection<Entity> entities) {
        List<Entity> myFighters = entities.stream()
                .filter(e -> e.type() == Entity.Type.FIGHTER && myColor.name().equalsIgnoreCase(e.color()) && !"D".equals(e.status()) && !"C".equals(e.status()))
                .toList();

        List<Entity> enemyCarriers = entities.stream()
                .filter(e -> e.type() == Entity.Type.CARRIER && !myColor.name().equalsIgnoreCase(e.color()) && !"D".equals(e.status()))
                .toList();

        if (myFighters.isEmpty() || enemyCarriers.isEmpty()) {
            return;
        }

        for (Entity fighter : myFighters) {
            if (fighter.missiles() <= 0) continue;

            for (Entity carrier : enemyCarriers) {
                float dx = carrier.px() - fighter.px();
                float dy = carrier.py() - fighter.py();
                float dist = (float) Math.hypot(dx, dy);

                // Check arming distance (1s * estimated speed)
                if (dist < FIGHTER_MISSILE_ARMING_TIME_S * ESTIMATED_MISSILE_SPEED) {
                    continue;
                }

                // Check if in front: dot product of (dx, dy) and velocity (vx, vy)
                // We normalize them to get the cosine of the angle
                float vLen = (float) Math.hypot(fighter.vx(), fighter.vy());
                if (vLen < 1.0f) continue; // Not moving much, skip auto-fire or use heading if available (but it's not)

                float dot = (dx * fighter.vx() + dy * fighter.vy()) / (dist * vLen);
                
                // If dot product > 0.98 (~11 degrees tolerance), it's pretty much in front
                if (dot > 0.98f) {
                    log.info("Fighter {} auto-firing missile at enemy carrier {} (dist={}, dot={})", fighter.id(), carrier.id(), dist, dot);
                    order(new Order(fighter.id(), OrderType.FIRE_MISSILE));
                    break; // Only one missile at a time
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

    private boolean canFireMissile(Entity entity, float targetX, float targetY, Collection<Entity> allEntities) {
        float armingTime = (entity.type() == Entity.Type.CARRIER) ? CARRIER_MISSILE_ARMING_TIME_S : FIGHTER_MISSILE_ARMING_TIME_S;
        float minArmingDist = armingTime * ESTIMATED_MISSILE_SPEED;
        
        // Check distance to all enemy carriers
        for (Entity e : allEntities) {
            if (e.type() == Entity.Type.CARRIER && !myColor.name().equalsIgnoreCase(e.color()) && !"D".equals(e.status())) {
                float dist = (float) Math.hypot(entity.px() - e.px(), entity.py() - e.py());
                if (dist < minArmingDist) {
                    return false;
                }
            }
        }
        
        return true;
    }

    private void issueMoveCommand(Entity entity, float targetX, float targetY, Collection<Entity> allEntities) {
        // MOVE coordinates are relative to carrier
        Entity myCarrier = allEntities.stream()
                .filter(e -> e.type() == Entity.Type.CARRIER && myColor.name().equalsIgnoreCase(e.color()))
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

        float[] adjusted = calculateCarrierManeuver(myCarrier, allEntities);

        if (adjusted != null) {
            log.info("Carrier maneuver: moving from [{}, {}] to [{}, {}]", 
                    myCarrier.px(), myCarrier.py(), adjusted[0], adjusted[1]);
            issueMoveCommand(myCarrier, adjusted[0], adjusted[1], allEntities);
        }
    }

    private float[] calculateCarrierManeuver(Entity myCarrier, Collection<Entity> allEntities) {
        float curX = myCarrier.px();
        float curY = myCarrier.py();
        float safeDistance = 25.0f;

        // 1. Priority: Border avoidance (Stay inside the world with a safety margin)
        boolean triggerLeft = false;
        boolean triggerRight = false;
        boolean triggerTop = false;
        boolean triggerBottom = false;

        float passiveTd = (Math.abs(myCarrier.vx()) < 0.1f && Math.abs(myCarrier.vy()) < 0.1f) ? safeDistance : -1;

        // Check Left
        float distLeft = curX;
        float tdLeft = calculateBorderTriggerDistance(distLeft, -1, 0, myCarrier.vx(), myCarrier.vy());
        if ((tdLeft > 0 && distLeft < tdLeft) || (passiveTd > 0 && distLeft < passiveTd)) {
            triggerLeft = true;
        }

        // Check Right
        float distRight = settings.worldWidth() - curX;
        float tdRight = calculateBorderTriggerDistance(distRight, 1, 0, myCarrier.vx(), myCarrier.vy());
        if ((tdRight > 0 && distRight < tdRight) || (passiveTd > 0 && distRight < passiveTd)) {
            triggerRight = true;
        }

        // Check Top
        float distTop = curY;
        float tdTop = calculateBorderTriggerDistance(distTop, 0, -1, myCarrier.vx(), myCarrier.vy());
        if ((tdTop > 0 && distTop < tdTop) || (passiveTd > 0 && distTop < passiveTd)) {
            triggerTop = true;
        }

        // Check Bottom
        float distBottom = settings.worldHeight() - curY;
        float tdBottom = calculateBorderTriggerDistance(distBottom, 0, 1, myCarrier.vx(), myCarrier.vy());
        if ((tdBottom > 0 && distBottom < tdBottom) || (passiveTd > 0 && distBottom < passiveTd)) {
            triggerBottom = true;
        }

        if (triggerLeft || triggerRight || triggerTop || triggerBottom) {
            float targetX = curX;
            float targetY = curY;
            float margin = 30.0f; // Distance from border to steer to

            if (triggerLeft && triggerTop) {
                // Corner: Top-Left -> Steer to center
                log.info("Carrier corner avoidance triggered (Top-Left)! Steering to center.");
                return new float[]{settings.worldWidth() / 2, settings.worldHeight() / 2};
            }
            if (triggerLeft && triggerBottom) {
                // Corner: Bottom-Left -> Steer to center
                log.info("Carrier corner avoidance triggered (Bottom-Left)! Steering to center.");
                return new float[]{settings.worldWidth() / 2, settings.worldHeight() / 2};
            }
            if (triggerRight && triggerTop) {
                // Corner: Top-Right -> Steer to center
                log.info("Carrier corner avoidance triggered (Top-Right)! Steering to center.");
                return new float[]{settings.worldWidth() / 2, settings.worldHeight() / 2};
            }
            if (triggerRight && triggerBottom) {
                // Corner: Bottom-Right -> Steer to center
                log.info("Carrier corner avoidance triggered (Bottom-Right)! Steering to center.");
                return new float[]{settings.worldWidth() / 2, settings.worldHeight() / 2};
            }

            // Single border avoidance
            if (triggerLeft) targetX = margin;
            if (triggerRight) targetX = settings.worldWidth() - margin;
            if (triggerTop) targetY = margin;
            if (triggerBottom) targetY = settings.worldHeight() - margin;

            log.info("Carrier border avoidance triggered! Target: [{}, {}]", targetX, targetY);
            return new float[]{targetX, targetY};
        }

        return null;
    }

    private float[] applyCollisionAvoidance(float targetX, float targetY, Collection<Entity> allEntities) {
        float adjustedX = targetX;
        float adjustedY = targetY;

        // 1. Border avoidance
        float margin = 100;

        boolean triggerLeft = adjustedX < margin;
        boolean triggerRight = adjustedX > settings.worldWidth() - margin;
        boolean triggerTop = adjustedY < margin;
        boolean triggerBottom = adjustedY > settings.worldHeight() - margin;

        if ((triggerLeft || triggerRight) && (triggerTop || triggerBottom)) {
            // Corner avoidance for fighters/entities too: steer to center
            adjustedX = settings.worldWidth() / 2;
            adjustedY = settings.worldHeight() / 2;
        } else {
            adjustedX = Math.max(margin, Math.min(settings.worldWidth() - margin, adjustedX));
            adjustedY = Math.max(margin, Math.min(settings.worldHeight() - margin, adjustedY));
        }

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

        // Threshold 2: Very close high threat (REFINED: must be VERY close or carrier is already damaged)
        int health = 100;
        try {
            health = Integer.parseInt(myCarrier.status());
        } catch (NumberFormatException ignored) {}

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
                    
                    // If health is high, only trigger if REALLY close (e.g. 50 units)
                    float triggerDist = (health > 70) ? 50.0f : EMERGENCY_THREAT_DISTANCE;
                    if (distSq < triggerDist * triggerDist) {
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
        for (Entity e : entities) {
            if (myColor.name().equalsIgnoreCase(e.color()) && !"D".equals(e.status()) && !"C".equals(e.status())) {
                defend(e, entities);
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
        log.info("[DEBUG_LOG] calculateBorderTriggerDistance: angleDeg={}, angleToBorder={}", angleDeg, angleToBorder);

        if (angleToBorder < 5) return 10.0f;
        if (angleToBorder <= 45) return 15.0f;
        if (angleToBorder <= 80) return 20.0f;
        if (angleToBorder <= 90) return 25.0f;

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
