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
  
- [ ] Implement basic MOVE logic for docked entities
- [ ] Analyze history and add more tasks
- [ ] Create a battle map.  
  Add a script that will parse received game snapshot data and base on that prepare a two dimensional array with map sector status. The size of the array should be configurable, use battlejar.conf file. For now use simple 3x3 split. For each sector prepare a record with the color status. The color's record should contain boolean marker if its carrier is in that sector, and fleet presence enum, with values NONE, SMALL, SIGNIFICANT, DOMINANCE. It should also contain list of fighters' names from that sector. DOMINANCE should be used only when the color's presence number is matching SIGNIFICANT level and enemies's presence is at lower level
- [] Add threat marker to battle map data.
  For each color based on current and old state calculate the threat level in the sector. It should reflect if enemies spacecraft are targeting our carrier or our fighters (moving in our direction)
