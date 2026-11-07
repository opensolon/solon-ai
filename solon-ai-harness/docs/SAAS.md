# 用 solon-ai-harness 开发 SaaS 服务

本文面向把 harness 部署成**多租户服务端**的开发者（Web API、IM 网关、企业 Agent 平台）。单机 CLI / 桌面 / 单用户 Web 请看 [README.md](README.md)，本文只讲服务端额外要做的事。

> **先看 [QUICKSTART-SAAS.md](QUICKSTART-SAAS.md)**：用宿主骨架 `HarnessHost` 搭服务端，只需实现两个输出回调；术语见 [CONCEPTS.md](CONCEPTS.md)。
> 本文是参考手册：隔离模型、作用域、多节点、存储与容量，以及不用骨架、直连 `engine.handle`（L1）时的写法。

> 文中 `TenantStore`、`Authz`、`RedisOwnership`、`ImHost` 等是**宿主自己的类**（示意），harness 不提供；
> `HarnessEngine`、`HarnessEnginePool`、`InputScope`、`SessionOwnership` 等才是 harness 的 API。
> 标 `@Preview("4.2.0")` 的类型（引擎池、作用域、会话归属）在 4.2.x 内可能还有调整。
>
> 术语：**input** 是一次 `handle` 的输入（`HarnessInput` / `InputScope`），**run** 是受理后开出的一条流（`RunTicket` / `RunDispatch`），
> **step** 是 ReAct 内部的一步。文中「一轮」指 run。

---

## 一、先定隔离模型

harness 的隔离单位有三层，先想清楚每一层对应你业务里的什么：

| 层 | harness 里的载体 | 隔离什么 | 典型对应 |
|---|---|---|---|
| 引擎 | `HarnessEngine`（绑定一个工作区 + 一个状态来源） | 文件系统、MCP / LSP 连接、模型配置、扩展实例、全局子代理 | 租户、或租户下的项目 |
| 会话 | `AgentSession`（`sessionProvider` 提供） | 对话历史、会话主体、HITL 挂起、会话级子代理、goal / queue / steer / loop 状态 | 一个聊天窗口、一个 IM 会话 |
| 输入 | `InputScope`（每次受理解析一次） | 这次输入能用哪些工具、技能、MCP、命令、模型 | 用户 / 角色的权限 |

推荐的组合：

- **租户之间需要文件隔离** → 每个租户（或每个租户项目）一个引擎，用 `HarnessEnginePool` 管理；
- **同一租户内不同用户 / 角色** → 共用引擎，用会话主体 + `InputScope` 收窄权限；
- **不要**让多个租户共用一个引擎，再试图用作用域隔离文件：作用域约束工具调用，但工作区、技能挂载、MCP 连接都是引擎级共享的。

> 经过评估，「单例引擎 + 请求时传工作区」的方案没有采用：单个引擎的固定开销偏轻（空闲时没有线程，调度器用的是 JVM 单例），
> 而单例会让租户隔离从结构保证退化为开发纪律。

---

## 二、最小可运行骨架

