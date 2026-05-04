# Summary

## 2026-05-04 — 83-game analysis; 180° formation arc is the primary multi-enemy gap

No new games since last session (83 total). Added `phases.py` to postprocessing — outputs `phases.json` per game with 1v1 start time, HP/EC-dist at 1v1, survival time, and death-cause label.

**42 multi-enemy deaths (excluding pre-deploy-fix peak=0 games): ef_near_100 peak median = 16, range 3–28.** Cause breakdown: fighters 62%, combined 33%, missiles 5%. **25 1v1 deaths: ef_near peak median = 9, fighters 72%.**

Root cause for multi-enemy deaths: the 180° directional arc at 8+ fighters only covers the nearest enemy's direction. Enemy fighters from the other 2 carriers approach from the exposed rear 180° unchallenged.

Three new tasks: 360° ring in multi-enemy, raise FIGHTER_INTRUDER_RANGE 60→120, raise DOCK_HEALTH_THRESHOLD 3→5.

## 2026-05-04 — 10 more games analyzed (73 total); first-kill timing is the win condition

2 new wins (f650d958: hp_min=318, 17bd6589: hp_min=719). Total: 6 survived, 2 wins from 73 games.

**First kill by t=15 = win; first kill after t=19 = loss** (from 73-game stats analysis). All 3 wins have first_kill_s ≤ 15.6. The opening 3 carrier missiles drop the nearest enemy from 1000 → ~500 at t=8 (confirmed in all_carriers.csv). KILL_FOCUS_HP=300 does not kick in at 500 HP, so fighters scatter instead of finishing the kill.

**ef_near_100 is the swarm metric**: in wins, ef_near@t=12 is 0–3. In deaths, it's 6–12. Current code: all 8 fighters attack ONE shared intruder, leaving 9–11 uncontested. Per-fighter independent targeting (each fighter attacks nearest enemy within 60 units of ITSELF) would distribute the load.

Three new tasks: per-fighter intruder targeting, raise KILL_FOCUS_HP to 600, early carrier push on wounded enemy before first kill.

**Postprocessing enriched**: timing_snapshots added to stats.json (our_hp/nearest_ec_hp/ef_near_100 at t=5/8/12/15, max_ef_near_100, hp_lost_by_t15, first_kill_s). Cross-game correlation now works from stats files alone without loading threats.csv.

## 2026-05-04 — 9 more games analyzed (63 total); formation overshoot in 1v1 identified

1 WIN (17bd6589): all 3 enemies killed (t=15.6, t=34.2, t=39.6), carrier hp=719 — cleanest win yet.

**Formation overshoot is the primary 1v1 damage mechanism.** When ec < 150 (FORMATION_RADIUS_WIDE), the forward fighters in the 180° arc overshoot the enemy carrier by `150 - ec` units. At ec=137 (91f49fd1, t=46-49): overshoot=13 units → 50 HP/s with m80=0 (carrier laser, not missiles). At ec=85 (t=54): overshoot=65 → 100 HP/s. At ec=23 (9a3bf322): overshoot=127 → 113 HP/s. Pattern is exact: **zero damage while ec ≥ 149, damage starts when ec < 149.** In 6493299c, 279 HP and 500 HP drops in 1s at ec=107 and ec=92 both with m80=0 — fighters plus carrier all lasering us from the same side because our formation has overshot.

Three new tasks: scale formation radius to ec/2 in 1v1 (primary fix), minimum carrier separation at ec<80, expand intruder-fighter detection from 50 to 100 units.

## 2026-05-04 — 9 more games analyzed (54 total); 1v1 loss pattern identified

6 of 9 new games reached 1v1 phase; WE LOST ALL 6. Two root causes: (1) Carrier kite creates a kite loop in 1v1 — oscillates at 148-174 units from enemy for 15s, never closes range (bd167898). (2) Enemy fighters rush inside our 50-unit ring and deal 305 HP laser damage in 1s (e4cbed4f, t=41). Fighter count dropped 6→2 simultaneously.

AGGRESSION_FIGHTER_THRESHOLD = 12 never fires in 1v1 (we have 5-9 fighters). Passive PATROL loses every 1v1.

Three new tasks: disable kite in 1v1 + lower aggression threshold to 6, attack close enemy fighters before distant carrier, carrier ATTACK in 1v1.

## 2026-05-04 — 10 new games + deep threats analysis (45 total)

10 new games, all deaths, peak 5–11 fighters, died 15–33s. Enemy carrier approach is universal (180→79–130 units by t=14–23s) and present even in the 132s survived game (ec=79 at t=14!). Survival depends on whether enemy carriers fight each other (diverting from us) — not on our defense quality alone.

Three new improvement tasks: fighter FIRE_MISSILE before TARGET "M" (kill approaching carrier fast), larger formation threshold (reduce slot-chasing from carrier dodge disruption), carrier kite when outnumbered (<8 fighters, ec<150).

## 2026-05-04 — threats.csv analysis: survived vs died high-peak games

Key finding: **ALL 3 survived games share one pattern — 2 of 3 enemies killed before t=26s, leaving only 1-vs-1 for the final stretch**. In every high-peak death game (peak 15-17), all 3 enemies stayed alive through ~t=25s, producing 3-directional missile fire that overwhelms the carrier regardless of fighter count.

**Two bugs identified from code review + threats data:**
1. Carrier dodge (priority 2) fires every 150 ms while any missile is within 200 units (true from t=3s onward). FIRE_MISSILE sits at priority 3, so the carrier almost never fires its own missiles — it just dodges indefinitely. Fix: move FIRE_MISSILE to priority 1.
2. `enemyMissilesNearby` is a shared flag — when ANY missile is within 300 units, ALL fighters switch to TARGET "M" defense. Missiles are present from t=5s onward, so fighters never attack. Fix: per-fighter proximity check (within 150 units of THAT fighter) so near-fighters defend while far-fighters attack.

Also: physical intercept at 250 units pulls formation fighters to chase missiles 7.5 seconds away; shrink to 80 units to stop disrupting formation.

Three new tasks: carrier FIRE_MISSILE priority, per-fighter TARGET "M", shrink intercept range.

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
