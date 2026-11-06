# solon-ai-harness-queue

solon-ai-harness 的会话级排队插件。在当前任务运行时，把后续消息登记到会话队列；当前任务结束、会话空闲后，由宿主驱动领取（`claimNext` / `drainLoop`），把队列里的消息作为新任务发送。

- 包名：`org.noear.solon.ai.harness.extension.queue`
- 只依赖核心契约（`HarnessExtension` / `spi.InputBacklog` / `spi.TurnDispatch`），不依赖其它插件
- 宿主只接触一个门面类：`QueueExtension`

## 一、功能介绍

### 1.1 与 steer 的区别

| 能力 | 作用对象 | 对进行中任务的影响 | 消费时机 |
|---|---|---|---|
| `/steer`（插话） | 当前正在运行的任务 | 注入当前任务 | 当前任务的推理过程中 |
| `/queue`（排队） | 当前任务之后的新任务 | 无影响 | 当前任务结束、宿主调用 `claimNext` / `drainLoop` 时 |

队列中的每一条都会作为一个独立的新任务发送，而不是并入正在跑的那一轮。

### 1.2 生命周期：入队、领取、收口、代际保护

1. **入队**：`enqueue(...)`（或 `/queue` 命令、核心经 `InputBacklog#offer` 转入）把一条 `QueueItem` 以 `PENDING` 状态追加到队尾，并随会话快照落盘。
2. **领取**：`claimNext(...)` 在会话锁内抢占领取权，然后取出队首，置为 `RUNNING` 并记为 in-flight；生成一个 `QueueClaim`（同时登记为核心槽位 `TurnDispatch`），领取瞬间绑定当前代际快照，再交给 `QueueClaimHandler`。
3. **收口**：只有两条路径。
   - handler 返回 `true`（同步完成）：内核当场确认（acknowledge），从 in-flight 移除；
   - handler 返回 `false`（异步启动）：由流终态调用核心 `SessionTurn#settle(boolean)`，内部转到 `QueueClaim#settle(boolean)`：`true` 为确认，`false` 为回滚到队首。
   - handler 抛异常：视为启动失败，内核按领取时的代际快照回滚到队首，返回 `START_FAILED`。
4. **代际保护**：`cancelPending`（以及 `engine.interrupt` 触发的 `onSessionInterrupt`）会清空待执行、移除 in-flight 记录、注销本插件的派发登记并把代际加一。此后旧 claim 的回滚因代际不匹配被拒绝，任务被丢弃，不会在用户 Stop 后“复活”。

```mermaid
sequenceDiagram
    participant U as 用户/渠道
    participant H as 宿主
    participant Q as QueueExtension
    participant S as 流(Agent)

    U->>H: 任务运行中发来消息
    H->>Q: enqueue(session, text, origin)
    Q-->>H: 位置(从 1 计) / -1
    S-->>H: 当前任务终态
    H->>Q: drainLoop(session, canDrain, handler)
    Q->>Q: 领取权 + 队首出队(RUNNING, in-flight) + 代际快照
    Q->>H: handler.run(claim)
    alt 同步完成(返回 true)
        Q->>Q: acknowledge
    else 异步启动(返回 false)
        H->>S: SessionTurn.begin + 开流
        S-->>H: 流终态
        H->>Q: turn.settle(true/false)
        Note over Q: true=确认; false=回队首(代际已变则丢弃)
    end
    U->>H: Stop
    H->>Q: engine.interrupt -> onSessionInterrupt -> cancelPending(代际+1)
```

### 1.3 与核心的协作

| 核心契约 | 本插件的实现 |
|---|---|
| `spi.InputBacklog#offer` | 等价于 `enqueue(session, text, origin)`（origin 为 null 时按 `InputOrigin.NONE`），受开关控制 |
| `spi.InputBacklog#hasPending` | `pendingSize(session) > 0`，事实口径，不受开关影响；引擎 `admit` 的先进先出与 `isIdle` 依赖它 |
| `HarnessExtension#contributeSessionStatus` | 贡献 `QueueExtension.STATUS_PENDING`（待执行数） |
| `HarnessExtension#onSessionInterrupt` | 调用 `cancelPending`（清理类，不判开关） |
| `HarnessExtension#onAttach` | 注册 `/queue` 命令 |
| `spi.TurnDispatch` | `QueueClaim` 实现，领取成功即登记到核心槽位，收口即注销 |

