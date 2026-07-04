package com.ecomarketshop.gui;

import com.ecomarketshop.config.EconomyConfig;
import com.ecomarketshop.data.MarketDataManager;
import com.ecomarketshop.market.MarketListing;
import com.ecomarketshop.util.CooldownManager;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 我的挂单 GUI 处理器 — 显示当前玩家在市场上的所有挂单，支持翻页和下架。
 *
 * <p>由 {@code /market my} 命令打开。玩家左键点击挂单后打开 {@link ConfirmScreenHandler}
 * 确认下架（实际下架由 {@link com.ecomarketshop.trade.MarketTradeService#executeDelist} 执行）。
 *
 * <p>布局（54 格箱子）：
 * <ul>
 *   <li>槽位 0~44：挂单显示区（每页最多 45 条）</li>
 *   <li>槽位 45：上一页导航</li>
 *   <li>槽位 49：页码信息</li>
 *   <li>槽位 53：下一页导航</li>
 *   <li>槽位 46~48, 50~52：填充物</li>
 * </ul>
 */
public class MarketMyListingsScreenHandler extends AbstractTradeScreenHandler {

    /** 交易区域槽位数（前 45 格） */
    private static final int TRADE_SLOTS = 45;

    /** 导航槽位索引 */
    private static final int NAV_PREV_SLOT = 45;
    private static final int NAV_INFO_SLOT = 49;
    private static final int NAV_NEXT_SLOT = 53;

    /** 填充物品 */
    private static final ItemStack FILLER;

    static {
        FILLER = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        FILLER.set(DataComponentTypes.CUSTOM_NAME, Text.literal(" "));
    }

    /** 当前页码（0-based） */
    private int currentPage = 0;

    /** 卖家 UUID（即打开 GUI 的玩家） */
    private final UUID sellerUuid;

    /**
     * 构造我的挂单 GUI。
     *
     * @param syncId          同步 ID
     * @param playerInventory 玩家背包
     * @param sellerUuid      卖家 UUID
     */
    public MarketMyListingsScreenHandler(int syncId, PlayerInventory playerInventory, UUID sellerUuid) {
        super(ScreenHandlerType.GENERIC_9X6, syncId);
        this.sellerUuid = sellerUuid;
        addSlots(playerInventory);
        populateDisplay();
    }

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType action, PlayerEntity player) {
        // 只处理左键单击
        if (action != SlotActionType.PICKUP || button != 0) {
            return;
        }

        // 处理导航按钮
        if (slotIndex == NAV_PREV_SLOT) {
            if (currentPage > 0 && CooldownManager.checkAndUpdate(player.getUuid())) {
                currentPage--;
                refreshDisplay();
            }
            return;
        }
        if (slotIndex == NAV_NEXT_SLOT) {
            int totalPages = Math.max(1, MarketDataManager.getTotalPagesBySeller(sellerUuid));
            if (currentPage < totalPages - 1 && CooldownManager.checkAndUpdate(player.getUuid())) {
                currentPage++;
                refreshDisplay();
            }
            return;
        }

        // 导航栏其他槽位忽略
        if (slotIndex >= TRADE_SLOTS && slotIndex < GUI_SIZE) {
            return;
        }

        // 交给父类处理：校验合法槽位后触发 triggerTrade
        super.onSlotClick(slotIndex, button, action, player);
    }

    @Override
    protected boolean isValidTradeSlot(int slot, PlayerEntity player) {
        if (slot < 0 || slot >= TRADE_SLOTS) {
            return false;
        }
        MarketListing listing = MarketDataManager.getListingBySellerPageIndex(sellerUuid, slot, currentPage);
        return listing != null && listing.getAmount() > 0;
    }

    @Override
    protected void triggerTrade(ServerPlayerEntity player, int slotIndex) {
        MarketListing listing = MarketDataManager.getListingBySellerPageIndex(sellerUuid, slotIndex, currentPage);
        if (listing == null || listing.getAmount() <= 0) {
            player.sendMessage(Text.literal("§c该挂单已不存在！"), false);
            return;
        }
        ConfirmScreenHandler.openMarketDelist(player, listing);
    }

    @Override
    protected void refreshDisplay() {
        populateDisplay();
        sendContentUpdates();
    }

    /**
     * 填充 GUI 显示内容：当前页挂单 + 导航栏。
     */
    private void populateDisplay() {
        // 清空显示
        for (int i = 0; i < GUI_SIZE; i++) {
            getDisplayInventory().setStack(i, ItemStack.EMPTY);
        }

        // 放置当前页挂单
        List<MarketListing> pageListings = MarketDataManager.getPageListingsBySeller(sellerUuid, currentPage);
        for (int i = 0; i < TRADE_SLOTS && i < pageListings.size(); i++) {
            MarketListing listing = pageListings.get(i);
            getDisplayInventory().setStack(i, createListingDisplayStack(listing));
        }

        // 用填充物填满空交易槽
        for (int i = pageListings.size(); i < TRADE_SLOTS; i++) {
            getDisplayInventory().setStack(i, FILLER.copy());
        }

        // 设置导航栏
        setupNavigation();
    }

    /**
     * 设置底部导航栏。
     */
    private void setupNavigation() {
        int totalPages = Math.max(1, MarketDataManager.getTotalPagesBySeller(sellerUuid));
        int totalCount = MarketDataManager.getTotalListingCountBySeller(sellerUuid);

        // 上一页按钮
        if (currentPage > 0) {
            ItemStack prev = new ItemStack(Items.ARROW);
            prev.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§e← 上一页"));
            getDisplayInventory().setStack(NAV_PREV_SLOT, prev);
        } else {
            getDisplayInventory().setStack(NAV_PREV_SLOT, FILLER.copy());
        }

        // 页码信息
        ItemStack info = new ItemStack(Items.PAPER);
        info.set(DataComponentTypes.CUSTOM_NAME,
            Text.literal("§6第 " + (currentPage + 1) + " / " + totalPages + " 页"));
        List<Text> infoLore = new ArrayList<>();
        infoLore.add(Text.literal("§7我的挂单: " + totalCount + " 条"));
        info.set(DataComponentTypes.LORE, new LoreComponent(infoLore));
        getDisplayInventory().setStack(NAV_INFO_SLOT, info);

        // 下一页按钮
        if (currentPage < totalPages - 1) {
            ItemStack next = new ItemStack(Items.ARROW);
            next.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§e下一页 →"));
            getDisplayInventory().setStack(NAV_NEXT_SLOT, next);
        } else {
            getDisplayInventory().setStack(NAV_NEXT_SLOT, FILLER.copy());
        }

        // 填充导航栏剩余槽位
        for (int i = TRADE_SLOTS; i < GUI_SIZE; i++) {
            if (i != NAV_PREV_SLOT && i != NAV_INFO_SLOT && i != NAV_NEXT_SLOT) {
                getDisplayInventory().setStack(i, FILLER.copy());
            }
        }
    }

    /**
     * 为挂单创建显示物品（带价格等 Lore，提示左键下架）。
     */
    private ItemStack createListingDisplayStack(MarketListing listing) {
        ItemStack display = listing.getItemStack();
        if (display == null || display.isEmpty()) {
            return FILLER.copy();
        }

        display = display.copy();
        // 设置自定义名称
        display.set(DataComponentTypes.CUSTOM_NAME,
            Text.literal("§f" + listing.getAmount() + "x " +
                display.getName().getString()));

        int feePercent = (int) Math.round(EconomyConfig.getMarketFeeRate() * 100);
        List<Text> lore = new ArrayList<>();
        lore.add(Text.literal("§7单价: §e" + listing.getPricePerUnit() + " " +
            EconomyConfig.getCurrencyName()));
        lore.add(Text.literal("§7总价: §e" + listing.getTotalCost() + " " +
            EconomyConfig.getCurrencyName()));
        lore.add(Text.literal("§7手续费: §c" + listing.getFee() + " (" + feePercent + "%)"));
        lore.add(Text.literal("§c左键下架"));
        display.set(DataComponentTypes.LORE, new LoreComponent(lore));

        return display;
    }
}
