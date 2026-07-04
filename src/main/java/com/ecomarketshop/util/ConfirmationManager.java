package com.ecomarketshop.util;

import com.ecomarketshop.EcoMarketShop;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天框交易确认管理器 — 替代原 GUI 内两步点击确认。
 *
 * <p>工作流程：
 * <ol>
 *   <li>玩家在 GUI 中点击商品/挂单 → 触发方调用 {@link #startPending}，传入操作详情与
 *       onConfirm/onCancel 回调，并关闭 GUI；本管理器向玩家发送聊天消息，含操作详情与
 *       可点击的 [确认]/[取消] 按钮。</li>
 *   <li>玩家点击 [确认] → 执行 {@code /eco confirm} → {@link #confirm} 执行 onConfirm。</li>
 *   <li>玩家点击 [取消] → 执行 {@code /eco cancel} → {@link #cancel} 执行 onCancel。</li>
 * </ol>
 *
 * <p>安全设计：
 * <ul>
 *   <li>每位玩家同一时间仅保留一条待确认；新请求会先 onCancel 旧的。</li>
 *   <li>60 秒超时自动取消（执行 onCancel）。</li>
 *   <li>玩家退出时调用 {@link #clear}，执行 onCancel 返还已捕获的物品。</li>
 *   <li>回调异常被捕获并记录，不影响后续流程。</li>
 * </ul>
 */
public final class ConfirmationManager {

    /** 待确认有效期（毫秒） */
    private static final long EXPIRY_MS = 60_000;

    /** 玩家 UUID → 待确认操作 */
    private static final ConcurrentHashMap<UUID, PendingConfirmation> PENDING = new ConcurrentHashMap<>();

    private ConfirmationManager() {}

    /**
     * 待确认操作上下文。
     */
    public static final class PendingConfirmation {
        /** 操作详情（聊天中展示） */
        public final Text description;
        /** 确认时执行 */
        public final Runnable onConfirm;
        /** 取消/超时/退出时执行（如返还物品） */
        public final Runnable onCancel;
        /** 过期时间戳（毫秒） */
        public final long expiry;

        public PendingConfirmation(Text description, Runnable onConfirm, Runnable onCancel) {
            this.description = description;
            this.onConfirm = onConfirm;
            this.onCancel = onCancel;
            this.expiry = System.currentTimeMillis() + EXPIRY_MS;
        }
    }

    /**
     * 启动一条待确认操作：若已存在旧的，先执行其 onCancel；随后发送聊天确认消息。
     *
     * @param player 玩家
     * @param pc     待确认上下文
     */
    public static void startPending(ServerPlayerEntity player, PendingConfirmation pc) {
        PendingConfirmation existing = PENDING.put(player.getUuid(), pc);
        if (existing != null) {
            safeRun(existing.onCancel, player.getUuid(), "旧待确认 onCancel");
            player.sendMessage(Text.literal("§7上一笔待确认操作已自动取消。"), false);
        }
        player.sendMessage(buildMessage(pc.description), false);
    }

    /**
     * 确认当前待确认操作（由 /eco confirm 调用）。
     */
    public static void confirm(ServerPlayerEntity player) {
        PendingConfirmation pc = PENDING.remove(player.getUuid());
        if (pc == null) {
            player.sendMessage(Text.literal("§c当前没有待确认的操作。"), false);
            return;
        }
        if (System.currentTimeMillis() > pc.expiry) {
            player.sendMessage(Text.literal("§c确认已超时，操作已取消。"), false);
            safeRun(pc.onCancel, player.getUuid(), "超时 onCancel");
            return;
        }
        safeRun(pc.onConfirm, player.getUuid(), "onConfirm");
    }

    /**
     * 取消当前待确认操作（由 /eco cancel 调用）。
     */
    public static void cancel(ServerPlayerEntity player) {
        PendingConfirmation pc = PENDING.remove(player.getUuid());
        if (pc == null) {
            player.sendMessage(Text.literal("§c当前没有待确认的操作。"), false);
            return;
        }
        player.sendMessage(Text.literal("§7操作已取消。"), false);
        safeRun(pc.onCancel, player.getUuid(), "cancel onCancel");
    }

    /**
     * 清除玩家待确认（退出时调用），执行 onCancel 返还物品。
     *
     * @param uuid 玩家 UUID
     */
    public static void clear(UUID uuid) {
        PendingConfirmation pc = PENDING.remove(uuid);
        if (pc != null) {
            safeRun(pc.onCancel, uuid, "disconnect onCancel");
        }
    }

    /**
     * 构建聊天确认消息：分隔线 + 详情 + 可点击按钮。
     */
    private static Text buildMessage(Text description) {
        MutableText msg = Text.literal("§e══════════════════════════\n");
        msg.append(Text.literal("§e你即将进行以下操作：\n"));
        msg.append(description);
        msg.append(Text.literal("\n"));

        // [确认] 按钮
        MutableText confirmBtn = Text.literal(" §a§l[确认] ");
        confirmBtn.setStyle(Style.EMPTY
            .withClickEvent(new ClickEvent.RunCommand("/eco confirm"))
            .withHoverEvent(new HoverEvent.ShowText(
                Text.literal("§a点击确认本次操作"))));

        // [取消] 按钮
        MutableText cancelBtn = Text.literal(" §c§l[取消] ");
        cancelBtn.setStyle(Style.EMPTY
            .withClickEvent(new ClickEvent.RunCommand("/eco cancel"))
            .withHoverEvent(new HoverEvent.ShowText(
                Text.literal("§c点击取消本次操作"))));

        msg.append(confirmBtn);
        msg.append(cancelBtn);
        msg.append(Text.literal("\n§e══════════════════════════"));
        return msg;
    }

    /**
     * 安全执行回调，捕获异常并记录。
     */
    private static void safeRun(Runnable r, UUID uuid, String label) {
        if (r == null) {
            return;
        }
        try {
            r.run();
        } catch (Exception e) {
            EcoMarketShop.LOGGER.error("[EcoMarketShop] 确认回调执行异常: uuid={}, label={}", uuid, label, e);
        }
    }
}