```java
public class SaasHarness {
    private final HarnessEnginePool pool;
    private final ExecutorService taskExecutor = new ThreadPoolExecutor(
            0, 256, 60, TimeUnit.SECONDS, new SynchronousQueue<>());   // 全进程共享的子代理执行器（可选）

    public SaasHarness(TenantStore tenants, Authz authz, SessionOwnership redisOwnership) {
        // ① 进程级 OS 沙盒：SERVER 模式下引擎不自建，宿主启动时初始化一次（网络白名单是进程级的）
        SandboxBootstrap.initOnce(sandboxConfig());

        // ② 引擎池：每个租户一个引擎，懒建（在锁外构建，慢租户不阻塞其它租户）、空闲回收
        this.pool = HarnessEnginePool.builder(tenantId -> buildEngine(tenantId, tenants, authz, redisOwnership))
                .idleTtl(Duration.ofMinutes(30))
                .maxEngines(500)
                .whenFull(HarnessEnginePool.WhenFull.WAIT, Duration.ofSeconds(5))   // 满额时等静默引擎可回收，缺省 REJECT
                .build();

        // ③ 重启恢复：只为「有后台工作的租户」预热（清单由宿主维护，如记录 goal / loop 的租户）
        pool.warmup(tenants.tenantsWithBackgroundWork());
    }

    private HarnessEngine buildEngine(String tenantId, TenantStore tenants, Authz authz, SessionOwnership redisOwnership) {
        Path ws    = tenants.workspaceOf(tenantId);   // 如 /data/ws/<tenant>
        Path state = tenants.stateDirOf(tenantId);    // 如 /data/state/<tenant>（必须在工作区之外）

        MountSource stateSrc = FileMountSource.of(state);              // 或 db / http 实现的 MountSource
        GoalExtension goal = GoalExtension.builder().roundConcurrency(2).build();

        HarnessEngine engine = HarnessEngine.of(ws.toString(), ".solon/")
                .profile(HarnessProfile.SERVER)                        // 保守缺省 + 构建期校验
                .stateSource(stateSrc)
                .sessionProvider(tenants.sessionProviderOf(tenantId))  // 会话持久化（见 §五）
                .modelAdd(tenants.modelsOf(tenantId))
                .defaultModel(tenants.defaultModelOf(tenantId))
                .toolsAdd(ToolName.TOOL_ALL_PUBLIC)                    // 引擎级上限；按用户再用作用域收窄
                .scopeResolver(InputScopeResolver.byPrincipal(p -> authz.scopeOf(tenantId, p)))
                .cwdPolicy(cwd -> Paths.get(cwd).startsWith(ws))       // 引擎先取真实路径再交给策略
                .sessionOwnership(SessionOwnership.namespaced(tenantId, redisOwnership))  // 多节点才需要；单机省略
                .taskExecutor(taskExecutor)                            // 可选：共享执行器，引擎 shutdown 不关闭它
                // ④ 会话插件（按需）：SERVER 下 build 之后引擎全局配置已锁定，插件必须在构建期挂上
                //    （build 之后再 addExtensionIfAbsent 会抛 IllegalStateException）
                .extensionAdd(QueueExtension.builder().maxSize(20).build())
                .extensionAdd(new SteerExtension())
                .extensionAdd(goal)                                    // SERVER 下缺省不注册内置构建验证器、且 FAIL_CLOSED
                .extensionAdd(LoopExtension.builder()
                        .store(stateSrc, "loop")                       // 与引擎共用状态来源；按会话分存，在缺省受保护名单内
                        .maxTasks(200).quota(sid -> 20)
                        .build())
                .build();

        goal.recover();                                                // build 之后：按 goal/active/ 索引恢复调度
        return engine;
    }

    public HarnessEngine engineOf(String tenantId) {
        return pool.get(tenantId);
    }
}
```

插件就是普通扩展：不要哪个删哪一行，宿主自己的扩展也是同一种 `extensionAdd` 写法（同一实例重复添加只算一次）。
loop 不想持有 `stateSrc` 变量时可写 `.store("loop")`，挂接时取引擎的状态来源。
用 `HarnessHost`（L0）时不必自己调 `goal.recover()`：骨架首次取到引擎时先登记后台执行器再恢复，见 [QUICKSTART-SAAS.md](QUICKSTART-SAAS.md)。

`ToolName.TOOL_ALL_PUBLIC` 即 `*`（公有工具组，不含 `task` / `generate` / `mcp` / `hitl` / `memory`）；关键是**引擎级给上限、作用域再收窄**。需要 MCP 时显式加 `ToolName.TOOL_MCP` 一类的工具。

创建会话时写入会话主体（见 §三）：

```java
AgentSession session = engine.getSession(newSessionId);
engine.setSessionPrincipal(session, tenantId + "/" + userId);    // 随快照持久化；设置后不能改成别的值
```

### 2.1 `profile(SERVER)` 改了什么

预设在 `build()` 时合成，规则是**显式值 > 预设值 > 默认值**，与调用顺序无关：

