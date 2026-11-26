package com.replayui.record;

import com.replayui.codec.MerchantOfferData;
import com.replayui.codec.ScreenExtras;
import com.replayui.log.DebugLog;
import com.replayui.util.ScreenReflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads the state that ordinary container menus keep in fields rather than in
 * slots or {@code ContainerData}, so replaying those screens shows more than
 * an empty shell.
 *
 * <p>Three cases:
 * <ul>
 *   <li><b>MerchantScreen</b> - the trade list is delivered by a dedicated
 *       packet ({@code ClientboundMerchantOffersPacket}), never as slots. Without
 *       this the screen opens, the two input slots and the result slot line up,
 *       and the whole offer column is blank.</li>
 *   <li><b>AnvilScreen</b> - the rename box text is a String field on the menu,
 *       not a slot. Without it the box comes back empty.</li>
 *   <li><b>EnchantmentScreen</b> - the three rune clues and level hints are
 *       int[] fields; the costs already travel through {@code dataSlots}, but
 *       the clue glyphs do not.</li>
 * </ul>
 *
 * <p>Everything is read reflectively by candidate names, exactly like
 * {@link SpecialScreenReader}. A mapping change leaves a default and one verbose
 * line - it never throws into {@code UiSampler} and never disables UI sampling
 * for the session.
 */
public final class MenuExtrasReader {

    /** Corrupt / malicious menu must not turn into an unbounded allocation. */
    public static final int MAX_MERCHANT_OFFERS = 64;

    private static final String[] MERCHANT_MENU = {
            "net.minecraft.world.inventory.MerchantMenu",
            "net.minecraft.class_1723"
    };
    private static final String[] ANVIL_MENU = {
            "net.minecraft.world.inventory.AnvilMenu",
            "net.minecraft.class_1707"
    };
    private static final String[] ENCHANT_MENU = {
            "net.minecraft.world.inventory.EnchantmentMenu",
            "net.minecraft.class_1714"
    };

    /** Crafting-station menus whose selected recipe is a plain int field. */
    private static final String[] RECIPE_MENU_HINTS = {
            "StonecutterMenu", "LoomMenu", "CartographyTableMenu", "SmithingMenu"
    };
    private static final String[] MERCHANT_SCREEN_HINTS = {
            "MerchantScreen", "TradingScreen"
    };
    private static final String[] ANVIL_SCREEN_HINTS = {
            "AnvilScreen"
    };

    private static boolean reportOnce = false;

    private MenuExtrasReader() {
    }

