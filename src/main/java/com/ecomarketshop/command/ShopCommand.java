package com.ecomarketshop.command;

import com.ecomarketshop.gui.ShopScreenHandler;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * 商店命令注册 — /shop
 *
 * <p>玩家输入 {@code /shop} 打开管理员商店 GUI（54 格箱子界面）。
 */
public final class ShopCommand {

    private ShopCommand() {}

    /**
     * 注册商店命令。
     */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("shop")
                .executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayer();
                    player.openHandledScreen(new NamedScreenHandlerFactory() {
                        @Override
                        public net.minecraft.screen.ScreenHandler createMenu(int syncId, net.minecraft.entity.player.PlayerInventory inv, net.minecraft.entity.player.PlayerEntity p) {
                            return new ShopScreenHandler(syncId, inv);
                        }
                        @Override
                        public Text getDisplayName() {
                            return Text.literal("§6管理员商店");
                        }
                    });
                    return 1;
                })
            );
        });
    }
}
