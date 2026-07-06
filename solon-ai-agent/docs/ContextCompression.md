# ContextCompressionInterceptor 使用说明

> **模块**: `solon-ai-agent`
> **包名**: `org.noear.solon.ai.agent.react.intercept`
> **版本**: 3.9.4+ / 4.0.0
> **注解**: `@Preview("3.8.2")`

## 一、概述

`ContextCompressionInterceptor` 是 Solon AI Agent 的**语义保护型上下文压缩拦截器**。它在 Agent 每轮推理开始前（`onReasonStart`）自动检测工作记忆区的大小，当消息数量或 Token 数超过阈值时，对历史消息进行**无损或近无损**的压缩。

### 核心能力

| 能力 | 说明 |
|------|------|
| 初心链保护 | 标记为 `META_FIRST` 的消息（system prompt、用户原始问题）永不压缩 |
| Tool-use 原子对保护 | `Assistant(with tool_calls)` ↔ `ToolMessage` 的调用-结果配对不会被拆散 |
| 多轮追溯保留 | 最后一条是 ToolMessage 时，向前追溯至完整的源头 Assistant(with tool_calls) |
| Token 预算控制 | 精确计算消息 Token 开销，预留摘要空间后按双维度确定保留窗口 |
| MicroCompact 守卫 | 每轮推理前无条件截断超大单条消息，防止单条消息撑爆上下文 |
| 模型感知阈值 | 根据底层模型的 `contextLength` 动态调整压缩触发阈值 |
| PTL 重试机制 | 压缩策略调用 LLM 返回 "prompt too long" 时，自动收窄范围重试 |
| 压缩效果警告 | 压缩后消息数 >= 原始 90% 时输出 warn 日志 |

---

## 二、快速开始

### 2.1 最简用法（零配置，纯裁剪模式）

不调用 LLM，仅执行原子对齐的物理裁剪。适合不需要语义摘要、只需控制上下文大小的场景。

```java
// 创建拦截器，使用默认配置
// 默认: maxMessages=15, maxTokens=15000, 无压缩策略
ContextCompressionInterceptor interceptor = new ContextCompressionInterceptor();

// 注册到 ReActAgent
ReActAgent agent = ReActAgent.builder(model)
        .defaultInterceptorAdd(interceptor)
        .build();
```

### 2.2 带 LLM 摘要的标准用法

```java
// 1. 创建压缩策略（LLM 语义摘要）
LLMCompressionStrategy strategy = new LLMCompressionStrategy();

// 2. 创建拦截器
ContextCompressionInterceptor interceptor = new ContextCompressionInterceptor(
        20,                    // maxMessages: 保留窗口最大消息数
        20000,                 // maxTokens: 保留窗口最大 Token 数
        () -> chatModel,       // chatModelSupplier: 提供 LLM 实例
        strategy               // 压缩策略
);

// 3. 注册到 Agent
ReActAgent agent = ReActAgent.builder(model)
        .defaultInterceptorAdd(interceptor)
        .build();
```

### 2.3 通过 Harness 引擎使用

Solon AI Harness 已内置默认压缩配置，无需手动创建：

```java
HarnessEngine engine = HarnessEngine.of(workspace, harnessHome)
        .compressionThreshold(20, 20000)  // maxMessages, maxTokens
        .compressionModel("deepseek-chat") // 指定压缩用的模型（可选，默认用主模型）
        .build();
```

Harness 默认使用 `CompositeCompressionStrategy`，组合了 `KeyInfoExtractionStrategy`（提取干货）+ `HierarchicalCompressionStrategy`（滚动摘要）。

---

## 三、构造参数详解

### 3.1 构造函数

```java
// 构造函数 1：标准四参数
public ContextCompressionInterceptor(
        int maxMessages,                        // 保留窗口最大消息数（最低 10）
        int maxTokens,                          // 保留窗口最大 Token 数（最低 10,000）
        Supplier<ChatModel> chatModelSupplier,  // LLM 提供者（可为 null）
        CompressionStrategy compressionStrategy  // 压缩策略（可为 null）
)

// 构造函数 2：含重试次数
public ContextCompressionInterceptor(
        int maxMessages,
        int maxTokens,
        int maxRetries,                         // LLM 调用重试次数
        Supplier<ChatModel> chatModelSupplier,
        CompressionStrategy compressionStrategy
)

// 构造函数 3：无参默认
public ContextCompressionInterceptor()
// 等价于 new ContextCompressionInterceptor(15, 15000, null, null)
```

