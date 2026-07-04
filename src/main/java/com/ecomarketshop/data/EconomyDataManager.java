package com.ecomarketshop.data;

import com.ecomarketshop.EcoMarketShop;
import com.ecomarketshop.api.EconomyChangeCallback;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 经济数据管理器 — 线程安全的玩家余额 JSON DAO。
 *
 * <p>核心设计：
 * <ul>
 *   <li>使用 {@link ReentrantReadWriteLock} 防止并发读写导致 JSON 损坏</li>
 *   <li>使用 {@link ConcurrentHashMap} 存储玩家余额，无锁读</li>
 *   <li>仅在玩家退出/服务器关闭时执行 {@code saveAll()}，避免频繁 IO</li>
 * </ul>
 *
 * <p>数据文件路径: {@code config/economy_data.json}
 */
public final class EconomyDataManager {

    private static final Path FILE_PATH = FabricLoader.getInstance().getConfigDir().resolve("economy_data.json");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<UUID, Integer>>(){}.getType();

    private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private static Map<UUID, Integer> balanceMap = new ConcurrentHashMap<>();

    /** 脏标记：余额发生变动时为 true，避免无变更时的无效写盘 */
    private static volatile boolean dirty = false;

    private EconomyDataManager() {}

    /**
     * 从 JSON 文件加载所有玩家余额到内存。
     * 若文件不存在，初始化为空 Map。
     */
    public static void loadAll() {
        lock.writeLock().lock();
        try {
            if (Files.exists(FILE_PATH)) {
                String json = Files.readString(FILE_PATH);
                Map<UUID, Integer> loaded = GSON.fromJson(json, MAP_TYPE);
                if (loaded != null) {
                    balanceMap = new ConcurrentHashMap<>(loaded);
                }
                EcoMarketShop.LOGGER.info("[EcoMarketShop] 经济数据加载完成，共 {} 个账户", balanceMap.size());
            } else {
                balanceMap = new ConcurrentHashMap<>();
                EcoMarketShop.LOGGER.info("[EcoMarketShop] 经济数据文件不存在，将在下次保存时创建");
            }
        } catch (Exception e) {
            EcoMarketShop.LOGGER.error("[EcoMarketShop] 经济数据加载失败", e);
            balanceMap = new ConcurrentHashMap<>();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 将所有玩家余额保存到 JSON 文件。
     * 仅在数据已变动（{@code dirty == true}）时执行实际 IO。
     */
    public static void saveAll() {
        if (!dirty) {
            return;
        }
        lock.writeLock().lock();
        try {
            String json = GSON.toJson(balanceMap);
            Files.writeString(FILE_PATH, json);
            dirty = false;
        } catch (IOException e) {
            EcoMarketShop.LOGGER.error("[EcoMarketShop] 经济数据保存失败", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 强制保存，无论数据是否已变动。
     * 用于服务器关闭等必须确保数据落盘的场景。
     */
    public static void forceSave() {
        lock.writeLock().lock();
        try {
            String json = GSON.toJson(balanceMap);
            Files.writeString(FILE_PATH, json);
            dirty = false;
        } catch (IOException e) {
            EcoMarketShop.LOGGER.error("[EcoMarketShop] 经济数据强制保存失败", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 获取玩家余额。若账户不存在返回 0。
     *
     * @param uuid 玩家 UUID
     * @return 当前余额
     */
    public static int getBalance(UUID uuid) {
        lock.readLock().lock();
        try {
            return balanceMap.getOrDefault(uuid, 0);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 设置玩家余额（覆写），并触发余额变动事件。
     *
     * @param uuid 玩家 UUID
     * @param amount 新余额
     */
    public static void setBalance(UUID uuid, int amount) {
        lock.writeLock().lock();
        try {
            int oldBalance = balanceMap.getOrDefault(uuid, 0);
            balanceMap.put(uuid, amount);
            dirty = true;
            // 触发余额变动事件
            EconomyChangeCallback.EVENT.invoker().onChange(uuid, oldBalance, amount, "SET");
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 增减玩家余额（正数加，负数扣），返回操作后的余额。
     *
     * @param uuid 玩家 UUID
     * @param delta 变化量
     * @return 操作后的余额
     */
    public static int addBalance(UUID uuid, int delta) {
        lock.writeLock().lock();
        try {
            int oldBalance = balanceMap.getOrDefault(uuid, 0);
            int newBalance = oldBalance + delta;
            balanceMap.put(uuid, newBalance);
            dirty = true;
            // 触发余额变动事件
            EconomyChangeCallback.EVENT.invoker().onChange(uuid, oldBalance, newBalance, "ADD:" + delta);
            return newBalance;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 原子性“检查并扣减”：余额充足时扣减，不足时拒绝。
     *
     * <p>解决 {@code hasEnough()} + {@code addBalance()} 之间的 TOCTOU 竞态：
     * 两个并发请求可能同时通过余额检查，导致余额变为负数。
     * 本方法在同一个写锁内完成检查 + 扣减，保证原子性。
     *
     * @param uuid   玩家 UUID
     * @param amount 扣减金额（必须 &gt;= 0）
     * @return true 表示余额充足并已扣减；false 表示余额不足，未做任何变更
     */
    public static boolean tryDeduct(UUID uuid, int amount) {
        lock.writeLock().lock();
        try {
            int balance = balanceMap.getOrDefault(uuid, 0);
            if (balance < amount) {
                return false;
            }
            int newBalance = balance - amount;
            balanceMap.put(uuid, newBalance);
            dirty = true;
            // 触发余额变动事件
            EconomyChangeCallback.EVENT.invoker().onChange(uuid, balance, newBalance, "DEDUCT:" + amount);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 判断玩家是否已有账户。
     */
    public static boolean hasAccount(UUID uuid) {
        lock.readLock().lock();
        try {
            return balanceMap.containsKey(uuid);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 判断玩家余额是否充足。
     */
    public static boolean hasEnough(UUID uuid, int amount) {
        return getBalance(uuid) >= amount;
    }

    /**
     * 获取所有玩家余额的不可变副本。
     */
    public static Map<UUID, Integer> getAllBalances() {
        lock.readLock().lock();
        try {
            return Map.copyOf(balanceMap);
        } finally {
            lock.readLock().unlock();
        }
    }
}
