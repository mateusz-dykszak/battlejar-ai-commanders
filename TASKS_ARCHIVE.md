# Completed tasks

- [x] Analyze AGANTS.md and the client sources and setup the game client

- [x] Modify Gradle setup to copy the result jar to ./build/commander.jar

- [x] Move carrier toward center at game start — carrier stays in its spawn corner every game, giving up all map control. Use MOVE to push it toward (0|0) relative offset (i.e. hold position near center-field).

- [x] Give fighters explicit formation positions — fighters currently drift with no positional orders and never contest center lanes. MOVE each fighter to a spread formation around the carrier (e.g. offsets like 80|0, -80|0, 0|80) so they create a real screen.

- [x] Prioritize attacking the enemy carrier — current ATTACK sends fighters at the nearest enemy. Add TARGET "C" and ATTACK with the enemy carrier id so fighters focus on the win condition instead of trading with escorts.

- [x] Dock damaged fighters — no health check exists at all. Parse numeric status; DOCK any fighter below a health threshold (e.g. < 30) so it survives and reloads.

- [x] Fire missiles — FIRE_MISSILE is never called. Fire carrier missiles when an enemy carrier is within range, and release fighter missiles when fighters are close to an enemy carrier.

- [x] Deploy docked fighters — all fighters start docked and only undock when they receive an order (confirmed from game data: 0 blue fighters ever deployed while all other teams had fighters active). Send formation MOVE orders to docked fighters that are not in recovery so the carrier releases them at the game's configured rate. Track fighters we explicitly docked for damage recovery (in a Set) and skip them for a recovery window (~3 s) before redeploying.

- [x] Fix TARGET + ATTACK cooldown conflict — the 150 ms per-entity cooldown means only TARGET "M" gets sent when enemy missiles are nearby; the ATTACK that follows is always dropped. Drop the redundant ATTACK call: TARGET "M" alone is sufficient because auto-targeting fires at missiles while that filter is set.

- [x] Carrier PATROL when centered — once the carrier reaches center it sends no orders (idle) unless fighters are gone. A stationary carrier is an easy target. Send PATROL when centered so it keeps moving unpredictably while fighters are active.

- [x] Stop moving carrier to world center — confirmed from JSONL: carrier reaches center at ~6s, takes fire from 3 directions simultaneously, dies at 13–20s with only 4–10 fighters active. The one game Klaudia survived to 52s she was in a corner. Remove the MOVE-to-center logic; keep carrier in its spawn quadrant (border safety only). Compensate by increasing fighter formation radius from 80 to 150 so fighters naturally cover the center lanes.

- [x] Directional fighter formation — the 8-position symmetric ring wastes half the fighters facing borders or empty space. Rotate the formation to face the nearest enemy carrier: compute the direction to the enemy, then place all 8 slots in a forward-biased arc (e.g. a semicircle on the enemy-facing side).

- [x] Active missile intercept — TARGET "M" makes fighters fire at missiles already in laser range but doesn't move fighters to intercept. When an armed enemy missile is within 150 units of the carrier, find the fighter closest to the missile's path and send it a MOVE order to the missile's current position (carrier-relative).

- [x] Adaptive formation radius — the fixed 150-unit radius leaves early fighters too far to shield the carrier. Missiles enter the 80-unit danger zone at t=4s when only 2-3 fighters are deployed; at radius 150 those fighters are spread wide and miss. When fewer than 8 fighters are active, use radius 50 so early fighters cluster tightly around the carrier. Once 8+ fighters are active, switch to the normal 150-unit directional arc.

- [x] Carrier evasion from incoming missiles — the carrier only PATROLs while fighters are active, making no attempt to dodge. Missiles are guided so their (vx, vy) reveals if they are heading straight for us. When an armed enemy missile is within 200 units and its velocity vector points toward our carrier, issue MOVE to the carrier perpendicular to the missile's velocity.

- [x] Carrier aggression after first kill — after eliminating at least one enemy carrier, switch from PATROL to closing in on the nearest remaining enemy when we have 12+ active fighters.

- [x] Full 360° ring for early formation — switch to a full 360° ring at radius 50 for the early phase so fighters provide all-around protection. Once 8+ fighters are active, keep the 180° directional arc at radius 150 for forward pressure.

