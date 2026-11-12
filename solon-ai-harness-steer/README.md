# solon-ai-harness-steer — 运行中插话（/steer）

`solon-ai-harness` 的会话插件之一，提供「插话」能力：任务正在运行时，用户补充一句话，插件把它注入**当前任务**的工作记忆，在下一个推理边界生效，**不打断**进行中的模型流和工具调用。

- 包名：`org.noear.solon.ai.harness.extension.steer`
- 只依赖核心契约 `solon-ai-harness-core`，不依赖其它插件（残留插话经核心 `spi.InputBacklog` 转排队，当前由 queue 插件实现）
- 宿主从装配到运行期只接触一个类：`SteerExtension`

**两种时态**（第三章讲用 L0 宿主骨架时怎么接，第四章总览，第五、六章分别给出自己接线（L1）的完整示例）：

| 时态 | 用户在哪里操作 | 宿主入口：用 L0（`HarnessHost`） | 宿主入口：自己接线（L1） | 本插件的入口 |
|---|---|---|---|---|
| 使用态 | 任务运行中在对话框 / IM 里输入 `/steer 改用 PostgreSQL 方言`，或直接发一句补充 | `host.submit(...)`：`/steer` 照常受理；普通消息配 `whenBusy(BusyPolicy.STEER)` 时由骨架先试插话 | `engine.handle`（`/steer` 是忙时可执行命令，在运行任务旁边执行） | `/steer` 命令、`steer(session, text, origin)`（不比对轮次） |
| 管理态 | 界面上的插话输入框、待生效列表、撤销 / 清空按钮 | `host.admin(tenant).call(sid, SteerExtension.class, 操作)` | 宿主自己的接口（HTTP 等）直接调门面 | `steerOnRun` / `pending` / `cancelOnRun` / `cancelPending`（按轮次绑定） |

相关文档：引擎参考见 [solon-ai-harness-core README](../solon-ai-harness-core/README.md)；L0 宿主骨架见 [solon-ai-harness-host README](../solon-ai-harness-host/README.md)；自己编排 `engine.handle` 见 [ENGINE-L1.md](../solon-ai-harness-core/docs/ENGINE-L1.md)；多租户 / 多节点见 [SAAS.md](../solon-ai-harness-core/docs/SAAS.md)。

---

## 一、功能介绍

### 1.1 与 queue 的区别

| 维度 | `/steer`（本模块） | `/queue`（`solon-ai-harness-queue`） |
|---|---|---|
| 影响对象 | 当前任务本轮的工作记忆 | 当前任务之后的新任务 |
| 生效时机 | 下一个推理边界（`onReasonStart`） | 当前任务结束、宿主领取时 |
| 任务未运行时 | 失败（`NOT_RUNNING` / `RUN_CHANGED`） | 照常入队 |
| 持久化 | 零持久化（只在 `session.attrs()`，不进快照、不进消息历史） | 随会话快照落盘 |
| 容量 | 缺省 5 条（可配） | 20 条 |
| 溢出 | 返回 `BOX_FULL`，由调用方决定改排队还是直发 | 拒绝入队 |

插话会加速上下文膨胀，所以邮箱很小；高频补充应引导用户改用 `/queue`。

### 1.2 生命周期

插件在每次**顶层请求**装配时挂上 `SteerInterceptor`（`onRequestAssemble`，`isDelegated == true` 的 `task` / `multitask` 委派任务不挂；为什么挂在请求级见 1.5）。拦截器在三个边界上工作：

| 边界 | 动作 |
|---|---|
| `onAgentStart`（开轮） | 记录本轮 `runId`。若上一轮没有正常收尾（被 interrupt 或异常退出时 `onAgentEnd` 不会触发），把上一轮残留转排队并推送 `SteerDroppedEvent(RUN_SUPERSEDED)`；`runId` 与当前相同（同一逻辑任务续跑）时保留邮箱，只重试 orphans |
| `onReasonStart`（推理边界） | 先做防御性开轮（拦截器在任务中途才启用时 `onAgentStart` 不会回调；轮次已一致时零行为）；再在满足注入守卫时排空本轮邮箱，逐条注入工作记忆，推送 `SteerAppliedEvent` |
| `onAgentEnd`（收轮） | 清除 `runId`；仍有残留则转排队并推送 `SteerDroppedEvent(RUN_ENDED)`；迟到的旧轮次回调（`runId` 不匹配）被忽略，不会摘走新任务的状态 |

宿主没有任何开流前义务：上一任务被 interrupt / 异常终止后的残留，由新任务开轮时自动交接。

残留处理（"已接受的插话绝不静默丢失"）：

1. 残留逐条经 `InputBacklog#offer` 转入积压（排队）；**先落队列，再推事件**，与有没有订阅者无关；
2. 转入失败的（未装 queue、queue 已停用或已满、入队异常）暂存为 orphans：**永不注入模型**，下次开轮或收轮时重试，可随时撤销；
3. 结果经 `SteerDroppedEvent` 告知：`getQueued()` 已进队列，`getFailed()` 未进队列。

### 1.3 注入守卫与注入格式

| 守卫 | 说明 |
|---|---|
| 首轮不注入 | `trace.getTurnCount() <= 1` 时跳过，保证原始提示词先被采样，不篡改初始意图（也是下文「位置保证」的来源） |
| tool_calls 未闭合不注入 | 工作记忆最后一条是带 `tool_calls` 的 assistant 消息时跳过。典型是 HITL 挂起恢复的窄窗：此时插入 user 消息会打断 tool_use / tool_result 配对，部分模型直接报错；等工具结果补齐后的下一轮自然恢复注入 |
| 无悬挂 | 已接受的插话要么被注入，要么在收轮 / 下次开轮时转排队（1.2），绝不出现「已接受但永不生效」 |

注入消息由公开协议类 `SteerProtocol` 定义：

| 项 | 值 |
|---|---|
| 消息类型 | `UserMessage` |
| 正文 | `SteerProtocol.PREFIX + 原话`，即 `"[用户实时补充] " + 原话` |
| metadata | `source = "steer"`（`SteerProtocol.SOURCE`），`steerId = <插话 ID>`（`SteerProtocol.META_STEER_ID`） |
| 位置保证 | 只出现在本任务第一条 assistant 消息之后 |

不追加 systemPrompt 说明，前缀本身已足够表意。注入只进当前任务的工作记忆，不经 `session.addMessage`（见第九章第 4 条「零持久化」）。

回放历史时用 `SteerProtocol.textOf(message)` 识别：是插话返回去掉前缀的原话，否则返回 null（metadata 为主判据，正文前缀为兜底，快照往返后 metadata 未必保留）。位置保证可用来排除两类假阳性：存量会话里已落盘的旧插话行，以及用户亲手输入的、恰好以前缀开头的提问。

### 1.4 与核心的协作

