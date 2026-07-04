package com.ecomarketshop.util;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 操作冷却 CD 管理器 — 防止高频刷物。
 *
 * <p>使用 {@link ConcurrentHashMap} 记录每位玩家最后一次操作的时间戳，
 * 在 {@code onSlotClick} 入口处拦截过快操作。
 *
 * <p>默认冷却时间为 500ms。
 *
 * <p>线程安全说明：使用 {@code synchronized(CooldownManager.class)} 保证
 * check+update 的原子性，避免并发请求同时通过冷却检查（对应测试场景 5）。
 */
public final class CooldownManager {

    /** 默认冷却时间（毫秒） */
    private static final long DEFAULT_COOLDOWN_MS = 500;

    /** 玩家 UUID → 上次操作时间戳 */
    private static final ConcurrentHashMap<UUID, Long> cooldownMap = new ConcurrentHashMap<>();

    private CooldownManager() {}

    /**
     * 检查玩家是否可以执行操作（冷却已过），并更新时间戳。
     *
     * @param uuid 玩家 UUID
     * @return true 表示冷却已过，操作允许；false 表示操作过快
     */
    public static boolean checkAndUpdate(UUID uuid) {
        return checkAndUpdate(uuid, DEFAULT_COOLDOWN_MS);
    }

    /**
     * 检查玩家是否可以执行操作（自定义冷却时间），并更新时间戳。
     *
     * <p>使用 {@code synchronized} 保证 check-then-act 的原子性，
     * 防止两个并发请求同时通过冷却检查。
     *
     * @param uuid 玩家 UUID
     * @param cooldownMs 冷却时间（毫秒）
     * @return true 表示冷却已过，操作允许
     */
    public static synchronized boolean checkAndUpdate(UUID uuid, long cooldownMs) {
        long now = System.currentTimeMillis();
        Long lastTime = cooldownMap.get(uuid);
        if (lastTime != null && (now - lastTime) < cooldownMs) {
            return false;
        }
        cooldownMap.put(uuid, now);
        return true;
    }

    /**
     * 清除玩家的冷却记录（玩家退出时调用）。
     *
     * @param uuid 玩家 UUID
     */
    public static void clear(UUID uuid) {
        cooldownMap.remove(uuid);
    }

    /**
     * 清除所有冷却记录。
     */
    public static void clearAll() {
        cooldownMap.clear();
    }
}
