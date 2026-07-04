package com.ecomarketshop.data;

import com.ecomarketshop.EcoMarketShop;
import com.ecomarketshop.market.MarketListing;
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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 市场数据管理器 — 挂单 CRUD、分页查询、线程安全保存。
 *
 * <p>核心设计：
 * <ul>
 *   <li>使用 {@link CopyOnWriteArrayList} 存储挂单，保证遍历线程安全</li>
 *   <li>交易操作使用 {@code synchronized(MarketDataManager.class)} 防止超卖</li>
 *   <li>数据文件: {@code config/market_data.json}</li>
 * </ul>
 */
public final class MarketDataManager {

    private static final Path FILE_PATH = FabricLoader.getInstance().getConfigDir().resolve("market_data.json");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 挂单列表（线程安全） */
    private static List<MarketListing> listings = new CopyOnWriteArrayList<>();

    /** 每页显示数量（54格箱子，去除导航栏后 45 格） */
    public static final int ITEMS_PER_PAGE = 45;

    private MarketDataManager() {}

    /**
     * 从 JSON 文件加载所有挂单。
     */
    public static void loadAll() {
        try {
            if (Files.exists(FILE_PATH)) {
                String json = Files.readString(FILE_PATH);
                TypeToken<List<MarketListing>> typeToken = new TypeToken<>() {};
                List<MarketListing> loaded = GSON.fromJson(json, typeToken.getType());
                if (loaded != null) {
                    // 过滤掉无效挂单（数量 <= 0）
                    listings = new CopyOnWriteArrayList<>(
                        loaded.stream()
                            .filter(l -> l.getAmount() > 0)
                            .collect(Collectors.toList())
                    );
                }
                EcoMarketShop.LOGGER.info("[EcoMarketShop] 市场数据加载完成，共 {} 条挂单", listings.size());
            } else {
                listings = new CopyOnWriteArrayList<>();
                EcoMarketShop.LOGGER.info("[EcoMarketShop] 市场数据文件不存在，将在下次保存时创建");
            }
        } catch (Exception e) {
            EcoMarketShop.LOGGER.error("[EcoMarketShop] 市场数据加载失败", e);
            listings = new CopyOnWriteArrayList<>();
        }
    }

    /**
     * 保存所有挂单到 JSON 文件。
     */
    public static void saveAll() {
        try {
            String json = GSON.toJson(listings);
            Files.writeString(FILE_PATH, json);
        } catch (IOException e) {
            EcoMarketShop.LOGGER.error("[EcoMarketShop] 市场数据保存失败", e);
        }
    }

    /**
     * 添加新挂单。
     *
     * <p>线程安全：使用 {@code synchronized(MarketDataManager.class)} 保护，
     * 与交易操作使用同一把锁，防止并发不一致。
     *
     * @param listing 挂单对象
     */
    public static void addListing(MarketListing listing) {
        synchronized (MarketDataManager.class) {
            listings.add(listing);
            saveAll();
        }
    }

    /**
     * 移除指定 ID 的挂单。
     *
     * <p>线程安全：使用 {@code synchronized(MarketDataManager.class)} 保护。
     *
     * @param listingId 挂单 ID
     * @return true 表示移除成功
     */
    public static boolean removeListing(String listingId) {
        synchronized (MarketDataManager.class) {
            boolean removed = listings.removeIf(l -> l.getId().equals(listingId));
            if (removed) {
                saveAll();
            }
            return removed;
        }
    }

    /**
     * 根据页内索引和当前页获取挂单。
     *
     * @param pageIndex 页内索引（0 ~ ITEMS_PER_PAGE-1）
     * @param currentPage 当前页码（0-based）
     * @return 对应挂单，不存在返回 null
     */
    public static MarketListing getListingByPageIndex(int pageIndex, int currentPage) {
        int absoluteIndex = currentPage * ITEMS_PER_PAGE + pageIndex;
        if (absoluteIndex < 0 || absoluteIndex >= listings.size()) {
            return null;
        }
        return listings.get(absoluteIndex);
    }

    /**
     * 根据挂单 ID 获取挂单。
     *
     * <p>用于聊天确认阶段以 ID 定位挂单（GUI 关闭后页码可能失真，ID 更稳健）。
     *
     * @param listingId 挂单 ID
     * @return 对应挂单，不存在返回 null
     */
    public static MarketListing getListingById(String listingId) {
        if (listingId == null) {
            return null;
        }
        for (MarketListing listing : listings) {
            if (listing.getId().equals(listingId)) {
                return listing;
            }
        }
        return null;
    }

    /**
     * 获取当前页的挂单列表（只读副本）。
     *
     * @param page 页码（0-based）
     * @return 该页的挂单列表
     */
    public static List<MarketListing> getPageListings(int page) {
        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, listings.size());
        if (start >= listings.size() || start < 0) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(listings.subList(start, end));
    }

    /**
     * 获取所有活跃挂单（不可变副本）。
     */
    public static List<MarketListing> getActiveListings() {
        return Collections.unmodifiableList(new ArrayList<>(listings));
    }

    /**
     * 根据卖家 UUID 查询其挂单。
     *
     * @param sellerUuid 卖家 UUID
     * @return 该卖家的挂单列表
     */
    public static List<MarketListing> getListingsBySeller(UUID sellerUuid) {
        return listings.stream()
            .filter(l -> l.getSellerUuid().equals(sellerUuid))
            .collect(Collectors.toList());
    }

    /**
     * 取消指定卖家的所有挂单。
     *
     * <p>线程安全：使用 {@code synchronized(MarketDataManager.class)} 保护。
     *
     * @param sellerUuid 卖家 UUID
     * @return 取消的挂单数量
     */
    public static int cancelAllListings(UUID sellerUuid) {
        synchronized (MarketDataManager.class) {
            int before = listings.size();
            listings.removeIf(l -> l.getSellerUuid().equals(sellerUuid));
            int cancelled = before - listings.size();
            if (cancelled > 0) {
                saveAll();
            }
            return cancelled;
        }
    }

    /**
     * 获取当前挂单总数。
     */
    public static int getTotalListingCount() {
        return listings.size();
    }

    /**
     * 获取总页数。
     */
    public static int getTotalPages() {
        return (int) Math.ceil((double) listings.size() / ITEMS_PER_PAGE);
    }
}
