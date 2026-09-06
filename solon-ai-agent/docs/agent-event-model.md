# Agent 事件模型（4.1）

> `AgentRequest.stream()` 返回 `Flux<AgentEvent>`。Agent 事件描述 `SimpleAgent`、`ReActAgent` 和 `TeamAgent` 的执行过程；其中部分增量事件包装底层 `ChatEvent`，但运行、Reason、Action、工具执行、HITL 和团队节点具有独立的 Agent 语义。本文只描述 `solon-ai-agent` 当前实际提供的 API。

## 一、从哪里开始

Agent 请求有三条结果路径：

| API | 返回值 | 适用场景 |
|---|---|---|
| `call()` | `SimpleResponse` / `ReActResponse` / `TeamResponse` | 同步取得完整结果；不产生顶层 End 事件 |
| `callAsync()` | `CompletableFuture<SimpleResponse / ReActResponse / TeamResponse>` | 异步取得完整结果；内部委托 `call()`，不产生顶层 End 事件 |
| `stream()` | `Flux<AgentEvent>` | 展示增量、观察工具和团队执行、审计等；由请求包装器追加顶层 End 事件 |

```java
// 非流式：直接取得完整结果
ReActResponse response = agent.prompt("搜索并总结 Solon AI").call();
String answer = response.getContent();

// 流式：观察正文增量
agent.prompt("搜索并总结 Solon AI").stream()
        .ofType(ReasonDeltaEvent.class)
        .filter(e -> !e.isThinking() && e.hasText())
        .map(ReasonDeltaEvent::getText)
        .subscribe(System.out::print);

// 流式：取得 ReAct 正常收敛后的完整响应
ReActResponse response = agent.prompt("搜索并总结 Solon AI").stream()
        .ofType(RunEndEvent.class)
        .map(RunEndEvent::getResponse)
        .blockFirst();
```

每个 Request 还可通过 `session(AgentSession)` 绑定会话，并通过各自的 `options(...)` 修改本次运行选项。Request 是可变且非线程安全的，一次调用应使用一次 `agent.prompt(...)` 新建的 Request。

不同 Agent 使用不同的顶层结束事件：

| Agent | 顶层开始事件 | 顶层结束事件 | 完整响应入口 |
|---|---|---|---|
| `SimpleAgent` | `SimpleStartEvent` | `SimpleEndEvent` | `SimpleEndEvent.getResponse()` |
| `ReActAgent` | `RunStartEvent` | `RunEndEvent` | `RunEndEvent.getResponse()` |
| `TeamAgent` | `TeamStartEvent` | `TeamEndEvent` | `TeamEndEvent.getResponse()` |

`ReasonEndEvent`、`ActionEndEvent`、`ToolCallEndEvent` 和 `NodeEndEvent` 都只是局部阶段结束，不能作为整个请求的终态。

## 二、公共事件契约

所有事件都实现 `AgentEvent`；该接口同时继承标记接口 `AiEvent` 和 `NonSerializable`。公共 API 如下：

| API | 含义 |
|---|---|
| `getRunId()` | 当前运行标识 |
| `getAgentName()` | 产生事件的 Agent 名称 |
| `getSession()` | 当前 `AgentSession` |
| `getMeta()` | 事件元数据 |
| `hasMeta(name)` | 是否存在指定元数据 |
| `hasText()` | 当前事件是否有非空文本 |
| `getText()` | 事件的文本投影；无文本的事件默认返回空字符串 |

Agent 层没有统一的 `AgentEventType` 或 `AgentEventGroup`。消费事件时应使用具体事件类和 `instanceof` / `ofType(...)`，不能套用 Chat 层的类型分组方式。

### 关联标识

- `runId`：标识由一次非空 Prompt 创建的逻辑任务，不等同于一次 `stream()` 订阅。使用同一 Session 以空 Prompt 恢复执行时会复用原 `runId`，因此同一 `runId` 可能跨请求出现多组 Start/End。
- `agentName`：标识事件的实际产生者；Team 流中可能出现多个 Agent 的事件。
- `reasonId`：关联一次 ReAct Reason 回合，可见于 Reason、工具和计划事件。
- `callId`：关联一次本地工具调用。
- `Node`：只存在于 Team 的节点边界及 Supervisor 事件中，标识协作图节点；成员 Agent 事件本身不携带父 Team 或 Node 标识。

