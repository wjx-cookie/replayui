package com.replayui;

import com.mojang.blaze3d.platform.InputConstants;
import com.replayui.log.DebugLog;
import com.replayui.playback.PlaybackTicker;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * The hotkeys this mod has.
 *
 * <p>Registered through Fabric's {@link KeyBindingHelper}, which puts them into
 * Options -> Controls alongside every other binding. That is what makes them
 * configurable: Minecraft owns the binding, stores the assigned key in
 * options.txt, and lets the player rebind or unbind it without this mod keeping
 * a config file of its own.
 *
 * <p>Every key is left <em>unbound</em> by default, on purpose. Any hardcoded
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
    private static KeyMapping toggleUi = null;
    private static KeyMapping detach = null;

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
        if (toggleUi == null) {
            // Listed under Flashback's own category rather than a second one of
            // ours: this is the key that matters while editing a replay, which
            // is where the rest of Flashback's keys already live. The
            // translation key is Flashback's, so no text of ours is needed.
            toggleUi = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                    "replayui.keybind.toggleui",
                    InputConstants.Type.KEYSYM,
                    InputConstants.UNKNOWN.getValue(),
                    "flashback.keybind"));
        }
        if (detach == null) {
            // The way out of a screen the editor cannot drive. Listed next to
            // the hide key for the same reason: it belongs to editing, not to
            // the rest of this mod's settings.
            detach = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                    "replayui.keybind.detach",
                    InputConstants.Type.KEYSYM,
                    InputConstants.UNKNOWN.getValue(),
                    "flashback.keybind"));
        }
    }

    public static void onClientTick(Minecraft client) {
        // Drained rather than checked once: consumeClick() counts presses, and
        // leaving a count behind would toggle again on a later tick.
        //
        // The count is drained before the message is sent, on purpose: two
        // presses inside one tick flip the flag back to where it started, and
        // announcing after each one would claim a state that is not the current
        // one. One message, describing where the flag actually ended up.
        boolean toggled = false;
        if (toggle != null) {
            while (toggle.consumeClick()) {
                ReplayUIConfig.enabled = !ReplayUIConfig.enabled;
                toggled = true;
            }
        }

        boolean uiToggled = false;
        if (toggleUi != null) {
            while (toggleUi.consumeClick()) {
                ReplayUIConfig.showContainerUi = !ReplayUIConfig.showContainerUi;
                uiToggled = true;
            }
        }

        // detach() tells us which way it went so the message can say so. A key
        // that only ever prints "detached" leaves the editor guessing whether
        // the second press did anything.
        boolean detachedNow = false;
        boolean reattached = false;
        if (detach != null) {
            while (detach.consumeClick()) {
                if (PlaybackTicker.detach()) {
                    detachedNow = true;
                } else {
                    reattached = true;
                }
            }
        }

        if (toggled) {
            DebugLog.info("hotkey: enabled=" + ReplayUIConfig.enabled);

            // Confirm out loud. A silent toggle gives no way to tell "switched
            // off" apart from "broken".
            add(client, ReplayUIConfig.enabled
                    ? "replayui.toggle.on" : "replayui.toggle.off");
        }

        if (uiToggled) {
            DebugLog.info("hotkey: showContainerUi=" + ReplayUIConfig.showContainerUi);
            add(client, ReplayUIConfig.showContainerUi
                    ? "replayui.ui.on" : "replayui.ui.off");
        }

        if (detachedNow) {
            add(client, "replayui.detach");
        }

        if (reattached) {
            add(client, "replayui.reattach");
        }
    }

    /**
     * True for a key that must never be swallowed by a rebuilt screen.
     *
     * <p>Our own bindings are polled with {@code consumeClick()} from a client
     * tick, so they depend on the key reaching the normal keybinding handling.
     * If a screen returns true from {@code keyPressed} the keyboard handler
     * stops there, the click count is never incremented and the hotkey simply
     * does nothing - which reads as "the key is broken" rather than "a screen
     * ate it".
     */
    public static boolean isReservedKey(int keyCode, int scanCode) {
        return matches(toggle, keyCode, scanCode)
                || matches(toggleUi, keyCode, scanCode)
                || matches(detach, keyCode, scanCode);
    }

    private static boolean matches(KeyMapping mapping, int keyCode, int scanCode) {
        return mapping != null && mapping.matches(keyCode, scanCode);
    }

    private static void add(Minecraft client, String key) {
        if (client.player != null && client.gui != null) {
            client.gui.getChat().addMessage(Component.translatable(key));
        }
    }
}
