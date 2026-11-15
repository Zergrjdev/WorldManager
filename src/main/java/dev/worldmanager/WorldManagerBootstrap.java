package dev.worldmanager;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.event.RegistryEvents;
import io.papermc.paper.registry.keys.DialogKeys;
import io.papermc.paper.registry.keys.tags.DialogTagKeys;
import io.papermc.paper.tag.PostFlattenTagRegistrar;

import java.util.List;

/**
 * The main world menu is a dialog. Dialogs can only be registered, and put on the pause menu, while the server is
 * bootstrapping - so this runs before WorldManager itself is enabled. Changing the menu settings needs a server restart.
 * (All the other screens of the menu are created on the fly by WorldMenu.)
 */
public final class WorldManagerBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(BootstrapContext context) {
        MenuSettings settings = MenuSettings.load(context.getDataDirectory());
        if (!settings.enabled()) return;

        TypedKey<Dialog> key = DialogKeys.create(WorldMenu.MAIN_ID);

        context.getLifecycleManager().registerEventHandler(RegistryEvents.DIALOG.compose(),
                event -> event.registry().register(key, builder -> builder.base(settings.base()).type(settings.type())));

        // The "pause_screen_additions" tag is what puts a button for the dialog on the pause (Esc) menu.
        context.getLifecycleManager().registerEventHandler(
                LifecycleEvents.TAGS.postFlatten(RegistryKey.DIALOG)
                        .newHandler((ReloadableRegistrarEvent<PostFlattenTagRegistrar<Dialog>> event) -> {
                            PostFlattenTagRegistrar<Dialog> registrar = event.registrar();
                            registrar.addToTag(DialogTagKeys.PAUSE_SCREEN_ADDITIONS, List.of(key));
                            if (settings.quickActions()) registrar.addToTag(DialogTagKeys.QUICK_ACTIONS, List.of(key));
                        }));
    }
}
