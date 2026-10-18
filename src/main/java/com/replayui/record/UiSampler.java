package com.replayui.record;

import com.moulberry.flashback.Flashback;
import com.replayui.ReplayUIConfig;
import com.replayui.codec.UiStateData;
import com.replayui.log.DebugLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Decides *when* to sample and hands the result to {@link RecorderBridge}.
 *
 * <p>First version samples once per tick. Sub-tick sampling (matching
 * Flashback's own {@code localPlayerUpdatesPerSecond}) is a later refinement;
 * it only matters for smooth cursor motion, not for correctness.
 */
public final class UiSampler {

    private static UiStateData lastWritten = null;

    private UiSampler() {
    }

    /** Set once sampling has failed, so a broken probe cannot spam every tick. */
    private static boolean disabled = false;

    public static void onClientTick(Minecraft client) {
        if (disabled) {
            return;
        }

        if (!isRecording(client)) {
            lastWritten = null;
            return;
        }

        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }

        UiStateData data;
        try {
            data = UiStateReader.capture(client, player.getUUID());
        } catch (Throwable t) {
            // This runs every tick while a recording is active. An exception
            // here - a missing mixin accessor, an odd mod screen - would
            // otherwise fire 20 times a second and take the recording with it.
            // Give up on sampling instead; the rest of the mod keeps working.
            disabled = true;
            DebugLog.warn("UI sampling failed, recording UI state is disabled for this session", t);
            return;
        }

        // Cursor recording is opt-out. Zeroing here rather than in the reader
        // keeps the reader a pure "what is on screen" probe.
        if (!ReplayUIConfig.captureMouse && (data.mouseX() != 0 || data.mouseY() != 0)) {
            data = new UiStateData(data.ownerUuid(), data.open(), data.menuTypeId(),
                    data.title(), data.items(), data.carried(),
                    data.containerId(), 0.0, 0.0, data.blockPos());
        }

        // State model: skip the write when nothing changed. Keeps files small
        // and makes the stream cheap to scan.
        if (data.equals(lastWritten)) {
            return;
        }
        lastWritten = data;

        DebugLog.verbose("sample open=" + data.open() + " menu=" + data.menuTypeId()
                + " slots=" + data.items().size());
        RecorderBridge.writeDelta(data);
    }

    private static boolean isRecording(Minecraft client) {
        if (client.level == null || Flashback.RECORDER == null) {
            return false;
        }
        // Never record while inside a replay: client.screen would be the
        // synthetic screen PlaybackTicker just opened, and we would write our
        // own output back into the file.
        return !Flashback.isInReplay();
    }

    /** Called when a recording stops, so the next one starts from a clean slate. */
    public static void reset() {
        lastWritten = null;
        // Not disabled: a failure is a permanent property of this session's
        // class layout, not of the recording that was running.
    }
}
