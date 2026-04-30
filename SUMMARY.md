# Summary

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
