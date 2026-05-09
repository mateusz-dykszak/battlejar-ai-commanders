package it.battlejar.commander.strategy;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.OrderSender;

import java.util.List;

/**
 * Wraps any strategy and prepends a deployment step: sends every docked fighter to its assigned
 * formation slot so fighters undock spread out rather than stacking at the same point and
 * colliding. Slot assignment is the same modular mapping used by {@link
 * it.battlejar.commander.tactic.fighter.FormationMoveTactic}.
 */
public class DeploymentDecorator implements Strategy {

    private final Strategy inner;

    public DeploymentDecorator(Strategy inner) {
        this.inner = inner;
    }

    @Override
    public boolean applies(GameSnapshot snapshot, CommanderState state) {
        return inner.applies(snapshot, state);
    }

    @Override
    public void execute(GameSnapshot snapshot, CommanderState state, OrderSender sender) {
        deployDockedFighters(snapshot, sender);
        inner.execute(snapshot, state, sender);
    }

    private void deployDockedFighters(GameSnapshot snapshot, OrderSender sender) {
        List<Entity> docked = snapshot.myDockedFighters();
        if (docked.isEmpty()) return;
        int[][] formation = snapshot.deploymentFormation();
        for (Entity fighter : docked) {
            int[] slot = slotFor(fighter, formation);
            sender.send(new Order(fighter.id(), OrderType.MOVE, slot[0] + "|" + slot[1]));
        }
    }

    private static int[] slotFor(Entity fighter, int[][] formation) {
        try {
            int num = Integer.parseInt(fighter.id().split("-")[1]);
            return formation[num % formation.length];
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return formation[0];
        }
    }
}
