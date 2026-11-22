package com.replayui.codec;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Everything added after format 6 that does not fit the original
 * {@link ScreenExtras} shape, carried as one trailing block.
 *
 * <p>Format 6 length-prefixes every sample, so an older reader skips this whole
 * block without desyncing, and this reader tolerates its absence. No version
 * bump, no forced re-record.
 *
 * <p>Layout shared between {@code ExtendedReader} and {@code ExtendedSupport}:
 * <ul>
 *   <li>{@code text} - ordered string slots (book/sign/command/structure state,
 *       beacon effect keys, then the book page text, sign focus text, command
 *       suggestions/history, structure mirror/rotation names, seed and block
 *       position, then recipe-book state, command output JSON and the recorded
 *       creative item ids).</li>
 *   <li>{@code numbers} - ordered integer slots (page index, colour, mode,
 *       structure pos/size, integrity, seed fallback, flags, then caret/selection,
 *       focused line, suggestion index and enum ordinals, recipe ordinals and
 *       the command success count). Slot indices are owned by
 *       {@code ExtendedReader} (0..8 / 0..20), {@code NonContainerDetail}
 *       (9..17 / 21..33) and {@code ScreenDetail} (18..27 / 34..37); all
 *       sides count from the same total, so adding slots never shifts an
 *       existing one.</li>
 *   <li>{@code skinValue/signature/model} - deprecated, kept empty for v6 wire layout.</li>
 *   <li>{@code hotbarGroups} - creative saved hotbars, kept as NESTED lists.
 *       The field in the player's inventory is a list of lists; flattening it
 *       and then setting individual ItemStacks corrupts the viewer's own data.</li>
 *   <li>{@code richPages} - Component JSON, so colour/bold/click events survive.</li>
 * </ul>
 */
public final class ExtendedState {

    public static final int MAX_TEXT = 96;
    public static final int MAX_NUMBERS = 96;
    public static final int MAX_PRESET_GROUPS = 16;
    public static final int MAX_PRESET_SLOTS = 64;
    public static final int MAX_RICH_PAGES = 64;

    public static final ExtendedState EMPTY = new ExtendedState(
            0, Collections.<String>emptyList(), new int[0], "", "", "",
            -1, -1, Collections.<List<ItemStack>>emptyList(),
            Collections.<String>emptyList());

    private final int kind;
    private final List<String> text;
    private final int[] numbers;
    private final String skinValue;
    private final String skinSignature;
    private final String skinModel;
    private final int beaconPrimary;
    private final int beaconSecondary;
    private final List<List<ItemStack>> hotbarGroups;
    private final List<String> richPages;

