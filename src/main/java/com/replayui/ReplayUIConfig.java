package com.replayui;

/**
 * Runtime switches.
 *
 * <p>Kept deliberately small: each flag maps to one behaviour that can be turned
 * off on its own, so a problem can be isolated without ripping the mod apart.
 */
public final class ReplayUIConfig {

    /**
     * Master switch, flipped by the hotkey in {@link ReplayUIKeybinds}.
     *
     * <p>Off means both halves stop: nothing is written into a recording, and
     * nothing is rebuilt during playback. Both sides check this rather than one
     * central gate, so a recording made while it was off stays consistent with
     * what playback expects.
     *
     * <p>Always starts true - see the note on {@link ReplayUIKeybinds} about why
     * the value is not persisted.
     */
    public static boolean enabled = true;

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

    /**
     * Record and replay cursor position.
     *
     * <p>Plain 2D screens need nothing else installed: the coordinates go back
     * into vanilla's MouseHandler, which is where the renderer gets the
     * {@code mouseX / mouseY} it draws the cursor-held stack and hover with.
     * Spatial GUI additionally maps them onto its 3D panel, which is what its
     * own hover and tooltips read.
     */
    public static boolean captureMouse = true;

    private ReplayUIConfig() {
    }
}