## 二、依赖

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-harness-queue</artifactId>
    <version>${solon-ai.version}</version>
</dependency>
```

当前源码版本继承自 `solon-ai-parent`（`4.2.0-SNAPSHOT`）。传递依赖 `solon-ai-harness-core`。

## 三、使用说明

### 3.1 简单示例

```java
// 装配：无参构造；onAttach 注册 /queue 命令
QueueExtension queue = engine.addExtensionIfAbsent(new QueueExtension());

// 任务运行中，用户又补了一句：入队（来源缺省为 InputOrigin.channel("queue")）
int pos = queue.enqueue(session, "跑完顺便把文档也更新一下");
if (pos < 0) {
    // 已满 / 超长 / 文本为空 / 扩展已停用
}

// 当前任务终态时续发：canDrain 回答“现在能不能接单”
queue.drainLoop(session, s -> !engine.status(s).isOccupied(), claim -> {
    QueueItem item = claim.getItem();
    runSync(session, item.getText());   // 宿主自己的同步执行
    return true;                        // 同步完成：内核当场收口
});
```

> `canDrain` 不要用 `engine.isIdle(session)`：它把积压也算作不空闲（见 1.3），队列非空时恒为 false，队列永远不会被领取。传 `null` 表示不预判，完全由队列状态决定。

### 3.2 完整示例

#### （1）入队重载与幂等

```java
// 携带来源身份：回复路由由 origin 承载，队列只透传与持久化
InputOrigin origin = InputOrigin.of("feishu", "ou_xxx", "chat_123", "msg_456");
int p1 = queue.enqueue(session, "接着跑一遍测试", origin);

// 完整参数：HTTP 重试时用 id 去重（同 id 已在待执行或运行中则返回 -1）
int p2 = queue.enqueue(session, "生成变更说明", origin,
        "req-20261106-001",   // id，null 或空白时自动生成 "q_" + UUID
        "gpt-x",              // model
        "high",               // reasoningEffort
        null,                 // thinkingMode
        null,                 // selectedAgent
        false);               // hasFiles
```

返回值是入队后的待执行数（即该条的位置，从 1 计，不含运行中的那条）。

#### （2）claimNext 异步任务，在流终态 settle

```java
QueueClaimStatus st = queue.claimNext(session,
        s -> !engine.status(s).isOccupied(),   // 与领取权同一把会话锁内求值；false 时不消耗队列项
        claim -> {
            QueueItem item = claim.getItem();
            startStreamAsync(session, item);  // 见下方
            return false;                     // 异步启动：收口交给流终态
        });

void startStreamAsync(AgentSession session, QueueItem item) {
    // 必须在订阅前、领取回调内调用：此时会捕获本次 QueueClaim
    SessionTurn turn = SessionTurn.begin(session);
    executor.execute(() -> {
        if (turn.isCancelled()) {
            turn.settle(false);               // 调度窗口内被取消；若已被 cancelPending 作废则为空操作
            return;
        }
        boolean ok = false;
        try {
            runStream(session, item);         // 宿主自己的流执行（阻塞到终态）
            ok = true;
        } finally {
            turn.settle(ok);                  // 幂等：true=确认，false=回滚到队首
            // 终态后续发下一条
            queue.drainLoop(session, s -> !engine.status(s).isOccupied(), this::dispatch);
            // dispatch 即本例的 QueueClaimHandler：claim -> { startStreamAsync(session, claim.getItem()); return false; }
        }
    });
}
```

要点：

- 返回值必须诚实。异步却返回 `true`，流失败后无法回滚；同步却返回 `false`，会留下未收口的 in-flight，挡住后续领取。
- 同一会话同时只有一条 in-flight。它未收口前，再次 `claimNext` 返回 `NO_TASK`。
- `QueueClaim.current(session)` 可取回会话当前未收口的队列领取（槽位里是别的插件的派发时返回 null）。
- 判断“当前线程是否在派发调用栈里”用 `claim.isOnDispatchThread()`；判断“这条派发是否仍未收口”用 `claim.isCurrent()`（跨线程可用）。两者不可混用。

#### （3）运行期改队列

```java
// 运行中任务（若有）在首位，其余为待执行
for (QueueItem it : queue.list(session)) {
    Map<String, Object> view = it.toMap();    // 对外展示的唯一合法来源
}

