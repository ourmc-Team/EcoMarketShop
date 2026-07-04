package com.ecomarketshop.util;

import com.ecomarketshop.EcoMarketShop;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 交易日志工具 — 使用 {@link BufferedWriter} 追加写入 {@code logs/trade.log}。
 *
 * <p>日志格式：
 * <pre>{@code
 * [2026-01-15 14:30:00] [Steve] [SHOP_BUY] [minecraft:diamond] [1] [100] [0]
 * }</pre>
 *
 * <p>字段含义：[时间] [玩家名] [操作类型] [物品ID] [数量] [总额] [手续费]
 *
 * <p>性能优化：
 * <ul>
 *   <li>使用 {@link BufferedWriter} 缓冲写入，减少磁盘 IO 次数</li>
 *   <li>使用 {@code synchronized} 保证多线程写入安全</li>
 *   <li>写入失败不影响主流程，仅打印错误日志</li>
 * </ul>
 *
 * <p>生命周期：应在服务器关闭时调用 {@link #shutdown()} 以确保缓冲区数据落盘。
 */
public final class TradeLogger {

    private static final Path LOG_PATH = Path.of("logs", "trade.log");

    private static final DateTimeFormatter FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 缓冲写入器（懒初始化，线程安全） */
    private static volatile BufferedWriter writer;

    private TradeLogger() {}

    /**
     * 获取或初始化缓冲写入器。
     */
    private static BufferedWriter getWriter() throws IOException {
        if (writer == null) {
            synchronized (TradeLogger.class) {
                if (writer == null) {
                    // 确保 logs 目录存在
                    if (!Files.exists(LOG_PATH.getParent())) {
                        Files.createDirectories(LOG_PATH.getParent());
                    }
                    writer = Files.newBufferedWriter(LOG_PATH,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
            }
        }
        return writer;
    }

    /**
     * 记录一笔交易日志。
     *
     * <p>使用 {@code synchronized} 保证多线程安全，
     * 内部通过 {@link BufferedWriter} 缓冲写入，减少磁盘 IO。
     *
     * @param playerName 玩家名
     * @param action     操作类型（SHOP_BUY / MARKET_BUY / MARKET_LIST 等）
     * @param itemId     物品 ID
     * @param amount     数量
     * @param price      总额
     * @param fee        手续费
     */
    public static synchronized void log(String playerName, String action, String itemId,
                                        int amount, int price, int fee) {
        try {
            String line = String.format("[%s] [%s] [%s] [%s] [%d] [%d] [%d]",
                LocalDateTime.now().format(FORMATTER),
                playerName, action, itemId, amount, price, fee);
            BufferedWriter w = getWriter();
            w.write(line);
            w.newLine();
            w.flush();
        } catch (IOException e) {
            // 日志写入失败不应影响主流程
            EcoMarketShop.LOGGER.error("[EcoMarketShop] 交易日志写入失败", e);
        }
    }

    /**
     * 关闭写入器，确保缓冲区数据全部落盘。
     * 应在服务器关闭时调用。
     */
    public static synchronized void shutdown() {
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException e) {
                EcoMarketShop.LOGGER.error("[EcoMarketShop] 交易日志关闭失败", e);
            }
            writer = null;
        }
    }
}
