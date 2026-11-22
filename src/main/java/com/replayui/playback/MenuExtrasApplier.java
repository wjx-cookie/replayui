package com.replayui.playback;

import com.replayui.codec.MerchantOfferData;
import com.replayui.codec.ScreenExtras;
import com.replayui.codec.UiStateData;
import com.replayui.log.DebugLog;
import com.replayui.util.ScreenReflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Restores the menu-side state captured by
 * {@link com.replayui.record.MenuExtrasReader}.
 *
 * <p>This is the reflective half of the feature and, honestly, the one that
 * cannot be proven without a real game. Every step is a candidate list: a name
 * that changed between mappings leaves the field at its default and logs one
 * verbose line. Nothing here throws out into {@code PlaybackTicker}, so a
 * mismatch costs a blank trade column, never a broken replay.
 *
 * <p>This class does NOT touch {@code player.experienceLevel}: permanently
 * overwriting a real entity in the replay world would be a gameplay side
 * effect. The recorded level is instead borrowed for a single render call by
 * {@link RecordedXpLevel}, which is where the enchantment table's greyed-out
 * buttons are corrected. Restoring the value here would leave it stranded.
 */
public final class MenuExtrasApplier {

    private static final String[] MERCHANT_OFFERS_CLASS = {
            "net.minecraft.world.item.trading.MerchantOffers",
            "net.minecraft.class_1874"
    };
    private static final String[] MERCHANT_OFFER_CLASS = {
            "net.minecraft.world.item.trading.MerchantOffer",
            "net.minecraft.class_1875"
    };

    private static boolean appliedOnce = false;

    private MenuExtrasApplier() {
    }

    /** Called after a screen is built, and again on every content refresh. */
    public static void apply(Minecraft client, Screen screen,
                             AbstractContainerMenu menu, UiStateData data) {
        ScreenExtras ex = data.extras();
        if (ex == null || menu == null || screen == null) { return; }
        if (ex.hasNoMenuExtras()) { return; }

        if (!ex.merchantOffers().isEmpty()) {
            applyOffers(screen, menu, ex.merchantOffers());
        }
        if (!ex.renameText().isEmpty()) {
            applyRename(screen, menu, ex.renameText());
        }
        if (ex.enchantClues().length > 0 || ex.enchantLevels().length > 0) {
            applyEnchantClues(menu, ex);
        }
        applyScreenSide(screen, menu, ex);
        ExtendedSupport.applyMenuState(client, screen, menu, data);
        if (!appliedOnce) {
            appliedOnce = true;
            DebugLog.verbose("menu extras applied: offers=" + ex.merchantOffers().size()
                    + ", rename=" + ex.renameText().length() + " chars"
                    + ", clues=" + ex.enchantClues().length
                    + " (recorded xpLevel=" + ex.experienceLevel()
                    + " borrowed during render by RecordedXpLevel)");
        }
    }

    // ---------------------------------------------------------------- trades

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void applyOffers(Screen screen, AbstractContainerMenu menu,
                                    List<MerchantOfferData> recorded) {
        Class<?> offersCls = ScreenReflect.find(MERCHANT_OFFERS_CLASS);
        Class<?> offerCls = ScreenReflect.find(MERCHANT_OFFER_CLASS);
        if (offersCls == null || offerCls == null) {
            DebugLog.verbose("merchant offers class not found - trade list left blank");
            return;
        }

        Object offers;
        try {
            Constructor<?> ctor = offersCls.getDeclaredConstructor();
            ctor.setAccessible(true);
            offers = ctor.newInstance();
        } catch (Throwable t) {
            DebugLog.verbose("merchant offers could not be constructed - trade list left blank");
            return;
        }
        if (!(offers instanceof List)) { return; }

        int built = 0;
        for (MerchantOfferData d : recorded) {
            Object offer = buildOffer(offerCls, d);
            if (offer == null) { continue; }
            ((List) offers).add(offer);
            built++;
        }

        // Three increasingly indirect ways in, because the owning object moved
        // between mappings: menu.setOffers, the merchant/container behind the
        // menu, or the screen's own cached list.
        boolean ok = invokeSetOffers(menu, offers);
        if (!ok) {
            for (String holder : new String[]{"merchant", "merchantContainer",
                    "tradeContainer", "container"}) {
                Object target = ScreenReflect.get(menu, holder);
                if (target != null && invokeSetOffers(target, offers)) { ok = true; break; }
            }
        }
        if (!ok) {
            ok = ScreenReflect.set(screen, offers, "offers");
        }
        if (!ok) {
            ok = ScreenReflect.set(menu, offers, "offers");
        }

        if (ok) {
            DebugLog.verbose("merchant offers restored: " + built + "/" + recorded.size());
        } else {
            DebugLog.verbose("no setOffers target found - trade list may be blank "
                    + "(built " + built + " offers)");
        }
    }

