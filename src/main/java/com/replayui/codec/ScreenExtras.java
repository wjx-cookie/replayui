package com.replayui.codec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * State that only exists on screens vanilla does not open through the normal
 * MenuType path: the creative inventory, a horse/llama inventory and a lectern
 * book. Kept as plain data - no Minecraft types - so the codec can carry it and
 * the playback side can restore it without importing any of those classes.
 *
 * <p>Every value is best effort: the fields are read reflectively by candidate
 * names, and a name that changed between mappings simply leaves the default.
 * That is a cosmetic downgrade for these three screens, never a crash.
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

    public ScreenExtras(int entityId, int columns, int selectedTab, float scrollOffs,
                        String searchText, int currentPage,
                        List<String> bookPages, int bookPage) {
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
    }

    public int entityId() { return entityId; }
    public int columns() { return columns; }
    public int selectedTab() { return selectedTab; }
    public float scrollOffs() { return scrollOffs; }
    public String searchText() { return searchText; }
    public int currentPage() { return currentPage; }
    public List<String> bookPages() { return bookPages; }
    public int bookPage() { return bookPage; }

    /** True when this sample belongs to a horse/llama inventory. */
    public boolean hasEntity() { return entityId >= 0; }

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
                && bookPage == e.bookPage;
    }

    @Override
    public int hashCode() {
        return Objects.hash(entityId, columns, selectedTab, scrollOffs,
                searchText, currentPage, bookPages, bookPage);
    }

    @Override
    public String toString() {
        return "ScreenExtras(entity=" + entityId + ", tab=" + selectedTab
                + ", scroll=" + scrollOffs + ", pages=" + bookPages.size() + ")";
    }
}
