package com.ecomarketshop.gui;

import com.ecomarketshop.config.EconomyConfig;
import com.ecomarketshop.util.CooldownManager;
import com.ecomarketshop.util.ItemValidator;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 玩家市场上架 GUI 处理器 — 由 {@code /market sell <单价>} 打开。
 *
 * <p>布局（54 格箱子）：
 * <ul>
 *   <li>槽位 0~35（4 行）：输入区，玩家可放入背包物品，支持多物品同时上架</li>
 *   <li>槽位 36~44：分隔填充</li>
 *   <li>槽位 45：取消按钮（红羊毛）</li>
 *   <li>槽位 49：确认上架按钮（绿羊毛）</li>
 *   <li>槽位 53：价格信息（纸）</li>
 *   <li>槽位 54~89：玩家背包</li>
 * </ul>
 *
 * <p>上架规则（执行上架时校验）：
 * <ul>
 *   <li>附魔等数据组件由 {@link com.ecomarketshop.util.ItemStackSerializer} 完整保留</li>
 *   <li>工具/护甲等可损坏物品仅满耐久（{@code damage==0}）可上架；耐久不全者返还玩家</li>
 * </ul>
 *
 * <p>确认流程：点击「确认上架」→ 捕获输入区物品并清空 → 打开 {@link ConfirmScreenHandler}
 * [确认] 创建挂单并返还耐久不足物品 / [取消] 全部返还。
 */
public class MarketListScreenHandler extends ScreenHandler {

    /** 输入区槽位数（前 4 行 = 36 格） */
    private static final int INPUT_SLOTS = 36;
    /** 容器总槽位 */
    private static final int CONTAINER_SIZE = 54;

    /** 按钮全局槽位索引 */
    private static final int CANCEL_SLOT = 45;
    private static final int CONFIRM_SLOT = 49;
    private static final int PRICE_INFO_SLOT = 53;

    /** 输入区库存（玩家可放入物品） */
    private final SimpleInventory inputInventory = new SimpleInventory(INPUT_SLOTS);
    /** 按钮区显示库存（锁定） */
    private final SimpleInventory displayInventory = new SimpleInventory(18);

    /** 单价（由命令参数指定） */
    private final int pricePerUnit;

