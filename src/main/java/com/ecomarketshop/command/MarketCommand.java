package com.ecomarketshop.command;

import com.ecomarketshop.EcoMarketShop;
import com.ecomarketshop.data.MarketDataManager;
import com.ecomarketshop.data.ShopDataManager;
import com.ecomarketshop.gui.AbstractTradeScreenHandler;
import com.ecomarketshop.gui.MarketListScreenHandler;
import com.ecomarketshop.gui.MarketMyListingsScreenHandler;
import com.ecomarketshop.gui.MarketScreenHandler;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * 市场命令注册 — /market
 *
 * <p>子命令：
 * <ul>
 *   <li>{@code /market} — 打开全球拍卖行 GUI（所有玩家）</li>
 *   <li>{@code /market sell <单价>} — 打开市场上架 GUI（所有玩家）</li>
 *   <li>{@code /market my} — 打开我的挂单 GUI，可下架自己的挂单（所有玩家）</li>
 *   <li>{@code /market reload} — 热重载商店/市场配置（OP only）</li>
 * </ul>
 */
public final class MarketCommand {

    private MarketCommand() {}

    /**
     * 注册市场命令。
     */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("market")
                // /market — 打开市场 GUI
                .executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayer();
                    player.openHandledScreen(new NamedScreenHandlerFactory() {
                        @Override
                        public net.minecraft.screen.ScreenHandler createMenu(int syncId, net.minecraft.entity.player.PlayerInventory inv, net.minecraft.entity.player.PlayerEntity p) {
                            return new MarketScreenHandler(syncId, inv);
                        }
                        @Override
                        public Text getDisplayName() {
                            return Text.literal("§6全球拍卖行");
                        }
                    });
                    return 1;
                })

                // /market sell <单价> — 打开市场上架 GUI
                .then(CommandManager.literal("sell")
                    .then(CommandManager.argument("price", IntegerArgumentType.integer(0))
                    .executes(ctx -> {
                        ServerPlayerEntity player = ctx.getSource().getPlayer();
                        final int price = ctx.getArgument("price", Integer.class);
                        player.openHandledScreen(new NamedScreenHandlerFactory() {
                            @Override
                            public net.minecraft.screen.ScreenHandler createMenu(int syncId, net.minecraft.entity.player.PlayerInventory inv, net.minecraft.entity.player.PlayerEntity p) {
                                return new MarketListScreenHandler(syncId, inv, price);
                            }
                            @Override
                            public Text getDisplayName() {
                                return Text.literal("§6市场上架");
                            }
                        });
                        return 1;
                    })))

                // /market my — 打开我的挂单 GUI（下架功能）
                .then(CommandManager.literal("my")
                    .executes(ctx -> {
                        ServerPlayerEntity player = ctx.getSource().getPlayer();
                        player.openHandledScreen(new NamedScreenHandlerFactory() {
                            @Override
                            public net.minecraft.screen.ScreenHandler createMenu(int syncId, net.minecraft.entity.player.PlayerInventory inv, net.minecraft.entity.player.PlayerEntity p) {
                                return new MarketMyListingsScreenHandler(syncId, inv, player.getUuid());
                            }
                            @Override
                            public Text getDisplayName() {
                                return Text.literal("§6我的挂单");
                            }
                        });
                        return 1;
                    }))

                // /market reload — 热重载配置
                .then(CommandManager.literal("reload")
                    .requires(src -> src.hasPermissionLevel(4))
                    .executes(ctx -> {
                        // 重新加载商店配置和市场数据（对应测试场景 3）
                        EcoMarketShop.LOGGER.info("[EcoMarketShop] 开始热重载...");

                        ShopDataManager.loadConfig();
                        MarketDataManager.loadAll();

                        // 强制关闭所有已打开的交易 GUI，防止显示过期数据
                        int closedCount = 0;
                        for (ServerPlayerEntity p : ctx.getSource().getServer().getPlayerManager().getPlayerList()) {
                            ScreenHandler handler = p.currentScreenHandler;
                            if (handler instanceof AbstractTradeScreenHandler) {
                                p.closeHandledScreen();
                                closedCount++;
                            }
                        }

                        int shopCount = ShopDataManager.getShopItems().size();
                        int marketCount = MarketDataManager.getTotalListingCount();

                        ctx.getSource().sendMessage(Text.literal(
                            "§a商店和市场配置已热重载！" +
                            "\n§7商店商品: §f" + shopCount + " 件" +
                            "\n§7市场挂单: §f" + marketCount + " 条" +
                            (closedCount > 0 ? "\n§e已刷新 " + closedCount + " 名玩家的 GUI" : "")));

                        EcoMarketShop.LOGGER.info(
                            "[EcoMarketShop] 热重载完成: 商店商品={}件, 市场挂单={}条, 刷新GUI={}人",
                            shopCount, marketCount, closedCount);
                        return 1;
                    }))
            );
        });
    }
}