当前事件 API 没有单独的 invocationId/resumeId，也没有成员事件到父 Team 节点的稳定关联字段。需要区分恢复批次或并行成员归属时，消费方应在传输 DTO 或外围执行上下文中补充关联信息。

`getMeta()` 返回事件内部按需创建的可变 Map，不是只读快照。`AgentEvent`、`Session`、`Trace` 等都是运行态对象。向 SSE、WebSocket 或消息队列转发时，应投影为自己的 DTO，不要直接把事件对象当作稳定的 JSON 协议。

## 三、常见消费方式

### 1. 区分正文与思考

`SimpleDeltaEvent`、`ReasonDeltaEvent` 和 `SupervisorDeltaEvent` 在正常模型输出路径中包装底层 `ChatEvent`，但对应不同执行范围：

| Agent 事件 | 范围 |
|---|---|
| `SimpleDeltaEvent` | SimpleAgent 的模型输出 |
| `ReasonDeltaEvent` | ReAct 的一次 Reason 回合 |
| `SupervisorDeltaEvent` | Team Supervisor 的模型输出 |

```java
agent.prompt(query).stream()
        .ofType(ReasonDeltaEvent.class)
        .subscribe(event -> {
            ChatEvent chatEvent = event.getChatEvent();
            if (chatEvent == null) {
                return; // 公开签名为 @Nullable，扩展事件也应安全处理
            }

            if (chatEvent.is(ChatEventType.TEXT_DELTA) && event.hasText()) {
                ui.appendText(event.getText());
            } else if (chatEvent.is(ChatEventType.THINKING_DELTA) && event.hasText()) {
                ui.appendThinking(event.getText());
            } else if (chatEvent.is(ChatEventType.MEDIA_DONE)) {
                ui.appendMedia(chatEvent.getBlock());
            }
        });
```

内置 Agent 当前只把以下七种 Chat 事件包装为 Delta：

```text
TEXT_START / TEXT_DELTA / TEXT_END
THINKING_START / THINKING_DELTA / THINKING_END
MEDIA_DONE
```

不要把所有 `ReasonDeltaEvent` 都当作正文。边界事件通常没有文本，`isThinking()` 可以快速区分思考分组；需要精确区分边界、增量或媒体时，应读取 `getChatEvent()`。`THINKING_SIGNATURE`、`THINKING_REDACTED`、`CITATION`、`MEDIA_PARTIAL`、Chat 工具事件、`USAGE`、`ERROR` 和 `RESPONSE_END` 等不会被包装到 Agent Delta 中。

正常 Reason Delta 对应底层 Chat 事件；但 ReAct 把部分内部故障转换为异常终态答案时，还会合成 `TEXT_DELTA` 和 `TEXT_END`，且不保证先有 `TEXT_START`。因此消费方不应把 Delta 当成严格成对、完整的底层协议回放。

### 2. 统一分派 ReAct 事件

```java
agent.prompt(query).stream().subscribe(
        event -> {
            if (event instanceof RunStartEvent) {
                ui.runStarted(event.getRunId());
            } else if (event instanceof ReasonDeltaEvent) {
                ReasonDeltaEvent delta = (ReasonDeltaEvent) event;
                if (delta.hasText()) {
                    if (delta.isThinking()) {
                        ui.appendThinking(delta.getText());
                    } else {
                        ui.appendText(delta.getText());
                    }
                }
            } else if (event instanceof ToolCallStartEvent) {
                ToolCallStartEvent start = (ToolCallStartEvent) event;
                ui.toolStarted(start.getCallId(), start.getToolName(), start.getArgs());
            } else if (event instanceof ToolCallEndEvent) {
                ToolCallEndEvent end = (ToolCallEndEvent) event;
                ui.toolFinished(end.getCallId(), end.getText(), end.getError());
            } else if (event instanceof HITLPendingEvent) {
                ui.requestApproval(((HITLPendingEvent) event).getPendingTasks());
            } else if (event instanceof RunEndEvent) {
                RunEndEvent end = (RunEndEvent) event;
                ui.finish(end.getResponse(), end.isAbnormal());
            } else {
                // 保留未知事件，便于兼容后续新增的事件类
                log.debug("Agent event: {}", event.getClass().getName());
            }
        },
        error -> log.warn("Agent 流失败", error));
```

