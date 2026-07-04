# 商店批量购买数量选择 GUI 实现计划

> **范围**：仅管理员商店（`/shop`），市场（`/market`）保持原有整单购买不变。
>
> **触发方式**：左键单击 → 买 1 个（现有逻辑不变）；
> Shift + 左键 → 弹出数量选择 GUI。

---

## 1. 交互流程

```
玩家打开 /shop (ShopScreenHandler)
         │
    ┌─────┴──────┐
    │  左键点击   │  Shift + 左键点击
    ▼             ▼
 买 1 个       弹出 ShopQuantityScreenHandler
 (chat confirm)    │
              ┌────┴────────┐
              │ 调整数量     │  [-16] [-1] [+1] [+16] [MAX]
              ▼             ▼
            [确认]        [取消]
              │             │
              ▼             ▼
         执行购买       关闭 GUI
      (ShopTradeService)  (无操作)
```

### 详细步骤

| 步骤 | 动作 | 说明 |
|------|------|------|
| 1 | 玩家在 `/shop` 中 Shift+点击商品 | `AbstractTradeScreenHandler.onSlotClick` 检测到 `SlotActionType.QUICK_MOVE` |
| 2 | 打开 `ShopQuantityScreenHandler` | 服务端创建新的 9×3 ScreenHandler 并同步到客户端 |
| 3 | 玩家点击数量按钮（-16/-1/+1/+16/MAX） | 每次点击服务器更新 quantity 状态，刷新 GUI 显示内容 |
| 4 | 玩家点击 [确认] | 调用 `ShopTradeService.executePurchase`，传入最终数量 |
| 5 | 玩家点击 [取消] | 关闭 GUI，不做任何操作 |

---

## 2. GUI 布局设计

使用 `ScreenHandlerType.GENERIC_9X3`（27 格），顶行装饰 + 中间调节区 + 底行操作区。

```
┌─────────────────────────────────────────────┐
│ [💎]  │  璀璨钻石  │  100/个  │ 库存: ∞ │   │   ← Row 0 (slots 0-8)
│        │            │          │         │   │      物品信息区
├─────────────────────────────────────────────┤
│  [-16] │  [-1] │  当前: 16  │  [+1] │ [+16]│   ← Row 1 (slots 9-17)
│        │       │            │       │[MAX] │      数量调节区
├─────────────────────────────────────────────┤
│        │        │  总价: 1600 金币  │ [✓] │[✗]│   ← Row 2 (slots 18-26)
│        │        │                   │ 确认 │ 取消   操作区
└─────────────────────────────────────────────┘
```

### 槽位分配明细

| 槽位 | 用途 | 类型 |
|------|------|------|
| 0 | 物品图标（带当前数量） | 展示 + 可点击（买1快捷） |
| 1–3 | 物品名称、单价、库存信息 | 纯展示 |
| 4–8 | 填充物（灰色玻璃板） | 纯展示 |
| 9 | `[-16]` 按钮 | 可点击 |
| 10 | `[-1]` 按钮 | 可点击 |
| 11 | 当前数量文本展示 | 纯展示 |
| 12 | `[+1]` 按钮 | 可点击 |
| 13 | `[+16]` 按钮 | 可点击 |
| 14 | 填充物 | 纯展示 |
| 15 | `[MAX]` 按钮 | 可点击 |
| 16–17 | 填充物 | 纯展示 |
| 18–21 | 填充物 | 纯展示 |
| 22 | 总价展示（随数量动态更新） | 纯展示 |
| 23–24 | 填充物 | 纯展示 |
| 25 | `[✓ 确认]` 按钮 | 可点击 |
| 26 | `[✗ 取消]` 按钮 | 可点击 |

---

## 3. 新增类

### 3.1 `ShopQuantityScreenHandler`

- **路径**: `src/main/java/com/ecomarketshop/gui/ShopQuantityScreenHandler.java`
- **继承**: `ScreenHandler`（使用 `GENERIC_9X3`，不需继承 `AbstractTradeScreenHandler`）
- **构造参数**: `syncId`, `playerInventory`, `shopSlotIndex`（商店商品槽位索引）

#### 关键字段

```java
public class ShopQuantityScreenHandler extends ScreenHandler {
    private static final int ROWS = 3;
    private static final int COLS = 9;
    private static final int GUI_SIZE = ROWS * COLS; // 27

    private final SimpleInventory displayInventory;
    private final int shopSlotIndex;        // 商店商品槽位索引
    private final ShopItemConfig config;    // 商品配置（从 ShopDataManager 实时获取）
    private int quantity;                   // 当前选中的数量
    private final int minQuantity = 1;
    private final int maxQuantity;          // 动态计算
}
```

#### `maxQuantity` 计算规则

