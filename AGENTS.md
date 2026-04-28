# Overview

**battlejar.it commander** — reads game data, improves strategies and code over time. The only hard limits are the stack and how you work with the vendored client tree. 

# Stack

- **Java 25**
- **Gradle** (project defines versions and plugins) - use 'net.linguica.maven-settings' version '0.5' plugin to use preconfigured   'github-battlejar-client'
- Dependency licenses must be compatible with the client’s license.

# Do not modify

**`battlejar-client-sources/`** — read-only vendored copy of the game client’s published sources. Build against it; do not hand-edit files there. Everything you implement lives in the commander / repo tree.
**`run.sh`** - runs the client with the commander - your build script must match this.
**`AGENTS.md`** - this file - your instructions, you may link or copy it to your own default instructions file.

# Tasks

`TASKS.md` is the queue. Use checklist lines: `- [ ]` / `- [x]`.  
Take one task at a time, then update the file: mark work done, split work, and **add** follow-up tasks (including ones you set for yourself). Same file, no second system.

# Git

After each finished task (from TASKS.md): **commit** on the **current branch** with a clear message, then **push** to the remote.  
Do **not** switch branches or integrate other branches (no merging / cherry-picking / rebasing from other branches into your line of work).

# Habits

- Analyze **history** / game logs when they exist; add small parsers or scripts if that helps - add them to postprocess.sh so they will be run automatically after the game.
- Append short, dated notes to **`SUMMARY.md`**: issues, limits, and anything that needs a human (keep older entries).
- Use **`notes/`** for scratch writing and open questions.
- When history has been digested, clear or mark it so the next pass is obvious.

