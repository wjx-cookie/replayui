package com.replayui.playback;

import com.moulberry.flashback.Flashback;
import com.replayui.codec.UiStateData;
import com.replayui.log.DebugLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Viewer-side cache of UI state, keyed by the player it belongs to.
 *
 * <p>A tick can carry samples from several players. Keeping only the last one
 * is wrong: in a two-player replay the samples alternate, so attaching the
 * camera to A shows nothing whenever B happens to be the final sample that
 * tick, and a free camera flickers between two inventories. Each owner gets
 * its own bucket instead; a sample only ever replaces that owner's own state.
 *
 * <p>The data is still a state, not an event: the newest sample per owner is
 * enough, so seeking and fast-forwarding need no replay of intermediate ticks.
 */
public final class PlaybackState {

    /** Stand-in for a missing owner uuid; ConcurrentHashMap rejects null keys. */
    private static final UUID NO_OWNER = new UUID(0L, 0L);

    /** Newest sample for each recorded player. */
    private static final Map<UUID, UiStateData> latestByOwner = new ConcurrentHashMap<>();

    /** Tick stamp per owner, used to pick the most recently updated one. */
    private static final Map<UUID, Long> updatedAt = new ConcurrentHashMap<>();
    private static final AtomicLong sequence = new AtomicLong();

    /** Sample currently being displayed; kept for {@link #current()}. */
    private static UiStateData current = null;

    /** Player whose UI is currently being shown, kept while free-flying. */
    private static UUID activeOwner = null;

    /**
     * Selection is cached within one tick. Both {@link #current()} and
     * {@link #isTargetMatch(Minecraft)} ask for it, and selecting has side
     * effects (adopting / handing over an owner). Doing it twice per tick
     * would let the second call see a different answer than the first, which
     * is exactly the disagreement that makes a screen vanish for one frame.
     */
    private static boolean selectionComputed = false;
    private static long selectionAtStamp = -1L;
    private static UUID selectionCamera = null;

    /** Whether we have ever received anything, so this is logged only once. */
    private static boolean announced = false;

    private PlaybackState() {
    }

    private static UUID key(UUID uuid) {
        return uuid == null ? NO_OWNER : uuid;
    }

    /**
     * Merges one tick of samples into the per-owner cache.
     *
     * <p>Deliberately does not clear the other buckets: a tick that only
     * contains B must not erase A's open container. {@link #select()} decides
     * which bucket is on screen, and only that one is ever shown.
     */
    public static void apply(List<UiStateData> samples) {
        if (samples == null || samples.isEmpty()) {
            return;
        }
        long stamp = sequence.incrementAndGet();
        UiStateData last = null;
        for (UiStateData sample : samples) {
            if (sample == null) {
                continue;
            }
            UUID bucket = key(sample.ownerUuid());
            latestByOwner.put(bucket, sample);
            updatedAt.put(bucket, stamp);
            last = sample;
        }
        if (last == null) {
            return;
        }
        DebugLog.verbose("ui tick: " + samples.size() + " samples, "
                + latestByOwner.size() + " owners cached");

        // Printed once, ever. This is the fork in the road when nothing shows
        // up: if this line is missing the data never left the recording, if it
        // is present the problem is downstream (menu type, slot count, target).
        if (!announced) {
            announced = true;
            int slots = last.items() == null ? 0 : last.items().size();
            DebugLog.info("received UI data from replay (open=" + last.open()
                    + ", menu type=" + last.menuTypeId()
                    + ", slots=" + slots + ")");
        }
    }

    public static UiStateData current() {
        return select();
    }

    public static UUID activeOwner() {
        return activeOwner;
    }

