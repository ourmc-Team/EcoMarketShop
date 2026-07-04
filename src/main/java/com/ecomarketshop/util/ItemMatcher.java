package com.ecomarketshop.util;

import com.ecomarketshop.EcoMarketShop;
import com.ecomarketshop.config.ShopItemConfig;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 物品 ID 校验与防注入工具。
 *
 * <p>安全要点：
 * <ol>
 *   <li>使用 {@link Identifier#tryParse(String)} 而非 {@code new Identifier()} —
 *       前者对非法格式返回 null，后者抛出异常</li>
 *   <li>使用 {@link Registries.ITEM#containsId(Identifier)} 校验 ID 是否已在注册表中</li>
 *   <li>配置文件加载时统一过滤，非法 ID 不会进入内存</li>
 * </ol>
 */
public final class ItemMatcher {

    private ItemMatcher() {}

    /**
     * 安全解析物品 ID，防止恶意注入。
     *
     * <p>流程：
     * <ol>
     *   <li>{@code Identifier.tryParse(id)} — 若格式非法返回 null</li>
     *   <li>{@code Registries.ITEM.containsId(identifier)} — 校验 ID 是否已注册</li>
     * </ol>
     *
     * @param id 物品 ID 字符串，如 "minecraft:diamond"
     * @return true 表示合法
     */
    public static boolean isValidItemId(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        Identifier identifier = Identifier.tryParse(id);
        return identifier != null && Registries.ITEM.containsId(identifier);
    }

    /**
     * 根据 ID 获取 Item。
     *
     * <p>安全说明：同时校验格式合法性和注册表存在性，
     * 避免 {@code Registries.ITEM.get()} 对未注册 ID 返回 {@code Items.AIR}。
     *
     * @param id 物品 ID
     * @return Item 对象，若无效或未注册返回 null
     */
    public static Item getItem(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null) {
            return null;
        }
        // 未注册的 ID 会返回 AIR，必须先用 containsId 拦截
        if (!Registries.ITEM.containsId(identifier)) {
            return null;
        }
        return Registries.ITEM.get(identifier);
    }

    /**
     * 管理员商店加载时的预处理：过滤无效 ID 并打印警告。
     *
     * @param rawItems 原始配置列表
     * @return 过滤后的有效配置列表
     */
    public static List<ShopItemConfig> filterValidShopItems(List<ShopItemConfig> rawItems) {
        return rawItems.stream()
            .filter(config -> {
                boolean valid = isValidItemId(config.getId());
                if (!valid) {
                    EcoMarketShop.LOGGER.warn("[EcoMarketShop] 跳过无效物品ID: {}", config.getId());
                }
                return valid;
            })
            .collect(Collectors.toList());
    }
}
