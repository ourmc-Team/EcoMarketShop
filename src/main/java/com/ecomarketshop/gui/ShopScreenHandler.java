package com.ecomarketshop.gui;

import com.ecomarketshop.config.EconomyConfig;
import com.ecomarketshop.config.ShopItemConfig;
import com.ecomarketshop.data.EconomyDataManager;
import com.ecomarketshop.data.ShopDataManager;
import com.ecomarketshop.trade.ShopTradeService;
import com.ecomarketshop.util.ConfirmationManager.PendingConfirmation;
import com.ecomarketshop.util.ItemMatcher;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理员商店 GUI 处理器。
 *
 * <p>显示 {@link ShopDataManager} 中配置的商品，玩家点击商品后关闭 GUI 并在
 * 聊天框中通过 [确认]/[取消] 完成购买（实际交易由 {@link ShopTradeService} 执行）。
 */
public class ShopScreenHandler extends AbstractTradeScreenHandler {

    /** 填充物品（灰色玻璃板） */
    private static final ItemStack FILLER;

    static {
        FILLER = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        FILLER.set(DataComponentTypes.CUSTOM_NAME, Text.literal(" "));
    }

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
        // 从数据源获取配置以展示详情（实际扣费在确认时由 ShopTradeService 二次校验）
        ShopItemConfig config = ShopDataManager.getItemBySlot(slotIndex);
        if (config == null || config.getStock() == 0) {
            player.sendMessage(Text.literal("§c该商品已售罄！"), false);
            return;
        }
        String name = config.getDisplayName() != null ? config.getDisplayName() : config.getId();
        int price = config.getPrice();

        MutableText desc = Text.literal("");
        desc.append(Text.literal("§7类型: §f管理员商店购买\n"));
        desc.append(Text.literal("§7商品: §f" + name + "\n"));
        desc.append(Text.literal("§7数量: §f1\n"));
        desc.append(Text.literal("§7价格: §e" + price + " " + EconomyConfig.getCurrencyName() + "\n"));
        desc.append(Text.literal("§7当前余额: §e" + EconomyDataManager.getBalance(player.getUuid())
            + " " + EconomyConfig.getCurrencyName()));

        final int slot = slotIndex;
        PendingConfirmation pc = new PendingConfirmation(
            desc,
            () -> ShopTradeService.executePurchase(player, slot),
            null
        );
        requestConfirmation(player, pc);
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
                getDisplayInventory().setStack(i, FILLER.copy());
            }
        }
    }

    /**
     * 为商品创建显示物品（带自定义名称和价格 Lore）。
     */
    private ItemStack createDisplayStack(ShopItemConfig config) {
        Item item = ItemMatcher.getItem(config.getId());
        if (item == null) {
            return FILLER.copy();
        }

        ItemStack display = new ItemStack(item);
        String name = config.getDisplayName() != null ? config.getDisplayName() : config.getId();
        display.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§f" + name));

        List<Text> lore = new ArrayList<>();
        lore.add(Text.literal("§7价格: §e" + config.getPrice() + " " + EconomyConfig.getCurrencyName()));
        if (config.isInfiniteStock()) {
            lore.add(Text.literal("§7库存: §a无限"));
        } else if (config.getStock() > 0) {
            lore.add(Text.literal("§7库存: §e" + config.getStock()));
        } else {
            lore.add(Text.literal("§7库存: §c售罄"));
        }
        lore.add(Text.literal("§a左键点击购买"));
        display.set(DataComponentTypes.LORE, new LoreComponent(lore));

        return display;
    }
}
