package it.battlejar.commander.tactic.fighter;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.GameUtils;
import it.battlejar.commander.tactic.Tactic;

import java.util.Optional;

/**
 * Moves a fighter to its assigned formation slot relative to the carrier when it has drifted
 * too far from that position. Formation layout (radius, arc) is precomputed in
 * {@link it.battlejar.commander.GameSnapshot#formation()}.
 */
public class FormationMoveTactic implements Tactic<Entity> {

    private final float formationThreshold;

    public FormationMoveTactic(float formationThreshold) {
        this.formationThreshold = formationThreshold;
    }

    @Override
    public Optional<Order> apply(Entity fighter, GameSnapshot snapshot, CommanderState state) {
        int[] offset = formationSlot(fighter, snapshot.formation());
        Entity carrier = snapshot.myCarrier();
        if (GameUtils.distanceTo(fighter, carrier.px() + offset[0], carrier.py() + offset[1]) >= formationThreshold) {
            return Optional.of(new Order(fighter.id(), OrderType.MOVE, offset[0] + "|" + offset[1]));
        }
        return Optional.empty();
    }

    private int[] formationSlot(Entity fighter, int[][] formation) {
        try {
            int num = Integer.parseInt(fighter.id().split("-")[1]);
            return formation[num % formation.length];
        } catch (NumberFormatException e) {
            return formation[0];
        }
    }
}