### 3. 取得完整终态响应

同步代码可以过滤具体的顶层结束事件，再使用 `blockFirst()`：

```java
SimpleResponse simpleResponse = simpleAgent.prompt(query).stream()
        .ofType(SimpleEndEvent.class)
        .map(SimpleEndEvent::getResponse)
        .blockFirst();

ReActResponse reactResponse = reactAgent.prompt(query).stream()
        .ofType(RunEndEvent.class)
        .map(RunEndEvent::getResponse)
        .blockFirst();

TeamResponse teamResponse = teamAgent.prompt(query).stream()
        .ofType(TeamEndEvent.class)
        .map(TeamEndEvent::getResponse)
        .blockFirst();
```

异步代码保留为 `Mono`，使用 `next()`：

```java
Mono<ReActResponse> response = agent.prompt(query).stream()
        .ofType(RunEndEvent.class)
        .map(RunEndEvent::getResponse)
        .next();
```

不要对未过滤的 Agent 事件流直接调用 `blockFirst()`，否则拿到的是开始事件。主动取消或流以 `onError` 结束时都不会补发顶层结束事件。主动取消且派生流正常完成时，上述同步写法可能返回 `null`，异步写法得到空 `Mono`；若流以 `onError` 结束，`blockFirst()` 会抛出异常，`Mono.next()` 会传播错误信号。调用方应分别处理取消、空结果和错误信号。

## 四、事件类型与载荷

### 1. SimpleAgent

| 事件 | 主要载荷 | 说明 |
|---|---|---|
| `SimpleStartEvent` | `getTrace()` | Simple 运行开始 |
| `SimpleDeltaEvent` | `getTrace()`、`getChatEvent()`、`isThinking()`、`getText()` | 被选择转发的底层 Chat 事件 |
| `SimpleEndEvent` | `getResponse()`、`getMessage()`、`getTrace()`、`getText()` | 顶层完整结果 |

正常时序：

```text
SimpleStartEvent
  -> SimpleDeltaEvent*
  -> SimpleEndEvent
  -> onComplete
```

### 2. ReAct 运行与 Reason

| 事件 | 主要载荷 | 说明 |
|---|---|---|
| `RunStartEvent` | `getTrace()` | 整个 ReAct 运行开始 |
| `ReasonStartEvent` | `getTrace()`、`getSystemPrompt()`、`getWorkingMemory()`、`getReasonId()` | 一次 Reason 回合开始 |
| `ReasonDeltaEvent` | `getTrace()`、`getChatEvent()`、`getReasonId()`、`isThinking()`、`getText()` | 本回合选择转发的 Chat 事件 |
| `ReasonEndEvent` | `getTrace()`、`getResponse()`、`getMessage()`、`getThinking()`、`getText()`、`isToolCalls()`、`getToolCalls()`、`getDurationMs()`、`getReasonId()` | 一次模型推理完成 |
| `RunEndEvent` | `getResponse()`、`getMessage()`、`getTrace()`、`getMetrics()`、`getText()`、`isAbnormal()` | 整个 ReAct 运行结束 |

`ReasonEndEvent.getResponse()` 是一次底层模型调用的 `ChatResponse`，不是整个 Agent 的 `ReActResponse`。ReAct 可执行多轮 Reason → Action，最终结果必须从 `RunEndEvent` 读取。

### 3. Action 与本地工具

