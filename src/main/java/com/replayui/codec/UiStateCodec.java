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

    /**
     * Upper bound on samples per tick, so a corrupt count cannot turn into an
     * unbounded allocation. A normal tick carries one; the baseline carries one.
     */
    public static final int MAX_SAMPLES_PER_TICK = 64;

    /**
     * Upper bound on slots per screen, for the same reason: a corrupt slot
     * count must not become an unbounded allocation. Vanilla tops out near 90
     * (large chest plus the player's rows) and modded menus go higher, so this
     * is set well above anything legitimate.
     */
    public static final int MAX_SLOTS_PER_SCREEN = 1024;

    /** Bound on progress values; vanilla menus use fewer than ten. */
    public static final int MAX_DATA_SLOTS = 64;

    /** Bound on book pages; a vanilla book caps well below this. */
    public static final int MAX_BOOK_PAGES = 64;

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

        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, data.carried()); // decode

        buf.writeVarInt(data.containerId());                  // readVarInt
        buf.writeDouble(data.mouseX());                       // readDouble
        buf.writeDouble(data.mouseY());                       // readDouble

        if (data.blockPos() != null) {                        // readBoolean
            buf.writeBoolean(true);
            buf.writeBlockPos(data.blockPos());               // readBlockPos
        } else {
            buf.writeBoolean(false);
        }

        int[] slots = data.dataSlots();
        if (slots == null) {
            buf.writeVarInt(0);
        } else {
            buf.writeVarInt(slots.length);
            for (int value : slots) {
                buf.writeVarInt(value);
            }
        }

        buf.writeVarInt(data.windowWidth());                  // readVarInt
        buf.writeVarInt(data.windowHeight());                 // readVarInt

        // ScreenExtras: one block, always written, so a version-4 reader never
        // has to guess whether the tail is present.
        ScreenExtras ex = data.extras() == null ? ScreenExtras.EMPTY : data.extras();
        buf.writeVarInt(ex.entityId());                       // readVarInt
        buf.writeVarInt(ex.columns());                        // readVarInt
        buf.writeVarInt(ex.selectedTab());                    // readVarInt
        buf.writeFloat(ex.scrollOffs());                      // readFloat
        buf.writeUtf(ex.searchText());                        // readUtf
        buf.writeVarInt(ex.currentPage());                    // readVarInt
        buf.writeVarInt(ex.bookPages().size());               // readVarInt
        for (String page : ex.bookPages()) {
            buf.writeUtf(page == null ? "" : page);           // readUtf
        }
        buf.writeVarInt(ex.bookPage());                       // readVarInt
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
        // Same guard as decodeTick, and just as necessary: new ArrayList<>(count)
        // with a corrupt count is an allocation of arbitrary size, and it
        // happens on the replay server thread while an action is being read.
        //
        // Generous on purpose - the largest vanilla menu a container screen can
        // show is around 90 slots and modded ones go well past that - so this
        // only ever trips on data that is genuinely broken.
        if (count < 0 || count > MAX_SLOTS_PER_SCREEN) {
            throw new IllegalStateException("replayui: implausible slot count " + count);
        }
        List<ItemStack> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
        }

        // OPTIONAL_STREAM_CODEC never returns null, but decode() on a buffer
        // that ran out can; EMPTY is the only sane fallback and keeps every
        // later encode from NPE-ing on a missing field.
        ItemStack carried = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        if (carried == null) {
            carried = ItemStack.EMPTY;
        }

        int containerId = buf.readVarInt();
        double mouseX = buf.readDouble();
        double mouseY = buf.readDouble();

        BlockPos blockPos = buf.readBoolean() ? buf.readBlockPos() : null;

        int dataSlotCount = buf.readVarInt();
        if (dataSlotCount < 0 || dataSlotCount > MAX_DATA_SLOTS) {
            throw new IllegalStateException("replayui: implausible data slot count "
                    + dataSlotCount);
        }
        int[] dataSlots = new int[dataSlotCount];
        for (int i = 0; i < dataSlotCount; i++) {
            dataSlots[i] = buf.readVarInt();
        }

        int windowWidth = buf.readVarInt();
        int windowHeight = buf.readVarInt();

        int entityId = buf.readVarInt();
        int columns = buf.readVarInt();
        int selectedTab = buf.readVarInt();
        float scrollOffs = buf.readFloat();
        String searchText = buf.readUtf();
        int currentPage = buf.readVarInt();
        int pageCount = buf.readVarInt();
        if (pageCount < 0 || pageCount > MAX_BOOK_PAGES) {
            throw new IllegalStateException("replayui: implausible book page count "
                    + pageCount);
        }
        List<String> bookPages = new ArrayList<>(pageCount);
        for (int i = 0; i < pageCount; i++) {
            bookPages.add(buf.readUtf());
        }
        int bookPage = buf.readVarInt();
        ScreenExtras extras = new ScreenExtras(entityId, columns, selectedTab,
                scrollOffs, searchText, currentPage, bookPages, bookPage);

        return new UiStateData(ownerUuid, open, menuTypeId, title,
                items, carried, containerId, mouseX, mouseY, blockPos, dataSlots,
                windowWidth, windowHeight, extras);
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
        // Sanity bound before allocating. A writer only ever emits one sample
        // per tick (delta) or one for the whole baseline, so a larger number
        // means the stream is corrupt - and new ArrayList<>(count) with a
        // garbage count is an allocation of arbitrary size inside the replay
        // server thread. Throw so UiStateAction's catch skips the payload
        // rather than letting the allocation happen.
        if (count < 0 || count > MAX_SAMPLES_PER_TICK) {
            throw new IllegalStateException("replayui: implausible sample count " + count);
        }
        List<UiStateData> samples = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            samples.add(decode(buf));
        }
        return samples;
    }
}
