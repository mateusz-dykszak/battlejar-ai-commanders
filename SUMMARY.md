# Summary

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
