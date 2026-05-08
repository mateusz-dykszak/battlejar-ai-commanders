package it.battlejar.commander;

import it.battlejar.api.Entity;
import it.battlejar.api.Order;
import it.battlejar.client.AbstractCommander;
import it.battlejar.commander.strategy.DeploymentDecorator;
import it.battlejar.commander.strategy.MultiEnemyHunterStrategy;
import it.battlejar.commander.strategy.MultiEnemySurvivalStrategy;
import it.battlejar.commander.strategy.OneVsOneStrategy;
import it.battlejar.commander.strategy.Strategy;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
public class ClaudeCommander extends AbstractCommander {

    private static final long ORDER_COOLDOWN_MS = 150;

    private final CommanderState state = new CommanderState();
    private final List<Strategy> strategies;
    private final OrderSender orderSender;

    public ClaudeCommander() {
        this.strategies = List.of(
                new DeploymentDecorator(new OneVsOneStrategy()),
                new DeploymentDecorator(new MultiEnemySurvivalStrategy()),
                new DeploymentDecorator(new MultiEnemyHunterStrategy())
        );
        this.orderSender = order -> {
            long now = System.currentTimeMillis();
            if (now - state.lastOrderTime.getOrDefault(order.id(), 0L) < ORDER_COOLDOWN_MS) return;
            state.lastOrderTime.put(order.id(), now);
            order(order);
        };
    }

    @Override
    protected boolean process(Collection<Entity> entities) {
        Entity myCarrier = entities.stream()
                .filter(e -> e.type() == Entity.Type.CARRIER)
                .filter(e -> myColor.name().equals(e.color()))
                .findFirst()
                .orElse(null);
        if (myCarrier == null) return true;

        GameSnapshot snapshot = buildSnapshot(entities, myCarrier);

        strategies.stream()
                .filter(s -> s.applies(snapshot, state))
                .findFirst()
                .ifPresent(s -> s.execute(snapshot, state, orderSender));

        return true;
    }

    private GameSnapshot buildSnapshot(Collection<Entity> entities, Entity myCarrier) {
        List<Entity> myActiveFighters = entities.stream()
                .filter(e -> e.type() == Entity.Type.FIGHTER)
                .filter(e -> myColor.name().equals(e.color()))
                .filter(e -> !"D".equals(e.status()) && !"C".equals(e.status()))
                .toList();

        List<Entity> myDockedFighters = entities.stream()
                .filter(e -> e.type() == Entity.Type.FIGHTER)
                .filter(e -> myColor.name().equals(e.color()))
                .filter(e -> "C".equals(e.status()))
                .toList();

        List<Entity> liveEnemyCarriers = entities.stream()
                .filter(e -> e.type() == Entity.Type.CARRIER)
                .filter(e -> !myColor.name().equals(e.color()))
                .filter(e -> !"D".equals(e.status()))
                .toList();

        boolean hasEnemies = entities.stream()
                .filter(e -> !myColor.name().equals(e.color()))
                .anyMatch(e -> !"D".equals(e.status()));

        if (liveEnemyCarriers.size() > state.initialEnemyCarrierCount) {
            state.initialEnemyCarrierCount = liveEnemyCarriers.size();
        }
        boolean hasKilledEnemy = liveEnemyCarriers.size() < state.initialEnemyCarrierCount;

        List<Entity> activeEnemyFighters = entities.stream()
                .filter(e -> e.type() == Entity.Type.FIGHTER)
                .filter(e -> !myColor.name().equals(e.color()))
                .filter(e -> !"D".equals(e.status()) && !"C".equals(e.status()))
                .toList();

        List<Entity> armedEnemyMissiles = entities.stream()
                .filter(e -> e.type() == Entity.Type.MISSILE)
                .filter(e -> !myColor.name().equals(e.color()))
                .filter(e -> "A".equals(e.status()))
                .toList();

        Entity primaryTarget = selectTarget(liveEnemyCarriers, myCarrier);
        if (primaryTarget != null) {
            state.lastFormationAngle = (float) Math.atan2(
                    primaryTarget.py() - myCarrier.py(),
                    primaryTarget.px() - myCarrier.px());
        }

        float effectiveWideRadius = GameConfig.FORMATION_RADIUS_WIDE;
        if (liveEnemyCarriers.size() == 1 && primaryTarget != null) {
            float ec = GameUtils.distance(myCarrier, primaryTarget);
            if (ec < GameConfig.FORMATION_RADIUS_WIDE) {
                effectiveWideRadius = Math.max(80f, ec / 2f);
            }
        }
        boolean expanded = myActiveFighters.size() >= GameConfig.FORMATION_EXPAND_AT;
        float formRadius = expanded ? effectiveWideRadius : GameConfig.FORMATION_RADIUS_TIGHT;
        float formArc = (expanded && liveEnemyCarriers.size() == 1)
                ? (float) Math.PI
                : 2f * (float) Math.PI;
        int[][] formation = buildFormation(state.lastFormationAngle, formRadius, formArc);

        Map<String, int[]> interceptMap = buildInterceptMap(armedEnemyMissiles, myCarrier, myActiveFighters);

        return new GameSnapshot(
                myCarrier, myActiveFighters, myDockedFighters,
                liveEnemyCarriers, activeEnemyFighters, armedEnemyMissiles,
                primaryTarget, state.lastFormationAngle,
                hasEnemies, hasKilledEnemy,
                interceptMap, formation, settings);
    }

