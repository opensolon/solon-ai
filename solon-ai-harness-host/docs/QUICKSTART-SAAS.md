# SaaS 快速上手：HarnessHost

本文只讲宿主骨架 `HarnessHost`（L0）：用它搭一个多租户服务端，只需要实现两个输出回调。
隔离模型、作用域、多节点、存储等细节见 [SAAS.md](SAAS.md)；术语见 [CONCEPTS.md](CONCEPTS.md)。

> 本文的代码与 `src/test/java/features/ai/harness/all/QuickstartSampleTest.java` 保持一致，并随构建编译运行。
> `HarnessHost` 及相关类型标注 `@Preview("4.2.0")`，在 4.2.x 内可能还有调整。

---

## 一、三层 API，先选对层

| 层 | 入口 | 适用 |
|---|---|---|
| **L0 宿主骨架** | `HarnessHost` + `HostSink` | 框架拥有输出通道：SaaS、IM 网关、普通 Web 服务（本文） |
| L1 受理 API | `engine.handle` + `InputOutcome` + `RunTicket` | 自带终态门、要自定义「回执与流的先后」或序号回放的宿主（CLI、桌面端），见 README §四 |
| L2 扩展 SPI | `HarnessExtension`、`InputBacklog`、`RunDispatch` | 写插件 |

用 L0 时，下面这些都由框架代办，宿主不需要知道：`RunTicket`、`afterRun`、`drainLoop`、`discard`、
派发事务的回复路由、五种状态口径、四个停流入口与 `NotOwnerException`。

---

## 二、依赖

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-harness</artifactId>   <!-- 聚合包：只做引用，核心 + 四个会话插件 + solon-ai-harness-host -->
    <version>${solon-ai.version}</version>
</dependency>
```

`HarnessHost` 等类型在独立模块 `solon-ai-harness-host`（包 `org.noear.solon.ai.harness.host`）。不想要聚合包时，直接引 `solon-ai-harness-host` 也一样：它依赖核心与四个插件。模块说明见 [solon-ai-harness-host/README.md](../solon-ai-harness-host/README.md)。

---

## 三、四步搭起来

### 3.1 构建引擎（每个租户一个）

插件就是普通的扩展，在**构建期**用 `extensionAdd` 挂上（SERVER 模式下 build 之后不能再挂）。
不要哪个删哪一行，自己写的扩展也是同一种写法：

```java
HarnessEngine buildEngine(String tenantId) {
    return HarnessEngine.of(workspaceOf(tenantId), ".solon/")
            .profile(HarnessProfile.SERVER)
            .stateSource(FileMountSource.of(stateDirOf(tenantId)))      // 必须在工作区之外
            .sessionProvider(sessionProviderOf(tenantId))
            .modelAdd(modelsOf(tenantId))
            .defaultModel(defaultModelOf(tenantId))
            .toolsAdd(ToolName.TOOL_ALL_PUBLIC)
            .scopeResolver(InputScopeResolver.byPrincipal(p -> authz.scopeOf(tenantId, p)))
            .extensionAdd(QueueExtension.builder().maxSize(20).build())  // 会话忙时排队（不装则直接拒绝）
            .extensionAdd(new SteerExtension())                          // 运行中插话
            .extensionAdd(GoalExtension.builder().roundConcurrency(2).build())           // 按需
            .extensionAdd(LoopExtension.builder().store("loop").quota(sid -> 20).build()) // 按需；存在状态来源的 loop/ 下
            .build();
}
```

goal 的重启恢复不用自己调：`HarnessHost` 首次取到引擎时先登记后台执行器，再 `goal.recover()`（见 §七）。

SERVER 模式还要求：启动时 `SandboxBootstrap.initOnce(...)` 一次；`stateSource` 显式配置且不在工作区内；
至少一个模型。详见 SAAS.md §2.1。

### 3.2 实现输出通道

`HostSink` 的**每个方法都有缺省实现**（不传 sink 也能跑通一轮，事件只记日志）。接入时通常只需覆盖两个投递出口：

```java
public class MySink implements HostSink {
    @Override
    public void reply(InputOrigin origin, String text) {        // 命令回执、排队提示、拒绝提示
        channels.send(origin, text);
    }

    @Override
    public void event(HostRun run, AgentEvent event) {          // 流事件，投递给 run.getOrigin()
        channels.push(run.getOrigin(), event);
    }

    // 可选：运行类
    // runStarted(run) / runFinished(run, error)   每个 started 恰好配一个 finished
    // hitl(origin, cards)                         有待审批的工具调用
    // 可选：结构化事实（文案、要不要提示用户，由你决定）
    // rejected(origin, reason, detail)            未受理（reason 是 RejectReason 枚举）
    // deferred(origin, position)                  会话忙，已排队
    // hitlExpired(origin)                         审批卡已失效
    // notOwner(origin, owner)                     多节点：会话由其它节点持有
}
```

### 3.3 组装骨架

引擎池 `HarnessEnginePool` 与 `HarnessHost` 同在 host 模块（`org.noear.solon.ai.harness.host`）：

```java
HarnessEnginePool pool = HarnessEnginePool.builder(this::buildEngine)
        .idleTtl(Duration.ofMinutes(30))
        .maxEngines(500)
        .build();

