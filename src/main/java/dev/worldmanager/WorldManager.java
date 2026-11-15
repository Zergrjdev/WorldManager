package dev.worldmanager;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Biome;
import org.bukkit.command.Command;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class WorldManager extends JavaPlugin implements TabExecutor, Listener {

    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_\\-]{1,32}");
    private static final String GEN_STRUCTURES = "generate_structures";
    /** Pseudo-gamerule: the gamemode players get when they enter the world (/create gamerules <world> default_gamemode adventure). */
    private static final String DEFAULT_GAMEMODE = "default_gamemode";
    private static final List<String> GAMEMODES = List.of("survival", "creative", "adventure", "spectator");
    private static final List<String> DIMENSIONS = List.of("overworld", "nether", "end");
    private static final String PRESET_EXAMPLE = FlatGenerator.DEFAULT_LAYERS + ";minecraft:plains";

    /** The world types /create world accepts by name. A superflat preset string is accepted instead of a name. */
    enum WorldKind {
        SUPERFLAT(World.Environment.NORMAL, true, false, "plains"),
        OVERWORLD(World.Environment.NORMAL, false, false, "plains"),
        SUPERFLAT_NETHER(World.Environment.NETHER, true, false, "nether_wastes"),
        NETHER(World.Environment.NETHER, false, false, "nether_wastes"),
        SUPERFLAT_END(World.Environment.THE_END, true, false, "the_end"),
        END(World.Environment.THE_END, false, false, "the_end"),
        VOID(World.Environment.NORMAL, true, true, "plains"),
        VOID_NETHER(World.Environment.NETHER, true, true, "nether_wastes"),
        VOID_END(World.Environment.THE_END, true, true, "the_end"),
        /** Superflat built from a preset string. Its dimension, layers and biome are stored in config.yml. Not selectable by name. */
        CUSTOM(World.Environment.NORMAL, true, false, "plains");

        final World.Environment environment;
        /** Uses FlatGenerator (superflat, void and custom worlds). */
        final boolean flat;
        final boolean voidWorld;
        final String defaultBiome;

        WorldKind(World.Environment environment, boolean flat, boolean voidWorld, String defaultBiome) {
            this.environment = environment;
            this.flat = flat;
            this.voidWorld = voidWorld;
            this.defaultBiome = defaultBiome;
        }

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        static WorldKind parse(String s) {
            if (s == null) return null;
            try {
                return valueOf(s.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    private Component motd;
    /** Player -> the world they were in before their current one (kept until the server restarts). */
    private final Map<UUID, UUID> lastWorld = new ConcurrentHashMap<>();
    private final WorldMenu menu = new WorldMenu(this);

    // ------------------------------------------------------------------ startup

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateMotdFile();
        addMissingConfigKeys();
        loadMotd();
        syncBukkitYml();
        registerCommands();
        blockStopPermission();
        Bukkit.getPluginManager().registerEvents(this, this);
        // Load saved worlds once the server has finished starting.
        Bukkit.getScheduler().runTask(this, this::loadSavedWorlds);
    }

    // ------------------------------------------------------------------ commands (registered in code: paper-plugin.yml has no "commands")

    /** Ops get minecraft.command.stop by default; take that default away so no permission-based route to /stop is left either. */
    private void blockStopPermission() {
        if (!getConfig().getBoolean("block-stop-for-players", true)) return;
        Permission stop = Bukkit.getPluginManager().getPermission("minecraft.command.stop");
        if (stop != null) stop.setDefault(PermissionDefault.FALSE);
    }

    private void registerCommands() {
        if (Bukkit.getPluginManager().getPermission("worldmanager.admin") == null) {
            Bukkit.getPluginManager().addPermission(new Permission("worldmanager.admin", "Create/delete worlds and change their settings.", PermissionDefault.TRUE));
        }
        if (Bukkit.getPluginManager().getPermission("worldmanager.use") == null) {
            Bukkit.getPluginManager().addPermission(new Permission("worldmanager.use", "Use /world.", PermissionDefault.TRUE));
        }
        var map = Bukkit.getCommandMap();
        map.register("worldmanager", new WmCommand("create", "Create your own worlds, set their gamerules and default gamemodes. Console can also assign worlds.",
                "/create <world|gamerules|gamemode|biome|pre-gen|assign> ...", "worldmanager.admin"));
        map.register("worldmanager", new WmCommand("delete", "Delete a world created by this plugin.", "/delete world <name>", "worldmanager.admin"));
        map.register("worldmanager", new WmCommand("world", "Teleport to a world.", "/world <name> | <owner>/<name>", "worldmanager.use"));
        map.register("worldmanager", new WmCommand("lastworld", "Teleport back to the world you were in before this one.", "/lastworld", "worldmanager.use"));
    }

    /** Forwards a command to this plugin's onCommand / onTabComplete. */
    private final class WmCommand extends Command {
        WmCommand(String name, String description, String usage, String permission) {
            super(name, description, usage, List.of());
            setPermission(permission);
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!testPermission(sender)) return true;
            return onCommand(sender, this, label, args);
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (getPermission() != null && !sender.hasPermission(getPermission())) return List.of();
            return onTabComplete(sender, this, alias, args);
        }
    }

    /** An existing config.yml doesn't get new options by itself, so copy any that are missing. */
    private void addMissingConfigKeys() {
        var defaults = getConfig().getDefaults();
        if (defaults == null) return;
        boolean missing = defaults.getKeys(true).stream().anyMatch(k -> !getConfig().contains(k, true));
        if (missing) {
            getConfig().options().copyDefaults(true);
            saveConfig();
        }
    }

    /** Older versions kept the MOTD in motd.txt. Move it into config.yml once. */
    private void migrateMotdFile() {
        Path old = getDataFolder().toPath().resolve("motd.txt");
        if (getConfig().contains("motd", true) || Files.notExists(old)) return;
        try {
            String text = trimTrailingNewlines(Files.readString(old, StandardCharsets.UTF_8));
            getConfig().set("motd", text);
            saveConfig();
            Files.move(old, old.resolveSibling("motd.txt.migrated"), StandardCopyOption.REPLACE_EXISTING);
            getLogger().info("Moved the MOTD from motd.txt into config.yml.");
        } catch (IOException e) {
            getLogger().warning("Could not migrate motd.txt: " + e.getMessage());
        }
    }

    private void loadMotd() {
        String text = trimTrailingNewlines(getConfig().getString("motd", "A minecraft server"));
        // Legacy section-sign colour codes still work; plain text is used exactly as written.
        motd = LegacyComponentSerializer.legacySection().deserialize(text);
        Bukkit.getServer().motd(motd);
    }

    private static String trimTrailingNewlines(String text) {
        while (text.endsWith("\n") || text.endsWith("\r")) text = text.substring(0, text.length() - 1);
        return text;
    }

    // Some hosts/panels reset the MOTD after startup, so also apply it whenever the server list is pinged.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPing(ServerListPingEvent event) {
        if (motd != null) event.motd(motd);
    }

    private void loadSavedWorlds() {
        ConfigurationSection sec = getConfig().getConfigurationSection("worlds");
        if (sec == null) return;
        for (String name : sec.getKeys(false)) {
            WorldKind kind = WorldKind.parse(sec.getString(name + ".type"));
            if (kind == null || Bukkit.getWorld(sec.getString(name + ".path", name)) != null) continue;
            try {
                if (buildCreator(name, kind).createWorld() == null) getLogger().warning("Could not load world " + name);
            } catch (Exception e) {
                getLogger().severe("Could not load world " + name + ": " + e);
            }
        }
    }

    // ------------------------------------------------------------------ default dimensions (world, world_nether, world_the_end)

    /**
     * The server creates its main world and the Nether/End before any plugin can touch them, so the only way to change
     * how they generate is the "generator:" entry in bukkit.yml. This adds that entry (once) for the three dimensions.
     * The server reads bukkit.yml at startup, so the first run only prepares it; the change applies after a restart.
     */
    private void syncBukkitYml() {
        if (!getConfig().getBoolean("default-worlds.configure-bukkit-yml", true)) return;
        File file = new File("bukkit.yml");
        if (!file.isFile()) return;
        String level = levelName();
        Map<String, String> dimensionWorlds = new LinkedHashMap<>();
        dimensionWorlds.put("overworld", level);
        dimensionWorlds.put("nether", level + "_nether");
        dimensionWorlds.put("end", level + "_the_end");

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        boolean changed = false;
        for (var entry : dimensionWorlds.entrySet()) {
            String path = "worlds." + entry.getValue() + ".generator";
            String current = yml.getString(path, "");
            String ours = getName() + ":" + entry.getKey();
            boolean vanilla = getConfig().getString("default-worlds." + entry.getKey(), "void").trim().equalsIgnoreCase("vanilla");
            if (!vanilla && current.isEmpty()) {
                yml.set(path, ours);
                changed = true;
            } else if (vanilla && current.equals(ours)) {
                yml.set(path, null);
                changed = true;
            }
        }
        if (!changed) return;
        try {
            yml.save(file);
            getLogger().warning("Updated bukkit.yml for the default dimensions. RESTART the server for it to apply. "
                    + "Chunks that already exist keep their old terrain; delete the world folders first for a fully void start.");
        } catch (IOException e) {
            getLogger().warning("Could not update bukkit.yml: " + e.getMessage());
        }
    }

    private String levelName() {
        Properties props = new Properties();
        try (var in = Files.newInputStream(Path.of("server.properties"))) {
            props.load(in);
        } catch (IOException ignored) {
        }
        return props.getProperty("level-name", "world");
    }

    /** Called by the server for the worlds that bukkit.yml points at this plugin ("WorldManager:overworld" etc.). */
    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        String dimension = id != null && !id.isBlank() ? id.toLowerCase(Locale.ROOT)
                : worldName.endsWith("_nether") ? "nether"
                : worldName.endsWith("_the_end") ? "end" : "overworld";
        if (!DIMENSIONS.contains(dimension)) dimension = "overworld";
        Biome defaultBiome = switch (dimension) {
            case "nether" -> Biome.NETHER_WASTES;
            case "end" -> Biome.THE_END;
            default -> Biome.PLAINS;
        };
        String setting = getConfig().getString("default-worlds." + dimension, "void").trim();
        if (setting.equalsIgnoreCase("vanilla")) return null;
        if (!setting.isEmpty() && !setting.equalsIgnoreCase("void")) {
            try {
                FlatGenerator.Preset preset = FlatGenerator.parsePreset(setting);
                Biome biome = findBiome(preset.biome());
                return new FlatGenerator(biome != null ? biome : defaultBiome, false, preset.layers(), null);
            } catch (IllegalArgumentException e) {
                getLogger().warning("default-worlds." + dimension + " is invalid (" + e.getMessage() + "). Using void instead.");
            }
        }
        return new FlatGenerator(defaultBiome, false, List.of(), platformBlock());
    }

    // ------------------------------------------------------------------ world creation settings

    /** Builds the creator for a world from its saved settings. */
    private WorldCreator buildCreator(String name, WorldKind kind) {
        String base = "worlds." + name;
        World.Environment environment = kind.environment;
        if (kind == WorldKind.CUSTOM) {
            World.Environment saved = parseDimension(getConfig().getString(base + ".environment", "overworld"));
            if (saved != null) environment = saved;
        }
        String levelName = getConfig().getString(base + ".path", name);
        // WorldType.FLAT matters even with our custom generator: it makes the server (and the client) treat the
        // world as a real superflat world. With NORMAL the client thinks the ground is far below sea level and
        // draws the black "void" sky.
        WorldCreator creator = new WorldCreator(levelName).environment(environment)
                .type(kind.flat ? WorldType.FLAT : WorldType.NORMAL);
        if (kind.flat) {
            Biome biome = findBiome(getConfig().getString(base + ".biome", kind.defaultBiome));
            if (biome == null) biome = findBiome(kind.defaultBiome);
            boolean structures = getConfig().getBoolean(base + ".generate_structures", false);
            List<FlatGenerator.Layer> layers = layersOf(name, kind);
            creator.generator(new FlatGenerator(biome, structures, layers, kind.voidWorld ? platformBlock() : null));
            creator.generatorSettings(flatSettings(biome, layers));
        }
        return creator;
    }

    private List<FlatGenerator.Layer> layersOf(String name, WorldKind kind) {
        if (kind.voidWorld) return List.of();
        if (kind == WorldKind.CUSTOM) {
            try {
                return FlatGenerator.parseLayers(getConfig().getString("worlds." + name + ".layers", ""));
            } catch (IllegalArgumentException e) {
                getLogger().warning("World " + name + " has invalid layers in config.yml (" + e.getMessage() + "). Using the default layers.");
            }
        }
        return FlatGenerator.parseLayers(FlatGenerator.DEFAULT_LAYERS);
    }

    /** WorldType.FLAT needs real flat settings (an empty value ends up as "{}" -> "No key layers"). A void world uses one air layer. */
    private static String flatSettings(Biome biome, List<FlatGenerator.Layer> layers) {
        List<FlatGenerator.Layer> used = layers.isEmpty() ? List.of(new FlatGenerator.Layer(Material.AIR, 1)) : layers;
        StringBuilder sb = new StringBuilder("{\"biome\":\"").append(biome.getKey())
                .append("\",\"features\":false,\"lakes\":false,\"layers\":[");
        for (int i = 0; i < used.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"block\":\"").append(used.get(i).block().getKey()).append("\",\"height\":").append(used.get(i).height()).append('}');
        }
        return sb.append("]}").toString();
    }

    /** The block of the small spawn platform in void worlds, or null if the platform is turned off. */
    private Material platformBlock() {
        if (!getConfig().getBoolean("void.spawn-platform", true)) return null;
        Material block = Material.matchMaterial(getConfig().getString("void.platform-block", "minecraft:stone"));
        return block != null && block.isBlock() && !block.isAir() ? block : Material.STONE;
    }

    /**
     * Worlds are stored in plugins/WorldManager/worlds/<owner uuid>/<name> (worlds with no owner: worlds/<name>).
     * Bukkit places a world at <world container>/<level name>, so the level name is that folder written relative
     * to the world container.
     */
    private String levelPathFor(String ownerUuid, String name) {
        String fallback = ownerUuid == null ? name : ownerUuid + "_" + name.toLowerCase(Locale.ROOT);
        if (!getConfig().getBoolean("store-worlds-in-plugin-folder", true)) return fallback;
        try {
            Path container = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
            Path target = getDataFolder().toPath().toAbsolutePath().normalize().resolve("worlds");
            if (ownerUuid != null) target = target.resolve(ownerUuid);
            target = target.resolve(ownerUuid == null ? name : name.toLowerCase(Locale.ROOT));
            String relative = container.relativize(target).toString().replace('\\', '/');
            if (!relative.startsWith("..")) return relative;
        } catch (IllegalArgumentException ignored) {
        }
        getLogger().warning("The plugin folder isn't inside the server's world container, so '" + name + "' is stored in the server folder instead.");
        return fallback;
    }

    WorldKind kindOf(String name) {
        return WorldKind.parse(getConfig().getString("worlds." + name + ".type"));
    }

    private WorldKind kindOf(World world) {
        String id = idOf(world);
        return id == null ? null : kindOf(id);
    }

    boolean isFlatGenerated(World world) {
        WorldKind kind = kindOf(world);
        return kind != null && kind.flat;
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "world" -> handleWorld(sender, args);
            case "lastworld" -> handleLastWorld(sender);
            case "create" -> handleCreate(sender, args);
            case "delete" -> handleDelete(sender, args);
        }
        return true;
    }

    void handleWorld(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, "Only players can use this command.", NamedTextColor.RED);
            return;
        }
        if (args.length != 1) {
            msg(sender, "Usage: /world [WORLD NAME] (or [OWNER]/[WORLD NAME])", NamedTextColor.RED);
            return;
        }
        World world = resolveWorld(sender, args[0]);
        if (world == null) {
            msg(sender, "World '" + args[0] + "' does not exist. Someone else's world is /world <owner>/<name>.", NamedTextColor.RED);
            return;
        }
        player.teleportAsync(world.getSpawnLocation()).thenAccept(ok -> {
            if (ok) msg(player, "Teleported to " + displayName(world) + ".", NamedTextColor.GREEN);
            else msg(player, "Teleport failed.", NamedTextColor.RED);
        });
    }

    /** /lastworld - back to the world you were in before this one. Running it again swaps back. */
    void handleLastWorld(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            msg(sender, "Only players can use this command.", NamedTextColor.RED);
            return;
        }
        UUID previous = lastWorld.get(player.getUniqueId());
        if (previous == null) {
            msg(sender, "You haven't changed worlds since the server started.", NamedTextColor.RED);
            return;
        }
        World world = Bukkit.getWorld(previous);
        if (world == null) {
            msg(sender, "Your last world isn't loaded any more (it may have been deleted).", NamedTextColor.RED);
            return;
        }
        player.teleportAsync(world.getSpawnLocation()).thenAccept(ok -> {
            if (ok) msg(player, "Teleported back to " + displayName(world) + ".", NamedTextColor.GREEN);
            else msg(player, "Teleport failed.", NamedTextColor.RED);
        });
    }

    void handleCreate(CommandSender sender, String[] args) {
        if (args.length == 0) {
            msg(sender, "Usage: /create <world|gamerules|gamemode|biome|pre-gen> ...", NamedTextColor.RED);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "world" -> createWorld(sender, args);
            case "gamerules", "gamerule" -> setGamerule(sender, args);
            case "biome" -> setBiome(sender, args);
            case "pre-gen", "pregen" -> preGen(sender, args);
            case "gamemode" -> setGamemode(sender, args);
            case "assign" -> assignWorld(sender, args);
            default -> msg(sender, "Usage: /create <world|gamerules|gamemode|biome|pre-gen> ...", NamedTextColor.RED);
        }
    }

    /** /create world [NAME] [TYPE | PRESET] [DIMENSION - presets only]. The world belongs to the player who runs it. */
    private void createWorld(CommandSender sender, String[] args) {
        if (args.length < 3 || args.length > 4) {
            msg(sender, "Usage: /create world [WORLD NAME] [WORLD TYPE or SUPERFLAT PRESET] [overworld|nether|end]", NamedTextColor.RED);
            return;
        }
        String name = args[1];
        if (!NAME_PATTERN.matcher(name).matches()) {
            msg(sender, "World names may only contain letters, numbers, '_' and '-' (max 32).", NamedTextColor.RED);
            return;
        }
        String typeArg = args[2];
        WorldKind kind = WorldKind.parse(typeArg);
        if (kind == WorldKind.CUSTOM) kind = null; // "custom" isn't a type you can pick by name
        FlatGenerator.Preset preset = null;
        World.Environment presetEnvironment = null;
        Biome presetBiome = null;
        if (kind == null) {
            if (!looksLikePreset(typeArg)) {
                msg(sender, "Unknown world type. Use: " + String.join(", ", kindIds())
                        + " - or a superflat preset like " + PRESET_EXAMPLE, NamedTextColor.RED);
                return;
            }
            try {
                preset = FlatGenerator.parsePreset(typeArg);
            } catch (IllegalArgumentException e) {
                msg(sender, "Invalid preset: " + e.getMessage(), NamedTextColor.RED);
                return;
            }
            presetBiome = findBiome(preset.biome());
            if (presetBiome == null) {
                msg(sender, "Unknown biome '" + preset.biome() + "' in the preset.", NamedTextColor.RED);
                return;
            }
            presetEnvironment = args.length == 4 ? parseDimension(args[3]) : World.Environment.NORMAL;
            if (presetEnvironment == null) {
                msg(sender, "Unknown dimension '" + args[3] + "'. Use: " + String.join(", ", DIMENSIONS), NamedTextColor.RED);
                return;
            }
            kind = WorldKind.CUSTOM;
        } else if (args.length == 4) {
            msg(sender, "The dimension argument is only for superflat preset strings.", NamedTextColor.RED);
            return;
        }
        // A player owns what they create. The console creates worlds with no owner (assign them with /create assign).
        String ownerUuid = sender instanceof Player p ? p.getUniqueId().toString() : null;
        boolean clashesWithServerWorld = Bukkit.getWorlds().stream()
                .anyMatch(w -> idOf(w) == null && w.getName().equalsIgnoreCase(name));
        if (clashesWithServerWorld || nameTaken(ownerUuid, name, null)) {
            msg(sender, "You already have a world named '" + name + "'.", NamedTextColor.RED);
            return;
        }
        msg(sender, "Creating world '" + name + "' (" + (preset != null ? "custom superflat" : kind.id()) + ")... the server may lag briefly.", NamedTextColor.YELLOW);
        // Save settings first so buildCreator picks them up (flat worlds: structures off by default).
        String id = ownerUuid == null ? name : ownerUuid + "_" + name.toLowerCase(Locale.ROOT);
        String base = "worlds." + id;
        String levelPath = levelPathFor(ownerUuid, name);
        getConfig().set(base + ".name", name);
        if (ownerUuid != null) getConfig().set(base + ".owner", ownerUuid);
        getConfig().set(base + ".type", kind.name());
        getConfig().set(base + ".path", levelPath);
        if (kind == WorldKind.CUSTOM) {
            getConfig().set(base + ".environment", dimensionName(presetEnvironment));
            getConfig().set(base + ".layers", preset.layersText());
            getConfig().set(base + ".biome", presetBiome.getKey().getKey());
        } else if (kind.flat) {
            getConfig().set(base + ".biome", kind.defaultBiome);
        }
        if (kind.flat) getConfig().set(base + ".generate_structures", false);
        World world;
        try {
            world = buildCreator(id, kind).createWorld();
        } catch (Exception e) {
            getLogger().severe("World creation failed: " + e);
            world = null;
        }
        if (world == null) {
            getConfig().set(base, null);
            saveConfig();
            msg(sender, "World creation failed. Check the console. If the folder '" + levelPath
                    + "' was left behind, delete it before trying again.", NamedTextColor.RED);
            return;
        }
        saveConfig();
        msg(sender, "World '" + name + "' created" + (ownerUuid == null ? " (no owner yet - assign it with /create assign)" : "")
                + ". Use /world " + name + " to visit it.", NamedTextColor.GREEN);
    }

    /** /create assign [WORLD NAME] [PLAYER|none] - console only. Gives a world to a player, or takes the owner away. */
    private void assignWorld(CommandSender sender, String[] args) {
        if (sender instanceof Player) {
            msg(sender, "Only the server console can assign worlds.", NamedTextColor.RED);
            return;
        }
        if (args.length != 3) {
            msg(sender, "Usage: /create assign [WORLD NAME or OWNER/WORLD NAME] [PLAYER or none]", NamedTextColor.RED);
            return;
        }
        World world = resolveWorld(sender, args[1]);
        if (world == null) {
            msg(sender, "World '" + args[1] + "' does not exist (or several share that name - use OWNER/WORLD NAME).", NamedTextColor.RED);
            return;
        }
        String id = idOf(world);
        if (id == null) {
            msg(sender, "Only worlds created with /create world can be assigned.", NamedTextColor.RED);
            return;
        }
        String newOwner = null;
        if (!args[2].equalsIgnoreCase("none")) {
            OfflinePlayer target = Bukkit.getPlayerExact(args[2]);
            if (target == null) target = Bukkit.getOfflinePlayerIfCached(args[2]);
            if (target == null) {
                msg(sender, "Unknown player '" + args[2] + "'. They must have joined this server before.", NamedTextColor.RED);
                return;
            }
            newOwner = target.getUniqueId().toString();
        }
        String name = displayName(world);
        if (nameTaken(newOwner, name, id)) {
            msg(sender, "That player already has a world named '" + name + "'.", NamedTextColor.RED);
            return;
        }
        getConfig().set("worlds." + id + ".owner", newOwner);
        saveConfig();
        msg(sender, "World '" + name + "' now belongs to " + (newOwner == null ? "nobody" : ownerName(newOwner)) + ".", NamedTextColor.GREEN);
    }

    /** A preset string has a ';' or ':' or '*' or ',' in it - none of those can be in a world type name. */
    private static boolean looksLikePreset(String s) {
        return s.contains(";") || s.contains(":") || s.contains("*") || s.contains(",");
    }

    private static World.Environment parseDimension(String s) {
        if (s == null) return null;
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "overworld", "normal" -> World.Environment.NORMAL;
            case "nether" -> World.Environment.NETHER;
            case "end", "the_end" -> World.Environment.THE_END;
            default -> null;
        };
    }

    private static String dimensionName(World.Environment environment) {
        return switch (environment) {
            case NETHER -> "nether";
            case THE_END -> "end";
            default -> "overworld";
        };
    }

    void setGamerule(CommandSender sender, String[] args) {
        // /create gamerules <world> <gamerule> [value]
        if (args.length < 3 || args.length > 4) {
            msg(sender, "Usage: /create gamerules [WORLD NAME] [GAMERULE] [VALUE]", NamedTextColor.RED);
            return;
        }
        World world = managedTarget(sender, args[1]);
        if (world == null) return;
        if (args[2].equalsIgnoreCase(GEN_STRUCTURES)) {
            setGenerateStructures(sender, world, args);
            return;
        }
        if (args[2].equalsIgnoreCase(DEFAULT_GAMEMODE)) {
            if (args.length == 3) {
                String id = idOf(world);
                String current = id == null ? null : getConfig().getString("worlds." + id + ".gamemode");
                msg(sender, DEFAULT_GAMEMODE + " in " + displayName(world) + " is currently "
                        + (current == null ? "not set" : current.toLowerCase(Locale.ROOT)) + ".", NamedTextColor.YELLOW);
            } else {
                setGamemode(sender, new String[]{"gamemode", args[1], args[3]});
            }
            return;
        }
        GameRule<?> rule = findRule(args[2]);
        if (rule == null) {
            msg(sender, "Unknown gamerule '" + args[2] + "'.", NamedTextColor.RED);
            return;
        }
        String ruleName = ruleName(rule);
        if (ruleValue(world, rule) == null) {
            msg(sender, ruleName + " isn't available on this server (its feature is not enabled).", NamedTextColor.RED);
            return;
        }
        if (args.length == 3) { // no value given: just show the current one
            msg(sender, ruleName + " in " + displayName(world) + " is currently " + ruleValue(world, rule) + ".", NamedTextColor.YELLOW);
            return;
        }
        if (applyRule(world, rule, args[3])) {
            msg(sender, "Set " + ruleName + " to " + args[3].toLowerCase(Locale.ROOT) + " in " + displayName(world) + ".", NamedTextColor.GREEN);
        } else {
            msg(sender, "Invalid value for " + ruleName + " (expected " + (rule.getType() == Boolean.class ? "true/false" : "a whole number") + ").", NamedTextColor.RED);
        }
    }

    /** generate_structures is a world-generation setting, handled here like a gamerule. */
    private void setGenerateStructures(CommandSender sender, World world, String[] args) {
        if (!isFlatGenerated(world)) {
            msg(sender, "generate_structures can only be changed on superflat, void and custom worlds made with /create world.", NamedTextColor.RED);
            return;
        }
        String name = displayName(world);
        String path = "worlds." + idOf(world) + ".generate_structures";
        if (args.length == 3) {
            msg(sender, GEN_STRUCTURES + " in " + name + " is currently " + getConfig().getBoolean(path, false) + ".", NamedTextColor.YELLOW);
            return;
        }
        if (!args[3].equalsIgnoreCase("true") && !args[3].equalsIgnoreCase("false")) {
            msg(sender, "Invalid value for " + GEN_STRUCTURES + " (expected true/false).", NamedTextColor.RED);
            return;
        }
        getConfig().set(path, Boolean.parseBoolean(args[3]));
        saveConfig();
        reloadAfterChange(sender, world, GEN_STRUCTURES + " set to " + args[3].toLowerCase(Locale.ROOT));
    }

    /** /create pre-gen [WORLD NAME] [RADIUS] - drives Chunky through console commands (no compile dependency). */
    void preGen(CommandSender sender, String[] args) {
        if (args.length != 3) {
            msg(sender, "Usage: /create pre-gen [WORLD NAME] [RADIUS]", NamedTextColor.RED);
            return;
        }
        World world = managedTarget(sender, args[1]);
        if (world == null) return;
        int radius;
        try {
            radius = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            radius = -1;
        }
        if (radius < 1 || radius > 1_000_000) {
            msg(sender, "Radius must be a whole number of blocks between 1 and 1000000.", NamedTextColor.RED);
            return;
        }
        var chunky = Bukkit.getPluginManager().getPlugin("Chunky");
        if (chunky == null || !chunky.isEnabled()) {
            msg(sender, "Chunky isn't installed (or isn't enabled), so I can't pre-generate.", NamedTextColor.RED);
            return;
        }
        Location spawn = world.getSpawnLocation();
        var console = Bukkit.getConsoleSender();
        String[] commands = {
                "chunky world " + world.getName(),
                "chunky shape square",
                "chunky center " + spawn.getBlockX() + " " + spawn.getBlockZ(),
                "chunky radius " + radius,
                "chunky start"
        };
        for (String command : commands) {
            if (!Bukkit.dispatchCommand(console, command)) {
                msg(sender, "Chunky rejected '" + command + "'. Check the console for details.", NamedTextColor.RED);
                return;
            }
        }
        msg(sender, "Chunky is pre-generating a " + radius + "-block radius square around the spawn of "
                + displayName(world) + ". Use /chunky progress to check on it.", NamedTextColor.GREEN);
    }

    void setBiome(CommandSender sender, String[] args) {
        // /create biome <world> [biome]
        if (args.length < 2 || args.length > 3) {
            msg(sender, "Usage: /create biome [WORLD NAME] [BIOME]", NamedTextColor.RED);
            return;
        }
        World world = managedTarget(sender, args[1]);
        if (world == null) return;
        if (!isFlatGenerated(world)) {
            msg(sender, "/create biome only works on superflat, void and custom worlds made with /create world.", NamedTextColor.RED);
            return;
        }
        String id = idOf(world);
        String name = displayName(world);
        WorldKind kind = kindOf(id);
        if (args.length == 2) {
            msg(sender, "Biome of " + name + " is " + getConfig().getString("worlds." + id + ".biome", kind.defaultBiome) + ".", NamedTextColor.YELLOW);
            return;
        }
        Biome biome = findBiome(args[2]);
        if (biome == null) {
            msg(sender, "Unknown biome '" + args[2] + "'. Use tab completion to see the options.", NamedTextColor.RED);
            return;
        }
        getConfig().set("worlds." + id + ".biome", biome.getKey().getKey());
        saveConfig();
        reloadAfterChange(sender, world, "Biome set to " + biome.getKey().getKey());
    }

    /**
     * Generator settings can't be changed on a live world, so the world is saved, unloaded and loaded again
     * with the new settings. Players inside are moved out and put back where they were.
     * The change affects newly generated chunks only; existing chunks keep their old biome/structures.
     */
    private void reloadAfterChange(CommandSender sender, World world, String what) {
        String id = idOf(world);
        String name = displayName(world);
        WorldKind kind = kindOf(id);
        World fallback = Bukkit.getWorlds().get(0);
        Map<Player, Location> players = new LinkedHashMap<>();
        for (Player p : world.getPlayers()) players.put(p, p.getLocation());
        players.keySet().forEach(p -> p.teleport(fallback.getSpawnLocation()));
        if (!Bukkit.unloadWorld(world, true)) {
            players.forEach(Player::teleport);
            msg(sender, "Could not reload '" + name + "' to apply the change.", NamedTextColor.RED);
            return;
        }
        World reloaded = buildCreator(id, kind).createWorld();
        if (reloaded == null) {
            msg(sender, "World '" + name + "' failed to reload. Check the console.", NamedTextColor.RED);
            return;
        }
        players.forEach((p, loc) -> {
            loc.setWorld(reloaded);
            p.teleport(loc);
        });
        msg(sender, what + " for " + name + ". It applies to newly generated chunks only.", NamedTextColor.GREEN);
    }

    @SuppressWarnings("unchecked")
    boolean applyRule(World world, GameRule<?> rule, String raw) {
        Class<?> type = rule.getType();
        if (type == Boolean.class) {
            if (!raw.equalsIgnoreCase("true") && !raw.equalsIgnoreCase("false")) return false;
            return world.setGameRule((GameRule<Boolean>) rule, Boolean.parseBoolean(raw));
        } else if (type == Integer.class) {
            try {
                return world.setGameRule((GameRule<Integer>) rule, Integer.parseInt(raw));
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    void setGamemode(CommandSender sender, String[] args) {
        // /create gamemode <world> <gamemode>
        if (args.length != 3) {
            msg(sender, "Usage: /create gamemode [WORLD NAME] [GAMEMODE]", NamedTextColor.RED);
            return;
        }
        World world = managedTarget(sender, args[1]);
        if (world == null) return;
        GameMode mode;
        try {
            mode = GameMode.valueOf(args[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            msg(sender, "Unknown gamemode. Use: " + String.join(", ", GAMEMODES), NamedTextColor.RED);
            return;
        }
        String id = idOf(world);
        if (id == null) {
            msg(sender, "Only worlds created with /create world can have a default gamemode.", NamedTextColor.RED);
            return;
        }
        String name = displayName(world);
        getConfig().set("worlds." + id + ".gamemode", mode.name());
        saveConfig();
        msg(sender, "Default gamemode of " + name + " is now " + mode.name().toLowerCase(Locale.ROOT)
                + ". Visitors get it when they enter the world (you keep your own gamemode).", NamedTextColor.GREEN);
    }

    void handleDelete(CommandSender sender, String[] args) {
        if (args.length != 2 || !args[0].equalsIgnoreCase("world")) {
            msg(sender, "Usage: /delete world [WORLD NAME]", NamedTextColor.RED);
            return;
        }
        World world = managedTarget(sender, args[1]);
        if (world == null) return;
        String id = idOf(world);
        String name = displayName(world);
        if (id == null) {
            msg(sender, "Only worlds created with /create world can be deleted.", NamedTextColor.RED);
            return;
        }
        World fallback = Bukkit.getWorlds().get(0);
        for (Player p : new ArrayList<>(world.getPlayers())) {
            p.teleport(fallback.getSpawnLocation());
            msg(p, "The world you were in is being deleted.", NamedTextColor.YELLOW);
        }
        Path folder = world.getWorldFolder().toPath();
        if (!Bukkit.unloadWorld(world, false)) {
            msg(sender, "Could not unload '" + name + "'.", NamedTextColor.RED);
            return;
        }
        getConfig().set("worlds." + id, null);
        saveConfig();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try (Stream<Path> walk = Files.walk(folder)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        getLogger().warning("Could not delete " + path + ": " + e.getMessage());
                    }
                });
            } catch (IOException e) {
                getLogger().warning("Failed deleting world folder: " + e.getMessage());
            }
        });
        msg(sender, "World '" + name + "' deleted.", NamedTextColor.GREEN);
    }

    // ------------------------------------------------------------------ gamemode enforcement

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        lastWorld.put(event.getPlayer().getUniqueId(), event.getFrom().getUID());
        applyDefaultGamemode(event.getPlayer());
    }

    /** Clicks on the buttons of the main menu dialog (the one on the pause menu). */
    @EventHandler
    public void onMenuClick(PlayerCustomClickEvent event) {
        var key = event.getIdentifier();
        if (!key.namespace().equals("worldmanager") || !key.value().startsWith("menu/")) return;
        if (!(event.getCommonConnection() instanceof PlayerGameConnection connection)) return;
        Player player = connection.getPlayer();
        if (Bukkit.isPrimaryThread()) menu.handleMainClick(player, key);
        else Bukkit.getScheduler().runTask(this, () -> menu.handleMainClick(player, key));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        applyDefaultGamemode(event.getPlayer());
    }

    /** Puts a player in the world's default gamemode, if its owner set one. The owner is never changed. */
    private void applyDefaultGamemode(Player player) {
        String id = idOf(player.getWorld());
        if (id == null) return;
        if (player.getUniqueId().toString().equals(ownerOf(id))) return;
        String mode = getConfig().getString("worlds." + id + ".gamemode");
        if (mode == null) return;
        try {
            player.setGameMode(GameMode.valueOf(mode));
        } catch (IllegalArgumentException ignored) {
        }
    }

    // ------------------------------------------------------------------ /stop blocked for players

    /**
     * Stops players - including ops - from running /stop. The console (and RCON / the host panel) still can.
     * Also catches the namespaced forms (/minecraft:stop, /bukkit:stop).
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!getConfig().getBoolean("block-stop-for-players", true)) return;
        String cmd = event.getMessage().trim().toLowerCase(Locale.ROOT);
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        int space = cmd.indexOf(' ');
        if (space >= 0) cmd = cmd.substring(0, space);
        int colon = cmd.indexOf(':');
        if (colon >= 0) cmd = cmd.substring(colon + 1);
        if (!cmd.equals("stop")) return;
        event.setCancelled(true);
        msg(event.getPlayer(), "Only the server console can use /stop.", NamedTextColor.RED);
    }

    // ------------------------------------------------------------------ host ad filter

    /**
     * Cancels "tellraw" commands that contain a blocked keyword (default: "minestrator"), so the host's
     * promo broadcast does nothing. Covers commands run from the console and RCON. Commands that another
     * plugin runs internally via Bukkit.dispatchCommand don't fire this event and can't be caught here.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onServerCommand(ServerCommandEvent event) {
        if (!getConfig().getBoolean("block-host-ads", true)) return;
        String cmd = event.getCommand().trim().toLowerCase(Locale.ROOT);
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        if (!cmd.startsWith("tellraw ") && !cmd.startsWith("minecraft:tellraw ")) return;
        List<String> keywords = getConfig().contains("blocked-keywords")
                ? getConfig().getStringList("blocked-keywords")
                : List.of("minestrator");
        for (String keyword : keywords) {
            if (!keyword.isBlank() && cmd.contains(keyword.toLowerCase(Locale.ROOT))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (name.equals("lastworld")) return List.of();
        if (name.equals("world")) {
            return args.length == 1 ? filter(worldLabels(sender, false, w -> true), args[0]) : List.of();
        }
        if (name.equals("delete")) {
            if (args.length == 1) return filter(List.of("world"), args[0]);
            if (args.length == 2) return filter(worldLabels(sender, true, w -> idOf(w) != null), args[1]);
            return List.of();
        }
        // /create
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("world", "gamerules", "gamemode", "biome", "pre-gen"));
            if (!(sender instanceof Player)) subs.add("assign");
            return filter(subs, args[0]);
        }
        List<String> mine = worldLabels(sender, true, w -> true);
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "world" -> {
                if (args.length == 3) {
                    List<String> options = new ArrayList<>(kindIds());
                    options.add(PRESET_EXAMPLE);
                    return filter(options, args[2]);
                }
                if (args.length == 4 && WorldKind.parse(args[2]) == null) return filter(DIMENSIONS, args[3]);
            }
            case "assign" -> {
                if (sender instanceof Player) return List.of();
                if (args.length == 2) return filter(worldLabels(sender, true, w -> idOf(w) != null), args[1]);
                if (args.length == 3) {
                    List<String> names = new ArrayList<>(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
                    names.add("none");
                    return filter(names, args[2]);
                }
            }
            case "pre-gen", "pregen" -> {
                if (args.length == 2) return filter(mine, args[1]);
                if (args.length == 3) return filter(List.of("500", "1000", "2000", "5000", "10000"), args[2]);
            }
            case "biome" -> {
                if (args.length == 2) return filter(worldLabels(sender, true, this::isFlatGenerated), args[1]);
                if (args.length == 3) return filter(biomeNames(), args[2]);
            }
            case "gamerules", "gamerule" -> {
                if (args.length == 2) return filter(mine, args[1]);
                if (args.length == 3) {
                    List<String> names = new ArrayList<>(ruleNames());
                    names.add(DEFAULT_GAMEMODE);
                    World w = resolveWorld(sender, args[1]);
                    if (w != null && isFlatGenerated(w)) names.add(GEN_STRUCTURES);
                    return filter(names, args[2]);
                }
                if (args.length == 4) {
                    if (args[2].equalsIgnoreCase(GEN_STRUCTURES)) return filter(List.of("true", "false"), args[3]);
                    if (args[2].equalsIgnoreCase(DEFAULT_GAMEMODE)) return filter(GAMEMODES, args[3]);
                    GameRule<?> rule = findRule(args[2]);
                    if (rule != null && rule.getType() == Boolean.class) return filter(List.of("true", "false"), args[3]);
                }
            }
            case "gamemode" -> {
                if (args.length == 2) return filter(mine, args[1]);
                if (args.length == 3) return filter(GAMEMODES, args[2]);
            }
        }
        return List.of();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The config.yml key of a world made by this plugin, or null for any other world (the server's own worlds).
     * Worlds made by this plugin live in plugins/WorldManager/worlds/..., so their real Bukkit name is a long path.
     */
    String idOf(World world) {
        ConfigurationSection sec = getConfig().getConfigurationSection("worlds");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                if (sec.getString(key + ".path", key).equals(world.getName())) return key;
            }
        }
        return null;
    }

    /** The name players use for a world. */
    String displayName(World world) {
        String id = idOf(world);
        return id == null ? world.getName() : getConfig().getString("worlds." + id + ".name", id);
    }

    /** UUID string of the player who owns a world, or null if it has no owner. */
    String ownerOf(String id) {
        return id == null ? null : getConfig().getString("worlds." + id + ".owner");
    }

    private String ownerName(String uuid) {
        try {
            String name = Bukkit.getOfflinePlayer(UUID.fromString(uuid)).getName();
            return name != null ? name : uuid;
        } catch (IllegalArgumentException e) {
            return uuid;
        }
    }

    /** True if this owner (null = nobody) already has a world with this name, ignoring the world with id exceptId. */
    private boolean nameTaken(String ownerUuid, String name, String exceptId) {
        ConfigurationSection sec = getConfig().getConfigurationSection("worlds");
        if (sec == null) return false;
        for (String key : sec.getKeys(false)) {
            if (key.equals(exceptId)) continue;
            if (Objects.equals(sec.getString(key + ".owner"), ownerUuid)
                    && sec.getString(key + ".name", key).equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /**
     * Finds the world a player typed. "owner/name" is that player's world. A plain "name" is the sender's own world
     * first, then a world with no owner (or one of the server's own worlds). The console can use a plain name for any
     * world, as long as only one world has it.
     */
    World resolveWorld(CommandSender sender, String arg) {
        int slash = arg.indexOf('/');
        if (slash > 0) {
            String ownerPart = arg.substring(0, slash);
            String namePart = arg.substring(slash + 1);
            for (World w : Bukkit.getWorlds()) {
                String owner = ownerOf(idOf(w));
                if (owner == null) continue;
                if ((ownerName(owner).equalsIgnoreCase(ownerPart) || owner.equalsIgnoreCase(ownerPart))
                        && displayName(w).equalsIgnoreCase(namePart)) return w;
            }
            return null;
        }
        String self = sender instanceof Player p ? p.getUniqueId().toString() : null;
        World unowned = null;
        List<World> owned = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            if (!displayName(w).equalsIgnoreCase(arg) && !w.getName().equalsIgnoreCase(arg)) continue;
            String owner = ownerOf(idOf(w));
            if (owner == null) {
                if (unowned == null) unowned = w;
            } else if (owner.equals(self)) {
                return w;
            } else {
                owned.add(w);
            }
        }
        if (unowned != null) return unowned;
        return self == null && owned.size() == 1 ? owned.get(0) : null;
    }

    /** Finds a world for a command that changes it, and checks the sender may: only its owner, or the console. */
    World managedTarget(CommandSender sender, String arg) {
        World world = resolveWorld(sender, arg);
        if (world == null) {
            msg(sender, "World '" + arg + "' does not exist.", NamedTextColor.RED);
            return null;
        }
        if (!(sender instanceof Player player)) return world; // the console can change anything
        String owner = ownerOf(idOf(world));
        if (owner == null) {
            msg(sender, "That world has no owner, so only the server console can change it.", NamedTextColor.RED);
            return null;
        }
        if (!owner.equals(player.getUniqueId().toString())) {
            msg(sender, "You can only change worlds you own.", NamedTextColor.RED);
            return null;
        }
        return world;
    }

    /**
     * World names for tab completion. Your own worlds and ownerless ones appear by name, other players' worlds as
     * owner/name. With modifiableOnly, a player only sees their own worlds (the console sees everything).
     */
    List<String> worldLabels(CommandSender sender, boolean modifiableOnly, Predicate<World> include) {
        String self = sender instanceof Player p ? p.getUniqueId().toString() : null;
        List<String> out = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            if (!include.test(w)) continue;
            String owner = ownerOf(idOf(w));
            if (owner != null && owner.equals(self)) out.add(displayName(w));
            else if (modifiableOnly && self != null) continue;
            else if (owner == null) out.add(displayName(w));
            else out.add(ownerName(owner) + "/" + displayName(w));
        }
        return out;
    }

    private Registry<Biome> biomeRegistry() {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);
    }

    private List<String> biomeNames() {
        return biomeRegistry().stream().map(b -> b.getKey().getKey()).sorted().toList();
    }

    private Biome findBiome(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return biomeRegistry().stream()
                .filter(b -> b.getKey().getKey().equals(n) || b.getKey().toString().equals(n))
                .findFirst().orElse(null);
    }

    /** The world types you can type by name (CUSTOM is created by giving a preset string instead). */
    List<String> kindIds() {
        return Arrays.stream(WorldKind.values()).filter(k -> k != WorldKind.CUSTOM).map(WorldKind::id).toList();
    }

    // Gamerules are a registry since 26.1 (snake_case names such as "advance_time").
    private Registry<GameRule<?>> ruleRegistry() {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.GAME_RULE);
    }

    /** A gamerule's value in a world, or null if the rule can't be used there (e.g. it belongs to a disabled experimental feature). */
    Object ruleValue(World world, GameRule<?> rule) {
        try {
            return world.getGameRuleValue(rule);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The gamerules that actually work in this world. */
    List<GameRule<?>> rulesOf(World world) {
        return allRules().stream().filter(r -> ruleValue(world, r) != null).collect(java.util.stream.Collectors.toList());
    }

    /** Every gamerule, sorted by name. */
    List<GameRule<?>> allRules() {
        return ruleRegistry().stream().sorted(Comparator.comparing(this::ruleName)).collect(java.util.stream.Collectors.toList());
    }

    String ruleName(GameRule<?> rule) {
        return rule.getKey().getKey();
    }

    private List<String> ruleNames() {
        return ruleRegistry().stream().map(this::ruleName).sorted().toList();
    }

    private GameRule<?> findRule(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return ruleRegistry().stream()
                .filter(r -> ruleName(r).equals(n) || r.getKey().toString().equals(n))
                .findFirst().orElse(null);
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(p)).toList();
    }

    static void msg(CommandSender to, String text, NamedTextColor color) {
        to.sendMessage(Component.text(text, color));
    }
}
