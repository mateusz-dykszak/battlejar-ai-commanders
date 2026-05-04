# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# Overview

**battlejar.it commander** — reads game data, improves strategies and code over time. The only hard limits are the stack and how you work with the vendored client tree.

# Stack

- **Java 25**
- **Gradle** (project defines versions and plugins)
- Dependency licenses must be compatible with the client's license.

# Do not modify

- **`battlejar-client-sources/`** — read-only vendored copy of the game client's published sources. Build against it; do not hand-edit files there.
- **`run.sh`** — runs the client with the commander; your build script must match it.
- **`AGENTS.md`** — agent instructions; do not edit.

# Build and run

```bash
./gradlew shadowJar          # compile + produce fat JAR
./run.sh                     # shadowJar → run → postprocess.sh
```

`run.sh` expects the fat JAR at `build/commander.jar`. The shadow plugin is configured to output directly to `build/commander.jar`.

GitHub Packages credentials are provided via the `net.linguica.maven-settings` plugin (version `0.5`), which reads the pre-configured `github-battlejar-client` server from `~/.m2/settings.xml`. Use this plugin when declaring the repository in `build.gradle.kts` instead of inline credentials.

# Architecture

```
Main
 └─ BattleJarContinuous         # multi-game loop (virtual threads)
     └─ ClaudeCommander         # strategy — extends AbstractCommander
```

`AbstractCommander` (from the client library) handles registration, WebSocket lifecycle, and phase filtering. It exposes:
- `myColor` — your assigned `Color` enum value
- `settings` — `GameSettings` (world dimensions, entity sizes/health)
- `order(Order)` — send an order to the server

Implement strategy in `ClaudeCommander.process(Collection<Entity>)`. Return `true` to keep playing, `false` to quit. This method is only called during the `RUNNING` game phase.

`run.sh` sets two env vars consumed by the app:
- `BATTLEJAR_API_URL` — server endpoint (switch to `http://localhost:8888` for local testing)
- `BATTLEJAR_HISTORY_DIR` — where game logs are saved (currently `./history`, i.e. the `history/` directory in the repo)

# Key game rules

- **MOVE coordinates are relative to your own carrier**, not absolute world coordinates. `"0|0"` returns an entity to the carrier.
- **One order per entity at a time** — sending a second order before the first is processed discards the first and restarts. Use a per-entity cooldown (≥ 150 ms, as in `ClaudeCommander`).
- **Border kills** — entities that leave the world boundary are destroyed. The game does not push them back; you must issue MOVE orders to keep your fleet inside.
- **Missiles are guided by the game** after firing; they do not respond to orders.
- **Entity status codes**: numeric = health, `"C"` = docked, `"D"` = destroyed/inactive, `"A"` = missile armed, `"E"` = missile exploding.
- **Filter active units** by excluding status `"D"` (destroyed) and `"C"` (docked) before issuing combat orders.

# Tasks

`TASKS.md` is the queue. Use checklist lines: `- [ ]` / `- [x]`.  
Take one task at a time, then update the file: mark work done, split work, and **add** follow-up tasks (including ones you set for yourself). Same file, no second system.

When the queue has no open tasks: analyze `history/` (`.log` and `.md` files only) and add new improvement tasks to `TASKS.md` based on what you find.

# Git

After each finished task: **commit** on the **current branch** with a clear message, then **push** to the remote.  
Do **not** switch branches or integrate other branches (no merging / cherry-picking / rebasing from other branches into your line of work).

# Analysis outputs

`postprocess.sh` runs after every game session and processes every `history/*.jsonl` that hasn't been seen yet. Results land in `local_history/` (gitignored — player-specific, never committed). Three files are written per game:

| File | Contents |
|------|----------|
| `<gameId>.stats.json` | Player roster, game duration, carrier death order, our first-deploy time, peak/total fighters, closest missile approach, winner. Also the skip sentinel — if this file exists the game is not reprocessed. |
| `<gameId>.deployment.csv` | Per-second: active / docked / destroyed fighter counts + cumulative total deployed. Use to catch deployment failures or screens collapsing faster than fighters undock. |
| `<gameId>.threats.csv` | Per-second: our carrier HP, active fighters, nearest enemy carrier distance, nearest armed missile distance, missile counts within 200 and 80 units. The within-80 column measures formation-ring penetration. |

Read these files (`.json` / `.csv`) when analyzing past performance — they are small and already parsed. Do not read `.jsonl` files directly (too large).

# Habits

- Analyze **history** / game logs when they exist. Prefer `local_history/` stats files for quantitative analysis; use `history/*.md` for narrative context. When no stats files exist for a game, `postprocess.sh` will generate them on the next run.
- Append short, dated notes to **`SUMMARY.md`**: issues, limits, and anything that needs a human (keep older entries).
- Use **`notes/`** for scratch writing and open questions.
- When history has been digested, clear or mark it so the next pass is obvious.