| 配置 | CODING（缺省） | SERVER |
|---|---|---|
| `sandboxEnabled` / `sandboxSystemRestrict` | `true` / `true` | `true` / `true` |
| `sandboxAllowUserHome` | `false` | `false` |
| `sandboxSelfBootstrap` | `true`（引擎自行初始化沙盒） | `false`（宿主 `SandboxBootstrap.initOnce`，未初始化时拒绝执行命令） |
| `bashAsyncEnabled` | `false` | `false` |
| `memorySessionIsolation` | `false` | `true`（长期记忆按会话隔离） |
| `runtimeMutable` | `true` | `false`（运行期改引擎级全局配置抛 `IllegalStateException`；命令与子代理注册表封口） |
| `scopeRequired` | `false` | `true`（解析不到作用域就拒绝受理） |
| `agentPersistPolicy` | 允许 | 拒绝（`generate(saveToFile=true)` 不能创建全局子代理） |
| goal 内置验证器 / 验证策略 | 注册 / `FAIL_OPEN` | 不注册 / `FAIL_CLOSED` |

`build()` 在 SERVER 模式下额外校验三件事，不满足就抛 `IllegalStateException`：

1. 必须**显式**配置 `stateSource`，本地来源时不能落在工作区内（否则模型的文件工具能读写 harness 状态）；
2. 至少有一个模型；
3. `defaultModel` 若配置了，必须存在。

沙盒被显式放宽（`sandboxEnabled=false` 或 `sandboxSystemRestrict=false`）时只打 WARN，不会拒绝构建。

---

## 三、会话主体与输入级作用域：`InputScope`

### 3.1 会话主体

会话主体回答「这个会话属于谁」。goal / loop / queue 的程序输入没有「当前请求」，解析器只能从会话本身拿到用户，所以主体必须随会话持久化：

| API | 说明 |
|---|---|
| `engine.setSessionPrincipal(session, principal)` | 创建会话时写入；同值重复设置是空操作，设置**不同值**抛 `SecurityException`（防止把 A 的会话交给 B） |
| `engine.getSessionPrincipal(session)` | 未设置为 null |
| `engine.rebindSessionPrincipal(session, principal)` | 管理操作（如账号合并），不受 `runtimeMutable` 约束，调用方自行鉴权 |
| `InputScopeResolver.byPrincipal(principal -> scope)` | 读出主体再查授权；会话没有主体时返回 null（`scopeRequired` 下即拒绝） |

不必再把租户或渠道编码进 sessionId。

### 3.2 作用域

作用域回答「这次输入能用什么」。它是**强制边界**：在引擎配置的基础上只能收窄，工具执行前由守卫拦截，委派子代理继承同一作用域。

```java
public class Authz {
    public InputScope scopeOf(String tenantId, String principal) {
        Role role = roleOf(tenantId, principal);                 // 建议按主体做 5–30 秒的短期缓存（见 3.3）
        if (role == null) return null;                           // 无授权：scopeRequired 下拒绝受理
        return InputScope.builder(principal)                     // principal：审计、配额、日志用
                .tools(role.tools())                             // 逻辑名（bash / read / skill / mcp ...）或分组（pi / * / **）
                .skills(role.skills())                           // null = 不限；空集合 = 全禁
                .mcps(role.mcps())
                .commands(role.commands())                       // 可用的命令名（不含前缀）
                .build();
    }
}
```

语义要点：

| 维度 | `null` | 空集合 | 名单 |
|---|---|---|---|
| `tools` | 不限 | 全禁（时钟工具除外） | 只允许名单内的内置工具 + MCP 工具；宿主自注册的工具也必须在名单内 |
| `skills` | 不限 | 全禁 | 约束技能工具（含 `skillread`）；**不约束**文件工具与 bash 对技能挂载点的访问 |
| `mcps` | 不限 | 全禁 | 强制边界（`call_tool` 中转也受约束） |
| `commands` | 不限 | 全禁 | 名单外的命令视同不存在（`/help` 也看不到） |

另有两个不能序列化的维度：`chatModel(...)`（租户自带 Key 的模型实例）与 `attr(...)`（给宿主自己的拦截器看）。带这两个维度的**显式**作用域不能进积压（见 3.3）。

- **扩展自带的工具不受 `tools` 名单约束**（经 `HarnessExtension#getToolNames()` 声明，如 `goal_get` / `goal_update`、`loop_*`），否则 goal 会因名单里没写 `goal_update` 而永远无法完成。不希望某类用户使用某扩展时，不要给对应引擎挂该扩展。
- **显式优先**：输入带了 `HarnessInput.scope(...)` 时不调用解析器；扩展在 `onInput` 里设置的作用域同样按显式处理。
- **`scopeRequired=true`**（SERVER 缺省）：解析器返回 `null` 或抛异常时，`handle` 返回 `REJECTED` + `RejectReason.SCOPE_UNAVAILABLE`（不 fail-open）。

