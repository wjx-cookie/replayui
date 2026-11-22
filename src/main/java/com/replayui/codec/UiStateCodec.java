package com.replayui.codec;

import com.replayui.ReplayUIIds;
import com.replayui.util.ComponentJson;
import io.netty.buffer.ByteBuf;
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

    /** Vanilla offers a handful of trades; bound the allocation on corrupt data. */
    public static final int MAX_MERCHANT_OFFERS = 64;

    /** Bound on the enchantment clue / level arrays. */
    public static final int MAX_EXTRA_INTS = 64;

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
            // encodeTick wraps every sample in a VarInt length prefix; budget
            // its worst case so a baseline right at the 1 MiB edge cannot slip
            // through the check and then fail on the real write.
            return tmp.writerIndex() + 5;
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
        // Serializer's 2-arg overload changed in 1.21.1. Go through the
        // reflective helper so a custom container name never degrades to an
        // empty title just because the guessed signature did not compile.
        buf.writeUtf(ComponentJson.toJson(data.title(), buf.registryAccess())); // readUtf

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

        buf.writeVarInt(data.eventKind());                    // readVarInt

        // ---- format 6: menu extras, always written in a fixed order ----
        // The per-sample length prefix added in encodeTick means a future writer
        // can append more fields here; an older reader skips the tail instead of
        // desyncing the whole stream.
        buf.writeVarInt(ex.merchantOffers().size());          // readVarInt
        for (MerchantOfferData offer : ex.merchantOffers()) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, offer.baseCostA()); // decode
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, offer.costB());     // decode
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, offer.result());     // decode
            buf.writeVarInt(offer.uses());                    // readVarInt
            buf.writeVarInt(offer.maxUses());                 // readVarInt
            buf.writeVarInt(offer.xp());                      // readVarInt
            buf.writeVarInt(offer.demand());                  // readVarInt
            buf.writeVarInt(offer.specialPrice());            // readVarInt
            buf.writeFloat(offer.priceMultiplier());          // readFloat
        }
        buf.writeUtf(ex.renameText());                        // readUtf
        buf.writeVarInt(ex.enchantClues().length);            // readVarInt
        for (int value : ex.enchantClues()) {
            buf.writeVarInt(value);                           // readVarInt
        }
        buf.writeVarInt(ex.enchantLevels().length);           // readVarInt
        for (int value : ex.enchantLevels()) {
            buf.writeVarInt(value);                           // readVarInt
        }
        buf.writeVarInt(ex.experienceLevel());                // readVarInt

        // ---- screen-side extras, appended without a format bump ----
        // format 6 already length-prefixes every sample. An older reader skips
        // this tail; this reader tolerates its absence below. No re-record.
        buf.writeUtf(ex.bookTitle());                         // readUtf
        buf.writeUtf(ex.bookAuthor());                        // readUtf
        buf.writeFloat(ex.merchantScroll());                  // readFloat
        buf.writeVarInt(ex.selectedOffer());                  // readVarInt
        buf.writeVarInt(ex.merchantLevel());                  // readVarInt
        buf.writeVarInt(ex.merchantXp());                     // readVarInt
        buf.writeVarInt(ex.recipeIndex());                    // readVarInt
        buf.writeBoolean(ex.anvilFocus());                    // readBoolean
        buf.writeVarInt(ex.anvilCursor());                    // readVarInt

        // Trailing block. Format 6 length-prefixes each sample, so an older
        // reader skips these bytes; no version bump, no re-record.
        ExtendedState ext = ex.extended() == null ? ExtendedState.EMPTY : ex.extended();
        ext.write(buf);
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
        // a null field, or the next encode would NPE. Reflective helper also
        // handles legacy plain-text titles and keeps their styling.
        Component title = ComponentJson.fromJson(buf.readUtf(), buf.registryAccess());
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
        int eventKind = buf.readVarInt();

        // ---- format 6: menu extras ----
        int offerCount = buf.readVarInt();
        if (offerCount < 0 || offerCount > MAX_MERCHANT_OFFERS) {
            throw new IllegalStateException("replayui: implausible merchant offer count "
                    + offerCount);
        }
        List<MerchantOfferData> offers = new ArrayList<>(offerCount);
        for (int i = 0; i < offerCount; i++) {
            ItemStack costA = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            ItemStack costB = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            ItemStack result = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            offers.add(new MerchantOfferData(
                    costA == null ? ItemStack.EMPTY : costA,
                    costB == null ? ItemStack.EMPTY : costB,
                    result == null ? ItemStack.EMPTY : result,
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readFloat()));
        }
        String renameText = buf.readUtf();
        int clueCount = buf.readVarInt();
        if (clueCount < 0 || clueCount > MAX_EXTRA_INTS) {
            throw new IllegalStateException("replayui: implausible clue count " + clueCount);
        }
        int[] enchantClues = new int[clueCount];
        for (int i = 0; i < clueCount; i++) {
            enchantClues[i] = buf.readVarInt();
        }
        int levelCount = buf.readVarInt();
        if (levelCount < 0 || levelCount > MAX_EXTRA_INTS) {
            throw new IllegalStateException("replayui: implausible level clue count " + levelCount);
        }
        int[] enchantLevels = new int[levelCount];
        for (int i = 0; i < levelCount; i++) {
            enchantLevels[i] = buf.readVarInt();
        }
        int experienceLevel = buf.readVarInt();

        // A sample written by an earlier format-6 build stops here. Each read is
        // guarded, so old replays still decode and new ones round-trip. This is
        // the other half of the length prefix: neither direction desyncs.
        String bookTitle = buf.isReadable() ? buf.readUtf() : "";
        String bookAuthor = buf.isReadable() ? buf.readUtf() : "";
        float merchantScroll = buf.isReadable() ? buf.readFloat() : 0.0f;
        int selectedOffer = buf.isReadable() ? buf.readVarInt() : -1;
        int merchantLevel = buf.isReadable() ? buf.readVarInt() : 0;
        int merchantXp = buf.isReadable() ? buf.readVarInt() : 0;
        int recipeIndex = buf.isReadable() ? buf.readVarInt() : -1;
        boolean anvilFocus = buf.isReadable() && buf.readBoolean();
        int anvilCursor = buf.isReadable() ? buf.readVarInt() : 0;

        ExtendedState extended = ExtendedState.read(buf);

        ScreenExtras extras = new ScreenExtras(entityId, columns, selectedTab,
                scrollOffs, searchText, currentPage, bookPages, bookPage,
                offers, renameText, enchantClues, enchantLevels, experienceLevel,
                bookTitle, bookAuthor, merchantScroll, selectedOffer,
                merchantLevel, merchantXp, recipeIndex, anvilFocus, anvilCursor,
                extended);

        // Forward compatibility: this buffer is one length-prefixed sample. A
        // newer writer may have appended fields this reader does not know about.
        // Consuming them keeps Flashback's "reader must eat exactly what the
        // writer produced" contract intact instead of desyncing the next sample.
        if (buf.isReadable()) {
            buf.skipBytes(buf.readableBytes());
        }

        return new UiStateData(ownerUuid, open, menuTypeId, title,
                items, carried, containerId, mouseX, mouseY, blockPos, dataSlots,
                windowWidth, windowHeight, extras, eventKind);
    }

    /**
     * Encodes one tick's worth of samples: count, then each sample as
     * {@code [length][payload]}.
     *
     * <p>The length prefix is what makes format 6 the last forced re-record.
     * A reader from an older build can skip a sample's trailing bytes without
     * knowing their layout, so appending a field in 7, 8, ... desyncs nothing.
     * Flashback still requires the outer buffer to be consumed exactly, which
     * the prefix preserves: every payload is read as a bounded slice.
     */
    public static void encodeTick(RegistryFriendlyByteBuf buf, List<UiStateData> samples) {
        buf.writeVarInt(samples.size());
        for (UiStateData sample : samples) {
            RegistryFriendlyByteBuf tmp =
                    new RegistryFriendlyByteBuf(Unpooled.buffer(), buf.registryAccess());
            try {
                encode(tmp, sample);
                int length = tmp.writerIndex();
                buf.writeVarInt(length);
                buf.writeBytes(tmp, 0, length);
            } finally {
                tmp.release();
            }
        }
    }

    public static List<UiStateData> decodeTick(RegistryFriendlyByteBuf buf) {
        if (!buf.isReadable()) {
            return java.util.Collections.emptyList();
        }
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
            int length = buf.readVarInt();
            if (length < 0 || length > buf.readableBytes()) {
                throw new IllegalStateException("replayui: implausible sample length " + length);
            }
            ByteBuf payload = buf.readSlice(length);
            RegistryFriendlyByteBuf view =
                    new RegistryFriendlyByteBuf(payload, buf.registryAccess());
            samples.add(decode(view));
        }
        return samples;
    }
}
