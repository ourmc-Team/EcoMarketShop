package com.ecomarketshop.gui;

import com.ecomarketshop.config.EconomyConfig;
import com.ecomarketshop.config.ShopItemConfig;
import com.ecomarketshop.data.EconomyDataManager;
import com.ecomarketshop.data.ShopDataManager;
import com.ecomarketshop.trade.ShopTradeService;
import com.ecomarketshop.util.CooldownManager;
import com.ecomarketshop.util.ItemMatcher;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;
import java.util.UUID;

/**
 * 商店批量购买数量选择 GUI 处理器。
 *
 * <p>当玩家在商店 GUI 中 Shift+左键点击可堆叠商品时弹出，允许玩家
 * 选择购买数量（1~maxQuantity），确认后由 {@link ShopTradeService} 执行批量购买。
 *
 * <p>使用原版 {@link ScreenHandlerType#GENERIC_9X3}（27 格），客户端无需安装模组。
 *
 * <p>布局（3 行 × 9 列，黑色玻璃板边框）：
 * <ul>
 *   <li>Row 0：边框 + 物品图标 + 名称 + 单价 + 库存 + 填充 + 边框</li>
 *   <li>Row 1：边框 + [-16] + [-1] + 当前数量 + [+1] + [+16] + 填充 + [MAX] + 边框</li>
 *   <li>Row 2：边框 + 填充 + 取消 + 总价 + 确认 + 填充 + 边框</li>
 * </ul>
 *
 * <p>安全设计：
 * <ul>
 *   <li>所有计算在服务端进行，不信任客户端</li>
 *   <li>maxQuantity 以 item.getMaxCount() 为硬上限</li>
 *   <li>确认时由 ShopTradeService 重新校验库存和余额</li>
 * </ul>
 */
public class ShopQuantityScreenHandler extends ScreenHandler {

    private static final int ROWS = 3;
    private static final int COLS = 9;
    private static final int GUI_SIZE = ROWS * COLS; // 27

    // ---- Row 0: 物品信息区槽位 ----
    private static final int SLOT_ITEM_ICON = 1;
    private static final int SLOT_ITEM_NAME = 2;
    private static final int SLOT_ITEM_PRICE = 3;
    private static final int SLOT_ITEM_STOCK = 4;
    // slot 0, 8: 边框;  slot 5-7: 填充物

    // ---- Row 1: 数量调节区槽位 ----
    private static final int SLOT_DEC_16 = 10;
    private static final int SLOT_DEC_1 = 11;
    private static final int SLOT_QTY_DISPLAY = 12;
    private static final int SLOT_INC_1 = 13;
    private static final int SLOT_INC_16 = 14;
    // slot 15: 填充物
    private static final int SLOT_MAX = 16;
    // slot 9, 17: 边框

    // ---- Row 2: 操作区槽位 ----
    private static final int SLOT_CANCEL = 21;
    private static final int SLOT_TOTAL_PRICE = 22;
    private static final int SLOT_CONFIRM = 23;
    // slot 18, 26: 边框;  slot 19-20, 24-25: 填充物

    /** 按钮类型枚举 */
    private enum QuantityButtonType {
        DEC_16, DEC_1, INC_1, INC_16, MAX, CONFIRM, CANCEL, NONE
    }

    /** 显示用库存（仅服务端使用） */
    private final SimpleInventory displayInventory;

    /** 商店商品槽位索引 */
    private final int shopSlotIndex;

    /** 玩家 UUID（用于查询余额） */
    private final UUID playerUuid;

    /** 当前选中的数量 */
    private int quantity;

    /** 最大可购买数量（动态计算） */
    private final int maxQuantity;

    // 填充物和边框统一使用 GuiUtils 创建

