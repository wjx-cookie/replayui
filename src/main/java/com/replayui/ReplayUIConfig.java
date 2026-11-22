package com.replayui;

/**
 * Runtime switches.
 *
 * <p>Only genuine preferences are configurable (see {@link com.replayui.config.ConfigStore}).
 * Core behaviours - container rebuilding, the recorded player figure and pointer,
 * UI in exported video, cursor smoothing, click pinning and item flight - are
 * always on. They are never read from the config file: an old saved
 * {@code enabled=false} must not silently disable the mod after an upgrade.
 * {@link #enabled} is still flipped at runtime by the hotkey for the current session.
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
     * <p>Always starts true in code. The Mod Menu config screen can persist a
     * different value into {@code config/replayui/cursor.properties}; the
     * hotkey still only changes it for the current session.
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

    /**
     * Whether a rebuilt screen is put on screen at all, during playback only.
     *
     * <p>Deliberately <em>not</em> the same as {@link #enabled}: that one stops
     * the recording side too, because a recording taken while it was off would
     * have no UI data in it and could not be replayed. This one is playback
     * only, so hiding the screen to fly the camera around never risks losing
     * data from a recording that happens to be in progress.
     *
     * <p>This is also the difference from pressing Escape or E. Those dismiss
     * the current screen but it comes back as soon as the recording shows the
     * player opening something else; this stays off until it is turned back on.
     */
    public static boolean showContainerUi = true;

    /**
     * Draw the recorded player in a rebuilt inventory instead of the viewer.
     *
     * <p>The little figure in the inventory screen is rendered from
     * {@code Minecraft.player}, which during a replay is the viewer's own
     * spectator entity. Off leaves that untouched, for cases where the figure
     * comes out wrong and the rest of the screen is still wanted.
     */
    public static boolean showRecordedPlayer = true;

    /**
     * Draw a pointer of their own for the recorded player inside the screen.
     *
     * <p>Vanilla leaves the cursor to the operating system, so a rebuilt screen
     * shows where the recorded player was pointing only through the stack they
     * were holding - and with an empty hand, not at all. This draws an arrow
     * for them.
     *
     * <p>It is drawn through the same {@code GuiGraphics} the screen uses, so a
     * screen that is transformed - Spatial GUI's floating 3D panel most of all -
     * takes the arrow with it. Off leaves the screen exactly as it was.
     */
    public static boolean showCursorIcon = true;

    /**
     * Resource-pack location of the recorded pointer's PNG.
     *
     * <p>Default {@code replayui:textures/gui/recorded_cursor.png}. Players do
     * not need to edit this: putting a PNG at {@code config/replayui/cursor.png}
     * or overriding that path in a resource pack is enough. This is the
     * fallback used when no {@code config/replayui/cursor.properties} exists.
     */
    public static String cursorTexture = "replayui:textures/gui/recorded_cursor.png";

    /**
     * Paint a one-pixel black border around the PNG's visible pixels.
     *
     * <p>Off by default. The shipped default cursor already carries its own
     * outline inside the PNG, and a player-supplied picture must be drawn
     * exactly as authored - the mod must never add a border to somebody
     * else's cursor behind their back. Set {@code outline=true} in
     * {@code config/replayui/cursor.properties} only when you explicitly want
     * one added to your own picture.
     */
    public static boolean cursorOutline = false;

    /** Hotspot offset: the PNG's top-left corner sits at (cursorX + X, cursorY + Y). */
    public static int cursorHotspotX = 0;

    /** Hotspot offset: the PNG's top-left corner sits at (cursorX + X, cursorY + Y). */
    public static int cursorHotspotY = 0;

    /**
     * Uniform scale for the pointer picture, applied to every source including
     * a player-supplied PNG. 1.0 draws it at its authored size. The shipped
     * default is already a compact 10x16 arrow; this exists so a larger custom
     * picture can be shrunk without editing the file.
     */
    public static double cursorScale = 1.0;

    /**
     * Use the desktop pointer's picture for the recorded cursor.
     *
     * <p>On Windows this reads the {@code Arrow} value under
     * {@code HKCU\Control Panel\Cursors} and decodes the .cur / .ani file it
     * points at. Off - or unreadable, or a non-Windows desktop - falls back to
     * the shipped PNG and then the built-in bitmap.
     *
     * <p>Independent of {@link #followSystemCursorSize}: style and size are
     * deliberately two switches. A player-supplied picture makes this one
     * <em>inactive</em> (overridden) without changing its stored value, so
     * removing that picture quietly restores system following.
     */
    public static boolean followSystemCursorStyle = true;

    /**
     * Match the desktop pointer's size for the recorded cursor.
     *
     * <p>Windows exposes it as {@code CursorBaseSize} (32 by default, 32..256)
     * in the same registry key. Desktop pixels are divided by the game's GUI
     * scale first: a 32px desktop pointer at guiScale 2 is 16 GUI pixels, the
     * same height as the shipped arrow. Unreadable values leave
     * {@link #cursorScale} in charge.
     *
     * <p>Still applies when a player picture has overridden the style; the two
     * switches never gate each other.
     */
    public static boolean followSystemCursorSize = true;

    /**
     * Hard ceiling on the drawn pointer height, in GUI pixels, shrink-only.
     *
     * <p>Guards against a left-over custom PNG being several times too large.
     * When {@link #followSystemCursorSize} is active the ceiling is raised to
     * the desktop pointer's own GUI height (capped at 128), so a genuinely
     * large system cursor is still followed. 8..128.
     */
    public static int cursorMaxHeight = 16;

    /**
     * Snap the recorded cursor exactly onto a sample taken at a click, and
     * write that extra sample in the first place. Easing across a pickup or a
     * place is what makes the pointer look half a slot behind the item.
     */
    public static boolean pinOnInteraction = true;

    /**
     * Draw rebuilt containers into Flashback's exported video frames as well.
     *
     * <p>Flashback clears every screen before it captures a frame, so without
     * this a preview shows the container and the exported file does not. The
     * screen is mounted only for the duration of one GameRenderer#render call
     * and removed immediately afterwards, which keeps Flashback's export loop
     * from stalling on a screen that is still open.
     *
     * <p>Independent of {@link #showContainerUi}: that one is about the live
     * preview while flying the camera. Turning this off gives a clean export
     * without changing what the editor sees. Flashback's own "No GUI" option
     * still wins - it cancels the GUI half of render entirely.
     */
    public static boolean includeUiInExport = true;

    /**
     * Ease the recorded cursor between its once-per-tick samples.
     *
     * <p>Pure playback-side arithmetic; the recorded values are untouched.
     * Off restores the old stepping behaviour and is the first thing to try if
     * the held stack ever looks like it lags behind the pointer.
     */
    public static boolean smoothCursor = true;

    /**
     * Draw the tooltip of the item under the recorded cursor.
     *
     * <p>The tooltip is vanilla's own, produced by the rebuilt screen from the
     * recorded mouse position. It can be noisy in an edited video, so it is a
     * separate switch from the pointer and the slot highlight.
     */
    public static boolean showRecordedTooltips = true;

    /**
     * Animate items that moved between slots without passing through the
     * cursor (shift-click, hotkey swaps).
     *
     * <p>Inferred from two consecutive full slot snapshots on the playback
     * side, so it changes nothing about the recording format. Ambiguous moves
     * are deliberately not animated.
     */
    public static boolean animateItemMoves = true;

    /**
     * Rebuild creative inventories, lectern books and horse inventories.
     *
     * <p>These three are not plain MenuType containers, so they are restored
     * reflectively by class and field name. Turning this off is the clean way
     * to isolate them if one of them misbehaves: every standard container keeps
     * working exactly as before.
     */
    public static boolean showSpecialScreens = true;

    private ReplayUIConfig() {
    }

    /**
     * Load {@code config/replayui/cursor.properties} once, after the field
     * defaults above are in place but before any consumer reads a switch.
     * Keeping this here means the initializer needs no new call site.
     */
    static {
        com.replayui.config.ConfigStore.load();
    }
}
