package it.battlejar.commander.ai;

import it.battlejar.api.Color;
import it.battlejar.api.Entity;
import it.battlejar.api.GameSettings;
import it.battlejar.commander.map.BattleMap;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AIAgentIntegrationTest {

    @Test
    void shouldKeepConversationMemoryWhenStateDidNotChange() throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY is required for integration test");

        AIAgent agent = new AIAgent(apiKey);
        AIAgent.CommanderService service = extractCommanderService(agent);
        assertNotNull(service, "Commander service should be initialized");

        String preparedState = """
                Current Battle Map State (Your color: RED):
                Carrier Health: 84
                Active Fighters: 6
                Docked Fighters: 3
                Grid: 3x3
                Sector 1x1:
                  Your fleet: Carrier=true, Presence=SIGNIFICANT, Fighters count=4
                Sector 1x2:
                  Enemy BLUE: Carrier=true, Presence=SMALL, Threat=HIGH
                Respond with valid command lines only.
                """;

        String firstResponse = service.getCommands(preparedState).trim();
        assertFalse(firstResponse.isBlank(), "First LLM response should not be blank");

        String secondResponse = service.getCommands("""
                State did not change. Return your previous response exactly.
                """).trim();

        assertFalse(secondResponse.isBlank(), "Second LLM response should not be blank");
        assertEquals(firstResponse, secondResponse, "Second response should match previous response when state is unchanged");
    }

    @Test
    void shouldGetCommandResponseFromPreparedStateThroughAIAgent() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY is required for integration test");

        AIAgent agent = new AIAgent(apiKey);
        BattleMap map = createPreparedMapState();

        String response = agent.getCommandsFromAI(map, Color.RED, "91", 8, 2);

        assertNotNull(response, "LLM response should not be null");
        assertFalse(response.isBlank(), "LLM response should not be blank");
        assertTrue(response.contains("CARRIER:"), "LLM response should include carrier command");
    }

    private static AIAgent.CommanderService extractCommanderService(AIAgent agent) throws Exception {
        Field serviceField = AIAgent.class.getDeclaredField("service");
        serviceField.setAccessible(true);
        return (AIAgent.CommanderService) serviceField.get(agent);
    }

    private static BattleMap createPreparedMapState() {
        GameSettings settings = new GameSettings(1000, 1000, 1, 10, 2, 50, 3, 5);
        BattleMap map = new BattleMap(3, 3, settings);

        Entity myCarrier = new Entity("carrier-red", Entity.Type.CARRIER, "RED", 500, 500, 0, 0, null, 0, 0, 0, "91");
        Entity myFighter1 = new Entity("fighter-red-1", Entity.Type.FIGHTER, "RED", 520, 520, 0, 0, null, 0, 0, 0, "100");
        Entity myFighter2 = new Entity("fighter-red-2", Entity.Type.FIGHTER, "RED", 540, 520, 0, 0, null, 0, 0, 0, "100");
        Entity enemyCarrier = new Entity("carrier-blue", Entity.Type.CARRIER, "BLUE", 820, 500, -20, 0, null, 0, 0, 0, "77");
        Entity enemyFighter = new Entity("fighter-blue-1", Entity.Type.FIGHTER, "BLUE", 780, 500, -10, 0, null, 0, 0, 0, "45");

        map.update(List.of(myCarrier, myFighter1, myFighter2, enemyCarrier, enemyFighter), Color.RED);
        return map;
    }
}
