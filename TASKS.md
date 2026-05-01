Execute one of the following tasks, commit and push to github when task is completed.
If no open tasks remain, analyze history/ (.log and .md files only) and add new improvement tasks below.

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

- [x] Stop moving carrier to world center — confirmed from JSONL: carrier reaches center at ~6s, takes fire from 3 directions simultaneously, dies at 13–20s with only 4–10 fighters active. The one game Klaudiusz survived to 52s he was in a corner. Remove the MOVE-to-center logic; keep carrier in its spawn quadrant (border safety only). Compensate by increasing fighter formation radius from 80 to 150 so fighters naturally cover the center lanes.

- [x] Directional fighter formation — the 8-position symmetric ring wastes half the fighters facing borders or empty space. Rotate the formation to face the nearest enemy carrier: compute the direction to the enemy, then place all 8 slots in a forward-biased arc (e.g. a semicircle on the enemy-facing side).

- [ ] Active missile intercept — TARGET "M" makes fighters fire at missiles already in laser range but doesn't move fighters to intercept. When an armed enemy missile is within 150 units of the carrier, find the fighter closest to the missile's path and send it a MOVE order to the missile's current position (carrier-relative).
