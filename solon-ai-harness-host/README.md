# solon-ai-harness-host

solon-ai harness 的**宿主骨架（L0）**：把「受理 → 开流 → 终态 → 续发积压」整套编排收进框架，宿主只决定输出怎么呈现。面向任意 agent / agent SaaS：HTTP 一问一答、IM 机器人、多会话推送的 Web 应用都用同一套 API，按需加深。

- 包名：`org.noear.solon.ai.harness.host`
- 起始版本：4.2.0（全部类型标注 `@Preview("4.2.0")`，4.2.x 内可能还有调整）
- 依赖：`solon-ai-harness-core` + goal / queue / steer / loop 四个会话插件（只想要最小依赖时，直接用 core 的 `engine.handle`，即 L1）
- 引擎池 `HarnessEnginePool` 在 core（`org.noear.solon.ai.harness.HarnessEnginePool`），通常作为 `engines(pool::get)` 的来源
- 聚合包 `solon-ai-harness` 只做引用，已包含本模块

上手教程见 [QUICKSTART-SAAS.md](docs/QUICKSTART-SAAS.md)，术语见 [CONCEPTS.md](../solon-ai-harness/docs/CONCEPTS.md)，设计取舍见 [HOST-OPTIMIZATION.md](docs/HOST-OPTIMIZATION.md)。

---

## 1. 依赖

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-harness-host</artifactId>
    <version>${solon-ai.version}</version>
</dependency>
```

**引入不等于装配**：插件上了类路径不会自己生效，仍需在构建引擎时用 `Builder.extensionAdd(...)` 挂上（SERVER 模式下 build 之后不能再挂）。

## 2. 三档用法

**第一档：一个请求一轮，不写 sink**（HTTP / SSE / 批处理 / 测试）

```java
HarnessHost host = HarnessHost.builder().engines(pool::get).build();
host.openSession(tenant, sid, principal);

RunOutcome o = host.submit(tenant, sid, SubmitSpec.of("hello").principal(principal))
                   .getRun().await(Duration.ofMinutes(5));            // 同步拿结果
Flux<HostSignal> sse = host.stream(tenant, sid, SubmitSpec.of(text).principal(principal)); // 直接返回给 SSE
```

**第二档：推送型**（WebSocket / IM）：覆盖两个投递出口

```java
HarnessHost host = HarnessHost.builder()
        .engines(pool::get)
        .sink(new HostSink() {
            public void reply(HostInput in, String text) { push(in.getSessionId(), text); }
            public void event(HostRun run, AgentEvent e) { push(run.getSessionId(), e); }
        })
        .build();
```

**第三档：每轮有状态**（分段渲染、按轮序号、按轮拦截器）：覆盖 `open`，每轮返回自己的 `RunSink`

```java
public RunSink open(HostRun run) {
    push(run.getSessionId(), "reset");                    // 本轮开始：流门复位
    return new RunSink() {
        int seq;                                          // 按轮状态
        public void prepare(RunPrep p) { p.request().options(o -> o.interceptorAdd(myInterceptor)); }
        public void event(AgentEvent e) { push(run.getSessionId(), ++seq, e); }
        public void finished(RunOutcome o) { push(run.getSessionId(), "done:" + o.getEnd()); }
    };
}
```

三档的完整写法见测试里的参考宿主 `ReferenceHosts`（A 无状态 SSE、B 文本 IM 机器人、C 多会话推送）。

## 3. 框架代办的事

直连 `engine.handle` 时这些都要宿主自己写对：

1. 按会话 ID 只查不建（不存在返回 `session_not_found`）；多节点先查归属再加载会话；传了主体时校验会话主体（防止用别人的 sessionId 提交）；
2. 订阅之前领取 `RunTicket`；调度窗口内、`prepare` 期间被取消则放弃开流；开流前失败则 `discard` 并收口派发；
3. 每轮 `open` 恰好配一次 `finished`，结束方式为 `COMPLETED` / `FAILED` / `CANCELLED`，附带用量 `RunUsage`；
4. 积压续发登记在 `afterRun`（运行句柄注销之后）；回执、排队、审批、空输入之后也会续发；续发的一轮按当初排队那条消息的来源投递；
5. 用户停止（`INTERRUPT_ALL`）内部走两段式：先落取消消息、把本轮标记为 `CANCELLED`、再停流；
6. 审批决策与续流一次完成，卡已失效时回调 `hitlExpired`，未齐备时推送剩余未决卡；
7. `engine_closed`（引擎刚被池回收）自动换新引擎重试一次；`not_owner` 交给 `HostSink#notOwner`；
8. goal / loop 的后台轮次与用户轮次走同一条链（推流事件、终态、续发、可被停止）；首次取到引擎时先登记执行器、再 `goal.recover()`；
9. 关闭会话的顺序固定为「判忙 → 通知扩展 → 删存储」（见 `closeSession`）。

## 4. 类型一览

