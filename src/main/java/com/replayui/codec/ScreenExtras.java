package com.replayui.codec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * State that vanilla does not fully describe with {@code menu.slots} and
 * {@code ContainerData}. Three groups live here:
 *
 * <ul>
 *   <li><b>Special screens</b> - creative tabs/scroll/search, horse/llama
 *       entity reference and columns, lectern book pages and metadata.</li>
 *   <li><b>Menu extras</b> (format 6) - villager trade offers, the anvil
 *       rename text, enchantment clue arrays.</li>
 *   <li><b>Screen-side extras</b> (format 6, appended) - the trade list
 *       scroll/selection/level, the selected recipe of the crafting-station
 *       menus, and the anvil rename box focus. These live on the Screen, not
 *       the Menu, which is why the readers need the screen instance.</li>
 * </ul>
 *
 * <p>Kept as plain data - no Minecraft classes - so the codec can carry it and
 * playback restores it reflectively. Every value is best effort: a candidate
 * name that changed between mappings leaves a default. That is a cosmetic
 * downgrade, never a crash.
 *
 * <p>The per-sample length prefix introduced in format 6 means these trailing
 * fields do not require another format bump: an older reader skips bytes it
 * does not know, and this reader tolerates their absence (see
 * {@code UiStateCodec#decode}).
 *
 * <p>The original 8- and 13-argument constructors are kept so the special
 * screen readers and earlier callers need no changes.
 */
public final class ScreenExtras {

    public static final ScreenExtras EMPTY =
            new ScreenExtras(-1, 0, -1, 0.0f, "", 0, Collections.<String>emptyList(), 0);

    private final int entityId;        // horse entity id in the replay world
    private final int columns;         // horse/llama inventory columns
    private final int selectedTab;     // creative tab index, -1 = unknown
    private final float scrollOffs;    // creative scroll, 0..1
    private final String searchText;   // creative search box contents
    private final int currentPage;     // creative page
    private final List<String> bookPages; // lectern pages, plain text
    private final int bookPage;        // lectern page index

    private final List<MerchantOfferData> merchantOffers;
    private final String renameText;
    private final int[] enchantClues;
    private final int[] enchantLevels;
    private final int experienceLevel;

    // ---- screen-side extras (appended in format 6, version unchanged) ----
    private final String bookTitle;    // lectern / written book title
    private final String bookAuthor;   // lectern / written book author
    private final float merchantScroll;    // MerchantScreen scroll offset
    private final int selectedOffer;       // highlighted trade, -1 = none
    private final int merchantLevel;       // villager tier shown in the header
    private final int merchantXp;          // tier progress bar
    private final int recipeIndex;         // stonecutter / loom / cartography / smithing
    private final boolean anvilFocus;      // rename box focused
    private final int anvilCursor;         // caret position in the rename box

    /** Trailing block: non-container screens, beacon, hotbar presets,
     *  rich-text pages. Added without a format bump - see ExtendedState. */
    private final ExtendedState extended;

    /** Original constructor: special-screen state only. */
    public ScreenExtras(int entityId, int columns, int selectedTab, float scrollOffs,
                        String searchText, int currentPage,
                        List<String> bookPages, int bookPage) {
        this(entityId, columns, selectedTab, scrollOffs, searchText, currentPage,
                bookPages, bookPage, Collections.<MerchantOfferData>emptyList(), "",
                new int[0], new int[0], -1);
    }

    /** Format-6 constructor before the screen-side fields were appended. */
    public ScreenExtras(int entityId, int columns, int selectedTab, float scrollOffs,
                        String searchText, int currentPage,
                        List<String> bookPages, int bookPage,
                        List<MerchantOfferData> merchantOffers, String renameText,
                        int[] enchantClues, int[] enchantLevels, int experienceLevel) {
        this(entityId, columns, selectedTab, scrollOffs, searchText, currentPage,
                bookPages, bookPage, merchantOffers, renameText,
                enchantClues, enchantLevels, experienceLevel,
                "", "", 0.0f, -1, 0, 0, -1, false, 0,
                ExtendedState.EMPTY);
    }

    /** Full constructor kept for older callers: extended defaults to EMPTY. */
    public ScreenExtras(int entityId, int columns, int selectedTab, float scrollOffs,
                        String searchText, int currentPage,
                        List<String> bookPages, int bookPage,
                        List<MerchantOfferData> merchantOffers, String renameText,
                        int[] enchantClues, int[] enchantLevels, int experienceLevel,
                        String bookTitle, String bookAuthor,
                        float merchantScroll, int selectedOffer,
                        int merchantLevel, int merchantXp,
                        int recipeIndex, boolean anvilFocus, int anvilCursor) {
        this(entityId, columns, selectedTab, scrollOffs, searchText, currentPage,
                bookPages, bookPage, merchantOffers, renameText,
                enchantClues, enchantLevels, experienceLevel,
                bookTitle, bookAuthor, merchantScroll, selectedOffer,
                merchantLevel, merchantXp, recipeIndex, anvilFocus, anvilCursor,
                ExtendedState.EMPTY);
    }

    /** Full constructor with the trailing block. */
    public ScreenExtras(int entityId, int columns, int selectedTab, float scrollOffs,
                        String searchText, int currentPage,
                        List<String> bookPages, int bookPage,
                        List<MerchantOfferData> merchantOffers, String renameText,
                        int[] enchantClues, int[] enchantLevels, int experienceLevel,
                        String bookTitle, String bookAuthor,
                        float merchantScroll, int selectedOffer,
                        int merchantLevel, int merchantXp,
                        int recipeIndex, boolean anvilFocus, int anvilCursor,
                        ExtendedState extended) {
        this.entityId = entityId;
        this.columns = columns;
        this.selectedTab = selectedTab;
        this.scrollOffs = scrollOffs;
        this.searchText = searchText == null ? "" : searchText;
        this.currentPage = currentPage;
        this.bookPages = bookPages == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(bookPages));
        this.bookPage = bookPage;
        this.merchantOffers = merchantOffers == null
                ? Collections.<MerchantOfferData>emptyList()
                : Collections.unmodifiableList(new ArrayList<MerchantOfferData>(merchantOffers));
        this.renameText = renameText == null ? "" : renameText;
        this.enchantClues = enchantClues == null ? new int[0] : enchantClues.clone();
        this.enchantLevels = enchantLevels == null ? new int[0] : enchantLevels.clone();
        this.experienceLevel = experienceLevel;
        this.bookTitle = bookTitle == null ? "" : bookTitle;
        this.bookAuthor = bookAuthor == null ? "" : bookAuthor;
        this.merchantScroll = merchantScroll;
        this.selectedOffer = selectedOffer;
        this.merchantLevel = merchantLevel;
        this.merchantXp = merchantXp;
        this.recipeIndex = recipeIndex;
        this.anvilFocus = anvilFocus;
        this.anvilCursor = anvilCursor;
        this.extended = extended == null ? ExtendedState.EMPTY : extended;
    }

    public int entityId() { return entityId; }
    public int columns() { return columns; }
    public int selectedTab() { return selectedTab; }
    public float scrollOffs() { return scrollOffs; }
    public String searchText() { return searchText; }
    public int currentPage() { return currentPage; }
    public List<String> bookPages() { return bookPages; }
    public int bookPage() { return bookPage; }

    public List<MerchantOfferData> merchantOffers() { return merchantOffers; }
    public String renameText() { return renameText; }
    public int[] enchantClues() { return enchantClues; }
    public int[] enchantLevels() { return enchantLevels; }
    public int experienceLevel() { return experienceLevel; }

    public String bookTitle() { return bookTitle; }
    public String bookAuthor() { return bookAuthor; }
    public float merchantScroll() { return merchantScroll; }
    public int selectedOffer() { return selectedOffer; }
    public int merchantLevel() { return merchantLevel; }
    public int merchantXp() { return merchantXp; }
    public int recipeIndex() { return recipeIndex; }
    public boolean anvilFocus() { return anvilFocus; }
    public int anvilCursor() { return anvilCursor; }
    public ExtendedState extended() { return extended; }

    /** True when this sample belongs to a horse/llama inventory. */
    public boolean hasEntity() { return entityId >= 0; }

    /** Cheap guard so ordinary containers skip the reflective applier entirely. */
    public boolean hasNoMenuExtras() {
        return merchantOffers.isEmpty() && renameText.isEmpty()
                && enchantClues.length == 0 && enchantLevels.length == 0
                && experienceLevel < 0
                && bookTitle.isEmpty() && bookAuthor.isEmpty()
                && Float.compare(merchantScroll, 0.0f) == 0
                && selectedOffer < 0 && merchantLevel == 0 && merchantXp == 0
                && recipeIndex < 0 && !anvilFocus && anvilCursor == 0
                && extended.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (!(o instanceof ScreenExtras)) { return false; }
        ScreenExtras e = (ScreenExtras) o;
        return entityId == e.entityId
                && columns == e.columns
                && selectedTab == e.selectedTab
                && Float.compare(scrollOffs, e.scrollOffs) == 0
                && searchText.equals(e.searchText)
                && currentPage == e.currentPage
                && bookPages.equals(e.bookPages)
                && bookPage == e.bookPage
                && merchantOffers.equals(e.merchantOffers)
                && renameText.equals(e.renameText)
                && java.util.Arrays.equals(enchantClues, e.enchantClues)
                && java.util.Arrays.equals(enchantLevels, e.enchantLevels)
                && experienceLevel == e.experienceLevel
                && bookTitle.equals(e.bookTitle)
                && bookAuthor.equals(e.bookAuthor)
                && Float.compare(merchantScroll, e.merchantScroll) == 0
                && selectedOffer == e.selectedOffer
                && merchantLevel == e.merchantLevel
                && merchantXp == e.merchantXp
                && recipeIndex == e.recipeIndex
                && anvilFocus == e.anvilFocus
                && anvilCursor == e.anvilCursor
                && extended.equals(e.extended);
    }

    @Override
    public int hashCode() {
        int h = Objects.hash(entityId, columns, selectedTab, scrollOffs,
                searchText, currentPage, bookPages, bookPage,
                merchantOffers, renameText, experienceLevel,
                bookTitle, bookAuthor, merchantScroll, selectedOffer,
                merchantLevel, merchantXp, recipeIndex, anvilFocus, anvilCursor,
                extended);
        h = 31 * h + java.util.Arrays.hashCode(enchantClues);
        h = 31 * h + java.util.Arrays.hashCode(enchantLevels);
        return h;
    }

    @Override
    public String toString() {
        return "ScreenExtras(entity=" + entityId + ", tab=" + selectedTab
                + ", scroll=" + scrollOffs + ", pages=" + bookPages.size()
                + ", offers=" + merchantOffers.size()
                + ", selectedOffer=" + selectedOffer
                + ", recipe=" + recipeIndex
                + ", extended=" + (extended.isEmpty() ? "none" : "yes") + ")";
    }
}
