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

    /** Whether a recording was running on the previous tick. */
    private static boolean wasRecording = false;

    private UiSampler() {
    }

    /** Set once sampling has failed, so a broken probe cannot spam every tick. */
    private static boolean disabled = false;

    public static void onClientTick(Minecraft client) {
        // The switch is checked before `disabled`, on purpose.
        //
        // Sampling can be dead for the whole session - a failed probe, an
        // accessor mixin that did not apply - and that must not stop the
        // closing sample below. It is the only thing that ends a stream which
        // still says "open", and with `disabled` checked first it would be
        // skipped: the newest recorded sample keeps claiming a screen is open,
        // nothing is going to overwrite it, and playback would pin that screen
        // up for the rest of the replay.
        //
        // Writing it needs no probe either - closed() carries no screen data -
        // so it is safe to send whatever state sampling is in.
        if (!ReplayUIConfig.enabled) {
            // Hotkey switched the mod off mid-recording.
            //
            // The newest sample may still say "open", and nothing is going to
            // overwrite it because we are about to stop writing - so playback
            // would keep that screen up for the rest of the replay, long after
            // the recorded player closed it. Emitting one closing sample ends
            // the stream in a state that is actually true.
            if (isRecording(client) && lastWritten != null && lastWritten.open()) {
                LocalPlayer player = client.player;
                if (player != null) {
                    RecorderBridge.writeDelta(UiStateData.closed(player.getUUID()));
                }
            }
            // Null either way: re-enabling has to start clean, and a stale
            // lastWritten would suppress the first sample after that.
            lastWritten = null;
            return;
        }

        if (!isRecording(client)) {
            lastWritten = null;
            wasRecording = false;
            return;
        }

        // Start of a recording, detected here rather than from an outside call.
        //
        // There used to be a public reset() for exactly this and nothing ever
        // called it - a live contract on dead code. Left unset, the last sample
        // of the PREVIOUS recording survives into this one: if the player starts
        // a new recording while the same screen is still open and nothing about
        // it has moved, the first sample compares equal to that leftover and is
        // skipped, so the new file gets no opening sample at all and the screen
        // does not appear until something happens to change. Doing it on the
        // transition cannot be forgotten, because there is no caller to forget.
        if (!wasRecording) {
            wasRecording = true;
            lastWritten = null;
        }

        // Moved below the switch check - see the note at the top. It still
        // gates sampling itself: once the probe has thrown there is no point
        // asking again 20 times a second.
        if (disabled) {
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
        //
        // sameAs, not equals - see UiStateData#sameAs. The record's generated
        // equals compares ItemStack by identity, and every stack here is a fresh
        // copy, so it was always false and this never actually skipped anything.
        //
        // Note that the cursor counts as a change: hovering over slots writes a
        // sample per tick even when no item moves. That is deliberate - dropping
        // it would freeze the replayed cursor - but it means "nothing changed"
        // really means "nothing moved, including the mouse".
        if (data.sameAs(lastWritten)) {
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
}
