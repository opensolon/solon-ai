# Chat 事件模型（4.1）

> `ChatRequestDesc.stream()` 在 4.1 起返回 `Flux<ChatEvent>`。职责边界固定为：`ChatEvent` 表达流式语义，`ChatAccumulator` 负责聚合，`AssistantMessage` 表达最终消息。方言负责把供应商响应解析到 `ChatStreamContext`，核心负责事件边界和终态响应。本文只描述 `solon-ai-core` 中实际提供的 API。

## 一、从哪里开始

`ChatModel` 有两条结果路径：

| API | 返回值 | 适用场景 |
|---|---|---|
| `call()` | 一个完整的 `ChatResponse` | 不需要观察流式过程 |
| `stream()` | `Flux<ChatEvent>` | 打字机、思考展示、工具调用、审计、转发等 |

```java
// 非流式：直接取得完整结果
ChatResponse response = chatModel.prompt("hello").call();

// 流式：只投影正文增量
chatModel.prompt("hello").stream()
        .filter(e -> e.is(ChatEventType.TEXT_DELTA) && e.hasText())
        .map(ChatEvent::getText)
        .subscribe(System.out::print);

// 流式：取得成功时的完整终态响应
ChatResponse response = chatModel.prompt("hello").stream()
        .filter(e -> e.is(ChatEventType.RESPONSE_END))
        .map(ChatEvent::getResponse)
        .blockFirst();
```

`RESPONSE_END` 的 `getResponse()` 才是完整聚合结果；`TEXT_DELTA`、`THINKING_DELTA` 等中间事件只表示当前分片。正常完成时一定会产生一个 `RESPONSE_END`，失败时产生 `ERROR` 并以同一异常触发 `onError`，不会再补发 `RESPONSE_END`。如果订阅方主动取消，Reactor 流会被切断，不能期待取消后再收到事件。

## 二、常见订阅方式

### 1. 只显示正文

正文事件必须按 `TEXT_DELTA` 过滤。不要只按 `isDelta()` 判断，因为思考、工具参数和拒答也属于增量事件。

```java
public Flux<String> typewriter(String message) {
    return chatModel.prompt(message).stream()
            .filter(e -> e.is(ChatEventType.TEXT_DELTA) && e.hasText())
            .map(ChatEvent::getText);
}
```

如果同时显示思考和正文，应按分组区分，并只对对应的增量事件读取文本：

```java
chatModel.prompt(query).stream()
        .filter(ChatEvent::isDelta)
        .subscribe(e -> {
            if (e.is(ChatEventType.TEXT_DELTA) && e.hasText()) {
                ui.appendText(e.getText());
            } else if (e.is(ChatEventType.THINKING_DELTA) && e.hasText()) {
                ui.appendThinking(e.getText());
            }
        });
```

`getText()` 可能为 `null`。使用方法引用 `map(ChatEvent::getText)` 前，应先用 `hasText()` 排除空负载；Reactor 的 `map` 不接受 `null` 结果。

### 2. 统一消费全部事件

事件类型固定归属于 9 个分组。订阅方可以按 `getGroup()` 分派，并保留 `default`，这样新增具体类型时不会因为 `switch` 漏掉事件。

