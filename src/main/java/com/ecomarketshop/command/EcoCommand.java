package com.ecomarketshop.command;

import com.ecomarketshop.EcoMarketShop;
import com.ecomarketshop.config.EconomyConfig;
import com.ecomarketshop.data.EconomyDataManager;
import com.ecomarketshop.util.ConfirmationManager;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.command.argument.GameProfileArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Collection;

/**
 * 经济命令注册 — /eco
 *
 * <p>子命令：
 * <ul>
 *   <li>{@code /eco} — 查询自己的余额（所有玩家）</li>
 *   <li>{@code /eco set <玩家> <数量>} — 设置余额（OP only）</li>
 *   <li>{@code /eco add <玩家> <数量>} — 增加余额（OP only）</li>
 *   <li>{@code /eco reduce <玩家> <数量>} — 扣减余额（OP only，余额不足拒绝，不变负）</li>
 *   <li>{@code /eco view <玩家>} — 查看指定玩家余额（OP only）</li>
 *   <li>{@code /eco confirm} — 确认聊天框中的待确认交易（玩家自助）</li>
 *   <li>{@code /eco cancel} — 取消聊天框中的待确认交易（玩家自助）</li>
 * </ul>
 */
public final class EcoCommand {

    private EcoCommand() {}

    /**
     * 注册经济命令。
     */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {

            dispatcher.register(CommandManager.literal("eco")
                // /eco — 查询余额
                .executes(ctx -> {
                    ServerPlayerEntity player;
                    try {
                        player = ctx.getSource().getPlayer();
                    } catch (Exception e) {
                        ctx.getSource().sendMessage(Text.literal(
                            "§c此命令只能由玩家执行，控制台请使用 /eco view <玩家>"));
                        return 0;
                    }
                    int balance = EconomyDataManager.getBalance(player.getUuid());
                    ctx.getSource().sendMessage(Text.literal(
                        "§e你的余额: §f" + balance + " " + EconomyConfig.getCurrencyName()));
                    return 1;
                })

                // /eco set <玩家> <数量> — OP 设置余额
                .then(CommandManager.literal("set")
                    .requires(src -> src.hasPermissionLevel(4))
                    .then(CommandManager.argument("target", GameProfileArgumentType.gameProfile())
                    .then(CommandManager.argument("amount", IntegerArgumentType.integer(0))
                    .executes(ctx -> {
                        Collection<GameProfile> profiles =
                            GameProfileArgumentType.getProfileArgument(ctx, "target");
                        int amount = ctx.getArgument("amount", Integer.class);
                        int count = 0;
                        for (GameProfile profile : profiles) {
                            if (profile.getId() != null) {
                                EconomyDataManager.setBalance(profile.getId(), amount);
                                count++;
                                ServerPlayerEntity targetPlayer =
                                    ctx.getSource().getServer().getPlayerManager()
                                        .getPlayer(profile.getId());
                                if (targetPlayer != null) {
                                    targetPlayer.sendMessage(Text.literal(
                                        "§e[系统] §a你的余额已被设置为: §f" + amount + " " +
                                        EconomyConfig.getCurrencyName()));
                                }
                            }
                        }
                        ctx.getSource().sendMessage(Text.literal(
                            "§a已将 " + count + " 名玩家的余额设置为: " + amount + " " +
                            EconomyConfig.getCurrencyName()));
                        EcoMarketShop.LOGGER.info("[EcoMarketShop] OP {} 设置余额: 目标={}, 金额={}",
                            ctx.getSource().getName(), count + "名玩家", amount);
                        return 1;
                    }))))

                // /eco add <玩家> <数量> — OP 增减余额
                .then(CommandManager.literal("add")
                    .requires(src -> src.hasPermissionLevel(4))
                    .then(CommandManager.argument("target", GameProfileArgumentType.gameProfile())
                    .then(CommandManager.argument("amount", IntegerArgumentType.integer())
                    .executes(ctx -> {
                        Collection<GameProfile> profiles =
                            GameProfileArgumentType.getProfileArgument(ctx, "target");
                        int amount = ctx.getArgument("amount", Integer.class);
                        int count = 0;
                        for (GameProfile profile : profiles) {
                            if (profile.getId() != null) {
                                int newBalance = EconomyDataManager.addBalance(profile.getId(), amount);
                                count++;
                                ServerPlayerEntity targetPlayer =
                                    ctx.getSource().getServer().getPlayerManager()
                                        .getPlayer(profile.getId());
                                if (targetPlayer != null) {
                                    targetPlayer.sendMessage(Text.literal(
                                        "§e[系统] §a你的余额已调整: " +
                                        (amount >= 0 ? "§a+" : "§c") + amount + " " +
                                        EconomyConfig.getCurrencyName() +
                                        " §7(新余额: " + newBalance + ")"));
                                }
                            }
                        }
                        ctx.getSource().sendMessage(Text.literal(
                            "§a已调整 " + count + " 名玩家的余额: " +
                            (amount >= 0 ? "+" : "") + amount + " " +
                            EconomyConfig.getCurrencyName()));
                        EcoMarketShop.LOGGER.info("[EcoMarketShop] OP {} 调整余额: 目标={}, 金额={}",
                            ctx.getSource().getName(), count + "名玩家",
                            (amount >= 0 ? "+" : "") + amount);
                        return 1;
                    }))))

                // /eco reduce <玩家> <数量> — OP 扣减余额（余额不足拒绝，不变负）
                .then(CommandManager.literal("reduce")
                    .requires(src -> src.hasPermissionLevel(4))
                    .then(CommandManager.argument("target", GameProfileArgumentType.gameProfile())
                    .then(CommandManager.argument("amount", IntegerArgumentType.integer(0))
                    .executes(ctx -> {
                        Collection<GameProfile> profiles =
                            GameProfileArgumentType.getProfileArgument(ctx, "target");
                        int amount = ctx.getArgument("amount", Integer.class);
                        int count = 0;
                        int failed = 0;
                        for (GameProfile profile : profiles) {
                            if (profile.getId() == null) {
                                continue;
                            }
                            if (EconomyDataManager.tryDeduct(profile.getId(), amount)) {
                                count++;
                                ServerPlayerEntity targetPlayer =
                                    ctx.getSource().getServer().getPlayerManager()
                                        .getPlayer(profile.getId());
                                if (targetPlayer != null) {
                                    targetPlayer.sendMessage(Text.literal(
                                        "§e[系统] §c你的余额已被扣减: -" + amount + " " +
                                        EconomyConfig.getCurrencyName() +
                                        " §7(新余额: " + EconomyDataManager.getBalance(profile.getId()) + ")"));
                                }
                            } else {
                                failed++;
                            }
                        }
                        ctx.getSource().sendMessage(Text.literal(
                            "§a已扣减 " + count + " 名玩家余额: -" + amount + " " +
                            EconomyConfig.getCurrencyName() +
                            (failed > 0 ? " §c(" + failed + " 名余额不足，未扣减)" : "")));
                        EcoMarketShop.LOGGER.info("[EcoMarketShop] OP {} 扣减余额: 目标={}名, 金额={}, 失败={}名",
                            ctx.getSource().getName(), count, amount, failed);
                        return 1;
                    }))))

                // /eco view <玩家> — OP 查看指定玩家余额
                .then(CommandManager.literal("view")
                    .requires(src -> src.hasPermissionLevel(4))
                    .then(CommandManager.argument("target", GameProfileArgumentType.gameProfile())
                    .executes(ctx -> {
                        Collection<GameProfile> profiles =
                            GameProfileArgumentType.getProfileArgument(ctx, "target");
                        for (GameProfile profile : profiles) {
                            if (profile.getId() == null) {
                                continue;
                            }
                            int balance = EconomyDataManager.getBalance(profile.getId());
                            String name = profile.getName() != null
                                ? profile.getName() : profile.getId().toString();
                            ctx.getSource().sendMessage(Text.literal(
                                "§e" + name + " §7的余额: §f" + balance + " " +
                                EconomyConfig.getCurrencyName()));
                        }
                        return 1;
                    })))

                // /eco confirm — 确认聊天框中的待确认交易（由可点击按钮触发）
                .then(CommandManager.literal("confirm")
                    .executes(ctx -> {
                        ConfirmationManager.confirm(ctx.getSource().getPlayer());
                        return 1;
                    }))

                // /eco cancel — 取消聊天框中的待确认交易（由可点击按钮触发）
                .then(CommandManager.literal("cancel")
                    .executes(ctx -> {
                        ConfirmationManager.cancel(ctx.getSource().getPlayer());
                        return 1;
                    }))
            );
        });
    }
}
