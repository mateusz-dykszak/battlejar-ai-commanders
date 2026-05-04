# Summary

## 2026-05-04 — 36-game analysis (new games include 3 wins)

Three confirmed wins across all history: fc7c192f (132s, peak 15), a7ffcd33 (62.9s, peak 14 — "Winner: Klaudiusz"), 1b9ad40f (39s, peak 17, hp_min=24 barely survived). Two more draws (both carriers alive at time limit).

**Early-game damage pattern (wins vs losses):** All three wins show identical signature — fighters drop from 5–6 to 1–3 at t=8–9s as they die blocking the first missile wave, carrier HP holds at 1000 until t=11s, then takes first hit (1000→735). Death games show simultaneous 2+ missile hits at t=4–8s with only 1–3 fighters. The tight 50-unit formation IS working (fighters die as shields), but the 180° arc leaves flanks unguarded against multi-directional attacks.

**Early missile saturation correlation:** max missiles within 200 units in first 15s: 5–9 = quick death (13–15s); 1–4 = survives longer. Fighter count matters less than arrival timing of the first salvo.

Three new tasks: 360° ring for early formation, focus-fire weakest carrier, wider missile detection thresholds.

## 2026-05-04 — 27-game stats analysis (local_history/)

First confirmed win (fc7c192f, 132s): peak 15 fighters, 72 total deployed, 2 enemy carriers eliminated; survived alongside Red (draw on carrier count, but narrative confirms Blue map dominance). Carrier HP reached minimum 77/1000.

**Survival vs death breakdown across 27 games:**
- Peak ≥ 12 fighters → death ≥ 35s or survived (8 games)
- Peak 6–11 fighters → death 13–21s (9 games)
- Peak 0 (pre-deploy-fix) → death ~23s (7 games)

**Missile penetration is universal**: min missile distance is 10.5–10.7 in every game with fighters deployed — missiles consistently reach the carrier. Root cause: formation radius 150 puts early fighters too far; missiles enter the 80-unit danger zone at t=4s when only 2-3 fighters are active. In the survival game, the first enemy missiles arrived later (182 units at t=3s), giving fighters time to intercept.

**Carrier velocity field available**: `Entity.vx()` / `vy()` confirmed in the API. Enables computing missile heading for a dodge manoeuvre.

**Never won offensively**: in every long-surviving game the carrier just PATROLs; after eliminating enemies we never push in to finish.

Three new tasks: adaptive formation radius (tight early, wide later), carrier lateral dodge based on missile velocity, carrier aggression after first kill.

## 2026-05-01 — 9 new games analysis

Fighters now deploy (fix confirmed working). But carrier still dies at 13–20 s in 7/7 games as Blue. Root cause: carrier rushed to world center at ~6 s, exposed to 3 enemies simultaneously, only 4–10 fighters active at time of death (factory releases ~1/2 per second). JSONL confirmed: 2 enemy missiles reached within 12–18 units of carrier (inside the 80-unit formation ring). The one game as GREEN (corner position) Klaudiusz survived to 52 s. Removed carrier MOVE-to-center; carrier now stays in spawn quadrant, only moves ~62 px to clear near border. Three new tasks added: directional formation, active missile intercept.

## 2026-05-01 — New game post-improvements (d94f33ee)

Klaudiusz destroyed at 13 s with **zero fighters ever deployed**. Root cause confirmed from JSONL: all 30 blue fighters stayed docked (status "C") for the entire game. Other teams (RED/GREEN/VIOLET) had active fighters by frame 10. The commander sends no orders to docked fighters; without an order the carrier won't release them. All formation/targeting/missile improvements were irrelevant — the carrier fought alone. Three new tasks added: deploy docked fighters (critical), fix TARGET+ATTACK cooldown conflict, add PATROL for carrier when centered.

## 2026-05-01 — History analysis (7 games)

Klaudiusz lost or was irrelevant in all 7 games. Common verdicts: "passive", "inactive", "under-committed", "non-participatory". Eliminated at 17–31 s in 6 of 7 matches; survived once but had no impact.

**Root causes identified in ClaudeCommander:**
- Carrier never moves from spawn corner — instant positional disadvantage.
- Fighters receive no MOVE orders, so they drift with no lane control.
- ATTACK targets the nearest enemy, never the enemy carrier directly.
- No health check or DOCK on damage — fighters fight to destruction rather than surviving.
- FIRE_MISSILE is never called — missiles are wasted.

Winning strategies observed across opponents: carrier pushed to center early, fighters in spread formation controlling lanes, direct focus on enemy carrier, high launch/reload tempo.

Five improvement tasks added to TASKS.md.
