# solon-ai-harness-host

solon-ai harness 的**宿主骨架（L0）与部署层**：把「受理 → 开流 → 终态 → 续发积压」整套编排收进框架，宿主只实现一个输出通道 `HostSink`；多租户时用引擎池 `HarnessEnginePool` 按租户托管引擎。

- 包名：`org.noear.solon.ai.harness.host`
- 起始版本：4.2.0（全部类型标注 `@Preview("4.2.0")`，4.2.x 内可能还有调整）
- 依赖：`solon-ai-harness-core` + goal / queue / steer / loop 四个会话插件
- 聚合包 `solon-ai-harness` 只做引用，已包含本模块；只要骨架时也可以直接引本模块

上手教程见 [QUICKSTART-SAAS.md](../solon-ai-harness/docs/QUICKSTART-SAAS.md)，术语见 [CONCEPTS.md](../solon-ai-harness/docs/CONCEPTS.md)。本文只列模块边界与 API。

---

## 1. 依赖

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-harness-host</artifactId>
    <version>${solon-ai.version}</version>
</dependency>
```

**引入不等于装配**：插件上了类路径不会自己生效，仍需在构建引擎时用 `Builder.extensionAdd(...)` 挂上（SERVER 模式下 build 之后不能再挂）。不要的插件删掉那一行即可，自己写的扩展也是同一种写法。

## 2. 什么时候用、什么时候不用

| 宿主形态 | 用法 |
|---|---|
| SaaS 服务端、IM 网关、普通 Web 服务：框架拥有输出通道即可 | 用 `HarnessHost`（本模块） |
| 自带终态门、需要自定义「回执与流」的先后时序、需要序号回放（如 soloncode 的 Web / 桌面端） | 直接用 `engine.handle`（L1，见 core README） |

骨架替宿主代办的约定（直连 `engine.handle` 时都要自己写对）：

1. 按会话 ID 只查不建（不存在返回 `session_not_found`）；多节点先查归属再加载会话；
2. 订阅之前领取 `RunTicket`；调度窗口内被取消则放弃开流；开流前失败则 `discard` 并收口派发；
3. 积压续发登记在 `afterRun`（运行句柄注销之后），判定口径固定为 `!isOccupied`；回执、排队、审批、空输入之后也会续发；
4. 队列续发的一轮按当初排队那条消息的来源投递；
5. 审批决策与续流一次完成，卡已失效时回调 `hitlExpired`，未齐备时推送剩余未决卡；
6. `engine_closed`（引擎刚被池回收）自动换新引擎重试一次；`not_owner` 交给 `HostSink#notOwner`；
7. goal / loop 的后台轮次走同一套终态与续发；首次取到引擎时先登记执行器、再 `goal.recover()`；
8. 关闭会话的顺序固定为「判忙 → 通知扩展 → 删存储」（见 `closeSession`）。

## 3. 最小示例

```java
import org.noear.solon.ai.harness.host.HarnessHost;
import org.noear.solon.ai.harness.host.HostRun;
import org.noear.solon.ai.harness.host.HostSink;
import org.noear.solon.ai.harness.host.SubmitResult;
import org.noear.solon.ai.harness.InterruptMode;

HarnessHost host = HarnessHost.builder()
        .engines(pool::get)                       // 租户 → 引擎
        .sink(new HostSink() {
            @Override
            public void reply(InputOrigin origin, String text) { /* 按 origin 回投文本 */ }

            @Override
            public void event(HostRun run, AgentEvent event) { /* 按 run.getOrigin() 推流事件 */ }
        })
        .build();

AgentSession session = host.openSession(tenant, sid, principal);      // 建会话（一次带主体）
SubmitResult r = host.submit(tenant, sid, text, origin);              // 使用态
host.decide(tenant, sid, callId, HITLAction.APPROVE, null, origin);   // 审批
host.interrupt(tenant, sid, InterruptMode.INTERRUPT_ALL);             // 停止
host.admin(tenant).call(sid, LoopExtension.class,
        (loop, s) -> HarnessResult.ok(loop.list(sid)));               // 管理态
```

`HostSink` 的**每个方法都有缺省实现**，`sink(...)` 不是必填——不传就能跑通完整一轮（事件只记日志）。接入后按需覆盖投递出口即可。

## 4. 类型一览

