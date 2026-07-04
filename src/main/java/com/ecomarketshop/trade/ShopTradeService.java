package com.ecomarketshop.trade;

import com.ecomarketshop.EcoMarketShop;
import com.ecomarketshop.api.ShopPurchaseCallback;
import com.ecomarketshop.config.EconomyConfig;
import com.ecomarketshop.config.ShopItemConfig;
import com.ecomarketshop.data.EconomyDataManager;
import com.ecomarketshop.data.ShopDataManager;
import com.ecomarketshop.util.ItemMatcher;
import com.ecomarketshop.util.TradeLogger;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * 商店购买交易执行服务 — 从原 ShopScreenHandler.executeTrade 抽取。
 *
 * <p>在聊天框 [确认] 时调用，不依赖 GUI 上下文（服务端权威执行）。
 *
 * <p>流程：
 * <ol>
 *   <li>synchronized(ShopDataManager.class) 内二次校验 → tryDeduct → 给物品 → decrementStock</li>
 *   <li>块外 saveConfig / TradeLogger / ShopPurchaseCallback / 消息</li>
 * </ol>
 */
public final class ShopTradeService {

    private ShopTradeService() {}

    /**
     * 执行商店购买。
     *
     * @param player    购买者
     * @param slotIndex 商品在 GUI 中的槽位索引
     */
    public static void executePurchase(ServerPlayerEntity player, int slotIndex) {
        String itemId;
        int price;

        synchronized (ShopDataManager.class) {
            // 从数据源重新获取配置（不信任客户端数据）
            ShopItemConfig config = ShopDataManager.getItemBySlot(slotIndex);
            if (config == null || config.getStock() == 0) {
                player.sendMessage(Text.literal("§c该商品已售罄！"));
                return;
            }

            price = config.getPrice();
            itemId = config.getId();

            // 原子性检查并扣减余额（解决 TOCTOU 竞态）
            if (!EconomyDataManager.tryDeduct(player.getUuid(), price)) {
                player.sendMessage(Text.literal("§c余额不足！需要: " + price + " " + EconomyConfig.getCurrencyName()));
                return;
            }

            // 给予物品（背包满则掉落至脚下）
            Item item = ItemMatcher.getItem(itemId);
            if (item != null) {
                ItemStack result = new ItemStack(item);
                if (!player.getInventory().insertStack(result)) {
                    player.dropItem(result, false);
                    EcoMarketShop.LOGGER.info("[EcoMarketShop] 玩家 {} 背包已满，物品 {} 掉落至地面",
                        player.getName().getString(), itemId);
                }
            }

            // 扣除库存（若配置非无限）
            ShopDataManager.decrementStock(slotIndex);
        }

        // ---- IO 操作移到同步块外 ----
        ShopDataManager.saveConfig();
        TradeLogger.log(player.getName().getString(), "SHOP_BUY", itemId, 1, price, 0);
        ShopPurchaseCallback.EVENT.invoker().onPurchase(player, itemId, 1, price);

        player.sendMessage(Text.literal("§a购买成功！花费: " + price + " " + EconomyConfig.getCurrencyName()));
        EcoMarketShop.LOGGER.info("[EcoMarketShop] 商店交易: 玩家={}, 物品={}, 价格={}",
            player.getName().getString(), itemId, price);
    }
}
