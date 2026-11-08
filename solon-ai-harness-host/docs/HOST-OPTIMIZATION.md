# solon-ai-harness-host 优化方案（v2，已拍板）

> **这是决策记录，不是使用文档。** 按时间追加，后面的章节会推翻前面的写法（例如第十二节删掉了 `SubmitSpec`，
> 第十四节把 `SubmitResult.Kind` 改成了 `Disposition`）；第三、四节的示例与类型表已同步到当前 API，其余章节保留当时的原文。
> 当前 API 以 [host README](../README.md) 与 [QUICKSTART-SAAS.md](QUICKSTART-SAAS.md) 为准。

> 目标：让 HarnessHost 能开发**任意** agent / agent SaaS，且三档用法都简单；soloncode-cli 的 Web 端
> （一个工作区 = 一个租户）是验证样本之一，而不是接口的模子。
>
> 状态：4.2.0 未发布、`@Preview`，直接改 API，不留桥接。`HarnessEnginePool` 留在 core。
> 进度：M0–M7、第九节 H1–H5 / S1–S4、9.3 命名收口、第十节（桌面端与无头模式迁移）全部落地（host 70 例全绿；soloncode-cli 1226 例，失败只有 3 个既有 settings 用例）。

## 一、已拍板的决定

| 事项 | 决定 |
|---|---|
| principal 校验 | SERVER 模式下 `submit` 不传 principal 只告警一次（不拒绝）；传了就与 `getSessionPrincipal` 比对，不一致 → `rejected(SCOPE_UNAVAILABLE)` |
| 插件依赖 | host 维持对 goal/queue/steer/loop 的硬依赖；README 写明「最小依赖请直接用 L1 的 `engine.handle`」 |
| 引擎池位置 | 留在 core（`org.noear.solon.ai.harness.HarnessEnginePool`），文档与守卫随之对齐 |
| 忙时语义 | 一问一答型宿主忙时直接拒绝、不排队（当时写作 `SubmitSpec.rejectWhenBusy(true)`；现为 `HarnessInput.whenBusy(BusyPolicy.REJECT)`，见第十二节） |

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
RunOutcome o = host.submit(tenant, sid, HarnessInput.of("hello").principal(userId))
                   .getRun().await(Duration.ofMinutes(5));
Flux<HostSignal> sse = host.stream(tenant, sid, HarnessInput.of(text).principal(userId));

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
    void admitted(HostInput in);                      // 受理后、分派前（STARTED/REPLY/HITL/IGNORED）
    void reply(HostInput in, String text);            // 无任务的回执（text 非空才调）
    void deferred(HostInput in, int position);
    void steered(HostInput in);                       // whenBusy(STEER) 插话成功
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
| `HostInput` | tenant、sessionId、origin、session(可空)、text、disposition（`Disposition`）、commandName、command、besideRunning、hasReply、queueItem(可空)、selectedModel、selectedAgent、attrs（只读） |
| `HostRun` | tenant、sessionId、origin、agentName、modelName、dispatched、programChannel、text、queueItem、startedAt、session |
| `RunPrep` | 只读视图：request()、prompt()、session()、modelName()、agentName()、run() |
| `RunOutcome` | end（COMPLETED/FAILED/CANCELLED）、error、resultText、usage（`RunUsage`：tokens、toolCalls、elapsedMs）、streamOpened、hitlCards |
| `RunHandle` | future()、await(Duration)、cancel() |
| `HarnessInput`（core） | 提交只有这一个输入类型：text、origin、principal、whenBusy(`BusyPolicy`：QUEUE/STEER/REJECT) 等；本轮监听改为 `submit/stream(t, sid, input, observer)` 的参数（原 `SubmitSpec` 已在第十二节删除） |
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

迁移后 soloncode Web 端多出的复杂度有四个根因：同一轮的回调分在两个线程上；停止后迟到事件仍会交给宿主；queue 没有积压索引（已由下表 H5 补上，即 `QueueIndex`）；工作区进缓存之前就通知运行时。本轮只调整语义、补一个索引，不新增回调。

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

## 十、第三轮：让所有入口都只剩「协议适配」（2026-11-08）