- [x] Focus-fire weakest enemy carrier — change target selection to prefer the lowest-HP enemy carrier within 350 units; fall back to nearest if none are wounded.

- [x] Wider missile detection thresholds — increase TARGET "M" to 300 units and intercept to 250 units for more lead time.

- [x] Prioritize carrier FIRE_MISSILE above dodge — move FIRE_MISSILE to priority 1 before border, dodge, and all movement orders.

- [x] Per-fighter missile proximity for TARGET "M" defense — replace the shared enemyMissilesNearby flag with a per-fighter check: a fighter switches to TARGET "M" only when an armed enemy missile is within 150 units of THAT fighter.

- [x] Shrink physical intercept range from 250 to 80 units — reserve physical interception only for missiles deep inside the defensive ring; rely on TARGET "M" lasers for missiles at 80–300 units.

- [x] Fighter FIRE_MISSILE before TARGET "M" — reorder: check `fighter.missiles() > 0 && nearestEnemyCarrier within 150` before the per-fighter TARGET "M" check.

- [x] Increase FORMATION_THRESHOLD to reduce slot-chasing — increase from 50 to 100 so fighters within 100 units of their slot count as in formation and can attack.

- [x] Carrier kite — maintain distance when outnumbered.

- [x] Disable carrier kite in 1v1 and lower aggression threshold.

- [x] Attack enemy fighters inside our formation zone — after per-fighter TARGET "M" check but before MOVE-to-slot and carrier ATTACK, find the nearest enemy fighter within FORMATION_RADIUS_TIGHT of our carrier and attack it.

- [x] Carrier also attacks in 1v1 — issue ATTACK nearestEnemyCarrier instead of PATROL when 1v1 and no missiles to fire.

- [x] Scale formation radius to ec/2 in 1v1 — when enemy is within FORMATION_RADIUS_WIDE, use `max(FORMATION_RADIUS_TIGHT, min(FORMATION_RADIUS_WIDE, nearestEnemyDist/2))` as the effective wide radius.

- [x] Minimum carrier separation in 1v1 — when ec < 80, skip the aggression push and issue MOVE away by 60 units to prevent collision.

- [x] Expand intruding-fighter ATTACK range from 50 to 100 units.

- [x] Per-fighter independent intruder targeting — each fighter finds the nearest enemy fighter within 60 units OF THAT FIGHTER and attacks it.

- [x] Raise KILL_FOCUS_HP from 300 to 600.

- [x] Early carrier push on wounded enemy before first kill — when `!hasKilledEnemy && enemyHP ≤ KILL_FOCUS_HP && fighters ≥ 6 && liveEnemyCarriers > 1`, issue MOVE toward wounded carrier.

- [x] 360° formation in multi-enemy phase — use full ring when `liveEnemyCarriers.size() > 1`; reserve 180° arc for 1v1 only.

- [x] Raise FIGHTER_INTRUDER_RANGE from 60 to 120.

- [x] Raise DOCK_HEALTH_THRESHOLD from 3 to 5.

- [x] Revert DOCK_HEALTH_THRESHOLD from 5 back to 3.

- [x] Fix intruder targeting: gate on carrier proximity, not fighter proximity — use `distance(e, myCarrier) < FIGHTER_INTRUDER_CARRIER_RANGE` (75 units) instead of fighter-relative distance.

- [x] Reduce RECOVERY_MS from 3000 to 1500.

- [x] Fix post-kill kite dead zone in multi-enemy.

- [x] Lower 1v1 push threshold from 6 to 4 fighters.

- [x] Raise FIGHTER_INTRUDER_CARRIER_RANGE to 100 in 1v1.

- [x] Increase 1v1 carrier push distance from 80 to 400 units.

- [x] Raise KILL_FOCUS_HP from 600 to 750.

- [x] Refactor commander to strategy/tactic pattern — Strategy interface with applies/execute, GameSnapshot, CommanderState, Tactic<T> chain, DeploymentDecorator, three strategies (OneVsOne, MultiEnemySurvival, MultiEnemyHunter).

- [x] Remove CarrierDodgeTactic — carrier too slow to dodge missiles effectively.
