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

    /**
     * Whether the newest sample from {@link #activeOwner} said a screen was open.
     *
     * <p>This is what lets the free-camera path hand the display over to another
     * player. Without it the only thing {@link #isTargetMatch} could do in free
     * camera is compare uuids, and since {@code activeOwner} is adopted exactly
     * once, every other player in the replay was permanently locked out.
     */
    private static boolean activeOwnerOpen = false;

    /** Whether we have ever received anything, so this is logged only once. */
    private static boolean announced = false;

    private PlaybackState() {
    }

    public static void apply(List<UiStateData> samples) {
        if (samples == null || samples.isEmpty()) {
            return;
        }
        current = samples.get(samples.size() - 1);

        // Track it here rather than in isTargetMatch: this is the only place
        // that sees every sample, including the closed ones. A closed sample
        // from the adopted owner is what releases the display for someone else.
        if (activeOwner != null && activeOwner.equals(current.ownerUuid())) {
            activeOwnerOpen = current.open();
        }

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
        activeOwnerOpen = false;
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
        if (spectating != null) {
            // The camera is attached to someone, so the fallback below must not
            // run: it would adopt whoever sent the newest sample and pop their
            // inventory open while the viewer is watching somebody else.
            //
            // Only ever showing the spectated player's own UI is safe here
            // because the uuid does survive a replay - replay data stores the
            // GameProfile and ReplayGamePacketHandler decodes it when spawning,
            // so the rebuilt entity carries the uuid it was recorded with.
            // Watching the player who made the recording therefore always
            // matches, and this cannot blank the screen by accident.
            if (spectating.getUUID().equals(current.ownerUuid())) {
                activeOwner = current.ownerUuid();
                activeOwnerOpen = current.open();
                return true;
            }
            return false;
        }

        // Free camera, where there is no spectating player at all (Flashback
        // opens replays that way).
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
            activeOwnerOpen = current.open();
            return true;
        }
        if (activeOwner.equals(current.ownerUuid())) {
            return true;
        }

        // Someone else's sample, and nobody is being spectated.
        //
        // Handing the display over unconditionally would be worse than the
        // latch: in a replay where two players have containers open at the same
        // time, the newest sample alternates between them every tick and the
        // screen would flicker between two inventories.
        //
        // So the display only moves when the current owner has nothing open any
        // more - their last sample said closed, which is exactly the moment
        // "keep showing whoever we were showing" stops meaning anything.
        if (!activeOwnerOpen) {
            activeOwner = current.ownerUuid();
            activeOwnerOpen = current.open();
            return true;
        }
        return false;
    }
}
