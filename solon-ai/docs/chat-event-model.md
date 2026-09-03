# Chat 事件模型（4.1）

> `ChatRequestDesc.stream()` 自 4.1 起返回 `Flux<ChatEvent>`。事件是不可变对象，方言只负责翻译，
> 边界语义由核心的归一化器统一保证。本文是订阅方与方言作者共同依赖的契约文档。

## 一、API 分层

| 层 | API | 适用 |
|---|---|---|
| 0 | `call()` | 非流式，一把梭；返回完整 `ChatResponse` |
| 1 | `stream()` + `filter` / `map` 投影 | 只要把正文增量打到页面上（demo / 控制器 / 转发） |
| 2 | `stream()` | 全事件：思考、工具调用、服务端工具、签名、usage、生命周期 |

```java
// 层 0：非流式
ChatResponse resp = chatModel.prompt("hello").call();

// 层 1：只要正文增量（打字机）——投影同样是原生操作符
// getText() 可能为 null（空增量），必须在 filter 阶段排除：Reactor 的 map 不允许返回 null
chatModel.prompt("hello").stream()
        .filter(e -> e.is(ChatEventType.TEXT_DELTA) && e.hasText())
        .map(ChatEvent::getText)
        .subscribe(out::print);

// 层 2：统一订阅（switch 打在 group 上，前向兼容后续新增的具体类型）
chatModel.prompt("hello").stream().subscribe(e -> {
    switch (e.getGroup()) {
        case TEXT:      ui.appendText(e.getText()); break;
        case THINKING:  ui.appendThinking(e.getText()); break;
        case TOOL_CALL: ui.onToolCall(e); break;
        case LIFECYCLE: if (e.is(ChatEventType.RESPONSE_END)) done(e.getResponse()); break;
        default:        log.debug("{} {}", e.getRawType(), e.getRaw()); break;
    }
});

// 终态归约（替代旧 blockLast 依赖可变累积器的写法）
ChatResponse response = ChatEvents.reduce(chatModel.prompt("hello").stream());
```

`stream()` 返回的就是标准 `Flux<ChatEvent>`：filter / buffer / window / timeout / publishOn /
retryWhen 等 Reactor 操作符全部可用。

## 二、三类典型用户

### 用户一：只要打字机效果

**画像**：demo、演示控制器、简单文本转发。不关心思考、工具调用，只想把模型说的话逐字打出来。

**推荐姿势**：`stream()` + `filter` / `map` 投影，与其他场景同一条流。

```java
// 控制器：返回正文增量流
@Mapping("/chat/typewriter")
public Flux<String> chat(String message) {
    return chatModel.prompt(message).stream()
            .filter(e -> e.is(ChatEventType.TEXT_DELTA) && e.hasText())
            .map(ChatEvent::getText);
}

// 同步场景：逐字打印
chatModel.prompt("讲个笑话").stream()
        .filter(e -> e.is(ChatEventType.TEXT_DELTA) && e.hasText())
        .map(ChatEvent::getText)
        .subscribe(System.out::print);
```

`filter` 只放行非空 `TEXT_DELTA`，思考、工具调用等事件自动被排除。空增量检查必须放在
`filter` 阶段：Reactor 的 `map` 不允许返回 null，直接 `map(ChatEvent::getText)` 遇到空增量会抛
NPE。介意空字符串可再收紧为 `.filter(e -> !e.getText().isEmpty())`。

如果正文和思考要分区块显示，用谓词过滤即可：

```java
chatModel.prompt(query).stream()
        .filter(e -> e.getType().isDelta())        // TEXT_DELTA / THINKING_DELTA / ...
        .subscribe(e -> {
            if (e.isGroup(ChatEventGroup.TEXT)) {
                ui.appendText(e.getText());
            } else if (e.isGroup(ChatEventGroup.THINKING)) {
                ui.appendThinking(e.getText());     // 思考开始/结束的 UI 折叠由 THINKING_START/END 驱动
            }
        });
```