```java
chatModel.prompt(query).stream().subscribe(
        e -> {
            switch (e.getGroup()) {
                case TEXT:
                    if (e.is(ChatEventType.TEXT_DELTA) && e.hasText()) {
                        ui.appendText(e.getText());
                    }
                    break;
                case THINKING:
                    if (e.is(ChatEventType.THINKING_DELTA) && e.hasText()) {
                        ui.appendThinking(e.getText());
                    }
                    // THINKING_SIGNATURE / THINKING_REDACTED 不是文本增量，按类型单独处理
                    break;
                case TOOL_CALL:
                    handleToolEvent(e);
                    break;
                case SERVER_TOOL:
                    handleServerTool(e.getType(), e.getSubType(), e.getRaw());
                    break;
                case MEDIA:
                    handleMediaOrCitation(e);
                    break;
                case SAFETY:
                    handleSafety(e);
                    break;
                case STEP:
                    handleStep(e.getType(), e.getStep());
                    break;
                case LIFECYCLE:
                    if (e.is(ChatEventType.RESPONSE_END)) {
                        ui.finish(e.getResponse());
                    } else if (e.is(ChatEventType.ABORT)) {
                        ui.abort();
                    }
                    break;
                case META:
                    if (e.is(ChatEventType.USAGE)) {
                        ui.updateUsage(e.getUsage());
                    } else if (e.is(ChatEventType.ERROR)) {
                        ui.fail(e.getError());
                    }
                    break;
                default:
                    log.debug("{} {}", e.getRawType(), e.getRaw());
                    break;
            }
        },
        error -> log.warn("聊天流失败", error));
```

注意：`TEXT_START` / `TEXT_END` 和 `THINKING_START` / `THINKING_END` 没有必要读取 `getText()`；它们是边界信号。工具调用的参数也不能当正文处理。

### 3. 取得终态或异步返回

同步代码可以直接阻塞等待第一个 `RESPONSE_END`：

```java
ChatResponse response = chatModel.prompt(query).stream()
        .filter(e -> e.is(ChatEventType.RESPONSE_END))
        .map(ChatEvent::getResponse)
        .blockFirst();

if (response != null) {
    String text = response.getText();
    List<ToolCall> toolCalls = response.getToolCalls();
}
```

异步代码保留为 `Mono<ChatResponse>`，使用 `next()`：

```java
Mono<ChatResponse> response = chatModel.prompt(query).stream()
        .filter(e -> e.is(ChatEventType.RESPONSE_END))
        .map(ChatEvent::getResponse)
        .next();
```

`blockFirst()` / `next()` 都表示“取第一个匹配的终态事件”，不是把中间分片重新聚合。不要对完整事件流直接调用 `blockFirst()`，否则拿到的通常是 `RESPONSE_START`。

## 三、事件载荷与类型

### 分组

| 分组 | 事件类型 | 主要载荷 |
|---|---|---|
| `LIFECYCLE` | `RESPONSE_START`、`STATUS`、`HEARTBEAT`、`RESPONSE_END`、`ABORT` | `RESPONSE_END.getResponse()` 为全流终态聚合 |
| `STEP` | `STEP_START`、`STEP_END` | `getStep()`；`STEP_END.getResponse()` 为本步聚合，`getUsage()` 为本步用量 |
| `TEXT` | `TEXT_START`、`TEXT_DELTA`、`TEXT_END` | `getText()` |
| `THINKING` | `THINKING_START`、`THINKING_DELTA`、`THINKING_END`、`THINKING_SIGNATURE`、`THINKING_REDACTED` | 思考增量或签名使用 `getText()` |
| `TOOL_CALL` | `TOOL_CALL_START`、`TOOL_CALL_ARGS_DELTA`、`TOOL_CALL_END`、`TOOL_RESULT` | `getToolCall()`、`getToolCallId()`、参数增量 `getText()` |
| `SERVER_TOOL` | `SERVER_TOOL_START`、`SERVER_TOOL_ARGS_DELTA`、`SERVER_TOOL_RESULT` | `getSubType()`、`getRaw()` 等 |
| `MEDIA` | `SEARCH_RESULT`、`CITATION`、`MEDIA_PARTIAL`、`MEDIA_DONE` | `getSearchResult()`、`getCitation()`、`getBlock()` |
| `SAFETY` | `REFUSAL_DELTA`、`CONTENT_FILTER` | 拒答文本使用 `getText()` |
| `META` | `USAGE`、`ERROR`、`RAW`、`CUSTOM` | `getUsage()`、`getError()`、`getRaw()` |

`ChatEventType` 还提供几个直接的判断方法：

```java
boolean delta = event.isDelta();
boolean terminal = event.isTerminal();
boolean text = event.is(ChatEventType.TEXT_DELTA);
boolean thinking = event.isGroup(ChatEventGroup.THINKING);
```

