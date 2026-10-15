package com.replayui.action;

import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.playback.ReplayPlayer;
import com.replayui.ReplayUIIds;
import com.replayui.codec.UiStateCodec;
import com.replayui.codec.UiStateData;
import com.replayui.log.DebugLog;
import com.replayui.net.UiStatePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Carries UI samples inside the replay file.
 *
 * <p>Three things about Flashback's action contract:
 * <ol>
 *   <li>{@code handle} runs on the <em>replay server</em>, so nothing reaches a
 *       viewer unless we send it ourselves.</li>
 *   <li>The name MUST end with "optional", otherwise opening a replay made with
 *       this mod crashes when the mod is absent.</li>
 *   <li>The buffer must be consumed exactly; leftover bytes kill the replay.</li>
 * </ol>
 */
public class UiStateAction implements com.moulberry.flashback.action.Action {

    /**
     * The one and only instance, and it must be the one registered.
     *
     * <p>ReplayWriter stores its action table in a {@code Reference2IntOpenHashMap}
     * keyed by object identity, filled from {@code ActionRegistry.getActions()}.
     * Writing with a different instance than the registered one makes
     * {@code startAction} throw "Unknown action", so registration and writing
     * have to share this exact object.
     */
    public static final UiStateAction INSTANCE = new UiStateAction();

    @Override
    public ResourceLocation name() {
        return ReplayUIIds.ACTION_UI_STATE;
    }

    @Override
    public void handle(ReplayServer replayServer, RegistryFriendlyByteBuf buf) {
        List<UiStateData> samples;
        try {
            samples = UiStateCodec.decodeTick(buf);
        } catch (Exception e) {
            // Never let a broken payload take the whole replay down. Jump to the
            // end so Flashback's "did not fully read" check stays happy.
            DebugLog.warn("failed to decode ui state action", e);
            buf.readerIndex(buf.writerIndex());
            return;
        }

        if (samples.isEmpty()) {
            return;
        }

        // Forward unconditionally, including while Flashback is applying a
        // snapshot: our data is a state, so the newest sample wins and a seek
        // lands on something correct. The viewer side decides when to show it.
        UiStatePayload payload = new UiStatePayload(samples);
        for (ReplayPlayer viewer : replayServer.getReplayViewers()) {
            try {
                ServerPlayNetworking.send(viewer, payload);
            } catch (Exception e) {
                DebugLog.warn("failed to send ui state to viewer", e);
            }
        }
    }
}