### 用户二：统一订阅（SSE 网关 / 协议转发 / trace 落库 / 审计）

**画像**：需要「所有事件一个出口」。网关把事件透传给前端，或把每个事件落库、写审计日志。
要求：新版本新增事件类型时不漏处理。

**推荐姿势**：单一 `subscribe` + `switch (e.getGroup())` + `default` 兜底。

```java
chatModel.prompt(query).stream().subscribe(e -> {
    switch (e.getGroup()) {
        case TEXT:        ui.appendText(e.getText());                        break;
        case THINKING:    ui.appendThinking(e.getText());                    break;
        case TOOL_CALL:   ui.onToolCall(e.getToolCallId(), e.getText());     break;
        case SERVER_TOOL: ui.onServerTool(e.getSubType(), e.getRaw());       break; // web_search / code_interpreter / mcp_call
        case MEDIA:       ui.onCitationOrMedia(e);                           break;
        case STEP:        ui.onStep(e.getStep());                            break; // 多轮工具调用轮次
        case LIFECYCLE:
            if (e.is(ChatEventType.RESPONSE_END)) {
                ui.finish(e.getResponse().getUsage());                       // 终态聚合 + 累计 usage
            }
            break;
        case META:
            if (e.is(ChatEventType.ERROR)) {
                ui.fail(e.getError());                                       // 携带已完成部分：e.getResponse() 可打捞
            }
            break;
        default:          log.debug("{} {}", e.getRawType(), e.getRaw());    break; // 新类型兜底
    }
}, err -> log.warn("流失败", err));                                            // 失败双通道的另一侧
```

要点：

- **switch 打在 group（9 个，闭集）而不是 type（34 个）**，新增具体类型只会落进既有分组或 default，
  已写的分派逻辑不会漏事件。
- **失败是双通道**：`ERROR` 事件（可打捞部分结果）+ 同一异常的 `onError` 信号。展示部分结果用前者，
  `retryWhen` / `onErrorResume` 恢复用后者，写一处即可。失败时**不会**再发 `RESPONSE_END`，
  「收到 RESPONSE_END 即视为成功」的判定是安全的。

网关需要透传**未建模原始帧**（供应商新出的字段、`ping` 等）时，用 `eventFilter` 显式开启
（`HEARTBEAT` / `RAW` 默认不投递，避免逐帧事件放大缓冲；`LIFECYCLE` / `STEP` 分组无论如何都会放行）：

```java
chatModel.prompt(query)
        .eventFilter(ChatEventFilter.DEFAULT.or(ChatEventFilter.of(ChatEventType.RAW)))
        .stream()
        .map(this::toWireFrame)   // 每个事件一个 wire 帧；未知协议帧靠 RAW（getRaw() 取原始 JSON）兜底
        ...
```

对接 AI SDK 协议（`useChat` 前端生态）则一行转换，内部同样消费事件流：

```java
@Mapping("/chat/ai-sdk")
public Flux<SseEvent> chat(String message) {
    return AiSdkStreamWrapper.of()
            .toAiSdkStream(chatModel.prompt(message).stream());
}
```

### 用户三：Agent 开发者 / 需要终态聚合

**画像**：ReAct 循环、批处理任务。要边流边消费事件（推 trace、推 UI），结束时拿完整结果继续下一步；
通常还有主动取消、失败重试诉求。

**推荐姿势**：`ChatEvents.reduce()` / `reduceAsync()` 归约终态，消费逻辑放 `doOnNext` 纯映射。

```java
// 命令式风格：边流边消费，最后拿不可变终态（替代旧 blockLast + 可变累积器）
// 消息经 prompt(...) 传入；工具与请求选项经 options(...) 配置
ChatResponse response = ChatEvents.reduce(
        chatModel.prompt(messages)                    // List<ChatMessage>：会话历史 + 本次输入
                .options(o -> o.toolAdd(tools))       // 绑定工具
                .stream()
                .takeUntil(e -> isCancelled())        // 主动取消：原生操作符
                .doOnNext(e -> {
                    AgentEvent agentEvent = AgentEvents.from(e);   // 纯映射，见下
                    if (agentEvent != null) {
                        trace.pushAgentEvent(agentEvent);
                    }
                }));

// 流中无 RESPONSE_END 时 reduce 返回 null（如取消后无回落帧），先判空
if (response != null && response.getMessage().isToolCalls()) {
    // 执行工具，递归下一轮 …
}
```