| 事件 | 主要载荷 | 说明 |
|---|---|---|
| `ActionStartEvent` | `getTrace()`、`getToolCalls()`（`Collection<ToolExchanger>`） | 一次 Action 阶段开始 |
| `ToolCallStartEvent` | `getTrace()`、`getCallId()`、`getToolName()`、`getArgs()`、`getReasonId()` | Agent 开始处理一个工具调用 |
| `ToolCallEndEvent` | Start 事件载荷，加 `getResult()`、`getText()`、`getError()`、`getDurationMs()` | Agent 对一个工具调用处理结束；结果为 observation 消息 |
| `ActionEndEvent` | `getTrace()` | 一次 Action 阶段结束 |

```java
agent.prompt(query).stream()
        .subscribe(event -> {
            if (event instanceof ToolCallStartEvent) {
                ToolCallStartEvent start = (ToolCallStartEvent) event;
                audit.start(start.getCallId(), start.getToolName(), start.getArgs());
            } else if (event instanceof ToolCallEndEvent) {
                ToolCallEndEvent end = (ToolCallEndEvent) event;
                audit.end(end.getCallId(), end.getResult(),
                        end.getError(), end.getDurationMs());
            }
        });
```

`ReasonEndEvent.getToolCalls()` 返回模型消息中的 `List<ToolCall>`；`ActionStartEvent.getToolCalls()` 返回 Agent 为执行阶段准备的 `Collection<ToolExchanger>`，两者不是同一种对象。Agent 层当前没有工具参数增量事件，不要把它与底层 Chat 的 `TOOL_CALL_ARGS_DELTA` 混用。供应商在模型侧执行的服务端工具也不会产生这组 Agent Action/Tool 事件。

`ToolCallStartEvent` / `ToolCallEndEvent` 表示 Agent 对调用的处理生命周期，不一定等同于真实调用了工具实现：HITL 的 skip 或批内 reject 会预填 observation，仍可产生完整的 Start/End。当前同一 Action 内的多个工具按顺序处理，典型顺序是 `start(A) -> end(A) -> start(B) -> end(B)`；这与 Team 节点可并行执行不同。

`ToolCallEndEvent.getResult()` 返回写入 ReAct 上下文的 `ChatMessage`，可能为 `null`，不是原始 Java 返回对象或 `ToolResult`；`getText()` 只是该消息内容的文本投影。普通 `FunctionTool.call()` 抛出的参数错误或执行异常当前通常会被转换为 observation 文本，此时 `getError()` 仍可能为 `null`。只有逃出工具动作执行路径并进入事件闭合逻辑的异常才会写入 `getError()`；更早的拦截器异常还可能直接触发 Reactor `onError` 而没有工具 End 事件。审计端应综合检查 observation、`getError()` 和流错误信号。

### 4. 计划、HITL 与上下文

| 事件 | 主要载荷 | 说明 |
|---|---|---|
| `PlanEvent` | `getTrace()`、`getPlans()`、`getPlanStage()`、`getPlanIndex()`、`getReasonId()` | 计划创建、推进或修订 |
| `HITLPendingEvent` | `getPendingTasks()`、`getTrace()` | 一批工具调用等待人工审核 |
| `HITLDecidedEvent` | `getTrace()`、`getCallId()`、`getToolName()`、`getArgs()`、`getComment()`、`getDecision()`、`isApproved()`、`isRejected()`、`isSkipped()` | 人工决策已生效 |
| `ContextSizeEvent` | `getTrace()`、`getContextLength()`、`getMessageCount()`、`getTokenCount()`、`isCompressed()`、四个 `getBefore*` / `getAfter*` 统计 | 上下文状态或压缩结果 |

`PlanEvent.getPlanStage()` 的取值是 `CREATE`、`PROGRESS`、`REVISE`。计划操作本身由工具实现，因此 `PlanEvent` 通常出现在对应 `ToolCallStartEvent` 和 `ToolCallEndEvent` 之间。

`HITLDecidedEvent.getCallId()` 和 `getComment()` 的公开签名允许为 `null`。`HITLPendingEvent.getPendingTasks()` 是不可修改的浅拷贝；`HITLDecidedEvent.getArgs()` 也是不可修改的浅拷贝。