### 3.2 可调参数（Setter）

| 参数 | Setter | 默认值 | 说明 |
|------|--------|--------|------|
| `maxMessages` | `setMaxMessages(int)` | 15 | 保留窗口最大消息数，最低强制为 10 |
| `maxTokens` | `setMaxTokens(int)` | 15,000 | 保留窗口最大 Token 数，最低强制为 10,000 |
| `minReservedMessages` | `setMinReservedMessages(int)` | `maxMessages / 3`（最低 3） | 保留窗口的绝对下限，防止 Token 维度过度截断 |
| `maxRetries` | `setMaxRetries(int)` | 3 | LLM 调用网络重试次数 |
| `perMessageCap` | `setPerMessageCap(int)` | 0（自动推导） | 单条消息 Token 硬上限。0 = 自动推导为 `max(2000, maxTokens/2)` |

### 3.3 内部常量

| 常量 | 值 | 说明 |
|------|----|------|
| `COMPACT_THRESHOLD_RATIO` | 0.75 | 压缩触发比例：当前 Token <= `effectiveMaxTokens * 0.75` 且消息数 <= `maxMessages` 时不触发 |
| `SYSTEM_PROMPT_RESERVE` | 20,000 | 系统提示词保留缓冲（用于模型感知阈值计算） |

---

## 四、压缩策略详解

### 4.1 策略接口

```java
@FunctionalInterface
public interface CompressionStrategy {
    ChatMessage compress(ChatModel chatModel, int maxRetries,
                          ReActTrace trace, List<ChatMessage> messagesToCompress);
}
```

传入"过期"消息段，返回一条包含压缩结果的消息（通常为 `UserMessage`）。返回 `null` 表示不生成摘要，拦截器自动回退到原子序列追溯的零成本裁剪路径。

### 4.2 内置策略一览

| 策略 | 类名 | 调用 LLM | 特点 | 适用场景 |
|------|------|----------|------|----------|
| 纯裁剪 | `null` | 否 | 零成本，仅保留最近的原子序列 | 轻量 Agent、对摘要质量无要求 |
| LLM 语义摘要 | `LLMCompressionStrategy` | 是 | 调用 LLM 生成执行进度总结，支持 PTL 重试 | 通用场景 |
| 关键信息提取 | `KeyInfoExtractionStrategy` | 是 | 提取事实/参数/结论，过滤思考过程 | 需要精确保留关键数据的场景 |
| 层级滚动摘要 | `HierarchicalCompressionStrategy` | 是 | 旧摘要 + 新增量递归合并，记忆链不断裂 | 超长对话、需要无限续航的场景 |
| 向量存储归档 | `VectorStoreCompressionStrategy` | 否 | 消息持久化到向量库，Agent 可通过 `recall_history` 工具回溯 | 需要冷热记忆分离的场景 |
| 组合策略 | `CompositeCompressionStrategy` | 取决于子策略 | 多策略级联，支持 ALL / FIRST_MATCH 两种模式 | 复杂场景 |

### 4.3 LLMCompressionStrategy

调用 LLM 对过期消息生成简洁的执行进度总结（默认 300 字以内）。

```java
LLMCompressionStrategy strategy = new LLMCompressionStrategy();

// 可选：自定义系统指令
strategy.systemInstruction("请用 200 字总结执行历史，重点保留关键决策和当前状态。");

ContextCompressionInterceptor interceptor = new ContextCompressionInterceptor(
        20, 20000, () -> chatModel, strategy);
```

**PTL 重试机制**：当待压缩历史本身过大导致 LLM 调用失败（返回 "prompt is too long" 或抛出 context length 异常）时，自动丢弃最旧的一半消息缩小范围后重试，最多重试 3 次。

### 4.4 KeyInfoExtractionStrategy

侧重提取"事实、参数、结论"，过滤掉无用的思考过程。输出格式为 Markdown 列表。

```java
KeyInfoExtractionStrategy strategy = new KeyInfoExtractionStrategy();

// 可选：自定义提取指令
strategy.systemInstruction("提取所有用户 ID、订单号和操作结果。");

ContextCompressionInterceptor interceptor = new ContextCompressionInterceptor(
        20, 20000, () -> chatModel, strategy);
```

### 4.5 HierarchicalCompressionStrategy

将"旧压缩结果"与"新过期的消息"递归合并，确保记忆链条永不断裂。内部通过 `ReActTrace.setExtra()` 维护滚动摘要状态。

