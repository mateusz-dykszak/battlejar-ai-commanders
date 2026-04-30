Execute one of the following tasks, commit and push to github when task is completed.
If no open tasks remain, analyze history/ (.log and .md files only) and add new improvement tasks below.

- [x] Analyze AGANTS.md and the client sources and setup the game client

- [x] Modify Gradle setup to copy the result jar to ./build/commander.jar

- [x] Move carrier toward center at game start — carrier stays in its spawn corner every game, giving up all map control. Use MOVE to push it toward (0|0) relative offset (i.e. hold position near center-field).

- [ ] Give fighters explicit formation positions — fighters currently drift with no positional orders and never contest center lanes. MOVE each fighter to a spread formation around the carrier (e.g. offsets like 80|0, -80|0, 0|80) so they create a real screen.

- [ ] Prioritize attacking the enemy carrier — current ATTACK sends fighters at the nearest enemy. Add TARGET "C" and ATTACK with the enemy carrier id so fighters focus on the win condition instead of trading with escorts.

- [ ] Dock damaged fighters — no health check exists at all. Parse numeric status; DOCK any fighter below a health threshold (e.g. < 30) so it survives and reloads.

- [ ] Fire missiles — FIRE_MISSILE is never called. Fire carrier missiles when an enemy carrier is within range, and release fighter missiles when fighters are close to an enemy carrier.