queue.cancelItem(session, itemId);   // 取消一条待执行；运行中那条不在待执行队列里，返回 false
queue.promote(session, itemId);      // 置顶一条待执行（只有置顶，没有任意位置插入）
queue.cancelPending(session);        // 清空待执行 + 移除 in-flight 记录 + 代际加一（不停流）
```

#### （4）重启恢复：isPersisted / hasItems

```java
// 会话快照里是否有队列（静态，按 FlowContext 探测，不需要扩展实例）
if (QueueExtension.isPersisted(session.getContext())) {
    // 首次访问时自动从快照恢复；上次进程 RUNNING 的项回到队首并重置为 PENDING
    queue.drainLoop(session, s -> !engine.status(s).isOccupied(), this::dispatch);
}

boolean any = queue.hasItems(session);       // 含运行中
int pending = queue.pendingSize(session);    // 不含运行中
```

#### （5）旧数据迁移：importItems

```java
// rows 来自旧版 queue-tasks.json / queue.json 的 Map 行
int imported = queue.importItems(session, rows);
```

迁移规则：按 `id` 去重；文本为空或超过 4096 字符的行被丢弃；RUNNING 行重置为 PENDING 并放到队首（保持相对顺序），其余追加到队尾；总数达到容量上限即停止；有导入时写快照。

#### （6）/queue 命令路径

命令语法与回执见第八章「命令路径：/queue 与宿主协作」，那里给出了受理、执行、续发三段连在一起的完整宿主示例。

#### （7）停用：受控与不受控

```java
engine.setExtensionEnabled(queue, false);   // 同时停用 /queue 命令
```

停用后 `/queue` 回执为命令已停用提示（核心 `CommandDispatcher.COMMAND_DISABLED_HINT`：`已停用。发送 /help 查看可用命令。`）。

| 类别 | 方法 | 停用后 |
|---|---|---|
| 受控 | `enqueue`（三个重载）、`offer` | 返回 `-1` |
| 受控 | `claimNext`（两个重载）、`drainLoop` | 返回 `NO_TASK`，不领取 |
| 不受控 | `cancelItem` / `promote` / `cancelPending` / `importItems` / `onSessionInterrupt` | 照常执行，保证停用前已入队的项可以清理 |
| 不受控（事实口径） | `pendingSize` / `hasItems` / `list` / `hasPending` / `STATUS_PENDING` | 照常反映真实队列 |

#### （8）与 engine.interrupt 的关系

```java
if (engine.interrupt(session)) {              // 先回调 onSessionInterrupt 清队列，再停流
    queue.enqueue(session, "回到上一个方案并跑一遍测试");
}
```

- `engine.interrupt` / `engine.prepareInterrupt` 保证在停流之前回调 `onSessionInterrupt`，因此终态回调里的 `drainLoop` 不会把刚被停掉的队列再推起来。
- 无运行流时 `interrupt` 返回 false，但队列仍会被清空。
- 中断会清掉队列里所有待执行项，这是“用户停止”的刻意语义。
- `engine.cancelStream` / `engine.preemptStream` 不走扩展中断回调，不清队列。

## 四、配置与限制

本插件没有配置项，只有固定常量与只读数据结构。

### 4.1 容量与长度

| 常量 | 值 | 读取方式 | 说明 |
|---|---|---|---|
| `MAX_QUEUE_SIZE` | `20` | `QueueExtension.maxQueueSize()` | 待执行条数上限，不含运行中那条；满时 `enqueue` 返回 -1 |
| `MAX_TEXT_LENGTH` | `4096` | `QueueExtension.maxTextLength()` | 单条文本最大字符数（门面按 trim 后长度校验） |

常量定义在包内类 `QueueStore`，外部只能经上述两个静态方法读取，仅用于展示或预校验；受理判定以 `enqueue` 返回值为准。

### 4.2 QueueItem 字段

`QueueItem` 是 final 类，对外只读（状态只由插件内部修改）。

| 访问器 | `toMap()` 键 | 含义 |
|---|---|---|
| `getId()` | `id` | 任务 id；入队未指定或空白时为 `"q_" + UUID` |
| `getText()` | `text` | 任务文本（trim 后） |
| — | `displayText` | 展示文本，当前与 `text` 相同 |
| `getStatus()` | `status` | `PENDING` 或 `RUNNING` |
| `isRunning()` | — | `status` 是否为 `RUNNING` |
| `getCreatedAt()` | `createdAt` | 入队时间（毫秒） |
| `getClaimedAt()` | `claimedAt` | 领取时间（毫秒），未领取为 null，此时不输出该键 |
| `getModel()` | `model` | 执行时使用的模型，空时不输出 |
| `getReasoningEffort()` | `reasoningEffort` | 推理强度，空时不输出 |
| `getThinkingMode()` | `thinkingMode` | 思考模式，空时不输出 |
| `getSelectedAgent()` | `selectedAgent` | 选定的 agent，空时不输出 |
| `hasFiles()` | `hasFiles` | 是否带附件，仅为 true 时输出 |
| `getOrigin()` | — | 来源身份，永不为 null（缺省 `InputOrigin.NONE`） |
| `getSource()` | `source` | `origin.getChannel()`，为 null 时不输出 |

队列不解释 `model` 等字段，只透传与持久化，执行时由宿主自行使用。

### 4.3 enqueue 重载参数

| 重载 | 参数 | 说明 |
|---|---|---|
| `enqueue(session, text)` | — | 来源缺省为 `InputOrigin.channel("queue")` |
| `enqueue(session, text, origin)` | `origin` | 来源身份；null 按 `InputOrigin.NONE` |
| `enqueue(session, text, origin, id, model, reasoningEffort, thinkingMode, selectedAgent, hasFiles)` | `id` | 幂等键；非 null 且与待执行或运行中的某条相同时返回 -1 |
| | `model` / `reasoningEffort` / `thinkingMode` / `selectedAgent` | 随任务保存，原样透传 |
| | `hasFiles` | 是否带附件的标记 |

返回 -1 的情形：扩展已停用、`session` 或 `text` 为 null、trim 后为空、超过 4096 字符、待执行已满 20 条、`id` 重复。

### 4.4 QueueClaimStatus

| 值 | 含义 | 宿主处理 |
|---|---|---|
| `STARTED` | 已领取并交给 handler 启动成功 | 同步：已收口；异步：等流终态 settle |
| `NO_TASK` | 没有可领取的任务：队空、已有 in-flight、领取权被占、`canAccept` 为 false、扩展停用或参数为 null | 可以停 |
| `START_FAILED` | 已领取但启动失败（handler 抛异常，或同步完成时确认被拒），任务已回滚到队首或因代际变化被丢弃 | 勿立即重试，等下一个终态时机 |

### 4.5 会话属性键（QueueAttrs）

以下键归本插件所有，宿主不应读写，应只通过 `QueueExtension` 操作。

| 常量 | 值 | 位置 | 含义 |
|---|---|---|---|
| `QUEUE` | `session.queue` | `session.attrs()`（瞬态） | 待执行队列本体（`Deque<QueueItem>`） |
| `QUEUE_ITEMS` | `session.queue.items` | `FlowContext.data()`（持久化） | 快照投影 `List<Map>`，运行中项在首位；值不可改，否则丢失存量队列 |
| `QUEUE_IN_FLIGHT` | `session.queue.inFlight` | `session.attrs()` | 当前运行中的队列项 |
| `QUEUE_GENERATION` | `session.queue.generation` | `session.attrs()` | 清空代际，`cancelPending` 加一 |
| `QUEUE_CLAIM` | `session.queue.claim` | `session.attrs()` | 领取权占位，`claimNext` 返回即释放 |

### 4.6 状态键

| 常量 | 名称 | 类型 | 含义 |
|---|---|---|---|
| `QueueExtension.STATUS_PENDING` | `queue.pending` | `SessionStatus.Key<Integer>`（计数，缺省 0） | 待执行数（不含运行中，等于 `pendingSize`）；事实口径，不受开关影响；内核不读 |

```java
int pending = engine.status(session).get(QueueExtension.STATUS_PENDING);
```

## 五、持久化与恢复语义

- **快照即持久化**：每次队列变更（入队、领取、确认、回滚、取消、置顶、导入、有内容时的清空）都会把“in-flight + 待执行”写入 `FlowContext.data()` 的 `session.queue.items`，并调用 `session.updateSnapshot()`。没有独立的队列文件。
- **空队列移除投影**：投影行为空时删除该键；对本就为空的会话执行 `cancelPending` 不写快照。
- **惰性恢复**：`session.attrs()` 是瞬态的。首次访问队列时，若 attrs 中没有队列，就从投影重建。
- **RUNNING 回到队首**：投影中 `RUNNING` 的项只说明上一个进程领取过，重建时重置为 `PENDING`、清空 `claimedAt`，并排在其余待执行项之前。重启后它会被重新执行一次。
- **非法行丢弃**：文本为空或超过 4096 字符的行在恢复时被跳过。
- **落盘格式**：持久化键为 `id`、`text`、来源字段（`source` / `sourceUserId` / `replyTarget` / `messageId`）、`status`、`createdAt`，以及非空时的 `claimedAt`、`model`、`reasoningEffort`、`thinkingMode`、`selectedAgent`、`hasFiles`。持久化格式与展示用 `toMap()` 相互独立。
- **代际不持久化**：代际、领取权、派发登记只存在于内存。
- **会话删除**：队列存在会话中，随会话一起消失。

## 六、API 速查

`QueueExtension`（`extends AbsHarnessExtension implements InputBacklog`）：

| 方法 | 返回 | 受开关控制 | 说明 |
|---|---|---|---|
| `enqueue(session, text)` | `int` | 是 | 入队，来源 `channel("queue")` |
| `enqueue(session, text, origin)` | `int` | 是 | 入队，携带来源 |
| `enqueue(session, text, origin, id, model, reasoningEffort, thinkingMode, selectedAgent, hasFiles)` | `int` | 是 | 完整参数，`id` 幂等 |
| `offer(session, text, origin)` | `int` | 是 | `InputBacklog` 入口，同 `enqueue` |
| `claimNext(session, handler)` | `QueueClaimStatus` | 是 | 领取并执行队首一条 |
| `claimNext(session, canAccept, handler)` | `QueueClaimStatus` | 是 | 同上，带锁内原子谓词 |
| `drainLoop(session, canDrain, handler)` | `QueueClaimStatus` | 是 | 循环领取，返回最后一次领取结果 |
| `pendingSize(session)` | `int` | 否 | 待执行数 |
| `hasItems(session)` | `boolean` | 否 | 是否有任务（含运行中） |
| `hasPending(session)` | `boolean` | 否 | `InputBacklog` 能力，待执行数大于 0 |
| `list(session)` | `List<QueueItem>` | 否 | 运行中在首位，其余为待执行 |
| `cancelItem(session, itemId)` | `boolean` | 否 | 取消一条待执行 |
| `promote(session, itemId)` | `boolean` | 否 | 置顶一条待执行 |
| `cancelPending(session)` | `boolean` | 否 | 清空并递增代际；session 非 null 即返回 true |
| `importItems(session, rows)` | `int` | 否 | 旧数据迁移，返回实际导入条数 |
| `static isPersisted(FlowContext)` | `boolean` | — | 快照中是否有队列投影 |
| `static maxQueueSize()` | `int` | — | `20` |
| `static maxTextLength()` | `int` | — | `4096` |

`QueueClaim`（`implements TurnDispatch`，由插件创建）：

| 方法 | 说明 |
|---|---|
| `static current(session)` | 会话当前未收口的队列领取，无则 null |
| `getItem()` | 本次领取的任务 |
| `getOrigin()` | 等于 `item.getOrigin()`，用于回复路由 |
| `settle(boolean started)` | 幂等收口：true 确认，false 回滚到队首；返回本次调用是否完成收口 |
| `isSettled()` | 是否已收口 |
| `isOnDispatchThread()` | 当前线程是否为发起派发的线程 |
| `isCurrent()` | 是否仍是会话当前的派发事务 |

`QueueClaimHandler`（函数式接口）：`boolean run(QueueClaim claim) throws Exception`，返回 true 表示同步完成，false 表示异步启动。

## 七、注意事项

1. **入队只是登记**：装配插件、执行 `/queue` 都不会让队列自己动起来。宿主必须在任务终态调用 `drainLoop` 或 `claimNext`，否则队列永远不消费。
2. **canDrain / canAccept 要传“是否被占用”而不是 `isIdle`**：`engine.isIdle` 把积压也算作不空闲，队列非空时恒为 false。
3. **drainLoop 的返回值是最后一次领取的结果**：异步 handler 启动一条后若仍有待执行项，且 `canDrain` 仍为 true（或为 null），下一次领取会因已有 in-flight 而得到 `NO_TASK` 并返回它。不要用返回值判断“本次是否启动过任务”。drainLoop 单次调用最多循环 `MAX_QUEUE_SIZE + 1` 次作为失控保护。
4. **START_FAILED 不要立即重试**：任务已回到队首，立即重试会对同一条失败任务无限循环。
5. **回滚遇到队列已满**：回滚允许在待执行已有 20 条时再放回 1 条；超过时回滚被拒，任务保持 in-flight，派发登记也保持，需要再次收口。
6. **cancelPending 不停流**：它只清空队列、移除 in-flight 记录并递增代际，正在跑的流需要用 `engine.interrupt` 停止。之后该流终态的 `settle` 为空操作。
7. **代际由 claim 持有**：不要在终态自己读代际再判断，那样拿到的总是新代际。收口一律通过 `SessionTurn#settle` 或 handler 返回值。
8. **SessionTurn.begin 必须在领取回调内、订阅之前调用**：否则捕获不到本次 `QueueClaim`（`settle` 会退回收口会话当前的派发）。
9. **不要直接读写 `session.queue.*`**：宿主只经 `QueueExtension` 操作。`QueueStore` 为包内实现。
10. **重启后 RUNNING 会重新执行**：如果任务不可重放，宿主需要自行做幂等处理。
11. **装配建议用 `addExtensionIfAbsent`**：`addExtension` 不查重，重复添加会再次回调 `onAttach`。

