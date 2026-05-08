package it.battlejar.commander;

import it.battlejar.api.*;
import it.battlejar.commander.ai.AICommandParser;
import it.battlejar.commander.map.BattleMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class AgenticCommanderTest {

    private static class TestOrderSender implements Consumer<Order> {
        Order lastOrder;
        @Override
        public void accept(Order order) {
            this.lastOrder = order;
        }
    }

    private AgenticCommander commander;
    private TestOrderSender orderSender;
    private GameSettings settings;

    @BeforeEach
    void setUp() {
        commander = new AgenticCommander();
        orderSender = new TestOrderSender();
        commander.setOrdersSender(orderSender);
        
        settings = new GameSettings(1000, 1000, 1, 10, 2, 50, 3, 5);
        
        // AbstractCommander.process(RegistrationResponse) sets myColor and settings
        RegistrationResponse regResponse = new RegistrationResponse(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), Color.RED, settings);
        commander.process(regResponse);
        
        // initializeBattleMap normally reads from file, let's trigger it or set it
        // Since initializeBattleMap is private and depends on battlejar.conf, 
        // we might need to be careful. In process() it's called if battleMap is null.
    }

    @Test
    void testIssueMoveCommand() {
        // Setup battleMap (3x3 grid by default)
        commander.process(Collections.emptyList()); // This triggers initializeBattleMap
        
        Entity carrier = new Entity("carrier1", Entity.Type.CARRIER, "RED", 500, 500, 0, 0, null, 0, 0, 0, "100");
        Entity fighter = new Entity("fighter1", Entity.Type.FIGHTER, "RED", 500, 500, 0, 0, null, 0, 0, 0, "100");
        Collection<Entity> entities = List.of(carrier, fighter);
        
        // Command to move to sector 0x0
        // parseSectorCoords(0x0) -> { (0+0.5)*W/3, (0+0.5)*H/3 } = { 166.6, 166.6 }
        // Carrier is at 500, 500. 
        // RelX = 166.6 - 500 = -333.3...
        // RelY = 166.6 - 500 = -333.3...
        
        AICommandParser.Command cmd = new AICommandParser.Command("MOVE", "0x0");
        
        commander.issueCommand(fighter, cmd, entities);
        
        Order order = orderSender.lastOrder;
        assertEquals("fighter1", order.id());
        assertEquals(OrderType.MOVE, order.type());
        // -333.33334|-333.33334
        String[] parts = order.details().split("\\|");
        assertEquals(-333.33334f, Float.parseFloat(parts[0]), 0.01f);
        assertEquals(-333.33334f, Float.parseFloat(parts[1]), 0.01f);
    }

    @Test
    void testIssueAttackCommandWithTarget() {
        commander.process(Collections.emptyList());
        
        Entity carrier = new Entity("carrier1", Entity.Type.CARRIER, "RED", 500, 500, 0, 0, null, 0, 0, 0, "100");
        Entity fighter = new Entity("fighter1", Entity.Type.FIGHTER, "RED", 500, 500, 0, 0, null, 0, 0, 0, "100");
        // Target in sector 2x2. World 1000x1000. Sector 2x2 is bottom right.
        // parseSectorCoords(2x2) -> { (2.5)*333.3, (2.5)*333.3 } = { 833.3, 833.3 }
        Entity enemy = new Entity("enemy1", Entity.Type.FIGHTER, "BLUE", 830, 830, 0, 0, null, 0, 0, 0, "100");
        
        Collection<Entity> entities = List.of(carrier, fighter, enemy);
        
        AICommandParser.Command cmd = new AICommandParser.Command("ATTACK", "2x2");
        
        commander.issueCommand(fighter, cmd, entities);
        
        Order order = orderSender.lastOrder;
        assertEquals("fighter1", order.id());
        assertEquals(OrderType.ATTACK, order.type());
        assertEquals("enemy1", order.details());
    }

    @Test
    void testIssueAttackCommandNoTargetInSector() {
        commander.process(Collections.emptyList());
        
        Entity carrier = new Entity("carrier1", Entity.Type.CARRIER, "RED", 500, 500, 0, 0, null, 0, 0, 0, "100");
        Entity fighter = new Entity("fighter1", Entity.Type.FIGHTER, "RED", 500, 500, 0, 0, null, 0, 0, 0, "100");
        // Enemy is in 0x0, but command is for 2x2
        Entity enemy = new Entity("enemy1", Entity.Type.FIGHTER, "BLUE", 100, 100, 0, 0, null, 0, 0, 0, "100");
        
        Collection<Entity> entities = List.of(carrier, fighter, enemy);
        
        AICommandParser.Command cmd = new AICommandParser.Command("ATTACK", "2x2");
        
        commander.issueCommand(fighter, cmd, entities);
        
        Order order = orderSender.lastOrder;
        assertEquals("fighter1", order.id());
        assertEquals(OrderType.MOVE, order.type()); // Falls back to MOVE
        // Rel coord to 2x2 center (833.3, 833.3) from (500, 500)
        String[] parts = order.details().split("\\|");
        assertEquals(333.33334f, Float.parseFloat(parts[0]), 0.01f);
        assertEquals(333.33334f, Float.parseFloat(parts[1]), 0.01f);
    }

    @Test
    void testIssueDefendCommand() {
        commander.process(Collections.emptyList());
        
        Entity carrier = new Entity("carrier1", Entity.Type.CARRIER, "RED", 500, 500, 0, 0, null, 0, 0, 0, "100");
        Entity fighter = new Entity("fighter1", Entity.Type.FIGHTER, "RED", 600, 600, 0, 0, null, 0, 0, 0, "100");
        
        // No threats, should use enemy carrier or just stay
        Entity enemyCarrier = new Entity("enemyCarrier", Entity.Type.CARRIER, "BLUE", 100, 100, 0, 0, null, 0, 0, 0, "100");
        Collection<Entity> entities = List.of(carrier, fighter, enemyCarrier);
        
        AICommandParser.Command cmd = new AICommandParser.Command("DEFEND", null);
        
        commander.issueCommand(fighter, cmd, entities);
        
        Order order = orderSender.lastOrder;
        // hash of "fighter1" determines distance
        int hash = Math.abs("fighter1".hashCode());
        float expectedDistance = (hash % 2 == 0) ? 40 : 80;
        expectedDistance += (hash % 10 - 5) * 2;
        
        float expectedRel = (float)((-400.0 / 565.6854) * expectedDistance);
        
        assertEquals("fighter1", order.id());
        assertEquals(OrderType.MOVE, order.type());
        String[] parts = order.details().split("\\|");
        assertEquals(expectedRel, Float.parseFloat(parts[0]), 0.1f);
        assertEquals(expectedRel, Float.parseFloat(parts[1]), 0.1f);
    }

    @Test
    void testMissileEvasion() {
        commander.process(Collections.emptyList());
        
        Entity carrier = new Entity("carrier1", Entity.Type.CARRIER, "RED", 500, 500, 0, 0, null, 0, 0, 0, "100");
        // Missile at 400, 400 moving towards 500, 500
        // Velocity (10, 10)
        Entity missile = new Entity("missile1", Entity.Type.MISSILE, "BLUE", 400, 400, 10, 10, null, 0, 0, 0, "100");
        
        Collection<Entity> entities = List.of(carrier, missile);
        
        commander.process(entities);
        
        Order order = orderSender.lastOrder;
        assertEquals("carrier1", order.id());
        assertEquals(OrderType.MOVE, order.type());
        
        // Evade vector: carrier(500,500) - missile(400,400) = (100, 100)
        // Normalized: (1/sqrt(2), 1/sqrt(2))
        // Move dist 100: (70.71, 70.71)
        String[] parts = order.details().split("\\|");
        assertEquals(70.71f, Float.parseFloat(parts[0]), 0.1f);
        assertEquals(70.71f, Float.parseFloat(parts[1]), 0.1f);
    }
}
