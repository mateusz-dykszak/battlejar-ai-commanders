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
        applyPassiveCarrierAvoidance(entities);

        long now = System.currentTimeMillis();
        if (now - lastAiTick > currentAiCooldownMs) {
            lastAiTick = now;
            try {
                String aiOutput = aiAgent.getCommandsFromAI(battleMap, myColor);
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

        if (myCarrier != null && response.carrierCommand() != null) {
            issueCommand(myCarrier, response.carrierCommand(), entities);
        }

        // Group my fighters by sector
        Map<String, List<Entity>> fightersBySector = new HashMap<>();
        for (Entity e : entities) {
            if (e.type() == Entity.Type.FIGHTER && myColor.name().equalsIgnoreCase(e.color()) && !"D".equals(e.status())) {
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
        
        // Fighters without AI instructions
        fightersBySector.forEach((sectorKey, sectorFighters) -> {
            if (!response.sectorCommands().containsKey(sectorKey)) {
                // Auto-regroup if presence is SMALL
                String[] parts = sectorKey.split("x");
                int r = Integer.parseInt(parts[0]);
                int c = Integer.parseInt(parts[1]);
                Sector sector = battleMap.getSector(r, c);
                ColorSectorStatus status = sector.colorStatuses().get(myColor);
                
                if (status != null && status.presence() == it.battlejar.commander.map.FleetPresence.SMALL) {
                    // Find a sector with SIGNIFICANT or DOMINANCE presence to regroup to
                    String targetSector = findRegroupTarget(r, c);
                    for (Entity f : sectorFighters) {
                        issueCommand(f, new AICommandParser.Command("REGROUP", targetSector), entities);
                    }
                } else {
                    // Default to defend
                    for (Entity f : sectorFighters) {
                        defend(f, entities);
                    }
                }
            }
        });
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
        if (entity.type() == Entity.Type.CARRIER && "MOVE".equals(cmd.type())) {
            float[] targetPos = parseSectorCoords(cmd.target());
            if (targetPos != null) {
                float[] adjusted = applyCollisionAvoidance(targetPos[0], targetPos[1], allEntities);
                if (adjusted[0] != targetPos[0] || adjusted[1] != targetPos[1]) {
                    log.info("Carrier move adjusted for collision/border avoidance: from {} to target pos [{}, {}]", 
                            cmd.target(), adjusted[0], adjusted[1]);
                    // Create a new command with specific coordinates if possible, but issueCommand uses sector coords string.
                    // Let's modify issueCommand to handle specific targetPos if it's already adjusted.
                    issueMoveCommand(entity, adjusted[0], adjusted[1], allEntities);
                    return;
                }
            }
        }
        
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
        float[] adjusted = applyCollisionAvoidance(currentPos[0], currentPos[1], allEntities);

        if (adjusted[0] != currentPos[0] || adjusted[1] != currentPos[1]) {
            log.info("Passive carrier avoidance: moving from [{}, {}] to [{}, {}]", 
                    currentPos[0], currentPos[1], adjusted[0], adjusted[1]);
            issueMoveCommand(myCarrier, adjusted[0], adjusted[1], allEntities);
        }
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

    void defend(Entity entity, Collection<Entity> allEntities) {
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
                // Determine distance based on entity ID to create layers/variety
                // We use hash of ID to consistently assign a fighter to a layer
                int hash = Math.abs(entity.id().hashCode());
                float distance;
                if (hash % 2 == 0) {
                    distance = 40; // Inner layer
                } else {
                    distance = 80; // Outer layer
                }

                // Add some small individual offset to avoid overlapping perfectly
                float offset = (hash % 10 - 5) * 2; // -10 to 10
                distance += offset;

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
