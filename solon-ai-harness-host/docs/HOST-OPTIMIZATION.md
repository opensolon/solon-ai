# solon-ai-harness-host 优化方案（v2，已拍板）

> 目标：让 HarnessHost 能开发**任意** agent / agent SaaS，且三档用法都简单；soloncode-cli 的 Web 端
> （一个工作区 = 一个租户）是验证样本之一，而不是接口的模子。
>
> 状态：4.2.0 未发布、`@Preview`，直接改 API，不留桥接。`HarnessEnginePool` 留在 core。
> 进度：M0–M7、第九节 H1–H5 / S1–S4、9.3 命名收口全部落地（host 69 例全绿；soloncode-cli 1225 例，失败只有 3 个既有 settings 用例）。

## 一、已拍板的决定

| 事项 | 决定 |
|---|---|
| principal 校验 | SERVER 模式下 `submit` 不传 principal 只告警一次（不拒绝）；传了就与 `getSessionPrincipal` 比对，不一致 → `rejected(SCOPE_UNAVAILABLE)` |
| 插件依赖 | host 维持对 goal/queue/steer/loop 的硬依赖；README 写明「最小依赖请直接用 L1 的 `engine.handle`」 |
| 引擎池位置 | 留在 core（`org.noear.solon.ai.harness.HarnessEnginePool`），文档与守卫随之对齐 |
| 忙时语义 | `SubmitSpec.rejectWhenBusy(true)`：一问一答型宿主忙时直接拒绝，不排队 |

## 二、防锁定规则

1. 每个公开扩展点都必须被参考宿主 A（`SseChatSample` 无状态 HTTP）或 B（`ImBotSample` 文本 IM）用到；
   只被 C（`PushWebSample` 多会话推送 + 每轮状态，即 soloncode Web 的形态）用到的，删除或降为宿主自己的代码。
   守卫 `HostGenericityGuardTest` 同时要求新增的公开回调 / 配置必须登记，否则测试失败。
   执行中已据此删掉 `RunSink.started()`（与 `HostSink.open` 同一时机，只有 C 用得上）。
2. host 主代码不得出现宿主专有词（soloncode / workspace / WebEventMapper / SAEP）。
3. `HostSink`、`RunSink` 没有抽象方法：`new HostSink(){}` 必须能编译、能跑通。
4. Builder 公开配置方法 ≤ 6（engines / sink / executor / wrap / input / programRuns），`HostSink` 回调 ≤ 11，`RunSink` 回调 ≤ 4。

## 三、三档用法

```java
// 第一档：一个请求一轮，不写 sink
HarnessHost host = HarnessHost.builder().engines(pool::get).build();
host.openSession(tenant, sid, userId);
RunOutcome o = host.submit(tenant, sid, SubmitSpec.of("hello").principal(userId))
                   .getRun().await(Duration.ofMinutes(5));
Flux<HostSignal> sse = host.stream(tenant, sid, SubmitSpec.of(text).principal(userId));

// 第二档：推送型（WebSocket / IM），覆盖 2~3 个方法
new HostSink() {
    public void reply(HostInput in, String text) { push(in.getSessionId(), text); }
    public void event(HostRun run, AgentEvent e) { push(run.getSessionId(), e); }
};

// 第三档：复杂宿主，每轮一个 RunSink（按轮状态放在实现对象里）
new HostSink() {
    public RunSink open(HostRun run) { return new MyRunSink(run); }
};
```

## 四、接口

```java
public interface HostSink {                         // 全部缺省实现
    void admitted(HostInput in);                      // 受理后、分派前（TASK/REPLY/HITL/IGNORED）
    void reply(HostInput in, String text);            // 无任务的回执（text 非空才调）
    void deferred(HostInput in, int position);
    void steered(HostInput in);                       // steerWhenBusy 插话成功
    void rejected(HostInput in, RejectReason r, @Nullable String detail);
    void notOwner(HostInput in, @Nullable String owner);   // 缺省链到 rejected
    void hitl(HostInput in, List<HitlCard> cards);
    void hitlExpired(HostInput in);
    RunSink open(HostRun run);                      // 缺省：转发到下面两个
    void event(HostRun run, AgentEvent e);
    void runFinished(HostRun run, RunOutcome o);
}

public interface RunSink {                          // 每轮一个；全部缺省实现（本轮开始 = HostSink.open）
    void prepare(RunPrep p);                        // 执行线程上、stream() 之前：改请求
    void reply(String text);                        // 带任务的命令回执
    void event(AgentEvent e);
    void finished(RunOutcome o);                    // 恰好一次
}
```