**问题**：soloncode 有 5 套编排（Web+IM 已用 HarnessHost；桌面、无头、命令行、`/web/run` 各写一套），
同一条规则写在多处已经走样（桌面端 goal 的行动证据只排除了 goal 工具，框架口径排除 goal/task/multitask/memory）。

**原则**：不要过度设计——只做有真实使用方、且能在本仓验证的改动；每项改动先读代码核实前提。

### 10.1 决定（执行前按代码核实后修订）

| 编号 | 原建议 | 核实后的决定 |
|---|---|---|
| F1 | 忙时策略改枚举，含 `REPLACE` | 做，但只要 `QUEUE` / `STEER` / `REJECT` 三个值：`REPLACE` 没有使用方——桌面端开流前的 `CANCEL_STREAM` 只是防御（会话忙时引擎早已排队，后台轮次忙时直接跳过）；「先插话再拒绝」的组合也没有使用方。`ROLLBACK` 不做 |
| F2 | 停止只留 `stop` + `RunHandle.cancel` | 做，但方法名保留 `interrupt`（此前已拍板：stop 像停服务，且与 `prepareInterrupt` 配套）：`host.interrupt(tenant, sid)` = 用户停止；三种 `InterruptMode` 只留在 `SessionAdmin.interrupt` |
| F3 | 多 sink | 不做：唯一的使用方是 S2，而 S2 拆分后 Web 与 IM 仍要互相知道对方的规则（IM 撞上待审批时 Web 不报错、忙时命令提示），反而更绕。需要计费 / 审计的宿主自己组合一个 HostSink 即可 |
| F4 | 按租户绑定 sink | 不做：sink 的生命周期要跟引擎实例走，缓存「租户 → sink」要处理引擎回收与强引用（值引用引擎会让 WeakHashMap 失效），比宿主侧 7 处查找更复杂；soloncode 侧收成一个小工具方法 |
| F5 | AG-UI 适配模块 | 暂缓：当前没有使用方，本环境也无法用真实 AG-UI 客户端验证；审批（interrupt）在 AG-UI 1.0 里还没有定型。等有使用方时单独立项（可在宿主层基于 `host.stream` 实现，框架不需要改） |
| F6 | RecordingSink 测试工具 | 不做：需要额外发布 test-jar；现有契约测试各自的记录器足够 |
| F7 | 文档路线图 | 做：README 写清「先认识哪几个类型」 |
| 新增 | — | `HostInput.isResume()`：桌面端审批续流要续用原来的流序号（不复位），这是任何「挂起后续跑」的界面都需要的事实 |
| 新增 | — | 修 `programRuns(false)` 的语义漏洞：不接管后台工作的骨架首次绑定引擎时也会恢复 goal、续发**全部**会话的积压（无头模式与 Web 共用引擎时，会在 `soloncode run` 进程里跑掉 Web 会话的排队）。改为不接管就不恢复 |
| S1 | 所有入口改用 HarnessHost | 桌面端、无头模式做；命令行与 `/web/run` 不做（命令行续发会与 readLine 抢终端，需另行验证；`/web/run` 保留子进程隔离） |
| S2 | Web / IM 呈现拆两个类 | 不做（见 F3） |
| S3 | IM 回复路由跟着本轮走 | 不做：`ImageReplyTurn` 也读这个属性，改动面超出本轮 |
| S4 | reset/done 集中到一个类 | 不做：规则已在 WebHostSink / WebRunSink 两处，各自有注释与测试；再抽一层只是搬家 |
| S5 | 清理 | 做：删已废弃的兼容入口，`recoverSessionQueues` 改名 `onWorkspaceReady` |

### 10.2 执行记录

- 框架（host）：`SubmitSpec.whenBusy(BusyPolicy)`（`QUEUE` / `STEER` / `REJECT`）取代两个布尔；`HarnessHost.interrupt(tenant, sid)` 即用户停止，
  带 `InterruptMode` 的版本收为包内，对外只经 `SessionAdmin.interrupt`；新增 `HostInput.isResume()`；`programRuns(false)` 首次绑定引擎时不再恢复 goal、不再续发积压。
  守卫 `EXTENSION_POINTS` 的两项合并为 `SubmitSpec#whenBusy`，数量上限不变。新增用例：`hostWithoutProgramRunsDoesNotResumeBacklog`、过期审批卡的 `isResume`。
