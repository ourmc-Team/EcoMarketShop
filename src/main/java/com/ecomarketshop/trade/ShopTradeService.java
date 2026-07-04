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
     * 执行商店购买（数量为 1）。委托给批量方法。
     *
     * @param player    购买者
     * @param slotIndex 商品在 GUI 中的槽位索引
     */
    public static void executePurchase(ServerPlayerEntity player, int slotIndex) {
        executePurchase(player, slotIndex, 1);
    }

    /**
     * 执行商店批量购买。
     *
     * <p>安全设计：所有校验在 {@code synchronized(ShopDataManager.class)} 内进行，
     * 保证 check-then-act 原子性，防止 TOCTOU 竞态。
     *
     * @param player    购买者
     * @param slotIndex 商品在 GUI 中的槽位索引
     * @param quantity  购买数量
     */
    public static void executePurchase(ServerPlayerEntity player, int slotIndex, int quantity) {
        if (quantity <= 0) {
            return;
        }

        String itemId = null;
        int totalPrice;

        synchronized (ShopDataManager.class) {
            // 从数据源重新获取配置（不信任客户端数据）
            ShopItemConfig config = ShopDataManager.getItemBySlot(slotIndex);
            if (config == null || config.getStock() == 0) {
                player.sendMessage(Text.literal("§c该商品已售罄！"));
                return;
            }

            // 库存检查（非无限库存）
            if (!config.isInfiniteStock() && config.getStock() < quantity) {
                player.sendMessage(Text.literal("§c库存不足！需要: " + quantity
                    + "，剩余: " + config.getStock()));
                return;
            }

            itemId = config.getId();
            totalPrice = config.getPrice() * quantity;

            // 原子性检查并扣减余额（解决 TOCTOU 竞态）
            if (!EconomyDataManager.tryDeduct(player.getUuid(), totalPrice)) {
                player.sendMessage(Text.literal("§c余额不足！需要: " + totalPrice
                    + " " + EconomyConfig.getCurrencyName()));
                return;
            }

            // 给予物品（含数量，按 maxStackSize 分批发放）
            Item item = ItemMatcher.getItem(itemId);
            if (item != null) {
                int maxStack = item.getMaxCount();
                int remaining = quantity;
                while (remaining > 0) {
                    int stackSize = Math.min(remaining, maxStack);
                    ItemStack result = new ItemStack(item, stackSize);
                    if (!player.getInventory().insertStack(result)) {
                        player.dropItem(result, false);
                        EcoMarketShop.LOGGER.info("[EcoMarketShop] 玩家 {} 背包已满，物品 {} 掉落至地面",
                            player.getName().getString(), itemId);
                    }
                    remaining -= stackSize;
                }
            }

            // 批量扣除库存
            ShopDataManager.decrementStock(slotIndex, quantity);
        }

        // ---- IO 操作移到同步块外 ----
        ShopDataManager.saveConfig();
        TradeLogger.log(player.getName().getString(), "SHOP_BUY", itemId, quantity, totalPrice, 0);
        ShopPurchaseCallback.EVENT.invoker().onPurchase(player, itemId, quantity, totalPrice);

        player.sendMessage(Text.literal("§a购买成功！花费: " + totalPrice + " "
            + EconomyConfig.getCurrencyName() + "，购买数量: " + quantity));
        EcoMarketShop.LOGGER.info("[EcoMarketShop] 商店交易: 玩家={}, 物品={}, 数量={}, 总价={}",
            player.getName().getString(), itemId, quantity, totalPrice);
    }

    /**
     * 右键直接购买一组（满组）。
     *
     * <p>实际购买数量取以下三项的最小值：
     * <ul>
     *   <li>物品最大堆叠数</li>
     *   <li>玩家余额可购买的数量</li>
     *   <li>库存剩余数量（无限库存则取 maxStack）</li>
     * </ul>
     *
     * <p>若玩家连 1 个都买不起，发送提示并拒绝。
     *
     * @param player    购买者
     * @param slotIndex 商品在 GUI 中的槽位索引
     */
    public static void executeBulkPurchase(ServerPlayerEntity player, int slotIndex) {
        ShopItemConfig config = ShopDataManager.getItemBySlot(slotIndex);
        if (config == null || config.getStock() == 0) {
            player.sendMessage(Text.literal("§c该商品已售罄！"));
            return;
        }

        Item item = ItemMatcher.getItem(config.getId());
        if (item == null) {
            return;
        }

        int maxStack = item.getMaxCount();
        int price = config.getPrice();

        // 计算实际可购买数量（取 maxStack、余额、库存的最小值）
        int maxAffordable = (price > 0)
            ? EconomyDataManager.getBalance(player.getUuid()) / price
            : maxStack;

        int maxStock = config.isInfiniteStock()
            ? maxStack
            : Math.min(config.getStock(), maxStack);

        int actualQuantity = Math.min(maxStack, Math.min(maxAffordable, maxStock));

        if (actualQuantity <= 0) {
            player.sendMessage(Text.literal("§c余额不足，无法购买！"));
            return;
        }

        executePurchase(player, slotIndex, actualQuantity);
    }
}
