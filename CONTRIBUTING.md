# 贡献指南

欢迎参与 EcoMarketShop 的开发！本文档说明了参与项目开发所需的环境准备、代码风格规范以及 Pull Request 的提交流程。

## 🌱 快速上手

### 1. Fork 与克隆仓库

1. 在 GitHub 上 Fork 本仓库到你的账号。
2. 克隆你 Fork 的仓库到本地：

```bash
git clone https://github.com/<你的用户名>/EcoMarketShop.git
cd EcoMarketShop
```

3. 添加上游仓库以便后续同步更新：

```bash
git remote add upstream https://github.com/ourmc-Team/EcoMarketShop.git
```

### 2. 开发环境要求

| 组件 | 版本 |
| :--- | :--- |
| JDK | 21 或更高 |
| Gradle | 使用项目内置的 `gradlew` 包装器（无需单独安装） |
| IDE | IntelliJ IDEA（推荐）或 Eclipse |

### 3. 构建与运行

```bash
# 编译并构建模组 JAR
./gradlew build        # macOS / Linux
gradlew.bat build      # Windows

# 在开发环境中启动 Minecraft 测试服务端
./gradlew runServer    # macOS / Linux
gradlew.bat runServer  # Windows
```

构建产物位于 `build/libs/` 目录下。

## 📂 项目结构

```
src/main/java/com/ecomarketshop/
├── api/          # 对外集成 API 与事件回调（经济、商店、市场）
├── command/      # 命令注册（/eco, /shop, /market）
├── config/       # 配置数据模型（EconomyConfig, ShopItemConfig）
├── data/         # 数据持久化管理（JSON 读写、脏标记优化）
├── economy/      # 经济系统相关逻辑
├── gui/          # 服务端 Screen Handler（箱子界面交互）
├── market/       # 市场挂单数据模型
├── trade/        # 交易执行服务（服务端权威执行）
└── util/         # 工具类（冷却、确认管理、物品校验、日志等）
```

详细的架构与设计说明请参阅 [docs/DEVELOPMENT_GUIDE.md](./docs/DEVELOPMENT_GUIDE.md)。

## 📐 代码风格规范

### 通用约定

- **开发语言**：Java 21，可使用 Java 21 引入的新语法特性。
- **字符编码**：所有源文件统一使用 UTF-8 编码。
- **换行符**：使用 LF（Unix 风格）。
- **缩进**：使用 4 个空格，禁止使用 Tab。
- **包名**：全部小写，采用 `com.ecomarketshop.<模块名>` 的层级结构。
- **类名**：使用大驼峰命名（PascalCase），如 `ShopTradeService`。
- **方法名 / 变量名**：使用小驼峰命名（camelCase），如 `executePurchase`。
- **常量名**：使用全大写加下划线（UPPER_SNAKE_CASE），如 `MOD_ID`。

### 注释规范

- 类与公共方法应使用 **Javadoc** 注释，描述其职责、参数与返回值。
- 注释使用中文，保持简洁明了。
- 复杂逻辑处应补充行内注释说明「为什么」这样实现，而非「做了什么」。

示例：

```java
/**
 * 执行商店购买。
 *
 * @param player    购买者
 * @param slotIndex 商品在 GUI 中的槽位索引
 */
public static void executePurchase(ServerPlayerEntity player, int slotIndex) {
    // 从数据源重新获取配置（不信任客户端数据）
    ShopItemConfig config = ShopDataManager.getItemBySlot(slotIndex);
    ...
}
```

### 安全实践（重要）

本项目遵循**服务端权威**原则，请在贡献代码时严格遵守：

- **绝不信任客户端数据**：所有交易执行时，必须从服务端数据源重新获取价格、库存、余额等信息，不得直接采用客户端 GUI 传入的参数。
- **原子性校验**：扣款与库存扣减必须在 `synchronized` 同步块内完成「检查 → 扣减」的原子操作，避免 TOCTOU 竞态条件。
- **IO 与逻辑分离**：耗时的文件 IO（如 `saveConfig`、`TradeLogger`）应移出同步块，避免阻塞并发线程。
- **工具/服务类**：无状态的工具类与服务类应声明为 `final` 并提供私有构造函数，禁止实例化。

### JSON 与配置字段命名

- 配置文件（`config/*.json`）中的字段使用 **snake_case** 命名（如 `price_per_unit`、`market_fee_rate`）。
- Java 实体字段使用 camelCase，并通过 Gson 的 `@SerializedName` 注解显式映射到 snake_case 键名，确保序列化兼容性。

### 日志规范

- 使用 SLF4J 的 `LoggerFactory` 获取日志记录器。
- 日志前缀统一为 `[EcoMarketShop]`，便于在服务端日志中筛选。

## 📦 提交规范

### 提交信息（Commit Message）

提交信息建议采用以下格式：

```
<类型>: <简短描述>

<可选的详细说明>
```

常用类型：

| 类型 | 说明 |
|------|------|
| `feat` | 新增功能 |
| `fix` | 修复缺陷 |
| `docs` | 文档更新 |
| `refactor` | 重构（不改变外部行为） |
| `perf` | 性能优化 |
| `chore` | 构建 / 工具链 / 杂项变更 |

示例：

```
feat: 市场上架支持多组物品同时挂单
fix: 修复玩家断线时已捕获上架物品丢失的问题
docs: 补充 API.md 中 ShopPurchaseCallback 说明
```

## 🔄 Pull Request 流程

1. **创建分支**：从最新的 `main` 分支创建特性分支，命名建议 `feat/<功能>`、`fix/<问题>` 或 `docs/<主题>`。
2. **编写代码**：遵循上述代码风格规范，保持提交历史清晰（必要时使用 `git rebase` 整理）。
3. **本地验证**：确保 `./gradlew build` 构建通过，并在 `runServer` 环境中完成基本功能测试。
4. **提交 PR**：在 PR 描述中说明本次变更的内容、动机与测试情况。若涉及行为变更，请同步更新 `docs/` 下相关文档。
5. **代码评审**：等待维护者评审，根据反馈进行修改。变更请追加到原分支并推送，避免在 PR 中使用 force push 造成评审记录丢失。

## 🐛 问题反馈

- 发现 Bug 或有功能建议，请通过 [GitHub Issues](https://github.com/ourmc-Team/EcoMarketShop/issues) 提交。
- 提交 Issue 时请描述：复现步骤、预期行为、实际行为，以及涉及的 Minecraft / Fabric API 版本。

## 📄 许可证

贡献的代码将遵循与项目相同的 [MIT License](./LICENSE) 开源协议。提交 PR 即表示你同意以该许可证发布你的贡献。