## 八、命令路径：/queue 与宿主协作

### 8.1 命令语法与回执

`/queue <消息>`：把消息排在当前任务之后，内部调用与门面同一个入队实现。

| 情形 | 回执文案 |
|---|---|
| 无参数 | `用法：/queue <消息>（当前任务结束后自动作为新任务发送）` |
| 超长 | `排队内容过长（上限 4096 字符）` |
| 入队失败（满 / 停用等） | `排队失败（队列已满，上限 20 条），请稍后再试` |
| 成功 | `已加入队列（第 N 位），将按顺序在任务空闲后自动发送` |
| 扩展已停用（命令层拦截） | `/queue 已停用。发送 /help 查看可用命令。` |

命令属性与语义：

- `runnableWhenBusy() == true`、`terminalOnly() == false`：任务运行中可受理，Web / IM / 桌面都可用；
- 结局恒为 `Kind.REPLY`，命令本身不开流；
- 来源取宿主透传的 `origin`（`HarnessInput.origin(...)`），为 null 时用 `InputOrigin.NONE`，不兜底渠道名；
- 命令只负责入队，**不负责续发**；
- 命令路径没有 `id` 幂等保护，同一内容不要既调门面又拼 `/queue ...`。

### 8.2 宿主接入：完整示例

队列要真正"动起来"，宿主需要把三段串起来：**受理**（这条输入现在能不能接）→ **执行**（交给引擎）→ **续发**（受理位释放或任务终态后领取队首）。下面是一个同步执行的 IM 宿主，完整覆盖 `/queue` 命令、忙态普通消息与队列派发：

