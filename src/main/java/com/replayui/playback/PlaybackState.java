package com.replayui.playback;

import com.moulberry.flashback.Flashback;
import com.replayui.codec.UiStateData;
import com.replayui.log.DebugLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.List;
import java.util.UUID;

/**
 * Viewer-side cache of the newest UI state.
 *
 * <p>Only the newest sample is kept: the data is a state, so the last one wins.
 * That is what makes seeking and fast-forwarding safe without any replay of
 * intermediate events.
 */
public final class PlaybackState {

    private static UiStateData current = null;

    /** Player whose UI is currently being shown, kept while free-flying. */
    private static UUID activeOwner = null;

    /** Whether we have ever received anything, so this is logged only once. */
    private static boolean announced = false;

    private PlaybackState() {
    }

    public static void apply(List<UiStateData> samples) {
        if (samples == null || samples.isEmpty()) {
            return;
        }
        current = samples.get(samples.size() - 1);

        // Printed once, ever. This is the fork in the road when nothing shows
        // up: if this line is missing the data never left the recording, if it
        // is present the problem is downstream (menu type, slot count, target).
        if (!announced) {
            announced = true;
            DebugLog.info("received UI data from replay (open=" + current.open()
                    + ", menu type=" + current.menuTypeId()
                    + ", slots=" + current.items().size() + ")");
        }
    }

    public static UiStateData current() {
        return current;
    }

    public static UUID activeOwner() {
        return activeOwner;
    }

    public static void clear() {
        current = null;
        activeOwner = null;
        // Do not reset announced: it exists to confirm the link works at all,
        // and re-printing it for every replay would be noise.
    }

    /**
     * True when the recorded UI belongs to whoever the viewer is watching.
     *
     * <p>While the camera is free-flying there is no spectating player, so we
     * keep the previous target instead of blanking the screen mid-shot.
     */
    public static boolean isTargetMatch(Minecraft client) {
        if (current == null) {
            return false;
        }
        AbstractClientPlayer spectating = Flashback.getSpectatingPlayer();
        if (spectating != null && spectating.getUUID().equals(current.ownerUuid())) {
            activeOwner = current.ownerUuid();
            return true;
        }

        // Not matched, or free camera where there is no spectating player at
        // all (Flashback opens replays that way).
        //
        // Returning false here would mean a replay shows no UI whatsoever -
        // indistinguishable from the mod being broken - whether the cause is
        // free camera or an owner uuid that simply does not line up with the
        // rebuilt entity. So: keep showing whoever we were showing, and adopt
        // the first owner we hear from if we have never matched anyone.
        //
        // Once a real match happens it takes over again, so switching targets
        // in a multiplayer replay still follows the camera.
        if (activeOwner == null) {
            activeOwner = current.ownerUuid();
            return true;
        }
        return activeOwner.equals(current.ownerUuid());
    }
}