`ContextSizeEvent` 由上下文压缩拦截器在每次 Reason 开始前产生，并非每个 ReAct 流都存在。它没有 `reasonId`；拦截器运行在 `ReasonStartEvent` 之前，因此该事件可能先于对应 Reason，甚至在流程提前转向结束时没有后续 `ReasonStartEvent`。`tokenCount` 及压缩前后 Token 数是估算值，不是供应商 Usage；未压缩时四个 before/after 统计均为 `0`。

### 5. TeamAgent

| 事件 | 主要载荷 | 说明 |
|---|---|---|
| `TeamStartEvent` | `getTrace()` | Team 顶层运行开始 |
| `SupervisorDeltaEvent` | `getNode()`、`getTrace()`、`getChatEvent()`、`isThinking()`、`getText()` | Supervisor 的流式输出 |
| `NodeStartEvent` | `getNode()`、`getTrace()` | 即将调用一个成员 Agent |
| `NodeEndEvent` | `getNode()`、`getMessage()`、`getTrace()`、`getText()` | 成员调用正常返回 |
| `TeamEndEvent` | `getResponse()`、`getMessage()`、`getTrace()`、`getText()` | Team 顶层完整结果 |

Team 的流会把成员 Agent 的过程事件合并到同一个 sink。顺序节点可近似观察为：

```text
TeamStartEvent（父 runId）
  -> SupervisorDeltaEvent*
  -> NodeStartEvent（父 runId，携带 Node）
       -> 成员 Agent 的 Start、增量、Reason、Action 或工具事件*（成员 runId）
       -> 不产生成员自己的顶层 End 事件
  -> NodeEndEvent（父 runId，携带 Node）
  -> ...
  -> TeamEndEvent（父 runId）
  -> onComplete
```

当前 Team Flow 直接调用成员的 `call(...)`，不会经过成员 Request 的 `stream()` 包装器。因此成员会产生自身的 Start 和过程事件，但不会产生对应的 `SimpleEndEvent`、`RunEndEvent` 或 `TeamEndEvent`；成员边界应使用父 Team 的 `NodeStartEvent` / `NodeEndEvent`，整个团队完成应使用顶层 `TeamEndEvent`。

并行节点下，上述事件不是严格嵌套的树：多个 `NodeStartEvent`、成员过程事件和 `NodeEndEvent` 可能交错。节点边界事件使用父 Team 的 `runId` 和 `agentName`，成员事件使用成员自己的标识，且成员事件不携带 `Node` 或 `parentRunId`。不要用“最近收到的 NodeStart”推断并行成员归属；需要无歧义审计时应额外建立父子关联。成员调用抛出未处理异常时，`NodeEndEvent` 也可能缺失。

## 五、ReAct 生命周期

### 1. 基本循环

无工具调用时，典型时序为：

```text
RunStartEvent
  -> [ContextSizeEvent]
  -> ReasonStartEvent
  -> ReasonDeltaEvent*
  -> ReasonEndEvent
  -> RunEndEvent
  -> onComplete
```

包含本地工具调用时，典型时序为：

```text
RunStartEvent
  -> ReasonStartEvent
  -> ReasonDeltaEvent*
  -> ReasonEndEvent
  -> ActionStartEvent
       -> ToolCallStartEvent
       -> ToolCallEndEvent
       -> ...更多工具调用（当前顺序处理）
  -> ActionEndEvent
  -> ...下一轮 Reason
  -> RunEndEvent
  -> onComplete
```

### 2. HITL 分支

HITL 在 Action 开始拦截阶段工作。首次发现待审核调用时，典型路径是：

```text
ReasonEndEvent
  -> HITLPendingEvent
  -> 本次不会产生 ActionStartEvent
  -> 不会伪造 ToolCallStartEvent / ToolCallEndEvent / ActionEndEvent
  -> RunEndEvent（当前实现 isAbnormal=true，session 仍为 pending）
  -> onComplete
```

挂起不是 Reactor 错误。这里的 `RunEndEvent` 只结束本次 Request/流，不代表可恢复的逻辑任务永久结束；当前实现会把 pending 摘要设为异常终态答案，因此 `isAbnormal()` 为 `true`。UI 应结合 `HITLPendingEvent` 或 `session.isPending()` 判断“等待审批”，不要仅凭 `isAbnormal()` 把它展示为不可恢复故障。会话会保留 pending 状态，调用方取得待审任务、提交决策后，可使用同一 Session 和空 Prompt 恢复执行。