映射函数是一个独立、可单测的纯函数，取代旧写法里散落在 lambda 中的启发式判断
（`resp.hasChoices() && !resp.getMessage().isToolCalls()`）：

```java
static AgentEvent from(ChatEvent e) {
    switch (e.getGroup()) {
        case STEP:      return e.is(ChatEventType.STEP_START)   ? reasonStart(e)      : null;
        case THINKING:  return e.is(ChatEventType.THINKING_DELTA) ? reasonDelta(e.getText()) : null;
        case TEXT:      return e.is(ChatEventType.TEXT_DELTA)   ? reasonDelta(e.getText()) : null;
        case TOOL_CALL: return e.is(ChatEventType.TOOL_CALL_END) ? toolCallStart(e.getToolCall()) : null;
        default:        return null;   // 不关心的事件直接跳过，新增类型不炸
    }
}
```

异步（非阻塞）场景用 `reduceAsync`：

```java
Mono<ChatResponse> response = ChatEvents.reduceAsync(chatModel.prompt(query).stream());
```

失败打捞：`ERROR` 事件携带已完成的部分聚合，部分成功的内容不必整轮丢弃：

```java
chatModel.prompt(query).stream().subscribe(
        e -> {
            if (e.is(ChatEventType.ERROR)) {
                ChatResponse partial = e.getResponse();   // 已完成部分，可为 null
                saveSalvage(partial);
            }
        },
        err -> log.warn("流失败，准备重试", err));
```

## 三、类型二维分解

`ChatEventType`（34 个）静态绑定到 9 个 `ChatEventGroup` × 5 个 `ChatEventPhase`。
group / phase 在枚举构造期绑定，实现类不可覆写——`getType()` / `getGroup()` / `getPhase()` 永不漂移。

### 分组表

| Group         | 类型                                                                                      | 载荷入口                                                                                |
|---------------|-------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------|
| `LIFECYCLE`   | `RESPONSE_START` `STATUS` `HEARTBEAT` `RESPONSE_END` `ABORT`                              | `RESPONSE_END.getResponse()` 携带终态聚合                                               |
| `STEP`        | `STEP_START` `STEP_END`                                                                   | `STEP_END.getUsage()` 单步 usage；`getResponse()` 单步聚合                              |
| `TEXT`        | `TEXT_START` `TEXT_DELTA` `TEXT_END`                                                      | `getText()`                                                                             |
| `THINKING`    | `THINKING_START` `THINKING_DELTA` `THINKING_END` `THINKING_SIGNATURE` `THINKING_REDACTED` | `getText()`；签名为 base64 文本                                                         |
| `TOOL_CALL`   | `TOOL_CALL_START` `TOOL_CALL_ARGS_DELTA` `TOOL_CALL_END` `TOOL_CALL_CHUNK` `TOOL_RESULT`  | `getToolCall()` / `getToolCallId()`；`TOOL_RESULT` 是本地已执行的结果                   |
| `SERVER_TOOL` | `SERVER_TOOL_START` `SERVER_TOOL_ARGS_DELTA` `SERVER_TOOL_RESULT`                         | `getSubType()` 区分 `web_search` / `code_interpreter` / `mcp_call` / `google_search` 等 |
| `MEDIA`       | `CITATION` `MEDIA_PARTIAL` `MEDIA_DONE`                                                   | 引用与生成媒体                                                                          |
| `SAFETY`      | `REFUSAL_DELTA` `CONTENT_FILTER`                                                          | 拒答与内容过滤                                                                          |
| `META`        | `USAGE` `ERROR` `RAW` `CUSTOM`                                                            | `RAW` 为未建模原始帧，`getRaw()` 取原始 JSON                                            |