HarnessHost host = HarnessHost.builder()
        .engines(pool::get)                       // 租户 → 引擎
        .sink(new MySink())                       // 可选：不传则用缺省 sink（只记日志）
        .build();

host.warmup(tenantsWithBackgroundWork());         // 重启后预热有 goal / loop 的租户（经 host，不要自己取引擎）
```

### 3.4 接入控制器

```java
// 使用态：用户发消息（含 /queue、/goal 等斜杠命令）
@Post 
@Mapping("/chat/{tenant}/{sid}")
public String chat(String tenant, String sid, String text, Context ctx) {
    SubmitResult r = host.submit(tenant, sid, text, InputOrigin.of("web", userOf(ctx), null, null));
    ctx.status(r.httpStatus());                  // 内容已经经 sink 发出，这里只回状态
    return r.getKind().name();
}

// 审批（协议字符串 → 枚举，脏值直接报错）
@Post 
@Mapping("/chat/{tenant}/{sid}/hitl")
public String decide(String tenant, String sid, String callId, String action, Context ctx) {
    SubmitResult r = host.decide(tenant, sid, callId, HITLAction.of(action), null, InputOrigin.channel("web"));
    ctx.status(r.httpStatus());
    return r.getKind().name();
}

// 停止
@Post 
@Mapping("/chat/{tenant}/{sid}/stop")
public Result stop(String tenant, String sid) {
    HarnessResult<Boolean> r = host.interrupt(tenant, sid, InterruptMode.INTERRUPT_ALL);
    return r.isOk() ? Result.succeed(r.getValue()) : Result.failure(r.getCode().name());
}
```

创建会话用 `openSession`：建会话与写入会话主体一次完成（主体是作用域解析的依据，设置后不可改）。

```java
// 登录后 / 新建对话时
host.openSession(tenant, newSessionId, tenant + "/" + userId);

// 需要删除会话时：判忙 → 通知扩展 → 删存储，顺序由框架保证
if (host.closeSession(tenant, sid, () -> store.delete(sid)) == false) {
    return Result.failure(409, "session is busy");
}
```

---

## 四、使用态：`submit` 的结果

要给用户看的内容**已经经 sink 发出**，`SubmitResult` 只用来映射状态码：

| Kind | 发生了什么 | sink 收到 | `httpStatus()` |
|---|---|---|---|
| `STARTED` | 已开流 | `runStarted` →（命令回执）→ `event`* → `hitl`（若挂起）→ `runFinished` | 200 |
| `REPLY` | 命令已执行，只有回执 | `reply` | 200 |
| `DEFERRED` | 会话忙，已排队；空闲后自动续发 | `deferred(origin, position)`，续发时按**这条消息自己的 origin** 再开流 | 202 |
| `REJECTED` | 未受理，原因见 `getRejectReason()`（`InputOutcome.RejectReason` 枚举，线上值 `.code()`） | `rejected(origin, reason, detail)`；`NOT_OWNER` 时为 `notOwner(origin, owner)`，持有者也从 `getOwner()` 读 | 404 / 307 / 403 / 503 / 409 |
| `HITL` | 审批已记录但本批未齐备（或卡已失效） | `hitl(cards)` 或 `hitlExpired(origin)` | 200 |
| `IGNORED` | 空输入 | — | 200 |

拒绝原因码：`session_not_found`（会话不存在，骨架只查不建）、`not_owner`、`scope_unavailable`、`busy`、
`hitl_pending`、`attachments_not_deferrable`、`backlog_unavailable`、`scope_not_deferrable`。
`engine_closed`（引擎刚被池回收）由骨架换新引擎自动重试一次，一般不会出现。

需要补充输入参数（附件、显式模型 / 子代理、宿主域属性）时：

```java
host.submit(tenant, sid, text, origin, in -> in.attachments(files).selectedModel("gpt-4o").attr("effort", "high"));
```

---

## 五、管理态：`SessionAdmin`

**鉴权在控制器 / 拦截器层做，框架不参与**（它拿不到角色、租户成员关系、限流等上下文；给了钩子也只会是一条重复路径，
而且默认值只能是 fail-open）。鉴权通过后，用 `host.admin(tenant)` 操作会话。只有一个入口 `call`：
取扩展 → 调它自己的门面 → 给出结果码。queue / steer / goal / loop 和你自己的扩展都是同一种写法：

```java
// 先鉴权（@CheckRole / 自己的 authz）
authz.require("console.queue.enqueue", tenant, principal, sid);

SessionAdmin admin = host.admin(tenant);

HarnessResult<List<LoopTask>> tasks = admin.call(sid, LoopExtension.class,
        (loop, s) -> HarnessResult.ok(loop.list(sid)));

// 插件写门面本身就返回 HarnessResult，直接交出去即可
HarnessResult<Integer> r = admin.call(sid, QueueExtension.class,
        (queue, s) -> queue.enqueue(s, text, InputOrigin.channel("console")));
