package com.replayui.compat;

import com.replayui.config.ReplayUIConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screens.Screen;

/**
 * Registers the config screen with Mod Menu.
 *
 * <p>This class is listed under the {@code modmenu} entrypoint. Fabric Loader
 * only instantiates that entrypoint when a mod actually provides it, so with
 * Mod Menu absent this class is never loaded and the missing API classes do
 * not matter. Mod Menu is a compile-only dependency; nothing is bundled.
 */
public final class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new ReplayUIConfigScreen(parent);
    }
}