| 核心契约 | 本插件的实现 |
|---|---|
| `HarnessExtension#onAttach` | 注册 `/steer` 命令（幂等） |
| `HarnessExtension#onRequestAssemble` | 非委派请求挂 `SteerInterceptor` |
| `HarnessExtension#contributeSessionStatus` | 贡献 `SteerExtension.STATUS_PENDING`（本轮邮箱 + orphans 条数） |
| `spi.InputBacklog` | 残留转排队的目标，每次从引擎现取（与插件装配顺序无关，运行期增删 queue 也即时生效） |
| `engine.status(session)` | 忙判定由插件自算：`isBusy() || isAdmitting()`（`handle` 返回 `TASK` 后占位跟着请求走，订阅或 `call()` 结束前仍算 admitting），宿主不传谓词 |

### 1.5 拦截器为何挂在请求级

| 问题 | 说明 |
|---|---|
| 为什么不是主代理的默认拦截器 | 面板或 `@` 提及选中的子代理也是一个**顶层请求**，只是目标代理不是主代理。挂在主代理默认拦截器上，这种请求根本命不中：`onAgentStart` / `onReasonStart` 不执行、`activeRunId` 恒为 null，插话一律 `NOT_RUNNING` / `RUN_CHANGED`，表现为「选了子代理就不能插话」。挂到请求级 options 后，无论目标是主代理还是面板子代理都能命中 |
| 为什么排除委派任务 | `task` / `multitask` 的子代理（`isDelegated == true`）跑在临时会话上，与前端会话无关，没有插话邮箱语义，挂了只会往临时会话写无用状态 |
| 顺序 | 请求级拦截器排在代理默认拦截器（上下文压缩等）之后，注入是向压缩产物尾部追加，不破坏消息序列 |
| 重复挂载 | 请求级拦截器按 Class 去重，不会挂两份 |
| 宿主要做什么 | 什么都不用做：`onRequestAssemble` 已代劳，宿主**不要**再每请求 new 一个拦截器挂上。没有拦截器时插话能进邮箱但没人消费，永不生效 |

---

## 二、依赖

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-harness-steer</artifactId>
    <version>${solon-ai.version}</version>
</dependency>
```

建议同时装配 `solon-ai-harness-queue`：没有它时残留插话无处可转，只能暂存为 orphans（不丢、不注入，但也不会执行）。

引入不等于装配：插件上了类路径不会自己生效，要在构建引擎时挂上（见 4.1）。用 L0 宿主骨架时还要引入 `solon-ai-harness-host`（见 [host README §2.1](../solon-ai-harness-host/README.md#21-依赖)）。

---

## 三、用 L0 时（HarnessHost）

用 `solon-ai-harness-host` 的 `HarnessHost` 时，宿主不直接调 `engine.handle`：插话的尝试、插不进时的回落、残留转排队后的续发都由骨架完成。本章只讲与 steer 有关的部分，骨架本身见 [host README](../solon-ai-harness-host/README.md)，多租户的引擎构建见 [QUICKSTART-SAAS.md](../solon-ai-harness-host/docs/QUICKSTART-SAAS.md)。

### 3.1 骨架代办了什么

| 环节 | 骨架的行为 |
|---|---|
| 忙时普通消息 | 输入配了 `whenBusy(BusyPolicy.STEER)` 且会话正在运行时，受理之前先调 `steer.steer(session, text, origin)`：成功则处置为 `Disposition.STEERED`，sink 收到 `steered(input)`，`host.stream` 给出 `HostSignal.Type.STEERED` 后结束，`httpStatus()` 为 200 |
| 插不进时 | 邮箱满、轮次刚结束等投递失败时，回落为正常受理，按 `QUEUE` 处理：通常是 `DEFERRED`（sink 收到 `deferred`）；没装 queue 时为 `REJECTED(BUSY)`；会话恰好已空闲时直接开轮（`STARTED`） |
| 不尝试插话的输入 | 空文本、带附件、审批决策与续流、能识别为命令的文本（斜杠命令）、会话不在运行、有待审批（交给引擎给出 `HITL_PENDING`）、引擎没装 steer（此时 `STEER` 退化为排队） |
| `/steer` 命令 | 作为忙时可执行命令照常受理（`REPLY`，`input.isBesideRunning()` 为 true），回执经 sink 的 `reply` 送达；不受 `whenBusy` 影响 |
| 残留插话 | 转排队的插话进积压后，骨架在当前一轮结束后自动续发，续发的一轮按插话自带的 `origin` 投递 |
| 管理态 | `host.admin(tenant).call(sid, SteerExtension.class, 操作)` 按固定顺序检查：`sessionId` 为空 → `INVALID`；扩展未装配 → `DISABLED`；会话不归本节点 → `NOT_OWNER`；会话不存在 → `NOT_FOUND`（只查不建）；然后执行操作。操作抛出的异常按 host README §5.3 译成结果码 |

`whenBusy(STEER)` 只在 L0 生效：直接调 `engine.handle` 时按 `QUEUE` 处理（L1 下自己实现见 5.3 末尾）。

### 3.2 宿主还要做什么

1. **构建期挂插件**：`HarnessEngine.Builder.extensionAdd(new SteerExtension())`，建议同时 `extensionAdd(new QueueExtension())`（承接插不进的输入与残留插话）。
2. **按渠道选忙时策略**：需要「边跑边补充」的渠道（IM、聊天输入框）用 `whenBusy(BusyPolicy.STEER)`；Web 推送想让用户看到「排队中」用缺省 `QUEUE`；一问一答用 `REJECT`。策略对照见 [host README §4.7](../solon-ai-harness-host/README.md#47-忙时策略)。
3. **在 `event` 回调里按类型映射两个事件**：`SteerAppliedEvent` / `SteerDroppedEvent` 和其它流事件一样经 `HostSink#event(run, event)`（缺省 `RunSink` 转来）或 `RunSink#event(event)` 到达，`host.stream` 时是 `HostSignal.Type.EVENT` 的 `getEvent()`。映射成自己协议里的 `steer_applied` / `steer_dropped`（语义见 4.3）；不映射也不会被当成文本打印。
4. **给 `steered` 写回执**：缺省实现什么都不做，推送型宿主一般回一句「已补充到当前任务」。
5. **管理接口先鉴权再 `host.admin`**：框架不做鉴权，也拿不到角色、租户成员关系。

### 3.3 示例：提交与管理

```java
// 构建期挂插件（SERVER 模式下 build 之后不能再挂）
HarnessEngine engine = HarnessEngine.of(workspace, ".solon/")
        // ... 模型、会话、作用域等
        .extensionAdd(new QueueExtension())
        .extensionAdd(new SteerExtension())
        .build();

// 使用态：IM 消息，忙时先插话，插不进再排队
SubmitResult r = host.submit(tenant, sid, HarnessInput.of(text)
        .origin(origin)
        .principal(principal)
        .whenBusy(BusyPolicy.STEER));
// r.getDisposition()：STEERED（已插话）/ DEFERRED（已排队）/ STARTED / REPLY（/steer 等命令）/ ...
```