### 3.3 解析时机与积压

解析器在 `handle` 第 0 步调用：**会话归属检查之后、会话锁之外**。所以：

- 非持有节点不会调用解析器（先被 `not_owner` 拒绝）；
- 解析失败的输入**不占积压名额**；
- 解析器有网络 IO 也不会阻塞会话锁；
- 解析器此时看不到扩展 `onInput` 的副作用，只应依赖会话与输入本身。

输入在会话忙时进积压，续发时按下表处理：

| 输入携带的作用域 | 入积压时 | 续发时 |
|---|---|---|
| 解析器解析得到的 | 只做预检，结果不保存 | 重新解析，权限变更立即生效 |
| 显式作用域，只含名单类维度 | 随积压保存 | 还原后与最新解析结果**取交集**（只会变窄；主体不一致直接拒绝） |
| 显式作用域，含 `chatModel` 或 `attrs` | `REJECTED(scope_not_deferrable)`，不占名额 | — |

积压同时保存 `selectedSkill` / `selectedMcp` 视野与 `terminal` 标记。忙时的输入会调用两次解析器（入积压预检一次、续发一次），授权查询较重时请做短期缓存。

---

## 四、多节点：会话归属 `SessionOwnership`

会话的运行态（流句柄、受理占位、插话邮箱、队列锁）都在进程内存里，**同一时刻只能由一个节点执行一个会话**。有两种做法：

| 方式 | 做法 | 适用 |
|---|---|---|
| 粘滞路由 | 网关按 `sessionId` 一致性哈希路由到固定节点 | 简单；但这是**部署前提**，要写进运维文档 |
| 单写者租约 | 实现 `SessionOwnership`（如 Redis `SET key node NX PX lease` + 节点心跳续期） | 节点可随时扩缩容 |

```java
public class RedisOwnership implements SessionOwnership {
    // 每次受理、每个写门面、每次后台派发都会调用：必须廉价（本地缓存已持有集合），对已持有的会话返回 true
    public boolean tryAcquire(String sessionId) { ... }
    public String ownerOf(String sessionId) { ... }    // 持有者节点标识，网关据此转发
    public void release(String sessionId) { ... }      // removeSession / shutdown 时调用；对未持有的会话应忽略
}
```

- **租约键带租户前缀**：会话 ID 不保证全局唯一（CLI 的会话就叫 `cli`）。引擎池里多个租户共用一个实现时，用 `SessionOwnership.namespaced(tenantId, delegate)` 包一层（幂等，同一命名空间不会重复包装）。
- 引擎的统一检查入口是 `engine.checkOwner(sessionId)`（返回 `OwnerCheck`：`isOwned()` / `getOwner()`）与 `engine.requireOwner(sessionId)`（不持有抛 `NotOwnerException`）。在没有任何节点持有的会话上调用，本节点成为持有者。
- `handle` 第 0 步检查归属，拿不到返回 `REJECTED` + `RejectReason.NOT_OWNER`，`getRejectDetail()` 为持有者标识，由网关转发。
- **写门面同样先查归属**，非持有节点上：

| 门面 | 失败方式 |
|---|---|
| queue / goal / loop 的全部写门面（`enqueue`、`cancelItem`、`create`、`pause`、`schedule` 等） | 返回 `HarnessResult` 的 `NOT_OWNER`（`getOwner()` 为持有者；与「不存在」的 `NOT_FOUND` 可区分） |
| `steer.steer` / `steerOnRun` / `cancelOnRun` / `cancelPending` | 同样返回 `HarnessResult` 的 `NOT_OWNER`（所有插件门面统一返回 `HarnessResult`，不带细分码） |
| `InterruptMode.INTERRUPT` | 返回 false；`engine.prepareInterrupt(session).isNotOwner()` 可与「没有在跑」区分 |