    /**
     * Build the extra-state block for a normal container screen. Always returns
     * non-null, so callers can pass it straight into {@code UiStateData}.
     */
    public static ScreenExtras read(Minecraft client, Screen screen,
                                    AbstractContainerMenu menu) {
        List<MerchantOfferData> offers = Collections.emptyList();
        String renameText = "";
        int[] enchantClues = new int[0];
        int[] enchantLevels = new int[0];
        int experienceLevel = -1;
        float merchantScroll = 0.0f;
        int selectedOffer = -1;
        int merchantLevel = 0;
        int merchantXp = 0;
        int recipeIndex = -1;
        boolean anvilFocus = false;
        int anvilCursor = 0;
        String bookTitle = "";
        String bookAuthor = "";

        if (menu == null) {
            return new ScreenExtras(-1, 0, -1, 0.0f, "", 0, null, 0,
                    offers, renameText, enchantClues, enchantLevels, experienceLevel,
                    bookTitle, bookAuthor, merchantScroll, selectedOffer,
                    merchantLevel, merchantXp, recipeIndex, anvilFocus, anvilCursor);
        }

        if (isA(menu, MERCHANT_MENU, "MerchantMenu")) {
            offers = readOffers(menu);
        }
        if (isA(menu, ANVIL_MENU, "AnvilMenu")) {
            renameText = readString(menu, new String[]{"itemName", "name"},
                    new String[]{"getItemName"});
            if (renameText.isEmpty()) {
                Object shaped = ScreenReflect.getByType(menu, String.class);
                renameText = shaped instanceof String ? (String) shaped : "";
            }
        }
        if (isA(menu, ENCHANT_MENU, "EnchantmentMenu")) {
            enchantClues = readIntArray(menu, new String[]{"enchantClue", "enchantClues"});
            enchantLevels = readIntArray(menu, new String[]{"levelClue", "levelClues"});
            if (enchantClues.length == 0 || enchantLevels.length == 0) {
                List<Object> arrays = ScreenReflect.fieldsByType(menu, int[].class);
                if (enchantClues.length == 0 && arrays.size() > 0
                        && arrays.get(0) instanceof int[]) {
                    enchantClues = ((int[]) arrays.get(0)).clone();
                }
                if (enchantLevels.length == 0 && arrays.size() > 1
                        && arrays.get(1) instanceof int[]) {
                    enchantLevels = ((int[]) arrays.get(1)).clone();
                }
            }
        }

        // Screen-side state. The screen instance used to be passed in but
        // ignored; these fields live on the Screen, not the Menu.
        if (screen != null) {
            if (isAny(screen, MERCHANT_SCREEN_HINTS)) {
                Object scroll = ScreenReflect.get(screen,
                        "scrollOffs", "scrollOffset", "tradeListScroll");
                if (scroll instanceof Number) {
                    merchantScroll = ((Number) scroll).floatValue();
                }
                selectedOffer = readInt(screen,
                        new String[]{"selectedOffer", "selectedOfferIndex", "hoveredOffer"},
                        new String[]{"getSelectedOffer"});
                if (selectedOffer < 0) { selectedOffer = -1; }
                merchantLevel = readInt(screen,
                        new String[]{"merchantLevel", "traderLevel", "villagerLevel"},
                        new String[0]);
                merchantXp = readInt(screen,
                        new String[]{"merchantXp", "traderXp", "villagerXp"},
                        new String[0]);
            }
            if (isAny(screen, ANVIL_SCREEN_HINTS)) {
                Object box = ScreenReflect.get(screen, "name", "itemNameField", "renameField");
                if (box != null) {
                    Object f = ScreenReflect.call(box, "isFocused", "isFocus");
                    anvilFocus = f instanceof Boolean && (Boolean) f;
                    anvilCursor = readInt(box,
                            new String[]{"cursorPos", "cursor", "caret"},
                            new String[]{"getCursorPosition"});
                }
            }
            // Book metadata is read by SpecialScreenReader for lecterns; a
            // plain BookViewScreen reached as a container still gets it here.
            Object title = ScreenReflect.get(screen, "bookTitle", "title");
            if (title instanceof String) { bookTitle = (String) title; }
            Object author = ScreenReflect.get(screen, "bookAuthor", "author");
            if (author instanceof String) { bookAuthor = (String) author; }
        }

        if (isAny(menu, RECIPE_MENU_HINTS)) {
            recipeIndex = readInt(menu,
                    new String[]{"selectedRecipeIndex", "selectedRecipe", "recipeIndex",
                            "selectedIndex"},
                    new String[]{"getSelectedRecipeIndex", "getSelectedRecipe"});
            if (recipeIndex < 0) {
                List<Object> shaped = ScreenReflect.fieldsByType(menu, int.class);
                Object first = shaped.isEmpty() ? null : shaped.get(0);
                recipeIndex = first instanceof Number
                        ? ((Number) first).intValue() : -1;
            }
        }

        // Recorded for the greyed-out "not enough levels" state on the
        // enchantment table. Not written onto the viewer's player here: the
        // playback side borrows it for one render call in RecordedXpLevel and
        // restores it immediately, so no real entity keeps a foreign value.
        if (client != null && client.player != null) {
            // Compile-time field reference: Loom remaps it, unlike a string.
            // No mapping name, no reflection, and javac verifies it exists.
            experienceLevel = client.player.experienceLevel;
        }

        if (!reportOnce) {
            reportOnce = true;
            DebugLog.verbose("menu extras reader: offers=" + offers.size()
                    + ", rename=" + renameText.length() + " chars"
                    + ", clues=" + enchantClues.length
                    + ", xpLevel=" + experienceLevel);
        }

        return new ScreenExtras(-1, 0, -1, 0.0f, "", 0, null, 0,
                offers, renameText, enchantClues, enchantLevels, experienceLevel,
                bookTitle, bookAuthor, merchantScroll, selectedOffer,
                merchantLevel, merchantXp, recipeIndex, anvilFocus, anvilCursor);
    }

    /** Simple-name fallback for screens whose mapped class name is unknown. */
    private static boolean isAny(Object value, String[] hints) {
        if (value == null) { return false; }
        for (Class<?> k = value.getClass(); k != null; k = k.getSuperclass()) {
            String simple = k.getSimpleName();
            for (String hint : hints) {
                if (simple.contains(hint)) { return true; }
            }
        }
        return false;
    }

    /** Class-name fallback matters: obfuscated/intermediary names vary. */
    private static boolean isA(Object value, String[] classNames, String simpleHint) {
        if (value == null) { return false; }
        Class<?> cls = ScreenReflect.find(classNames);
        if (cls != null && cls.isInstance(value)) { return true; }
        for (Class<?> k = value.getClass(); k != null; k = k.getSuperclass()) {
            if (k.getSimpleName().contains(simpleHint)) { return true; }
        }
        return false;
    }