```java
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.extension.queue.QueueExtension;
import org.noear.solon.ai.harness.extension.queue.QueueItem;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.spi.TurnDispatch;
import org.noear.solon.ai.harness.turn.AdmitDecision;
import org.noear.solon.ai.harness.turn.InputGate;

public class ImQueueHost {
    private final HarnessEngine engine;
    private final QueueExtension queue;

    public ImQueueHost(HarnessEngine engine) {
        this.engine = engine;
        this.queue = engine.addExtensionIfAbsent(new QueueExtension());   // 注册 /queue
    }

    /** 唯一输入入口：用户消息、/queue 命令、队列派发的一轮都走这里 */
    public void onMessage(AgentSession session, String text, InputOrigin origin) {
        // 1) 受理：忙态普通消息 → DEFER；任务运行中的 /queue → ADMIT_BYPASS；派发轮次 → ADMIT_BYPASS
        AdmitDecision admit = engine.admit(session, text, false, engine::isBusyBypassCommand);
        switch (admit.getKind()) {
            case REJECT:
                reply(origin, admit.getRejectDetail());
                return;
            case DEFER: {
                int pos = queue.enqueue(session, text, origin);
                reply(origin, pos < 0 ? "排队失败，请稍后再试" : "已排队（第 " + pos + " 位）");
                drain(session);          // 会话可能只是"积压未清"而并不忙：立即尝试续发
                return;
            }
            default:
                break;
        }

        // 2) 派发轮次沿用派发事务的来源（回复要回到当初排队的那个渠道）
        TurnDispatch turn = admit.isDispatched() ? admit.getDispatch() : null;
        InputOrigin route = (turn != null && turn.getOrigin() != InputOrigin.NONE) ? turn.getOrigin() : origin;

        boolean acquired = admit.getTicket().isAcquired();
        try (InputGate.Ticket ticket = admit.getTicket()) {
            InputOutcome outcome = engine.handle(HarnessInput.of(session, text).origin(route));
            switch (outcome.getKind()) {
                case REPLY:      // /queue 恒为 REPLY
                    reply(route, outcome.getCommandOutput());
                    break;
                case TASK:       // 普通消息（含派发出来的那一轮）：同步跑完
                    if (outcome.hasReply()) {
                        reply(route, outcome.getCommandOutput());
                    }
                    reply(route, outcome.getRequest().call().getContent());
                    break;
                default:
                    break;
            }
        } catch (Throwable e) {
            if (turn != null) {
                throw new IllegalStateException("queued turn failed to start", e); // 交回派发侧回滚到队首
            }
            reply(route, "处理失败：" + e.getMessage());
        } finally {
            if (acquired) {
                drain(session);  // 释放受理位后续发：覆盖"空闲时 /queue 入队"与同步命令场景
            }
        }
    }

    /** 续发：领取队首，在同一线程回到输入入口 */
    private void drain(AgentSession session) {
        queue.drainLoop(session, s -> !engine.status(s).isOccupied(), claim -> {
            QueueItem item = claim.getItem();
            onMessage(session, item.getText(), item.getOrigin()); // 同线程调用：admit 能识别出"派发轮次"
            return true;                                         // 同步跑完，内核当场确认
        });
    }

    private void reply(InputOrigin origin, String text) { /* 按 origin 回投渠道 */ }
}
```