switch (r.getCode()) {
    case OK:        return Result.succeed(r.getValue());          // 队列位置
    case NOT_OWNER: return Result.failure(r.httpStatus(), r.getOwner()); // 307：网关转发
    default:        return Result.failure(r.httpStatus(), r.getDetail()); // 404 / 403 / 409 / 429 / 503 / 400
}

// 或直接（粗码自带建议状态码，不必自己维护映射表）
return r.isOk() ? Result.succeed(r.getValue())
                : Result.failure(r.httpStatus(), r.getDetail());

admin.interrupt(sid, InterruptMode.INTERRUPT_ALL);
```

`call` 按同一顺序检查：扩展是否装配（`DISABLED`）→ 会话归属（`NOT_OWNER`）→ 会话是否存在（`NOT_FOUND`，只查不建）→ 执行。扩展抛的 `NotOwnerException` 译为 `NOT_OWNER`，
`IllegalArgumentException` 译为 `INVALID`，`IllegalStateException` 译为 `CONFLICT`。成功之后自动续发积压（入队、置顶后会话空闲就立即执行）。

| Code | 含义 | `httpStatus()` |
|---|---|---|
| `OK` | 成功，`getValue()` 为结果 | 200 |
| `NOT_FOUND` | 会话、队列项或任务不存在 | 404 |
| `NOT_OWNER` | 会话由其它节点持有，`getOwner()` 为持有者 | 307 |
| `DISABLED` | 插件未装配或已停用 | 503 |
| `FULL` | 队列满、loop 任务数或会话配额达上限 | 429 |
| `INVALID` | 参数不合法（重试同样的请求不会成功），`getDetail()` 为原因 | 400 |
| `CONFLICT` | 参数合法但当前状态不允许（goal 已是终态、轮次已切换、ID 重复等） | 409 |
| `FORBIDDEN` | 框架不再产生；鉴权拒绝由宿主在控制器层返回（码表里保留，便于统一） | 403 |

状态码由粗码自带（每个 `Code` 值上就有，`r.httpStatus()` 是同一份的转发），宿主不用另维护一张表；
`NOT_OWNER` 用 307 而不是 302，是为了让网关跳转后仍用原方法与原请求体。

以上是粗码，决定怎么处置；失败时还可能带细分码 `getReason()`（可空，大写下划线，如 steer 的 `BOX_FULL`），判定用 `hasReason(...)`；透传给前端的应答码用 `getReasonOrCode()`（有细分码返回细分码，否则粗码名）。

---

## 六、停止：`InterruptMode`

| InterruptMode | 行为 |
|---|---|
| `INTERRUPT_ALL` | 用户点「停止」：先清空排队任务，再停当前流，并在历史里写取消消息 |
| `CANCEL_STREAM` | 只停当前流，保留排队任务（结束后续发下一条），不写历史 |
| `PREEMPT_STREAM` | 为新一轮腾位：只停流，不推进取消代数（一般用不到） |

---

## 七、goal / loop 后台轮次

`HarnessHost` 首次取到某个引擎时，先为它的 goal / loop 登记执行器（通道名 `host`），再恢复 goal 调度：后台轮次同样走
`runStarted` / `runFinished`（`run.isProgram()` 为 true，`run.getOrigin()` 为 `channel("goal")` / `channel("loop")`，
最终文本在 `run.getResultText()`），结束后自动续发积压。后台轮次同步执行，不推送流事件。

不想接入时 `HarnessHost.builder().programRuns(false)`，沿用插件自带的缺省执行器。

---

## 八、线程与上下文

| 配置 | 说明 |
|---|---|
| `executor(Executor)` | 开流动作在哪里执行，缺省 Reactor `boundedElastic`；Agent 管道的同步部分在这里跑 |
| `wrap(UnaryOperator<Runnable>)` | 包装开流动作：注入 MDC、日志作用域、租户上下文 |

sink 回调可能在任意线程上执行，实现需线程安全；回调抛出的异常只记日志，不影响运行。

---

## 九、上线检查清单（L0）

- [ ] 引擎：`profile(SERVER)`、`stateSource` 在工作区之外、`SandboxBootstrap.initOnce`、插件经 `Builder.extensionAdd` 在构建期挂上
- [ ] 权限：用 `host.openSession` 建会话并写入主体；`scopeResolver` 用 `byPrincipal` 并做短期缓存（SERVER 下缺失会在 `build()` 报错）
- [ ] 骨架：`engines(pool::get)`；预热用 `host.warmup`；sink 线程安全
- [ ] 管理态：鉴权放在控制器 / 拦截器层；按 `HarnessResult.Code` 映射状态码
- [ ] 多节点：`SessionOwnership.namespaced(tenantId, impl)`；实现 `HostSink.notOwner` 与 `NOT_OWNER` 的网关转发
- [ ] 删除会话：用 `host.closeSession(tenant, sid, storageCleanup)`（判忙 → 通知扩展 → 删存储，顺序由框架保证）
