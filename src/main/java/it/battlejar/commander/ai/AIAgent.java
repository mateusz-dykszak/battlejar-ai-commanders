package it.battlejar.commander.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import it.battlejar.api.Color;
import it.battlejar.commander.map.BattleMap;
import it.battlejar.commander.map.ColorSectorStatus;
import it.battlejar.commander.map.Sector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

public class AIAgent {

    private static final Logger log = LoggerFactory.getLogger(AIAgent.class);

    public interface CommanderService {
        @SystemMessage("""
            You are a space battle commander. You control a carrier and multiple fighters.
            Your goal is to win the battle by eliminating enemies or outmaneuvering them.
            
            You will receive the current battle map state.
            The map is a grid of sectors.
            Each sector contains information about your fleet and enemy fleets.
            
            Allowed commands for each sector:
            - MOVE <SectorCoordinates> (e.g., MOVE 1x2)
            - ATTACK <SectorCoordinates> (e.g., ATTACK 3x1)
            - REGROUP <SectorCoordinates> (e.g., REGROUP 1x1)
            - HARASS <SectorCoordinates> (e.g., HARASS 2x2)
            - DEFEND
            
            Allowed commands for CARRIER:
            - MOVE <SectorCoordinates>
            - ATTACK <SectorCoordinates>
            - REGROUP <SectorCoordinates>
            - HARASS <SectorCoordinates>
            - FIRE_MISSILE <SectorCoordinates>
            - DEFEND
            
            Rules:
            1. Carrier can get exactly one command.
            2. Fighters in a sector can be given multiple commands. If you give N commands to fighters in a sector, the fighters will be split into N equal groups, each following one command.
            3. Coordinates are 0-indexed: <row>x<col>.
            
            Current Status:
            - Carrier Health: %s
            - Active Fighters: %d
            - Docked Fighters: %d
            
            Respond ONLY with a list of commands in the following format:
            CARRIER: <command>
            SECTOR <row>x<col>: <command1>, <command2>, ...
            
            Note: For FIRE_MISSILE, carriers fire towards the center of the target sector. Fighters fire missiles AUTOMATICALLY when an enemy carrier is in front of them, so you do not need to give them FIRE_MISSILE commands.
            
            Strategy Guidelines:
            1. Target Prioritization: Prioritize attacking enemy carriers. If an enemy carrier is detected in a sector or nearby, focus fire on it. Eliminating the enemy carrier is the fastest way to win. Also, prioritize entities with low health (numeric status) to quickly reduce enemy numbers.
            2. Aggression: If you have a SIGNIFICANT or DOMINANCE presence, be aggressive. Use ATTACK commands to push into enemy-held sectors, especially those with enemy carriers or low-health groups.
            3. Carrier Safety: Keep your carrier safe. Use MOVE to reposition away from HIGH threat levels, and use DEFEND to keep fighters as a screen.
            4. Fighter Regrouping: If your fighters in a sector are spread thin (Presence=SMALL), use REGROUP or MOVE commands to regroup them into a stronger sector (Presence=SIGNIFICANT or DOMINANCE) or a safer sector near your carrier.
            5. Fighter Harassment: Use HARASS to send a small group of fighters to stay near an enemy carrier sector. This disrupts their fighter launches and intercepts newly launched units. Only use this if you have enough fighters to spare.
            6. Mindset: 
               - If your Carrier Health is low or you are under heavy attack (HIGH threat levels near carrier), switch to a DEFENSIVE mindset. Prioritize DEFEND and MOVE (carrier away) commands.
               - If your Carrier is NOT under attack, adopt an AGGRESSIVE mindset. Launch attacks and hunt enemy carriers. DO NOT keep fighters in defense formation if there is no immediate threat to the carrier; this causes unnecessary collisions and reduces your offensive potential.
               - Use Docked Fighters as a reserve. If you have many docked fighters, you can afford to be more aggressive with your active ones.
               - Spread out your fighters. Don't crowd multiple sectors with the same command unless you're making a concentrated push.
            7. Missile Usage: 
               - Use FIRE_MISSILE for the CARRIER when you have a clear shot at an enemy carrier. 
               - DO NOT fire missiles if you are too close to the enemy carrier. Carriers need 2 seconds (approx 100 units) for missiles to arm.
            """)
        String getCommands(@UserMessage String mapState);
    }

    private final CommanderService service;

    public AIAgent() {
        this(System.getenv("OPENAI_API_KEY"));
    }

    public AIAgent(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            this.service = null;
            return;
        }

        ChatModel model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gpt-5-nano") // AI - Do not change the model
                .temperature(0.0)
                .build();

        this.service = AiServices.builder(CommanderService.class)
                .chatModel(model)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                .build();
    }

    public String getCommandsFromAI(BattleMap map, Color myColor, String carrierHealth, int activeFighters, int dockedFighters) {
        String userMessage = formatMapState(map, myColor, carrierHealth, activeFighters, dockedFighters);
        return getCommands(userMessage);
    }

    public String formatMapState(BattleMap map, Color myColor, String carrierHealth, int activeFighters, int dockedFighters) {
        StringBuilder sb = new StringBuilder();
        sb.append("Current Battle Map State (Your color: ").append(myColor).append("):\n");
        sb.append("Carrier Health: ").append(carrierHealth).append("\n");
        sb.append("Active Fighters: ").append(activeFighters).append("\n");
        sb.append("Docked Fighters: ").append(dockedFighters).append("\n");
        sb.append("Grid: ").append(map.getRows()).append("x").append(map.getCols()).append("\n");

        for (int r = 0; r < map.getRows(); r++) {
            for (int c = 0; c < map.getCols(); c++) {
                Sector sector = map.getSector(r, c);
                ColorSectorStatus myStatus = sector.colorStatuses().get(myColor);
                
                if (myStatus != null || !sector.colorStatuses().isEmpty()) {
                    sb.append("Sector ").append(r).append("x").append(c).append(":\n");
                    if (myStatus != null) {
                        sb.append("  Your fleet: Carrier=").append(myStatus.hasCarrier())
                                .append(", Presence=").append(myStatus.presence())
                                .append(", Fighters count=").append(myStatus.fighterNames().size()).append("\n");
                    }
                    
                    sector.colorStatuses().forEach((color, status) -> {
                        if (color != myColor) {
                            sb.append("  Enemy ").append(color).append(": Carrier=").append(status.hasCarrier())
                                    .append(", Presence=").append(status.presence())
                                    .append(", Threat=").append(status.threatLevel()).append("\n");
                        }
                    });
                }
            }
        }

        return String.format(sb.toString(), carrierHealth, activeFighters, dockedFighters);
    }

    public String getCommands(String userMessage) {
        if (service == null) {
            return "ERROR: AI service not initialized (check API key)";
        }
        log.info("Sending message to LLM:\n{}", userMessage);

        Instant start = Instant.now();
        String response = service.getCommands(userMessage);
        Instant end = Instant.now();

        log.info("LLM response received in {}ms:\n{}", Duration.between(start, end).toMillis(), response);

        return response;
    }
}
