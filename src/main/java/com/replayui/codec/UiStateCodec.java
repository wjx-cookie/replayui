package com.replayui.codec;

import com.replayui.ReplayUIIds;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Wire format for {@link UiStateData}.
 *
 * <p><b>Flashback requires the reader to consume exactly what the writer
 * produced.</b> Reading too much or too little throws and kills the replay, so
 * every {@code write} below MUST stay paired with the matching {@code read}.
 * If you touch one side, touch the other in the same commit.
 */
public final class UiStateCodec {

    /**
     * Vanilla caps a clientbound custom payload at 1 MiB. The delta path writes
     * a Flashback action and is not affected, but the baseline rides a real
     * payload, so it has to fit. Leave headroom for the payload framing.
     */
    public static final int MAX_PAYLOAD_BYTES = 1024 * 1024 - 4096;

    private UiStateCodec() {
    }

    /**
     * Encodes into a throwaway buffer just to measure the size.
     * Returns -1 if encoding failed.
     *
     * <p>Flashback silently swallows a payload that fails to encode, so an
     * oversized baseline would vanish with no error anywhere. Callers use this
     * to bail out loudly instead.
     */
    public static int measureEncodedSize(net.minecraft.core.RegistryAccess access, UiStateData data) {
        RegistryFriendlyByteBuf tmp = new RegistryFriendlyByteBuf(Unpooled.buffer(), access);
        try {
            encode(tmp, data);
            return tmp.writerIndex();
        } catch (Exception e) {
            return -1;
        } finally {
            tmp.release();
        }
    }

    public static void encode(RegistryFriendlyByteBuf buf, UiStateData data) {
        buf.writeVarInt(ReplayUIIds.FORMAT_VERSION);          // readVarInt
        buf.writeUUID(data.ownerUuid());                      // readUUID
        buf.writeBoolean(data.open());                        // readBoolean
        buf.writeVarInt(data.menuTypeId());                   // readVarInt
        buf.writeUtf(Component.Serializer.toJson(data.title(), buf.registryAccess())); // readUtf

        buf.writeVarInt(data.items().size());                 // readVarInt
        for (ItemStack stack : data.items()) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack); // decode
        }

        buf.writeVarInt(data.containerId());                  // readVarInt
        buf.writeDouble(data.mouseX());                       // readDouble
        buf.writeDouble(data.mouseY());                       // readDouble

        if (data.blockPos() != null) {                        // readBoolean
            buf.writeBoolean(true);
            buf.writeBlockPos(data.blockPos());               // readBlockPos
        } else {
            buf.writeBoolean(false);
        }
    }

    public static UiStateData decode(RegistryFriendlyByteBuf buf) {
        int version = buf.readVarInt();
        if (version != ReplayUIIds.FORMAT_VERSION) {
            // Unknown layout: stop cleanly instead of desyncing the stream.
            throw new IllegalStateException("replayui: unsupported format " + version);
        }

        UUID ownerUuid = buf.readUUID();
        boolean open = buf.readBoolean();
        int menuTypeId = buf.readVarInt();
        // fromJson is @Nullable - a title that failed to parse must not become
        // a null field, or the next encode would NPE.
        Component title = Component.Serializer.fromJson(buf.readUtf(), buf.registryAccess());
        if (title == null) {
            title = Component.empty();
        }

        int count = buf.readVarInt();
        List<ItemStack> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
        }

        int containerId = buf.readVarInt();
        double mouseX = buf.readDouble();
        double mouseY = buf.readDouble();

        BlockPos blockPos = buf.readBoolean() ? buf.readBlockPos() : null;

        return new UiStateData(ownerUuid, open, menuTypeId, title,
                items, containerId, mouseX, mouseY, blockPos);
    }

    /** Encodes one tick's worth of samples: count followed by each sample. */
    public static void encodeTick(RegistryFriendlyByteBuf buf, List<UiStateData> samples) {
        buf.writeVarInt(samples.size());
        for (UiStateData sample : samples) {
            encode(buf, sample);
        }
    }

    public static List<UiStateData> decodeTick(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<UiStateData> samples = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            samples.add(decode(buf));
        }
        return samples;
    }
}
