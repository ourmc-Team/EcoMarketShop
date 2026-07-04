package com.ecomarketshop.gui;

import com.ecomarketshop.config.EconomyConfig;
import com.ecomarketshop.config.ShopItemConfig;
import com.ecomarketshop.data.ShopDataManager;
import com.ecomarketshop.trade.ShopTradeService;
import com.ecomarketshop.util.ItemMatcher;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理员商店 GUI 处理器。
 *
 * <p>显示 {@link ShopDataManager} 中配置的商品。玩家点击商品后根据物品可堆叠性：
 * <ul>
 *   <li>可堆叠物品左键 → 直接购买 1 个，无需确认</li>
 *   <li>不可堆叠物品左键 → 打开 {@link ConfirmScreenHandler} 确认购买</li>
 * </ul>
 */
public class ShopScreenHandler extends AbstractTradeScreenHandler {

    // 填充物统一使用 GuiUtils 创建

    /**
     * 构造商店 GUI。
     *
     * @param syncId          同步 ID
     * @param playerInventory 玩家背包
     */
    public ShopScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ScreenHandlerType.GENERIC_9X6, syncId);
        addSlots(playerInventory);
        populateDisplay();
    }

    @Override
    protected boolean isValidTradeSlot(int slot, PlayerEntity player) {
        if (slot < 0 || slot >= GUI_SIZE) {
            return false;
        }
        ShopItemConfig config = ShopDataManager.getItemBySlot(slot);
        if (config == null) {
            return false;
        }
        // 售罄的商品不可点击
        return config.getStock() != 0;
    }

    @Override
    protected void triggerTrade(ServerPlayerEntity player, int slotIndex) {
        ShopItemConfig config = ShopDataManager.getItemBySlot(slotIndex);
        if (config == null || config.getStock() == 0) {
            player.sendMessage(Text.literal("§c该商品已售罄！"), false);
            return;
        }
        Item item = ItemMatcher.getItem(config.getId());
        if (item != null && item.getMaxCount() > 1) {
            // 可堆叠 → 直接购买 1 个，无需确认
            ShopTradeService.executePurchase(player, slotIndex, 1);
        } else {
            // 不可堆叠 → GUI 确认
            ConfirmScreenHandler.openShopBuy(player, slotIndex);
        }
    }

    @Override
    protected void refreshDisplay() {
        populateDisplay();
        sendContentUpdates();
    }

    /**
     * 填充 GUI 显示内容：商店商品 + 填充物。
     */
    private void populateDisplay() {
        // 清空显示
        for (int i = 0; i < GUI_SIZE; i++) {
            getDisplayInventory().setStack(i, ItemStack.EMPTY);
        }

        // 放置商店商品
        for (ShopItemConfig config : ShopDataManager.getShopItems()) {
            int slot = config.getSlotIndex();
            if (slot >= 0 && slot < GUI_SIZE) {
                getDisplayInventory().setStack(slot, createDisplayStack(config));
            }
        }

        // 用填充物填满空槽
        for (int i = 0; i < GUI_SIZE; i++) {
            if (getDisplayInventory().getStack(i).isEmpty()) {
                getDisplayInventory().setStack(i, GuiUtils.createFiller());
            }
        }
    }

    /**
     * 为商品创建显示物品（带自定义名称和价格 Lore）。
     */
    private ItemStack createDisplayStack(ShopItemConfig config) {
        Item item = ItemMatcher.getItem(config.getId());
        if (item == null) {
            return GuiUtils.createFiller();
        }

        String name = config.getDisplayName() != null ? config.getDisplayName() : config.getId();

        List<Text> lore = new ArrayList<>();
        lore.add(Text.literal("§7价格: §e" + config.getPrice() + " " + EconomyConfig.getCurrencyName()));
        if (config.isInfiniteStock()) {
            lore.add(Text.literal("§7库存: §a无限"));
        } else if (config.getStock() > 0) {
            lore.add(Text.literal("§7库存: §e" + config.getStock()));
        } else {
            lore.add(Text.literal("§7库存: §c售罄"));
        }
        lore.add(Text.literal("§a左键点击购买 1 个"));
        if (item.getMaxCount() > 1) {
            lore.add(Text.literal("§e右键购买一组"));
            lore.add(Text.literal("§eShift+左键批量购买"));
        }

        return GuiUtils.createInfoItem(item, "§f" + name, lore);
    }
}
