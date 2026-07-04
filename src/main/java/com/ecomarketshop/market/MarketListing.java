package com.ecomarketshop.market;

import com.ecomarketshop.config.EconomyConfig;
import com.ecomarketshop.util.ItemStackSerializer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.UUID;

/**
 * 市场挂单实体。
 *
 * <p>记录一名卖家将特定物品上架的信息：
 * <ul>
 *   <li>唯一挂单 ID（UUID 字符串）</li>
 *   <li>卖家 UUID 和名称</li>
 *   <li>物品 ID（如 "minecraft:diamond"）</li>
 *   <li>数量</li>
 *   <li>单价</li>
 *   <li>创建时间戳</li>
 * </ul>
 *
 * <p>ItemStack 在需要时从 itemId + amount 重建，
 * 避免直接序列化 Minecraft 对象。
 */
public class MarketListing {

    /** 挂单唯一 ID */
    private String id;

    /** 卖家 UUID */
    private UUID sellerUuid;

    /** 卖家名称（便于日志显示） */
    private String sellerName;

    /** 物品 ID，如 "minecraft:diamond" */
    private String itemId;

    /** 数量 */
    private int amount;

    /** 单价 */
    private int pricePerUnit;

    /** 创建时间戳（毫秒） */
    private long timestamp;

    /** 完整物品 NBT（SNBT 字符串，保留附魔/耐久/自定义名等所有数据组件）；为空时回退到 itemId+amount */
    private String itemNbt;

    // ---- Constructors ----

    public MarketListing() {}

    public MarketListing(UUID sellerUuid, String sellerName, String itemId,
                         int amount, int pricePerUnit) {
        this.id = UUID.randomUUID().toString();
        this.sellerUuid = sellerUuid;
        this.sellerName = sellerName;
        this.itemId = itemId;
        this.amount = amount;
        this.pricePerUnit = pricePerUnit;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 基于完整 ItemStack 构造挂单 — 保留附魔、耐久、自定义名等所有数据组件。
     *
     * @param sellerUuid   卖家 UUID
     * @param sellerName   卖家名称
     * @param stack        物品堆叠（完整保留）
     * @param pricePerUnit 单价
     */
    public MarketListing(UUID sellerUuid, String sellerName, ItemStack stack, int pricePerUnit) {
        this.id = UUID.randomUUID().toString();
        this.sellerUuid = sellerUuid;
        this.sellerName = sellerName;
        this.itemId = Registries.ITEM.getId(stack.getItem()).toString();
        this.amount = stack.getCount();
        this.pricePerUnit = pricePerUnit;
        this.timestamp = System.currentTimeMillis();
        this.itemNbt = ItemStackSerializer.serialize(stack);
    }

    // ---- Getters / Setters ----

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public UUID getSellerUuid() {
        return sellerUuid;
    }

    public void setSellerUuid(UUID sellerUuid) {
        this.sellerUuid = sellerUuid;
    }

    public String getSellerName() {
        return sellerName;
    }

    public void setSellerName(String sellerName) {
        this.sellerName = sellerName;
    }

    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }

    public int getPricePerUnit() {
        return pricePerUnit;
    }

    public void setPricePerUnit(int pricePerUnit) {
        this.pricePerUnit = pricePerUnit;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    /** 获取完整物品 NBT 字符串（可能为空） */
    public String getItemNbt() {
        return itemNbt;
    }

    public void setItemNbt(String itemNbt) {
        this.itemNbt = itemNbt;
    }

    /**
     * 计算总价 = 单价 × 数量。
     *
     * <p>使用 {@code long} 中间值防止 {@code pricePerUnit * amount} 溢出，
     * 并在超出 {@link Integer#MAX_VALUE} 时截断为上限值，防止经济刷取漏洞。
     *
     * @return 总价（保证 &gt;= 0）
     */
    public int getTotalCost() {
        long raw = (long) pricePerUnit * amount;
        if (raw > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (raw < 0) {
            return 0;
        }
        return (int) raw;
    }

    /**
     * 计算手续费 — 比例由 {@link EconomyConfig#getMarketFeeRate()} 配置。
     */
    public int getFee() {
        return (int) Math.floor(getTotalCost() * EconomyConfig.getMarketFeeRate());
    }

    /** 计算卖家到手金额（扣除 5% 手续费） */
    public int getSellerRevenue() {
        return getTotalCost() - getFee();
    }

    /**
     * 重建并返回该挂单对应的 ItemStack。
     *
     * <p>优先从 {@code itemNbt} 反序列化完整堆叠（含附魔等数据组件）；
     * 为空时回退到 {@code itemId + amount}（兼容旧数据）。
     *
     * @return 物品堆叠副本，若物品 ID 无效返回 null
     */
    public ItemStack getItemStack() {
        if (itemNbt != null && !itemNbt.isBlank()) {
            ItemStack stack = ItemStackSerializer.deserialize(itemNbt);
            return stack.isEmpty() ? null : stack;
        }
        Identifier identifier = Identifier.tryParse(itemId);
        if (identifier == null) {
            return null;
        }
        Item item = Registries.ITEM.get(identifier);
        if (item == null) {
            return null;
        }
        return new ItemStack(item, amount);
    }
}