- soloncode 桌面端：删除 `DesktopStreamTurn`（订阅、`RunTicket`、顶流）与 `DesktopInputRouter` 的 `dispatchTurn` / `drainBacklog` / `afterRun`、`WsGate.runGoalRoundAndCapture`（闭锁、自算证据与 token、`PREEMPT_STREAM`）。
  新增 `DesktopHostSink`（会话级回执，发给该会话最近一次发起请求的连接，与迁移前一致）与 `DesktopRunSink`（每轮：流开 / 续用、请求选项、事件转换、hitl / done / error 终态；goal 轮次只推过程）。
  `WsGate` 建 `HarnessHost`（租户 `desktop`，`programRuns(SessionIdOwnership::isDesktop)`），构造时 `warmup`。goal 行动证据改为框架口径（修复只排除 goal 工具的偏差）。
  桌面会话的 loop 轮次经骨架执行但不推流（桌面端没有 loop 界面，与迁移前插件缺省执行器一致）。
- soloncode 无头模式：`PrintMode.runAgent` 改为 `host.submit(...whenBusy(REJECT).observe(...))` + `RunHandle.future().get()`；删除闭锁、`turnStateLock`、`AtomicReference` 句柄；
  「中断帧先于句柄发布」的竞态改为两个 volatile 互相可见（先写自己、再读对方，重复停止幂等）。`programRuns(false)`：不在 `soloncode run` 进程里恢复别的入口的积压。
- soloncode Web：`whenBusy(STEER/QUEUE)`、`host.interrupt(tenant, sid)`；删除 `WebGate` 的废弃兼容入口（`ATTR_INPUT_ADMITTING` / `hasActiveStream` / `isInputAdmitting`，测试改测 `SessionActivity`）；
  `recoverSessionQueues` 改名 `onWorkspaceReady`。
- 测试：删除 `DesktopStreamTurnCancelTest`（测的是已删除的顶流防御，框架的停止屏障与单流受理已覆盖）；新增 `DesktopHostFlowContractTest` 4 例（真实引擎 + 骨架：终态恰好一次且带序号、无运行时停止只发 reason + done、过期审批卡回执、受理后才写选择）。
  `InjectedRoundChannelContractTest` 的桌面条款改为 `.programRuns(SessionIdOwnership::isDesktop)`。
- 发现的既有问题（未改）：`/prompts/desktop-collaboration.md` 资源从未提交（`DesktopCollaborationPrompt` 自 82e3a313 引入起即缺），桌面端每轮都会在选项装配时抛
  `ExceptionInInitializerError`。迁移前后表现一致（迁移后以一个 error 终态收尾），需要补上提示词文件。
- 验证：host 70 例全绿并已 install；soloncode-cli 全量 1226 例，失败 3 例（settings 既有），跳过 3 例。


## 十一、第四轮：只修语义缺口，复杂度回到呈现层（2026-11-08）

### 11.1 核实（P0）结论

