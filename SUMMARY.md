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