```java
HierarchicalCompressionStrategy strategy = new HierarchicalCompressionStrategy();

// 可选：配置摘要最大长度
strategy.maxSummaryLength(500);

// 可选：自定义系统指令
strategy.systemInstruction("合并旧摘要和新历史，保持 500 字以内。");

ContextCompressionInterceptor interceptor = new ContextCompressionInterceptor(
        20, 20000, () -> chatModel, strategy);
```

### 4.6 VectorStoreCompressionStrategy

将过期消息持久化到向量库（`RepositoryStorable`），并在上下文中保留一条归档通知。同时注册 `recall_history` 工具，让 Agent 可以主动回溯历史细节。

```java
// 需要一个向量存储实现（如 PgVectorRepository、ChromaRepository 等）
RepositoryStorable repository = new YourRepositoryImpl();

VectorStoreCompressionStrategy strategy = new VectorStoreCompressionStrategy(repository);

// strategy 同时也是 AbsTalent，需注册为 Agent 的 Talent
ReActAgent agent = ReActAgent.builder(model)
        .defaultInterceptorAdd(new ContextCompressionInterceptor(20, 20000, () -> model, strategy))
        .talent(strategy)  // 注册 recall_history 工具
        .build();
```

Agent 在后续对话中可以通过调用 `recall_history` 工具检索已归档的历史细节：

```
Tool: recall_history(query="用户订单号", limit=3)
```

### 4.7 CompositeCompressionStrategy

按顺序执行多个子策略，支持两种执行模式：

| 模式 | 说明 | 适用场景 |
|------|------|----------|
| `ALL`（默认） | 所有子策略全部执行，结果合并 | 先存档到向量库，再用 LLM 总结 |
| `FIRST_MATCH` | 短路模式，第一个有效结果即返回 | 有优先级的兜底场景 |

```java
// 示例 1：ALL 模式 —— 先存档再用 LLM 总结
CompositeCompressionStrategy composite = new CompositeCompressionStrategy()
        .addStrategy(new VectorStoreCompressionStrategy(repository))  // 先存档
        .addStrategy(new KeyInfoExtractionStrategy())                 // 再提取干货
        .addStrategy(new HierarchicalCompressionStrategy());          // 后滚动摘要
// 默认就是 ALL 模式

ContextCompressionInterceptor interceptor = new ContextCompressionInterceptor(
        20, 20000, () -> chatModel, composite);

// 示例 2：FIRST_MATCH 模式 —— 优先 LLM，失败则回退
CompositeCompressionStrategy fallback = new CompositeCompressionStrategy()
        .addStrategy(new LLMCompressionStrategy())    // 优先 LLM 摘要
        .addStrategy(new KeyInfoExtractionStrategy()) // LLM 失败则提取关键信息
        .mode(CompositeCompressionStrategy.CompositeMode.FIRST_MATCH);
```

### 4.8 自定义策略

实现 `CompressionStrategy` 接口即可：

```java
public class MyStrategy implements CompressionStrategy {
    @Override
    public ChatMessage compress(ChatModel chatModel, int maxRetries,
                                 ReActTrace trace, List<ChatMessage> messagesToCompress) {
        // 1. 过滤初心链消息
        List<ChatMessage> filtered = messagesToCompress.stream()
                .filter(m -> !m.hasMetadata(AgentTrace.META_FIRST))
                .collect(Collectors.toList());
        if (filtered.isEmpty()) return null;

        // 2. 使用 CompressionUtil 统一格式化（推荐）
        String text = CompressionUtil.formatMessages(filtered);

        // 3. 自定义压缩逻辑...

        // 4. 返回带 META_COMPRESSED 标记的消息（推荐使用工具类构建）
        return CompressionUtil.buildCompressedMessage("--- [我的摘要] ---", summaryText);
    }
}
```

---

## 五、压缩执行流程

拦截器在每次 `onReasonStart` 时按以下步骤执行：

