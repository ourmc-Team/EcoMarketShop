package com.ecomarketshop.config;

import com.ecomarketshop.EcoMarketShop;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 经济系统配置 — 读取 {@code config/economy_config.json}。
 *
 * <p>配置字段：
 * <ul>
 *   <li>{@code currency_name} — 货币名称（默认 "Coin"）</li>
 *   <li>{@code initial_balance} — 新玩家初始余额（默认 200）</li>
 *   <li>{@code enable_decimal} — 是否启用小数（默认 false，当前版本仅支持整数）</li>
 *   <li>{@code market_fee_rate} — 全球市场交易手续费比例（默认 0.05 = 5%）</li>
 * </ul>
 */
public final class EconomyConfig {

    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("economy_config.json");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // 默认配置
    private static final String DEFAULT_CURRENCY_NAME = "Coin";
    private static final int DEFAULT_INITIAL_BALANCE = 200;
    private static final boolean DEFAULT_ENABLE_DECIMAL = false;
    private static final double DEFAULT_MARKET_FEE_RATE = 0.05;

    // 运行时配置值
    private static String currencyName = DEFAULT_CURRENCY_NAME;
    private static int initialBalance = DEFAULT_INITIAL_BALANCE;
    private static boolean enableDecimal = DEFAULT_ENABLE_DECIMAL;
    private static double marketFeeRate = DEFAULT_MARKET_FEE_RATE;

    private EconomyConfig() {}

    /**
     * 加载配置文件。若文件不存在，自动生成默认配置。
     */
    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            createDefaultConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH);
            ConfigData data = GSON.fromJson(json, ConfigData.class);
            if (data != null) {
                currencyName = data.currency_name != null ? data.currency_name : DEFAULT_CURRENCY_NAME;
                initialBalance = data.initial_balance >= 0 ? data.initial_balance : DEFAULT_INITIAL_BALANCE;
                enableDecimal = data.enable_decimal;
                marketFeeRate = data.market_fee_rate >= 0 ? data.market_fee_rate : DEFAULT_MARKET_FEE_RATE;
            }
        } catch (Exception e) {
            EcoMarketShop.LOGGER.error("[EcoMarketShop] 经济配置加载失败，使用默认值", e);
        }
    }

    /**
     * 创建默认配置文件。
     */
    private static void createDefaultConfig() {
        try {
            ConfigData data = new ConfigData();
            data.currency_name = DEFAULT_CURRENCY_NAME;
            data.initial_balance = DEFAULT_INITIAL_BALANCE;
            data.enable_decimal = DEFAULT_ENABLE_DECIMAL;
            data.market_fee_rate = DEFAULT_MARKET_FEE_RATE;
            Files.writeString(CONFIG_PATH, GSON.toJson(data));
            EcoMarketShop.LOGGER.info("[EcoMarketShop] 已生成默认经济配置: {}", CONFIG_PATH);
        } catch (IOException e) {
            EcoMarketShop.LOGGER.error("[EcoMarketShop] 无法创建经济配置文件", e);
        }
    }

    /** 获取货币名称 */
    public static String getCurrencyName() {
        return currencyName;
    }

    /** 获取新玩家初始余额 */
    public static int getInitialBalance() {
        return initialBalance;
    }

    /** 是否启用小数 */
    public static boolean isEnableDecimal() {
        return enableDecimal;
    }

    /** 获取全球市场交易手续费比例（如 0.05 表示 5%） */
    public static double getMarketFeeRate() {
        return marketFeeRate;
    }

    /** 配置数据内部类 */
    private static class ConfigData {
        String currency_name;
        int initial_balance;
        boolean enable_decimal;
        double market_fee_rate;
    }
}