- **后台派发**：goal / loop 调度在**加载会话之前**先查归属，非持有节点不会把会话加载进内存。goal 没拿到租约时按 `GoalExtension.builder().ownerProbeIntervalMs(...)`（缺省 15 秒）退避，持有节点宕机、租约过期后在一个探测间隔内接管；建议探测间隔不超过租期的一半。
- **租约由实现自行保活**（节点心跳批量续期、Redisson watchdog 等），只应在节点失联后过期：一次运行可能远长于租期（goal 长轮次、慢工具），引擎不在运行中续约。
- 引擎在 `removeSession` 时释放该会话，在 `shutdown` 时释放本引擎认领过的全部会话。

---

## 五、状态与存储

| 数据 | 存在哪 | 由谁决定 | 多节点注意 |
|---|---|---|---|
| 对话历史、会话快照（含 queue 投影、goal 投影、会话主体、会话 cwd、会话级子代理） | 会话实现 | `sessionProvider` | 要能跨节点读到（共享存储），且只由持有节点写 |
| goal 活跃索引 | `stateSource` 的 `goal/active/`（每会话一个空标记文件） | 引擎 | 多节点共用来源时不会互相覆盖 |
| loop 任务 | `store(source, dir)` 指定，每会话一个文件 `<dir>/<encode(sid)>.json` | 宿主 | 建议传构建引擎时用的同一个 `stateSource` |
| 全局子代理（`generate(saveToFile=true)`） | 工作区代理目录 `<harnessAgents>/<name>.md` | 引擎 | SERVER 缺省禁止创建 |
| 长期记忆 | `memoryProvider` | 宿主 | 缓存与索引在进程内存，其它节点写入后需重建实例才能检索到 |
| steer 邮箱、受理占位、运行句柄、派发事务（`session.run.dispatch`） | 进程内存 | — | 不落盘；重启后未注入的插话丢失 |
| 工作区文件 | 引擎工作区（本地目录） | 宿主 | 模型的文件工具、bash、LSP 都直接操作本地目录；远程工作区需要宿主自己做同步 |

会话提供者的契约：`getSession(id, createIfAbsent=false)` 在会话不存在时必须返回 `null` 且无副作用（不建实例、不写存储、不放缓存）。生产实现应缓存实例，并设上限与淘汰策略。

### 5.1 受保护状态路径

`protectedStatePaths`（缺省 `sessions, memory, channels, tasks.json, loop, goal`，相对 harnessHome）里的路径，模型的文件工具读写会被拒绝，OS 沙盒也会同时 denyRead / denyWrite。
loop 用其它目录名时，请把它加进这个名单。

### 5.2 会话工作目录

`engine.setSessionCwd(session, cwd)` 受 `cwdPolicy` 约束，值随会话快照持久化（重启后 goal 的后台轮次仍在原目录执行）。终端工具在**使用**工作目录时再按真实路径（`toRealPath`）校验一次，
所以请求级 `toolContext` 或直接写会话属性都绕不过策略，指向租户根目录外的符号链接也会被拒绝。

### 5.3 运行期创建的子代理

模型通过 `generate` 工具创建子代理时按 `saveToFile` 分级：`false`（缺省）只在本会话可见，存在会话快照里，每会话最多 16 个；`true` 写入工作区代理目录、本引擎所有会话可见，受 `agentPersistPolicy` 约束（SERVER 缺省拒绝）。
两级都拒绝保留名（`main`、`general` 及内置挂载的代理名），同名不覆盖。只想对管理员开放全局创建：

```java
.agentPersistPolicy((session, def) -> admins.contains(engine.getSessionPrincipal(session)))
```

---

## 六、宿主接入：使用态与管理态

**推荐用宿主骨架**：`HarnessHost.submit / decide / stop` 与 `host.admin(tenant, principal)` 已代办本节的全部约定
（只查不建会话、`RunTicket`、`afterRun` 续发、`isOccupied` 口径、派发轮次的回复路由、开流前失败的 `discard`、
`engine_closed` 重试、`not_owner` 转发、管理门面的统一结果码与鉴权、入队后自动续发），写法见 [QUICKSTART-SAAS.md](QUICKSTART-SAAS.md)。

下面是**不用骨架**、直连 `engine.handle`（L1）时的写法，适用于自带终态门或需要自定义输出时序的宿主。

### 6.1 使用态（用户发消息）

所有用户输入都走唯一入口 `engine.handle(HarnessInput)`，按六种结局分派：

