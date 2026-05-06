package it.battlejar.commander;

import it.battlejar.client.BattleJarContinuous;

import java.util.concurrent.Executors;

public class Main {

    private static final int MAX_GAMES = 3;

    public static void main(String[] args) {
        String apiUrl = System.getenv("BATTLEJAR_API_URL");
        if (apiUrl == null || apiUrl.isBlank()) {
            apiUrl = "https://api.battlejar.it";
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var continuous = new BattleJarContinuous(
                apiUrl,
                null, // Player will be resolved from battlejar.conf
                AgenticCommander::new,
                executor,
                    MAX_GAMES
            );
            continuous.run();
        }
    }
}
