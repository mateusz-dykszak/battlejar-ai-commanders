# Summary of changes

## 2026-05-07
- Set log level to WARN in `logback.xml` to reduce noise.
- Enabled INFO logging for `it.battlejar.commander` package to ensure agent logs are visible.
- Added performance logging to `AIAgent.java` to measure LLM response time.
- Added request/response logging to `AIAgent.java` to help with strategy analysis.


### 2026-05-07
- Implemented immediate undocking for fighters. Docked fighters now receive `MOVE` orders to defensive positions (between carrier and threats) every tick, bypassing the 2-second LLM cooldown.
- Updated `AgenticCommander.process` to handle docked fighters independently of the AI loop.
- Refined fighter grouping in `executeAiResponse` to include all non-destroyed fighters, ensuring docked ones are also considered for sector commands.

### 2026-05-08
- Implemented missile evasion logic for the carrier. It now moves away from incoming enemy missiles that are within 400 units and heading towards it.
- Integrated evasion logic into the main game loop and defensive maneuvers.
- Added unit test for missile evasion.

### 2026-05-08
- Implemented carrier collision avoidance.
  - Carrier now avoids world borders (100 unit margin).
  - Carrier avoids high-density sectors (>15 entities, carrier counts as 10).
  - Passive avoidance ensures carrier stays safe even when idle.
  - Verified with unit tests.

## 2026-05-09
- Updated carrier border avoidance trigger distances to [10, 15, 20, 25] units based on approach angle.
- Increased base carrier safety margin to 25 units.
- Improved maneuver logic to return `null` instead of current position when no action is needed, reducing redundant "MOVE 0|0" orders.
- Analyzed game history: identified early carrier destruction (often < 20s) as a primary failure mode.
- Added new tasks to TASKS.md focused on carrier survival, automated post-run analysis, and improved AI tactical context.
- Implemented automatic history analysis in `postprocess.sh`.
  - Created `scripts/analyze_history.py` to extract survival time and death counts from game logs.
  - Configured `postprocess.sh` to run the analysis automatically after each session.
- Improved Carrier Kiting logic:
  - Replaced basic two-carrier avoidance with a multi-factor weighted avoidance vector (potential field).
  - Carrier now avoids: enemy carriers (long range), high-threat sectors from BattleMap (tactical avoidance), and nearby missiles (urgent evasion).
  - Added a weak center bias to prevent the carrier from getting pinned in corners.
  - Maintained strict 15-unit border safety margin.
  - Updated unit tests to reflect new movement calculations.

## 2026-05-09
- Enhanced AI Strategic Prompting: Updated `AIAgent` to include carrier health and fighter counts (active vs docked). Added "Mindset" guidelines to the LLM system prompt to encourage switching between aggressive and defensive strategies based on current status.
- Switched LLM model to `gpt-4o-mini` for better instruction following and reliability.
- Implemented Fighter "Harassment" logic:
  - Added `HARASS` command to `AIAgent` and `AICommandParser`.
  - Implemented `harass` method in `AgenticCommander` to position fighters near enemy carriers (70 units distance) to disrupt launches and intercept units.
  - Updated LLM prompt with guidelines for using the `HARASS` strategy.


2026-05-09: Fixed logic issues in `AgenticCommander.java`:
- Border avoidance: Only triggers when moving towards borders (or if stationary and too close), as per requirements.
- Emergency Screen: Trigger refined to be more conservative, only activating at low health (<30%) or extremely close high threat (<50-100 units).
- Missile evasion: Removed ineffective carrier-side relative moves for missiles; carrier now relies on fighter screen for missile defense.
- Fixed a bug in `calculateCarrierManeuver` where `distBottom` was incorrectly calculated using X coordinates.

### 2026-05-09
- Improved fighter aggressiveness and reduced collisions:
  - Modified `AgenticCommander.executeAiResponse` to prevent fighters from defaulting to a tight defensive formation when the carrier is safe.
  - Implemented `patrol` and `findNearestEnemy` logic for idle fighters to keep them active and spread out.
  - Adjusted `defend` formation spacing to be more relaxed (wider layers) when no immediate threat is detected.
  - Updated `AIAgent` system prompt to emphasize aggressive positioning when the carrier is not under attack.

### 2026-05-10
- Refined carrier border avoidance logic to prevent false positives when the carrier is moving away from the border.
- Removed the legacy absolute distance check (`dist < safeDistance`) that bypassed the movement direction check.
- Now, border avoidance only triggers if:
    - The carrier is moving towards the border (detected via dot product of velocity and border normal).
    - OR the carrier is nearly stationary and within the safety margin.
- Added regression tests in `AgenticCommanderTest` to verify the fix.