| 项 | 结论 | 依据 |
|---|---|---|
| F2 受理窗口的停止 | 成立，但**不能**把 `RunTicket.begin` 提到 `engine.handle` 之前 | handle 内执行的命令可能自己推进取消代数，提前取基准会把本轮误判为已取消。改为骨架内登记「受理中」占位（见 11.2） |
| F3 残留决策 | 框架口径（`HitlCard.undecided`）正确，宿主的 `HitlCard.next` 兜底多余 | 「整批已决策但没续流」时 `pendingCount=0`，引擎不判 HITL_PENDING；下一轮 `HITLInterceptor.onAgentStart` 见全部已决策会直接路由到 ACTION 消化掉。残留只出现在被中断的续流之后，那一轮是 CANCELLED，本就不推卡 |
| 会话忙口径 | Web 的删除 / 批量清理 / 回退应当用「占用」（含待审批），插话与「运行中」展示用「有运行流」 | `SessionWebController` 三处删除 / 回退原用 `isBusy`，会放过正在等审批的会话（回退会写坏挂起的 trace）；桌面端与 `HarnessHost.closeSession` 早已用 `isOccupied` |
| `emitToClient` 读盘 | 成立：每个事件（含流式分片）都 `SessionMeta.load` 一次，哪怕没开对话隔离 | 只在隔离开启时读 |
| F1 生效模型名 | 不做 | 桌面端在 `prepare` 里已拿到实际模型（`DesktopRunSink.modelName`），Web 不用；没有第二个使用方 |
| F1 异常结束 | 做 | 只用 `submit().getRun().await()` 的 SaaS（第一档用法）拿不到「超出 maxTurns」这一事实，只能自己解析事件 |
| F4 受理时写入选择 | 不进框架 | Web 与桌面的语义不同：Web 的子代理是「没带就保持」，桌面是「每条消息显式、没带就是主代理」；桌面的 goal 路径不经受理、仍要自己写。放进框架既要开关又删不掉宿主代码。改为桌面两份合一 |
| F5 RecordingSink | 不做 | 上次说的两个使用方（`WebHostFlowContractTest` / `DesktopHostFlowContractTest`）并不自写记录器，它们观测的是协议输出 |
| F6 拆分 | 只拆 `ProgramResult` | 后台轮次的执行仍要用 `openRun` / `runStream` / `ActiveRun` 等私有状态，整段搬走只会多一层包内耦合；把「终态 → 插件结局」的翻译拆成包内类即可 |
| S1 IM 投递按轮状态 | 暂缓 | 现在是正确的（第九节的屏障已消除当初的竞态），收益只是可读性；而这条链还服务于不开流的命令回执、忙时命令的独立路由、受理时（开流之前）就启动的心跳，5 个测试类约 2800 行锁定了它的时序（含「落库卡住期间 IM 收不到」）。本环境无真实 IM 渠道，回归无法实测，等有渠道联调时单独做 |
| S7 命令行 | 只统一证据口径 | `ReActRequest.stream()` 在装配时已自取取消代数，命令行不缺 `RunTicket` |

### 11.2 执行记录

- 框架（host）：
  - **受理中也能停**：`admit` 一开始就登记「受理中」占位（队列续发的领取回调同样登记），`interrupt` 的用户停止与 `CANCEL_STREAM` 会给它记上停止请求；`openRun` 先登记活跃轮次、再读占位上的请求（与 `interrupt`「先记占位、再读活跃」相对，至少一边看得到对方），开出的那一轮在执行线程上以 CANCELLED 收尾。腾位（为新输入让路）与句柄取消（只管自己那一轮）不记占位。`interrupt` 返回 true 的含义扩到「有输入正在受理」。
  - **`stream()` 先登记下游取消**：受理期间客户端就断开时，拿到句柄后补做取消。
  - **`RunOutcome.isAbnormal()`**：ReAct 异常结束（如超出 maxTurns），流本身正常结束、结局仍是 COMPLETED。
  - `ProgramResult` 拆为包内类（`toGoalOutcome` / `toLoopOutcome`）。
  - 新增用例：`interruptDuringAdmissionCancelsTheRunItOpens`、`streamCancelledDuringAdmissionStopsTheRun`。
- soloncode 桌面端：`DesktopRunSink` 的终态只在 `finished` 里、只看 `RunOutcome`（审批卡取 `getHitlCards()` 第一张，done 带 `getUsage()` 的 token 与耗时）；删除 `onRunEnd`、`start_time`、`terminalSent`。停止与 Web 同一写法：用户停止的那一轮由 `finished(CANCELLED, INTERRUPT_ALL)` 发 reason + done，入口只在 `interrupt` 返回 false 时兜底（`DesktopRunSink.stopped` 共用）。会话选择写入合为 `DesktopHostSink.applySelection` 一处（受理后与 goal 创建前共用）。
- soloncode 无头模式：删除 `currentRun`、`turnRunning`、`PrintResult.trace`；只剩 `turnSessionId` 与 `turnInterrupted`，停止直接 `host.interrupt(会话)`；`maxTurnsExceeded` 取 `RunOutcome.isAbnormal()`。
- soloncode 其它：`WorkspaceRuntimePort` 新增 `isSessionOccupied`，删除 / 批量清理 / 回退改用它；`emitToClient` 只在对话隔离开启时读会话主人；`/rewind` 回执条数的解析合为 `SessionRewindService.rewindReceiptCount`；命令行「这一轮是否做了事」改用 `GoalOutcome.isEvidenceTool`。
- 验证：host 72 例全绿并已 install；soloncode-cli 全量 1226 例，失败 3 例（settings 既有），跳过 3 例。

