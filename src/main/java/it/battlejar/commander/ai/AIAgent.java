package it.battlejar.commander.ai;

import dev.langchain4j.model.openai.OpenAiChatModel;
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
            - DEFEND
            
            Rules:
            1. Carrier can get exactly one command.
            2. Fighters in a sector can be given multiple commands. If you give N commands to fighters in a sector, the fighters will be split into N equal groups, each following one command.
            3. Coordinates are 0-indexed: <row>x<col>.
            
            Respond ONLY with a list of commands in the following format:
            CARRIER: <command>
            SECTOR <row>x<col>: <command1>, <command2>, ...
            
            Strategy Guidelines:
            1. Target Prioritization: Prioritize attacking enemy carriers. If an enemy carrier is detected in a sector or nearby, focus fire on it. Eliminating the enemy carrier is the fastest way to win.
            2. Aggression: If you have a SIGNIFICANT or DOMINANCE presence, be aggressive. Use ATTACK commands to push into enemy-held sectors, especially those with enemy carriers.
            3. Carrier Safety: Keep your carrier safe. Use MOVE to reposition away from HIGH threat levels, and use DEFEND to keep fighters as a screen.
            4. Fighter Regrouping: If your fighters in a sector are spread thin (Presence=SMALL), use REGROUP or MOVE commands to regroup them into a stronger sector (Presence=SIGNIFICANT or DOMINANCE) or a safer sector near your carrier.
            
            Example:
            CARRIER: MOVE 1x1
            SECTOR 0x0: ATTACK 0x1, DEFEND
            SECTOR 1x1: MOVE 2x2
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

        OpenAiChatModel model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gpt-5.4-nano")
                .build();

        this.service = AiServices.create(CommanderService.class, model);
    }

    public String getCommandsFromAI(BattleMap map, Color myColor) {
        StringBuilder sb = new StringBuilder();
        sb.append("Current Battle Map State (Your color: ").append(myColor).append("):\n");
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

        String userMessage = sb.toString();
        log.info("Sending map state to LLM:\n{}", userMessage);

        Instant start = Instant.now();
        String response = service.getCommands(userMessage);
        Instant end = Instant.now();

        log.info("LLM response received in {}ms:\n{}", Duration.between(start, end).toMillis(), response);

        return response;
    }
}