### Phase

`START` / `DELTA` / `END` / `CHUNK` / `NONE`。

- `*_CHUNK` 是「一发即含首尾」的完整块变体（Ollama 等只返回完整块的方言）；归一化器自动展开为 `START + DELTA + END`，订阅方无需分辨方言是否逐 token 流式。
- 谓词：`e.getType().isDelta()` / `isChunk()` / `isMainContent()`（TEXT/THINKING/TOOL_CALL 的
  START/DELTA/CHUNK，专用于「本帧是否已以事件形态表达内容主干」的门控判定）/ `isTerminal()`。

## 四、硬不变量（订阅方唯一可依赖的契约）

1. **每次订阅 `RESPONSE_START` / `RESPONSE_END` 各恰一次**，只在最外层发射；工具递归轮不重复发。
2. **每轮 LLM 调用对应一对 `STEP_START` / `STEP_END`**；方言看到的轮内终止标记映射为 `STEP_END`，不是 `RESPONSE_END`。
3. **每个 `TEXT_DELTA` / `THINKING_DELTA` 一定被对应 `*_START` / `*_END` 包裹**；正文与思考交替时自动关闭前一块；流终止前自动补齐。
4. **TOOL_CALL 组宽松补齐**：`ARGS_DELTA` 之前必有 `START`、流终止前必有 `END`；但绝不去重 `START`、绝不丢弃 `END`（分片式工具调用只在首片携带 id）。
5. **`ABORT` 后仍会发 `RESPONSE_END`**；`ERROR` 通过 `getError()` 携带异常，且失败时不再发 `RESPONSE_END`。
6. **`RESPONSE_END` 携带多步累计 Usage**；`STEP_END` 携带单步 Usage。
7. **`responseId` / `step` 全流一致**（含归一化器补齐的事件）；`getProviderResponseId()` 携带供应商原始响应 id（如 `resp_xxx`），用于关联供应商侧日志排障。
8. `HEARTBEAT` / `RAW` 默认不投递；通过 `eventFilter(ChatEventFilter.all())` 或 `DEFAULT.or(of(RAW))` 显式开启。

### ABORT 与 cancel 的区别

| | `ABORT` 事件 | Reactor cancel |
|---|---|---|
| 含义 | **上游/服务端**中止（Anthropic error 事件、Responses `response.incomplete`） | **订阅方**主动取消 |
| 观察方式 | 事件流内的一个事件 | `doOnCancel(...)` / `takeUntil(...)` |
| 之后的行为 | 仍发 `RESPONSE_END` 归约收尾 | 流被切断，不再有任何事件 |

Reactive Streams 规范禁止 cancel 后再 onNext，因此**不要**指望「取消时收到 ABORT 事件」——取消是订阅侧语义，用 Reactor 原生操作符观察。

## 五、方言迁移指南（`parseResponseJson` 契约）

4.1 的 `ChatDialect` 事件出口：

```java
public interface ChatDialect extends AiModelDialect {
    /**
     * 方言解析响应的唯一必需入口：流式为当帧 SSE data，非流式为完整响应体。
     * 有内容就 ctx.emit(...) 或写入 ctx.getAccumulator()，出错就 setError，
     * 已消费但无内容则什么都不做（不再用 boolean 区分「有内容」与「解析失败」）。
     */
    void parseResponseJson(ChatStreamContext ctx, String respJson);
}
```

### 迁移三步

1. **覆盖 `parseResponseJson`**：从 `data`（JSON 字符串）解析后，通过 `ctx.emit(...)` 或 `ctx.event(TYPE)...build()` 发射事件。context 已预填 `responseId` / `step`。
2. **协议跨帧状态**：原先寄生在 `ChatResponseDefault` 的公共可变字段（`in_thinking` / `lastFinishReason` / `thinkingSignature` 等）迁到 `ctx.attrPut(name, val)` / `ctx.attrAs(name)`，或使用专用事件（`THINKING_SIGNATURE` / `THINKING_REDACTED`）。
3. **不要保留只收 `ChatAccumulator` 的平行解析入口**，避免双路径漂移。