    public ExtendedState(int kind, List<String> text, int[] numbers,
                         String skinValue, String skinSignature, String skinModel,
                         int beaconPrimary, int beaconSecondary,
                         List<List<ItemStack>> hotbarGroups, List<String> richPages) {
        this.kind = kind;
        this.text = text == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(text));
        this.numbers = numbers == null ? new int[0] : numbers.clone();
        this.skinValue = skinValue == null ? "" : skinValue;
        this.skinSignature = skinSignature == null ? "" : skinSignature;
        this.skinModel = skinModel == null ? "" : skinModel;
        this.beaconPrimary = beaconPrimary;
        this.beaconSecondary = beaconSecondary;
        this.hotbarGroups = hotbarGroups == null
                ? Collections.<List<ItemStack>>emptyList()
                : deepCopy(hotbarGroups);
        this.richPages = richPages == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(richPages));
    }

    private static List<List<ItemStack>> deepCopy(List<List<ItemStack>> src) {
        List<List<ItemStack>> out = new ArrayList<List<ItemStack>>(src.size());
        for (List<ItemStack> group : src) {
            List<ItemStack> g = new ArrayList<ItemStack>();
            if (group != null) {
                for (ItemStack stack : group) {
                    g.add(stack == null ? ItemStack.EMPTY : stack.copy());
                }
            }
            out.add(Collections.unmodifiableList(g));
        }
        return Collections.unmodifiableList(out);
    }

    public int kind() { return kind; }
    public List<String> text() { return text; }
    public int[] numbers() { return numbers.clone(); }
    public String skinValue() { return skinValue; }
    public String skinSignature() { return skinSignature; }
    public String skinModel() { return skinModel; }
    public int beaconPrimary() { return beaconPrimary; }
    public int beaconSecondary() { return beaconSecondary; }
    public List<List<ItemStack>> hotbarGroups() { return hotbarGroups; }
    public List<String> richPages() { return richPages; }

    public String textAt(int i) { return i >= 0 && i < text.size() ? text.get(i) : ""; }
    public int numberAt(int i) { return i >= 0 && i < numbers.length ? numbers[i] : 0; }

    public boolean isEmpty() {
        return kind == 0 && text.isEmpty() && numbers.length == 0
                && skinValue.isEmpty() && skinSignature.isEmpty() && skinModel.isEmpty()
                && beaconPrimary < 0 && beaconSecondary < 0
                && hotbarGroups.isEmpty() && richPages.isEmpty();
    }

    /** Appended at the very end of one sample. */
    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(kind);
        buf.writeVarInt(text.size());
        for (String s : text) { buf.writeUtf(s == null ? "" : s); }
        buf.writeVarInt(numbers.length);
        for (int v : numbers) { buf.writeVarInt(v); }
        buf.writeUtf(skinValue);
        buf.writeUtf(skinSignature);
        buf.writeUtf(skinModel);
        buf.writeVarInt(beaconPrimary);
        buf.writeVarInt(beaconSecondary);
        buf.writeVarInt(hotbarGroups.size());
        for (List<ItemStack> group : hotbarGroups) {
            buf.writeVarInt(group.size());
            for (ItemStack stack : group) {
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack);
            }
        }
        buf.writeVarInt(richPages.size());
        for (String page : richPages) { buf.writeUtf(page == null ? "" : page); }
    }

    /**
     * Old samples stop before this block entirely. A build that wrote the old
     * FLAT preset list has a group count well above {@link #MAX_PRESET_GROUPS},
     * so it degrades to EMPTY instead of being misparsed. Every step is guarded
     * so a truncated tail never takes the whole action down.
     */
    public static ExtendedState read(RegistryFriendlyByteBuf buf) {
        if (!buf.isReadable()) { return EMPTY; }
        try {
            int k = buf.readVarInt();
            int tc = buf.readVarInt();
            if (tc < 0 || tc > MAX_TEXT) { return EMPTY; }
            List<String> t = new ArrayList<String>(tc);
            for (int i = 0; i < tc && buf.isReadable(); i++) { t.add(buf.readUtf()); }
            int nc = buf.isReadable() ? buf.readVarInt() : 0;
            if (nc < 0 || nc > MAX_NUMBERS) { return EMPTY; }
            int[] n = new int[nc];
            for (int i = 0; i < nc && buf.isReadable(); i++) { n[i] = buf.readVarInt(); }
            String sv = buf.isReadable() ? buf.readUtf() : "";
            String ss = buf.isReadable() ? buf.readUtf() : "";
            String sm = buf.isReadable() ? buf.readUtf() : "";
            int bp = buf.isReadable() ? buf.readVarInt() : -1;
            int bs = buf.isReadable() ? buf.readVarInt() : -1;
            int gc = buf.isReadable() ? buf.readVarInt() : 0;
            if (gc < 0 || gc > MAX_PRESET_GROUPS) { return EMPTY; }
            List<List<ItemStack>> groups = new ArrayList<List<ItemStack>>(gc);
            for (int g = 0; g < gc && buf.isReadable(); g++) {
                int sc = buf.readVarInt();
                if (sc < 0 || sc > MAX_PRESET_SLOTS) { return EMPTY; }
                List<ItemStack> group = new ArrayList<ItemStack>(sc);
                for (int i = 0; i < sc && buf.isReadable(); i++) {
                    ItemStack s = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                    group.add(s == null ? ItemStack.EMPTY : s);
                }
                groups.add(group);
            }
            int rc = buf.isReadable() ? buf.readVarInt() : 0;
            if (rc < 0 || rc > MAX_RICH_PAGES) { return EMPTY; }
            List<String> r = new ArrayList<String>(rc);
            for (int i = 0; i < rc && buf.isReadable(); i++) { r.add(buf.readUtf()); }
            return new ExtendedState(k, t, n, sv, ss, sm, bp, bs, groups, r);
        } catch (Throwable ignored) {
            return EMPTY;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (!(o instanceof ExtendedState)) { return false; }
        ExtendedState e = (ExtendedState) o;
        return kind == e.kind && text.equals(e.text)
                && Arrays.equals(numbers, e.numbers)
                && skinValue.equals(e.skinValue) && skinSignature.equals(e.skinSignature)
                && skinModel.equals(e.skinModel)
                && beaconPrimary == e.beaconPrimary && beaconSecondary == e.beaconSecondary
                && sameGroups(hotbarGroups, e.hotbarGroups)
                && richPages.equals(e.richPages);
    }

    /**
     * ItemStack has no content-based equals in 1.21.1. A plain List.equals
     * would compare decoded presets by identity, so every sample with a skin
     * would look "changed" and be written 20 times a second. Same rule as
     * {@code UiStateData#sameAs}: count plus matches().
     */
    private static boolean sameGroups(List<List<ItemStack>> a, List<List<ItemStack>> b) {
        if (a == b) { return true; }
        if (a == null || b == null || a.size() != b.size()) { return false; }
        for (int i = 0; i < a.size(); i++) {
            if (!sameStacks(a.get(i), b.get(i))) { return false; }
        }
        return true;
    }

    private static boolean sameStacks(List<ItemStack> a, List<ItemStack> b) {
        if (a == b) { return true; }
        if (a == null || b == null || a.size() != b.size()) { return false; }
        for (int i = 0; i < a.size(); i++) {
            ItemStack x = a.get(i);
            ItemStack y = b.get(i);
            if (x == y) { continue; }
            if (x == null || y == null) { return false; }
            if (x.getCount() != y.getCount() || !ItemStack.matches(x, y)) { return false; }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = Objects.hash(kind, text, skinValue, skinSignature, skinModel,
                beaconPrimary, beaconSecondary, richPages);
        h = 31 * h + Arrays.hashCode(numbers);
        h = 31 * h + hotbarGroups.size();
        for (List<ItemStack> group : hotbarGroups) {
            h = 31 * h + group.size();
        }
        return h;
    }
}
