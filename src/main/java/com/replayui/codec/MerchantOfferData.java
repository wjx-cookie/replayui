package com.replayui.codec;

import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/**
 * One villager / wandering-trader offer, reduced to plain data so the codec can
 * carry it without importing {@code MerchantOffer} at compile time.
 *
 * <p>The three stacks are the two ingredient slots and the result. The integer
 * fields drive the "out of stock" stripe, the price adjustment and the level
 * bar that {@code MerchantScreen} draws from its {@code MerchantOffers} list.
 *
 * <p>Equality is content-based, not identity-based: {@link UiStateData#sameAs}
 * compares the enclosing {@link ScreenExtras} with equals(), so an offer list
 * whose ItemStacks are fresh copies every tick must still compare equal or the
 * de-duplication would stop working for every trading screen.
 */
public final class MerchantOfferData {

    public static final MerchantOfferData EMPTY = new MerchantOfferData(
            ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY,
            0, 0, 0, 0, 0, 0.0f);

    private final ItemStack baseCostA;
    private final ItemStack costB;
    private final ItemStack result;
    private final int uses;
    private final int maxUses;
    private final int xp;
    private final int demand;
    private final int specialPrice;
    private final float priceMultiplier;

    public MerchantOfferData(ItemStack baseCostA, ItemStack costB, ItemStack result,
                             int uses, int maxUses, int xp, int demand,
                             int specialPrice, float priceMultiplier) {
        this.baseCostA = nonNull(baseCostA);
        this.costB = nonNull(costB);
        this.result = nonNull(result);
        this.uses = uses;
        this.maxUses = maxUses;
        this.xp = xp;
        this.demand = demand;
        this.specialPrice = specialPrice;
        this.priceMultiplier = priceMultiplier;
    }

    private static ItemStack nonNull(ItemStack stack) {
        return stack == null ? ItemStack.EMPTY : stack;
    }

    public ItemStack baseCostA() { return baseCostA; }
    public ItemStack costB() { return costB; }
    public ItemStack result() { return result; }
    public int uses() { return uses; }
    public int maxUses() { return maxUses; }
    public int xp() { return xp; }
    public int demand() { return demand; }
    public int specialPrice() { return specialPrice; }
    public float priceMultiplier() { return priceMultiplier; }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (!(o instanceof MerchantOfferData)) { return false; }
        MerchantOfferData e = (MerchantOfferData) o;
        return uses == e.uses
                && maxUses == e.maxUses
                && xp == e.xp
                && demand == e.demand
                && specialPrice == e.specialPrice
                && Float.compare(priceMultiplier, e.priceMultiplier) == 0
                && sameStack(baseCostA, e.baseCostA)
                && sameStack(costB, e.costB)
                && sameStack(result, e.result);
    }

    /** Same reasoning as {@link UiStateData#sameAs}: count is compared apart. */
    private static boolean sameStack(ItemStack a, ItemStack b) {
        if (a == null || b == null) { return a == b; }
        return a.getCount() == b.getCount() && ItemStack.matches(a, b);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uses, maxUses, xp, demand, specialPrice, priceMultiplier,
                baseCostA.getItem().hashCode(), baseCostA.getCount(),
                costB.getItem().hashCode(), costB.getCount(),
                result.getItem().hashCode(), result.getCount());
    }

    @Override
    public String toString() {
        return "Offer(uses=" + uses + "/" + maxUses + ", xp=" + xp
                + ", demand=" + demand + ")";
    }
}
