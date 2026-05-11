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
 * Fallback attack when a fighter is in formation and has no defensive duty. Prefers enemy
 * fighters that have breached the carrier's inner perimeter (intruder range differs by scenario),
 * then the primary target carrier, then a generic ATTACK as a last resort.
 */
public class FighterAttackTactic implements Tactic<Entity> {

    private final float intruderCarrierRange;
    private final boolean intruderOnly;

    public FighterAttackTactic(float intruderCarrierRange) {
        this(intruderCarrierRange, false);
    }

    /** @param intruderOnly if true, returns empty when no intruder is found (no carrier fallback) */
    public FighterAttackTactic(float intruderCarrierRange, boolean intruderOnly) {
        this.intruderCarrierRange = intruderCarrierRange;
        this.intruderOnly = intruderOnly;
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
        if (intruderOnly) return Optional.empty();
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
