package it.battlejar.commander;

import it.battlejar.api.Entity;
import it.battlejar.client.AbstractCommander;
import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AgenticCommander extends AbstractCommander {
    private static final Logger log = LoggerFactory.getLogger(AgenticCommander.class);

    @Override
    protected boolean process(Collection<Entity> entities) {
        log.info("Processing {} entities", entities.size());
        
        // TODO: Implement strategy
        
        return true; // Keep playing
    }
}