| 类型 | 作用 |
|---|---|
| `HarnessHost` | 骨架本体：`openSession` / `closeSession` / `warmup` / `submit` / `decide` / `interrupt` / `admin` |
| `HostSink` | 输出通道（全部有缺省）：投递出口 `reply` / `event`；运行类 `runStarted` / `runFinished` / `hitl`；结构化事实 `rejected` / `deferred` / `hitlExpired` / `notOwner` |
| `HostRun` | 一条运行流的只读描述：租户、会话、来源、代理名、是否队列派发、是否后台轮次、后台轮次的最终文本 |
| `SubmitResult` | `submit` / `decide` 的结果，只用于映射状态码（内容已经经 sink 发出） |
| `SessionAdmin` | 管理态：`call(sid, 扩展类型, 操作)` 与 `interrupt(sid, mode)`；**不做鉴权** |
| `HarnessEnginePool` | 引擎池：按租户懒建、空闲且静默才回收、满额策略（`WhenFull.REJECT / WAIT`）；通常作为 `engines(pool::get)` 的来源。只用引擎公开的 `isQuiescent()` / `shutdown()`，与 `HarnessHost` 无代码耦合 |

结果类型 `HarnessResult` 在 core（`org.noear.solon.ai.harness.HarnessResult`），插件的写门面直接返回它，所以管理入口不用做任何翻译。

### 4.1 HarnessHost.Builder

| 方法 | 缺省 | 说明 |
|---|---|---|
| `engines(Function<String, HarnessEngine>)` | 必填 | 租户 → 引擎；同一租户应返回同一实例，直到它被回收。单租户部署传 `k -> engine` |
| `sink(HostSink)` | 缺省 sink（只记日志） | 输出通道 |
| `executor(Executor)` | Reactor `boundedElastic` | 开流动作的执行器 |
| `wrap(UnaryOperator<Runnable>)` | 原样 | 包装开流动作（MDC、日志作用域、租户上下文） |
| `programRuns(boolean)` | `true` | goal / loop 后台轮次是否接入骨架（通道名 `"host"`）；关闭时沿用插件缺省执行器 |

没有「单引擎宿主」捷径（`engine(HarnessEngine)`）：多租户共用一个引擎会让不同租户的会话互相可见，而那种写法不会报错。

### 4.2 HarnessHost 方法

| 方法 | 返回 | 说明 |
|---|---|---|
| `openSession(tenant, sid, principal)` | `AgentSession` | 建会话并写入主体，一次调用完成（不给「先建后写主体」的窗口）。主体非空、且设置后不可改（换主体抛 `SecurityException`） |
| `closeSession(tenant, sid[, storageCleanup])` | `boolean` | 判忙 → 通知扩展 → 删存储；忙时返回 false 且不做任何事；本来就不存在返回 true |
| `warmup(tenants)` | `int` | 预热有后台工作的租户（单个失败只记日志），返回成功数 |
| `submit(tenant, sid, text, origin[, customizer])` | `SubmitResult` | 提交用户输入（含斜杠命令）；`customizer` 补充附件、显式模型 / 子代理、`attr(...)` |
| `decide(tenant, sid, callId, action, note, origin)` | `SubmitResult` | 提交审批决策；本批齐备时直接续流（`STARTED`） |
| `interrupt(tenant, sid, mode)` | `HarnessResult<Boolean>` | 值为「确有运行中的流被停掉」；`NOT_FOUND` / `NOT_OWNER` |
| `admin(tenant)` | `SessionAdmin` | 管理态入口 |

`engine(tenant)` 与 `drain(tenant, sid)` 是包内方法：宿主需要的东西已经由 `openSession` / `closeSession` / `submit` / `admin` 覆盖，绕过骨架直接取引擎会让后台轮次不经 `HostSink`、结束后也不续发。

### 4.3 SubmitResult

| Kind | 含义 | `httpStatus()` |
|---|---|---|
| `STARTED` | 本轮已交给执行器（结果经 `event` / `runFinished` 推送） | 200 |
| `REPLY` | 只有回执（命令已执行，或在本渠道不可用而未执行） | 200 |
| `DEFERRED` | 会话忙，已排队（`getBacklogPosition()`），空闲后由宿主续发 | 202 |
| `REJECTED` | 未受理（`getRejectReason()`，`RejectReason` 枚举） | 404（`SESSION_NOT_FOUND`）/ 307（`NOT_OWNER`）/ 403（`SCOPE_UNAVAILABLE`）/ 503（`ENGINE_CLOSED`）/ 409（其余） |
| `HITL` | 审批已记录但本批未齐备，或卡已失效 | 200 |
| `IGNORED` | 空输入 | 200 |

`STARTED` 的含义是「已经交出去了」，不保证流已真的跑起来（调度空档里被取消、启动失败也是它，失败会经 `runFinished` 以 error 收尾）。

