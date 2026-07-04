package com.ecomarketshop.api;

import com.ecomarketshop.config.EconomyConfig;
import com.ecomarketshop.data.EconomyDataManager;

import java.util.UUID;

/**
 * 经济系统 API — 供其他模组调用的静态方法接口。
 *
 * <p>所有方法均为线程安全，可在异步线程中调用。
 *
 * <pre>{@code
 * // 有借有还
 * int fee = 50;
 * if (EconomyAPI.hasEnough(playerUuid, fee)) {
 *     EconomyAPI.addBalance(playerUuid, -fee);
 * }
 * }</pre>
 */
public final class EconomyAPI {

    private EconomyAPI() {}

    /**
     * 获取玩家余额。
     *
     * @param playerUuid 玩家 UUID
     * @return 当前余额（无账户返回 0）
     */
    public static int getBalance(UUID playerUuid) {
        return EconomyDataManager.getBalance(playerUuid);
    }

    /**
     * 设置玩家余额（覆写）。
     *
     * @param playerUuid 玩家 UUID
     * @param amount     新余额
     */
    public static void setBalance(UUID playerUuid, int amount) {
        EconomyDataManager.setBalance(playerUuid, amount);
    }

    /**
     * 增减玩家余额（正数加，负数扣）。
     *
     * @param playerUuid 玩家 UUID
     * @param delta      变化量
     * @return 操作后的余额
     */
    public static int addBalance(UUID playerUuid, int delta) {
        return EconomyDataManager.addBalance(playerUuid, delta);
    }

    /**
     * 判断玩家是否有足够余额。
     *
     * @param playerUuid 玩家 UUID
     * @param amount     所需金额
     * @return true 表示余额充足
     */
    public static boolean hasEnough(UUID playerUuid, int amount) {
        return EconomyDataManager.hasEnough(playerUuid, amount);
    }

    /**
     * 原子性“检查并扣减”：余额充足时扣减，不足时拒绝。
     *
     * <p>解决 {@link #hasEnough} + {@link #addBalance} 之间的 TOCTOU 竞态。
     *
     * @param playerUuid 玩家 UUID
     * @param amount     扣减金额（必须 &gt;= 0）
     * @return true 表示余额充足并已扣减；false 表示余额不足
     */
    public static boolean tryDeduct(UUID playerUuid, int amount) {
        return EconomyDataManager.tryDeduct(playerUuid, amount);
    }

    /**
     * 获取货币名称（来自配置）。
     *
     * @return 货币名称，如 "Coin"
     */
    public static String getCurrencyName() {
        return EconomyConfig.getCurrencyName();
    }
}