### contentEmits 规则（最重要的一条）

**方言要么发事件，要么写 accumulator 的 choice，绝不能两者都做。**

核心用 `contentEmits` 计数器区分两条路径（`ChatRequestDescDefault`）：

- 方言在 `parseResponseJson` 里 `ctx.emit(...)` 了内容事件（TEXT/THINKING/TOOL_CALL 等）→ 计数器增加 → 核心跳过 `publishChoice` 的 choice→事件转换；
- 方言没发任何事件（只写 accumulator 的内容项）→ 核心把 accumulator 里的 choice 转换为 `TEXT_DELTA` / `THINKING_DELTA` / `TOOL_CALL_*` 事件。

**双发的症状**：订阅方收到重复正文/重复参数（同一内容既从事件、又从 choice 转换流出），且终态聚合翻倍。

### 未建模事件的处理

协议里出现方言尚未建模的事件类型时，发射 `RAW` 事件（`getRaw()` 携带原始 JSON），**不要静默丢弃**。`RAW` 默认不投递给订阅方，但网关透传/诊断场景可通过 `eventFilter` 开启。

### 归一化器兜底范围

方言不需要自己保证边界配平：

- 裸 `TEXT_DELTA` / `THINKING_DELTA` / `TOOL_CALL_ARGS_DELTA`（无 START）→ 自动补 START；
- 未闭合的块 → `STEP_END` / `RESPONSE_END` / `ABORT` / 流结束前自动补 END；
- `*_CHUNK` → 自动展开；
- TEXT/THINKING 重复 START 去重、孤立 END 丢弃（严格配对，按 itemId + index）；TOOL_CALL 只补不删（宽松）。

但**不要依赖兜底作为正常设计**——内置方言都显式发射完整边界，兜底只为第三方方言的安全网。

### 下线时间表

| 版本 | 动作 |
|---|---|
| 4.1 | `parseResponseJson(ChatStreamContext, String)` 成为方言解析的唯一入口；旧的 `boolean tryParseFrameJson(ChatConfig, ChatAccumulator, String)` 已移除 |
| 4.2 | `ChatAccumulator` 收窄为框架内部（方言只经 `ChatStreamContext` 交互） |

## 六、迁移对照表（旧流用法 → 4.1）

| 旧写法（4.0） | 新写法（4.1）                                                                                 |
|---|-----------------------------------------------------------------------------------------------|
| `stream().subscribe(resp -> resp.getMessage().getContent())` | `stream().filter(e -> e.is(ChatEventType.TEXT_DELTA) && e.hasText()).map(ChatEvent::getText)` |
| `stream()` 里 `filter(resp -> resp.isFinished())` 取末帧 usage | `reduce(stream())` → `RESPONSE_END.getUsage()`，或订阅 `ChatEventType.USAGE`                  |
| `blockLast()` 聚合（依赖可变实例） | `ChatEvents.reduce(stream())`（不可变终态）                                                   |
| `resp.isThinking()` 判断思考帧 | `e.getGroup() == ChatEventGroup.THINKING`                                                     |
| `resp.getMessage().getToolCalls()` 流式累计 | `TOOL_CALL_START/ARGS_DELTA/END` 事件序列，终态经 `reduce()`                                  |
| 自写 `textStarted` 状态机保证边界 | 直接依赖 `*_START/*_DELTA/*_END` 不变量                                                       |
| `interceptStream(Flux<ChatResponse>)` | `interceptStream(Flux<ChatEvent>)`                                                            |
| `toAiSdkStream(Flux<ChatResponse>)` | `toAiSdkStream(Flux<ChatEvent>)`（旧重载更名 `toAiSdkStreamOfResponses`，已弃用）             |
