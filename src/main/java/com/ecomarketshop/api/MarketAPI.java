package com.ecomarketshop.api;

import com.ecomarketshop.data.MarketDataManager;
import com.ecomarketshop.market.MarketListing;

import java.util.List;
import java.util.UUID;

/**
 * 市场拍卖行 API — 供其他模组查询市场数据和挂单。
 *
 * <p>返回的集合为不可变副本。
 */
public final class MarketAPI {

    private MarketAPI() {}

    /**
     * 获取当前所有活跃挂单（不可变副本）。
     *
     * @return 挂单列表
     */
    public static List<MarketListing> getActiveListings() {
        return MarketDataManager.getActiveListings();
    }

    /**
     * 根据卖家 UUID 查询其挂单。
     *
     * @param sellerUuid 卖家 UUID
     * @return 该卖家的挂单列表
     */
    public static List<MarketListing> getListingsBySeller(UUID sellerUuid) {
        return MarketDataManager.getListingsBySeller(sellerUuid);
    }

    /**
     * 取消指定卖家的所有挂单。
     *
     * @param sellerUuid 卖家 UUID
     * @return 取消的挂单数量
     */
    public static int cancelAllListings(UUID sellerUuid) {
        return MarketDataManager.cancelAllListings(sellerUuid);
    }

    /**
     * 创建新的市场挂单。
     *
     * @param listing 挂单对象
     */
    public static void addListing(MarketListing listing) {
        MarketDataManager.addListing(listing);
    }

    /**
     * 下架指定挂单（仅限卖家本人的挂单）。
     *
     * <p>仅移除挂单记录，不负责返还物品。如需返还物品请使用
     * {@link com.ecomarketshop.trade.MarketTradeService#executeDelist}。
     *
     * @param sellerUuid 卖家 UUID（用于权限校验）
     * @param listingId  挂单 ID
     * @return true 表示下架成功
     */
    public static boolean delistListing(UUID sellerUuid, String listingId) {
        MarketListing listing = MarketDataManager.getListingById(listingId);
        if (listing == null || !listing.getSellerUuid().equals(sellerUuid)) {
            return false;
        }
        return MarketDataManager.removeListing(listingId);
    }

    /**
     * 获取当前挂单总数。
     *
     * @return 挂单数量
     */
    public static int getTotalListingCount() {
        return MarketDataManager.getTotalListingCount();
    }
}
