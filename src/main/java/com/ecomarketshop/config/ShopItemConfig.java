package com.ecomarketshop.config;

/**
 * 管理员商店单个商品配置 POJO。
 *
 * <p>对应 {@code shop_config.json} 数组中的每一项：
 * <pre>{@code
 * {
 *   "id": "minecraft:diamond",
 *   "display_name": "璀璨钻石",
 *   "price_per_unit": 100,
 *   "stock": -1,
 *   "slot_index": 0
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@code stock} = -1 表示无限库存</li>
 *   <li>{@code slot_index} 范围 0~53（54格箱子界面）</li>
 * </ul>
 */
public class ShopItemConfig {

    /** 物品 ID，如 "minecraft:diamond" */
    private String id;

    /** GUI 中显示的自定义名称 */
    private String display_name;

    /** 单件价格 */
    private int price_per_unit;

    /** 库存数量，-1 表示无限 */
    private int stock;

    /** GUI 槽位索引（0~53） */
    private int slot_index;

    // ---- Getters / Setters ----

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDisplayName() {
        return display_name;
    }

    public void setDisplayName(String display_name) {
        this.display_name = display_name;
    }

    public int getPricePerUnit() {
        return price_per_unit;
    }

    public void setPricePerUnit(int price_per_unit) {
        this.price_per_unit = price_per_unit;
    }

    public int getStock() {
        return stock;
    }

    public void setStock(int stock) {
        this.stock = stock;
    }

    public int getSlotIndex() {
        return slot_index;
    }

    public void setSlotIndex(int slot_index) {
        this.slot_index = slot_index;
    }

    /** 是否为无限库存 */
    public boolean isInfiniteStock() {
        return stock == -1;
    }

    /** 价格的便捷别名 */
    public int getPrice() {
        return price_per_unit;
    }
}
