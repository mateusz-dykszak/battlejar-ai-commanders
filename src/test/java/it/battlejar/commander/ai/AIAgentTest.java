package it.battlejar.commander.ai;

import it.battlejar.api.Color;
import it.battlejar.commander.map.*;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class AIAgentTest {

    @Test
    void testAIAgentIntegrationAndMemory() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("Skipping integration test: OPENAI_API_KEY not set");
            return;
        }

        AIAgent agent = new AIAgent(apiKey);
        
        int rows = 3;
        int cols = 3;
        it.battlejar.api.GameSettings settings = new it.battlejar.api.GameSettings(1000, 1000, 1, 10, 2, 50, 3, 5);
        BattleMap map = new BattleMap(rows, cols, settings);

        // Put our carrier in 1x1
        it.battlejar.api.Entity myCarrier = new it.battlejar.api.Entity("carrier-red", it.battlejar.api.Entity.Type.CARRIER, "RED", 500, 500, 0, 0, null, 0, 0, 0, "100");
        map.update(java.util.List.of(myCarrier), Color.RED);

        // Put enemy carrier in 2x2
        it.battlejar.api.Entity enemyCarrier = new it.battlejar.api.Entity("carrier-blue", it.battlejar.api.Entity.Type.CARRIER, "BLUE", 800, 800, 0, 0, null, 0, 0, 0, "100");
        map.update(java.util.List.of(myCarrier, enemyCarrier), Color.RED);

        // First call
        System.out.println("First call to AI...");
        String response1 = agent.getCommandsFromAI(map, Color.RED, "100", 1, 0);
        assertNotNull(response1);
        assertFalse(response1.isBlank());
        System.out.println("Response 1: " + response1);

        // Second call: state hasn't changed, memory should help
        System.out.println("Second call to AI (info that state didn't change)...");
        String response2 = agent.getCommands("The state did not change. Return the previous response.");
        assertNotNull(response2);
        assertFalse(response2.isBlank());
        System.out.println("Response 2: " + response2);
        
        // Check if response2 is similar to response1 or acknowledges it
        // Since we told it to return previous response, it should be very similar.
    }
}