## 十二、提交只有一个输入类型：SubmitSpec 并入 HarnessInput（2026-11-08）

### 12.1 决定

`SubmitSpec` 存在的唯一技术原因是 `HarnessInput` 构造时就要带会话（`session` 是 final），而骨架要先查归属、再加载会话，只能用 spec 带文本、再用 `customize` 回调补参数。它另外装的东西都不该在 host：`text` / `origin` 与 `HarnessInput` 重复；`whenBusy` 与 `principal` 属于受理，core 本来就负责；`observe` 是对这一轮的监听，不是输入的一部分。两套并存已经造成两处缺陷：

1. 来源两份：骨架用 `spec.getOrigin()` 做回调与回复路由，`customize(in -> in.origin(x))` 改的是交给引擎的那一份；
2. `REJECT` 有竞态：骨架先 `isBusyFor` 判空闲、再 `engine.handle`，两步之间没有锁；其间别的输入抢先开流时，这条会被 core 放进积压，一问一答请求收到「已排队」而没人接。

改法：

| 层 | 改动 |
|---|---|
| core | `HarnessInput.of(text)`（不带会话）、`whenBusy(BusyPolicy)` / `getWhenBusy()`、`principal(p)` / `getPrincipal()`、`copyFor(session)`；`BusyPolicy` 挪到 `input` 包。`REJECT` 在 `SessionControl.deferOrReject` 里（会话锁内）与程序轮次、续流同一条规则；`STEER` 由骨架在受理前尝试，直连 `handle` 时按 `QUEUE`。主体校验在 `InputPipeline` 第 0b 步（归属之后、作用域预检之前），不符时 `SCOPE_UNAVAILABLE` + `principal mismatch`，不占位、不进积压；不进积压保存（受理时已校验） |
| host | 删除 `SubmitSpec`、`submit(t, sid, text, origin)`、`isBusyFor`、`checkPrincipal`；`submit(t, sid, input[, observer])`、`stream(t, sid, input[, observer])`；加载会话后 `input.copyFor(session)`，输入已带会话且 id 不符时抛 `IllegalArgumentException`；回调与路由一律用副本上的 origin（装饰器改的也算）；缺主体的告警留在骨架（只对用户提交，不对审批决策） |
| soloncode | `WebGate`、`DesktopInputRouter`、`PrintMode` 三处把 `customize` 里的内容平铺，`PrintMode` 的 `observe` 改为第四个参数 |

不选的：把会话从 `HarnessInput` 拿掉（`engine.handle(session, input)`，所有直连调用方都要改）；`submit(t, sid, Consumer<HarnessInput>)`（回调式间接写法还在）；让 `SubmitSpec` 继承或包装 `HarnessInput`（还是两个类型）。

核实的两点：`copyFor` 复制全部 25 个字段，由反射测试守住（以后新增字段漏抄会失败）；`InputAttachments` 是可变对象，引擎受理期间不修改它，但仍按列表复制一份（包内 `copy()`），副本与原对象互不影响。

### 12.2 执行记录

