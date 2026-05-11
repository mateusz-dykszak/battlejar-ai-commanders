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
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
        if (primaryTarget != null) {
            float ec = GameUtils.distance(myCarrier, primaryTarget);
            if (ec < GameConfig.FORMATION_RADIUS_WIDE) {
                effectiveWideRadius = Math.max(80f, ec / 2f);
            }
        }
        boolean expanded = myActiveFighters.size() >= GameConfig.FORMATION_EXPAND_AT;
        float formRadius = expanded ? effectiveWideRadius : GameConfig.FORMATION_RADIUS_TIGHT;
        float formArc = expanded ? (float) Math.PI : 2f * (float) Math.PI;
        int[][] formation = buildFormation(state.lastFormationAngle, formRadius, formArc);

        Map<String, int[]> interceptMap = buildInterceptMap(armedEnemyMissiles, myCarrier, myActiveFighters, state);

        // When an enemy carrier is very close, deploy fighters in the opposite direction so they
        // turn around toward the enemy — giving missiles time to arm before reaching the target.
        float deployAngle = state.lastFormationAngle;
        if (primaryTarget != null
                && GameUtils.distance(myCarrier, primaryTarget) < GameConfig.ENEMY_CLOSE_DEPLOY_THRESHOLD) {
            float flippedAngle = deployAngle + (float) Math.PI;
            float slotX = myCarrier.px() + formRadius * (float) Math.cos(flippedAngle);
            float slotY = myCarrier.py() + formRadius * (float) Math.sin(flippedAngle);
            if (!GameUtils.isNearBorder(slotX, slotY, settings, GameConfig.BORDER_MARGIN)) {
                deployAngle = flippedAngle;
            }
        }
        // If the center deploy slot is in the border zone, flip to the opposite side so fighters
        // don't undock directly into a border and waste ticks on BorderEvasionTactic.
        float centerX = myCarrier.px() + formRadius * (float) Math.cos(deployAngle);
        float centerY = myCarrier.py() + formRadius * (float) Math.sin(deployAngle);
        if (GameUtils.isNearBorder(centerX, centerY, settings, GameConfig.BORDER_MARGIN)) {
            float flippedAngle = deployAngle + (float) Math.PI;
            float flippedX = myCarrier.px() + formRadius * (float) Math.cos(flippedAngle);
            float flippedY = myCarrier.py() + formRadius * (float) Math.sin(flippedAngle);
            if (!GameUtils.isNearBorder(flippedX, flippedY, settings, GameConfig.BORDER_MARGIN)) {
                deployAngle = flippedAngle;
            }
        }
        int[][] deploymentFormation = buildFormation(deployAngle, formRadius, formArc);

        return new GameSnapshot(
                myCarrier, myActiveFighters, myDockedFighters,
                liveEnemyCarriers, activeEnemyFighters, armedEnemyMissiles,
                primaryTarget, state.lastFormationAngle,
                hasEnemies, hasKilledEnemy,
                interceptMap, formation, deploymentFormation, settings);
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
            List<Entity> armedEnemyMissiles, Entity myCarrier, List<Entity> myFighters,
            CommanderState state) {
        Set<String> activeMissileIds = armedEnemyMissiles.stream()
                .map(Entity::id).collect(Collectors.toSet());
        Set<String> activeFighterIds = myFighters.stream()
                .map(Entity::id).collect(Collectors.toSet());

        // Drop assignments for missiles that are gone or fighters that are gone
        state.missileInterceptAssignments.keySet().retainAll(activeMissileIds);
        state.missileInterceptAssignments.entrySet()
                .removeIf(e -> !activeFighterIds.contains(e.getValue()));

        List<Entity> threats = armedEnemyMissiles.stream()
                .filter(e -> GameUtils.distance(e, myCarrier) < GameConfig.MISSILE_INTERCEPT_RANGE)
                .sorted((a, b) -> Float.compare(
                        GameUtils.distance(a, myCarrier), GameUtils.distance(b, myCarrier)))
                .toList();

        Map<String, int[]> result = new HashMap<>();
        // Fighters already committed — don't double-assign
        Set<String> assignedFighters = new HashSet<>(state.missileInterceptAssignments.values());

        for (Entity missile : threats) {
            String fighterId = state.missileInterceptAssignments.get(missile.id());
            if (fighterId == null || !activeFighterIds.contains(fighterId)) {
                // Need a new assignment: closest-to-carrier, not already assigned, not near border
                Optional<Entity> candidate = myFighters.stream()
                        .filter(f -> !assignedFighters.contains(f.id()))
                        .filter(f -> !GameUtils.isNearBorder(f, settings, GameConfig.BORDER_MARGIN))
                        .min((a, b) -> Float.compare(
                                GameUtils.distance(a, myCarrier), GameUtils.distance(b, myCarrier)));
                if (candidate.isEmpty()) continue;
                fighterId = candidate.get().id();
                assignedFighters.add(fighterId);
                state.missileInterceptAssignments.put(missile.id(), fighterId);
            }
            // Intercept point = midpoint of missile → carrier (carrier-relative)
            result.put(fighterId, new int[]{
                Math.round((missile.px() - myCarrier.px()) / 2f),
                Math.round((missile.py() - myCarrier.py()) / 2f)
            });
        }
        return result;
    }
}
