package it.battlejar.commander.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AICommandParser {

    public record Command(String type, String target) {}

    public record AIResponse(Command carrierCommand, Map<String, List<Command>> sectorCommands) {}

    public static AIResponse parse(String aiOutput) {
        Command carrierCommand = null;
        Map<String, List<Command>> sectorCommands = new HashMap<>();

        String[] lines = aiOutput.split("\n");
        Pattern carrierPattern = Pattern.compile("CARRIER:\\s*(MOVE|ATTACK|REGROUP|DEFEND|HARASS)\\s*(\\d+x\\d+)?", Pattern.CASE_INSENSITIVE);
        Pattern sectorPattern = Pattern.compile("SECTOR\\s*(\\d+x\\d+):\\s*(.*)", Pattern.CASE_INSENSITIVE);
        Pattern commandPattern = Pattern.compile("(MOVE|ATTACK|REGROUP|DEFEND|HARASS)\\s*(\\d+x\\d+)?", Pattern.CASE_INSENSITIVE);

        for (String line : lines) {
            line = line.trim();
            Matcher carrierMatcher = carrierPattern.matcher(line);
            if (carrierMatcher.find()) {
                carrierCommand = new Command(carrierMatcher.group(1).toUpperCase(), carrierMatcher.group(2));
                continue;
            }

            Matcher sectorMatcher = sectorPattern.matcher(line);
            if (sectorMatcher.find()) {
                String sectorCoords = sectorMatcher.group(1);
                String commandsPart = sectorMatcher.group(2);
                List<Command> commands = new ArrayList<>();
                
                Matcher cmdMatcher = commandPattern.matcher(commandsPart);
                while (cmdMatcher.find()) {
                    commands.add(new Command(cmdMatcher.group(1).toUpperCase(), cmdMatcher.group(2)));
                }
                
                if (!commands.isEmpty()) {
                    sectorCommands.put(sectorCoords, commands);
                }
            }
        }

        return new AIResponse(carrierCommand, sectorCommands);
    }
}