```java
public void onMessage(String tenantId, String sessionId, String text, InputOrigin origin) {
    HarnessEngine engine = saas.engineOf(tenantId);
    AgentSession session = engine.getSessionOrNull(sessionId);
    if (session == null) { reply(origin, "会话不存在"); return; }     // 不要用 getSession：它会凭空新建

    InputOutcome outcome = engine.handle(HarnessInput.of(session, text).origin(origin));
    switch (outcome.getKind()) {
        case TASK:
            if (outcome.hasReply()) reply(origin, outcome.getCommandOutput());
            RunTicket ticket = RunTicket.begin(session);               // 订阅之前领取凭据（取消基准 + 派发事务）
            outcome.afterRun(() -> drain(engine, session));            // 续发积压必须挂在 afterRun，不要写在 doFinally
            outcome.stream()
                    .doFinally(s -> ticket.settle(true))               // 幂等；非程序派发时是空操作
                    .subscribe(e -> push(origin, e));                  // 订阅时机由宿主定；开流前出错要 outcome.discard()
            break;
        case REPLY:    reply(origin, outcome.getCommandOutput()); drain(engine, session); break;
        case DEFERRED: reply(origin, "已排队（第 " + outcome.getBacklogPosition() + " 位）"); break;
        case REJECTED: onRejected(origin, outcome); break;
        case HITL:
        case IGNORED:  drain(engine, session); break;
    }
}

private void onRejected(InputOrigin origin, InputOutcome o) {
    switch (o.getRejectReason()) {
        case NOT_OWNER:         forwardTo(o.getRejectDetail()); break;  // 转发给持有节点
        case SCOPE_UNAVAILABLE: reply(origin, "无权限或授权服务不可用"); break;
        case ENGINE_CLOSED:     retryOnFreshEngine(); break;            // 引擎刚被池回收：重新 pool.get
        default:                reply(origin, o.getRejectDetail());     // BUSY / HITL_PENDING / 不可排队 / 积压已满（线上值用 getRejectReason().code()）
    }
}

private void drain(HarnessEngine engine, AgentSession session) {
    engine.getExtension(QueueExtension.class).ifPresent(q ->
            q.drainLoop(session, s -> !engine.status(s).isOccupied(),   // 用 isOccupied，不要用 isIdle（含积压，永远领不到）
                    claim -> onMessageFromQueue(engine, session, claim.getItem())));
}
```

队列续发在领取回调里**同线程**调用 `engine.handle(claim.getItem().toInput(session))`，引擎会识别为程序派发（`outcome.isDispatched()`），
回复路由取 `outcome.getDispatch().getOrigin()`。完整写法见 [queue README](../solon-ai-harness-queue/README.md)。

### 6.2 管理态（控制台 / 后台 API）

门面（`queue.enqueue`、`steer.steer`、`goal.create/update/pause`、`loop.schedule`、`InterruptMode.INTERRUPT` 等）是对象路径，**不经过 `handle` 的受理判定**：

| 检查 | `handle` 路径 | 门面路径 | 宿主要做的 |
|---|---|---|---|
| 会话归属 | 自动（`not_owner`） | 自动（见 §四的失败方式表） | 按 `NOT_OWNER` 的 `getOwner()` 转发 |
| 作用域 / 权限 | 自动（工具级、命令级） | **不检查** | 在控制器上做租户与角色鉴权 |
| 会话存在性 | 宿主传入会话对象 | goal 门面不再新建会话；其它门面收会话对象 | 用 `engine.requireSession(sid)`（不存在抛 `IllegalArgumentException`）或 `getSessionOrNull`，映射为 404 |
| 续发 | 引擎自动入积压 | `enqueue` / `promote` 后不会自动续发 | 调用使用态的同一个 `drain` |

