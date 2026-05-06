package it.battlejar.commander.strategy;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.api.OrderType;
import it.battlejar.commander.CommanderState;
import it.battlejar.commander.GameConfig;
import it.battlejar.commander.GameSnapshot;
import it.battlejar.commander.GameUtils;
import it.battlejar.commander.OrderSender;

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
        if (snapshot.myDockedFighters().isEmpty()) return;
        Entity carrier = snapshot.myCarrier();
        Entity threat = findClosestThreat(snapshot, carrier);

        int[] offset;
        if (threat != null) {
            float dx = threat.px() - carrier.px();
            float dy = threat.py() - carrier.py();
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist > 0.001f) {
                offset = new int[]{
                    Math.round(dx / dist * GameConfig.DEPLOY_OFFSET),
                    Math.round(dy / dist * GameConfig.DEPLOY_OFFSET)
                };
            } else {
                offset = new int[]{(int) GameConfig.DEPLOY_OFFSET, 0};
            }
        } else {
            float angle = snapshot.primaryTargetAngle();
            offset = new int[]{
                Math.round((float) Math.cos(angle) * GameConfig.DEPLOY_OFFSET),
                Math.round((float) Math.sin(angle) * GameConfig.DEPLOY_OFFSET)
            };
        }

        String offsetStr = offset[0] + "|" + offset[1];
        for (Entity docked : snapshot.myDockedFighters()) {
            sender.send(new Order(docked.id(), OrderType.MOVE, offsetStr));
        }
    }

    private Entity findClosestThreat(GameSnapshot snapshot, Entity carrier) {
        Entity missile = snapshot.armedEnemyMissiles().stream()
                .min((a, b) -> Float.compare(GameUtils.distance(a, carrier), GameUtils.distance(b, carrier)))
                .orElse(null);
        if (missile != null) return missile;

        Entity fighter = snapshot.activeEnemyFighters().stream()
                .min((a, b) -> Float.compare(GameUtils.distance(a, carrier), GameUtils.distance(b, carrier)))
                .orElse(null);
        if (fighter != null) return fighter;

        return snapshot.liveEnemyCarriers().stream()
                .min((a, b) -> Float.compare(GameUtils.distance(a, carrier), GameUtils.distance(b, carrier)))
                .orElse(null);
    }
}
