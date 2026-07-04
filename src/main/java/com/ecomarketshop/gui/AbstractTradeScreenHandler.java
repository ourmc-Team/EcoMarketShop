package com.ecomarketshop.gui;

import com.ecomarketshop.EcoMarketShop;
import com.ecomarketshop.config.ShopItemConfig;
import com.ecomarketshop.data.ShopDataManager;
import com.ecomarketshop.util.CooldownManager;
import com.ecomarketshop.util.ItemMatcher;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * 交易 GUI 抽象基类 — 实现“点击触发 → GUI 确认 / 直接执行”流程。
 *
 * <p>核心交互流程：
 * <ol>
 *   <li>玩家左键点击商品槽位 → {@link #triggerTrade} 根据物品可堆叠性决定直接执行或打开确认 GUI</li>
 *   <li>右键点击可堆叠物品 → 打开 {@link ConfirmScreenHandler} 确认购买一组</li>
 *   <li>Shift+左键点击可堆叠物品 → 打开 {@link ShopQuantityScreenHandler} 选择数量</li>
 * </ol>
 *
 * <p>安全设计：
 * <ul>
 *   <li>所有点击在服务端 {@code onSlotClick} 中处理，不信任客户端</li>
 *   <li>冷却 CD 拦截高频操作</li>
 *   <li>交易回调（子类提供）在执行前重新从数据源获取物品，不使用客户端传来的 ItemStack</li>
 * </ul>
 *
 * <p>使用 {@link ScreenHandlerType#GENERIC_9X6}（原版 6 行箱子类型），
 * 客户端无需安装模组即可显示 GUI。
 */
public abstract class AbstractTradeScreenHandler extends ScreenHandler {

    /** GUI 尺寸（6 行 × 9 列 = 54 格） */
    public static final int GUI_SIZE = 54;

    /** 显示用库存（仅服务端使用） */
    protected final SimpleInventory displayInventory;

    protected AbstractTradeScreenHandler(ScreenHandlerType<?> type, int syncId) {
        super(type, syncId);
        this.displayInventory = new SimpleInventory(GUI_SIZE);
    }

    /**
     * 初始化 GUI 槽位布局：54 个锁定显示槽 + 36 个玩家背包槽。
     *
     * @param playerInventory 玩家背包
     */
    protected void addSlots(PlayerInventory playerInventory) {
        // ---- 54 个显示槽（6 行 × 9 列）----
        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new LockedSlot(displayInventory, col + row * 9,
                    8 + col * 18, 18 + row * 18));
            }
        }

        // ---- 27 个玩家主背包槽（3 行 × 9 列）----
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, 9 + col + row * 9,
                    8 + col * 18, 130 + row * 18));
            }
        }

        // ---- 9 个快捷栏槽 ----
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col,
                8 + col * 18, 188));
        }
    }

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType action, PlayerEntity player) {
        // === Shift+左键 → 打开数量选择 GUI（仅商店可堆叠物品）===
        // QuickMove 是 Shift+点击产生的 ActionType，button == 0 是左 Shift
        if (action == SlotActionType.QUICK_MOVE && button == 0) {
            // 仅商店支持批量购买，市场保持整单购买不变
            if (!(this instanceof ShopScreenHandler)) {
                return;
            }
            // 忽略玩家背包区域的点击（槽位 54+）
            if (slotIndex >= GUI_SIZE) {
                return;
            }
            // 冷却检查
            if (!CooldownManager.checkAndUpdate(player.getUuid())) {
                player.sendMessage(Text.literal("§c操作过快，请稍后再试！"), false);
                return;
            }
            // 校验槽位是否为合法可交易商品
            if (isValidTradeSlot(slotIndex, player)) {
                ShopItemConfig config = ShopDataManager.getItemBySlot(slotIndex);
                if (config != null) {
                    Item item = ItemMatcher.getItem(config.getId());
                    // 不可堆叠物品回退到左键逻辑（买 1）
                    if (item == null || item.getMaxCount() <= 1) {
                        triggerTrade((ServerPlayerEntity) player, slotIndex);
                    } else {
                        ShopQuantityScreenHandler.open((ServerPlayerEntity) player, slotIndex);
                    }
                }
            }
            return;
        }

        // === 右键 → 直接购买一组（仅商店可堆叠物品）===
        // PICKUP + button == 1 是右键单击
        if (action == SlotActionType.PICKUP && button == 1) {
            if (!(this instanceof ShopScreenHandler)) {
                return;
            }
            if (slotIndex >= GUI_SIZE) {
                return;
            }
            if (!CooldownManager.checkAndUpdate(player.getUuid())) {
                player.sendMessage(Text.literal("§c操作过快，请稍后再试！"), false);
                return;
            }
            if (isValidTradeSlot(slotIndex, player)) {
                ShopItemConfig config = ShopDataManager.getItemBySlot(slotIndex);
                if (config != null) {
                    Item item = ItemMatcher.getItem(config.getId());
                    // 仅可堆叠物品支持右键购买一组 → 打开确认 GUI
                    if (item != null && item.getMaxCount() > 1) {
                        ConfirmScreenHandler.openShopBulkBuy((ServerPlayerEntity) player, slotIndex);
                    }
                }
            }
            return;
        }

        // === 原有左键逻辑（买 1）===
        // 1. 只处理左键单击（PICKUP + button 0）
        if (action != SlotActionType.PICKUP || button != 0) {
            return;
        }

        // 忽略玩家背包区域的点击（槽位 54+）
        if (slotIndex >= GUI_SIZE) {
            return;
        }

        // 2. 全局冷却检查
        if (!CooldownManager.checkAndUpdate(player.getUuid())) {
            player.sendMessage(Text.literal("§c操作过快，请稍后再试！"), false);
            EcoMarketShop.LOGGER.debug("[EcoMarketShop] 冷却CD拦截: 玩家={}, 槽位={}",
                player.getName().getString(), slotIndex);
            return;
        }

        // 3. 校验槽位是否为合法可交易商品
        if (isValidTradeSlot(slotIndex, player)) {
            triggerTrade((ServerPlayerEntity) player, slotIndex);
        }
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return true;
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        // 禁止 Shift 点击移动物品
        return ItemStack.EMPTY;
    }

    @Override
    public void onClosed(PlayerEntity player) {
        super.onClosed(player);
    }

    /**
     * 获取显示库存（子类用于设置显示物品）。
     */
    protected SimpleInventory getDisplayInventory() {
        return displayInventory;
    }

    // ---- 子类必须实现的抽象方法 ----

    /**
     * 判断指定槽位是否为可交易的合法槽位。
     */
    protected abstract boolean isValidTradeSlot(int slot, PlayerEntity player);

    /**
     * 触发交易：子类根据物品可堆叠性决定直接执行或打开确认 GUI。
     *
     * @param player     玩家
     * @param slotIndex  被点击的槽位索引
     */
    protected abstract void triggerTrade(ServerPlayerEntity player, int slotIndex);

    /**
     * 刷新 GUI 显示内容（交易执行后或热重载后调用）。
     */
    protected abstract void refreshDisplay();
}
