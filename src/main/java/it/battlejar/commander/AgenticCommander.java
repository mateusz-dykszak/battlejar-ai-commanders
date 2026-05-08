package it.battlejar.commander;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.client.AbstractCommander;
import it.battlejar.commander.ai.AIAgent;
import it.battlejar.commander.ai.AICommandParser;
import it.battlejar.commander.map.BattleMap;
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
    private static final long AI_COOLDOWN_MS = 2000; // Run AI every 2 seconds

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

        long now = System.currentTimeMillis();
        if (now - lastAiTick > AI_COOLDOWN_MS) {
            lastAiTick = now;
            try {
                String aiOutput = aiAgent.getCommandsFromAI(battleMap, myColor);
                log.info("AI Output: {}", aiOutput);
                AICommandParser.AIResponse aiResponse = AICommandParser.parse(aiOutput);
                executeAiResponse(aiResponse, entities);
            } catch (Exception e) {
                log.error("Error getting or executing AI commands", e);
                executeDefensiveManeuvers(entities);
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
        
        // Fighters without AI instructions -> defend
        fightersBySector.forEach((sectorKey, sectorFighters) -> {
            if (!response.sectorCommands().containsKey(sectorKey)) {
                for (Entity f : sectorFighters) {
                    defend(f, entities);
                }
            }
        });
    }

    void issueCommand(Entity entity, AICommandParser.Command cmd, Collection<Entity> allEntities) {
        switch (cmd.type()) {
            case "MOVE" -> {
                if (cmd.target() != null) {
                    float[] targetPos = parseSectorCoords(cmd.target());
                    if (targetPos != null) {
                        // MOVE coordinates are relative to carrier
                        Entity myCarrier = allEntities.stream()
                                .filter(e -> e.type() == Entity.Type.CARRIER && myColor.name().equalsIgnoreCase(e.color()))
                                .findFirst().orElse(null);
                        if (myCarrier != null) {
                            float relX = targetPos[0] - myCarrier.px();
                            float relY = targetPos[1] - myCarrier.py();
                            order(new Order(entity.id(), OrderType.MOVE, relX + "|" + relY));
                        }
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
                            issueCommand(entity, new AICommandParser.Command("MOVE", cmd.target()), allEntities);
                        }
                    }
                }
            }
            case "DEFEND" -> defend(entity, allEntities);
        }
    }

    Entity findTargetInSector(float centerX, float centerY, Collection<Entity> entities) {
        float sectorW = settings.worldWidth() / battleMap.getCols();
        float sectorH = settings.worldHeight() / battleMap.getRows();
        
        return entities.stream()
                .filter(e -> !myColor.name().equalsIgnoreCase(e.color()) && !"D".equals(e.status()))
                .filter(e -> Math.abs(e.px() - centerX) <= sectorW / 2 && Math.abs(e.py() - centerY) <= sectorH / 2)
                .min(Comparator.comparingInt((Entity e) -> e.type() == Entity.Type.CARRIER ? 0 : 1)
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
        for (Entity e : entities) {
            if (myColor.name().equalsIgnoreCase(e.color()) && !"D".equals(e.status()) && !"C".equals(e.status())) {
                defend(e, entities);
            }
        }
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
