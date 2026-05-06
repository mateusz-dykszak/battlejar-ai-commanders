package it.battlejar.commander;

import it.battlejar.api.Entity;
import it.battlejar.client.AbstractCommander;
import it.battlejar.commander.map.BattleMap;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AgenticCommander extends AbstractCommander {
    private static final Logger log = LoggerFactory.getLogger(AgenticCommander.class);

    private BattleMap battleMap;

    @Override
    protected boolean process(Collection<Entity> entities) {
        if (battleMap == null) {
            initializeBattleMap();
        }

        battleMap.update(entities);

        log.info("Processing {} entities. Map size: {}x{}", entities.size(), battleMap.getRows(), battleMap.getCols());
        
        // TODO: Implement strategy
        
        return true; // Keep playing
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
