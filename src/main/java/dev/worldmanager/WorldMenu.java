package dev.worldmanager;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput.OptionEntry;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The dialog screens behind the pause-menu button. The main menu is registered at bootstrap (see MenuSettings); every other
 * screen is built when it is opened, so it always shows current values. Every action goes through the same command handlers
 * as /create, /delete and /world, so ownership checks and messages are identical.
 */
final class WorldMenu {

    static final Key MAIN_ID = Key.key("worldmanager", "menu");
    /** Light blue -> dark purple-blue. */
    static final String GRADIENT_TITLE = "<gradient:#8fd8ff:#4b2fb8><bold>World Manage</bold></gradient>";
    private static final String GRADIENT_OPEN = "<gradient:#8fd8ff:#4b2fb8><bold>";
    private static final String GRADIENT_CLOSE = "</bold></gradient>";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Number gamerules that get a slider instead of a text box: name -> {min, max}. Other number rules get a text box. */
    private static final Map<String, int[]> SLIDERS = Map.of(
            "random_tick_speed", new int[]{0, 100},
            "players_sleeping_percentage", new int[]{0, 100},
            "spawn_radius", new int[]{0, 128},
            "max_entity_cramming", new int[]{0, 100});

    private final WorldManager wm;

    WorldMenu(WorldManager wm) {
        this.wm = wm;
    }

    // ------------------------------------------------------------------ main menu buttons

    void handleMainClick(Player p, Key id) {
        if (!p.hasPermission("worldmanager.use")) return;
        guarded(p, () -> {
            if (id.equals(MenuSettings.MY_WORLDS)) myWorlds(p);
            else if (id.equals(MenuSettings.ALL_WORLDS)) allWorlds(p);
            else if (id.equals(MenuSettings.CREATE)) create(p);
            else if (id.equals(MenuSettings.LAST)) {
                p.closeDialog();
                wm.handleLastWorld(p);
            }
        });
    }

    private void showMain(Player p) {
        Dialog main = RegistryAccess.registryAccess().getRegistry(RegistryKey.DIALOG).get(MAIN_ID);
        if (main == null) p.closeDialog();
        else p.showDialog(main);
    }

    // ------------------------------------------------------------------ all worlds (teleport)

    private void allWorlds(Player p) {
        List<String> labels = wm.worldLabels(p, false, w -> true);
        List<ActionButton> buttons = new ArrayList<>();
        for (String label : labels) {
            buttons.add(button(label, "Teleport to " + label, 150, run(p, () -> {
                p.closeDialog();
                wm.handleWorld(p, new String[]{label});
            })));
        }
        p.showDialog(dialog("All Worlds", body(buttons.isEmpty() ? "There are no worlds to visit." : "Click a world to teleport to it."),
                List.of(), DialogType.multiAction(buttons, backButton(p, () -> showMain(p)), 2)));
    }

    // ------------------------------------------------------------------ my worlds

