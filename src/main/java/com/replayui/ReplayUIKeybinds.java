package com.replayui;

import com.mojang.blaze3d.platform.InputConstants;
import com.replayui.log.DebugLog;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * The one hotkey this mod has.
 *
 * <p>Registered through Fabric's {@link KeyBindingHelper}, which puts it into
 * Options -> Controls alongside every other binding. That is what makes it
 * configurable: Minecraft owns the binding, stores the assigned key in
 * options.txt, and lets the player rebind or unbind it without this mod keeping
 * a config file of its own.
 *
 * <p>The key is left <em>unbound</em> by default, on purpose. Any hardcoded
 * default risks colliding with Flashback's own keys or another mod's, and a
 * stray press that silently switches the whole thing off is indistinguishable
 * from a bug - it just looks like "the UI stopped appearing". Requiring one
 * deliberate trip to the Controls screen means the player knows the key exists.
 *
 * <p>The switch itself is deliberately not persisted: it starts enabled on
 * every launch. A sticky "off" is the kind of thing that survives a restart and
 * then costs an hour to find.
 */
public final class ReplayUIKeybinds {

    private static final String CATEGORY = "replayui.keybind.category";

    private static KeyMapping toggle = null;

    private ReplayUIKeybinds() {
    }

    /**
     * Called from client init, the only safe point for this -
     * {@link KeyBindingHelper} touches client-only registration state.
     */
    public static void register() {
        if (toggle == null) {
            toggle = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                    "replayui.keybind.toggle",
                    InputConstants.Type.KEYSYM,
                    InputConstants.UNKNOWN.getValue(),
                    CATEGORY));
        }
    }

    public static void onClientTick(Minecraft client) {
        if (toggle == null) {
            return;
        }

        // Drained rather than checked once: consumeClick() counts presses, and
        // leaving a count behind would toggle again on a later tick.
        //
        // The count is drained before the message is sent, on purpose: two
        // presses inside one tick flip the flag back to where it started, and
        // announcing after each one would claim a state that is not the current
        // one. One message, describing where the flag actually ended up.
        boolean toggled = false;
        while (toggle.consumeClick()) {
            ReplayUIConfig.enabled = !ReplayUIConfig.enabled;
            toggled = true;
        }

        if (toggled) {
            DebugLog.info("hotkey: enabled=" + ReplayUIConfig.enabled);

            // Confirm out loud. A silent toggle gives no way to tell "switched
            // off" apart from "broken".
            if (client.player != null && client.gui != null) {
                client.gui.getChat().addMessage(Component.translatable(
                        ReplayUIConfig.enabled ? "replayui.toggle.on" : "replayui.toggle.off"));
            }
        }
    }
}