`isTerminal()` 只对 `RESPONSE_END`、`ABORT`、`ERROR` 返回 `true`；`STEP_END`、`TEXT_END`、`THINKING_END` 和 `TOOL_CALL_END` 是各自范围的结束事件，不是全流终止事件。

### 工具调用的生命周期

客户端工具调用按以下事件序列观察：

```java
void handleToolEvent(ChatEvent e) {
    if (e.is(ChatEventType.TOOL_CALL_START)) {
        onToolStart(e.getToolCallId(), e.getToolCall());
    } else if (e.is(ChatEventType.TOOL_CALL_ARGS_DELTA)) {
        onToolArguments(e.getToolCallId(), e.getText());
    } else if (e.is(ChatEventType.TOOL_CALL_END)) {
        onToolEnd(e.getToolCallId(), e.getToolCall());
    } else if (e.is(ChatEventType.TOOL_RESULT)) {
        onToolResult(e.getToolCallId(), e.getToolCall(), e.getText());
    }
}
```

`TOOL_CALL_ARGS_DELTA` 是参数字符串分片，不能假定每一片都是完整 JSON。工具调用完成后，完整的 `ToolCall` 列表从 `STEP_END` 或 `RESPONSE_END` 携带的 `ChatResponse` 读取。服务端工具属于 `SERVER_TOOL` 分组，不要与本地执行的 `TOOL_CALL` 混用。

### 用量与响应

- `USAGE` 是某一步的用量事件，`getUsage()` 可能为空。
- `STEP_END` 的 `getUsage()` 是该步用量。
- `RESPONSE_END` 的 `getUsage()` 是整个 `stream()`（包括自动工具调用多步）的累计用量。
- `RESPONSE_END` / `STEP_END` 的 `getResponse()` 是完整聚合，`getMessage()` 为最终 `AssistantMessage`。
- `USAGE` 可携带分片 `ChatResponse`，但该响应不包含最终 `AssistantMessage`；流式内容读取对应 `ChatEvent` 负载。

## 四、事件流不变量

核心的 `ChatEventNormalizer` 会为方言输出补齐边界，但订阅方仍应按以下契约编写：

1. 正常完成时，全流有一个 `RESPONSE_START` 和一个 `RESPONSE_END`；失败时使用 `ERROR` + `onError`，不再发 `RESPONSE_END`。
2. 每轮模型调用有一对 `STEP_START` / `STEP_END`；自动工具调用会产生多个步骤。
3. 每个正文或思考增量都处于对应的 `START` / `END` 之间；正文与思考交替时，前一个块会先结束。
4. 工具参数增量之前会有 `TOOL_CALL_START`，流结束前会有 `TOOL_CALL_END`。工具调用采用宽松补齐策略，不应依赖事件去重。
5. `responseId` 标识一次 `stream()`，`step` 标识当前模型调用；`providerResponseId` 是供应商原始响应 id，可能随自动工具调用的步骤变化。
6. `ABORT` 表示服务端或上游中止；调用方主动取消属于 Reactor cancel，两者不是同一个事件。

## 五、过滤器与失败处理

默认过滤器只屏蔽 `HEARTBEAT` 和 `RAW`。`LIFECYCLE` 与 `STEP` 分组始终放行，因为它们承载流的生命周期和终态聚合。

```java
// 默认事件之外，再放行未建模的 RAW 事件
Flux<ChatEvent> events = chatModel.prompt(query)
        .eventFilter(ChatEventFilter.DEFAULT.or(
                ChatEventFilter.of(ChatEventType.RAW)))
        .stream();

// 需要全部事件时显式开启（包括 HEARTBEAT 与 RAW）
Flux<ChatEvent> allEvents = chatModel.prompt(query)
        .eventFilter(ChatEventFilter.all())
        .stream();
```

失败有两个观察点，但表示同一次失败：

