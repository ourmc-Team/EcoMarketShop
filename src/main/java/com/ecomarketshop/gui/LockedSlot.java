package com.ecomarketshop.gui;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;

/**
 * 锁定槽位 — 防止玩家向 GUI 中放入或取出物品。
 *
 * <p>所有交易逻辑在 {@code onSlotClick} 中处理，
 * 槽位仅用于显示，不允许直接操作物品。
 */
public class LockedSlot extends Slot {

    public LockedSlot(Inventory inventory, int index, int x, int y) {
        super(inventory, index, x, y);
    }

    @Override
    public boolean canInsert(ItemStack stack) {
        return false;
    }

    @Override
    public boolean canTakeItems(PlayerEntity player) {
        return false;
    }
}
