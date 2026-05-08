package it.battlejar.commander.map;

import it.battlejar.api.Color;
import it.battlejar.api.Entity;
import it.battlejar.api.GameSettings;

import java.util.*;

public class BattleMap {
    private final int rows;
    private final int cols;
    private final float worldWidth;
    private final float worldHeight;
    private final Sector[][] grid;

    private float myCarrierX = -1;
    private float myCarrierY = -1;

    private static final int SIGNIFICANT_LEVEL = 5;

    public BattleMap(int rows, int cols, GameSettings settings) {
        this.rows = rows;
        this.cols = cols;
        this.worldWidth = settings.worldWidth();
        this.worldHeight = settings.worldHeight();
        this.grid = new Sector[rows][cols];
    }

    public void update(Collection<Entity> entities, Color myColor) {
        // Find our carrier position first
        for (Entity entity : entities) {
            if (entity.type() == Entity.Type.CARRIER && myColor.name().equalsIgnoreCase(entity.color())) {
                myCarrierX = entity.px();
                myCarrierY = entity.py();
                break;
            }
        }

        // Initialize grid with empty sectors
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                grid[r][c] = new Sector(new EnumMap<>(Color.class));
            }
        }

        // Temporary storage for processing
        Map<Color, List<String>>[][] fightersInSector = new Map[rows][cols];
        Map<Color, Boolean>[][] carrierInSector = new Map[rows][cols];
        Map<Color, List<Entity>>[][] entitiesInSector = new Map[rows][cols];

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                fightersInSector[r][c] = new EnumMap<>(Color.class);
                carrierInSector[r][c] = new EnumMap<>(Color.class);
                entitiesInSector[r][c] = new EnumMap<>(Color.class);
            }
        }

        for (Entity entity : entities) {
            if ("D".equals(entity.status())) continue;

            int r = (int) (entity.py() / (worldHeight / rows));
            int c = (int) (entity.px() / (worldWidth / cols));

            // Clamp to grid boundaries
            r = Math.max(0, Math.min(rows - 1, r));
            c = Math.max(0, Math.min(cols - 1, c));

            Color color = Color.valueOf(entity.color().toUpperCase());
            entitiesInSector[r][c].computeIfAbsent(color, k -> new ArrayList<>()).add(entity);

            if (entity.type() == Entity.Type.FIGHTER) {
                fightersInSector[r][c].computeIfAbsent(color, k -> new ArrayList<>()).add(entity.id());
            } else if (entity.type() == Entity.Type.CARRIER) {
                carrierInSector[r][c].put(color, true);
            }
        }

        // Finalize sectors
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Map<Color, ColorSectorStatus> colorStatuses = grid[r][c].colorStatuses();
                
                Set<Color> activeColors = new HashSet<>();
                activeColors.addAll(fightersInSector[r][c].keySet());
                activeColors.addAll(carrierInSector[r][c].keySet());

                for (Color color : activeColors) {
                    List<String> names = fightersInSector[r][c].getOrDefault(color, Collections.emptyList());
                    boolean hasCarrier = carrierInSector[r][c].getOrDefault(color, false);
                    
                    FleetPresence presence = calculatePresence(color, names.size(), fightersInSector[r][c]);
                    
                    ThreatLevel threat = ThreatLevel.NONE;
                    if (color != myColor) {
                        threat = calculateThreat(color, entitiesInSector[r][c].get(color), myColor, entities);
                    }
                    
                    colorStatuses.put(color, new ColorSectorStatus(hasCarrier, presence, names, threat));
                }
            }
        }
    }

    private ThreatLevel calculateThreat(Color enemyColor, List<Entity> enemyEntities, Color myColor, Collection<Entity> allEntities) {
        if (enemyEntities == null || enemyEntities.isEmpty() || myCarrierX == -1) return ThreatLevel.NONE;

        boolean movingTowardsCarrier = false;
        boolean movingTowardsFighters = false;
        boolean missileTargetingCarrier = false;

        List<Entity> myFighters = allEntities.stream()
                .filter(e -> e.type() == Entity.Type.FIGHTER && myColor.name().equalsIgnoreCase(e.color()) && !"D".equals(e.status()))
                .toList();

        for (Entity enemy : enemyEntities) {
            if (enemy.type() == Entity.Type.MISSILE) {
                // Vector from missile to my carrier
                float toCarrierX = myCarrierX - enemy.px();
                float toCarrierY = myCarrierY - enemy.py();
                
                // Dot product of velocity and direction to carrier
                float dotCarrier = enemy.vx() * toCarrierX + enemy.vy() * toCarrierY;
                if (dotCarrier > 0) {
                    float distSq = toCarrierX * toCarrierX + toCarrierY * toCarrierY;
                    if (distSq < 160000) { // 400 units, missiles are dangerous
                        missileTargetingCarrier = true;
                    }
                }
                continue;
            }

            // Vector from enemy to my carrier
            float toCarrierX = myCarrierX - enemy.px();
            float toCarrierY = myCarrierY - enemy.py();
            
            // Dot product of velocity and direction to carrier
            float dotCarrier = enemy.vx() * toCarrierX + enemy.vy() * toCarrierY;
            if (dotCarrier > 0) {
                movingTowardsCarrier = true;
            }

            for (Entity myFighter : myFighters) {
                float toFighterX = myFighter.px() - enemy.px();
                float toFighterY = myFighter.py() - enemy.py();
                float dotFighter = enemy.vx() * toFighterX + enemy.vy() * toFighterY;
                if (dotFighter > 0) {
                    float distSq = toFighterX * toFighterX + toFighterY * toFighterY;
                    if (distSq < 40000) { // arbitrary "close enough" distance (200 units)
                        movingTowardsFighters = true;
                        break;
                    }
                }
            }
            
            if (movingTowardsCarrier && movingTowardsFighters && missileTargetingCarrier) break;
        }

        if (missileTargetingCarrier || movingTowardsCarrier) return ThreatLevel.HIGH;
        if (movingTowardsFighters) return ThreatLevel.MEDIUM;
        
        return ThreatLevel.LOW; // Present but not moving towards us
    }

    private FleetPresence calculatePresence(Color color, int count, Map<Color, List<String>> sectorFighters) {
        if (count == 0) return FleetPresence.NONE;
        
        if (count < SIGNIFICANT_LEVEL) {
            return FleetPresence.SMALL;
        }

        // Check for dominance
        boolean othersAtSignificant = false;
        for (Map.Entry<Color, List<String>> entry : sectorFighters.entrySet()) {
            if (entry.getKey() == color) continue;
            if (entry.getValue().size() >= SIGNIFICANT_LEVEL) {
                othersAtSignificant = true;
                break;
            }
        }

        if (!othersAtSignificant) {
            return FleetPresence.DOMINANCE;
        }

        return FleetPresence.SIGNIFICANT;
    }

    public int getEntityCount(int row, int col) {
        if (row < 0 || row >= rows || col < 0 || col >= cols) return 0;
        int count = 0;
        Sector sector = grid[row][col];
        for (ColorSectorStatus status : sector.colorStatuses().values()) {
            if (status.hasCarrier()) count += 10; // Carrier counts as many entities for density
            count += status.fighterNames().size();
        }
        return count;
    }

    public Sector getSector(int row, int col) {
        return grid[row][col];
    }

    public int getRows() {
        return rows;
    }

    public int getCols() {
        return cols;
    }
}