```java
private int calculateMaxQuantity(ServerPlayerEntity player) {
    ShopItemConfig config = ShopDataManager.getItemBySlot(shopSlotIndex);
    if (config == null) return 0;

    Item item = ItemMatcher.getItem(config.getId());
    if (item == null) return 0;

    // 不可堆叠物品 max = 1，直接走左键逻辑，不进此 GUI
    int maxStack = item.getMaxCount();

    // 余额限制
    int price = config.getPrice();
    int maxAffordable = EconomyDataManager.getBalance(player.getUuid()) / price;

    // 库存限制
    int maxStock = config.isInfiniteStock() ? maxStack
                                            : Math.min(config.getStock(), maxStack);

    return Math.min(maxStack, Math.min(maxAffordable, maxStock));
}
```

#### 按钮槽位定义

使用枚举区分各按钮：

```java
enum QuantityButtonType {
    DEC_16, DEC_1, INC_1, INC_16, MAX, CONFIRM, CANCEL, NONE
}
```

槽位 → 按钮映射表固化在 `getButtonType(int slot)` 方法中。

#### 核心方法

| 方法 | 说明 |
|------|------|
| `onSlotClick(...)` | 根据点击的槽位执行对应操作（调整数量或确认/取消） |
| `adjustQuantity(int delta)` | 调整数量，钳制在 `[minQuantity, maxQuantity]` 范围 |
| `setToMax()` | 将数量设为 `maxQuantity` |
| `confirmPurchase(ServerPlayerEntity)` | 关闭 GUI 并调用 `ShopTradeService.executePurchase` |
| `cancelPurchase(ServerPlayerEntity)` | 关闭 GUI |
| `populateDisplay()` | 根据当前 `quantity` 刷新所有显示槽位 |
| `createButtonStack(Item, String)` | 创建按钮展示物品 |

#### `onSlotClick` 逻辑

```java
@Override
public void onSlotClick(int slotIndex, int button, SlotActionType action, PlayerEntity player) {
    if (action != SlotActionType.PICKUP || button != 0) return;
    if (slotIndex < 0 || slotIndex >= GUI_SIZE) return;

    if (!CooldownManager.checkAndUpdate(player.getUuid())) {
        player.sendMessage(Text.literal("§c操作过快，请稍后再试！"));
        return;
    }

    switch (getButtonType(slotIndex)) {
        case DEC_16 -> adjustQuantity(-16);
        case DEC_1  -> adjustQuantity(-1);
        case INC_1  -> adjustQuantity(+1);
        case INC_16 -> adjustQuantity(+16);
        case MAX    -> setToMax();
        case CONFIRM -> confirmPurchase((ServerPlayerEntity) player);
        case CANCEL  -> cancelPurchase((ServerPlayerEntity) player);
        case NONE   -> { /* 展示槽位，无操作 */ }
    }
}
```

#### `confirmPurchase`

```java
private void confirmPurchase(ServerPlayerEntity player) {
    ShopTradeService.executePurchase(player, shopSlotIndex, quantity);
    player.closeHandledScreen();
}
```

#### 按钮物品样式

| 按钮 | 物品 | 名称颜色 |
|------|------|---------|
| `[-16]` | `SPECTRAL_ARROW` | §c 红色 |
| `[-1]` | `ARROW` | §c 红色 |
| `[+1]` | `ARROW` | §a 绿色 |
| `[+16]` | `SPECTRAL_ARROW` | §a 绿色 |
| `[MAX]` | `EXPERIENCE_BOTTLE` | §e 金色 |
| `[✓ 确认]` | `LIME_DYE` | §a 绿色 |
| `[✗ 取消]` | `RED_DYE` | §c 红色 |
| 填充物 | `GRAY_STAINED_GLASS_PANE` | 透明 |

数量展示槽（slot 11）：使用 `ITEM_FRAME` 或 `PAPER`，Lore 显示 `"当前: 16"`。

### 3.2 `QuantityButtonType` 枚举

（可放 `ShopQuantityScreenHandler` 内部或独立文件）

### 3.3 打开 GUI 的工厂方法

在 `ShopCommand` 或 `AbstractTradeScreenHandler` 中提供便捷方法：

```java
public static void openQuantityScreen(ServerPlayerEntity player, int shopSlotIndex) {
    player.openHandledScreen(new NamedScreenHandlerFactory() {
        @Override
        public ScreenHandler createMenu(int syncId, PlayerInventory inv, PlayerEntity p) {
            return new ShopQuantityScreenHandler(syncId, inv, shopSlotIndex);
        }
        @Override
        public Text getDisplayName() {
            return Text.literal("§6选择数量");
        }
    });
}
```

---

## 4. 修改现有类