- core：`BusyPolicy`（新）、`HarnessInput`、`InputAttachments.copy()`、`SessionControl.deferOrReject`、`InputPipeline` 第 0b 步。新增 `HarnessInputSubmitOptionsTest` 6 例：并发 12 条 REJECT 只有 1 条 TASK、其余 BUSY 且积压为空；QUEUE 与直连的 STEER 照常进积压；主体不符被拒且不占位、一致或不传照常受理、会话无主体时传主体被拒；`copyFor` 反射全字段。
- 同批：`HarnessProfile` 改名 `DeployMode`（`Builder.deployMode` / `getDeployMode`，校验信息改为 `SERVER deploy mode ...`），goal 与文档同步。
- host：守卫 `EXTENSION_POINTS` 去掉 `SubmitSpec` 的 4 项，改登记「带本次监听的 `submit` / `stream`」；新增 `submitSpecStaysRetired`。新增 `submittedInputIsCopiedAndHasOneOrigin`（调用方对象不被绑定、不被装饰器改动；回调看到装饰后的来源；会话不一致抛异常）。参考宿主与 README 示例改为平铺写法；README §8.2 改为 `HarnessInput` 表。

## 十三、结果类型不合并，用 host 的人只认一套词（2026-11-08）

### 13.1 决定

四个结果类各答一个问题，不合并：`InputOutcome`（core）回答「这条输入你该怎么执行」，带着执行要用的请求、`stream()`、`discard()`、`afterRun()`，交给 host 的调用方会被重复订阅或误释放占位；`SubmitResult` 回答「已经被怎么处理了」，有 core 给不出的 `RunHandle` 与 `STEERED`；`RunOutcome` 回答「这一轮怎么结束的」，与提交不在同一时刻（一次提交开出 0 或 1 轮）；`ProgramResult` 是包内类，把 `RunOutcome` 换成 goal / loop 要的结局，外部看不到。内部枚举同理：`InputOutcome.Kind` 与 `SubmitResult.Kind` 的差异（`TASK` 与 `STARTED`、`STEERED`）是真实的；`CancelCause` 多出的 `HANDLE` / `EXTERNAL` 不是中断方式，不能用 `InterruptMode` 代替。

真正的问题是用 host 的人两套词都会碰到：`submit` 返回 `SubmitResult.Kind`，而 `HostSink` 回调里的 `HostInput.getKind()` 是 core 的 `InputOutcome.Kind`，受理前结束的输入还是 null；拒绝码也得 `import InputOutcome.RejectReason`。改法：

| 层 | 改动 |
|---|---|
| core | `RejectReason` 挪成 `input` 包独立枚举（与 `BusyPolicy` 同属受理口径），取值与 `code()` 不变 |
| host | `HostInput.getKind()` 返回 `SubmitResult.Kind`，永不为 null，与同一次 `submit` 的返回值一致：`TASK` → `STARTED`（含队列续发、后台轮次），忙时可执行命令触发了任务 → `REJECTED`，会话不存在 / 非持有节点 → `REJECTED`，插话成功 → `STEERED`，其余同名；换算只在 `HarnessHost.kindOf` 一处，与 `route` 的分支一一对应 |
| soloncode | `WebHostSink`、`DesktopHostSink` 改用 `SubmitResult.Kind`；`rejected` 里「忙时命令触发任务」的判断简化为 `reason == BUSY && isBesideRunning()` |

做完后用 host 的人只认：输入侧 `HarnessInput` / `BusyPolicy` / `RejectReason`，结果侧 `SubmitResult`（含 `Kind`）/ `RunOutcome`（含 `End` / `CancelCause`）。

不做的：`CancelCause.INTERRUPT_ALL` 改名（已与 `InterruptMode` 同名）；删 `SubmitResult.getOwner()`（网关代码读起来更直白）；为 host 另建 `InputKind`（多一个类型，复用 `SubmitResult.Kind` 即可）。

### 13.2 执行记录

- core：新增 `input/RejectReason.java`，`InputOutcome` 删除内部枚举；`InputPipeline`、`SessionControl` 与 core / queue 测试换 import。
- host：`HostInput.early(kind, …)` / `of(kind, …)` 由骨架传入换算后的 kind（`early` 去掉恒为 null 的队列项参数）；`HostSink.admitted` 注释、README §9.4 / §9.6 与回调时序图改用 `STARTED`。`HarnessHostExtensionPointsTest` 原 `assertNull(getKind())` 改为断言 `REJECTED`，并补断言排队输入为 `DEFERRED`、续发开出的一轮为 `STARTED`。
- 文档：harness README、host README、QUICKSTART-SAAS 的 `InputOutcome.RejectReason` 改为 `RejectReason`（`input` 包）。
- 验证：host 79 例全绿（已 install）；core 672 例 32F+5E（失败集与基线一致：ToolStrategy / TaskTalent / Accessor），queue 99 / goal 232 / loop 102 / steer 73 全绿；soloncode-cli 1226 例 3F（settings 既有）+3 跳过。