    /**
     * 构造数量选择 GUI。
     *
     * @param syncId          同步 ID
     * @param playerInventory 玩家背包
     * @param shopSlotIndex   商店商品槽位索引
     */
    public ShopQuantityScreenHandler(int syncId, PlayerInventory playerInventory, int shopSlotIndex) {
        super(ScreenHandlerType.GENERIC_9X3, syncId);
        this.displayInventory = new SimpleInventory(GUI_SIZE);
        this.shopSlotIndex = shopSlotIndex;
        this.playerUuid = playerInventory.player.getUuid();

        // 计算 maxQuantity（仅服务端执行，客户端使用 vanilla 渲染器）
        if (playerInventory.player instanceof ServerPlayerEntity) {
            this.maxQuantity = calculateMaxQuantity((ServerPlayerEntity) playerInventory.player);
        } else {
            this.maxQuantity = 64; // 客户端默认值，显示内容由服务端同步
        }

        // 初始数量为 1（若 maxQuantity < 1 则为 0）
        this.quantity = (this.maxQuantity >= 1) ? 1 : 0;

        addSlots(playerInventory);
        populateDisplay();
    }

    /**
     * 打开数量选择 GUI。
     *
     * @param player        玩家
     * @param shopSlotIndex 商店商品槽位索引
     */
    public static void open(ServerPlayerEntity player, int shopSlotIndex) {
        player.openHandledScreen(new NamedScreenHandlerFactory() {
            @Override
            public ScreenHandler createMenu(int syncId, PlayerInventory inv, PlayerEntity p) {
                return new ShopQuantityScreenHandler(syncId, inv, shopSlotIndex);
            }

            @Override
            public Text getDisplayName() {
                return Text.literal("§6选择数量");
            }
        });
    }