恢复后，批准或跳过的典型路径是：

```text
RunStartEvent（复用挂起前的 runId）
  -> HITLDecidedEvent+
  -> ActionStartEvent
  -> ToolCallStartEvent
  -> ToolCallEndEvent
  -> ActionEndEvent
  -> [下一轮 Reason...]
  -> RunEndEvent
```

恢复会复用挂起前保存的 `lastReasonMessage` 和同一个 `runId`，通常直接进入 Action，不会在 `HITLDecidedEvent` 前重新产生 Reason。批准会真实执行工具；skip 或多个敏感调用中的批内 reject 会预填 observation，仍可出现 ToolCall Start/End，但不会调用工具实现。只有一个敏感调用时，reject 可能直接把 ReAct 路由到结束，因此收到 `HITLDecidedEvent` 后不应强制期待 Action 或工具事件。

## 六、响应、轨迹与指标

所有具体 `AgentResponse` 都提供：

- `getTrace()`：执行轨迹；
- `getSession()`：会话；
- `getContext()`：Flow 上下文；
- `getMetrics()`：执行指标；
- `getMessage()`：最终 `AssistantMessage`；
- `getContent()`：最终消息正文；
- `toBean(type)`：把最终答案转换为结构化对象。

流式消费时，完整响应从对应的顶层结束事件读取。`RunEndEvent` 还直接提供 `getMetrics()` 和 `isAbnormal()`；中间 Delta 事件只用于过程展示，不应自行拼接后替代最终响应。

## 七、失败、异常终态与取消

Agent 当前没有统一的 `ErrorEvent` 或 `CancelEvent`。需要区分三个层次：

### 1. 未处理异常

未被 Agent 内部转换的异常通过 Reactor `onError` 结束：

```java
agent.prompt(query).stream().subscribe(
        this::handleEvent,
        error -> retryOrRecover(error));
```

这种情况下不会再补发 `SimpleEndEvent`、`RunEndEvent` 或 `TeamEndEvent`。

### 2. ReAct 异常终态与工具异常

部分 ReAct 故障会被转换为可展示的最终答案，此时流仍以 `RunEndEvent` 和 `onComplete` 收敛，但 `RunEndEvent.isAbnormal()` 为 `true`：

```java
agent.prompt(query).stream()
        .ofType(RunEndEvent.class)
        .subscribe(end -> {
            if (end.isAbnormal()) {
                ui.showAbnormalResult(end.getText());
            } else {
                ui.showResult(end.getText());
            }
        });
```

普通工具函数的参数错误或执行异常通常会先被转换为 observation 文本，不一定出现在 `ToolCallEndEvent.getError()`；未被吸收且进入工具闭合逻辑的异常才可从该字段读取，更早失败则可能直接成为 Reactor `onError`。它和 `RunEndEvent.isAbnormal()` 表示不同层级，审计时应同时观察结果消息、`getError()`、异常终态和流错误。

### 3. 主动取消

订阅取消会中断正在执行的请求，之后不再投递事件，也不会为了闭合序列而补发顶层 End 事件：

```java
List<AgentEvent> firstEvents = agent.prompt(query).stream()
        .take(2)
        .collectList()
        .block();
```

派生流的 `take(2)` 可以正常完成，但对原始生产者而言这是取消。取消会尝试中断执行线程，后续业务是否立即停止仍取决于底层阻塞调用如何响应中断；没有 End 事件也不等于内部结束钩子一定未执行。不要据此假定第二个事件之后还存在 `RunEndEvent` 或其他结束事件。

## 八、事件流不变量

订阅方应按以下契约编写：