| 类型 | 内容 |
|---|---|
| `HostInput` | tenant、sessionId、origin、session(可空)、text、kind、commandName、command、besideRunning、hasReply、queueItem(可空)、selectedModel、selectedAgent、attrs（只读） |
| `HostRun` | tenant、sessionId、origin、agentName、modelName、dispatched、programChannel、text、queueItem、startedAt、session |
| `RunPrep` | 只读视图：request()、prompt()、session()、modelName()、agentName()、run() |
| `RunOutcome` | end（COMPLETED/FAILED/CANCELLED）、error、resultText、usage（`RunUsage`：tokens、toolCalls、elapsedMs）、streamOpened、hitlCards |
| `RunHandle` | future()、await(Duration)、cancel() |
| `SubmitSpec` | text、origin、principal、steerWhenBusy、rejectWhenBusy、customize(Consumer&lt;HarnessInput&gt;)、observe(RunSink) |
| `HostSignal` | `host.stream` 元素：REPLY / EVENT / DEFERRED / REJECTED / HITL / STEERED / FINISHED |

Builder：`engines`（必需）、`sink`、`executor`、`wrap(BiFunction<String,Runnable,Runnable>)`、
`input(BiConsumer<String,HarnessInput>)`、`programRuns(@Nullable Predicate<String> sessionFilter)` / `programRuns(false)`。

## 五、框架保证的语义

- 每次 `open` 恰好配一次 `finished`：调度窗口内取消、开流失败、流出错、中断都覆盖。
- `observe(RunSink)` 与 `RunHandle` 在投递执行器之前挂好，不丢开头事件；输入进了积压（DEFERRED）则没有 RunHandle，
  `host.stream` 发出 DEFERRED 后结束（续发的那一轮只走全局 sink）。
- 中断 INTERRUPT_ALL 内部用两段式：`checkOwner → prepareInterrupt → 有活跃流时 writeCancelEarly → 标记取消 → commit`；
  结束统一为 `finished(CANCELLED)`，`RunUsage` 在 commit 前取快照。
- 后台轮次（goal/loop）与用户轮次走同一条链 `open → prepare → event → finished`（推流），也领 `RunTicket`。
- `wrap` 覆盖开流动作、续发、后台轮次；`input` 覆盖 submit / decide / 续发 / 后台轮次四个构造点。
- 首次绑定引擎：登记执行器 → 恢复 goal。queue 插件没有「有积压的会话」索引，重启后的积压在该会话下次提交 / 管理操作时续发；
  需要立即续发的宿主在 `warmup` 之后自行触发（soloncode 现有 `WebQueueDispatcher` 的枚举逻辑即此）。
- 调度窗口内或 `prepare` 期间被取消：不开流、`finished(CANCELLED)`，非派发轮次补一次续发（`CANCEL_STREAM` 保留的排队不会卡住）。
- 行动证据：「内部工具（goal/task/multitask/memory）不算行动证据、失败调用不算」是框架规则（修 `GoalOutcome.done(ReActResponse)`）。

## 六、里程碑

| 里程碑 | 内容 | 验证 |
|---|---|---|
| M0 | 池留 core：修 core `HarnessPackageBoundaryTest`、`HarnessHost` 注释、README/CONCEPTS/QUICKSTART/UPDATE_LOG、失效链接 | core/host 测试绿；全仓无 `harness.host.HarnessEnginePool` |
| M1 | HostInput、admitted、input、principal 可空与校验、steerWhenBusy、rejectWhenBusy | 回调带正确 tenant/sessionId；越权提交被拒；STEERED/回落 DEFERRED |
| M2 | RunSink、RunPrep、RunOutcome/RunUsage、两段式中断内置、wrap 带租户覆盖续发 | open/finished 恰好一次；中断 → CANCELLED；prepare 生效；ThreadLocal 可见 |
| M3 | RunHandle、observe、host.stream；Quickstart 去轮询 | 无 sleep 轮询；信号序 EVENT* → FINISHED |
| M4 | 后台轮次经 RunSink 推流；programRuns(Predicate)；GoalOutcome 证据口径；首绑续发积压 | 过滤外会话不接管；只调内部工具 hasToolCalls=false |
| M5 | 三个参考宿主 + 防锁定守卫 | 全绿 |
| M6 | soloncode Web 迁移（WebHostSink + WebRunSink），删 WebSessionRunner/WebQueueDispatcher/WebGoalRecovery 编排 | soloncode 回归绿；api/web 无 RunTicket.begin/drainLoop/afterRun |
| M7 | README 按三档重写；删除「soloncode 请用 L1」 | 首个示例 ≤ 10 行且不实现接口 |

## 七、风险