    private void myWorlds(Player p) {
        String me = p.getUniqueId().toString();
        List<ActionButton> buttons = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            String id = wm.idOf(w);
            if (id == null || !me.equals(wm.ownerOf(id))) continue;
            String name = wm.displayName(w);
            buttons.add(button(name, "Open the settings of " + name, 150, run(p, () -> settings(p, name))));
        }
        boolean unowned = Bukkit.getWorlds().stream().anyMatch(w -> wm.idOf(w) != null && wm.ownerOf(wm.idOf(w)) == null);
        String hint = buttons.isEmpty()
                ? "You don't own any worlds yet. Use \"Create World\"." + (unowned ? " Some worlds have no owner - the server console can give you one with /create assign." : "")
                : "Click a world to open its settings.";
        p.showDialog(dialog("My Worlds", body(hint),
                List.of(), DialogType.multiAction(buttons, backButton(p, () -> showMain(p)), 2)));
    }

    // ------------------------------------------------------------------ create

    private void create(Player p) {
        List<OptionEntry> types = new ArrayList<>();
        for (String id : wm.kindIds()) types.add(OptionEntry.create(id, Component.text(id), id.equals("void")));
        List<OptionEntry> dims = List.of(
                OptionEntry.create("overworld", Component.text("overworld"), true),
                OptionEntry.create("nether", Component.text("nether"), false),
                OptionEntry.create("end", Component.text("end"), false));
        List<DialogInput> inputs = List.of(
                DialogInput.text("name", Component.text("World name")).width(250).maxLength(32).build(),
                DialogInput.singleOption("type", Component.text("World type"), types).width(250).build(),
                DialogInput.text("preset", Component.text("Superflat preset (optional, replaces the type)")).width(250).maxLength(256).build(),
                DialogInput.singleOption("dimension", Component.text("Preset dimension"), dims).width(250).build());
        ActionButton yes = button("Create", "Create the world.", 120, run(p, view -> {
            String name = text(view, "name");
            String preset = text(view, "preset");
            p.closeDialog();
            if (name.isEmpty()) {
                WorldManager.msg(p, "Type a name for the world.", NamedTextColor.RED);
                return;
            }
            String[] args = preset.isEmpty()
                    ? new String[]{"world", name, text(view, "type")}
                    : new String[]{"world", name, preset, text(view, "dimension")};
            wm.handleCreate(p, args);
        }));
        p.showDialog(dialog("Create World", body("Name it, pick a type - or paste a superflat preset."), inputs,
                DialogType.confirmation(yes, backButton(p, () -> showMain(p)))));
    }

    // ------------------------------------------------------------------ settings of one world (everything in one screen)

    private void settings(Player p, String name) {
        World w = wm.managedTarget(p, name);
        if (w == null) return;
        String id = wm.idOf(w);
        boolean flat = wm.isFlatGenerated(w);
        List<DialogInput> inputs = new ArrayList<>();

        // Default gamemode for visitors
        List<OptionEntry> modes = new ArrayList<>();
        modes.add(OptionEntry.create("keep", Component.text("keep as it is"), true));
        for (String mode : List.of("survival", "creative", "adventure", "spectator")) {
            modes.add(OptionEntry.create(mode, Component.text(mode), false));
        }
        String currentMode = id == null ? null : wm.getConfig().getString("worlds." + id + ".gamemode");
        inputs.add(DialogInput.singleOption("gamemode", Component.text("Default gamemode for visitors (now: "
                + (currentMode == null ? "not set" : currentMode.toLowerCase(Locale.ROOT)) + ")"), modes).width(300).build());

        // Biome + structures (superflat / void / custom worlds)
        String oldBiome = "";
        boolean oldStructures = false;
        if (flat && id != null) {
            WorldManager.WorldKind kind = wm.kindOf(id);
            oldBiome = wm.getConfig().getString("worlds." + id + ".biome", kind == null ? "plains" : kind.defaultBiome);
            oldStructures = wm.getConfig().getBoolean("worlds." + id + ".generate_structures", false);
            inputs.add(DialogInput.text("biome", Component.text("Biome (new chunks only)")).initial(oldBiome).maxLength(64).width(300).build());
            inputs.add(DialogInput.bool("structures", Component.text("Generate structures (new chunks only)")).initial(oldStructures).build());
        }

        // Pre-generate radius (used by the Pre-generate button)
        inputs.add(DialogInput.numberRange("radius", Component.text("Pre-generate radius (blocks)"), 100f, 10000f)
                .step(100f).initial(1000f).width(300).build());

        // Number gamerules
        for (GameRule<?> rule : wm.rulesOf(w)) {
            if (rule.getType() != Integer.class) continue;
            String key = wm.ruleName(rule);
            String inputKey = inputKey(key);
            int current = w.getGameRuleValue(rule) instanceof Integer i ? i : 0;
            int[] range = SLIDERS.get(key);
            if (range != null) {
                inputs.add(DialogInput.numberRange(inputKey, Component.text(key), range[0], range[1])
                        .step(1f).initial((float) Math.max(range[0], Math.min(range[1], current))).width(300).build());
            } else {
                inputs.add(DialogInput.text(inputKey, Component.text(key)).initial(String.valueOf(current)).maxLength(16).width(300).build());
            }
        }

        // On/off gamerules
        for (GameRule<?> rule : wm.rulesOf(w)) {
            if (rule.getType() != Boolean.class) continue;
            inputs.add(DialogInput.bool(inputKey(wm.ruleName(rule)), Component.text(wm.ruleName(rule)))
                    .initial(Boolean.TRUE.equals(w.getGameRuleValue(rule))).build());
        }

        final String biomeBefore = oldBiome;
        final boolean structuresBefore = oldStructures;
        List<ActionButton> buttons = List.of(
                button("Apply", "Save every setting on this screen.", 150,
                        run(p, view -> apply(p, name, flat, biomeBefore, structuresBefore, view))),
                button("Teleport here", "Go to this world.", 150, run(p, () -> {
                    p.closeDialog();
                    wm.handleWorld(p, new String[]{name});
                })),
                button("Pre-generate", "Generate chunks around spawn with the radius above (needs Chunky).", 150, run(p, view -> {
                    Float r = view.getFloat("radius");
                    p.closeDialog();
                    wm.preGen(p, new String[]{"pre-gen", name, String.valueOf(r == null ? 1000 : Math.round(r))});
                })),
                button("Delete world", "Permanently delete this world.", 150, run(p, () -> confirmDelete(p, name))));
        p.showDialog(dialog("Settings: " + name,
                body("Change what you want, then press Apply. Order: gamemode, biome, structures, pre-generate radius, number gamerules, on/off gamerules."),
                inputs, DialogType.multiAction(buttons, backButton(p, () -> myWorlds(p)), 2)));
    }

    /** Applies every changed value of the settings screen: gamerules first, then gamemode, then (world-reloading) biome/structures. */
    private void apply(Player p, String name, boolean flat, String biomeBefore, boolean structuresBefore, DialogResponseView view) {
        World target = wm.managedTarget(p, name);
        if (target == null) return;
        int changed = 0;
        List<String> invalid = new ArrayList<>();
        for (GameRule<?> rule : wm.rulesOf(target)) {
            String key = wm.ruleName(rule);
            if (rule.getType() == Boolean.class) {
                Boolean now = view.getBoolean(inputKey(key));
                if (now != null && !now.equals(target.getGameRuleValue(rule)) && wm.applyRule(target, rule, now.toString())) changed++;
            } else if (rule.getType() == Integer.class) {
                String value;
                if (SLIDERS.containsKey(key)) {
                    Float f = view.getFloat(inputKey(key));
                    value = f == null ? null : String.valueOf(Math.round(f));
                } else {
                    String raw = view.getText(inputKey(key));
                    value = raw == null ? null : raw.trim();
                }
                if (value == null || value.isEmpty() || value.equals(String.valueOf(target.getGameRuleValue(rule)))) continue;
                if (wm.applyRule(target, rule, value)) changed++;
                else invalid.add(key);
            }
        }
        if (changed > 0) WorldManager.msg(p, "Updated " + changed + " gamerule(s) in " + name + ".", NamedTextColor.GREEN);
        if (!invalid.isEmpty()) WorldManager.msg(p, "Not a valid whole number for: " + String.join(", ", invalid), NamedTextColor.RED);

        String mode = text(view, "gamemode");
        if (!mode.isEmpty() && !mode.equals("keep")) wm.setGamemode(p, new String[]{"gamemode", name, mode});
        if (flat) {
            String newBiome = text(view, "biome");
            if (!newBiome.isEmpty() && !newBiome.equals(biomeBefore)) wm.setBiome(p, new String[]{"biome", name, newBiome});
            Boolean s = view.getBoolean("structures");
            if (s != null && s != structuresBefore) wm.setGamerule(p, new String[]{"gamerules", name, "generate_structures", s.toString()});
        }
        settings(p, name); // show the screen again with the new values
    }

    private void confirmDelete(Player p, String name) {
        ActionButton yes = button("Delete it", "This cannot be undone.", 140, run(p, () -> {
            p.closeDialog();
            wm.handleDelete(p, new String[]{"world", name});
        }));
        p.showDialog(dialog("Delete " + name + "?", body("The world and everything in it will be gone for good."), List.of(),
                DialogType.confirmation(yes, backButton(p, () -> settings(p, name)))));
    }

    // ------------------------------------------------------------------ helpers

    /** Input names must be plain identifiers and unique: gamerule inputs are "gr_" + the gamerule name with odd characters replaced. */
    private static String inputKey(String ruleName) {
        return "gr_" + ruleName.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static String text(DialogResponseView view, String key) {
        String s = view.getText(key);
        return s == null ? "" : s.trim();
    }

    private static List<DialogBody> body(String message) {
        return List.of(DialogBody.plainMessage(Component.text(message, NamedTextColor.GRAY)));
    }

    private static Dialog dialog(String title, List<DialogBody> body, List<DialogInput> inputs, DialogType type) {
        Component gradientTitle = MM.deserialize(GRADIENT_OPEN + MM.escapeTags(title) + GRADIENT_CLOSE);
        DialogBase base = DialogBase.builder(gradientTitle)
                .body(body).inputs(inputs).canCloseWithEscape(true)
                .pause(false) // a pausing dialog must use after_action close/wait, not none
                .afterAction(DialogBase.DialogAfterAction.NONE) // stay open so the next screen can replace it
                .build();
        return Dialog.create(builder -> builder.empty().base(base).type(type));
    }

    private static ActionButton button(String label, String tooltip, int width, DialogAction action) {
        return ActionButton.create(Component.text(label), Component.text(tooltip), width, action);
    }

    private ActionButton backButton(Player p, Runnable back) {
        return button("Back", "Go back.", 120, run(p, back));
    }

    /** A button action that runs on the main server thread. */
    private DialogAction run(Player p, Runnable task) {
        return DialogAction.customClick((view, audience) -> sync(p, task), ClickCallback.Options.builder().build());
    }

    /** Same, for dialogs with inputs: the task gets what the player typed. */
    private DialogAction run(Player p, Consumer<DialogResponseView> task) {
        return DialogAction.customClick((view, audience) -> sync(p, () -> task.accept(view)), ClickCallback.Options.builder().build());
    }

    private void sync(Player p, Runnable task) {
        if (Bukkit.isPrimaryThread()) guarded(p, task);
        else Bukkit.getScheduler().runTask(wm, () -> guarded(p, task));
    }

    /** Runs a menu action; if it throws, the error goes to the console and to the player instead of the menu silently doing nothing. */
    void guarded(Player p, Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            wm.getLogger().log(java.util.logging.Level.SEVERE, "World menu error", t);
            WorldManager.msg(p, "The menu hit an error: " + t + " (details are in the server console).", NamedTextColor.RED);
        }
    }
}
