package com.replayui;

import net.minecraft.resources.ResourceLocation;

/**
 * Central place for every id / magic number used across modules.
 *
 * Changing anything here changes the on-disk format - keep in sync with
 * {@link com.replayui.codec.UiStateCodec}.
 */
public final class ReplayUIIds {

    public static final String MOD_ID = "replayui";

    /**
     * Flashback requires unknown actions to end with "optional" or it throws
     * when a replay made with this mod is opened without it installed.
     * (ReplayReader: name.getPath().endsWith("optional"))
     */
    public static final ResourceLocation ACTION_UI_STATE =
            ResourceLocation.fromNamespaceAndPath("flashback", "action/replayui_ui_state_optional");

    /** S2C payload id used to hand the state from the replay server to viewers. */
    public static final ResourceLocation PAYLOAD_UI_STATE =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "ui_state");

    /**
     * Vanilla cycles container ids 0-99. We stay clear of that range so we can
     * never shadow a real container that exists inside the replay world.
     */
    public static final int CONTAINER_ID_BASE = 200;

    /** Format version, bumped whenever the codec layout changes. */
    public static final int FORMAT_VERSION = 1;

    private ReplayUIIds() {
    }
}