    private Entity selectTarget(List<Entity> liveEnemyCarriers, Entity myCarrier) {
        return liveEnemyCarriers.stream()
                .filter(e -> GameUtils.health(e) <= GameConfig.KILL_FOCUS_HP
                        && GameUtils.distance(e, myCarrier) <= GameConfig.KILL_FOCUS_RANGE)
                .min((a, b) -> Integer.compare(GameUtils.health(a), GameUtils.health(b)))
                .or(() -> liveEnemyCarriers.stream()
                        .min((a, b) -> Float.compare(
                                GameUtils.distance(a, myCarrier), GameUtils.distance(b, myCarrier))))
                .orElse(null);
    }

    private int[][] buildFormation(float angleRad, float radius, float arcRad) {
        int slots = GameConfig.FORMATION_SLOTS;
        int[][] offsets = new int[slots][2];
        for (int i = 0; i < slots; i++) {
            float t = (float) i / slots;
            float slotAngle = arcRad < 2f * (float) Math.PI
                    ? angleRad - arcRad / 2f + t * arcRad + arcRad / (2f * slots)
                    : t * arcRad;
            offsets[i][0] = Math.round(radius * (float) Math.cos(slotAngle));
            offsets[i][1] = Math.round(radius * (float) Math.sin(slotAngle));
        }
        return offsets;
    }

    private Map<String, int[]> buildInterceptMap(
            List<Entity> armedEnemyMissiles, Entity myCarrier, List<Entity> myFighters) {
        List<Entity> threats = armedEnemyMissiles.stream()
                .filter(e -> GameUtils.distance(e, myCarrier) < GameConfig.MISSILE_INTERCEPT_RANGE)
                .sorted((a, b) -> Float.compare(
                        GameUtils.distance(a, myCarrier), GameUtils.distance(b, myCarrier)))
                .toList();

        Map<String, int[]> result = new HashMap<>();
        Set<String> assigned = new HashSet<>();
        for (Entity missile : threats) {
            myFighters.stream()
                    .filter(f -> !assigned.contains(f.id()))
                    .filter(f -> !GameUtils.isNearBorder(f, settings, GameConfig.BORDER_MARGIN))
                    .min((a, b) -> Float.compare(GameUtils.distance(a, missile), GameUtils.distance(b, missile)))
                    .ifPresent(f -> {
                        assigned.add(f.id());
                        result.put(f.id(), new int[]{
                            Math.round(missile.px() - myCarrier.px()),
                            Math.round(missile.py() - myCarrier.py())
                        });
                    });
        }
        return result;
    }
}
