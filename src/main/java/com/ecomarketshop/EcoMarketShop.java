package com.ecomarketshop;

import com.ecomarketshop.command.EcoCommand;
import com.ecomarketshop.command.MarketCommand;
import com.ecomarketshop.command.ShopCommand;
import com.ecomarketshop.data.EconomyDataManager;
import com.ecomarketshop.data.MarketDataManager;
import com.ecomarketshop.config.EconomyConfig;
import com.ecomarketshop.data.ShopDataManager;
import com.ecomarketshop.util.CooldownManager;
import com.ecomarketshop.util.TradeLogger;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * EcoMarketShop 主入口类。
 *
 * <p>负责在服务端启动时：
 * <ul>
 *   <li>加载经济、商店、市场数据</li>
 *   <li>注册玩家进出事件（自动加载/保存余额）</li>
 *   <li>注册所有命令</li>
 * </ul>
 */
public class EcoMarketShop implements ModInitializer {

    /** 模组 ID */
    public static final String MOD_ID = "ecomarketshop";

    /** 日志记录器 */
    public static final Logger LOGGER = LoggerFactory.getLogger("EcoMarketShop");

    /** 服务端注册表查找器（用于 ItemStack 序列化，保留附魔等组件），服务端启动时缓存 */
    private static volatile RegistryWrapper.WrapperLookup registryLookup;

    /** 获取缓存的注册表查找器（服务端启动后可用） */
    public static RegistryWrapper.WrapperLookup getRegistryLookup() {
        return registryLookup;
    }

    @Override
    public void onInitialize() {
        LOGGER.info("[EcoMarketShop] 开始初始化...");

        // ---- 1. 加载数据与配置 ----
        EconomyConfig.load();
        EconomyDataManager.loadAll();
        ShopDataManager.loadConfig();
        MarketDataManager.loadAll();

        // ---- 2. 注册玩家进出事件 ----
        registerPlayerEvents();

        // ---- 3. 注册服务端生命周期事件 ----
        registerServerLifecycleEvents();

        // ---- 4. 注册命令 ----
        EcoCommand.register();
        ShopCommand.register();
        MarketCommand.register();

        LOGGER.info("[EcoMarketShop] 初始化完成！经济系统、商店、市场已就绪。");
    }

    /**
     * 注册玩家连接/断开事件：
     * <ul>
     *   <li>JOIN: 若玩家无账户，自动创建并赋予初始余额</li>
     *   <li>DISCONNECT: 保存经济数据（脏标记检查）+ 清理冷却记录</li>
     * </ul>
     */
    private void registerPlayerEvents() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            UUID uuid = player.getUuid();
            if (!EconomyDataManager.hasAccount(uuid)) {
                int initialBalance = EconomyConfig.getInitialBalance();
                EconomyDataManager.setBalance(uuid, initialBalance);
                LOGGER.info("[EcoMarketShop] 为新玩家 {} 创建账户，初始余额: {}",
                        player.getName().getString(), initialBalance);
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID uuid = handler.getPlayer().getUuid();
            // 仅当余额发生变动时才执行写盘（脏标记优化）
            EconomyDataManager.saveAll();
            // 清理玩家冷却记录，防止内存泄漏
            CooldownManager.clear(uuid);
        });
    }

    /**
     * 注册服务端生命周期事件：
     * <ul>
     *   <li>SERVER_STOPPING: 关闭前强制保存所有数据 + 关闭日志写入器</li>
     * </ul>
     */
    private void registerServerLifecycleEvents() {
        // 服务端启动：缓存注册表查找器（供 ItemStack 序列化保留附魔等数据组件）
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
            registryLookup = server.getRegistryManager());

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("[EcoMarketShop] 服务器正在关闭，保存所有数据...");
            // 强制保存（无论是否有脏标记）
            EconomyDataManager.forceSave();
            MarketDataManager.saveAll();
            ShopDataManager.saveConfig();
            // 关闭日志写入器，确保缓冲区数据落盘
            TradeLogger.shutdown();
            LOGGER.info("[EcoMarketShop] 数据保存完成。");
        });
    }
}
