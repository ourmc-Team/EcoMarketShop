package com.ecomarketshop.api;

import com.ecomarketshop.config.ShopItemConfig;
import com.ecomarketshop.data.ShopDataManager;

import java.util.List;

/**
 * 管理员商店 API — 供其他模组查询商店配置和库存。
 *
 * <p>返回的集合为不可变副本，修改需通过 {@link #reloadShopConfig()} 重载。
 */
public final class ShopAPI {

    private ShopAPI() {}

    /**
     * 获取当前商店所有商品配置（只读副本）。
     *
     * @return 商品配置列表
     */
    public static List<ShopItemConfig> getShopItems() {
        return ShopDataManager.getShopItems();
    }

    /**
     * 根据 GUI 槽位索引获取商品。
     *
     * @param slotIndex 槽位索引
     * @return 商品配置，不存在返回 null
     */
    public static ShopItemConfig getItemBySlot(int slotIndex) {
        return ShopDataManager.getItemBySlot(slotIndex);
    }

    /**
     * 强制刷新商店配置（等价于 /market reload）。
     */
    public static void reloadShopConfig() {
        ShopDataManager.loadConfig();
    }

    /**
     * 获取商品的当前剩余库存。
     *
     * @param itemId 物品 ID
     * @return 库存数量（-1 表示无限，0 表示不存在或售罄）
     */
    public static int getStock(String itemId) {
        return ShopDataManager.getStock(itemId);
    }
}