```java
@Controller
@Mapping("/admin/{tenant}/sessions/{sid}")
public class AdminController {
    @Inject SaasHarness saas;

    @Post @Mapping("interrupt")
    public Result interrupt(String tenant, String sid) {
        HarnessEngine engine = saas.engineOf(tenant);
        AgentSession session = engine.getSessionOrNull(sid);
        if (session == null) return Result.failure(404, "session not found");

        try (InterruptPlan plan = engine.prepareInterrupt(session)) {
            if (plan != null && plan.isNotOwner()) {
                return Result.failure(307, plan.getOwner());       // 网关转发
            }
            return Result.succeed(plan != null && plan.hasActiveRun());
        }
    }

    @Post @Mapping("goal/pause")
    public Result pauseGoal(String tenant, String sid) {
        GoalExtension goal = saas.engineOf(tenant).getExtension(GoalExtension.class).orElse(null);
        if (goal == null) return Result.failure(501, "goal not installed");
        HarnessResult<Void> r = goal.pause(sid);
        switch (r.getCode()) {
            case OK:        return Result.succeed();
            case NOT_OWNER: return Result.failure(307, r.getOwner());   // 网关转发
            case NOT_FOUND: return Result.failure(404, r.getDetail());
            default:        return Result.failure(409, r.getDetail());  // CONFLICT：当前状态不可暂停
        }
    }
}
```

> `InterruptMode.INTERRUPT` 会**连带清空队列**；只想停当前流、保留排队用 `engine.interrupt(session, InterruptMode.CANCEL_STREAM)`。改 goal 配置时 `goal.update(..., stopper)` 的 `stopper` 不要传 `InterruptMode.INTERRUPT`（会清队列）。

---

## 七、资源与容量

一个引擎的开销（随租户数线性增长）：

| 资源 | 归属 | 说明 |
|---|---|---|
| 子代理执行器（`task` / `multitask`） | 每引擎一个（缺省上限 64 线程），或宿主注入的共享执行器 | 空闲不占线程；单次并发由 `subagentDefaultConcurrency`（缺省 5）或模型 `concurrency` 控制 |
| goal 轮次线程池 | 每个 GoalExtension 一个 | `roundConcurrency`（缺省 4）；空闲不占线程 |
| goal 节拍 job | 每个 GoalExtension 一个（1 秒） | 有活跃 goal 才注册 |
| loop job | **每个任务一个** | 用 `maxTasks` + `quota` 控总量 |
| MCP 连接 | 每引擎按配置建连 | 构建期建连，`shutdown` 时关闭 |
| LSP 进程 | 每引擎按需启动 | `shutdown` 时关闭 |
| 会话缓存 | `sessionProvider` 实现 | 由宿主控制上限与淘汰 |

回收规则：

- 引擎池按 `idleTtl` 回收**空闲且静默**的引擎（`engine.isQuiescent()`：无执行中的请求，且所有扩展都静默）；
- **有活跃 goal 或启用中的 loop 任务的引擎不会被回收**，它们需要引擎活着才能继续跑；
- 满额（`maxEngines`）时先回收最久未用的静默引擎；仍满则按 `whenFull`：`REJECT`（缺省，抛 `IllegalStateException`）或 `WAIT`（等 `fullWait` 后再拒绝）；
- `engine.shutdown()` 幂等：回调扩展 `onDetach`、关闭引擎私有的子代理执行器、LSP 进程与 MCP 连接、释放会话租约；之后的 `handle` 返回 `REJECTED(engine_closed)`；
- 引擎池是懒建的：进程重启后，没人访问的租户不会自动创建引擎。用 `pool.warmup(keys)` 预热有后台工作的租户（单个键失败只记日志）；
- 租户下线：`pool.removeWhenQuiescent(key, timeout)` 等任务跑完再移除，超时返回 false。

池的并发约定：引擎在锁外构建，构建失败不留占位、下次可重试；同一个键的回收（含 `shutdown`）在该键的 `computeIfPresent` 里完成，因此扩展的 `onDetach` 里**不得再调用本池**，也不宜长时间阻塞。

---

## 八、运行期配置

SERVER 模式下 `runtimeMutable=false`：工具权限（含 `toolPermissionReset`）、模型、MCP / OpenAPI / LSP、挂载、沙盒、HITL 开关、系统提示词、记忆开关、子代理默认并发、缓存控制、扩展增删等引擎级修改会抛 `IllegalStateException`；
命令注册表与子代理注册表在 `build()` 末尾封口，`getCommandRegistry().register(...)`、`getAgentManager().addAgent(...)` 同样抛异常。差异请在**构建期**决定，按用户的差异用 `InputScope` 表达：

