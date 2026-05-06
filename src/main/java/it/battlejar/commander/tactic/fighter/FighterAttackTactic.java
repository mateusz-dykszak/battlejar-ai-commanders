package it.battlejar.commander.tactic.fighter;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.GameUtils;
import it.battlejar.commander.tactic.Tactic;

import java.util.Optional;

public class FighterAttackTactic implements Tactic<Entity> {

    private final float intruderCarrierRange;

    public FighterAttackTactic(float intruderCarrierRange) {
        this.intruderCarrierRange = intruderCarrierRange;
    }

    @Override
    public Optional<Order> apply(Entity fighter, GameSnapshot snapshot, CommanderState state) {
        Entity carrier = snapshot.myCarrier();
        Entity intruder = snapshot.activeEnemyFighters().stream()
                .filter(e -> GameUtils.distance(e, carrier) < intruderCarrierRange)
                .min((a, b) -> Float.compare(GameUtils.distance(a, fighter), GameUtils.distance(b, fighter)))
                .orElse(null);
        if (intruder != null) {
            return Optional.of(new Order(fighter.id(), OrderType.ATTACK, intruder.id()));
        }
        Entity target = snapshot.primaryTarget();
        if (target != null) {
            return Optional.of(new Order(fighter.id(), OrderType.ATTACK, target.id()));
        }
        if (snapshot.hasEnemies()) {
            return Optional.of(new Order(fighter.id(), OrderType.ATTACK));
        }
        return Optional.empty();
    }
}