    /**
     * 初始化 GUI 槽位布局：27 个锁定显示槽 + 36 个玩家背包槽。
     *
     * @param playerInventory 玩家背包
     */
    private void addSlots(PlayerInventory playerInventory) {
        // ---- 27 个显示槽（3 行 × 9 列）----
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                this.addSlot(new LockedSlot(displayInventory, col + row * 9,
                    8 + col * 18, 18 + row * 18));
            }
        }

        // ---- 27 个玩家主背包槽（3 行 × 9 列）----
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, 9 + col + row * 9,
                    8 + col * 18, 85 + row * 18));
            }
        }

        // ---- 9 个快捷栏槽 ----
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col,
                8 + col * 18, 143));
        }
    }

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType action, PlayerEntity player) {
        // 只处理左键单击（PICKUP + button 0）
        if (action != SlotActionType.PICKUP || button != 0) {
            return;
        }

        // 忽略玩家背包区域的点击（槽位 27+）
        if (slotIndex < 0 || slotIndex >= GUI_SIZE) {
            return;
        }

        // 全局冷却检查
        if (!CooldownManager.checkAndUpdate(player.getUuid())) {
            player.sendMessage(Text.literal("§c操作过快，请稍后再试！"), false);
            return;
        }

        switch (getButtonType(slotIndex)) {
            case DEC_16  -> { adjustQuantity(-16); refreshDisplay(); }
            case DEC_1   -> { adjustQuantity(-1);  refreshDisplay(); }
            case INC_1   -> { adjustQuantity(+1);  refreshDisplay(); }
            case INC_16  -> { adjustQuantity(+16); refreshDisplay(); }
            case MAX     -> { setToMax();          refreshDisplay(); }
            case CONFIRM -> confirmPurchase((ServerPlayerEntity) player);
            case CANCEL  -> cancelPurchase((ServerPlayerEntity) player);
            case NONE    -> { /* 展示槽位，无操作 */ }
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

    // ---- 核心逻辑方法 ----

    /**
     * 获取槽位对应的按钮类型。
     */
    private QuantityButtonType getButtonType(int slot) {
        return switch (slot) {
            case SLOT_DEC_16 -> QuantityButtonType.DEC_16;
            case SLOT_DEC_1  -> QuantityButtonType.DEC_1;
            case SLOT_INC_1  -> QuantityButtonType.INC_1;
            case SLOT_INC_16 -> QuantityButtonType.INC_16;
            case SLOT_MAX    -> QuantityButtonType.MAX;
            case SLOT_CONFIRM -> QuantityButtonType.CONFIRM;
            case SLOT_CANCEL  -> QuantityButtonType.CANCEL;
            default          -> QuantityButtonType.NONE;
        };
    }

    /**
     * 调整数量，钳制在 [1, maxQuantity] 范围。
     *
     * @param delta 变化量（正数增加，负数减少）
     */
    private void adjustQuantity(int delta) {
        if (maxQuantity <= 0) return;
        quantity = Math.max(1, Math.min(quantity + delta, maxQuantity));
    }

    /**
     * 将数量设为 maxQuantity。
     */
    private void setToMax() {
        if (maxQuantity <= 0) return;
        quantity = maxQuantity;
    }

    /**
     * 确认购买：关闭 GUI 并调用 {@link ShopTradeService} 执行批量购买。
     *
     * @param player 玩家
     */
    private void confirmPurchase(ServerPlayerEntity player) {
        if (quantity > 0) {
            ShopTradeService.executePurchase(player, shopSlotIndex, quantity);
        } else {
            player.sendMessage(Text.literal("§c数量不足，无法购买！"), false);
        }
        player.closeHandledScreen();
    }

    /**
     * 取消购买：关闭 GUI，不做任何操作。
     *
     * @param player 玩家
     */
    private void cancelPurchase(ServerPlayerEntity player) {
        player.closeHandledScreen();
    }

    /**
     * 刷新 GUI 显示并同步到客户端。
     */
    private void refreshDisplay() {
        populateDisplay();
        sendContentUpdates();
    }

    /**
     * 计算最大可购买数量。
     *
     * <p>取以下三项的最小值：
     * <ul>
     *   <li>物品最大堆叠数（{@code item.getMaxCount()}）</li>
     *   <li>玩家余额可购买的数量</li>
     *   <li>库存剩余数量（无限库存则取 maxStack）</li>
     * </ul>
     *
     * @param player 玩家
     * @return 最大可购买数量，0 表示无法购买
     */
    private int calculateMaxQuantity(ServerPlayerEntity player) {
        ShopItemConfig config = ShopDataManager.getItemBySlot(shopSlotIndex);
        if (config == null) return 0;

        Item item = ItemMatcher.getItem(config.getId());
        if (item == null) return 0;

        int maxStack = item.getMaxCount();

        // 余额限制（price 为 0 时不限）
        int price = config.getPrice();
        int maxAffordable = (price > 0)
            ? EconomyDataManager.getBalance(player.getUuid()) / price
            : maxStack;

        // 库存限制
        int maxStock = config.isInfiniteStock()
            ? maxStack
            : Math.min(config.getStock(), maxStack);

        return Math.min(maxStack, Math.min(maxAffordable, maxStock));
    }

    // ---- 显示方法 ----

    /**
     * 填充 GUI 显示内容：物品信息 + 数量调节 + 操作区。
     */
    private void populateDisplay() {
        // 清空显示
        for (int i = 0; i < GUI_SIZE; i++) {
            displayInventory.setStack(i, ItemStack.EMPTY);
        }

        ShopItemConfig config = ShopDataManager.getItemBySlot(shopSlotIndex);

        if (config == null) {
            // 商品不存在
            fillAll();
            displayInventory.setStack(SLOT_QTY_DISPLAY, GuiUtils.createButton(Items.BARRIER, "§c商品不存在"));
            return;
        }

        Item item = ItemMatcher.getItem(config.getId());
        String displayName = config.getDisplayName() != null ? config.getDisplayName() : config.getId();
        int price = config.getPrice();

        // ---- 边框（黑色玻璃板）----
        displayInventory.setStack(0, GuiUtils.createBorder());
        displayInventory.setStack(8, GuiUtils.createBorder());
        displayInventory.setStack(9, GuiUtils.createBorder());
        displayInventory.setStack(17, GuiUtils.createBorder());
        displayInventory.setStack(18, GuiUtils.createBorder());
        displayInventory.setStack(26, GuiUtils.createBorder());

        // ---- Row 0: 物品信息区 ----
        if (item != null) {
            ItemStack icon = GuiUtils.createInfoItem(item, "§f" + displayName,
                List.of(Text.literal("§7单价: §e" + price + " " + EconomyConfig.getCurrencyName())));
            displayInventory.setStack(SLOT_ITEM_ICON, icon);
        } else {
            displayInventory.setStack(SLOT_ITEM_ICON, GuiUtils.createFiller());
        }

        setInfoStack(SLOT_ITEM_NAME, "§f物品: " + displayName);
        setInfoStack(SLOT_ITEM_PRICE, "§7单价: §e" + price + " " + EconomyConfig.getCurrencyName());

        if (config.isInfiniteStock()) {
            setInfoStack(SLOT_ITEM_STOCK, "§7库存: §a无限");
        } else {
            setInfoStack(SLOT_ITEM_STOCK, "§7库存: §e" + config.getStock());
        }

        for (int i = 5; i <= 7; i++) {
            displayInventory.setStack(i, GuiUtils.createFiller());
        }

        // ---- Row 1: 数量调节区 ----
        displayInventory.setStack(SLOT_DEC_16, createButtonStack(Items.SPECTRAL_ARROW, "§c[-16]"));
        displayInventory.setStack(SLOT_DEC_1, createButtonStack(Items.ARROW, "§c[-1]"));
        displayInventory.setStack(SLOT_QTY_DISPLAY, createQuantityDisplay());
        displayInventory.setStack(SLOT_INC_1, createButtonStack(Items.ARROW, "§a[+1]"));
        displayInventory.setStack(SLOT_INC_16, createButtonStack(Items.SPECTRAL_ARROW, "§a[+16]"));
        displayInventory.setStack(15, GuiUtils.createFiller());
        displayInventory.setStack(SLOT_MAX, createButtonStack(Items.EXPERIENCE_BOTTLE, "§e[MAX]"));

        // ---- Row 2: 操作区 ----
        displayInventory.setStack(19, GuiUtils.createFiller());
        displayInventory.setStack(20, GuiUtils.createFiller());
        displayInventory.setStack(SLOT_CANCEL, GuiUtils.createCancelButton());
        displayInventory.setStack(SLOT_TOTAL_PRICE, createTotalPriceDisplay(config));
        displayInventory.setStack(SLOT_CONFIRM, GuiUtils.createConfirmButton());
        displayInventory.setStack(24, GuiUtils.createFiller());
        displayInventory.setStack(25, GuiUtils.createFiller());
    }

    /** 用填充物填满所有槽位 */
    private void fillAll() {
        for (int i = 0; i < GUI_SIZE; i++) {
            displayInventory.setStack(i, GuiUtils.createFiller());
        }
    }

    /** 创建信息展示物品（PAPER） */
    private void setInfoStack(int slot, String name) {
        displayInventory.setStack(slot, GuiUtils.createInfoItem(name));
    }

    /** 创建按钮物品 */
    private ItemStack createButtonStack(Item item, String name) {
        return GuiUtils.createButton(item, name);
    }

    /** 创建数量展示物品 */
    private ItemStack createQuantityDisplay() {
        if (maxQuantity <= 0) {
            return GuiUtils.createDetailItem("§c无法购买",
                List.of(Text.literal("§7余额或库存不足")));
        }
        return GuiUtils.createDetailItem("§e当前数量: §f" + quantity,
            List.of(Text.literal("§7范围: 1 ~ " + maxQuantity)));
    }

    /** 创建总价展示物品 */
    private ItemStack createTotalPriceDisplay(ShopItemConfig config) {
        int total = config.getPrice() * quantity;
        return GuiUtils.createDetailItem("§6总价: §e" + total + " " + EconomyConfig.getCurrencyName(),
            List.of(Text.literal("§7当前余额: §e" + EconomyDataManager.getBalance(playerUuid)
                + " " + EconomyConfig.getCurrencyName())));
    }
}