**先认识哪几个**（按用法加深，后一档不影响前一档）：

| 档 | 要认识的类型 | 够用于 |
|---|---|---|
| 一问一答 | `HarnessHost`、`SubmitSpec`、`RunHandle` / `RunOutcome`、`HostSignal` | HTTP / SSE / 批处理、无头脚本 |
| 推送型 | + `HostSink`、`HostInput` | WebSocket / IM：受理、排队、拒收、回执的呈现 |
| 每轮有状态 | + `RunSink`、`HostRun`、`RunPrep`、`SessionAdmin` | 分段渲染、按轮计费、管理控制台 |

停止只有两个动作：用户点「停止」用 `host.interrupt(tenant, sid)`，只取消本次请求的那一轮用 `RunHandle.cancel()`。

| 类型 | 作用 |
|---|---|
| `HarnessHost` | 骨架本体：`openSession` / `closeSession` / `warmup` / `submit` / `stream` / `decide` / `interrupt` / `admin` |
| `SubmitSpec` | 一次提交：`origin` / `principal` / `whenBusy` / `customize` / `observe` |
| `SubmitResult` | 结果：`getKind()`、`httpStatus()`；开了流时 `getRun()`，只有回执时 `getReplyText()` |
| `RunHandle` | 本次请求开出的那一轮：`future()` / `await(Duration)` / `cancel()` |
| `HostSignal` | `stream` 的元素：`REPLY` / `EVENT` / `DEFERRED` / `STEERED` / `REJECTED` / `HITL` / `IGNORED` / `FINISHED` |
| `HostSink` | 全局输出通道（全部有缺省）：会话级 `admitted` / `reply` / `deferred` / `steered` / `rejected` / `notOwner` / `hitl` / `hitlExpired`；轮次级 `open` / `event` / `runFinished` |
| `RunSink` | 每轮输出通道（全部有缺省）：`prepare` / `reply` / `event` / `finished` |
| `HostInput` | 一次输入：租户、会话、来源、输入文本、结局、命令、是否在运行中任务旁边、队列项、是否续跑（`isResume`：审批决策 / 显式续流，界面续用原来的输出流）；本次输入的 `selectedModel` / `selectedAgent` / 宿主属性 `getAttr(key)`（续发时为排队时保存的值） |
| `HostRun` | 一轮运行：所属输入、会话、代理 / 模型名、是否队列派发、是否后台轮次 |
| `RunPrep` | 开流前的准备视图：`request()` / `prompt()` / `session()` / `engine()` |
| `RunOutcome` / `RunUsage` | 终态：结束方式、错误、最终文本、挂起的审批卡；用量：token、工具调用数、耗时 |
| `SessionAdmin` | 管理态：`call(sid, 扩展类型, 操作)` 与高级用法 `interrupt(sid, mode)`；**不做鉴权** |

### 4.1 HarnessHost.Builder

| 方法 | 缺省 | 说明 |
|---|---|---|
| `engines(Function<String, HarnessEngine>)` | 必填 | 租户 → 引擎；同一租户应返回同一实例，直到它被回收。单租户部署传 `k -> engine` |
| `sink(HostSink)` | 只记日志 | 全局输出通道 |
| `executor(Executor)` | Reactor `boundedElastic` | 开流动作的执行器 |
| `wrap(BiFunction<String, Runnable, Runnable>)` | 原样 | 包装框架代跑的动作（开流、续发、后台轮次）：MDC、日志作用域、租户上下文 |
| `input(BiConsumer<String, HarnessInput>)` | 无 | 统一补充每条输入（用户提交、审批、队列续发、后台轮次） |
| `programRuns(boolean)` / `programRuns(Predicate<String>)` | `true`（全部会话） | goal / loop 后台轮次是否接入骨架、接入哪些会话；关闭或不命中时沿用插件缺省执行器。首次绑定引擎时只恢复接管范围内的 goal 与积压；`false` 时不恢复（与别的入口共用引擎的无头 / 脚本入口用） |

### 4.2 SubmitSpec

| 方法 | 说明 |
|---|---|
| `of(text)` / `origin(origin)` | 输入文本与来源（回复路由） |
| `principal(p)` | 提交者主体：非空时必须与会话主体一致，否则 `REJECTED(SCOPE_UNAVAILABLE)`。多用户 SaaS 应当总是传；SERVER 模式下不传只告警一次 |
| `whenBusy(BusyPolicy)` | 会话忙时怎么办：`QUEUE`（缺省，进积压、空闲后续发）/ `STEER`（先作为插话交给运行中的那一轮，需 steer 插件，插不进再排队；命令不插话）/ `REJECT`（直接 `REJECTED(BUSY)`，一问一答型宿主用） |
| `customize(in -> ...)` | 本次的附件、显式模型 / 子代理、`attr(...)` |
| `observe(RunSink)` | 只作用于本次请求开出的那一轮的附加监听（在全局 sink 之后调用；进了积压时不调用） |