### 8.3 协作要点

1. **命令注册由插件完成**：`QueueExtension#onAttach` 已注册 `/queue`，宿主不用注册。
2. **任务运行中的 `/queue` 必须放行**：`admit` 已按 `runnableWhenBusy` 识别它（`ADMIT_BYPASS`，不占位、不改回复路由）。宿主若有自己的忙碌拦截，至少用 `engine.isBusyBypassCommand(text)` 放行，否则 `/queue` 本身会被当成普通消息再排一次队，结束后作为命令执行，变成"排队的排队"。
3. **空闲时的 `/queue` 也会入队**：此时没有终态可等，必须在受理位释放后立即 `drain`（示例的 `finally`），否则这条要等下一个任务结束才会被发出。
4. **续发在同一线程回到入口**：`admit` 用 `TurnDispatch#isOnDispatchThread()` 识别派发轮次，派发轮次不占位、免于"有积压就延后"的判定。异步开流的宿主改为在领取回调里 `SessionTurn.begin(session)`，流终态 `turn.settle(ok)`（见 3.2 第 2 小节）。
5. **派发轮次的回复路由取 `TurnDispatch#getOrigin()`**：即当初入队时的来源，而不是触发续发的那次请求。
6. **派发轮次启动失败要抛出**：抛给 `drainLoop` 的回调后，内核按领取时的代际回滚到队首并返回 `START_FAILED`，不会无限重试。
7. **排队的可以是命令**：忙态下用户发 `/goal ...`、`/loop ...` 这类非旁路命令时，`admit` 给出 `DEFER`，它们以原文入队，续发时经同一入口按命令执行。
8. **审批挂起**：`admit` 第 4 个参数传 `engine::isBusyBypassCommand`，让 `/queue` 在审批挂起期间仍可受理；不传则被 `REJECT("hitl_pending")`。
9. **Web 前端已自行排队时不要再走命令**：同一条输入只允许一个入口入队。HTTP 重试幂等用 `enqueue` 的 `id` 参数。
10. **停止**：`engine.interrupt(session)` 会先清空队列再停流，终态回调里的 `drain` 不会把刚停掉的内容重新推起来。
