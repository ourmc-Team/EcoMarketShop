# GUI 确认功能改造实现计划

> **范围**：将原有的聊天栏 [确认]/[取消] 模式**全部替换**为 GUI 界面确认。
>
> **涉及场景**：管理员商店左键（不可堆叠）/ 右键购买一组、市场购买、市场上架。

---

## 目录

1. [背景与动机](#1-背景与动机)
2. [交互总览](#2-交互总览)
3. [GUI 布局设计](#3-gui-布局设计)
4. [新增类：ConfirmScreenHandler](#4-新增类confirmscreenhandler)
5. [改造点详解](#5-改造点详解)
6. [文件变更汇总](#6-文件变更汇总)
7. [实现步骤与建议顺序](#7-实现步骤与建议顺序)
8. [边界情况与安全设计](#8-边界情况与安全设计)
9. [清理工作](#9-清理工作)
10. [测试要点](#10-测试要点)

---

## 1. 背景与动机

### 1.1 当前问题

现有确认机制依赖 `ConfirmationManager`：玩家在 GUI 中点击商品 → GUI 关闭 → 聊天栏收到可点击 [确认]/[取消] 消息。该模式存在以下不足：

- **交互断裂**：GUI 关闭后玩家需要切换到聊天栏操作，流程不连贯
- **信息易丢失**：聊天消息可能被其他消息淹没
- **操作路径长**：点击商品 → 关闭 GUI → 点击聊天按钮，步骤多

### 1.2 改造目标

- **统一使用 GUI 确认**，不再依赖聊天栏
- **减少不必要的确认**：商店购买可堆叠物品时，左键直接买 1 个无需确认
- **保持安全**：所有交易逻辑仍在服务端 `synchronized` 块内执行

### 1.3 各场景确认逻辑汇总

| 场景 | 操作 | 确认方式 |
|------|------|---------|
| 商店 — 可堆叠物品 | 左键买 1 个 | **无需确认**，直接执行 |
| 商店 — 不可堆叠物品 | 左键买 1 个 | 打开 `ConfirmScreenHandler` |
| 商店 — 可堆叠物品 | 右键购买一组 | 打开 `ConfirmScreenHandler`（显示数量/总价） |
| 商店 — 可堆叠物品 | Shift+左键批量购买 | **保持现状**（`ShopQuantityScreenHandler` 内已有确认） |
| 市场 | 左键购买挂单 | 打开 `ConfirmScreenHandler` |
| 市场 | 确认上架 | 打开 `ConfirmScreenHandler`（确认后创建挂单，取消则返还物品） |

---

## 2. 交互总览

### 2.1 管理员商店流程

```
玩家打开 /shop (ShopScreenHandler)
         │
    ┌────┴─────────────────────────────────────┐
    │                                            │
左键点击                                     右键点击
    │                                            │
    ├── 可堆叠 ──→ executePurchase(1)             │
    │              (无需确认)                     │
    ├── 不可堆叠 ──→ ConfirmScreenHandler          │
    │                [确认] → executePurchase(1)   │
    │                [取消] → 关闭                 │
    │                                            ├── 可堆叠 ──→ 计算可购买数量
    │                                            │             → ConfirmScreenHandler
    │                                            │               [确认] → executeBulkPurchase
    │                                            │               [取消] → 关闭
    │                                            │
Shift+左键                                      │
    │                                            │
    ├── 可堆叠 ──→ ShopQuantityScreenHandler      │
    │               [确认] → executePurchase(qty) │
    │               [取消] → 关闭                 │
    └── 不可堆叠 ──→ (同左键) ConfirmScreenHandler │
```

### 2.2 市场购买流程

```
玩家打开 /market (MarketScreenHandler)
         │
    点击挂单
         │
         ▼
ConfirmScreenHandler
  [确认] → MarketTradeService.executePurchase()
  [取消] → 关闭
```

### 2.3 市场上架流程

```
玩家执行 /market sell <单价> (MarketListScreenHandler)
         │
    拖入物品 → 点击 [确认上架]
         │
         ▼
捕获输入区物品 → 校验耐久 → 分类（可上架/返还）
         │
         ▼
ConfirmScreenHandler
  [确认] → 创建挂单 + 返还耐久不足物品
  [取消] → 全部返还玩家
```

---

## 3. GUI 布局设计

### 3.1 ConfirmScreenHandler 布局

使用 `ScreenHandlerType.GENERIC_9X3`（27 格），布局如下：

```
┌─────────────────────────────────────────────────────┐
│ [💎钻石] │  物品: 钻石  │  数量: 1  │  价格: 100  │   │  ← Row 0 (slots 0-7)
│           │  单价: 100   │  总价: 100 │  余额: 5000 │   │      信息展示区
├─────────────────────────────────────────────────────┤
│                                                      │  ← Row 1 (slots 9-17)
│                   详细说明区域                          │      详细文字说明
│          (使用纸 + Lore 展示多行信息)                    │
│                                                      │
├─────────────────────────────────────────────────────┤
│           │           │ [✓确认] │ [✗取消] │           │  ← Row 2 (slots 18-26)
│           │           │         │         │           │      操作区
└─────────────────────────────────────────────────────┘
```

### 3.2 槽位分配明细

| 槽位 | 用途 | 说明 |
|------|------|------|
| 0 | 物品图标 | 交易物品的实际图标（带数量） |
| 1–7 | 信息条目 | 使用 `PAPER` + Lore 展示（价格、数量、余额等） |
| 8 | 填充物（灰色玻璃板） | — |
| 9–17 | 详细说明区域 | 使用 `PAPER` + 多行 Lore 展示操作详情 |
| 18–21 | 填充物 | — |
| 22 | **[✓ 确认]** 按钮 | `EMERALD`，绿色名称 |
| 23 | 填充物 | — |
| 24 | **[✗ 取消]** 按钮 | `BARRIER` 或 `RED_WOOL`，红色名称 |
| 25–26 | 填充物 | — |

### 3.3 按钮物品样式

| 按钮 | 物品 | 名称颜色 |
|------|------|---------|
| `[✓ 确认]` | `EMERALD` | §a 绿色 |
| `[✗ 取消]` | `BARRIER` | §c 红色 |
| 信息条目 | `PAPER` | §f 白色 / §e 金色 |
| 填充物 | `GRAY_STAINED_GLASS_PANE` | 透明（空格名称） |

---

## 4. 新增类：ConfirmScreenHandler

### 4.1 文件路径

```
src/main/java/com/ecomarketshop/gui/ConfirmScreenHandler.java
```

### 4.2 类设计

```java
package com.ecomarketshop.gui;

/**
 * 通用 GUI 确认界面 — 替代原聊天栏确认。
 *
 * <p>使用 ScreenHandlerType.GENERIC_9X3（27 格），客户端无需安装模组。
 *
 * <p>通过静态工厂方法创建不同场景的确认界面：
 * <ul>
 *   <li>{@link #openShopBuy} — 管理员商店不可堆叠物品左键购买确认</li>
 *   <li>{@link #openShopBulkBuy} — 管理员商店右键购买一组确认</li>
 *   <li>{@link #openMarketBuy} — 市场购买确认</li>
 *   <li>{@link #openMarketSell} — 市场上架确认</li>
 * </ul>
 */
public class ConfirmScreenHandler extends ScreenHandler {

    private static final int ROWS = 3;
    private static final int COLS = 9;
    private static final int GUI_SIZE = ROWS * COLS; // 27

    // ---- 槽位常量 ----
    private static final int SLOT_ITEM_ICON = 0;
    // slots 1-7: 信息条目（由调用方填充）
    private static final int SLOT_INFO_START = 1;
    private static final int SLOT_INFO_END = 7;

    // slot 8: 填充物

    // slots 9-17: 详细说明区域

    // slots 18-21: 填充物
    private static final int SLOT_CONFIRM = 22;
    // slot 23: 填充物
    private static final int SLOT_CANCEL = 24;
    // slots 25-26: 填充物

    private final SimpleInventory displayInventory;
    private final Runnable onConfirm;
    private final Runnable onCancel;

    // ---- 填充物 ----
    private static final ItemStack FILLER;
    static {
        FILLER = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        FILLER.set(DataComponentTypes.CUSTOM_NAME, Text.literal(" "));
    }
}
```

### 4.3 核心方法

| 方法 | 说明 |
|------|------|
| `ConfirmScreenHandler(syncId, playerInventory, displayItems, infoText, onConfirm, onCancel)` | 私有构造，填充 GUI 布局 |
| `openShopBuy(player, slotIndex)` | 静态工厂：商店左键不可堆叠购买确认 |
| `openShopBulkBuy(player, slotIndex)` | 静态工厂：商店右键购买一组确认 |
| `openMarketBuy(player, listing)` | 静态工厂：市场购买确认 |
| `openMarketSell(player, pricePerUnit, listable, returns, allItems)` | 静态工厂：市场上架确认 |
| `onSlotClick(...)` | 处理确认/取消按钮点击 |
| `onClosed(...)` | 取消时执行 `onCancel`（返还物品） |
| `confirm()` | 执行 `onConfirm` 并关闭 GUI |
| `cancel()` | 执行 `onCancel` 并关闭 GUI |
| `populateDisplay(displayItems, infoText)` | 填充所有槽位 |

### 4.4 工厂方法伪代码

#### `openShopBuy`

```java
public static void openShopBuy(ServerPlayerEntity player, int slotIndex) {
    ShopItemConfig config = ShopDataManager.getItemBySlot(slotIndex);
    if (config == null || config.getStock() == 0) return;

    Item item = ItemMatcher.getItem(config.getId());
    // 确保调用方已过滤：仅不可堆叠走此路径
    // (可堆叠在 ShopScreenHandler.triggerTrade 中直接执行)

    String name = config.getDisplayName() != null ? config.getDisplayName() : config.getId();
    int price = config.getPrice();
    int balance = EconomyDataManager.getBalance(player.getUuid());

    List<ItemStack> infoItems = List.of(
        createInfoItem("§f物品: " + name),
        createInfoItem("§7数量: §e1"),
        createInfoItem("§7价格: §e" + price + " " + EconomyConfig.getCurrencyName()),
        createInfoItem("§7余额: §e" + balance + " " + EconomyConfig.getCurrencyName())
    );

    Text detailText = Text.literal("")
        .append(Text.literal("§7类型: §f管理员商店购买\n"))
        .append(Text.literal("§7商品: §f" + name + "\n"))
        .append(Text.literal("§7价格: §e" + price + " " + EconomyConfig.getCurrencyName() + "\n"))
        .append(Text.literal("§7确认后将直接从余额中扣除" + price + " " + EconomyConfig.getCurrencyName()));

    ItemStack icon = new ItemStack(item);
    Runnable onConfirm = () -> ShopTradeService.executePurchase(player, slotIndex);

    player.openHandledScreen(new NamedScreenHandlerFactory() { ... });
}
```

#### `openShopBulkBuy`

```java
public static void openShopBulkBuy(ServerPlayerEntity player, int slotIndex) {
    ShopItemConfig config = ShopDataManager.getItemBySlot(slotIndex);
    if (config == null || config.getStock() == 0) return;

    Item item = ItemMatcher.getItem(config.getId());
    if (item == null || item.getMaxCount() <= 1) return; // 仅可堆叠

    // 计算可购买数量（同 executeBulkPurchase 逻辑）
    int maxStack = item.getMaxCount();
    int price = config.getPrice();
    int maxAffordable = (price > 0)
        ? EconomyDataManager.getBalance(player.getUuid()) / price
        : maxStack;
    int maxStock = config.isInfiniteStock()
        ? maxStack
        : Math.min(config.getStock(), maxStack);
    int quantity = Math.min(maxStack, Math.min(maxAffordable, maxStock));

    if (quantity <= 0) {
        player.sendMessage(Text.literal("§c余额不足！"));
        return;
    }

    int totalPrice = price * quantity;
    // 构建展示信息...
    Runnable onConfirm = () -> ShopTradeService.executePurchase(player, slotIndex, quantity);

    player.openHandledScreen(new NamedScreenHandlerFactory() { ... });
}
```

#### `openMarketBuy`

```java
public static void openMarketBuy(ServerPlayerEntity player, MarketListing listing) {
    String itemName = listing.getItemStack() != null
        ? listing.getItemStack().getName().getString()
        : listing.getItemId();
    int totalCost = listing.getTotalCost();
    int fee = listing.getFee();

    // 构建展示信息...
    Runnable onConfirm = () -> MarketTradeService.executePurchase(player, listing.getId());

    player.openHandledScreen(new NamedScreenHandlerFactory() { ... });
}
```

#### `openMarketSell`

```java
public static void openMarketSell(ServerPlayerEntity player, int pricePerUnit,
                                   List<ItemStack> listable, List<ItemStack> returns,
                                   List<ItemStack> allItems) {
    // 构建展示信息，包含可上架列表和返还列表...
    Runnable onConfirm = () -> {
        for (ItemStack s : listable) {
            MarketListing listing = new MarketListing(
                player.getUuid(), player.getName().getString(), s, pricePerUnit);
            MarketDataManager.addListing(listing);
        }
        for (ItemStack s : returns) {
            if (!player.getInventory().insertStack(s))
                player.dropItem(s, false);
        }
        player.sendMessage(Text.literal("§a上架成功！"));
    };
    Runnable onCancel = () -> {
        for (ItemStack s : allItems) {
            if (!player.getInventory().insertStack(s))
                player.dropItem(s, false);
        }
        player.sendMessage(Text.literal("§7上架已取消，物品已返还。"));
    };

    player.openHandledScreen(new NamedScreenHandlerFactory() { ... });
}
```

### 4.5 `onClosed` 行为

```java
@Override
public void onClosed(PlayerEntity player) {
    if (onCancel != null && !isConfirmed) {  // isConfirmed 标记防止重复执行
        safeRun(onCancel);
    }
    super.onClosed(player);
}
```

> **注意**：使用 `isConfirmed` 布尔标记防止玩家按 Esc 退出后 `onCancel` 与后续逻辑冲突。点击 [确认] 时先设 `isConfirmed = true` 再关闭 GUI。

---

## 5. 改造点详解

### 5.1 `AbstractTradeScreenHandler.java`

**改动点**：

1. **移除 `requestConfirmation()` 方法**（不再需要聊天确认）

2. **右键购买一组改为先打开确认 GUI** — 原版直接调用 `executeBulkPurchase`，现改为：
   ```java
   // 在 onSlotClick 右键分支中，将：
   //   ShopTradeService.executeBulkPurchase(...)
   // 改为：
   //   ConfirmScreenHandler.openShopBulkBuy(...)
   ```

3. **移除 `ConfirmationManager` 相关 import**

4. **`onSlotClick` 中的左键逻辑**：保持调用 `triggerTrade()`，由子类决定行为

### 5.2 `ShopScreenHandler.java`

**改动点**：

`triggerTrade()` 中根据物品是否可堆叠决定行为：
- **可堆叠**：直接调用 `ShopTradeService.executePurchase(player, slotIndex, 1)`，**无需确认**
- **不可堆叠**：调用 `ConfirmScreenHandler.openShopBuy(player, slotIndex)`

```java
@Override
protected void triggerTrade(ServerPlayerEntity player, int slotIndex) {
    ShopItemConfig config = ShopDataManager.getItemBySlot(slotIndex);
    if (config == null || config.getStock() == 0) {
        player.sendMessage(Text.literal("§c该商品已售罄！"));
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
```

### 5.3 `ShopQuantityScreenHandler.java`

**无需修改**。该 GUI 已内建 confirm/cancel，且确认后直接调用 `ShopTradeService.executePurchase(player, shopSlotIndex, quantity)`。符合"批量购买需要确认"的要求。

### 5.4 `MarketScreenHandler.java`

**改动点**：

`triggerTrade()` 中不再构建 `PendingConfirmation`，改为调用 `ConfirmScreenHandler.openMarketBuy()`：

```java
@Override
protected void triggerTrade(ServerPlayerEntity player, int slotIndex) {
    MarketListing listing = MarketDataManager.getListingByPageIndex(slotIndex, currentPage);
    if (listing == null || listing.getAmount() <= 0) {
        player.sendMessage(Text.literal("§c该挂单已不存在！"));
        return;
    }
    ConfirmScreenHandler.openMarketBuy(player, listing);
}
```

### 5.5 `MarketListScreenHandler.java`

**改动点**：

`handleConfirm()` 中不再调用 `ConfirmationManager.startPending()`，改为调用 `ConfirmScreenHandler.openMarketSell()`：

```java
private void handleConfirm(ServerPlayerEntity player) {
    // 收集输入区物品、校验耐久、分类（listable / returns）...
    // ...原有逻辑保持不变...
    
    // 将：
    //   PendingConfirmation pc = new PendingConfirmation(desc, onConfirm, onCancel);
    //   ConfirmationManager.startPending(player, pc);
    // 改为：
    ConfirmScreenHandler.openMarketSell(player, pricePerUnit, listable, returns, items);
}
```

### 5.6 `EcoCommand.java`

**改动点**：

移除 `/eco confirm` 和 `/eco cancel` 子命令注册：

```java
// 删除以下两个子命令分支：
// .then(CommandManager.literal("confirm") ...)
// .then(CommandManager.literal("cancel") ...)
```

---

## 6. 文件变更汇总

| 操作 | 文件 | 说明 |
|------|------|------|
| **新增** | `gui/ConfirmScreenHandler.java` | 通用 GUI 确认界面 + 4 个静态工厂方法 |
| **修改** | `gui/AbstractTradeScreenHandler.java` | 移除 `requestConfirmation()`；右键改为打开确认 GUI |
| **修改** | `gui/ShopScreenHandler.java` | `triggerTrade()` 中可堆叠直接执行，不可堆叠打开确认 GUI |
| **修改** | `gui/MarketScreenHandler.java` | `triggerTrade()` 改为打开确认 GUI |
| **修改** | `gui/MarketListScreenHandler.java` | `handleConfirm()` 改为打开确认 GUI |
| **修改** | `command/EcoCommand.java` | 移除 `/eco confirm` 和 `/eco cancel` 子命令 |
| **删除** | `util/ConfirmationManager.java` | 整个类不再需要 |
| **无修改** | `gui/ShopQuantityScreenHandler.java` | 已有 GUI 内 confirm/cancel，不需改动 |
| **无修改** | `trade/ShopTradeService.java` | 交易执行逻辑不变 |
| **无修改** | `trade/MarketTradeService.java` | 交易执行逻辑不变 |

---

## 7. 实现步骤与建议顺序

```
Step 1: 创建 ConfirmScreenHandler.java      # 实现通用确认 GUI + 工厂方法
Step 2: 改造 AbstractTradeScreenHandler     # 移除 requestConfirmation，改右键为 GUI 确认
Step 3: 改造 ShopScreenHandler              # triggerTrade 按可堆叠性分流
Step 4: 改造 MarketScreenHandler            # triggerTrade 改为 GUI 确认
Step 5: 改造 MarketListScreenHandler        # handleConfirm 改为 GUI 确认
Step 6: 清理 EcoCommand                     # 删除 /eco confirm + /eco cancel
Step 7: 删除 ConfirmationManager.java       # 清理旧代码
Step 8: 编译测试                            # 确保无编译错误
```

---

## 8. 边界情况与安全设计

### 8.1 边界情况

| 场景 | 预期行为 |
|------|---------|
| 商店左键点击可堆叠物品（如钻石） | 直接购买 1 个，跳过确认 |
| 商店左键点击不可堆叠物品（如钻石剑） | 弹出确认 GUI |
| 商店右键点击可堆叠物品时余额不足 | 提示余额不足，不打开确认 GUI |
| 商店右键盘点购买一组后库存变化 | 确认时 `executePurchase` 重新校验 |
| 市场挂单在确认前被其他玩家买走 | 确认时重新获取 listing，不存在则拒绝 |
| 市场上架确认前玩家断开 | 通过 `player.disconnect` 事件处理（现有 `EcoMarketShop` 已注册） |
| 玩家在确认 GUI 中按 Esc | 执行 `onCancel`（市场：返还物品；商店：无操作） |
| 点击确认后快速关闭 GUI | 先执行 `onConfirm` 再 `closeHandledScreen`，保证原子性 |
| 确认 GUI 中点击玩家背包槽位 | 通过 `LockedSlot` 阻止，或忽略背包区域点击 |

### 8.2 安全设计

| 风险 | 缓解措施 |
|------|---------|
| TOCTOU 竞态 | `synchronized(ShopDataManager.class)` / `synchronized(MarketDataManager.class)` 保证原子性 |
| 客户端篡改显示数据 | 所有显示数据由服务端构造，GUI 使用 `LockedSlot` 只读 |
| 超买/超卖 | 确认回调中重新从数据源获取最新配置 |
| 操作过快 | `CooldownManager` 拦截高频点击 |
| 确认/取消双重执行 | `isConfirmed` 布尔标记防止重复触发 |
| 背包满 | `insertStack` 失败时 `dropItem` 掉落至地面 |

### 8.3 onCancel 执行时机

`onCancel` 在以下情况执行：
1. 玩家点击 [✗ 取消] 按钮
2. 玩家按 Esc 关闭 GUI（`onClosed` 中检测）
3. 玩家断开连接（由 `EcoMarketShop` 的 `PlayerDisconnect` 事件处理）

但 `onCancel` 不应在[确认]执行后触发。通过 `isConfirmed` 标记实现：

```java
private boolean isConfirmed = false;

private void confirm() {
    isConfirmed = true;
    safeRun(onConfirm);
    closeHandledScreen();
}

@Override
public void onClosed(PlayerEntity player) {
    if (!isConfirmed && onCancel != null) {
        safeRun(onCancel);
    }
    super.onClosed(player);
}
```

---

## 9. 清理工作

### 9.1 `ConfirmationManager` 依赖清理

删除 `ConfirmationManager.java` 后，需要处理所有引用：

| 引用位置 | 处理方式 |
|---------|---------|
| `AbstractTradeScreenHandler.java` | 移除 import 和 `requestConfirmation()` |
| `MarketListScreenHandler.java` | 替换为 `ConfirmScreenHandler.openMarketSell()` |
| `EcoCommand.java` | 移除 `/eco confirm` + `/eco cancel` |
| `EcoMarketShop.java`（如有引用） | 检查并移除 |

### 9.2 `PendingConfirmation` 清理

`PendingConfirmation` 内联类定义在 `ConfirmationManager.java` 中，随文件删除自动清理。确保没有其他引用。

### 9.3 `requestConfirmation()` 清理

删除 `AbstractTradeScreenHandler` 中的 `requestConfirmation()` 方法及其调用：

- `ShopScreenHandler.triggerTrade()` — 不再调用 `requestConfirmation()`
- `MarketScreenHandler.triggerTrade()` — 不再调用 `requestConfirmation()`

---

## 10. 测试要点

由于项目无自动化测试框架（`src/test` 为空），建议手动测试以下场景：

### 10.1 管理员商店

| # | 测试场景 | 预期 |
|---|---------|------|
| 1 | 左键点击可堆叠物品（钻石） | 直接购买 1 个，无任何确认弹窗 |
| 2 | 左键点击不可堆叠物品（钻石剑） | 弹出确认 GUI，显示物品名、价格、余额 |
| 3 | 确认 GUI 中点击 [✓ 确认] | 执行购买，扣款，给物品 |
| 4 | 确认 GUI 中点击 [✗ 取消] | 关闭 GUI，无副作用 |
| 5 | 确认 GUI 中按 Esc | 关闭 GUI，无副作用 |
| 6 | 右键点击可堆叠物品（钻石） | 弹出确认 GUI，显示计算后的数量与总价 |
| 7 | 右键确认后购买 | 收到正确数量的物品 |
| 8 | 右键时余额不足 | 不打开 GUI，直接提示余额不足 |
| 9 | Shift+左键可堆叠物品 | 打开 `ShopQuantityScreenHandler`（行为不变） |
| 10 | Shift+左键不可堆叠物品 | 回退到左键行为（可堆叠判断直接执行，不可堆叠打开确认 GUI） |
| 11 | 确认后库存不足 | 提示售罄，交易拒绝 |
| 12 | 确认后余额不足 | 提示余额不足，交易拒绝 |

### 10.2 市场购买

| # | 测试场景 | 预期 |
|---|---------|------|
| 13 | 点击市场挂单 | 弹出确认 GUI，显示物品名、数量、总价、手续费、余额 |
| 14 | 确认购买 | 扣款，给物品，卖家收款 |
| 15 | 取消购买 | 关闭 GUI |
| 16 | 挂单在确认前被买走 | 提示挂单不存在，拒绝交易 |
| 17 | 余额不足时确认 | 提示余额不足，拒绝交易 |

### 10.3 市场上架

| # | 测试场景 | 预期 |
|---|---------|------|
| 18 | 放入物品 → 点击确认上架 | 弹出确认 GUI，显示可上架物品列表 |
| 19 | 确认上架 | 创建挂单，返还耐久不足物品（如有） |
| 20 | 取消上架 | 全部物品返还背包 |
| 21 | 按 Esc 关闭上架确认 GUI | 全部物品返还背包 |
| 22 | 上架被耐久不足物品 | 确认 GUI 中显示返还列表 |
| 23 | 空输入区点击确认上架 | 提示"上架区没有物品"，不打开确认 GUI |
| 24 | 背包满时返还物品 | 物品掉落至地面 |

### 10.4 边界情况

| # | 测试场景 | 预期 |
|---|---------|------|
| 25 | 快速双击确认按钮 | `CooldownManager` 拦截 |
| 26 | 在确认 GUI 中重连 | 断开时执行 `onCancel`，重新连接后无残留 |
| 27 | 同时打开多个确认 GUI | 打开新 GUI 前旧 GUI 的 `onClosed` 处理旧回调 |
| 28 | 连接断开后重新登录 | 无残留的待确认操作 |
| 29 | `/eco confirm` 命令 | 命令不存在（已移除） |
| 30 | `/eco cancel` 命令 | 命令不存在（已移除） |

---

> **文档版本**：v1.0
> **最后更新**：2026-07-04
> **对应代码**：`EcoMarketShop` 聊天确认 → GUI 确认 改造
