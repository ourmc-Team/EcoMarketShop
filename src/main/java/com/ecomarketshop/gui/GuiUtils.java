package com.ecomarketshop.gui;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.List;

/**
 * GUI 界面通用工具类 — 统一界面元素创建，确保各 GUI 视觉风格一致。
 *
 * <p>提供以下工厂方法：
 * <ul>
 *   <li>{@link #createBorder} — 黑色玻璃板边框</li>
 *   <li>{@link #createFiller} — 灰色玻璃板填充物</li>
 *   <li>{@link #createInfoItem} — 信息展示物品（纸张）</li>
 *   <li>{@link #createButton} — 按钮物品（可带 Lore）</li>
 *   <li>{@link #createConfirmButton} / {@link #createCancelButton} — 标准确认/取消按钮</li>
 *   <li>{@link #createDetailItem} — 详情展示物品（纸张 + Lore）</li>
 * </ul>
 */
public final class GuiUtils {

    private GuiUtils() {}

    /**
     * 创建边框物品（黑色玻璃板，空名称）。
     */
    public static ItemStack createBorder() {
        ItemStack stack = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(" "));
        return stack;
    }

    /**
     * 创建填充物（灰色玻璃板，空名称）。
     */
    public static ItemStack createFiller() {
        ItemStack stack = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(" "));
        return stack;
    }

    /**
     * 创建信息展示物品（纸张 + 自定义名称）。
     */
    public static ItemStack createInfoItem(String name) {
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        return stack;
    }

    /**
     * 创建信息展示物品（指定材质 + 自定义名称）。
     */
    public static ItemStack createInfoItem(Item material, String name) {
        ItemStack stack = new ItemStack(material);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        return stack;
    }

    /**
     * 创建带 Lore 的信息展示物品（指定材质 + 名称 + Lore）。
     */
    public static ItemStack createInfoItem(Item material, String name, List<Text> lore) {
        ItemStack stack = new ItemStack(material);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        if (lore != null && !lore.isEmpty()) {
            stack.set(DataComponentTypes.LORE, new LoreComponent(lore));
        }
        return stack;
    }

    /**
     * 创建按钮物品（指定材质 + 名称，无 Lore）。
     */
    public static ItemStack createButton(Item material, String name) {
        ItemStack stack = new ItemStack(material);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        return stack;
    }

    /**
     * 创建带 Lore 的按钮物品。
     */
    public static ItemStack createButton(Item material, String name, List<Text> lore) {
        ItemStack stack = createButton(material, name);
        if (lore != null && !lore.isEmpty()) {
            stack.set(DataComponentTypes.LORE, new LoreComponent(lore));
        }
        return stack;
    }

    /**
     * 创建标准确认按钮（绿色羊毛 + Lore）。
     */
    public static ItemStack createConfirmButton() {
        return createButton(Items.GREEN_WOOL, "§a§l[✓ 确认]",
            List.of(Text.literal("§7点击确认此次交易")));
    }

    /**
     * 创建标准取消按钮（红色羊毛 + Lore）。
     */
    public static ItemStack createCancelButton() {
        return createButton(Items.RED_WOOL, "§c§l[✗ 取消]",
            List.of(Text.literal("§7点击取消此次交易")));
    }

    /**
     * 创建详情展示物品（纸张 + 名称 + Lore）。
     */
    public static ItemStack createDetailItem(String name, List<Text> lore) {
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        if (lore != null && !lore.isEmpty()) {
            stack.set(DataComponentTypes.LORE, new LoreComponent(lore));
        }
        return stack;
    }
}