1. 对单次正常完成的顶层 `stream()` 订阅，对应的 `SimpleEndEvent`、`RunEndEvent` 或 `TeamEndEvent` 是最后一个业务事件，随后 `onComplete`。
2. 顶层结束事件仅由各 Request 的 `stream()` 包装器在完整响应构造后产生；`call()`、`callAsync()` 和 Team 对成员的直接调用不产生这类 End，局部阶段结束事件也不能替代它。
3. `runId` 标识逻辑任务；HITL 恢复可在同一 `runId` 下产生新一组 `RunStartEvent` / `RunEndEvent`。
4. ReAct 可以有多轮 Reason → Action；每个 `ReasonEndEvent` 只结束当前 Reason 回合。
5. 工具 Start/End 表示调用处理边界；多个调用当前顺序处理，HITL 预填结果也可能产生边界，异常路径则不保证严格闭合。
6. HITL pending 会省略尚未开始的 Action/Tool 事件，但仍以 `RunEndEvent` 正常完成本次流；恢复后继续原逻辑任务。
7. Team 流可包含并行且交错的成员事件；成员事件没有父 Team/Node 关联字段，不能仅靠现有标识无歧义还原并行嵌套关系。
8. 主动取消后不保证任何结束事件；未处理异常通过 `onError` 终止，也不补发顶层结束事件，局部边界也可能不完整。
9. Agent 层仅包装七种白名单 Chat 事件，且异常终态还可能产生合成 Delta；不要把 Agent Delta 当作完整 Chat 事件流。

## 九、Agent 事件与 Chat 事件的边界

- 需要正文、思考边界或媒体详情时，读取 Delta 事件携带的 `ChatEvent`；但只会看到白名单类型，且异常终态可能出现合成事件。
- 需要 Reason、Action、工具处理、HITL、计划、上下文压缩或团队协作语义时，消费具体的 `AgentEvent`。
- Chat 工具事件描述模型响应中的工具调用内容；Agent 工具事件描述 ReAct 对本地工具调用的处理，包括真实执行和 HITL 预填 observation。
- Chat 的 `RESPONSE_END` 只结束一次底层 Chat 流且不会作为 Agent Delta 转发；Agent 顶层结果仍应读取 `SimpleEndEvent`、`RunEndEvent` 或 `TeamEndEvent`。
- Agent 请求没有 `ChatEventFilter` 对应物。需要筛选事件时使用 Reactor 的 `filter(...)`、`ofType(...)` 或 `handle(...)`。

## 十、事件扩展边界

事件由其真正发生的执行位置产生：

- Agent 执行逻辑产生顶层开始事件；
- Request 的 `stream()` 包装器产生顶层结束事件；
- Reason、Action 和工具任务产生各自的生命周期事件；
- HITL、上下文压缩等拦截器产生治理事件；
- Team Flow 节点产生节点边界事件。

扩展 Agent 或拦截器时，应在行为真正开始或完成的位置发射一次对应事件，不要为了视觉闭合伪造未发生的结束事件。事件投递也不应改变 Agent 主流程：订阅已经取消时应停止投递，事件消费端失败不应被误当作工具或模型执行结果。

## 十一、从旧流式用法迁移

| 旧思路 | 4.1 写法 |
|---|---|
| 使用 `AgentChunk` | 使用 `AgentEvent` 及具体事件类 |
| 把每个流事件都当作正文 | 只消费对应 Delta 事件，并检查其 `ChatEvent` 类型 |
| 用统一完成标记判断所有 Agent | 按 Agent 类型过滤 `SimpleEndEvent`、`RunEndEvent` 或 `TeamEndEvent` |
| 把 `ReasonEndEvent` 当作 ReAct 最终结果 | 从 `RunEndEvent.getResponse()` 读取完整 `ReActResponse` |
| 从 Chat 工具参数分片推断工具执行 | 使用 `ToolCallStartEvent` / `ToolCallEndEvent` 观察本地工具执行 |
| 失败只看 Reactor `onError` | 同时观察 observation、`ToolCallEndEvent.getError()`、`RunEndEvent.isAbnormal()` 和 Reactor `onError` |
| 对完整事件流直接调用 `blockFirst()` | 先过滤具体顶层 End 事件，再调用 `blockFirst()`；异步使用 `next()` |
| 直接序列化事件对象 | 显式投影需要的字段，避免暴露 Trace、Session 等运行态对象 |
