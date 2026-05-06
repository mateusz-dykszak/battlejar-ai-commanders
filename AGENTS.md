# Overview

**battlejar.it commander** — reads game data, improves strategies and code over time. The only hard limits are the stack and how you work with the vendored client tree.

# Stack

- **Java 25**
- **Gradle** (project defines versions and plugins) - use 'net.linguica.maven-settings' version '0.5' plugin to use preconfigured 'github-battlejar-client'
- Dependency licenses must be compatible with the client’s license.

# Do not modify

- **`battlejar-client-sources/`** — read-only vendored copy of the game client’s published sources. Build against it; do not hand-edit files there. Everything you implement lives in the commander / repo tree.
- **`run.sh`** — runs the client with the commander; your build script must match this.
- **`AGENTS.md`** — this file; your instructions.

# Build and run

```bash
./gradlew shadowJar          # compile + produce fat JAR
./run.sh                     # shadowJar → run → postprocess.sh
```

`run.sh` expects the fat JAR at `build/commander.jar`.
GitHub Packages credentials are provided via the `net.linguica.maven-settings` plugin (version `0.5`), which reads the pre-configured `github-battlejar-client` server from `~/.m2/settings.xml`. Use this plugin when declaring the repository in `build.gradle.kts` instead of inline credentials.

# Architecture

```
Main
 └─ BattleJarContinuous         # multi-game loop (virtual threads)
     └─ AgenticCommander        # strategy — extends AbstractCommander (placeholder)
```

`AbstractCommander` (from the client library) handles registration, WebSocket lifecycle, and phase filtering. It exposes:
- `myColor` — your assigned `Color` enum value
- `settings` — `GameSettings` (world dimensions, entity sizes/health)
- `order(Order)` — send an order to the server

Implement strategy in `AgenticCommander.process(Collection<Entity>)` (placeholder). Return `true` to keep playing, `false` to quit. This method is only called during the `RUNNING` game phase.

`run.sh` sets two env vars consumed by the app:
- `BATTLEJAR_API_URL` — server endpoint
- `BATTLEJAR_HISTORY_DIR` — where game logs are saved (currently `./history`)

# Key game rules

- **MOVE coordinates are relative to your own carrier**, not absolute world coordinates. `"0|0"` returns an entity to the carrier.
- **One order per entity at a time** — sending a second order before the first is processed discards the first and restarts. Use a per-entity cooldown (e.g., ≥ 150 ms).
- **Border kills** — entities that leave the world boundary are destroyed. The game does not push them back; you must issue MOVE orders to keep your fleet inside.
- **Missiles are guided by the game** after firing; they do not respond to orders.
- **Entity status codes**: numeric = health, `"C"` = docked, `"D"` = destroyed/inactive, `"A"` = missile armed, `"E"` = missile exploding.
- **Filter active units** by excluding status `"D"` (destroyed) and `"C"` (docked) before issuing combat orders.

# Tasks

`TASKS.md` is the queue. Use checklist lines: `- [ ]` / `- [x]`.
Take one task at a time, then update the file: mark work done, split work, and **add** follow-up tasks (including ones you set for yourself). Same file, no second system.
When the queue has no open tasks: analyze `history/` and add new improvement tasks to `TASKS.md` based on what you find.

# Git

After each finished task (from TASKS.md): **commit** on the **current branch** with a clear message, then **push** to the remote.
Do **not** switch branches or integrate other branches (no merging / cherry-picking / rebasing from other branches into your line of work).

# Analysis outputs

`postprocess.sh` runs after every game session and processes every `history/*.jsonl` that hasn't been seen yet.
Read generated analysis files (e.g., in `local_history/` placeholder) when analyzing past performance.

## Available scripts (Placeholders)

| Script | Output | One-line description |
|--------|--------|----------------------|
| `scripts/stats.py` | `<gameId>.stats.json` | Per-game summary. |
| `scripts/deployment.py` | `<gameId>.deployment.csv` | Per-second deployment stats. |
| `scripts/threats.py` | `<gameId>.threats.csv` | Per-second threat analysis. |

# Habits

- Analyze **history** / game logs when they exist; add small parsers or scripts if that helps - add them to `postprocess.sh` so they will be run automatically after the game.
- Append short, dated notes to **`SUMMARY.md`**: issues, limits, and anything that needs a human (keep older entries).
- Use **`notes/`** for scratch writing and open questions.
- When history has been digested, clear or mark it so the next pass is obvious.

