package dev.worldmanager;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.ServerLinks;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The "server links page": a server-links dialog (1.21.6+) that gets its own button on the pause menu
 * (like the "Donut SMP" button on DonutSMP), plus the links that fill it (server links, 1.21+).
 * Everything is read from the "server-links-page" section of config.yml.
 */
final class ServerLinksPage {

    static final String ROOT = "server-links-page";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private ServerLinksPage() {
    }

    /** The settings the dialog needs. Read in the bootstrapper, i.e. before the plugin itself is enabled. */
    record Settings(boolean enabled, boolean quickActions, Component buttonLabel, Component title, List<Component> intro,
                    int columns, int buttonWidth, Component exitLabel) {

        DialogBase base() {
            DialogBase.Builder builder = DialogBase.builder(title)
                    .externalTitle(buttonLabel) // the text of the button on the pause menu
                    .canCloseWithEscape(true);
            if (!intro.isEmpty()) builder.body(intro.stream().map(DialogBody::plainMessage).toList());
            return builder.build();
        }

        DialogType type() {
            // A null action closes the dialog (back to the pause menu).
            return DialogType.serverLinks(ActionButton.create(exitLabel, null, Math.min(buttonWidth, 1024), null), columns, buttonWidth);
        }
    }

    /**
     * Loads config.yml from the plugin folder, falling back to the defaults inside the jar for anything missing
     * (the bootstrapper runs before saveDefaultConfig(), so the file may not exist yet).
     */
    static Settings loadSettings(Path dataDirectory) {
        YamlConfiguration defaults = new YamlConfiguration();
        try (var in = ServerLinksPage.class.getResourceAsStream("/config.yml")) {
            if (in != null) defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
        Path file = dataDirectory.resolve("config.yml");
        YamlConfiguration cfg = Files.isRegularFile(file) ? YamlConfiguration.loadConfiguration(file.toFile()) : new YamlConfiguration();
        cfg.setDefaults(defaults);

        List<Component> intro = new ArrayList<>();
        for (String line : cfg.getStringList(ROOT + ".intro")) intro.add(MM.deserialize(line));
        int width = clamp(cfg.getInt(ROOT + ".button-width", 200), 1, 1024);
        return new Settings(
                cfg.getBoolean(ROOT + ".enabled", true),
                cfg.getBoolean(ROOT + ".show-in-quick-actions", false),
                MM.deserialize(cfg.getString(ROOT + ".button-label", "<yellow>Server Links")),
                MM.deserialize(cfg.getString(ROOT + ".title", "<yellow><bold>Server Links")),
                intro,
                Math.max(1, cfg.getInt(ROOT + ".columns", 1)),
                width,
                MM.deserialize(cfg.getString(ROOT + ".exit-button-label", "Back to Game")));
    }

    /**
     * Adds the configured links to the server's link list (what the dialog and the vanilla "Server Links..." screen show).
     * Each entry has either "label" (MiniMessage text) or "type" (a built-in link type), plus a "url".
     *
     * @return the links that were added, so they can be removed again
     */
    static List<ServerLinks.ServerLink> applyLinks(ServerLinks target, List<Map<?, ?>> entries, Consumer<String> warn) {
        List<ServerLinks.ServerLink> added = new ArrayList<>();
        for (Map<?, ?> entry : entries) {
            Object rawUrl = entry.get("url");
            if (rawUrl == null) {
                warn.accept("A link in " + ROOT + ".links has no url - skipped.");
                continue;
            }
            URI uri;
            try {
                uri = URI.create(String.valueOf(rawUrl).trim());
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                if (!scheme.equals("http") && !scheme.equals("https")) throw new IllegalArgumentException("must start with http:// or https://");
            } catch (IllegalArgumentException e) {
                warn.accept("Invalid url '" + rawUrl + "' in " + ROOT + ".links (" + e.getMessage() + ") - skipped.");
                continue;
            }
            Object type = entry.get("type");
            Object label = entry.get("label");
            if (type != null) {
                try {
                    added.add(target.addLink(ServerLinks.Type.valueOf(String.valueOf(type).toUpperCase(Locale.ROOT)), uri));
                } catch (IllegalArgumentException e) {
                    warn.accept("Unknown link type '" + type + "' in " + ROOT + ".links. Valid: "
                            + String.join(", ", java.util.Arrays.stream(ServerLinks.Type.values()).map(Enum::name).toList()));
                }
            } else if (label != null) {
                added.add(target.addLink(MM.deserialize(String.valueOf(label)), uri));
            } else {
                warn.accept("The link " + rawUrl + " has neither a label nor a type - skipped.");
            }
        }
        return added;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
