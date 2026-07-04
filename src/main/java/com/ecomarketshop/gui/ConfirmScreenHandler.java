package com.ecomarketshop.gui;

import com.ecomarketshop.EcoMarketShop;
import com.ecomarketshop.config.EconomyConfig;
import com.ecomarketshop.config.ShopItemConfig;
import com.ecomarketshop.data.EconomyDataManager;
import com.ecomarketshop.data.MarketDataManager;
import com.ecomarketshop.data.ShopDataManager;
import com.ecomarketshop.market.MarketListing;
import com.ecomarketshop.trade.MarketTradeService;
import com.ecomarketshop.trade.ShopTradeService;
import com.ecomarketshop.util.ItemMatcher;
import com.ecomarketshop.util.TradeLogger;
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

import java.util.ArrayList;
import java.util.List;

/**
 * 通用 GUI 确认界面 — 替代原聊天栏确认。
 *
 * <p>使用 ScreenHandlerType.GENERIC_9X3（27 格），客户端无需安装模组。
 *
 * <p>布局（3 行 × 9 列）：
 * <ul>
 *   <li>Row 0：边框 + 物品图标 + 6 个信息条目 + 边框</li>
 *   <li>Row 1：边框 + 填充 + 3 份详情展示（带 Lore） + 填充 + 边框</li>
 *   <li>Row 2：边框 + 填充 + 取消按钮 + 填充 + 确认按钮 + 填充 + 边框</li>
 * </ul>
 *
 * <p>通过静态工厂方法创建不同场景的确认界面：
 * <ul>
 *   <li>{@link #openShopBuy} — 管理员商店不可堆叠物品左键购买确认</li>
 *   <li>{@link #openShopBulkBuy} — 管理员商店右键购买一组确认</li>
 *   <li>{@link #openMarketBuy} — 市场购买确认</li>
 *   <li>{@link #openMarketSell} — 市场上架确认</li>
 *   <li>{@link #openMarketDelist} — 市场下架确认</li>
 * </ul>
 */
public class ConfirmScreenHandler extends ScreenHandler {

    private static final int ROWS = 3;
    private static final int COLS = 9;
    private static final int GUI_SIZE = ROWS * COLS; // 27

    // ---- 槽位常量 ----
    // Row 0: 边框 + 物品图标 + 信息条目 + 边框
    private static final int SLOT_ITEM_ICON = 1;
    private static final int SLOT_INFO_START = 2;
    private static final int SLOT_INFO_END = 7;           // 6 个信息槽 (2~7)
    // slot 0, 8: 边框
    // Row 1: 边框 + 填充 + 详情展示 + 填充 + 边框
    private static final int SLOT_DETAIL_START = 12;
    private static final int SLOT_DETAIL_END = 14;         // 3 份详情副本 (12~14)
    // slot 9, 17: 边框;  slot 10-11, 15-16: 填充物
    // Row 2: 边框 + 填充 + 取消 + 填充 + 确认 + 填充 + 边框
    private static final int SLOT_CANCEL = 21;
    private static final int SLOT_CONFIRM = 23;
    // slot 18, 26: 边框;  slot 19-20, 22, 24-25: 填充物

    /** 显示用库存（仅服务端使用） */
    private final SimpleInventory displayInventory;

    /** 确认回调 */
    private final Runnable onConfirm;

    /** 取消回调 */
    private final Runnable onCancel;

    /** 是否已确认（防止 onClosed 重复执行 onCancel） */
    private boolean isConfirmed = false;

    // 填充物和边框统一使用 GuiUtils 创建