    /**
     * Picks the sample that belongs to whoever the viewer is watching.
     *
     * <p>Both {@link #current()} and {@link #isTargetMatch(Minecraft)} go
     * through here so they can never disagree about which owner is on screen.
     */
    private static UiStateData select() {
        AbstractClientPlayer spectating = Flashback.getSpectatingPlayer();
        UUID camera = spectating == null ? null : spectating.getUUID();
        long stamp = sequence.get();
        boolean sameCamera = camera == null
                ? selectionCamera == null
                : camera.equals(selectionCamera);
        if (selectionComputed && selectionAtStamp == stamp && sameCamera) {
            return current;
        }
        current = computeSelection(camera);
        selectionComputed = true;
        selectionAtStamp = stamp;
        selectionCamera = camera;
        return current;
    }

    private static UiStateData computeSelection(UUID camera) {
        if (camera != null) {
            // The camera is attached to someone, so only that player's bucket
            // may be shown. There is no fallback: adopting the newest sample
            // here would pop open a stranger's inventory mid-shot. The uuid
            // survives a replay (Flashback decodes the GameProfile when
            // spawning), so the rebuilt entity carries the recorded uuid.
            UiStateData sample = latestByOwner.get(key(camera));
            if (sample != null) {
                activeOwner = camera;
            }
            return sample;
        }

        // Free camera, where there is no spectating player at all (Flashback
        // opens replays that way). Returning nothing here would make a replay
        // show no UI whatsoever - indistinguishable from the mod being broken.
        if (activeOwner == null) {
            // Prefer whoever actually has a container open. Adopting a closed
            // bucket just because it was the final sample of the tick would
            // hide a second player who is still holding a chest open.
            UiStateData first = newestOpenOtherThan(null);
            if (first == null) {
                first = newestSample();
            }
            if (first != null) {
                activeOwner = first.ownerUuid();
            }
            return first;
        }

        UUID ownKey = key(activeOwner);
        UiStateData own = latestByOwner.get(ownKey);
        // Read the adopted owner's own bucket, not the last sample of the tick:
        // someone else closing a container must not release our latch.
        if (own != null && own.open()) {
            return own;
        }

        // Hand the display over only when the adopted owner has nothing open
        // any more, to whoever opened something most recently. Unconditional
        // hand-over is what made two simultaneous containers flicker.
        UiStateData next = newestOpenOtherThan(ownKey);
        if (next != null) {
            activeOwner = next.ownerUuid();
            return next;
        }
        return own;
    }

    private static UiStateData newestSample() {
        Map.Entry<UUID, UiStateData> best = null;
        long bestStamp = Long.MIN_VALUE;
        for (Map.Entry<UUID, UiStateData> entry : latestByOwner.entrySet()) {
            long stamp = stampOf(entry.getKey());
            if (stamp >= bestStamp) {
                bestStamp = stamp;
                best = entry;
            }
        }
        return best == null ? null : best.getValue();
    }

    private static UiStateData newestOpenOtherThan(UUID excluded) {
        Map.Entry<UUID, UiStateData> best = null;
        long bestStamp = Long.MIN_VALUE;
        for (Map.Entry<UUID, UiStateData> entry : latestByOwner.entrySet()) {
            if (excluded != null && entry.getKey().equals(excluded)) {
                continue;
            }
            UiStateData sample = entry.getValue();
            if (sample == null || !sample.open()) {
                continue;
            }
            long stamp = stampOf(entry.getKey());
            if (stamp >= bestStamp) {
                bestStamp = stamp;
                best = entry;
            }
        }
        return best == null ? null : best.getValue();
    }

    private static long stampOf(UUID bucket) {
        Long stamp = updatedAt.get(bucket);
        return stamp == null ? Long.MIN_VALUE : stamp;
    }

    public static boolean isTargetMatch(Minecraft client) {
        return select() != null;
    }

    public static void clear() {
        current = null;
        activeOwner = null;
        latestByOwner.clear();
        updatedAt.clear();
        selectionComputed = false;
        selectionAtStamp = -1L;
        selectionCamera = null;
        // Do not reset announced: it exists to confirm the link works at all,
        // and re-printing it for every replay would be noise.
    }
}