### 4.1 `AbstractTradeScreenHandler`

**改动点**：`onSlotClick` 增加 Shift+左键检测分支。

```java
@Override
public void onSlotClick(int slotIndex, int button, SlotActionType action, PlayerEntity player) {
    // === 新增：Shift+左键 → 打开数量选择 GUI（仅商店）===
    if (action == SlotActionType.QUICK_MOVE && button == 0) {
        if (!(this instanceof ShopScreenHandler)) return; // 仅商店
        if (slotIndex >= GUI_SIZE) return;
        if (!CooldownManager.checkAndUpdate(player.getUuid())) {
            player.sendMessage(Text.literal("§c操作过快，请稍后再试！"));
            return;
        }
        if (isValidTradeSlot(slotIndex, player)) {
            ShopItemConfig config = ShopDataManager.getItemBySlot(slotIndex);
            Item item = ItemMatcher.getItem(config.getId());
            // 不可堆叠物品回退到左键逻辑
            if (item == null || item.getMaxCount() <= 1) {
                triggerTrade((ServerPlayerEntity) player, slotIndex);
            } else {
                ShopQuantityScreenHandler.open((ServerPlayerEntity) player, slotIndex);
            }
        }
        return;
    }

    // 原有左键逻辑不变
    if (action != SlotActionType.PICKUP || button != 0) return;
    // ... 原代码
}
```

> **说明**：`QuickMove` 是 Shift+点击产生的 ActionType，`button == 0` 是左 Shift。
> 也可以使用 `button == 1` 检测右 Shift，但一般统一处理即可。

### 4.2 `ShopTradeService`

**改动点**：新增 `executePurchase(player, slotIndex, quantity)` 重载。

```java
/**
 * 执行商店批量购买。
 *
 * @param player    购买者
 * @param slotIndex 商品在 GUI 中的槽位索引
 * @param quantity  购买数量
 */
public static void executePurchase(ServerPlayerEntity player, int slotIndex, int quantity) {
    // 参数校验
    if (quantity <= 0) return;

    String itemId;
    int totalPrice;

    synchronized (ShopDataManager.class) {
        ShopItemConfig config = ShopDataManager.getItemBySlot(slotIndex);
        if (config == null || config.getStock() == 0) {
            player.sendMessage(Text.literal("§c该商品已售罄！"));
            return;
        }

        // 库存检查（非无限）
        if (!config.isInfiniteStock() && config.getStock() < quantity) {
            player.sendMessage(Text.literal("§c库存不足！需要: " + quantity
                + "，剩余: " + config.getStock()));
            return;
        }

        totalPrice = config.getPrice() * quantity;

        // 余额检查
        if (!EconomyDataManager.tryDeduct(player.getUuid(), totalPrice)) {
            player.sendMessage(Text.literal("§c余额不足！需要: " + totalPrice
                + " " + EconomyConfig.getCurrencyName()));
            return;
        }

        // 给予物品（含数量）
        Item item = ItemMatcher.getItem(config.getId());
        if (item != null) {
            // 按 maxStackSize 分批发放
            int maxStack = item.getMaxCount();
            int remaining = quantity;
            while (remaining > 0) {
                int stackSize = Math.min(remaining, maxStack);
                ItemStack result = new ItemStack(item, stackSize);
                if (!player.getInventory().insertStack(result)) {
                    player.dropItem(result, false);
                }
                remaining -= stackSize;
            }
        }

        // 扣除库存（quantity 数量）
        for (int i = 0; i < quantity; i++) {
            ShopDataManager.decrementStock(slotIndex);
        }
    }

    ShopDataManager.saveConfig();
    TradeLogger.log(player.getName().getString(), "SHOP_BUY", itemId, quantity, totalPrice, 0);
    ShopPurchaseCallback.EVENT.invoker().onPurchase(player, itemId, quantity, totalPrice);

    player.sendMessage(Text.literal("§a购买成功！花费: " + totalPrice + " "
        + EconomyConfig.getCurrencyName()));
}
```

> **重要**：原有 `executePurchase(player, slotIndex)` 保留不变（左键买 1 使用），
> 内部可委托给新的重载 `executePurchase(player, slotIndex, 1)`。

### 4.3 `ShopDataManager`

**改动点**：新增批量扣减库存方法。

```java
/**
 * 批量扣减库存。
 *
 * @param slotIndex 槽位索引
 * @param quantity  扣减数量
 * @return true 表示扣减成功
 */
public static synchronized boolean decrementStock(int slotIndex, int quantity) {
    ShopItemConfig item = slotIndexMap.get(slotIndex);
    if (item == null) return false;
    if (item.isInfiniteStock()) return true;
    if (item.getStock() < quantity) return false;
    item.setStock(item.getStock() - quantity);
    return true;
}
```