### 4.3 SubmitResult

| Kind | 含义 | `httpStatus()` |
|---|---|---|
| `STARTED` | 本轮已交给执行器（`getRun()`） | 200 |
| `REPLY` | 只有回执（`getReplyText()`，命令无输出时为 null） | 200 |
| `HITL` | 审批已记录但本批未齐备（`getHitlCards()`），或卡已失效（卡为空） | 200 |
| `IGNORED` | 空输入 | 200 |
| `DEFERRED` | 会话忙，已排队（`getBacklogPosition()`），空闲后自动续发 | 202 |
| `STEERED` | 已作为插话交给运行中的那一轮 | 200 |
| `REJECTED` | 未受理（`getRejectReason()`） | 404（`SESSION_NOT_FOUND`）/ 307（`NOT_OWNER`）/ 403（`SCOPE_UNAVAILABLE`）/ 503（`ENGINE_CLOSED`）/ 409（其余） |

`STARTED` 的含义是「已经交出去了」，不保证流已真的跑起来（调度空档里被取消、启动失败也是它，终态见 `RunOutcome`）。

### 4.4 回调时序

```
submit ─┬─ 会话不存在 / 非持有 / 主体不符 ── rejected / notOwner
        ├─ 忙时插话成功 ───────────────────── steered
        ├─ DEFERRED ───────────────────────── deferred
        └─ 受理（TASK/REPLY/HITL/IGNORED）── admitted
               ├─ REPLY ── reply
               ├─ HITL ─── hitl / hitlExpired
               └─ TASK ─── open(run) → [RunSink.reply] → prepare → event* → finished（恰好一次）→ [hitl]
```

`open` 在调用线程上执行，只用来建对象、不要输出（本轮可能在调度窗口内就被取消）；同一轮的 `reply` / `prepare` / `event` / `finished` 串行（不并发、前后有先行关系），按轮状态用普通字段即可。停止就是停止：中断受理后不再有 `event`，终态一定是 CANCELLED，原因见 `RunOutcome.getCancelCause()`（用户停止 `INTERRUPT_ALL` / 程序取消 / 腾位 / `HANDLE` 客户端断开 / 外部）。回调抛出的异常只记日志。缺省 `open` 返回 `RunSink.forwardTo(sink, run)`：回执转 `HostSink#reply`，事件转 `HostSink#event`，终态转 `HostSink#runFinished`——只覆盖 `HostSink` 的宿主不必知道 `RunSink`。

### 4.5 InterruptMode

`host.interrupt(tenant, sid)` 就是 `INTERRUPT_ALL`；其它方式经 `admin.interrupt(sid, mode)`（高级用法），本轮都以 `RunOutcome.End.CANCELLED` 收尾：

| 值 | 行为 |
|---|---|
| `INTERRUPT_ALL` | 用户点「停止」：清空排队、停当前流、在历史里补写取消消息（内部两段式） |
| `CANCEL_STREAM` | 只停当前流，保留排队（结束后续发下一条），不写历史；`RunHandle.cancel()` 等价于它 |
| `PREEMPT_STREAM` | 为新一轮腾位：只停流，不推进取消代数（一般用不到） |

### 4.6 SessionAdmin

`call(sessionId, extensionType, action)` 按固定顺序检查：`sessionId` 为空 → `INVALID`；扩展未装配 → `DISABLED`；会话不归本节点 → `NOT_OWNER`；会话不存在 → `NOT_FOUND`；执行 `action`。`OK` 之后自动续发积压。action 抛出的 `NotOwnerException` 译为 `NOT_OWNER`，`IllegalArgumentException` 译为 `INVALID`，`IllegalStateException` 译为 `CONFLICT`。

**鉴权不在本类**：请在控制器 / 拦截器层做，再调用管理态：

```java
authz.require("console.queue.enqueue", tenant, principal, sid);
HarnessResult<Integer> pos = host.admin(tenant).call(sid, QueueExtension.class,
        (queue, s) -> queue.enqueue(s, text, InputOrigin.channel("console")));
```

## 5. 模块边界（守卫测试）

| 约束 | 守卫 |
|---|---|
| 聚合包无源码、只引 host；host 只在最上层；插件互不依赖；写门面返回 `HarnessResult`；主代码无弃用标记；无中文用户文案；`AdminAuthorizer` 不复活 | `HarnessStructureGuardTest` |
| 引擎池留在 core | core 的 `HarnessPackageBoundaryTest.enginePoolLivesInCore` |
| **防锁定**：每个扩展点都被参考宿主 A（无状态 SSE）或 B（文本 IM）用到，不能只为「多会话推送 + 每轮状态」这一种宿主存在；主代码无宿主专有词；`HostSink` / `RunSink` 无抽象方法；Builder ≤ 6 个配置方法、`HostSink` ≤ 11 个回调、`RunSink` ≤ 4 个回调 | `HostGenericityGuardTest` |