```
推理开始 (onReasonStart)
    │
    ├── 0. MicroCompact 守卫 ──────────────────────────────────
    │   对每条消息检查 Token 数，超过 perMessageCap 的做头尾截断。
    │   初心链消息跳过，多模态消息跳过。
    │
    ├── 1. 模型感知阈值判断 ────────────────────────────────────
    │   effectiveMaxTokens = max(maxTokens, contextLength - 20000)
    │   若 消息数 <= maxMessages 且 Token <= effectiveMaxTokens * 0.75 → 不压缩，返回
    │
    ├── 2. 提取初心链 ──────────────────────────────────────────
    │   收集所有 META_FIRST 标记的消息，计算固定 Token 开销
    │
    ├── 3. 计算窗口预算 ────────────────────────────────────────
    │   availableTokens = maxTokens - fixedTokens
    │   summaryReserve = max(200, availableTokens * 10%)
    │   windowBudget = availableTokens - summaryReserve
    │
    ├── 4. 双维度确定截断点 ────────────────────────────────────
    │   targetByCount  = 按消息数量维度的截断位置
    │   targetByTokens = 按 Token 预算维度的截断位置（从尾向前累加）
    │   minReservedIdx = 保留窗口的绝对下限
    │   targetIdx = max(targetByCount, min(targetByTokens, minReservedIdx))
    │
    ├── 5. 原子对对齐 ──────────────────────────────────────────
    │   若 targetIdx 落在 ToolMessage 上 → 向前回退至配套的 Assistant(with tool_calls)
    │
    ├── 6. 语义连贯补齐 ────────────────────────────────────────
    │   若截断点前一条为空想的 Assistant thought → 一并纳入保留区
    │
    ├── 7. 重构 WorkingMemory ──────────────────────────────────
    │   [初心链] + [过期区压缩结果/零成本裁剪] + [保留窗口]
    │   过期区处理：
    │     - 有压缩策略 → 调用策略.compress()，成功则注入摘要消息
    │     - 无策略或策略返回 null → 原子序列追溯（保留最后一个完整 tool-use 序列）
    │
    ├── 8. 清理孤立消息 ────────────────────────────────────────
    │   removeDanglingToolOutputs: 移除没有配对结果的 Assistant(tool_calls)
    │   和没有配对来源的孤立 ToolMessage
    │
    └── 9. 推送 ContextSizeChunk ───────────────────────────────
        向流式输出推送上下文大小信息（含压缩前后对比数据）
```

---

## 六、保护机制详解

### 6.1 初心链保护（META_FIRST）

被标记为 `AgentTrace.META_FIRST` 的消息**永远不会被压缩或截断**。这通常包括：
- 系统提示词（System Message）
- 用户的原始问题/目标

框架会自动为这些消息注入标记。手动标记方式：

```java
ChatMessage userGoal = ChatMessage.ofUser("请帮我重构这个项目");
userGoal.addMetadata(AgentTrace.META_FIRST, 1);
```

### 6.2 Tool-use 原子对保护

`Assistant(with tool_calls)` 和其对应的 `ToolMessage` 构成一个**原子对**，压缩时不会被拆散：
- 截断点落在 `ToolMessage` 上 → 向前回退至配套的 `Assistant(with tool_calls)`
- 截断点落在 `Assistant(with tool_calls)` 上 → 保留（这是原子对的正确起点）

### 6.3 minReservedMessages 下限保护

防止 Token 维度截断过度压缩保留窗口：

```java
// 默认: maxMessages / 3，最低 3
interceptor.setMinReservedMessages(5); // 至少保留 5 条非初心消息
```

### 6.4 MicroCompact 单条消息守卫

在所有裁剪逻辑之前执行，截断超大单条消息：
- 自动推导：`perMessageCap = max(2000, maxTokens / 2)`
- 手动设置：`interceptor.setPerMessageCap(4000)`
- 截断方式：保留首尾各一半 Token，中间插入占位标记
- 跳过初心链消息和多模态消息

### 6.5 模型感知阈值

当 `ChatModel` 配置了 `contextLength` 时，压缩触发阈值自动感知模型上下文窗口：

```
effectiveMaxTokens = max(maxTokens, contextLength - 20000)
触发条件: Token > effectiveMaxTokens * 0.75
```

**注意**：模型感知阈值仅用于**触发判断**，保留窗口预算始终使用用户配置的 `maxTokens`，避免大上下文模型导致压缩形同虚设。

### 6.6 PTL (Prompt-Too-Long) 重试

当压缩策略调用 LLM 失败（返回 "prompt is too long" 或抛出 context length 异常）时：
1. 丢弃最旧的一半消息缩小范围
2. 保留最近的消息（相关性最高）
3. 最多重试 3 次

### 6.7 压缩效果警告

压缩后若消息数 >= 原始 90%，输出 warn 日志提示压缩效果不显著，建议调整 `maxMessages` 或 `minReservedMessages`。