    /**
     * 私有构造，填充 GUI 布局。
     */
    private ConfirmScreenHandler(int syncId, PlayerInventory playerInventory,
                                  ItemStack icon, List<ItemStack> infoItems,
                                  Text detailText,
                                  Runnable onConfirm, Runnable onCancel) {
        super(ScreenHandlerType.GENERIC_9X3, syncId);
        this.displayInventory = new SimpleInventory(GUI_SIZE);
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;

        // ---- 27 个锁定显示槽 ----
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                this.addSlot(new LockedSlot(displayInventory, col + row * 9,
                    8 + col * 18, 18 + row * 18));
            }
        }

        // ---- 27 个玩家主背包槽 ----
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

        // 填充显示内容
        populateDisplay(icon, infoItems, detailText);
    }

    // ==== 静态工厂方法 ====

    /**
     * 商店左键不可堆叠物品购买确认。
     */
    public static void openShopBuy(ServerPlayerEntity player, int slotIndex) {
        ShopItemConfig config = ShopDataManager.getItemBySlot(slotIndex);
        if (config == null || config.getStock() == 0) return;

        Item item = ItemMatcher.getItem(config.getId());
        if (item == null) return;

        String name = config.getDisplayName() != null ? config.getDisplayName() : config.getId();
        int price = config.getPrice();
        int balance = EconomyDataManager.getBalance(player.getUuid());

        List<ItemStack> infoItems = List.of(
            GuiUtils.createInfoItem("§f物品: " + name),
            GuiUtils.createInfoItem("§7数量: §e1"),
            GuiUtils.createInfoItem("§7价格: §e" + price + " " + EconomyConfig.getCurrencyName()),
            GuiUtils.createInfoItem("§7余额: §e" + balance + " " + EconomyConfig.getCurrencyName())
        );

        Text detailText = Text.literal("")
            .append(Text.literal("§7类型: §f管理员商店购买\n"))
            .append(Text.literal("§7商品: §f" + name + "\n"))
            .append(Text.literal("§7价格: §e" + price + " " + EconomyConfig.getCurrencyName() + "\n"))
            .append(Text.literal("§7确认后将直接从余额中扣除 " + price + " " + EconomyConfig.getCurrencyName()));

        ItemStack icon = new ItemStack(item);
        Runnable onConfirm = () -> ShopTradeService.executePurchase(player, slotIndex);

        openConfirmGui(player, icon, infoItems, detailText, onConfirm, null);
    }

    /**
     * 商店右键购买一组确认。
     */
    public static void openShopBulkBuy(ServerPlayerEntity player, int slotIndex) {
        ShopItemConfig config = ShopDataManager.getItemBySlot(slotIndex);
        if (config == null || config.getStock() == 0) return;

        Item item = ItemMatcher.getItem(config.getId());
        if (item == null || item.getMaxCount() <= 1) return; // 仅可堆叠

        // 计算可购买数量（同 executeBulkPurchase 逻辑）
        int maxStack = item.getMaxCount();
        int price = config.getPrice();
        int maxAffordable = (price > 0)
            ? EconomyDataManager.getBalance(player.getUuid()) / price
            : maxStack;
        int maxStock = config.isInfiniteStock()
            ? maxStack
            : Math.min(config.getStock(), maxStack);
        int quantity = Math.min(maxStack, Math.min(maxAffordable, maxStock));

        if (quantity <= 0) {
            player.sendMessage(Text.literal("§c余额不足！"), false);
            return;
        }

        int totalPrice = price * quantity;
        int balance = EconomyDataManager.getBalance(player.getUuid());
        String name = config.getDisplayName() != null ? config.getDisplayName() : config.getId();

        List<ItemStack> infoItems = List.of(
            GuiUtils.createInfoItem("§f物品: " + name),
            GuiUtils.createInfoItem("§7数量: §e" + quantity),
            GuiUtils.createInfoItem("§7单价: §e" + price + " " + EconomyConfig.getCurrencyName()),
            GuiUtils.createInfoItem("§7总价: §e" + totalPrice + " " + EconomyConfig.getCurrencyName()),
            GuiUtils.createInfoItem("§7余额: §e" + balance + " " + EconomyConfig.getCurrencyName())
        );

        Text detailText = Text.literal("")
            .append(Text.literal("§7类型: §f管理员商店批量购买\n"))
            .append(Text.literal("§7商品: §f" + name + "\n"))
            .append(Text.literal("§7数量: §e" + quantity + "\n"))
            .append(Text.literal("§7总价: §e" + totalPrice + " " + EconomyConfig.getCurrencyName() + "\n"))
            .append(Text.literal("§7确认后将直接从余额中扣除 " + totalPrice + " " + EconomyConfig.getCurrencyName()));

        ItemStack icon = new ItemStack(item, Math.min(quantity, maxStack));
        Runnable onConfirm = () -> ShopTradeService.executePurchase(player, slotIndex, quantity);

        openConfirmGui(player, icon, infoItems, detailText, onConfirm, null);
    }

    /**
     * 市场购买确认。
     */
    public static void openMarketBuy(ServerPlayerEntity player, MarketListing listing) {
        String itemName = listing.getItemStack() != null
            ? listing.getItemStack().getName().getString()
            : listing.getItemId();
        int totalCost = listing.getTotalCost();
        int fee = listing.getFee();
        int feePercent = (int) Math.round(EconomyConfig.getMarketFeeRate() * 100);
        int balance = EconomyDataManager.getBalance(player.getUuid());

        List<ItemStack> infoItems = new ArrayList<>();
        infoItems.add(GuiUtils.createInfoItem("§f物品: " + listing.getAmount() + "x " + itemName));
        infoItems.add(GuiUtils.createInfoItem("§7卖家: §e" + listing.getSellerName()));
        infoItems.add(GuiUtils.createInfoItem("§7总价: §e" + totalCost + " " + EconomyConfig.getCurrencyName()));
        infoItems.add(GuiUtils.createInfoItem("§7手续费: §c" + fee + " (" + feePercent + "%)"));
        infoItems.add(GuiUtils.createInfoItem("§7余额: §e" + balance + " " + EconomyConfig.getCurrencyName()));

        Text detailText = Text.literal("")
            .append(Text.literal("§7类型: §f全球市场购买\n"))
            .append(Text.literal("§7物品: §f" + listing.getAmount() + "x " + itemName + "\n"))
            .append(Text.literal("§7卖家: §f" + listing.getSellerName() + "\n"))
            .append(Text.literal("§7总价: §e" + totalCost + " " + EconomyConfig.getCurrencyName() + "\n"))
            .append(Text.literal("§7手续费: §c" + fee + " (" + feePercent + "%)\n"))
            .append(Text.literal("§7确认后将直接从余额中扣除 " + totalCost + " " + EconomyConfig.getCurrencyName()));

        ItemStack icon = listing.getItemStack();
        if (icon == null || icon.isEmpty()) {
            icon = new ItemStack(Items.PAPER);
        } else {
            icon = icon.copy();
        }

        final String listingId = listing.getId();
        Runnable onConfirm = () -> MarketTradeService.executePurchase(player, listingId);

        openConfirmGui(player, icon, infoItems, detailText, onConfirm, null);
    }

    /**
     * 市场下架确认 — 卖家从市场收回自己的挂单。
     */
    public static void openMarketDelist(ServerPlayerEntity player, MarketListing listing) {
        String itemName = listing.getItemStack() != null
            ? listing.getItemStack().getName().getString()
            : listing.getItemId();
        int totalCost = listing.getTotalCost();
        int fee = listing.getFee();
        int feePercent = (int) Math.round(EconomyConfig.getMarketFeeRate() * 100);

        List<ItemStack> infoItems = new ArrayList<>();
        infoItems.add(GuiUtils.createInfoItem("§f物品: " + listing.getAmount() + "x " + itemName));
        infoItems.add(GuiUtils.createInfoItem("§7单价: §e" + listing.getPricePerUnit() + " " + EconomyConfig.getCurrencyName()));
        infoItems.add(GuiUtils.createInfoItem("§7总价: §e" + totalCost + " " + EconomyConfig.getCurrencyName()));
        infoItems.add(GuiUtils.createInfoItem("§7手续费: §c" + fee + " (" + feePercent + "%)"));

        Text detailText = Text.literal("")
            .append(Text.literal("§7类型: §f市场下架\n"))
            .append(Text.literal("§7物品: §f" + listing.getAmount() + "x " + itemName + "\n"))
            .append(Text.literal("§7单价: §e" + listing.getPricePerUnit() + " " + EconomyConfig.getCurrencyName() + "\n"))
            .append(Text.literal("§7总价: §e" + totalCost + " " + EconomyConfig.getCurrencyName() + "\n"))
            .append(Text.literal("§7确认后物品将返还到你的背包"));

        ItemStack icon = listing.getItemStack();
        if (icon == null || icon.isEmpty()) {
            icon = new ItemStack(Items.PAPER);
        } else {
            icon = icon.copy();
        }

        final String listingId = listing.getId();
        Runnable onConfirm = () -> MarketTradeService.executeDelist(player, listingId);

        openConfirmGui(player, icon, infoItems, detailText, onConfirm, null);
    }

    /**
     * 市场上架确认。
     */
    public static void openMarketSell(ServerPlayerEntity player, int pricePerUnit,
                                       List<ItemStack> listable, List<ItemStack> returns,
                                       List<ItemStack> allItems) {
        // 构建展示信息
        List<ItemStack> infoItems = new ArrayList<>();
        infoItems.add(GuiUtils.createInfoItem("§7类型: §f市场上架"));
        infoItems.add(GuiUtils.createInfoItem("§7单价: §e" + pricePerUnit + " " + EconomyConfig.getCurrencyName()));
        infoItems.add(GuiUtils.createInfoItem("§7可上架: §f" + listable.size() + " 组"));
        int n = 1;
        for (ItemStack s : listable) {
            if (infoItems.size() >= 6) break;
            infoItems.add(GuiUtils.createInfoItem("§7  " + n + ". §f" + s.getCount() + "x " + s.getName().getString()));
            n++;
        }
        if (!returns.isEmpty() && infoItems.size() < 6) {
            infoItems.add(GuiUtils.createInfoItem("§c耐久不足返还: §f" + returns.size() + " 组"));
        }

        // 详情文本
        StringBuilder detailBuilder = new StringBuilder();
        detailBuilder.append("§7类型: §f市场上架\n");
        detailBuilder.append("§7单价: §e").append(pricePerUnit).append(" ").append(EconomyConfig.getCurrencyName()).append("\n");
        detailBuilder.append("§7可上架: §f").append(listable.size()).append(" 组\n");
        int idx = 1;
        for (ItemStack s : listable) {
            detailBuilder.append("§7  ").append(idx).append(". §f").append(s.getCount()).append("x ")
                .append(s.getName().getString()).append("\n");
            idx++;
        }
        if (!returns.isEmpty()) {
            detailBuilder.append("§c耐久不足将返还: §f").append(returns.size()).append(" 组\n");
            int r = 1;
            for (ItemStack s : returns) {
                detailBuilder.append("§c  ").append(r).append(". §f").append(s.getCount()).append("x ")
                    .append(s.getName().getString()).append("\n");
                r++;
            }
        }
        detailBuilder.append("§7成交时按单价×数量计算，并扣除手续费。");
        Text detailText = Text.literal(detailBuilder.toString());

        // 图标：取第一个可上架物品
        ItemStack icon;
        if (!listable.isEmpty()) {
            icon = listable.get(0).copy();
        } else {
            icon = new ItemStack(Items.PAPER);
        }

        Runnable onConfirm = () -> {
            int created = 0;
            for (ItemStack s : listable) {
                MarketListing listing = new MarketListing(
                    player.getUuid(), player.getName().getString(), s, pricePerUnit);
                MarketDataManager.addListing(listing);
                created++;
            }
            // 返还不可上架物品
            for (ItemStack s : returns) {
                if (!player.getInventory().insertStack(s)) {
                    player.dropItem(s, false);
                }
            }
            player.sendMessage(Text.literal("§a上架成功！共 " + created + " 组物品已挂单。"), false);
            TradeLogger.log(player.getName().getString(), "MARKET_LIST", "-", created, 0, 0);
        };

        Runnable onCancel = () -> {
            for (ItemStack s : allItems) {
                if (!player.getInventory().insertStack(s)) {
                    player.dropItem(s, false);
                }
            }
            player.sendMessage(Text.literal("§7上架已取消，物品已返还。"), false);
        };

        openConfirmGui(player, icon, infoItems, detailText, onConfirm, onCancel);
    }

    // ==== 核心方法 ====

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType action, PlayerEntity player) {
        // 只处理左键单击
        if (action != SlotActionType.PICKUP || button != 0) {
            return;
        }

        // 忽略玩家背包区域
        if (slotIndex < 0 || slotIndex >= GUI_SIZE) {
            return;
        }

        if (slotIndex == SLOT_CONFIRM) {
            confirm(player);
        } else if (slotIndex == SLOT_CANCEL) {
            cancel(player);
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
        if (!isConfirmed && onCancel != null) {
            safeRun(onCancel);
        }
        super.onClosed(player);
    }

    /**
     * 执行确认：设置标记 → 执行 onConfirm → 关闭 GUI。
     */
    private void confirm(PlayerEntity player) {
        isConfirmed = true;
        safeRun(onConfirm);
        ((ServerPlayerEntity) player).closeHandledScreen();
    }

    /**
     * 执行取消：执行 onCancel → 关闭 GUI。
     */
    private void cancel(PlayerEntity player) {
        safeRun(onCancel);
        ((ServerPlayerEntity) player).closeHandledScreen();
    }

    // ==== 内部辅助方法 ====

    /**
     * 打开确认 GUI 的通用入口。
     */
    private static void openConfirmGui(ServerPlayerEntity player, ItemStack icon,
                                        List<ItemStack> infoItems, Text detailText,
                                        Runnable onConfirm, Runnable onCancel) {
        player.openHandledScreen(new NamedScreenHandlerFactory() {
            @Override
            public ScreenHandler createMenu(int syncId, PlayerInventory inv, PlayerEntity p) {
                return new ConfirmScreenHandler(syncId, inv, icon, infoItems, detailText,
                    onConfirm, onCancel);
            }

            @Override
            public Text getDisplayName() {
                return Text.literal("§6确认操作");
            }
        });
    }

    /**
     * 填充所有显示槽位。
     *
     * <p>布局：黑色玻璃板边框 + 灰色填充 + 图标/信息/详情/按钮。
     */
    private void populateDisplay(ItemStack icon, List<ItemStack> infoItems, Text detailText) {
        // 清空
        for (int i = 0; i < GUI_SIZE; i++) {
            displayInventory.setStack(i, ItemStack.EMPTY);
        }

        // ---- 边框（黑色玻璃板）----
        displayInventory.setStack(0, GuiUtils.createBorder());
        displayInventory.setStack(8, GuiUtils.createBorder());
        displayInventory.setStack(9, GuiUtils.createBorder());
        displayInventory.setStack(17, GuiUtils.createBorder());
        displayInventory.setStack(18, GuiUtils.createBorder());
        displayInventory.setStack(26, GuiUtils.createBorder());

        // ---- Row 0: 物品图标 + 信息条目 ----
        displayInventory.setStack(SLOT_ITEM_ICON, icon != null ? icon : GuiUtils.createFiller());

        for (int i = SLOT_INFO_START; i <= SLOT_INFO_END; i++) {
            int infoIdx = i - SLOT_INFO_START;
            if (infoIdx < infoItems.size()) {
                displayInventory.setStack(i, infoItems.get(infoIdx));
            } else {
                displayInventory.setStack(i, GuiUtils.createFiller());
            }
        }

        // ---- Row 1: 详情说明区 ----
        displayInventory.setStack(10, GuiUtils.createFiller());
        displayInventory.setStack(11, GuiUtils.createFiller());

        // Slots 12-14: 详情展示（3 份副本方便悬停查看）
        ItemStack detailPaper = createDetailPaper(detailText);
        for (int i = SLOT_DETAIL_START; i <= SLOT_DETAIL_END; i++) {
            displayInventory.setStack(i, detailPaper.copy());
        }

        displayInventory.setStack(15, GuiUtils.createFiller());
        displayInventory.setStack(16, GuiUtils.createFiller());

        // ---- Row 2: 操作按钮区 ----
        displayInventory.setStack(19, GuiUtils.createFiller());
        displayInventory.setStack(20, GuiUtils.createFiller());
        displayInventory.setStack(SLOT_CANCEL, GuiUtils.createCancelButton());
        displayInventory.setStack(22, GuiUtils.createFiller());
        displayInventory.setStack(SLOT_CONFIRM, GuiUtils.createConfirmButton());
        displayInventory.setStack(24, GuiUtils.createFiller());
        displayInventory.setStack(25, GuiUtils.createFiller());
    }

    /**
     * 创建详情展示物品（纸张 + 多行 Lore）。
     * 若 detailText 为空则返回填充物。
     */
    private static ItemStack createDetailPaper(Text detailText) {
        List<Text> lore = new ArrayList<>();
        if (detailText != null) {
            String raw = detailText.getString();
            for (String line : raw.split("\n")) {
                if (!line.isEmpty()) {
                    lore.add(Text.literal(line));
                }
            }
        }
        if (lore.isEmpty()) {
            return GuiUtils.createFiller();
        }
        return GuiUtils.createDetailItem("§e操作详情", lore);
    }

    /**
     * 安全执行回调，捕获异常并记录。
     */
    private static void safeRun(Runnable r) {
        if (r == null) return;
        try {
            r.run();
        } catch (Exception e) {
            EcoMarketShop.LOGGER.error("[EcoMarketShop] 确认界面回调执行异常", e);
        }
    }
}
