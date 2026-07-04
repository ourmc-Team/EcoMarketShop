package com.ecomarketshop.util;

import net.minecraft.item.ItemStack;

/**
 * 上架物品校验工具 — 根据需求约束玩家在市场上架的物品。
 *
 * <p>规则：
 * <ul>
 *   <li>附魔等数据组件由 {@link ItemStackSerializer} 完整保留，无需在此校验。</li>
 *   <li>工具类/护甲类等可损坏物品（{@link net.minecraft.item.Item#isDamageable()}），
 *       仅允许耐久度为 100%（即 {@code damage == 0}）的物品上架。</li>
 *   <li>耐久度不全的物品将在执行上架操作时直接返还玩家背包。</li>
 * </ul>
 */
public final class ItemValidator {

    private ItemValidator() {}

    /**
     * 判断物品是否可上架（满足耐久度规则）。
     *
     * @param stack 物品堆叠
     * @return true 表示可上架；false 表示因耐久不足需返还
     */
    public static boolean isListable(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        // 非可损坏物品（普通材料/方块等）恒可上架
        if (!stack.isDamageable()) {
            return true;
        }
        // 可损坏物品（工具/护甲）要求满耐久：damage == 0
        return stack.getDamage() == 0;
    }

    /**
     * 判断物品是否因耐久不足而不可上架。
     *
     * @param stack 物品堆叠
     * @return true 表示耐久不全，需返还玩家
     */
    public static boolean isDamaged(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.isDamageable() && stack.getDamage() > 0;
    }
}
