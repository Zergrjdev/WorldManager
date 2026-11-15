WorldManager - build instructions
  1. Install JDK 25 (e.g. from adoptium.net) - nothing else is needed, the Gradle wrapper is included.
  2. Open a terminal in this folder and run:
       Windows:     gradlew.bat build
       Mac/Linux:   ./gradlew build
  3. Copy build/libs/WorldManager-1.0.0.jar into your server's plugins/ folder.
If 26.3.build.+ doesn't resolve, set the exact version from papermc.io/downloads in build.gradle.kts.

Usage (no op needed). Every player has their own worlds; only the owner (or the console) can change or delete one:
  /create world <name> <type>                       types: superflat, overworld, nether, end, superflat_nether,
                                                    superflat_end, void, void_nether, void_end
  /create world <name> <preset> [overworld|nether|end]
        e.g. /create world flat1 minecraft:bedrock,2*minecraft:dirt,minecraft:grass_block;minecraft:plains
  /delete world <name>      /create gamerules|gamemode|biome|pre-gen ...
  /create gamerules <world> default_gamemode <mode>   (same as /create gamemode: visitors get that mode, e.g. adventure)
  /world <name>             your own world (or one with no owner)
  /world <owner>/<name>     visit someone else's world
  /lastworld                back to the world you were in before this one (run it again to swap back)
  Console only: /create assign <world|owner/world> <player|none>   (give a world to a player)

Everything is configured in plugins/WorldManager/config.yml. New worlds are stored in plugins/WorldManager/worlds/.
First start with the void default dimensions: the plugin edits bukkit.yml, then restart once (delete the world folders
first if you want the existing world, nether and end replaced).

World menu (button on the Esc menu, 1.21.6+ clients):
  Main menu: My Worlds, All Worlds, Create World, Last World.
  My Worlds -> click a world -> one settings screen with default gamemode, biome, structures, number gamerules (boxes/sliders),
  on/off gamerules (tick boxes), pre-generate (Chunky), teleport and delete. All Worlds -> click a world to teleport.
  It uses the same rules as the commands (only owners can change a world).
  Configure the button/title in the "menu" section of config.yml. It is registered while the server is bootstrapping,
  so RESTART the server after editing that section.
  The plugin uses paper-plugin.yml (a bootstrapper is required for dialogs); commands are registered in code.

/stop: with block-stop-for-players: true (default) players, including ops, can't run /stop. Only the console can.