> 原 `decrementStock(slotIndex)` 保留，委托给新方法：
> `return decrementStock(slotIndex, 1);`

### 4.4 `ShopScreenHandler`

**改动点**：无。Shift+左键检测已在 `AbstractTradeScreenHandler` 中统一处理，
且 `triggerTrade` 不受影响（左键仍买 1）。

但考虑微调 `createDisplayStack` 中的 Lore，提示玩家可以 Shift+点击批量购买：

```java
// 在原有 Lore 末尾加一行提示
if (item.getMaxCount() > 1) {
    lore.add(Text.literal("§eShift+左键批量购买"));
}
```

### 4.5 `TradeLogger`

**无需改动**。`log()` 方法已接受 `amount` 和 `price` 参数，传 quantity 和 totalPrice 即可。

---

## 5. 安全设计

| 风险 | 缓解措施 |
|------|---------|
| 客户端篡改 quantity | 所有计算在服务端 `ShopQuantityScreenHandler` 和 `ShopTradeService` 中进行 |
| TOCTOU 竞态 | `synchronized(ShopDataManager.class)` 保证库存/余额 check-then-act 原子性 |
| 超买（买多组超出 maxStack） | 分批发货，每次 `min(remaining, maxStackSize)` |
| 余额不足 | 确认时重新计算总价并调用 `tryDeduct` |
| 库存耗尽 | 确认时重新获取配置并检查 `stock >= quantity` |
| 操作过快 | `CooldownManager` 拦截 |
| 不可堆叠物品 | `getMaxCount() <= 1` 时回退到左键买 1，不进数量 GUI |
| 数量超出 maxStackSize | `calculateMaxQuantity` 以 `item.getMaxCount()` 为硬上限 |

---

## 6. 边界情况

| 场景 | 预期行为 |
|------|---------|
| 点击 `[-1]` 时 quantity = 1 | 不变（已是最小值） |
| 点击 `[+1]` 时 quantity = maxQuantity | 不变（已是最大值） |
| 点击 `[+16]` 时 quantity + 16 > maxQuantity | 钳位到 maxQuantity |
| 玩家余额仅够买 5 个，点击 `[MAX]` | quantity = 5 |
| 库存剩 3 个，点击 `[MAX]` | quantity = 3（非无限库存时） |
| 玩家余额为 0 | `maxQuantity = 0`，无法进入 GUI（或进入后所有按钮灰色不可用） |
| 物品最大堆叠为 16（末影珍珠） | `[-16]` 仍有效，上限钳位到 16 |
| 玩家在数量 GUI 中时库存被其他管理员修改 | 确认时重新获取 Config 和 Stock，以最新值为准 |
| 玩家在数量 GUI 中时余额变化 | 确认时重新调用 `tryDeduct`，不足则拒绝 |

---

## 7. 文件变更汇总

| 操作 | 文件 | 说明 |
|------|------|------|
| **新增** | `gui/ShopQuantityScreenHandler.java` | 数量选择 GUI 主体 |
| 修改 | `gui/AbstractTradeScreenHandler.java` | `onSlotClick` 增加 Shift+左键分支 |
| 修改 | `trade/ShopTradeService.java` | 新增批量购买重载 |
| 修改 | `data/ShopDataManager.java` | 新增 `decrementStock(slotIndex, quantity)` |
| 可选修改 | `gui/ShopScreenHandler.java` | Lore 添加 Shift 提示 |

---

## 8. 实现步骤（建议顺序）

```
Step 1: ShopDataManager   → 新增 decrementStock(slot, qty)
Step 2: ShopTradeService   → 新增 executePurchase(player, slot, qty)
Step 3: ShopQuantityScreenHandler → 完整实现数量 GUI
Step 4: AbstractTradeScreenHandler → 接入 Shift+左键检测
Step 5: ShopScreenHandler  → 可选：Lore 添加批量购买提示
Step 6: 测试验证
```

---

## 9. 测试要点

由于项目无测试框架（`src/test` 为空），建议手动测试以下场景：

1. **左键买 1** 保持原有行为不变
2. **Shift+左键不可堆叠物品**（桶、剑等）→ 回退买 1
3. **Shift+左键堆叠物品** → 弹出数量 GUI
4. **数量调节**：-16/-1/+1/+16/MAX 按钮行为正确
5. **边界钳位**：数量不会低于 1 或超过 maxQuantity
6. **确认购买**：库存、余额正确扣减，物品正确发放
7. **余额不足时确认**：拒绝并提示
8. **库存不足时确认**：拒绝并提示
9. **分批发货**：买 80 个钻石（maxStack=64）→ 64 + 16 两批
10. **取消**：关闭 GUI，无副作用
