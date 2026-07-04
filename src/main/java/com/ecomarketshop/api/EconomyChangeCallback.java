package com.ecomarketshop.api;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

import java.util.UUID;

/**
 * 玩家余额变动事件回调。
 *
 * <p>当玩家余额通过 {@link com.ecomarketshop.data.EconomyDataManager} 发生变动时触发。
 * 其他模组可监听此事件以感知经济变动。
 *
 * <pre>{@code
 * EconomyChangeCallback.EVENT.register((uuid, oldBal, newBal, reason) -> {
 *     LOGGER.info("Player {} balance changed: {} -> {} ({})", uuid, oldBal, newBal, reason);
 * });
 * }</pre>
 */
@FunctionalInterface
public interface EconomyChangeCallback {

    /**
     * 事件实例。
     */
    Event<EconomyChangeCallback> EVENT = EventFactory.createArrayBacked(
        EconomyChangeCallback.class,
        (listeners) -> (playerUuid, oldBalance, newBalance, reason) -> {
            for (EconomyChangeCallback listener : listeners) {
                listener.onChange(playerUuid, oldBalance, newBalance, reason);
            }
        }
    );

    /**
     * 余额变动时调用。
     *
     * @param playerUuid 玩家 UUID
     * @param oldBalance 变动前余额
     * @param newBalance 变动后余额
     * @param reason     变动原因（如 "SET", "ADD:50", "SHOP_BUY", "MARKET_BUY" 等）
     */
    void onChange(UUID playerUuid, int oldBalance, int newBalance, String reason);
}
