package it.battlejar.commander;

import it.battlejar.api.Player;
import it.battlejar.client.BattleJarClient;
import it.battlejar.client.BattleJarContinuous;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;

@Slf4j
public class Main {

    private static final String SERVER_URL = System.getenv("BATTLEJAR_API_URL");
    private static final int MAX_GAMES = 10;

    public static void main(String[] args) {
        Player player = new Player(null, null, null);


        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            new BattleJarContinuous(SERVER_URL, player, ClaudeCommander::new, executor, MAX_GAMES).run();
        }

        log.info("All {} games completed", MAX_GAMES);
    }
}