```java
// HostSink：插话成功的回执与两个事件
@Override
public void steered(HostInput input) {
    im.send(chatOf(input.getSessionId()), "已补充到当前任务");
}

@Override
public void event(HostRun run, AgentEvent e) {
    if (e instanceof SteerAppliedEvent) {
        push(run, "steer_applied", ((SteerAppliedEvent) e).getItems());
    } else if (e instanceof SteerDroppedEvent) {
        push(run, "steer_dropped", e);          // getQueued() / getFailed() / getReason()
    } else {
        render(run, e);
    }
}
```

```java
// 管理态：先鉴权，再经 host.admin 调 steer 门面
@Get
@Mapping("steer")
public Result<List<SteerMessage>> steerPending(String tenant, String sid, Context ctx) {
    return reply(admin("steer.read", tenant, sid, ctx)
            .call(sid, SteerExtension.class, (steer, s) -> HarnessResult.ok(steer.pending(s))), ctx);
}

@Post
@Mapping("steer/clear")
public Result<SteerReceipt> steerClear(String tenant, String sid, Context ctx) {
    return reply(admin("steer.write", tenant, sid, ctx)
            .call(sid, SteerExtension.class, (steer, s) -> steer.cancelPending(s, null)), ctx);
}
```

`admin(...)`（鉴权后返回 `host.admin(tenant)`）与 `reply(...)`（按 `r.httpStatus()` 写应答，`NOT_OWNER` 带上持有者）的写法见 [host README §5.1](../solon-ai-harness-host/README.md#51-完整示例管理接口)。`steerOnRun` / `cancelOnRun` 同样放在 `call` 的操作里调，参数与结果码见第六、七章。回调的先后顺序见 [host README §10](../solon-ai-harness-host/README.md#10-回调时序与线程)。

---

## 四、装配与两种时态

### 4.1 装配

推荐在构建期挂上（SERVER / STANDALONE 都适用）：

```java
// 无参构造即全部缺省（邮箱 5 条、单条 4096 字符、ID 128 字符）
HarnessEngine engine = HarnessEngine.of(workspace, ".solon/")
        // ... 其它配置
        .extensionAdd(new SteerExtension())
        .build();

// 或自定义容量
HarnessEngine engine = HarnessEngine.of(workspace, ".solon/")
        .extensionAdd(SteerExtension.builder()
                .maxBoxSize(8)          // 邮箱容量
                .maxTextLength(8192)    // 单条插话字符上限
                .maxIdLength(64)        // steerId 字符上限
                .build())
        .build();

SteerExtension steer = engine.getExtension(SteerExtension.class).orElse(null);
int maxBox = steer.getMaxBoxSize();       // 投递与提示文案读同一份约束（另有 getMaxTextLength / getMaxIdLength）
```

运行期写法（仅 `runtimeMutable` 时可用，STANDALONE 缺省；SERVER 模式 build 之后调用抛 `IllegalStateException`）：

```java
SteerExtension steer = engine.addExtensionIfAbsent(new SteerExtension());   // 已有同类型实例时返回已挂载的那个
```

装配就这一处，没有任何端口要注入：构造器只有 `()` 与 `builder()`，输出全部走事件（4.3）。核心侧的装配说明见 [core README §1.4](../solon-ai-harness-core/README.md)。

### 4.2 两种时态

```mermaid
flowchart LR
    subgraph 使用态
        U1["运行中输入 /steer ..."] --> H["engine.handle<br/>忙时可执行，在运行任务旁边执行"] --> C["SteerCommand<br/>steer(session, text, origin)"]
    end
    subgraph 管理态
        U2["插话输入框 / 撤销按钮"] --> API["宿主接口"] --> F["steerOnRun / cancelOnRun<br/>pending / cancelPending"]
    end
    C --> B[("本轮插话邮箱")]
    F --> B
    B --> I["下一个推理边界注入"] -. SteerAppliedEvent .-> V["事件流 → 界面"]
    B --> D["收轮仍有残留 → 转排队"] -. SteerDroppedEvent .-> V
```

| 对比项 | 使用态 | 管理态 |
|---|---|---|
| 用户动作 | 任务运行中在对话框里输入 `/steer <补充内容>` | 在插话输入框里提交；在待生效列表里撤销一条或清空 |
| 宿主入口 | 统一输入入口：`engine.handle(HarnessInput)`（运行中在运行任务旁边执行，`isBesideRunning() == true`） | 宿主自己的接口，直接调门面 |
| 轮次绑定 | 不比对：投给"当前正在跑的那一轮" | 严格比对：带上前端看到的 `runId`，换轮了就返回 `RUN_CHANGED` |
| 幂等 | 无（同一内容输两次投两次） | 前端生成 `steerId`，重试返回 `DUPLICATE_STEER_ID` |
| 结果怎么返回 | 回执文本 `outcome.getCommandOutput()` | `HarnessResult<SteerReceipt>`（与其它插件门面同一结果类型），粗码映射 HTTP 状态、细分码作应答码 |
| 只有这一态能做的 | IM / CLI 等只有文本输入的渠道也能插话 | 撤销、批量撤销、刷新后重建待生效列表 |

两种时态投进的是**同一个邮箱**：`/steer` 投递的插话会出现在 `pending(session)` 里，界面上也能撤销；生效与转排队都通过同一组事件通知（4.3）。L0 下 `whenBusy(STEER)` 的插话走的也是使用态这条路径（`steer(session, text, origin)`）。

### 4.3 插话怎样生效：事件流

两个事件都继承 `AbsReActEvent`，随本轮的事件流推出（L1 为 `outcome.getRequest().stream()`，L0 为 `event` 回调，见 3.2）；**刻意不覆写 `getText()`**：它们不属于文本流，一旦给出文本，按文本兜底渲染的宿主会把插话内容顺手打进对话。需要展示时按类型分支：

| 事件 | 建议的协议名 | 含义 | 客户端处理 |
|---|---|---|---|
| `SteerAppliedEvent` | `steer_applied` | 插话已注入工作记忆（「已生效」的唯一信号） | 清除待生效态，落到当前回复气泡里 |
| `SteerDroppedEvent` | `steer_dropped` | 有未消费插话被交接 | `getQueued()` 已转为排队任务：清除气泡；`getFailed()` 未进队列：保留气泡并提示尚未排队 |

映射即接入：harness 不认识任何宿主类型，也没有端口接口可实现。例如 soloncode 在 Web 事件映射里加两个 `instanceof` 分支，映射为 `system.steer_applied` / `system.steer_dropped`；不认识这两个类型的宿主不写代码即自然忽略。下面是自己接线（L1）时的写法，L0 写法见 3.3：

```java
outcome.getRequest().stream().doOnNext(e -> {
    if (e instanceof SteerAppliedEvent) {
        // 已注入：清除这些插话的"待生效"标记，落到当前回复气泡里
        for (SteerMessage m : ((SteerAppliedEvent) e).getItems()) {
            markApplied(m.getId(), m.getText());
        }
    } else if (e instanceof SteerDroppedEvent) {
        SteerDroppedEvent d = (SteerDroppedEvent) e;
        // d.getReason()：RUN_ENDED（正常结束仍有残留）/ RUN_SUPERSEDED（上一轮被中断，新轮开始时交接）
        d.getQueued().forEach(m -> markQueued(m.getId()));      // 已转为排队任务：清除待生效气泡
        if (d.hasFailed()) {
            d.getFailed().forEach(m -> markNotQueued(m.getId())); // 未排队：保留气泡并提示用户
        }
    } else {
        render(e);
    }
}).blockLast();
```

**事件只是展示，持久化在 harness 内**：非流式调用（`call()`）或订阅已取消时事件会被静默丢弃。`steer_applied` 没有副作用，丢了无妨；`steer_dropped` 对应的转排队（经 `spi.InputBacklog`）在推事件**之前**已经完成，不看有没有订阅者，因此 CLI / Desktop 这类不订阅事件的宿主也能把残留插话转为排队任务。顺序反过来，就会在无订阅者时丢插话。

---

## 五、使用态：用户在对话中输入（自己接线，L1）

本章的示例直接调 `engine.handle`（L1）。命令本身的语义（5.1、5.2）对 L0 同样成立；用 L0 时的接法见第三章。L1 的两段式宿主骨架见 [ENGINE-L1 §2](../solon-ai-harness-core/docs/ENGINE-L1.md)，会话插件的接线约定见 [ENGINE-L1 §3](../solon-ai-harness-core/docs/ENGINE-L1.md)。

### 5.1 用户能输入什么

`/steer <补充内容>`，内部调用的就是门面 `steer.steer(session, text, origin)`，开关、忙判定、容量与门面完全同源。

| 结果 | 回执文案 |
|---|---|
| 无参数 | `用法：/steer <补充内容>（在任务执行中向当前任务插入实时补充）` |
| `OK` | `已插话，将在下一步注入当前任务（待生效 N 条）` |
| `BOX_FULL` | `插话邮箱已满（上限 N 条），可用 /queue 排队` |
| `NOT_RUNNING` / `RUN_CHANGED` | `当前无正在执行的任务可插话，可直接发送或用 /queue 排队` |
| `TEXT_TOO_LONG` | `插话内容过长（上限 N 字符）` |
| `DISABLED` | `插话能力已停用，可直接发送或使用 /queue 排队` |
| 其它 | `插话未生效：<getReasonOrCode()>` |
| 扩展已停用（命令层拦截） | `/steer 已停用。发送 /help 查看可用命令。` |

插件**只做投递，不做降级**：任何失败都如实回执，不会代为排队或直接发送，由用户自行决定改排队还是直接发送。

### 5.2 命令的边界

| 项 | 说明 |
|---|---|
| 注册 | `SteerExtension#onAttach` 自动注册，宿主不用注册 |
| 忙态 | `SteerCommand` 声明 `runnableWhenBusy() == true`：任务运行中 `handle` 第 0 步放行，在运行任务旁边执行（`outcome.isBesideRunning() == true`，宿主不要改写回复路由） |
| 渠道 | `terminalOnly() == false`：Web / IM / 桌面都能用，无需 `.terminal(true)` |
| 结局 | 恒为 `Kind.REPLY`，不会产生 `TASK` |
| 来源 | 取宿主透传的 `origin`；渠道为空时命令补成 `"command"`。插话没被注入而转排队时，排队项沿用这份来源 |

### 5.3 宿主接入：完整示例

命令只负责投递，"任务运行中能不能把这条输入交给引擎"由 `engine.handle` 第 0 步的受理判定决定，它已经知道 `/steer` 是忙时可执行命令；宿主只管把输入交给 `handle` 并按结局分支（各结局的含义见 [ENGINE-L1 §1.3](../solon-ai-harness-core/docs/ENGINE-L1.md)）：

```java
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.extension.queue.QueueExtension;
import org.noear.solon.ai.harness.extension.steer.SteerExtension;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.input.InputOutcome;

public class ImInputHandler {
    private final HarnessEngine engine;
    private final QueueExtension queue;

    /** 引擎已在构建期 extensionAdd(new SteerExtension())（注册 /steer）与 extensionAdd(new QueueExtension()) */
    public ImInputHandler(HarnessEngine engine) {
        this.engine = engine;
        this.queue = engine.getExtension(QueueExtension.class)   // 承接残留插话与忙态输入
                .orElseThrow(() -> new IllegalStateException("queue extension is required"));
    }

    /** IM 渠道的一条入站消息（可能是普通消息，也可能是 /steer ...） */
    public void onMessage(AgentSession session, String text, InputOrigin origin) {
        // 受理判定、命令识别、执行、回执都在 handle 内完成（判定与占位、入积压同一把会话锁）
        InputOutcome outcome = engine.handle(HarnessInput.of(session, text).origin(origin));
        switch (outcome.getKind()) {
            case TASK:       // 普通消息：同步跑完；占位由请求自己释放
                setReplyRoute(session, origin);
                try {
                    if (outcome.hasReply()) {
                        reply(origin, outcome.getCommandOutput());
                    }
                    reply(origin, outcome.getRequest().call().getContent());
                } catch (Throwable e) {
                    outcome.discard();   // 幂等；开流前失败时确保占位释放
                    reply(origin, "处理失败：" + e.getMessage());
                }
                drain(session);
                break;
            case REPLY:      // /steer 恒为 REPLY：把回执发回来源渠道即可
                if (!outcome.isBesideRunning()) {
                    setReplyRoute(session, origin);   // 在运行任务旁边执行的命令不能改写运行中任务的回复目标
                }
                reply(origin, outcome.getCommandOutput());
                drain(session);
                break;
            case DEFERRED:   // 任务运行中的普通消息：引擎已入积压
                reply(origin, "已排队（第 " + outcome.getBacklogPosition() + " 位）");
                drain(session);
                break;
            case REJECTED:   // RejectReason.HITL_PENDING：有待审批卡；BUSY：引擎没装 queue 无处可延；等
                reply(origin, outcome.getRejectDetail());
                break;
            case HITL:
            case IGNORED:
                drain(session);
                break;
        }
    }

    private void drain(AgentSession session) {
        queue.drainLoop(session, s -> !engine.status(s).isOccupied(), claim -> {
            InputOutcome outcome = engine.handle(claim.getItem().toInput(session)); // 同一线程：派发轮次
            // 按上面的结局分支处理，回复路由取 outcome.getDispatchOrigin()（略）
            return true;
        });
    }

    private void reply(InputOrigin origin, String text) { /* 按 origin.getChannel()/getReplyTarget() 回投 */ }
    private void setReplyRoute(AgentSession session, InputOrigin origin) { /* 宿主私有 */ }
}
```

不想让用户输入命令前缀的渠道（如 IM 里"任务运行中发的消息一律当作插话"），可以在交给 `handle` 之前先试着直接调门面；插话没生效时再照常交给 `handle`，忙时由引擎入积压（`DEFERRED`），宿主不必自己 `enqueue`。这就是 L0 `whenBusy(BusyPolicy.STEER)` 替你做的事（3.1）；直接调 `engine.handle` 时 `whenBusy(STEER)` 按 `QUEUE` 处理，不会插话，所以 L1 要自己写：

```java
if (engine.status(session).isBusy() && !text.startsWith("/")) {   // 另外：带附件、审批决策、续流的输入不要插话
    HarnessResult<SteerReceipt> r = steer.steer(session, text, origin);   // origin 随插话保存，转排队后回复路由不丢
    if (r.isOk()) {
        reply(origin, "已插话（待生效 " + r.getValue().getPending() + " 条）");
        return;
    }
    // 邮箱满、刚好换轮等：继续交给 handle，由引擎决定排队或直接执行
}
onMessage(session, text, origin);
```

### 5.4 停、改、重排

「停」「改」「重排」不是一条串行管道：steer 只服务于「任务还在跑」的窗口，`interrupt` 之后那个窗口就关了。先判断处于哪个阶段，再选入口（三种干预的完整对照见 [ENGINE-L1 §3.5](../solon-ai-harness-core/docs/ENGINE-L1.md)）：

```java
// 场景 A：任务还在跑，改主意了 —— 插话（不打断）
HarnessResult<SteerReceipt> r = steer.steer(session, "先别改了，回到上一个方案");
if (r.hasReason(SteerExtension.REASON_NOT_RUNNING) || r.hasReason(SteerExtension.REASON_RUN_CHANGED)) {
    queue.enqueue(session, "回到上一个方案");        // 宿主自己决定降级为排队
}

// 场景 B：必须停下来重来 —— 中断后排队（不要再 steer）
if (Boolean.TRUE.equals(engine.interrupt(session, InterruptMode.INTERRUPT_ALL).getValue())) {
    queue.enqueue(session, "回到上一个方案");
}
```

`interrupt` 之后当前轮次已不存在，`steer` 只会得到 `NOT_RUNNING`；被中断那一轮邮箱里的残留，会在下一个任务开轮时以 `RUN_SUPERSEDED` 转排队。各中断方式（`INTERRUPT_ALL` 会连带清队列，`CANCEL_STREAM` 只停流、保留队列）的差别见 [ENGINE-L1 §3.4](../solon-ai-harness-core/docs/ENGINE-L1.md)。

### 5.5 使用态要点

1. **注册不需要宿主做**：`SteerExtension#onAttach` 已注册命令；宿主只要不自己再注册一个同名命令。
2. **忙态放行由 `handle` 完成**：`SteerCommand` 声明 `runnableWhenBusy`，任务运行中输入 `/steer ...`，`handle` 第 0 步放行并在运行任务旁边执行（`isBesideRunning() == true`）。宿主不要在 `handle` 之前自己拦忙，否则 `/steer` 会被挡住或排进队列，变成"任务结束后才插话"，永远是 `NOT_RUNNING`。
3. **空闲时也能受理**：此时命令照常执行（`REPLY`，`isBesideRunning() == false`）并回复"当前无正在执行的任务可插话……"，不会被当成普通消息发给模型。
4. **在运行任务旁边执行的命令不改回复路由**：运行中任务的回复目标属于那个任务；`/steer` 的回执直接回给本条消息的来源。
5. **审批挂起期间**：`handle` 对普通输入返回 `REJECTED`（`RejectReason.HITL_PENDING`）；`/steer`、`/queue` 等声明了 `runnableWhenBusy` 的命令、审批决策与续流由引擎按输入本身豁免，宿主无需传谓词。
6. **回执怎么落地**：缺省的缓冲式命令输出会去掉 ANSI 颜色，回执从 `getCommandOutput()` 一次取回；CLI 想实时写终端，再给 `HarnessInput` 配 `commandOutputFactory(...)`。
7. **不要两条路都走**：已经用门面 `steer.steer(...)` 投递过的内容，不要再拼成 `/steer ...` 交给 `handle`，会投递两次。命令文本得到的是 `Kind.REPLY` + 人读回执，门面得到的是 `HarnessResult<SteerReceipt>`：要程序判定走门面，要现成文案走文本路径（三条路径的对照见 [ENGINE-L1 §3.3](../solon-ai-harness-core/docs/ENGINE-L1.md)）。

---

## 六、管理态：用户在界面上操作（自己接线，L1）

Web / 桌面界面通常有专门的插话输入框和"待生效"气泡列表。这些按钮不经 `handle`，由宿主接口直接调门面。本章示例从引擎直接取门面；用 L0 时把同样的门面调用放进 `host.admin(tenant).call(sid, SteerExtension.class, ...)`（3.3），会话查找、归属检查由骨架完成。与命令最大的区别是**按轮次绑定**：前端在任务结束、下一轮开始的窄窗里提交的插话，必须能识别出"已经不是我看到的那一轮了"。

### 6.1 界面动作与门面对照

| 界面动作 | 门面调用 | 结果 |
|---|---|---|
| 打开 / 刷新页面时恢复状态 | `steer.activeRunId(session)`、`steer.pending(session)` | 当前轮次（无则 null）；待生效列表（本轮邮箱在前、orphans 在后） |
| 提交插话 | `steer.steerOnRun(session, runId, steerId, text, origin)` | `HarnessResult<SteerReceipt>`（6.4 给出界面处理，7.2 给出 HTTP 映射） |
| 撤销一条 | `steer.cancelOnRun(session, runId, steerId)` | `HarnessResult<SteerReceipt>`：OK / `NOT_PENDING` / `RUN_CHANGED` / `INVALID_STEER_ID` |
| 全部撤销 | `steer.cancelPending(session, null)`（或传 id 列表） | `HarnessResult<SteerReceipt>`，`getValue().getCanceled()` 为实际移除条数 |
| 徽章计数 | `engine.status(session).get(SteerExtension.STATUS_PENDING)` | 待生效条数 |

### 6.2 完整示例：管理接口

```java
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessResult;
import org.noear.solon.ai.harness.extension.steer.SteerExtension;
import org.noear.solon.ai.harness.extension.steer.SteerMessage;
import org.noear.solon.ai.harness.extension.steer.SteerReceipt;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.annotation.*;
import org.noear.solon.core.handle.Result;

import java.util.*;

@Controller
@Mapping("/admin/steer")
public class SteerAdminController {
    @Inject
    HarnessEngine engine;

    private SteerExtension steer() {
        return engine.getExtension(SteerExtension.class).orElse(null);
    }

    /** 刷新 / 重连后重建插话面板：当前轮次 + 待生效列表 + 容量 */
    @Get
    @Mapping("state")
    public Result<Map<String, Object>> state(@Param("sessionId") String sessionId) {
        SteerExtension steer = steer();
        if (steer == null) return Result.failure(503, "STEER_UNAVAILABLE");
        AgentSession session = engine.getSessionOrNull(sessionId);   // 只读：不顺带建会话

        List<Map<String, Object>> rows = new ArrayList<>();
        for (SteerMessage m : steer.pending(session)) {              // session 为 null 时返回空列表
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("steerId", m.getId());
            row.put("text", m.getText());
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", steer.activeRunId(session));               // 无活跃轮次为 null：输入框置灰
        data.put("pending", rows);
        data.put("maxBoxSize", steer.getMaxBoxSize());
        data.put("maxTextLength", steer.getMaxTextLength());
        return Result.succeed(data);
    }

    /** 提交插话：runId 来自前端看到的事件流，steerId 由前端生成用于重试幂等 */
    @Post
    @Mapping("submit")
    public Result<Map<String, Object>> submit(@Param("sessionId") String sessionId,
                                              @Param("runId") String runId,
                                              @Param(value = "steerId", required = false) String steerId,
                                              @Param("text") String text) {
        SteerExtension steer = steer();
        if (steer == null) return Result.failure(503, "STEER_UNAVAILABLE");
        AgentSession session = engine.getSessionOrNull(sessionId);
        if (session == null) return Result.failure(409, "NOT_RUNNING");

        // steerId 未传时由后端生成 "s_" + UUID，经回执 getSteerId() 返回
        return toResult(steer.steerOnRun(session, runId, steerId, text, InputOrigin.channel("web")), "STEERED");
    }

    @Post
    @Mapping("cancel")
    public Result<Map<String, Object>> cancel(@Param("sessionId") String sessionId,
                                              @Param("runId") String runId,
                                              @Param("steerId") String steerId) {
        SteerExtension steer = steer();
        if (steer == null) return Result.failure(503, "STEER_UNAVAILABLE");
        return toResult(steer.cancelOnRun(engine.getSessionOrNull(sessionId), runId, steerId), "CANCELED");
    }

    @Post
    @Mapping("cancelAll")
    public Result<Map<String, Object>> cancelAll(@Param("sessionId") String sessionId,
                                                 @Param(value = "steerIds", required = false) String steerIds) {
        SteerExtension steer = steer();
        if (steer == null) return Result.failure(503, "STEER_UNAVAILABLE");
        List<String> ids = (steerIds == null || steerIds.trim().isEmpty())
                ? null : Arrays.asList(steerIds.split(","));          // null = 清空全部（不比对轮次）
        return toResult(steer.cancelPending(engine.getSessionOrNull(sessionId), ids), "CANCELED");
    }

    /** 投递与撤销共用一套映射：粗码自带 HTTP 状态（httpStatus()），细分码原样作应答码 */
    private static Result<Map<String, Object>> toResult(HarnessResult<SteerReceipt> r, String okStatus) {
        if (r.isOk()) {
            SteerReceipt receipt = r.getValue();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", okStatus);
            if (receipt.getSteerId() != null) {
                data.put("steerId", receipt.getSteerId());
            } else {
                data.put("canceled", receipt.getCanceled());          // 批量撤销：实际移除条数
            }
            data.put("queued", receipt.getPending());                 // 操作后仍待生效条数，不必再查一次
            return Result.succeed(data);
        }
        // INVALID 400（EMPTY_TEXT / TEXT_TOO_LONG / INVALID_STEER_ID）：前端提示后不再重试；
        // CONFLICT 409（NOT_RUNNING / RUN_CHANGED / DUPLICATE_STEER_ID / NOT_PENDING）；FULL 429（BOX_FULL）；
        // DISABLED 503；NOT_OWNER 307（多节点时把 r.getOwner() 交给网关转发）。
        // NOT_PENDING：已被注入或已转排队，终态以 applied / dropped 事件为准，不能宣称撤销成功
        return Result.failure(r.httpStatus(), r.getReasonOrCode());
    }
}
```

### 6.3 前端如何拿到 runId

- **事件流**：本轮任何事件的 `AbsReActEvent.getTrace().getRunId()`，宿主随事件信封推给前端；
- **状态接口**：示例的 `state` 返回 `steer.activeRunId(session)`，用于刷新或重连后恢复。

`runId` 在拦截器 `onAgentStart` 写入。任务刚受理、尚未开轮时提交会得到 `NOT_RUNNING` / `RUN_CHANGED`，这不是错误，而是「此刻还没有可插话的推理现场」，前端应按普通发送或排队处理。

### 6.4 状态码与界面处理

| `steerOnRun` 结果（`getReasonOrCode()`） | 原因 | 建议的界面处理 |
|---|---|---|
| `OK` | 已进邮箱，下一个推理边界注入（只代表已入邮箱，生效以 `SteerAppliedEvent` 为准） | 显示"待生效"气泡，等 `SteerAppliedEvent` / `SteerDroppedEvent` |
| `NOT_RUNNING` | 当前没有运行中的轮次 | 回落为普通发送 |
| `RUN_CHANGED` | `runId` 为空、已不是当前轮次，或当前无轮次 | 转为排队（或提示刷新） |
| `BOX_FULL` | 本轮邮箱已满 | 提示"可改为排队" |
| `DUPLICATE_STEER_ID` | 同一 steerId 已在待生效列表（先判重再判容量） | 按已投递处理，不要降级为普通发送 |
| `EMPTY_TEXT` / `TEXT_TOO_LONG` / `INVALID_STEER_ID` | 参数非法 | 提示并停止重试 |
| `DISABLED` | 扩展已停用 | 隐藏插话入口 |
| `NOT_OWNER` | 会话由其它节点持有 | 由网关按 `getOwner()` 转发后重试 |

### 6.5 开关

```java
engine.setExtensionEnabled(steer, false);   // 停用：命令回停用提示、拦截器停止注入、steer() 返回 DISABLED
engine.setExtensionEnabled(steer, true);
```

`pending` / `cancelOnRun` / `cancelPending` / `activeRunId` / 状态贡献不受开关控制，保证停用前已投递的插话仍可查询和撤销。

- **没有「只关命令」的开关**：`onAttach` 一律注册 `/steer`；要下线只有 `setExtensionEnabled(steer, false)` 一条路，命令、拦截器与投递门面一起关。
- **`setExtensionEnabled` 同样受 `runtimeMutable` 约束**：SERVER 模式 build 之后调用抛 `IllegalStateException`。要以停用状态起步，在挂载前调 `steer.setEnabled(false)`，再 `extensionAdd(steer)`。
- **未加入引擎的扩展会先被加入再直接返回**：对没挂上的实例调 `setExtensionEnabled`，等同于先设开关再 `addExtension`（运行期写法，约束同 4.1）。

运行期开关的整体约定见 [ENGINE-L1 §3.4](../solon-ai-harness-core/docs/ENGINE-L1.md)。

### 6.6 管理态要点

1. **用 `steerOnRun`，不要用 `steer`**：界面提交一定带 `runId`，否则任务结束、下一轮刚开始的窄窗里，插话会投进用户没看到的新任务。
2. **降级由界面决定**：插件不代为排队或直接发送。`NOT_RUNNING` 一般回落为普通发送，`RUN_CHANGED` / `BOX_FULL` 一般转排队（调 queue 的管理接口）。
3. **撤销以返回值为准**：`pending` 只是快照，读到的下一刻就可能被推理线程消费；`cancelOnRun` 返回 `NOT_PENDING` 时不能把气泡删掉，要等 applied / dropped 事件给出终态。
4. **orphans 不比对轮次**：转排队失败的残留与轮次无关，任务结束后也能撤；批量撤销（`cancelPending`）同样不比对轮次。
5. **"停止"按钮不是插话**：要停下当前任务用宿主的停止入口 `engine.interrupt(session, InterruptMode.INTERRUPT_ALL)`（L0 为 `host.interrupt` / `admin.interrupt`），之后只能排队，不能再插话。
6. **能力缺失与状态不允许分开报**：扩展没装配（示例的 `STEER_UNAVAILABLE`）与已停用（`DISABLED`）都是 503，不要和 409 的状态码混在一起，前端才能区分"隐藏入口"和"这次没投进去"。
7. **为什么需要 `pending`**：`cancelOnRun` 需要 `steerId`，而后端生成的 `steerId` 只出现在投递返回值里。没有 `pending` 时，页面一刷新或重连，就再也无法撤销一条尚未生效的插话，它仍会在下一个推理边界被注入，用户"明明撤销了却生效"。`pending` 返回副本，推理线程同时消费不影响遍历；它不是持久数据。
8. **门面不经受理、不经作用域**：投递与撤销会先查会话归属（非持有节点返回 `NOT_OWNER`，按 `getOwner()` 转发），但不校验作用域与主体。多租户的管理接口要先自行鉴权（L0 见 [host README §5](../solon-ai-harness-host/README.md#5-管理态用户在界面上操作)，引擎侧的作用域与会话归属见 [SAAS.md](../solon-ai-harness-core/docs/SAAS.md)）。

---

## 七、配置与状态码

### 7.1 Builder

| 方法 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `maxBoxSize(int)` | int | `5` | 本轮邮箱容量；满时投递返回 `BOX_FULL` |
| `maxTextLength(int)` | int | `4096` | 单条插话字符上限（按 trim 后长度）；超出返回 `TEXT_TOO_LONG` |
| `maxIdLength(int)` | int | `128` | steerId 字符上限；超出返回 `INVALID_STEER_ID` |

三个值都必须为正数，否则 `build()` 抛 `IllegalArgumentException("steer limits must be positive")`。`new SteerExtension()` 等价于 `builder().build()`（全取缺省值）。生效值经门面 `getMaxBoxSize()` / `getMaxTextLength()` / `getMaxIdLength()` 读取。

### 7.2 结果码与细分码

所有插件门面统一返回 `HarnessResult`：steer 的投递（`steer` / `steerOnRun`）与撤销（`cancelOnRun` / `cancelPending`）都返回 `HarnessResult<SteerReceipt>`。结果分两层：粗码 `getCode()` 决定处置（HTTP 状态），细分码 `getReason()` 取值即应答码，常量在 `SteerExtension.REASON_*`（值为常量名去掉 `REASON_`，如 `REASON_BOX_FULL = "BOX_FULL"`）。成功判定用 `r.isOk()`，细分判定用 `r.hasReason(SteerExtension.REASON_X)`，透传前端用 `r.getReasonOrCode()`。

| 粗码 | `httpStatus()` | 细分码（`SteerExtension` 常量） | 含义 | 适用 |
|---|---|---|---|---|
| `OK` | 200 | — | 投递：已进入本轮邮箱，将在下一个推理边界注入；撤销：已从待生效列表移除（批量撤销在持有节点上恒为 OK，看 `getCanceled()`） | 全部 |
| `CONFLICT` | 409 | `NOT_RUNNING`（`REASON_NOT_RUNNING`） | 当前没有运行中的轮次（`runId` 为 null 的投递；`session` 为 null 也返回它） | 投递 |
| `CONFLICT` | 409 | `RUN_CHANGED`（`REASON_RUN_CHANGED`） | 请求所带 `runId` 为空（仅 `steerOnRun`）、已不是当前轮次，或当前无轮次；撤销时只针对本轮邮箱（orphans 不比对轮次） | 投递 / `cancelOnRun` |
| `CONFLICT` | 409 | `DUPLICATE_STEER_ID`（`REASON_DUPLICATE_STEER_ID`） | 同一 steerId 已在待生效列表中（本轮邮箱或 orphans；重试幂等） | 投递 |
| `CONFLICT` | 409 | `NOT_PENDING`（`REASON_NOT_PENDING`） | 不在待生效列表（已注入或已转排队；会话从未有过插话状态时也是它） | `cancelOnRun` |
| `FULL` | 429 | `BOX_FULL`（`REASON_BOX_FULL`） | 本轮邮箱已满 | 投递 |
| `INVALID` | 400 | `EMPTY_TEXT`（`REASON_EMPTY_TEXT`） | 正文为空或全空白 | 投递 |
| `INVALID` | 400 | `TEXT_TOO_LONG`（`REASON_TEXT_TOO_LONG`） | 正文超长（按 trim 后长度） | 投递 |
| `INVALID` | 400 | `INVALID_STEER_ID`（`REASON_INVALID_STEER_ID`） | steerId 超过长度上限；撤销时为空或超长 | 投递 / `cancelOnRun` |
| `DISABLED` | 503 | — | 扩展已停用，本次未入邮箱（撤销不受开关控制） | 投递 |
| `NOT_OWNER` | 307 | — | 多节点：会话由其它节点持有，`getOwner()` 为持有者 | 全部 |

HTTP 映射（与 6.2 示例一致）：直接用 `r.httpStatus()`，宿主不必自己维护映射表；应答码取 `getReasonOrCode()`（无细分码时为粗码名，如 `DISABLED`）。`NOT_OWNER` 取 307 而不是 302，是为了网关跳转后仍用原方法与原请求体。粗码的完整码表（含 steer 不产生的 `NOT_FOUND` 404、`FORBIDDEN` 403）见 [host README §5.3](../solon-ai-harness-host/README.md#53-结果码)。

投递的判定顺序：停用（`DISABLED`）→ 归属（`NOT_OWNER`）→ 会话为 null（`NOT_RUNNING`）→ 正文空 / 超长 → `steerOnRun` 的 `runId` 为空（`RUN_CHANGED`）→ steerId 超长 → 轮次比对 → 判重 → 容量。

`SteerReceipt`（OK 时的 `getValue()`，不可变）访问器：

| 方法 | 说明 |
|---|---|
| `getSteerId()` | 插话标识（投递与单条撤销时为目标 steerId；批量撤销为 null） |
| `getCanceled()` | 实际撤销条数（投递为 0；已被消费的不计入） |
| `getPending()` | 操作后仍待生效的条数（本轮邮箱 + orphans） |

`steerOnRun` 的入参情形：

| 情形 | 结果 |
|---|---|
| `runId` 为空 | `RUN_CHANGED`（不生成 steerId） |
| `runId` 与当前轮次不同，或当前没有轮次 | `RUN_CHANGED` |
| 同一 `steerId` 已在邮箱 | `DUPLICATE_STEER_ID`（先判重再判容量，满邮箱中的重试也能拿到准确结果） |
| `steerId` 为 null | 由后端生成 `"s_" + UUID` |

投递后的复查：入邮箱后会在锁外复查会话是否仍忙（`engine.status(session)` 的 busy 或 admitting），并比对轮次代际。不忙或已换轮时尝试回滚：回滚成功报 `NOT_RUNNING`（同轮）/ `RUN_CHANGED`（换轮）；回滚失败（已被注入或已转排队）一律报 `OK`，防止调用方降级后重复执行。

### 7.3 事件

| 事件 | 时机 | 主要方法 |
|---|---|---|
| `SteerAppliedEvent` | 插话已注入工作记忆（"已生效"的唯一信号） | `getItems()` |
| `SteerDroppedEvent` | 有未消费插话被交接（残留插话的唯一输出通道） | `getReason()`、`getQueued()`（已进队列）、`getFailed()`（未进队列，已暂存为 orphans，永不注入）、`hasFailed()` |

| `SteerDroppedEvent.Reason` | 含义 |
|---|---|
| `RUN_ENDED` | 任务正常结束，仍有未消费插话 |
| `RUN_SUPERSEDED` | 上一任务被中断或异常退出，新任务开始时交接 |

`SteerMessage`：`getId()`、`getText()`、`getOrigin()`（永不为 null）。

### 7.4 常量与键

| 常量 | 值 | 说明 |
|---|---|---|
| `SteerExtension.STATUS_PENDING` | `SessionStatus.Key.count("steer.pending")` | 待生效插话数（本轮邮箱 + orphans），不受开关影响 |
| `SteerAttrs.STEER` | `"session.steer"` | 会话级插话状态（`attrs()`，瞬态）；`SteerAttrs` 包内可见，宿主一律走门面 |
| `SteerProtocol.PREFIX` / `SOURCE` / `META_STEER_ID` | `"[用户实时补充] "` / `"steer"` / `"steerId"` | 注入消息协议 |

邮箱内核 `SteerMailbox`、会话状态 `SteerState` 都是包内可见的实现，宿主无从依赖，一律走 `SteerExtension` 门面。

---

## 八、API 速查

| 方法 | 返回 | 受开关控制 | 时态 | 说明 |
|---|---|---|---|---|
| `new SteerExtension()` / `builder()…build()` | — | — | 装配 | 装配 |
| `steer(session, text)` | `HarnessResult<SteerReceipt>` | 是 | 使用 | 投给当前活跃轮次，来源 `NONE` |
| `steer(session, text, origin)` | `HarnessResult<SteerReceipt>` | 是 | 使用（`/steer` 同路径） | 同上，携带来源 |
| `steerOnRun(session, runId, steerId, text)` | `HarnessResult<SteerReceipt>` | 是 | 管理 | 严格轮次绑定 |
| `steerOnRun(session, runId, steerId, text, origin)` | `HarnessResult<SteerReceipt>` | 是 | 管理 | 同上，携带来源 |
| `cancelOnRun(session, runId, steerId)` | `HarnessResult<SteerReceipt>` | 否 | 管理 | 撤销一条 |
| `cancelPending(session, ids)` | `HarnessResult<SteerReceipt>` | 否 | 管理 | 批量撤销，null = 全部 |
| `pending(session)` | `List<SteerMessage>` | 否 | 管理 | 待生效快照（副本） |
| `activeRunId(session)` | `String` | 否 | 管理 | 当前轮次，无则 null |
| `getMaxBoxSize()` / `getMaxTextLength()` / `getMaxIdLength()` | `int` | — | — | 生效的容量约束 |
| `SteerProtocol.textOf(message)` | `String` | — | — | 识别插话消息并还原原话 |

---

## 九、注意事项

1. **"改"的窗口比"停"窄**：`runId` 在拦截器 `onAgentStart` 写入。任务刚受理、尚未开轮时投递会得到 `NOT_RUNNING`（不带 `runId`）/ `RUN_CHANGED`（带 `runId`），这不是错误。
2. **steer 与 interrupt 互斥**：中断后只能排队，不能再插话。
3. **首轮不注入**：插话最早在第二个推理回合生效；任务如果一回合就结束，插话会以 `RUN_ENDED` 转排队，作为下一个任务执行。
4. **零持久化**：插话状态只在 `session.attrs()`，不进快照、不经 `session.addMessage`、不写消息历史，因此不占 `sessionWindowSize`，也不会在后续任务里被当作初心重新入场；进程重启后未注入的插话丢失（orphans 也在内存）。刷新页面后插话气泡即消失是刻意取舍：展示层应在收到 `steer_applied` 时把插话就地渲染进当前回复气泡。
5. **委派任务不挂**：`task` / `multitask` 的子代理跑在临时会话上，没有插话语义；面板或 `@` 选中的子代理属于顶层请求，可以插话。
6. **orphans 不会执行**：未装 queue 或 queue 拒收时，残留只暂存、只重试转排队，永远不注入。需要"一定执行"请装配 queue。
7. **`pending` 是快照**：读到的下一刻就可能被推理线程消费，只能作 UI 依据，不能保证"一定还能撤销"。
8. **未挂接的门面**：直接 `new SteerExtension()` 而不加入引擎时，忙判定一律视为忙（只按轮次判定），且没有积压能力，适合单元测试。
9. **装配在构建期**：推荐 `Builder.extensionAdd(new SteerExtension())`（同一实例重复添加只算一次）。运行期的 `engine.addExtension(...)`（按引用查重）/ `addExtensionIfAbsent(...)`（按类型查重，已有同类型实例时返回已挂载的那个）仅 `runtimeMutable` 时可用（STANDALONE 缺省）；**SERVER 模式 build 之后调用抛 `IllegalStateException`**。
10. **更多跨插件的坑**：命令注册、HITL 续流、中断方式等引擎侧的常见坑见 [core README §十一](../solon-ai-harness-core/README.md)。