    private static List<MerchantOfferData> readOffers(AbstractContainerMenu menu) {
        Object offers = ScreenReflect.call(menu, "getOffers");
        if (offers == null) {
            // Production mappings rename the method. Find it by shape instead:
            // the only no-arg method on a merchant menu returning an Iterable.
            offers = ScreenReflect.callReturningAssignable(menu, Iterable.class);
        }
        if (!(offers instanceof Iterable<?> iterable)) {
            return Collections.emptyList();
        }
        List<MerchantOfferData> out = new ArrayList<MerchantOfferData>();
        for (Object offer : iterable) {
            if (offer == null) { continue; }
            if (out.size() >= MAX_MERCHANT_OFFERS) {
                DebugLog.verbose("merchant offer list truncated at " + MAX_MERCHANT_OFFERS);
                break;
            }
            out.add(readOffer(offer));
        }
        return out.isEmpty() ? Collections.<MerchantOfferData>emptyList() : out;
    }

    private static MerchantOfferData readOffer(Object offer) {
        // Production mappings rename every field. Names above are tried first
        // (dev workspace); when they all miss, fall back to the field SHAPE:
        // a MerchantOffer carries three ItemStack fields in declaration order,
        // then its int fields, then a float multiplier. No name, no number.
        List<Object> stacks = ScreenReflect.fieldsByType(offer, ItemStack.class);
        ItemStack shapeA = at(stacks, 0, ItemStack.EMPTY);
        ItemStack shapeB = at(stacks, 1, ItemStack.EMPTY);
        ItemStack shapeR = at(stacks, 2, ItemStack.EMPTY);
        List<Object> ints = ScreenReflect.fieldsByType(offer, int.class);
        List<Object> floats = ScreenReflect.fieldsByType(offer, float.class);
        ItemStack costA = readStack(offer,
                new String[]{"baseCostA", "costA", "itemCostA"},
                new String[]{"getBaseCostA", "getCostA"}, shapeA);
        ItemStack costB = readStack(offer,
                new String[]{"costB", "itemCostB"},
                new String[]{"getCostB"}, shapeB);
        ItemStack result = readStack(offer,
                new String[]{"result", "resultStack"},
                new String[]{"getResult"}, shapeR);
        int uses = readInt(offer, new String[]{"uses"}, new String[]{"getUses"}, ints, 0);
        int maxUses = readInt(offer, new String[]{"maxUses"}, new String[]{"getMaxUses"}, ints, 1);
        int xp = readInt(offer, new String[]{"xp"}, new String[]{"getXp"}, ints, 2);
        int demand = readInt(offer, new String[]{"demand"}, new String[]{"getDemand"}, ints, 3);
        int specialPrice = readInt(offer,
                new String[]{"specialPriceDiff", "specialPrice"},
                new String[]{"getSpecialPriceDiff"}, ints, 4);
        float multiplier = readFloat(offer,
                new String[]{"priceMultiplier"},
                new String[]{"getPriceMultiplier"}, floats);
        return new MerchantOfferData(costA, costB, result, uses, maxUses, xp,
                demand, specialPrice, multiplier);
    }

    private static ItemStack readStack(Object target, String[] fields, String[] getters,
                                       ItemStack fallback) {
        Object v = first(target, fields, getters);
        return v instanceof ItemStack ? (ItemStack) v : fallback;
    }

    private static String readString(Object target, String[] fields, String[] getters) {
        Object v = first(target, fields, getters);
        return v instanceof String ? (String) v : "";
    }

    private static int readInt(Object target, String[] fields, String[] getters) {
        return readInt(target, fields, getters, null, -1);
    }

    private static int readInt(Object target, String[] fields, String[] getters,
                               List<Object> shaped, int index) {
        Object v = first(target, fields, getters);
        if (v instanceof Number) { return ((Number) v).intValue(); }
        if (shaped == null) { return 0; }
        return at(shaped, index, Integer.valueOf(0)).intValue();
    }

    private static int[] readIntArray(Object target, String[] fields) {
        Object v = ScreenReflect.get(target, fields);
        return v instanceof int[] ? ((int[]) v).clone() : new int[0];
    }

    private static float readFloat(Object target, String[] fields, String[] getters,
                                   List<Object> shaped) {
        Object v = first(target, fields, getters);
        if (v instanceof Number) { return ((Number) v).floatValue(); }
        return at(shaped, 0, Float.valueOf(0.0f)).floatValue();
    }

    private static <T> T at(List<Object> values, int index, T fallback) {
        if (index < 0 || index >= values.size()) { return fallback; }
        Object v = values.get(index);
        return fallback.getClass().isInstance(v) ? (T) v : fallback;
    }

    /** Fields first, then no-arg getters - both mapping styles are seen. */
    private static Object first(Object target, String[] fields, String[] getters) {
        Object v = ScreenReflect.get(target, fields);
        if (v != null) { return v; }
        return ScreenReflect.call(target, getters);
    }
}