| 运行期写法（SERVER 下抛异常） | 构建期写法 |
|---|---|
| `engine.addExtensionIfAbsent(ext)` / `addExtension(ext)` | `Builder.extensionAdd(ext)`（扩展的 `onAttach` 在注册表封口前执行，插件自带的命令照常注册） |
| `engine.getCommandRegistry().register(cmd)` | `Builder.commandAdd(cmd)` |
| `engine.getCommandRegistry().load(source, dir)` | `Builder.commandLoad(source, dir)` |

推理调优类参数（`setMaxTurns`、`setSessionWindowSize`、`setCompressionThreshold`、重试次数等）不在锁内，可以在运行期调。
会话级选择（`setSessionModel`、`setSessionAgent`、`setSessionCwd`、`setSessionPrincipal`）也不受锁约束；`setSessionCwd` 受 `cwdPolicy` 约束，`setSessionPrincipal` 设置后不可改。

> 锁是防误用的护栏，不是沙箱：**不要把引擎对象暴露给不可信的代码**。

---

## 九、上线检查清单

用 `HarnessHost` 时，带「（L1）」的项由骨架代办，可跳过。

- [ ] `profile(HarnessProfile.SERVER)`；`stateSource` 在工作区之外
- [ ] 启动时 `SandboxBootstrap.initOnce(...)`，网络白名单按全进程的需求配置
- [ ] 创建会话时 `setSessionPrincipal`；`scopeResolver` 用 `byPrincipal` 查授权表并做短期缓存；授权服务异常时宁可拒绝
- [ ] `cwdPolicy` 限制在租户根目录内；换目录走 `setSessionCwd`
- [ ] 多节点：粘滞路由，或 `SessionOwnership.namespaced(tenantId, redisOwnership)`；网关处理 `not_owner` 转发
- [ ] 管理接口：租户鉴权（骨架下配置 `adminAuthorizer`）；（L1）`requireSession` / `getSessionOrNull` 校验存在性、处理门面的 `NOT_OWNER`、入队 / 置顶后调用 `drain`
- [ ] （L1）队列续发挂在 `outcome.afterRun(...)`；`drainLoop` 的谓词用 `isOccupied`；订阅前 `RunTicket.begin`
- [ ] （L1）结局分派处理 `scope_not_deferrable`、`engine_closed` 两个拒绝码
- [ ] 只注册服务端可用的命令，或用作用域的 `commands` 维度收窄
- [ ] `generate` 全局创建：保持 SERVER 缺省拒绝，或用 `agentPersistPolicy` 只对管理员开放
- [ ] goal：确认 `FAIL_CLOSED` 的影响；需要构建 / 测试验证时显式 `defaultValidators(true)` 并评估命令执行风险
- [ ] 插件全部在构建期用 `Builder.extensionAdd(...)` 挂上（SERVER 下 build 之后不能再挂）；自定义命令用 `Builder.commandAdd` / `commandLoad`
- [ ] loop：`store(stateSrc, "loop")`（与引擎同一个状态来源）、`maxTasks`、`quota`
- [ ] 引擎池：`maxEngines`、`idleTtl`、`whenFull`；启动时 `warmup` 有后台工作的租户；build 之后调用 `goal.recover()`
- [ ] 会话提供者：实例缓存有上限；`createIfAbsent=false` 无副作用
- [ ] 删除会话：判忙 → `engine.removeSession(id)` → 移出内存 → 删除存储

---

## 十、已知限制

以下问题已经识别、尚未在框架层解决：

1. 插件门面不经过作用域校验：用 `AdminGateway` 时由 `AdminAuthorizer` 统一鉴权，直连门面时要宿主自己鉴权；
2. 引擎之间不共享模型实例与 MCP 连接，也没有进程级的节拍与按模型并发闸门（M5b 未落地）；
3. 引擎池不会自动知道哪些租户有后台工作，`warmup` 的租户清单由宿主维护；
4. goal / loop 执行器只能按 `sessionId` 认领（M6.2 未落地）；
5. 宿主骨架 `HarnessHost`（M6.1）已落地（`@Preview`）；goal / loop 的后台轮次经骨架时同步执行、不推送流事件；
6. `SessionOwnership` 没有续约接口，长运行期间的租约保活由实现负责。
