package com.ecomarketshop.api;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

import java.util.UUID;

/**
 * 市场交易完成事件回调。
 *
 * <p>当玩家从市场购买挂单（整单购买）完成时触发。
 */
@FunctionalInterface
public interface MarketTradeCallback {

    /**
     * 事件实例。
     */
    Event<MarketTradeCallback> EVENT = EventFactory.createArrayBacked(
        MarketTradeCallback.class,
        (listeners) -> (buyerUuid, sellerUuid, itemId, amount, totalCost, fee) -> {
            for (MarketTradeCallback listener : listeners) {
                listener.onTrade(buyerUuid, sellerUuid, itemId, amount, totalCost, fee);
            }
        }
    );

    /**
     * 市场交易完成时调用。
     *
     * @param buyerUuid  买家 UUID
     * @param sellerUuid 卖家 UUID
     * @param itemId     物品 ID
     * @param amount     数量
     * @param totalCost  总额
     * @param fee        手续费
     */
    void onTrade(UUID buyerUuid, UUID sellerUuid, String itemId, int amount, int totalCost, int fee);
}
