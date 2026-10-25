package com.replayui.record;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.record.Recorder;
import com.replayui.ReplayUIConfig;
import com.replayui.action.UiStateAction;
import com.replayui.codec.UiStateCodec;
import com.replayui.codec.UiStateData;
import com.replayui.log.DebugLog;
import com.replayui.net.UiStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;

import java.util.List;
import java.util.function.Consumer;

/**
 * Owns every write into the replay file. Nothing else talks to {@link Recorder}.
 *
 * <p>Two paths, because they have different guarantees:
 * <ul>
 *   <li><b>Delta</b> via {@code submitCustomTask} - a custom action, immune to
 *       Flashback's ignored-packet list. This is the normal path.</li>
 *   <li><b>Baseline</b> via {@code writeCustomSnapshot} - the only path that
 *       survives {@code readyToWrite()} being false (startup, resume after
 *       pause). It is reached from {@code MixinRecorder}. Snapshots can only
 *       carry packets, so this one rides a payload instead of an action.</li>
 * </ul>
 */
public final class RecorderBridge {

    // Must be the registered singleton - see UiStateAction#INSTANCE.
    private static final UiStateAction ACTION = UiStateAction.INSTANCE;

    private RecorderBridge() {
    }

    public static void writeDelta(UiStateData data) {
        Recorder recorder = Flashback.RECORDER;
        if (recorder == null) {
            return;
        }
        try {
            recorder.submitCustomTask(writer -> {
                writer.startAction(ACTION);
                try {
                    UiStateCodec.encodeTick(writer.friendlyByteBuf(), List.of(data));
                } catch (Exception e) {
                    // Still finish below. ReplayWriter keeps a "currently
                    // writing" flag that is only cleared in finishAction, so
                    // bailing out here would wedge every later write and then
                    // break popBytes() - i.e. corrupt the whole recording.
                    DebugLog.warn("failed to encode ui delta", e);
                }
                writer.finishAction(ACTION);
            });
        } catch (Exception e) {
            DebugLog.warn("failed to write ui delta", e);
        }
    }

    /**
     * Called from {@code MixinRecorder} inside {@code writeCustomSnapshot}.
     * Packets added here bypass both the ignore list and the readyToWrite gate.
     */
    public static void writeBaseline(Consumer<net.minecraft.network.protocol.Packet<? super ClientGamePacketListener>> consumer) {
        // Same gate as the delta path, enforced here rather than in
        // MixinRecorder because this class owns every write.
        //
        // It matters more than it looks: a baseline fires at recording start
        // and again on resume after a pause, so with the switch off but this
        // path open, a recording would still pick up a screen the moment it
        // started - and playback would then show UI from a recording made while
        // the mod was supposedly disabled.
        if (!ReplayUIConfig.enabled) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return;
        }
        try {
            UiStateData data = UiStateReader.capture(client, player.getUUID());

            // The baseline travels as a real payload, which vanilla caps at
            // 1 MiB, and Flashback drops anything that fails to encode without
            // a word. Measure first so an oversized container is reported
            // instead of silently missing from the replay.
            int size = UiStateCodec.measureEncodedSize(player.registryAccess(), data);
            if (size < 0) {
                DebugLog.warn("ui baseline failed to encode, skipping");
                return;
            }
            if (size > UiStateCodec.MAX_PAYLOAD_BYTES) {
                DebugLog.warn("ui baseline too large (" + size + " bytes), skipping");
                return;
            }

            consumer.accept(new ClientboundCustomPayloadPacket(new UiStatePayload(List.of(data))));
            DebugLog.verbose("baseline written open=" + data.open() + " bytes=" + size);
        } catch (Exception e) {
            DebugLog.warn("failed to write ui baseline", e);
        }
    }
}