## 十四、HostInput 不删；枚举放哪里有规则（2026-11-08）

### 14.1 决定

**`HarnessInput` 不能替代 `HostInput`。** `HostInput` 的 15 个读方法里只有 6 个与 `HarnessInput` 重复（文本、来源、模型、子代理、属性），另有「发给谁」（租户、会话 ID、会话）与「引擎怎么处理的」（处置、命令、`isBesideRunning`、`hasReply`、队列项）两类。回调直接收 `HarnessInput` 会：① 处理结果没地方放，只能再交出 `InputOutcome`（带 `stream()` / `discard()`，误调会重复订阅或释放占位）；② `HarnessInput` 可改，同一条输入的多个回调可能在不同线程上互相看到改动；③ 受理前就被拒的路径没有副本，只能把调用方的对象交给回调。所以 `HostInput` 是「一次输入的处理记录」，保留；规则是**与 `HarnessInput` 同名的读方法必须同义**。

按这条规则修两处：

| 缺陷 | 改法 |
|---|---|
| `HostInput.isResume()` 原为 `isResume() || isHitlDecision()`，与 `HarnessInput.isResume()` 同名不同义（Web 经 `submit` + `hitl(...)` 且 `resume=false` 时两边不一致） | 改为只看 `isResume()`，新增 `isHitlDecision()`。`host.decide` 总带 `resume(true)`，桌面端行为不变 |
| 非持有节点、会话不存在两条提前拒绝路径构造 `HostInput` 时传 `in=null`，回调读不到调用方的模型、子代理、属性（宿主用属性做请求关联时关联不上） | 改传调用方的 `HarnessInput`；`HostInput` 只复制值、不持有对象 |

**枚举放哪里（与 JDK 一致）**：只被一个类型使用的枚举嵌套在它里面（如 `Thread.State`）；出现在两个以上类型的公开签名里的放到顶层（如 `TimeUnit`）。同一外层类型的嵌套类（如 `HarnessEnginePool.Builder`）算同一个类型。

| 枚举 | 处理 |
|---|---|
| `SubmitResult.Kind` | 独立为 host 的 `Disposition`（处置 / 去向）：回调里没有 `SubmitResult`，却要写 `input.getKind() == SubmitResult.Kind.STARTED`。名字考虑过 `SubmitKind`（中文不好讲）、`InputKind`（会被读成「输入是哪一类」）、`SubmitStatus`（与 `SessionStatus` 撞词）。getter 跟着改为 `getDisposition()`（见 14.3） |
| `GoalSnapshot.Status` | 守卫首跑抓到：出现在 `GoalExtension`、`GoalProjection` 的公开签名里，独立为 goal 包的 `GoalStatus` |
| `HarnessEnginePool.WhenFull` | 只在池内，保留嵌套；改名 `FullPolicy`，与 `whenBusy(BusyPolicy)` 同一写法（`whenFull(FullPolicy.WAIT, …)`） |
| `InputOutcome.Kind`、`HarnessResult.Code`、`HostSignal.Type`、`RunOutcome.End` / `CancelCause` | 只被一个类型使用，保留嵌套。`InputOutcome.Kind` 不跟着独立：前缀本身说明它是 core 层的词，与 host 的 `Disposition` 分得开；`HostSignal.Type` 不复用 `Disposition`（没有 `STARTED`，多了 `EVENT` / `FINISHED`） |

守卫：`HarnessStructureGuardTest.nestedPublicEnumsStayWithTheirOwner`——反射扫描 core、四插件、host 的全部公开类型，嵌套公开枚举出现在别的外层类型的公开 / 受保护方法、构造器、字段签名里（含泛型参数）即失败。

