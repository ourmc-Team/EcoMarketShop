package com.ecomarketshop.data;

import com.ecomarketshop.EcoMarketShop;
import com.ecomarketshop.config.ShopItemConfig;
import com.ecomarketshop.util.ItemMatcher;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 商店数据管理器 — 加载/热重载商店配置，管理库存。
 *
 * <p>核心功能：
 * <ul>
 *   <li>从 {@code config/shop_config.json} 加载商品配置</li>
 *   <li>使用 {@link ItemMatcher} 过滤无效物品 ID</li>
 *   <li>提供按槽位查询商品、库存增减等操作</li>
 *   <li>支持热重载（{@code /market reload}）</li>
 * </ul>
 */
public final class ShopDataManager {

    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("shop_config.json");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 商品列表（线程安全） */
    private static volatile List<ShopItemConfig> shopItems = new ArrayList<>();

    /** 按 slot_index 快速索引 */
    private static volatile Map<Integer, ShopItemConfig> slotIndexMap = new ConcurrentHashMap<>();

    /** 配置是否已加载 */
    private static boolean loaded = false;

    private ShopDataManager() {}

    /**
     * 加载商店配置。若文件不存在，自动生成默认配置。
     */
    public static void loadConfig() {
        if (!Files.exists(CONFIG_PATH)) {
            createDefaultConfig();
        }
        try {
            String json = Files.readString(CONFIG_PATH);
            TypeToken<List<ShopItemConfig>> typeToken = new TypeToken<>() {};
            List<ShopItemConfig> rawItems = GSON.fromJson(json, typeToken.getType());
            if (rawItems == null) {
                rawItems = new ArrayList<>();
            }

            // 过滤无效物品 ID（安全防护，对应测试场景 6）
            List<ShopItemConfig> validItems = ItemMatcher.filterValidShopItems(rawItems);
            int filteredCount = rawItems.size() - validItems.size();
            if (filteredCount > 0) {
                EcoMarketShop.LOGGER.warn(
                    "[EcoMarketShop] 商店配置中有 {} 件无效物品ID已被过滤", filteredCount);
            }

            // 构建 slot 索引（重复 slot_index 后者覆盖前者）
            Map<Integer, ShopItemConfig> newSlotMap = new ConcurrentHashMap<>();
            for (ShopItemConfig item : validItems) {
                newSlotMap.put(item.getSlotIndex(), item);
            }

            shopItems = validItems;
            slotIndexMap = newSlotMap;
            loaded = true;

            EcoMarketShop.LOGGER.info("[EcoMarketShop] 商店配置加载完成，共 {} 件有效商品", validItems.size());
        } catch (Exception e) {
            EcoMarketShop.LOGGER.error("[EcoMarketShop] 商店配置加载失败", e);
            shopItems = new ArrayList<>();
            slotIndexMap = new ConcurrentHashMap<>();
        }
    }

    /**
     * 保存当前商店配置（含库存变更）到文件。
     */
    public static void saveConfig() {
        try {
            String json = GSON.toJson(shopItems);
            Files.writeString(CONFIG_PATH, json);
        } catch (IOException e) {
            EcoMarketShop.LOGGER.error("[EcoMarketShop] 商店配置保存失败", e);
        }
    }

    /**
     * 创建默认商店配置文件。
     */
    private static void createDefaultConfig() {
        try {
            List<ShopItemConfig> defaults = new ArrayList<>();

            ShopItemConfig diamond = new ShopItemConfig();
            diamond.setId("minecraft:diamond");
            diamond.setDisplayName("璀璨钻石");
            diamond.setPricePerUnit(100);
            diamond.setStock(-1);
            diamond.setSlotIndex(0);
            defaults.add(diamond);

            ShopItemConfig cookie = new ShopItemConfig();
            cookie.setId("minecraft:cookie");
            cookie.setDisplayName("美味饼干");
            cookie.setPricePerUnit(5);
            cookie.setStock(64);
            cookie.setSlotIndex(1);
            defaults.add(cookie);

            Files.writeString(CONFIG_PATH, GSON.toJson(defaults));
            EcoMarketShop.LOGGER.info("[EcoMarketShop] 已生成默认商店配置: {}", CONFIG_PATH);
        } catch (IOException e) {
            EcoMarketShop.LOGGER.error("[EcoMarketShop] 无法创建商店配置文件", e);
        }
    }

    /**
     * 获取所有商品配置（只读副本）。
     */
    public static List<ShopItemConfig> getShopItems() {
        return Collections.unmodifiableList(shopItems);
    }

    /**
     * 根据 GUI 槽位索引获取商品配置。
     *
     * @param slotIndex 槽位索引
     * @return 商品配置，不存在返回 null
     */
    public static ShopItemConfig getItemBySlot(int slotIndex) {
        return slotIndexMap.get(slotIndex);
    }

    /**
     * 获取指定物品的当前剩余库存。
     *
     * @param itemId 物品 ID
     * @return 库存数量，-1 表示无限，0 表示不存在或售罄
     */
    public static int getStock(String itemId) {
        for (ShopItemConfig item : shopItems) {
            if (item.getId().equals(itemId)) {
                return item.getStock();
            }
        }
        return 0;
    }

    /**
     * 扣减库存（若为无限库存则不扣）。委托给批量方法，数量为 1。
     *
     * @param slotIndex 槽位索引
     * @return true 表示扣减成功或无限库存
     */
    public static synchronized boolean decrementStock(int slotIndex) {
        return decrementStock(slotIndex, 1);
    }

    /**
     * 批量扣减库存（若为无限库存则不扣）。
     *
     * <p>线程安全：方法内部使用 {@code synchronized(ShopDataManager.class)} 保证
     * check-then-act 的原子性，防止并发超卖。
     *
     * @param slotIndex 槽位索引
     * @param quantity  扣减数量
     * @return true 表示扣减成功或无限库存
     */
    public static synchronized boolean decrementStock(int slotIndex, int quantity) {
        ShopItemConfig item = slotIndexMap.get(slotIndex);
        if (item == null) {
            return false;
        }
        if (item.isInfiniteStock()) {
            return true;
        }
        if (item.getStock() < quantity) {
            return false;
        }
        item.setStock(item.getStock() - quantity);
        return true;
    }

    /** 是否已加载 */
    public static boolean isLoaded() {
        return loaded;
    }
}