---

## 七、CompressionUtil 工具类

所有策略实现共享的工具类，位于 `org.noear.solon.ai.agent.react.intercept.compress.CompressionUtil`。

### 常用方法

| 方法 | 说明 |
|------|------|
| `formatMessageForCompression(msg)` | 将单条消息格式化为文本行，ToolMessage 超过 2000 字符自动截断 |
| `formatMessages(messages)` | 将多条消息拼接为压缩用文本块 |
| `isEmptySummary(summary)` | 检测 LLM 返回是否为"无显著增量"标记 |
| `isPromptTooLong(response)` | 检测 LLM 返回文本是否为 PTL 错误 |
| `isPromptTooLongError(throwable)` | 检测异常链是否包含 PTL 关键词 |
| `buildCompressedMessage(prefix, content)` | 创建带 `META_COMPRESSED` 标记的压缩结果消息 |

### 自定义策略推荐用法

```java
public class MyStrategy implements CompressionStrategy {
    @Override
    public ChatMessage compress(ChatModel chatModel, int maxRetries,
                                 ReActTrace trace, List<ChatMessage> messagesToCompress) {
        // 1. 过滤初心链
        List<ChatMessage> filtered = messagesToCompress.stream()
                .filter(m -> !m.hasMetadata(AgentTrace.META_FIRST))
                .collect(Collectors.toList());
        if (filtered.isEmpty()) return null;

        // 2. 统一格式化（推荐）
        String text = CompressionUtil.formatMessages(filtered);
        if (CompressionUtil.isEmptySummary(text)) return null;

        // 3. 压缩逻辑...

        // 4. 构建结果消息（推荐）
        return CompressionUtil.buildCompressedMessage("--- [我的摘要] ---", result);
    }
}
```

---

## 八、ContextSizeChunk 事件

压缩时通过流式输出推送 `ContextSizeChunk`，让用户侧感知上下文状态。

### 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `messageCount` | int | 当前上下文总消息数 |
| `tokenCount` | int | 当前上下文总 Token 数（估算） |
| `compressed` | boolean | 本次是否触发了压缩 |
| `beforeMessageCount` | int | 压缩前消息数（未压缩时为 0） |
| `afterMessageCount` | int | 压缩后消息数（未压缩时为 0） |
| `beforeTokenCount` | int | 压缩前 Token 数（未压缩时为 0） |
| `afterTokenCount` | int | 压缩后 Token 数（未压缩时为 0） |

### 消费示例

```java
// 在流式输出中消费 ContextSizeChunk
agent.prompt("帮我分析这段代码")
        .stream(chunk -> {
            if (chunk instanceof ContextSizeChunk) {
                ContextSizeChunk csc = (ContextSizeChunk) chunk;
                if (csc.isCompressed()) {
                    System.out.printf("上下文压缩: %d→%d 消息, %d→%d tokens%n",
                            csc.getBeforeMessageCount(), csc.getAfterMessageCount(),
                            csc.getBeforeTokenCount(), csc.getAfterTokenCount());
                }
            }
        })
        .call();
```

---

## 九、Token 估算机制

拦截器使用 `jtokkit` 库（GPT-4o 编码器）进行 Token 估算。

### 估算范围

| 维度 | 包含内容 |
|------|----------|
| 消息内容 | `ChatMessage.getContent()` |
| 工具调用 | `AssistantMessage` 的 `toolCalls`（name + arguments + 结构开销） |
| 系统提示词 | `systemPrompt` 文本 |
| 工具定义 | `FunctionTool` 的 name + description + inputSchema（仅 NATIVE_TOOL 模式） |
| 结构开销 | 每条消息 +4 tokens，总体 +3 tokens |

### 缓存优化

Token 计算结果缓存在消息元数据 `META_TOKEN_SIZE` 中，避免每轮重复编码。当消息内容被截断后，缓存自动清除以触发重算。

---

## 十、最佳实践

### 10.1 参数调优建议

| 场景 | maxMessages | maxTokens | 推荐策略 |
|------|-------------|-----------|----------|
| 轻量 Agent（快速问答） | 10-15 | 8,000-15,000 | `null`（纯裁剪） |
| 通用 Agent（工具调用） | 15-25 | 15,000-30,000 | `LLMCompressionStrategy` |
| 代码 Agent（长文件操作） | 20-30 | 20,000-40,000 | `CompositeCompressionStrategy` |
| 超长对话（客服/陪聊） | 15-20 | 15,000-20,000 | `HierarchicalCompressionStrategy` |
| 冷热记忆分离 | 15-20 | 15,000-20,000 | `VectorStoreCompressionStrategy` |

