package com.ecomarketshop.util;

import com.ecomarketshop.EcoMarketShop;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.RegistryWrapper;

import java.util.Optional;

/**
 * ItemStack 序列化工具 — 在 {@link com.ecomarketshop.market.MarketListing} 中完整保存
 * 物品堆叠（含附魔、耐久、自定义名称等所有数据组件）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>使用 {@link ItemStack#MAP_CODEC} 配合 {@link NbtCompound#copyFromCodec} 将物品
 *       序列化为 NBT，再转为 SNBT 字符串存入 JSON 配置，确保所有数据组件（附魔等）完整保留。</li>
 *   <li>反序列化使用 {@link NbtCompound#decode} 配合 {@link ItemStack#MAP_CODEC}。</li>
 *   <li>{@link RegistryWrapper.WrapperLookup} 由 {@link EcoMarketShop#getRegistryLookup()} 提供，
 *       在服务端启动时缓存。</li>
 * </ul>
 */
public final class ItemStackSerializer {

    private ItemStackSerializer() {}

    /**
     * 将 ItemStack 序列化为 SNBT 字符串。
     *
     * @param stack 物品堆叠
     * @return SNBT 字符串；空堆叠返回空字符串
     */
    public static String serialize(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        try {
            NbtCompound nbt = new NbtCompound();
            nbt.copyFromCodec(ItemStack.MAP_CODEC,
                EcoMarketShop.getRegistryLookup().getOps(NbtOps.INSTANCE), stack);
            return nbt.toString();
        } catch (Exception e) {
            EcoMarketShop.LOGGER.error("[EcoMarketShop] ItemStack 序列化失败", e);
            return "";
        }
    }

    /**
     * 从 SNBT 字符串反序列化为 ItemStack（保留所有数据组件）。
     *
     * @param snbt SNBT 字符串
     * @return 物品堆叠；失败返回空堆叠
     */
    public static ItemStack deserialize(String snbt) {
        if (snbt == null || snbt.isBlank()) {
            return ItemStack.EMPTY;
        }
        try {
            NbtCompound nbt = StringNbtReader.readCompound(snbt);
            Optional<ItemStack> result = nbt.decode(ItemStack.MAP_CODEC,
                EcoMarketShop.getRegistryLookup().getOps(NbtOps.INSTANCE));
            return result.orElse(ItemStack.EMPTY);
        } catch (Exception e) {
            EcoMarketShop.LOGGER.error("[EcoMarketShop] ItemStack 反序列化失败", e);
            return ItemStack.EMPTY;
        }
    }
}
