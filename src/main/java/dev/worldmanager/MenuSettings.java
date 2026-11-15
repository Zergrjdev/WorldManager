package dev.worldmanager;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Settings of the main menu dialog (the button on the pause menu). Read in the bootstrapper, i.e. before the plugin is enabled,
 * so it reads config.yml itself and falls back to the defaults inside the jar.
 */
record MenuSettings(boolean enabled, boolean quickActions, Component buttonLabel, Component title,
                    int columns, int buttonWidth, Component exitLabel) {

    static final String ROOT = "menu";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Keys of the main menu buttons. WorldMenu handles the clicks (PlayerCustomClickEvent). */
    static final Key MY_WORLDS = Key.key("worldmanager", "menu/my_worlds");
    static final Key ALL_WORLDS = Key.key("worldmanager", "menu/all_worlds");
    static final Key CREATE = Key.key("worldmanager", "menu/create");
    static final Key LAST = Key.key("worldmanager", "menu/last");

    static MenuSettings load(Path dataDirectory) {
        YamlConfiguration defaults = new YamlConfiguration();
        try (var in = MenuSettings.class.getResourceAsStream("/config.yml")) {
            if (in != null) defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
        Path file = dataDirectory.resolve("config.yml");
        YamlConfiguration cfg = Files.isRegularFile(file) ? YamlConfiguration.loadConfiguration(file.toFile()) : new YamlConfiguration();
        cfg.setDefaults(defaults);
        return new MenuSettings(
                cfg.getBoolean(ROOT + ".enabled", true),
                cfg.getBoolean(ROOT + ".show-in-quick-actions", false),
                MM.deserialize(cfg.getString(ROOT + ".button-label", WorldMenu.GRADIENT_TITLE)),
                MM.deserialize(cfg.getString(ROOT + ".title", WorldMenu.GRADIENT_TITLE)),
                Math.max(1, cfg.getInt(ROOT + ".columns", 2)),
                Math.max(1, Math.min(1024, cfg.getInt(ROOT + ".button-width", 160))),
                MM.deserialize(cfg.getString(ROOT + ".exit-button-label", "Back to Game")));
    }

    DialogBase base() {
        return DialogBase.builder(title)
                .externalTitle(buttonLabel) // the text of the button on the pause menu
                .canCloseWithEscape(true)
                .pause(false) // a pausing dialog must use after_action close/wait, not none
                .afterAction(DialogBase.DialogAfterAction.NONE)
                .build();
    }

    DialogType type() {
        List<ActionButton> buttons = List.of(
                button("My Worlds", "Your worlds: settings, gamerules, pre-generation and delete.", MY_WORLDS),
                button("All Worlds", "Every world on the server. Click one to teleport.", ALL_WORLDS),
                button("Create World", "Make a new world of your own.", CREATE),
                button("Last World", "Go back to the world you were in before this one.", LAST));
        // A null exit action just closes the dialog (back to the pause menu).
        return DialogType.multiAction(buttons, ActionButton.create(exitLabel, null, buttonWidth, null), columns);
    }

    private ActionButton button(String label, String tooltip, Key id) {
        return ActionButton.create(Component.text(label, NamedTextColor.WHITE), Component.text(tooltip), buttonWidth,
                DialogAction.customClick(id, null));
    }
}