    /** Finds a one-arg method by name whose parameter can hold the offers list. */
    private static boolean invokeSetOffers(Object target, Object offers) {
        if (target == null) { return false; }
        for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals("setOffers") || m.getParameterCount() != 1) { continue; }
                if (!m.getParameterTypes()[0].isInstance(offers)) { continue; }
                try {
                    m.setAccessible(true);
                    m.invoke(target, offers);
                    return true;
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    /**
     * MerchantOffer's constructor signature is not stable. Pick a declared
     * constructor and fill it positionally: ItemStack parameters take the three
     * stacks in order, int parameters the counters, float the multiplier.
     */
    private static Object buildOffer(Class<?> offerCls, MerchantOfferData d) {
        Constructor<?>[] all;
        try {
            all = offerCls.getDeclaredConstructors();
        } catch (Throwable t) {
            return null;
        }
        for (Constructor<?> c : all) {
            Class<?>[] types = c.getParameterTypes();
            Object[] args = new Object[types.length];
            int stackIdx = 0;
            int intIdx = 0;
            int[] ints = {d.uses(), d.maxUses(), d.xp(), d.demand(), d.specialPrice(), 0};
            boolean ok = true;
            for (int i = 0; i < types.length; i++) {
                Class<?> t = types[i];
                if (ItemStack.class.isAssignableFrom(t)) {
                    if (stackIdx >= 3) { ok = false; break; }
                    args[i] = stackIdx == 0 ? d.baseCostA()
                            : stackIdx == 1 ? d.costB() : d.result();
                    stackIdx++;
                } else if (t == int.class || t == Integer.class) {
                    args[i] = Integer.valueOf(intIdx < ints.length ? ints[intIdx++] : 0);
                } else if (t == float.class || t == Float.class) {
                    args[i] = Float.valueOf(d.priceMultiplier());
                } else if (t == boolean.class || t == Boolean.class) {
                    args[i] = Boolean.FALSE;
                } else if (!t.isPrimitive()) {
                    args[i] = null;
                } else {
                    ok = false;
                    break;
                }
            }
            if (!ok || stackIdx < 3) { continue; }
            try {
                c.setAccessible(true);
                return c.newInstance(args);
            } catch (Throwable ignored) {
                // Try the next signature.
            }
        }
        return null;
    }

    // ----------------------------------------------------------------- anvil

    private static void applyRename(Screen screen, AbstractContainerMenu menu, String text) {
        boolean menuOk = ScreenReflect.set(menu, text, "itemName", "name");
        Object box = ScreenReflect.get(screen, "name", "itemNameField", "renameField");
        boolean boxOk = false;
        if (box != null) {
            // setValue is void, so callWith returns null even on success.
            // Invoke it anyway, then verify or fall back to writing the field.
            ScreenReflect.callWith(box, new Class<?>[]{String.class},
                    new Object[]{text}, "setValue");
            boxOk = text.equals(ScreenReflect.get(box, "value"))
                    || ScreenReflect.set(box, text, "value");
        }
        if (!menuOk && !boxOk) {
            DebugLog.verbose("anvil rename field not found - box may be empty");
        }
    }

    // ------------------------------------------------------------ enchantment

    /**
     * Restores scroll / selection / tier / recipe / caret state. All of it is
     * cosmetic: a name that does not exist leaves vanilla's default and costs
     * one verbose line. Nothing here writes to the viewer's real player.
     */
    private static void applyScreenSide(Screen screen, AbstractContainerMenu menu,
                                        ScreenExtras ex) {
        if (Float.compare(ex.merchantScroll(), 0.0f) != 0) {
            ScreenReflect.set(screen, Float.valueOf(ex.merchantScroll()),
                    "scrollOffs", "scrollOffset", "tradeListScroll");
        }
        if (ex.selectedOffer() >= 0) {
            ScreenReflect.set(screen, Integer.valueOf(ex.selectedOffer()),
                    "selectedOffer", "selectedOfferIndex", "hoveredOffer");
        }
        if (ex.merchantLevel() > 0) {
            ScreenReflect.set(screen, Integer.valueOf(ex.merchantLevel()),
                    "merchantLevel", "traderLevel", "villagerLevel");
        }
        if (ex.merchantXp() > 0) {
            ScreenReflect.set(screen, Integer.valueOf(ex.merchantXp()),
                    "merchantXp", "traderXp", "villagerXp");
        }
        if (ex.recipeIndex() >= 0) {
            boolean ok = ScreenReflect.set(menu, Integer.valueOf(ex.recipeIndex()),
                    "selectedRecipeIndex", "selectedRecipe", "recipeIndex", "selectedIndex");
            if (!ok) {
                ScreenReflect.set(screen, Integer.valueOf(ex.recipeIndex()),
                        "selectedRecipeIndex", "selectedRecipe", "recipeIndex");
            }
        }
        if (!ex.bookTitle().isEmpty()) {
            ScreenReflect.set(screen, ex.bookTitle(), "bookTitle", "title");
        }
        if (!ex.bookAuthor().isEmpty()) {
            ScreenReflect.set(screen, ex.bookAuthor(), "bookAuthor", "author");
        }
        if (ex.anvilFocus() || ex.anvilCursor() > 0) {
            Object box = ScreenReflect.get(screen, "name", "itemNameField", "renameField");
            if (box != null) {
                if (ex.anvilFocus()) {
                    ScreenReflect.callWith(box, new Class<?>[]{boolean.class},
                            new Object[]{Boolean.TRUE}, "setFocus", "setFocused");
                }
                if (ex.anvilCursor() > 0) {
                    ScreenReflect.callWith(box, new Class<?>[]{int.class},
                            new Object[]{Integer.valueOf(ex.anvilCursor())},
                            "setCursorPosition", "setCursor");
                }
            }
        }
    }

    private static void applyEnchantClues(AbstractContainerMenu menu, ScreenExtras ex) {
        if (ex.enchantClues().length > 0) {
            ScreenReflect.set(menu, ex.enchantClues().clone(),
                    "enchantClue", "enchantClues");
        }
        if (ex.enchantLevels().length > 0) {
            ScreenReflect.set(menu, ex.enchantLevels().clone(),
                    "levelClue", "levelClues");
        }
    }
}