### 14.2 执行记录

- host：新增 `Disposition.java`，删除 `SubmitResult.Kind`；`HostInput`（`getKind()` 返回 `Disposition`，`isResume` 改语义，新增 `isHitlDecision`）、`SubmitResult`、`HarnessHost`（`kindOf`、`early` 等）跟着改；两条提前拒绝路径传调用方输入。测试：6 个类的 `SubmitResult.Kind` 换成 `Disposition`；补断言「会话不存在 / 非持有节点的拒绝带着属性与模型」「`submit` + `hitl` 不带续跑时 `isResume()` 为 false、`isHitlDecision()` 为 true」。
- core：`HarnessEnginePool.WhenFull` → `FullPolicy`。
- goal：新增 `GoalStatus.java`，`GoalSnapshot` 删除内部枚举（状态机图移到 `GoalStatus`）；goal 主代码、测试、README 与 harness README 换名。
- soloncode：`WebHostSink`、`WebGate`、`DesktopHostSink`、`DesktopInputRouter`、`PrintMode` 换 `Disposition`；`LoopWebController`、`SessionSnapshots`、`GoalLoopEndpointTest` 换 `GoalStatus`；`DesktopRunSink` / `DesktopHostSink` 的 `isResume()` 只来自 `host.decide`，行为不变。
- 验证：host 80 例全绿、goal 232 例全绿（均已 install）；core 672 例 32F+5E（失败集与基线一致：ToolStrategy / TaskTalent / Accessor），queue 99 / loop 102 / steer 73 全绿；soloncode-cli 1226 例 3F（settings 既有）+3 跳过。

### 14.3 后续两处改名

**`GoalState` → `Goal`**：`GoalState` 与 `GoalStatus` 只差一个后缀，英文、中文都是「状态」。命名规则是「插件名 + 插件里装的那个东西」：queue 装多个条目（`QueueItem`）、loop 装多个定时任务（`LoopTask`），goal 每会话只有一个目标，那个东西就是目标本身。不用 `GoalTask`：4.2.0 刚把公开的 `GoalTask` 改名为 `GoalSnapshot`，再用会让读更新日志的人误以为改回去了；goal 没有 taskId，带 Task 会让人去找 id 与 list；也容易读成「目标下的子任务」。它只在包内可见，从未发布。改后 goal 包四个名字各管一件事：`Goal` 本体（可变）、`GoalStatus` 状态取值、`GoalSnapshot` 只读副本、`GoalProjection` 落盘投影。

**`getKind()` → `getDisposition()`**（`SubmitResult` 与 `HostInput` 一起）：core 的 `InputOutcome.getKind()` 返回另一套词（`TASK` …），`HarnessHost` 里 `outcome.getKind()` 与 `r.getKind()` 同文件出现却不是一回事；`HostInput.getKind()` 上一轮还返回 core 的 Kind，老读者会误读。两个方法是同一个值，必须同名，所以一起改。规则：host 的读方法名与返回类型同名，不与 core 同名方法混用。

执行记录：

- goal：`GoalState.java` → `Goal.java`（类注释补命名说明），包内 `GoalProjection.ofState` → `ofGoal`；测试 `GoalStateTest` / `GoalStateErrorTest` → `GoalTest` / `GoalErrorTest`，`GoalBoundaryTest` 的源码路径与用例名跟着改。会话属性键 `GoalAttrs.GOAL_STATE`（值 `session.goal.state`，运行态、不落盘）未改。
- host：`SubmitResult` / `HostInput` 的 `getKind()` → `getDisposition()`（字段、工厂参数同步），`HarnessHost.kindOf` → `dispositionOf`；`Disposition`、`HostSink` 注释、README、QUICKSTART-SAAS、测试与 `ReadmeSamples` 同步。
- soloncode：`PrintMode`、`WebGate`、`WebHostSink`、`DesktopHostSink`、`DesktopInputRouter` 换 `getDisposition()`；`app-loop.js` 注释改指 `GoalStatus`。`GoalDataMigrator` 里的 `GoalState` 指旧版 tasks.json 的字段，属历史格式，保留。
