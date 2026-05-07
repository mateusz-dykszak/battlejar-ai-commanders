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
    private static final float MISSILE_RANGE = 150f;

    private FighterMissileFireTactic tactic;
    private CommanderState state;

    @BeforeEach
    void setUp() {
        tactic = new FighterMissileFireTactic(MISSILE_RANGE);
        state = new CommanderState();
    }

    /**
     * Normal case: fighter has missiles, enemy is within range, and enemy is closer
     * to the fighter than our carrier. Missile should fire.
     */
    @Test
    void enemyCloserThanCarrier_fires() {
        // Carrier at (100, 200), fighter 100 units ahead toward enemy, enemy just 50 away
        Entity myCarrier = carrier(100, 200);
        Entity fighter = fighter(100, 100, 1);   // 100 units from carrier
        Entity target = enemy(100, 60);          // 40 units from fighter, 140 from carrier

        Optional<Order> order = tactic.apply(fighter, snapshot(myCarrier, fighter, target), state);

        assertTrue(order.isPresent());
        assertEquals(OrderType.FIRE_MISSILE, order.get().type());
    }

    /**
     * Fighter is next to our carrier and the enemy is far away — our carrier is closer.
     * Missile must NOT fire to avoid friendly lock-on.
     */
    @Test
    void carrierCloserThanEnemy_doesNotFire() {
        // Carrier at (100, 200), fighter just deployed next to carrier, enemy 120 away
        Entity myCarrier = carrier(100, 200);
        Entity fighter = fighter(100, 180, 1);  // 20 units from carrier
        Entity target = enemy(100, 60);          // 120 units from fighter, but carrier only 20 away

        Optional<Order> order = tactic.apply(fighter, snapshot(myCarrier, fighter, target), state);

        assertFalse(order.isPresent(), "Should not fire when own carrier is closer than target");
    }

    /**
     * Carrier and enemy equidistant from fighter — no fire (carrier <= distance condition).
     */
    @Test
    void carrierAndEnemyEquidistant_doesNotFire() {
        Entity myCarrier = carrier(100, 200);
        Entity fighter = fighter(100, 100, 1);  // exactly between carrier and enemy
        Entity target = enemy(100, 0);          // both are 100 units from fighter

        Optional<Order> order = tactic.apply(fighter, snapshot(myCarrier, fighter, target), state);

        assertFalse(order.isPresent(), "Should not fire when own carrier is equidistant to target");
    }

    /** No missiles on fighter — never fire. */
    @Test
    void noMissiles_doesNotFire() {
        Entity myCarrier = carrier(100, 200);
        Entity fighter = fighter(100, 100, 0);  // 0 missiles
        Entity target = enemy(100, 0);

        Optional<Order> order = tactic.apply(fighter, snapshot(myCarrier, fighter, target), state);

        assertFalse(order.isPresent());
    }

    /** Enemy outside missile range — never fire. */
    @Test
    void enemyOutOfRange_doesNotFire() {
        Entity myCarrier = carrier(100, 200);
        Entity fighter = fighter(100, 100, 1);
        Entity target = enemy(100, -80);  // 180 units away, beyond 150 range

        Optional<Order> order = tactic.apply(fighter, snapshot(myCarrier, fighter, target), state);

        assertFalse(order.isPresent());
    }

    /** No primary target — never fire. */
    @Test
    void noTarget_doesNotFire() {
        Entity myCarrier = carrier(100, 200);
        Entity fighter = fighter(100, 100, 1);

        Optional<Order> order = tactic.apply(fighter, snapshotNoTarget(myCarrier, fighter), state);

        assertFalse(order.isPresent());
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

    private static GameSnapshot snapshot(Entity myCarrier, Entity fighter, Entity target) {
        return new GameSnapshot(
                myCarrier, List.of(fighter), List.of(), List.of(target), List.of(), List.of(),
                target, 0f, true, false, Map.of(), new int[0][0], SETTINGS);
    }

    private static GameSnapshot snapshotNoTarget(Entity myCarrier, Entity fighter) {
        return new GameSnapshot(
                myCarrier, List.of(fighter), List.of(), List.of(), List.of(), List.of(),
                null, 0f, false, false, Map.of(), new int[0][0], SETTINGS);
    }
}
