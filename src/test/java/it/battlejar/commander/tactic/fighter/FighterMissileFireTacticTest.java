package it.battlejar.commander.tactic.fighter;

import it.battlejar.api.Entity;
import it.battlejar.api.GameSettings;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FighterMissileFireTacticTest {

    private static final GameSettings SETTINGS = new GameSettings(384, 216, 0, 0, 0, 0, 0, 0);

    private FighterMissileFireTactic tactic;
    private CommanderState state;

    @BeforeEach
    void setUp() {
        tactic = new FighterMissileFireTactic();
        state = new CommanderState();
    }

    /** (a) Enemy in front at safe range, carrier behind — missile should fire. */
    @Test
    void enemyInFront_carrierBehind_fires() {
        Entity myCarrier = carrier(0, 200);   // behind the fighter
        Entity fighter = fighter(0, 0, 1);
        Entity enemy = enemy(0, -200);        // 200 units ahead, well beyond arming range

        Optional<Order> order = tactic.apply(fighter, snapshot(myCarrier, fighter, enemy), state);

        assertTrue(order.isPresent());
        assertEquals(OrderType.FIRE_MISSILE, order.get().type());
    }

    /** (b) Carrier in front and closer than enemy — missile would home on carrier. Block fire. */
    @Test
    void carrierInFrontAndCloser_doesNotFire() {
        Entity myCarrier = carrier(0, -100);  // 100 units ahead of fighter
        Entity fighter = fighter(0, 0, 1);
        Entity enemy = enemy(0, -300);        // 300 units ahead — carrier is in the way

        Optional<Order> order = tactic.apply(fighter, snapshot(myCarrier, fighter, enemy), state);

        assertFalse(order.isPresent(), "Should not fire when own carrier is in front and closer");
    }

    /** (c) Carrier to the side, enemy in front — carrier not in trajectory, should fire. */
    @Test
    void carrierToSide_enemyInFront_fires() {
        Entity myCarrier = carrier(100, 0);   // 100 units to the right (perpendicular)
        Entity fighter = fighter(0, 0, 1);
        Entity enemy = enemy(0, -200);        // 200 units ahead

        Optional<Order> order = tactic.apply(fighter, snapshot(myCarrier, fighter, enemy), state);

        assertTrue(order.isPresent(), "Should fire when carrier is to the side and not blocking");
    }

    /** (d) Nearest enemy within arming range — missile won't arm in time. Block fire. */
    @Test
    void nearestEnemyWithinArmingRange_doesNotFire() {
        Entity myCarrier = carrier(0, 500);   // far behind
        Entity fighter = fighter(0, 0, 1);
        Entity enemy = enemy(0, -80);         // 80 units away, below 100-unit min range

        Optional<Order> order = tactic.apply(fighter, snapshot(myCarrier, fighter, enemy), state);

        assertFalse(order.isPresent(), "Should not fire when nearest enemy is within arming range");
    }

    /** (e) No live enemies — never fire. */
    @Test
    void noLiveEnemies_doesNotFire() {
        Entity myCarrier = carrier(0, 200);
        Entity fighter = fighter(0, 0, 1);

        Optional<Order> order = tactic.apply(fighter, snapshotNoEnemies(myCarrier, fighter), state);

        assertFalse(order.isPresent(), "Should not fire with no live enemy carriers");
    }

    /** No missiles on fighter — never fire. */
    @Test
    void noMissiles_doesNotFire() {
        Entity myCarrier = carrier(0, 200);
        Entity fighter = fighter(0, 0, 0);   // 0 missiles
        Entity enemy = enemy(0, -200);

        Optional<Order> order = tactic.apply(fighter, snapshot(myCarrier, fighter, enemy), state);

        assertFalse(order.isPresent(), "Should not fire without missiles");
    }

    private static Entity carrier(float px, float py) {
        return new Entity("carrier1", Entity.Type.CARRIER, "BLUE", px, py, 0, 0, null, 0, 0, 0, "1000");
    }

    private static Entity fighter(float px, float py, int missiles) {
        return new Entity("fighter1", Entity.Type.FIGHTER, "BLUE", px, py, 0, 0, null, 0, 0, missiles, "10");
    }

    private static Entity enemy(float px, float py) {
        return new Entity("enemy1", Entity.Type.CARRIER, "RED", px, py, 0, 0, null, 0, 0, 0, "1000");
    }

    private static GameSnapshot snapshot(Entity myCarrier, Entity fighter, Entity enemy) {
        return new GameSnapshot(
                myCarrier, List.of(fighter), List.of(), List.of(enemy), List.of(), List.of(),
                enemy, 0f, true, false, Map.of(), new int[0][0], new int[0][0], SETTINGS);
    }

    private static GameSnapshot snapshotNoEnemies(Entity myCarrier, Entity fighter) {
        return new GameSnapshot(
                myCarrier, List.of(fighter), List.of(), List.of(), List.of(), List.of(),
                null, 0f, false, false, Map.of(), new int[0][0], new int[0][0], SETTINGS);
    }
}
