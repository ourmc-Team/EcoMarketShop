package com.ecomarketshop.trade;

import com.ecomarketshop.EcoMarketShop;
import com.ecomarketshop.api.MarketTradeCallback;
import com.ecomarketshop.config.EconomyConfig;
import com.ecomarketshop.data.EconomyDataManager;
import com.ecomarketshop.data.MarketDataManager;
import com.ecomarketshop.market.MarketListing;
import com.ecomarketshop.util.TradeLogger;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * 市场购买交易执行服务 — 从原 MarketScreenHandler.executeTrade 抽取。
 *
 * <p>在聊天框 [确认] 时调用，通过挂单 ID 定位（GUI 关闭后页码易失真）。
 *
 * <p>流程：
 * <ol>
 *   <li>从 MarketDataManager 按 ID 重新获取挂单（防篡改）</li>
 *   <li>计算总价和卖家收入（按配置手续费比例）</li>
 *   <li>{@code synchronized} 原子操作：扣买家余额 → 加卖家余额 → 给物品（含附魔） → 移除挂单</li>
 * </ol>
 */
public final class MarketTradeService {

    private MarketTradeService() {}

    /**
     * 执行市场挂单购买（整单）。
     *
     * @param player    买家
     * @param listingId 挂单 ID
     */
    public static void executePurchase(ServerPlayerEntity player, String listingId) {
        // 从数据源重新获取挂单（不信任客户端数据）
        MarketListing listing = MarketDataManager.getListingById(listingId);
        if (listing == null || listing.getAmount() <= 0) {
            player.sendMessage(Text.literal("§c该挂单已不存在！"));
            return;
        }

        int totalCost = listing.getTotalCost();
        int sellerRevenue = listing.getSellerRevenue();
        int fee = listing.getFee();

        String buyerName;

        synchronized (MarketDataManager.class) {
            // 二次检查：挂单可能已被其他玩家买走
            listing = MarketDataManager.getListingById(listingId);
            if (listing == null || listing.getAmount() <= 0) {
                player.sendMessage(Text.literal("§c该挂单已被其他玩家购买！"));
                return;
            }

            // 原子性检查并扣减余额（解决 TOCTOU 竞态）
            if (!EconomyDataManager.tryDeduct(player.getUuid(), totalCost)) {
                player.sendMessage(Text.literal("§c余额不足！需要: " + totalCost + " " +
                    EconomyConfig.getCurrencyName()));
                return;
            }

            // 加卖家余额
            EconomyDataManager.addBalance(listing.getSellerUuid(), sellerRevenue);

            // 给买家物品（含附魔等完整数据，背包满则掉落）
            ItemStack item = listing.getItemStack();
            if (item != null) {
                if (!player.getInventory().insertStack(item)) {
                    player.dropItem(item, false);
                    EcoMarketShop.LOGGER.info(
                        "[EcoMarketShop] 玩家 {} 背包已满，市场购买物品 {} x{} 掉落至地面",
                        player.getName().getString(), listing.getItemId(), listing.getAmount());
                }
            }

            // 移除挂单（整单通吃）
            MarketDataManager.removeListing(listing.getId());

            buyerName = player.getName().getString();
        }

        // ---- IO 操作移到同步块外 ----
        MarketDataManager.saveAll();
        TradeLogger.log(buyerName, "MARKET_BUY",
            listing.getItemId(), listing.getAmount(), totalCost, fee);
        MarketTradeCallback.EVENT.invoker().onTrade(
            player.getUuid(), listing.getSellerUuid(),
            listing.getItemId(), listing.getAmount(), totalCost, fee);

        player.sendMessage(Text.literal("§a购买成功！花费: " + totalCost + " " +
            EconomyConfig.getCurrencyName() + "，含手续费: " + fee));
    }
}
