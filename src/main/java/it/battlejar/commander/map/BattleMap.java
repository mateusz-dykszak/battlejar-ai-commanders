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

    private static final int SIGNIFICANT_LEVEL = 5;

    public BattleMap(int rows, int cols, GameSettings settings) {
        this.rows = rows;
        this.cols = cols;
        this.worldWidth = settings.worldWidth();
        this.worldHeight = settings.worldHeight();
        this.grid = new Sector[rows][cols];
    }

    public void update(Collection<Entity> entities) {
        // Initialize grid with empty sectors
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                grid[r][c] = new Sector(new EnumMap<>(Color.class));
            }
        }

        // Temporary storage for processing
        Map<Color, List<String>>[][] fightersInSector = new Map[rows][cols];
        Map<Color, Boolean>[][] carrierInSector = new Map[rows][cols];

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                fightersInSector[r][c] = new EnumMap<>(Color.class);
                carrierInSector[r][c] = new EnumMap<>(Color.class);
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
                    
                    colorStatuses.put(color, new ColorSectorStatus(hasCarrier, presence, names));
                }
            }
        }
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
