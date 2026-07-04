package com.ecomarketshop.gui;

import com.ecomarketshop.config.EconomyConfig;
import com.ecomarketshop.data.EconomyDataManager;
import com.ecomarketshop.data.MarketDataManager;
import com.ecomarketshop.market.MarketListing;
import com.ecomarketshop.trade.MarketTradeService;
import com.ecomarketshop.util.CooldownManager;
import com.ecomarketshop.util.ConfirmationManager.PendingConfirmation;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 全球拍卖行（市场）GUI 处理器。
 *
 * <p>显示当前页的挂单列表，支持翻页导航。玩家点击挂单后关闭 GUI 并在聊天框中
 * 通过 [确认]/[取消] 完成整单购买（实际交易由 {@link MarketTradeService} 按 ID 执行）。
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
public class MarketScreenHandler extends AbstractTradeScreenHandler {

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

    /**
     * 构造市场 GUI。
     *
     * @param syncId          同步 ID
     * @param playerInventory 玩家背包
     */
    public MarketScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ScreenHandlerType.GENERIC_9X6, syncId);
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
            int totalPages = Math.max(1, MarketDataManager.getTotalPages());
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

        // 交给父类处理：校验合法槽位后触发 triggerTrade（聊天确认）
        super.onSlotClick(slotIndex, button, action, player);
    }

    @Override
    protected boolean isValidTradeSlot(int slot, PlayerEntity player) {
        if (slot < 0 || slot >= TRADE_SLOTS) {
            return false;
        }
        MarketListing listing = MarketDataManager.getListingByPageIndex(slot, currentPage);
        return listing != null && listing.getAmount() > 0;
    }

    @Override
    protected void triggerTrade(ServerPlayerEntity player, int slotIndex) {
        // 从数据源获取挂单以展示详情（实际交易在确认时由 MarketTradeService 按 ID 二次校验）
        MarketListing listing = MarketDataManager.getListingByPageIndex(slotIndex, currentPage);
        if (listing == null || listing.getAmount() <= 0) {
            player.sendMessage(Text.literal("§c该挂单已不存在！"), false);
            return;
        }
        final String listingId = listing.getId();
        int totalCost = listing.getTotalCost();
        int fee = listing.getFee();
        int feePercent = (int) Math.round(EconomyConfig.getMarketFeeRate() * 100);
        ItemStack sample = listing.getItemStack();
        String itemName = (sample != null && !sample.isEmpty())
            ? sample.getName().getString() : listing.getItemId();

        MutableText desc = Text.literal("");
        desc.append(Text.literal("§7类型: §f全球市场购买\n"));
        desc.append(Text.literal("§7物品: §f" + listing.getAmount() + "x " + itemName + "\n"));
        desc.append(Text.literal("§7卖家: §f" + listing.getSellerName() + "\n"));
        desc.append(Text.literal("§7总价: §e" + totalCost + " " + EconomyConfig.getCurrencyName() + "\n"));
        desc.append(Text.literal("§7手续费: §c" + fee + " (" + feePercent + "%)\n"));
        desc.append(Text.literal("§7当前余额: §e" + EconomyDataManager.getBalance(player.getUuid())
            + " " + EconomyConfig.getCurrencyName()));

        PendingConfirmation pc = new PendingConfirmation(
            desc,
            () -> MarketTradeService.executePurchase(player, listingId),
            null
        );
        requestConfirmation(player, pc);
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
        List<MarketListing> pageListings = MarketDataManager.getPageListings(currentPage);
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
        int totalPages = Math.max(1, MarketDataManager.getTotalPages());

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
        infoLore.add(Text.literal("§7共 " + MarketDataManager.getTotalListingCount() + " 条挂单"));
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
     * 为挂单创建显示物品（带卖家、价格等 Lore）。
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
        lore.add(Text.literal("§7卖家: §e" + listing.getSellerName()));
        lore.add(Text.literal("§7单价: §e" + listing.getPricePerUnit() + " " +
            EconomyConfig.getCurrencyName()));
        lore.add(Text.literal("§7总价: §e" + listing.getTotalCost() + " " +
            EconomyConfig.getCurrencyName()));
        lore.add(Text.literal("§7手续费: §c" + listing.getFee() + " (" + feePercent + "%)"));
        lore.add(Text.literal("§a左键整单购买"));
        display.set(DataComponentTypes.LORE, new LoreComponent(lore));

        return display;
    }
}
