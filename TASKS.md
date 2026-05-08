Execute one of the following tasks, commit and push to github when task is completed

- [x] Compare AGENTS.md with CLAUDE.md from origin/claude-commander and update it
- [x] Link AGENTS.md to .junie/guidelines.md
- [x] Analyze AGENTS.md and the client sources and set up the game client
  Use these settings to access the client lib:
  ```
    plugin id 'net.linguica.maven-settings' version '0.5'
    maven {
      url = uri('https://maven.pkg.github.com/mateusz-dykszak/battlejar-client')
      name = 'github-battlejar-client'
    }
  ```
  If you decide to use maven, the ropi should work without any additional plugins. Its credentials are already configured in the .m2/settings.xml
- [x] Create a battle map.  
  Add a script that will parse received game snapshot data and base on that prepare a two dimensional array with map sector status. The size of the array should be configurable, use battlejar.conf file. For now use simple 3x3 split. For each sector prepare a record with the color status. The color's record should contain boolean marker if its carrier is in that sector, and fleet presence enum, with values NONE, SMALL, SIGNIFICANT, DOMINANCE. It should also contain list of fighters' names from that sector. DOMINANCE should be used only when the color's presence number is matching SIGNIFICANT level and enemies's presence is at lower level
- [x] Add threat marker to battle map data.
  For each enemy color based on current and old state calculate the threat level in the sector. It should reflect if enemies spacecraft are targeting our carrier or our fighters (moving in our direction)
- [x] Add langchain4j to dependencies (code is temporarily linked in project root folder)
  ```
      <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>dev.langchain4j</groupId>
                <artifactId>langchain4j-bom</artifactId>
                <version>1.13.1</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
      </dependencyManagement>
  ```
  Create and openai gpt 5.4-mini model and using it agent, with memory (api key should be taken from env variables - System.getenv("OPENAI_API_KEY")). Send battle map's carrier and fleet presence data to the agent and ask it to provide commands for each sector. Allowed commands are MOVE, ATTACK and DEFEND. MOVE and ATTACK should be followed by Section coordinates e.g. 1x2, 3x1, etc. Carrier can get one command, fighters can get more. If there are more commands, fighters will be split to groups with count equal to number of commands, and each group will get one command. Prepare system and user prompt allowing the agent to make best decisions. 
- [x] Implement sending commands to the spacecraft, based on the response from the agent. If there are no instructions from the agent, set fighters to defend stance, that should order them to stay in the current sector but to be placed between the carrier and closest threat sector. If there are no threat sectors use enemy carriers as threat source. If a spacecraft is sent to attack a sector and that sector contains a carrier they should attack it. 
- [x] Set log level to warn. Add Agent logs to see how long it takes LLM to process the request. Log user message and LLM's response
- [x] Stop sending requests to LLM when you've already lost (do not have a carrier)
- [x] Undock fighters and position them between carrier and enemies without waiting for LLM
- [x] Create unit tests to issueCommand method
- [x] Analyze history and add more tasks
- [x] Improve carrier defense logic: currently fighters stay 50 units away from carrier. Experiment with dynamic distance or multiple layers (close screen and outer patrol).
- [x] Add missile threat to BattleMap: currently missiles are ignored in threat calculation. They should contribute to HIGH threat if heading towards carrier.
- [x] Enhance AIAgent prompt: encourage LLM to prioritize target prioritization (e.g., focus fire on enemy carrier if it's within reach).
- [x] Implement per-entity cooldown management: current `issueCommand` doesn't explicitly track the 150ms cooldown per entity mentioned in AGENTS.md.
- [x] Optimize AI tick rate: currently fixed at 2000ms. Consider adaptive rate based on battle intensity or game phase.
- [ ] Implement missile evasion logic: carriers should attempt to move away from incoming missiles detected in the BattleMap.
- [ ] Fighter regrouping: add a command or logic for fighters to regroup in a specific sector if their presence falls below SMALL, to regain combat effectiveness.
