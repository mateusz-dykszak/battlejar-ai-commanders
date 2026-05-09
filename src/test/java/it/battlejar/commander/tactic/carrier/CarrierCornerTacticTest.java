package it.battlejar.commander.tactic.carrier;

import it.battlejar.api.Entity;
import it.battlejar.api.GameSettings;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CarrierCornerTacticTest {

    private static final int WORLD_WIDTH = 384;
    private static final int WORLD_HEIGHT = 216;
    // World 384×216, corners 25 units from each border: (25,25), (359,25), (25,191), (359,191)
    private static final GameSettings SETTINGS = new GameSettings(WORLD_WIDTH, WORLD_HEIGHT, 0, 0, 0, 0, 0, 0);
    private static final float CORNER_MARGIN = 25f;

    private CarrierCornerTactic tactic;
    private CommanderState state;

    @BeforeEach
    void setUp() {
        tactic = new CarrierCornerTactic(CORNER_MARGIN);
        state = new CommanderState();
    }

    /**
     * Carrier at spawn (102, 198) should get a MOVE toward bottom-left corner (80, 136).
     */
    @ParameterizedTest
    @MethodSource("atCornerArguments")
    void farFromCorner_sendsMoveTowardCorner(float x, float y, Predicate<Integer> vxPredicate, Predicate<Integer> vyPredicate, String vxPredicateName, String vyPredicateName) {

        Entity carrier = carrier(x, y);
        Optional<Order> order = tactic.apply(carrier, snapshot(carrier), state);

        assertTrue(order.isPresent());
        assertEquals(OrderType.MOVE, order.get().type());

        String[] parts = order.get().details().split("\\|");
        int dx = Integer.parseInt(parts[0]);
        int dy = Integer.parseInt(parts[1]);
        assertTrue(vxPredicate.test(dx), "Should move " + vxPredicateName + ", got dx=" + dx);
        assertTrue(vyPredicate.test(dy), "Should move " + vyPredicateName + ", got dy=" + dy);
    }

    static Stream<Arguments> atCornerArguments() {
        // Corners are 20 units from each wall: (20,20), (364,20), (20,196), (364,196).
        // x: 40 units inside each corner toward center; y: midway between wall and corner.
        float nearLeft   = CORNER_MARGIN * 3;                //  60
        float nearRight  = WORLD_WIDTH  - CORNER_MARGIN * 3; // 324
        float nearTop    = CORNER_MARGIN / 2f;               //  10 — between top wall and top corners
        float nearBottom = WORLD_HEIGHT - CORNER_MARGIN / 2f; // 206 — between bottom corners and bottom wall
        return Stream.of(
                Arguments.of(nearLeft,  nearBottom, (Predicate<Integer>) x -> x < 0, (Predicate<Integer>) y -> y < 0, "left",  "up"),
                Arguments.of(nearRight, nearBottom, (Predicate<Integer>) x -> x > 0, (Predicate<Integer>) y -> y < 0, "right", "up"),
                Arguments.of(nearLeft,  nearTop,    (Predicate<Integer>) x -> x < 0, (Predicate<Integer>) y -> y > 0, "left",  "down"),
                Arguments.of(nearRight, nearTop,    (Predicate<Integer>) x -> x > 0, (Predicate<Integer>) y -> y > 0, "right", "down")
        );
    }

    /**
     * Carrier exactly at corner (25, 191) should still send MOVE (offset ~0|0) not PATROL.
     */
    @Test
    void atCorner_sendsMoveNotPatrol() {
        Entity carrier = carrier(25, 191);
        Optional<Order> order = tactic.apply(carrier, snapshot(carrier), state);

        assertTrue(order.isPresent());
        assertEquals(OrderType.MOVE, order.get().type(), "Should always be MOVE, never PATROL");
    }

    /**
     * carrierReachedCorner flag is set when carrier is inside the corner square.
     */
    @Test
    void withinThreshold_setsReachedCornerFlag() {
        Entity carrier = carrier(23, 199); // inside bottom-left corner square (x<25, y>191)
        assertFalse(state.carrierReachedCorner);

        tactic.apply(carrier, snapshot(carrier), state);

        assertTrue(state.carrierReachedCorner);
    }

    /**
     * carrierReachedCorner flag is NOT set when carrier is far from corner.
     */
    @Test
    void farFromCorner_doesNotSetReachedCornerFlag() {
        Entity carrier = carrier(102, 198);
        tactic.apply(carrier, snapshot(carrier), state);

        assertFalse(state.carrierReachedCorner);
    }

    /**
     * Actual game spawn positions must map to the correct corner offset.
     * Spawn positions: (282,18), (102,18), (282,198), (102,198).
     * World 384×216, margin=20 → corners at (20,20), (364,20), (20,196), (364,196).
     */
    @ParameterizedTest
    @MethodSource("actualSpawnArguments")
    void actualSpawnPositions_moveToCorrectCorner(float spawnX, float spawnY, int expectedDx, int expectedDy) {
        Entity carrier = carrier(spawnX, spawnY);
        Optional<Order> order = tactic.apply(carrier, snapshot(carrier), state);

        assertTrue(order.isPresent());
        String[] parts = order.get().details().split("\\|");
        int dx = Integer.parseInt(parts[0]);
        int dy = Integer.parseInt(parts[1]);
        assertEquals(expectedDx, dx, "Wrong dx from spawn (" + spawnX + "," + spawnY + ")");
        assertEquals(expectedDy, dy, "Wrong dy from spawn (" + spawnX + "," + spawnY + ")");
    }

    static Stream<Arguments> actualSpawnArguments() {
        // Spawn → nearest corner → expected offset  (margin=25: corners at (25,25),(359,25),(25,191),(359,191))
        return Stream.of(
            Arguments.of(282f, 18f,   77,  7),   // → top-right  (359,25)
            Arguments.of(102f, 18f,  -77,  7),   // → top-left   (25,25)
            Arguments.of(282f, 198f,  77, -7),   // → bottom-right (359,191)
            Arguments.of(102f, 198f, -77, -7)    // → bottom-left (25,191)
        );
    }

    /**
     * Carrier overshot past corner (above it) still gets MOVE back toward the corner.
     */
    @Test
    void overshootPastCorner_sendsMoveBackToCorner() {
        // Carrier overshot to (22, 180) — above corner at (20, 196)
        Entity carrier = carrier(22, 180);
        Optional<Order> order = tactic.apply(carrier, snapshot(carrier), state);

        assertTrue(order.isPresent());
        assertEquals(OrderType.MOVE, order.get().type());
        String[] parts = order.get().details().split("\\|");
        int dy = Integer.parseInt(parts[1]);
        assertTrue(dy > 0, "Should move DOWN back toward y=196, got dy=" + dy);
    }

    private static Entity carrier(float px, float py) {
        return new Entity("carrier1", Entity.Type.CARRIER, "BLUE", px, py, 0, 0, null, 0, 0, 0, "1000");
    }

    private static GameSnapshot snapshot(Entity carrier) {
        return new GameSnapshot(
                carrier, List.of(), List.of(), List.of(), List.of(), List.of(),
                null, 0f, false, false, Map.of(), new int[0][0], new int[0][0], SETTINGS);
    }
}
