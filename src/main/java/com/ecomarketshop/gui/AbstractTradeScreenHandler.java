package com.ecomarketshop.gui;

import com.ecomarketshop.EcoMarketShop;
import com.ecomarketshop.util.CooldownManager;
import com.ecomarketshop.util.ConfirmationManager;
import com.ecomarketshop.util.ConfirmationManager.PendingConfirmation;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * 交易 GUI 抽象基类 — 实现"点击触发 → 聊天框确认"流程。
 *
 * <p>核心交互流程（替代原两步点击确认）：
 * <ol>
 *   <li>玩家左键点击商品槽位 → {@link #triggerTrade} 构建操作详情与回调</li>
 *   <li>调用 {@link #requestConfirmation}：经 {@link ConfirmationManager} 发送聊天
 *       [确认]/[取消] 按钮，并关闭 GUI（Minecraft 中 GUI 打开时无法点击聊天）</li>
 *   <li>玩家在聊天框点击 [确认] → 执行交易回调；点击 [取消] → 取消</li>
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

    /**
     * 发起聊天框确认：记录待确认操作、发送可点击消息，并关闭 GUI。
     *
     * @param player 玩家
     * @param pc     待确认上下文（详情 + onConfirm/onCancel）
     */
    protected void requestConfirmation(ServerPlayerEntity player, PendingConfirmation pc) {
        ConfirmationManager.startPending(player, pc);
        player.closeHandledScreen();
    }

    // ---- 子类必须实现的抽象方法 ----

    /**
     * 判断指定槽位是否为可交易的合法槽位。
     */
    protected abstract boolean isValidTradeSlot(int slot, PlayerEntity player);

    /**
     * 触发交易：子类构建操作详情与 onConfirm/onCancel 回调，并调用
     * {@link #requestConfirmation} 发起聊天确认。
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
