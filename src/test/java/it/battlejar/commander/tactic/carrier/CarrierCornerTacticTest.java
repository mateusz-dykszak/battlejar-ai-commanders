package it.battlejar.commander.tactic.carrier;

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

class CarrierCornerTacticTest {

    // World 384×216, corners at margin=80 from each border
    private static final GameSettings SETTINGS = new GameSettings(384, 216, 0, 0, 0, 0, 0, 0);
    private static final float BORDER_MARGIN = 50f;
    private static final float SAFE_INSET = 30f;
    private static final float CORNER_THRESHOLD = 10f;

    // Bottom-left corner target: (80, 136)
    private CarrierCornerTactic tactic;
    private CommanderState state;

    @BeforeEach
    void setUp() {
        tactic = new CarrierCornerTactic(BORDER_MARGIN, SAFE_INSET, CORNER_THRESHOLD);
        state = new CommanderState();
    }

    /** Carrier at spawn (102, 198) should get a MOVE toward bottom-left corner (80, 136). */
    @Test
    void farFromCorner_sendsMoveTowardCorner() {
        Entity carrier = carrier(102, 198);
        Optional<Order> order = tactic.apply(carrier, snapshot(carrier), state);

        assertTrue(order.isPresent());
        assertEquals(OrderType.MOVE, order.get().type());
        // offset should be roughly (-22, -62) — negative x and negative y
        String[] parts = order.get().details().split("\\|");
        int dx = Integer.parseInt(parts[0]);
        int dy = Integer.parseInt(parts[1]);
        assertTrue(dx < 0, "Should move left toward x=80, got dx=" + dx);
        assertTrue(dy < 0, "Should move up toward y=136, got dy=" + dy);
    }

    /** Carrier exactly at corner (80, 136) should still send MOVE (offset ~0|0) not PATROL. */
    @Test
    void atCorner_sendsMoveNotPatrol() {
        Entity carrier = carrier(80, 136);
        Optional<Order> order = tactic.apply(carrier, snapshot(carrier), state);

        assertTrue(order.isPresent());
        assertEquals(OrderType.MOVE, order.get().type(), "Should always be MOVE, never PATROL");
    }

    /** carrierReachedCorner flag is set when carrier is within threshold. */
    @Test
    void withinThreshold_setsReachedCornerFlag() {
        Entity carrier = carrier(83, 138); // ~3.6 units from (80, 136)
        assertFalse(state.carrierReachedCorner);

        tactic.apply(carrier, snapshot(carrier), state);

        assertTrue(state.carrierReachedCorner);
    }

    /** carrierReachedCorner flag is NOT set when carrier is far from corner. */
    @Test
    void farFromCorner_doesNotSetReachedCornerFlag() {
        Entity carrier = carrier(102, 198);
        tactic.apply(carrier, snapshot(carrier), state);

        assertFalse(state.carrierReachedCorner);
    }

    /** Carrier overshot past corner (above it) still gets MOVE back toward the corner. */
    @Test
    void overshootPastCorner_sendsMoveBackToCorner() {
        // Carrier overshot to (82, 120) — above corner at (80, 136)
        Entity carrier = carrier(82, 120);
        Optional<Order> order = tactic.apply(carrier, snapshot(carrier), state);

        assertTrue(order.isPresent());
        assertEquals(OrderType.MOVE, order.get().type());
        String[] parts = order.get().details().split("\\|");
        int dy = Integer.parseInt(parts[1]);
        assertTrue(dy > 0, "Should move DOWN back toward y=136, got dy=" + dy);
    }

    private static Entity carrier(float px, float py) {
        return new Entity("carrier1", Entity.Type.CARRIER, "BLUE", px, py, 0, 0, null, 0, 0, 0, "1000");
    }

    private static GameSnapshot snapshot(Entity carrier) {
        return new GameSnapshot(
                carrier, List.of(), List.of(), List.of(), List.of(), List.of(),
                null, 0f, false, false, Map.of(), new int[0][0], SETTINGS);
    }
}