结果只说明事实，不携带文案：提示已经由 `HostSink` 的结构化回调交给宿主（`rejected` / `deferred` / `hitlExpired` / `notOwner`）。`NOT_OWNER` 的持有者另可从 `getOwner()` 读到，网关不必自己解析拒绝详情。

`Kind` 与 core 的 `InputOutcome.Kind` 一一对应（枚举顺序也一致，便于对照），只有 `TASK` 在骨架层叫 `STARTED`（骨架已经替你交出去了）。拒绝原因统一为 `InputOutcome.RejectReason` 枚举，其中 `SESSION_NOT_FOUND` 只由宿主层给出；分流写 `switch (r.getRejectReason()) { case NOT_OWNER: ... }`，线上值（如 `busy`、`not_owner`、`session_not_found`）用 `getRejectReason().code()`。

### 4.4 InterruptMode

`host.interrupt` / `admin.interrupt` 直接使用 core 的 `InterruptMode`，与 `engine.interrupt(session, mode)` 同一枚举、同一语义：

| 值 | 行为 |
|---|---|
| `INTERRUPT_ALL` | 用户点「停止」：清空排队、停当前流、在历史里补写取消消息 |
| `CANCEL_STREAM` | 只停当前流，保留排队（结束后续发下一条），不写历史 |
| `PREEMPT_STREAM` | 为新一轮腾位：只停流，不推进取消代数（骨架宿主一般用不到） |

两段式 `prepareInterrupt`（自定义终态时序）属于 L1，骨架不暴露。

### 4.5 SessionAdmin

`call(sessionId, extensionType, action)` 按固定顺序检查，任一不过即返回对应结果码：

1. `sessionId` 为空 → `INVALID`
2. 扩展未装配 → `DISABLED`
3. 会话不归本节点 → `NOT_OWNER`（先查归属再加载会话）
4. 会话不存在 → `NOT_FOUND`（只查不建）
5. 执行 `action(扩展, 会话)`，返回它给出的 `HarnessResult`；action 返回 null 视为 `OK`

`OK` 之后自动续发积压（入队、置顶后会话空闲就立即执行）。action 里抛出的 `NotOwnerException` 译为 `NOT_OWNER`，`IllegalArgumentException` 译为 `INVALID`、`IllegalStateException` 译为 `CONFLICT`——这只是给宿主自己的扩展兜底，goal / loop / queue / steer 的写门面本身不抛业务异常。

**鉴权不在本类**：它需要调用者身份、角色、租户成员关系、限流等上下文，框架一样拿不到。请在控制器 / 拦截器层做（`@CheckRole`、自己的 authz），再调用管理态：

```java
// 控制器层：先鉴权
authz.require("console.queue.enqueue", tenant, principal, sid);

SessionAdmin admin = host.admin(tenant);
HarnessResult<Integer> pos = admin.call(sid, QueueExtension.class,
        (queue, s) -> queue.enqueue(s, text, InputOrigin.channel("console")));

HarnessResult<Void> paused = admin.call(sid, GoalExtension.class,
        (goal, s) -> goal.pause(sid));

admin.interrupt(sid, InterruptMode.CANCEL_STREAM);
```

所有插件门面统一返回 `HarnessResult`，steer 也不例外（`HarnessResult<SteerReceipt>`，细分码见 `SteerExtension.REASON_*`），可以直接交出去：

```java
admin.call(sid, SteerExtension.class, (steer, s) -> {
    return steer.cancelPending(s, null);   // OK 时 getValue().getCanceled() / getPending()
});
```

## 5. 模块边界（守卫测试）

本模块的 `HarnessStructureGuardTest` 守护整个 harness 家族的结构约束：

| 约束 | 说明 |
|---|---|
| 聚合包无源码 | `solon-ai-harness` 只有 pom，且引用 host（core 与四个插件由 host 传递） |
| host 只在最上层 | core 与插件不得 import `org.noear.solon.ai.harness.host` |
| 插件互不依赖 | goal / loop / queue / steer 之间零 import |
| 写门面返回 `HarnessResult` | goal / loop / queue 门面的写方法不得返回 `int` / `boolean` 哨兵值 |
| 引擎池在 host | core 不得再有 `HarnessEnginePool`（core 的 `HarnessPackageBoundaryTest.enginePoolLivesInHost`） |
| 主代码无弃用标记 | 未发布前不留兼容层（`HarnessEngine` 的 `prompt` / `resume` 为登记在案的例外） |
| 框架不含业务决策 | 主代码不得出现中文用户文案；`AdminAuthorizer` 不得复活（鉴权归宿主） |