### 10.2 perMessageCap 调优

| 场景 | 建议 | 原因 |
|------|------|------|
| 默认 | 0（自动推导） | 自动适配 maxTokens |
| 代码 Agent（读大文件） | 3000-5000 | 工具输出可能超大，需要控制单条上限 |
| 精确问答 | 0 | 工具输出通常不大，无需额外限制 |

### 10.3 策略组合建议

```java
// 推荐组合：先存档再总结（Harness 默认方案）
CompositeCompressionStrategy strategy = new CompositeCompressionStrategy()
        .addStrategy(new KeyInfoExtractionStrategy())       // 提取关键信息
        .addStrategy(new HierarchicalCompressionStrategy()); // 滚动更新摘要

// 高级组合：向量归档 + LLM 摘要
CompositeCompressionStrategy strategy = new CompositeCompressionStrategy()
        .addStrategy(new VectorStoreCompressionStrategy(repo))  // 冷记忆归档
        .addStrategy(new LLMCompressionStrategy());              // 热记忆摘要
```

### 10.4 自定义压缩 Prompt

每个 LLM 策略都支持自定义 `systemInstruction`：

```java
LLMCompressionStrategy strategy = new LLMCompressionStrategy()
        .systemInstruction(
            "## 角色\n你是代码审查助手，请总结代码变更历史。\n" +
            "## 要点\n1. 修改了哪些文件\n2. 解决了什么问题\n3. 当前状态\n" +
            "## 约束\n不超过 200 字，不要包含废话。"
        );
```

---

## 十一、元数据标记

| 标记 | 值 | 说明 |
|------|----|------|
| `META_FIRST` | `AgentTrace.META_FIRST` | 初心链标记，标记的消息永不压缩 |
| `META_COMPRESSED` | `ContextCompressionInterceptor.META_COMPRESSED` (`"_compressed"`) | 压缩结果标记，标识该消息是压缩产生的摘要 |
| `META_TOKEN_SIZE` | `"token_size"`（内部） | Token 数缓存，避免重复编码 |

---

## 十二、注意事项

1. **chatModelSupplier 为 null 时**：拦截器不调用 LLM，仅执行物理裁剪。即使配置了压缩策略也不会触发。
2. **压缩策略返回 null 时**：自动回退到原子序列追溯的零成本裁剪路径，不会丢失最近一轮的完整 tool-use 序列。
3. **多模态消息**：`MicroCompact` 守卫跳过多模态消息（含图片块等），不做内容截断。
4. **含 toolCalls 的 AssistantMessage**：不做 content 截断，避免损坏推理链/原子对。
5. **模型感知阈值**：需要 `ChatModel.getConfig().getContextLength()` 返回大于 0 的值才生效，否则回退到 `maxTokens`。
6. **`copyWith` 方法**：用于创建带有新参数的拦截器副本，共享相同的 `chatModelSupplier` 和 `compressionStrategy`。
7. **Harness 默认配置**：如果未通过 Builder 显式设置 `compressionInterceptor`，Harness 会自动创建 `CompositeCompressionStrategy(KeyInfo + Hierarchical)` 的默认配置。

---

## 十三、类关系图

```
ContextCompressionInterceptor (implements ReActInterceptor)
    │
    ├── 压缩策略接口
    │   └── CompressionStrategy (@FunctionalInterface)
    │       ├── LLMCompressionStrategy         ── LLM 语义摘要 + PTL 重试
    │       ├── KeyInfoExtractionStrategy      ── 关键信息提取
    │       ├── HierarchicalCompressionStrategy ── 层级滚动摘要
    │       ├── VectorStoreCompressionStrategy ── 向量存储归档 + recall_history 工具
    │       └── CompositeCompressionStrategy   ── 组合策略 (ALL / FIRST_MATCH)
    │
    ├── 工具类
    │   └── CompressionUtil                    ── 消息格式化、截断、PTL 检测、结果构建
    │
    ├── 事件输出
    │   └── ContextSizeChunk                   ── 上下文大小状态块
    │
    └── Harness 集成
        ├── HarnessOptions                     ── 配置持有
        └── HarnessEngine                      ── 默认配置创建 + Builder 模式接入
```
