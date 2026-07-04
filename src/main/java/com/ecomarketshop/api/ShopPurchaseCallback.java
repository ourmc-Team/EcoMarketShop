package com.ecomarketshop.api;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 商店购买完成事件回调。
 *
 * <p>当玩家在管理员商店成功完成一笔购买时触发。
 */
@FunctionalInterface
public interface ShopPurchaseCallback {

    /**
     * 事件实例。
     */
    Event<ShopPurchaseCallback> EVENT = EventFactory.createArrayBacked(
        ShopPurchaseCallback.class,
        (listeners) -> (player, itemId, amount, cost) -> {
            for (ShopPurchaseCallback listener : listeners) {
                listener.onPurchase(player, itemId, amount, cost);
            }
        }
    );

    /**
     * 商店购买完成时调用。
     *
     * @param player 购买的玩家
     * @param itemId 物品 ID
     * @param amount 购买数量
     * @param cost   花费总额
     */
    void onPurchase(ServerPlayerEntity player, String itemId, int amount, int cost);
}
