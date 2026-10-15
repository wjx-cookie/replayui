package com.replayui.net;

import com.replayui.ReplayUIIds;
import com.replayui.codec.UiStateCodec;
import com.replayui.codec.UiStateData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * Server -> viewer payload carrying one tick of UI samples.
 *
 * <p>Must be registered on both sides (see ReplayUI), otherwise Flashback's
 * {@code FlashbackRawCustomPayload} wrapper cannot decode it and the data is
 * dropped silently.
 */
public record UiStatePayload(List<UiStateData> samples) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<UiStatePayload> TYPE =
            new CustomPacketPayload.Type<>(ReplayUIIds.PAYLOAD_UI_STATE);

    public static final StreamCodec<RegistryFriendlyByteBuf, UiStatePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> UiStateCodec.encodeTick(buf, payload.samples()),
                    buf -> new UiStatePayload(UiStateCodec.decodeTick(buf))
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