```java
chatModel.prompt(query).stream().subscribe(
        event -> {
            if (event.is(ChatEventType.ERROR)) {
                // 可选：保存已经完成的部分结果
                savePartial(event.getResponse(), event.getUsage());
            }
        },
        error -> retryOrRecover(error));
```

需要展示或保存已完成部分时消费 `ERROR`；需要使用 `retryWhen`、`onErrorResume` 等 Reactor 操作符时处理 `onError`。不要同时把两条通道当作两次独立失败。

## 六、方言作者：解析入口

`ChatDialect` 的解析入口是：

```java
void parseResponseJson(ChatStreamContext ctx, String respJson);
```

方言可以：

- 正文、思考、工具调用、搜索结果、引用、媒体和用量通过 `ctx.emit(...)` 发射语义事件，核心会先归并到 `ChatAccumulator` 再投递；
- 已解析成完整 `AssistantMessage` 的兼容路径可使用核心辅助发布方法；若需单独保存协议 metadata/raw 等终态载体，应使用 `ctx.getAccumulator().mergeTerminalMessage(...)`；
- 使用 `ctx.event(type)` 创建事件。该构建器已经预填当前 `responseId`、供应商响应 id（如果已设置）和 `step`；
- 使用 `ctx.attrPut` / `ctx.attrAs` 保存跨帧的方言私有状态；
- 解析错误时写入 `ctx.getAccumulator().setError(...)`，已消费但没有语义内容时不发事件。

示例：

```java
@Override
public void parseResponseJson(ChatStreamContext ctx, String json) {
    // 供应商首帧提取到原始响应 id 后记录一次
    ctx.setProviderResponseId(readProviderResponseId(json));

    String text = readTextDelta(json);
    if (text != null && !text.isEmpty()) {
        ctx.emit(ctx.event(ChatEventType.TEXT_DELTA)
                .text(text)
                .build());
    }

    Citation citation = readCitation(json);
    if (citation != null) {
        ctx.emit(ctx.event(ChatEventType.CITATION)
                .citation(citation)
                .text(citation.getUrl()) // 兼容只消费 text 的旧订阅方
                .build());
    }

    SearchResult result = readSearchResult(json);
    if (result != null) {
        ctx.emit(ctx.event(ChatEventType.SEARCH_RESULT)
                .searchResult(result)
                .text(result.getUrl())
                .build());
    }
}
```

方言不要对同一份正文、思考、工具参数、搜索结果、引用或媒体重复发射语义事件，否则订阅方会收到重复增量，终态聚合也会重复。累计快照协议应按请求、内容位置和稳定条目标识去重；不能按 URL 做全局去重，因为同一来源可能在答案不同位置被多次引用。`ChatEventNormalizer` 可以为第三方方言补齐缺失边界，但兜底不应替代正常的解析设计。

## 七、从旧流式用法迁移

| 旧思路 | 4.1 写法 |
|---|---|
| 把 `Flux<ChatResponse>` 的每个对象当作累计结果 | 消费 `Flux<ChatEvent>`；完整结果从 `RESPONSE_END.getResponse()` 读取 |
| 用响应对象的可变字段拼接正文 | 过滤 `TEXT_DELTA`，读取 `getText()`；最终结果读取 `ChatResponse.getText()` |
| 用 `blockLast()` 等待一个可变末帧 | 过滤 `RESPONSE_END` 后使用 `blockFirst()`，或异步使用 `next()` |
| 用启发式判断区分正文、思考和工具 | 根据 `ChatEventType` 或 `ChatEventGroup` 分派 |
| 自己维护正文/思考的开始和结束状态 | 依赖 `*_START` / `*_DELTA` / `*_END` 事件边界 |
| 从每个参数分片解析完整工具调用 | 累积 `TOOL_CALL_ARGS_DELTA`；完整工具调用从终态 `ChatResponse.getToolCalls()` 读取 |
| 方言保留旧的布尔返回解析入口 | 实现 `parseResponseJson(ChatStreamContext, String)` |