    public MarketListScreenHandler(int syncId, PlayerInventory playerInventory, int pricePerUnit) {
        super(ScreenHandlerType.GENERIC_9X6, syncId);
        this.pricePerUnit = pricePerUnit;

        // ---- 输入区 0~35（4 行）----
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 9; col++) {
                int index = col + row * 9;
                this.addSlot(new Slot(inputInventory, index, 8 + col * 18, 18 + row * 18));
            }
        }

        // ---- 按钮/分隔区 36~53（2 行，锁定显示）----
        for (int row = 4; row < 6; row++) {
            for (int col = 0; col < 9; col++) {
                int displayIndex = (row - 4) * 9 + col;
                this.addSlot(new LockedSlot(displayInventory, displayIndex,
                    8 + col * 18, 18 + row * 18));
            }
        }

        // ---- 玩家主背包 27 格 ----
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, 9 + col + row * 9,
                    8 + col * 18, 130 + row * 18));
            }
        }

        // ---- 快捷栏 9 格 ----
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 188));
        }

        setupDisplay();
    }

    /**
     * 设置按钮区显示物品。
     */
    private void setupDisplay() {
        ItemStack cancel = GuiUtils.createButton(Items.RED_WOOL, "§c§l取消上架",
            List.of(Text.literal("§7点击取消上架并返还物品")));

        ItemStack confirm = GuiUtils.createButton(Items.GREEN_WOOL, "§a§l确认上架",
            List.of(
                Text.literal("§7单价: §e" + pricePerUnit + " " + EconomyConfig.getCurrencyName()),
                Text.literal("§a点击将输入区物品上架")
            ));

        ItemStack priceInfo = GuiUtils.createDetailItem("§6上架信息",
            List.of(
                Text.literal("§7设定单价: §e" + pricePerUnit + " " + EconomyConfig.getCurrencyName()),
                Text.literal("§7手续费比例: §c" +
                    ((int) Math.round(EconomyConfig.getMarketFeeRate() * 100)) + "%"),
                Text.literal("§7将上方物品放入输入区上架"),
                Text.literal("§7耐久不足的工具/护甲将返还")
            ));

        // 填充
        for (int i = 0; i < 18; i++) {
            displayInventory.setStack(i, GuiUtils.createFiller());
        }
        // 按钮对应 displayInventory 索引 = 全局槽位 - 36
        displayInventory.setStack(CANCEL_SLOT - 36, cancel);     // index 9
        displayInventory.setStack(CONFIRM_SLOT - 36, confirm);   // index 13
        displayInventory.setStack(PRICE_INFO_SLOT - 36, priceInfo); // index 17
    }

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType action, PlayerEntity player) {
        // 确认上架按钮
        if (slotIndex == CONFIRM_SLOT) {
            if (action == SlotActionType.PICKUP && button == 0
                && com.ecomarketshop.util.CooldownManager.checkAndUpdate(player.getUuid())) {
                handleConfirm((ServerPlayerEntity) player);
            }
            return;
        }
        // 取消按钮：清空输入区，由 onClosed 返还物品，玩家按 Esc 关闭 GUI
        if (slotIndex == CANCEL_SLOT) {
            if (action == SlotActionType.PICKUP && button == 0) {
                for (int i = 0; i < INPUT_SLOTS; i++) {
                    inputInventory.setStack(i, ItemStack.EMPTY);
                }
            }
            return;
        }
        // 按钮区其余槽位（含价格信息）忽略
        if (slotIndex >= INPUT_SLOTS && slotIndex < CONTAINER_SIZE) {
            return;
        }
        // 输入区与玩家背包：放行常规交互（拖放/取出/shift 移动等由 quickMove/基类处理）
        super.onSlotClick(slotIndex, button, action, player);
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasStack()) {
            return ItemStack.EMPTY;
        }
        ItemStack original = slot.getStack();
        ItemStack result = original.copy();
        if (index >= CONTAINER_SIZE) {
            // 玩家背包 -> 输入区
            if (!this.insertItem(original, 0, INPUT_SLOTS, false)) {
                return ItemStack.EMPTY;
            }
        } else if (index < INPUT_SLOTS) {
            // 输入区 -> 玩家背包
            if (!this.insertItem(original, CONTAINER_SIZE, this.slots.size(), false)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 按钮区不允许 shift 移动
            return ItemStack.EMPTY;
        }
        if (original.isEmpty()) {
            slot.setStack(ItemStack.EMPTY);
        } else {
            slot.markDirty();
        }
        return result;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return true;
    }

    @Override
    public void onClosed(PlayerEntity player) {
        // 返还输入区残余物品（Esc 关闭 / 取消按钮 / 确认后空输入均安全）
        for (int i = 0; i < INPUT_SLOTS; i++) {
            ItemStack stack = inputInventory.getStack(i);
            if (!stack.isEmpty()) {
                inputInventory.setStack(i, ItemStack.EMPTY);
                if (!player.getInventory().insertStack(stack)) {
                    player.dropItem(stack, false);
                }
            }
        }
        super.onClosed(player);
    }

    /**
     * 确认上架：捕获输入区物品 → 校验耐久 → 打开确认 GUI。
     */
    private void handleConfirm(ServerPlayerEntity player) {
        // 收集输入区所有非空物品（复制）
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < INPUT_SLOTS; i++) {
            ItemStack stack = inputInventory.getStack(i);
            if (!stack.isEmpty()) {
                items.add(stack.copy());
            }
        }
        if (items.isEmpty()) {
            player.sendMessage(Text.literal("§c上架区没有物品！"), false);
            return;
        }
        // 清空输入区（物品已捕获到 items，避免 onClosed 重复返还）
        for (int i = 0; i < INPUT_SLOTS; i++) {
            inputInventory.setStack(i, ItemStack.EMPTY);
        }

        // 按耐久规则分类
        List<ItemStack> listable = new ArrayList<>();
        List<ItemStack> returns = new ArrayList<>();
        for (ItemStack stack : items) {
            if (ItemValidator.isListable(stack)) {
                listable.add(stack);
            } else {
                returns.add(stack);
            }
        }

        // 打开确认 GUI（由 ConfirmScreenHandler 处理上架/返还逻辑）
        ConfirmScreenHandler.openMarketSell(player, pricePerUnit, listable, returns, items);
    }
}
