package com.replayui;

/**
 * Runtime switches.
 *
 * <p>Kept deliberately small: each flag maps to one behaviour that can be turned
 * off on its own, so a problem can be isolated without ripping the mod apart.
 */
public final class ReplayUIConfig {

    /**
     * Aligns the viewer entity's yaw with the recorded player.
     *
     * <p>Spatial GUI anchors its floating panel to {@code Minecraft.player}'s
     * yaw. In a replay that entity is the viewer, not the recorded player, so
     * without this the panel ends up stuck to the camera.
     *
     * <p>Off by default: the visual result still needs a real session to
     * confirm, and the rest of the mod does not depend on it.
     */
    public static boolean syncYaw = false;

    /** Record and replay cursor position. Requires Spatial GUI for the 3D mapping. */
    public static boolean captureMouse = true;

    private ReplayUIConfig() {
    }
}
