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

        int maxGames = MAX_GAMES;
        String maxGamesEnv = System.getenv("BJ_CLIENT_MAX_GAMES");
        if (maxGamesEnv != null && !maxGamesEnv.isBlank()) {
            try {
                maxGames = Integer.parseInt(maxGamesEnv);
            } catch (NumberFormatException e) {
                System.err.println("Invalid BJ_CLIENT_MAX_GAMES value: " + maxGamesEnv + ". Using default: " + MAX_GAMES);
            }
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var continuous = new BattleJarContinuous(
                apiUrl,
                null, // Player will be resolved from battlejar.conf
                AgenticCommander::new,
                executor,
                maxGames
            );
            continuous.run();
        }
    }
}