1. soloncode 恢复顺序：先 `host.warmup(ws)` 登记执行器，再做自己的按目录恢复与旧队列迁移。
2. soloncode 停止时的 trace 指标由 `RunUsage` 快照提供，不加回 `cancelling` 回调。
3. `started` 在投递前同步调用：窗口内被取消也会先 started 再 finished(CANCELLED)，前端需能正确收尾。
4. `QueueItem.toInput` 是否恢复模型 / attrs、命令与在跑流的竞态，在 M6 前核实。

## 八、M6 执行记录（soloncode Web 迁移）

**框架只补了一处**：`HostTurn` 暴露本次输入的 `selectedModel` / `selectedAgent` / `attrs`。
没有它，宿主在 `admitted` 里拿不到「用户这次选了什么」，只能把模型 / 推理强度 / cwd 先写进会话 attrs，
这又违背「被排队或拒绝的输入不得改写正在运行会话的选择」。参考宿主 B（IM）用它取回复目标，过了防锁定守卫。

**没有加回的东西**：`cancelling` 回调。soloncode 停止时要在写取消消息之前占住 IM「取消」终态
（防止停流窗口内迟到的成功结果发到 IM），这由 `WebGate.interruptSession` 先调本轮 `WebRunSink.reserveCancel()`、
再调 `host.interrupt` 完成；回执在 `finished(CANCELLED)` 里投递（此时取消消息已持久化），trace 指标取 `RunUsage`。

**soloncode 侧映射**：

| 旧（自己编排） | 新 |
|---|---|
| `WebSessionRunner`（异步 / 同步两条开流路径） | 骨架 `startRun` / `runProgram`；`WebRunSink`：open 复位流门、prepare 装 SAEP 管道（`WebStreamBuilder.openPipeline`）、finished 发失败 / 取消回执与 done |
| `WebChatInputHandler`（结局分派、命令回执信封、IM 忙时插话降级排队、interrupt 包序） | `WebHostSink`（admitted / reply / deferred / steered / rejected / hitlExpired）；IM 入口 `steerWhenBusy(true)`；`WebGate.submit` 只组装输入 |
| `WebQueueDispatcher`（drainLoop、续发、重启恢复） | 骨架自动续发；队列管理接口与重启恢复走 `SessionAdmin.call`（成功后自动续发） |
| `WebGoalRecovery` + `WorkspaceManager` 的 web goal / loop 执行器 | `host.warmup` 先登记执行器（`programRuns(SessionIdOwnership::isWeb)`），再 `WebSessionRecovery` 按会话目录恢复 |
| 端口 `runLoop` / `runGoalRound` / `drainSessionQueue` | 删除；新增 `sessionAdmin(ctx)` |

**行为差异（有意）**：调度窗口内被停止的一轮会先收到 reset 再收到 done（旧版只有 done），前端能正确收尾；
窗口内停止的 IM 输入现在会收到「用户已取消任务」。桌面端（`DesktopInputRouter`）仍走 L1，不在本次范围。

## 九、第二轮优化：去掉宿主侧的时序负担

迁移后 soloncode Web 端多出的复杂度有四个根因：同一轮的回调分在两个线程上；停止后迟到事件仍会交给宿主；queue 没有积压索引；工作区进缓存之前就通知运行时。本轮只调整语义、补一个索引，不新增回调。

| 编号 | 内容 |
|---|---|
| H1 | 同一轮的 RunSink 回调全部在执行线程上串行：`reply → 窗口检查 → prepare → event* → finished`；`open` 只建对象、不输出 |
| H2 | 取消屏障：中断受理后本轮不再收到 `event`，终态一定是 CANCELLED；`interrupt` 返回 true = 本节点有一轮会以 `finished(CANCELLED)` 收尾（含调度窗口内）；`RunOutcome.getCancelCause()` |
| H3 | HostRun 瘦身：输入相关字段统一走 `getTurn()`，`isDispatched` 只保留 HostTurn 一份；新增 `getId()` |
| H4 | 每轮一个递增 id，DEBUG 日志记录生命周期迁移 |
| H5 | queue 积压索引（状态源 `queue/pending/` 下每会话一个标记），HarnessHost 首次绑定引擎时恢复 goal 之后续发索引里的会话（受 `programRuns` 的会话过滤约束） |
| S1 | soloncode：工作区放进缓存之后再通知运行时，删除 `WebGate.tenants` |
| S2 | soloncode：WebRunSink 首次输出时复位流门；按 `getCancelCause` 发取消回执；删除 `reserveCancel` 与 `WebGate.runs` |
| S3 | soloncode：WebSessionRecovery 只做旧数据迁移与索引补写，不再有「先 warmup 再恢复」的顺序要求 |
| S4 | soloncode：删除入口处与引擎重复的 HITL 预检 |

### 9.1 执行记录

- 框架：host 68 例（新增 `HarnessHostLifecycleTest` 7 例：窗口内停止返回 true 且以 CANCELLED 收尾、无轮次返回 false、`HANDLE` 原因、open 不输出且回调不并发、停止后无事件、首次绑定按索引续发、续发受会话过滤约束）、queue 99 例全绿；防锁定守卫的回调 / Builder 数量不变。
- 与方案的偏差：H4 的 `RecordingSink`（test-jar）未做，DEBUG 生命周期日志已做；`RunSink` 的串行保证写为「不并发、有先行关系」，不承诺同一线程（事件按流的信号线程到达）；顺带修了忙时插话会插进待审批会话的问题（`trySteer` 排除 `hasPendingHitl`）。
- soloncode：S1–S4 落地；`ImageReplyLifecycleTest` 的两条停止窗口用例改为新契约（持久化阻塞期间 IM 无任何投递、回执在持久化之后且只发一次、迟到成功被去重门挡下、下一轮不受影响）；`ImBusySteerContractTest` 的 3 条源码契约改为「入口不再重复引擎的 HITL 判定」。全量 1223 例，失败仍是 3 个 settings 老用例；`WebRunHttpIntegrationTest` 全量时偶发端口启动失败，单独运行 3 例通过。
- 行为变化：窗口内被停、没有回执的一轮只收到 done（恢复迁移前行为），IM 不再收到「已取消」；`WebSessionRecovery` 每个工作区只扫一次会话目录（标记 `migrations/web-session-index-v1` 写在状态来源），之后重启恢复全靠框架索引。

### 9.2 第二轮复审（2026-11-08）

- 修复：`HarnessHost.stream` 不再临时改写调用方的 `SubmitSpec`（原先 `spec.observe(...)` 后再还原，同一个 spec 被并发复用时会串监听），改为内部把本次监听直接传给受理流程。
- 修复（soloncode）：忙时可执行命令触发的任务撞上在跑的流时，框架给出 `rejected(BUSY)`；`WebHostSink` 恢复迁移前的呈现——只发一条命令提示「当前有任务正在执行，本次触发已忽略」，不弹错误、不发终态（终态归在跑的那条流），IM 来源补一个拒收信号。
- 待定（已在 9.3 处理）：`HostTurn` 改名；`WebStreamBuilder.buildStreamFlux` 只有测试在用；`SubmitSpec.rejectWhenBusy()` 与 `steerWhenBusy(boolean)` 形状不一致。
- 验证：host 68 例全绿；soloncode-cli 全量 1225 例，失败 3 例（settings 既有），跳过 3 例。

### 9.3 命名收口：HostTurn → HostInput（2026-11-08）

- 术语：一次输入 = `HostInput`，一次开流 = `HostRun`，一次输入开出 0 或 1 轮运行。不再用 Turn 命名宿主层类型——Turn 与 Run 中文都是「一轮」，且 steer 的 `RUN_CHANGED`、ReAct 的 `maxTurns`、soloncode 的 `beginStreamTurn` 里 turn 都指运行或推理步。
- 改名：`HostTurn` → `HostInput`，`HostRun.getTurn()` → `getInput()`，`getInputText()` → `getText()`（类名已表明是输入，不再重复）；`HostSink` 回调参数名 `turn` → `input`；`HarnessHost` 内部的输入装饰器字段改名 `inputDecorator`（Builder 方法仍叫 `input`）。`HostInput` 类注释写明与 `HarnessInput`（交给引擎、可改）和 `HostRun` 的分工。
- 顺带：`SubmitSpec.rejectWhenBusy()` → `rejectWhenBusy(boolean)`，与 `steerWhenBusy(boolean)` 同形；两者可同时开（先插话，插不进再拒绝）。
- 守卫：`HostGenericityGuardTest.hostTurnStaysRetired` 锁定主代码不得再出现 `HostTurn` / `getTurn(`。
- soloncode：`WebHostSink` / `WebRunSink` / `WebInputAttrs` 跟进；删除只有测试在用的 `WebStreamBuilder.buildStreamFlux`，`ImageReplyLifecycleTest.buildStream()` 改为在测试里直接组装 `openPipeline` + 订阅（同步装配失败时 replyError + cleanup，与生产上 `WebRunSink.finished(FAILED)` 一致）。
- 验证：host 69 例全绿（新增守卫 1 例）并已 install；soloncode-cli 全量 1225 例，失败 3 例（settings 既有），跳过 3 例。
