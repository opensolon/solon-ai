# 用 solon-ai-harness 开发 SaaS 服务

本文面向把 harness 部署成**多租户服务端**的开发者（Web API、IM 网关、企业 Agent 平台）。单机 CLI / 桌面 / 单用户 Web 请看 [README.md](README.md)，本文只讲服务端额外要做的事。

> 文中 `TenantStore`、`Authz`、`RedisOwnership`、`ImHost` 等是**宿主自己的类**（示意），harness 不提供；
> `HarnessEngine`、`HarnessEnginePool`、`TurnScope`、`SessionOwnership` 等才是 harness 的 API。
> 标 `@Preview("4.2.0")` 的类型（引擎池、作用域、会话归属）在 4.2.x 内可能还有调整。

---

## 一、先定隔离模型

harness 的隔离单位有三层，先想清楚每一层对应你业务里的什么：

| 层 | harness 里的载体 | 隔离什么 | 典型对应 |
|---|---|---|---|
| 引擎 | `HarnessEngine`（绑定一个工作区 + 一个状态来源） | 文件系统、MCP / LSP 连接、模型配置、扩展实例 | 租户、或租户下的项目 |
| 会话 | `AgentSession`（`sessionProvider` 提供） | 对话历史、HITL 挂起、goal / queue / steer / loop 状态 | 一个聊天窗口、一个 IM 会话 |
| 轮次 | `TurnScope`（每次 `handle` 解析一次） | 本轮能用哪些工具、技能、MCP、模型 | 用户 / 角色的权限 |

推荐的组合：

- **租户之间需要文件隔离** → 每个租户（或每个租户项目）一个引擎，用 `HarnessEnginePool` 管理；
- **同一租户内不同用户 / 角色** → 共用引擎，用 `TurnScope` 收窄权限；
- **不要**让多个租户共用一个引擎，再试图用作用域隔离文件：作用域约束工具调用，但工作区、技能挂载、MCP 连接都是引擎级共享的。

> 经过评估，「单例引擎 + 请求时传工作区」的方案没有采用：单个引擎的固定开销偏轻（空闲时没有线程，调度器用的是 JVM 单例），
> 而单例会让租户隔离从结构保证退化为开发纪律。

---

## 二、最小可运行骨架

```java
public class SaasHarness {
    private final HarnessEnginePool pool;

    public SaasHarness(TenantStore tenants, Authz authz, SessionOwnership ownership) {
        // ① 进程级 OS 沙盒：SERVER 模式下引擎不自建，宿主启动时初始化一次（网络白名单是进程级的）
        SandboxBootstrap.initOnce(sandboxConfig());

        // ② 引擎池：每个租户一个引擎，懒建、空闲回收
        this.pool = HarnessEnginePool.builder(tenantId -> buildEngine(tenantId, tenants, authz, ownership))
                .idleTtl(Duration.ofMinutes(30))
                .maxEngines(500)                     // 软上限；满额时先回收静默引擎，仍满则抛 IllegalStateException
                .build();
    }

    private HarnessEngine buildEngine(String tenantId, TenantStore tenants, Authz authz, SessionOwnership ownership) {
        Path ws    = tenants.workspaceOf(tenantId);   // 如 /data/ws/<tenant>
        Path state = tenants.stateDirOf(tenantId);    // 如 /data/state/<tenant>（必须在工作区之外）

        HarnessEngine engine = HarnessEngine.of(ws.toString(), ".solon/")
                .profile(HarnessProfile.SERVER)                        // 保守缺省 + 构建期校验
                .stateSource(FileMountSource.of(state))                // 或 db / http 实现的 MountSource
                .sessionProvider(tenants.sessionProviderOf(tenantId))  // 会话持久化（见 §五）
                .modelAdd(tenants.modelsOf(tenantId))
                .defaultModel(tenants.defaultModelOf(tenantId))
                .toolsAdd(ToolName.TOOL_ALL_PUBLIC)                    // 引擎级上限；按用户再用作用域收窄
                .scopeResolver((session, input) -> authz.scopeOf(tenantId, session, input))
                .cwdPolicy(cwd -> Paths.get(cwd).toAbsolutePath().normalize().startsWith(ws))
                .sessionOwnership(ownership)                           // 多节点才需要；单机省略（缺省 LOCAL）
                .build();

        // ③ 会话插件（按需）：SERVER 模式下 goal 缺省不注册内置构建验证器、且 FAIL_CLOSED
        engine.addExtensionIfAbsent(QueueExtension.builder().maxSize(20).build());
        engine.addExtensionIfAbsent(new SteerExtension());
        GoalExtension goal = engine.addExtensionIfAbsent(GoalExtension.builder().roundConcurrency(2).build());
        engine.addExtensionIfAbsent(LoopExtension.builder()
                .store(engine.getStateSource(), "loop")                // 按会话分存，在缺省受保护名单内
                .maxTasks(200).quota(sid -> 20)
                .build());

        goal.recover();                                                // 按 goal/active/ 索引恢复调度
        return engine;
    }

    public HarnessEngine engineOf(String tenantId) {
        return pool.get(tenantId);
    }
}
```

`ToolName.TOOL_ALL_PUBLIC` 即 `*`（公有工具组，不含 `task` / `generate` / `mcp` / `hitl` / `memory`）；关键是**引擎级给上限、作用域再收窄**。需要 MCP 时显式加 `ToolName.TOOL_MCP` 一类的工具。

### 2.1 `profile(SERVER)` 改了什么

预设在 `build()` 时合成，规则是**显式值 > 预设值 > 默认值**，与调用顺序无关：

| 配置 | CODING（缺省） | SERVER |
|---|---|---|
| `sandboxEnabled` / `sandboxSystemRestrict` | `true` / `true` | `true` / `true` |
| `sandboxAllowUserHome` | `false` | `false` |
| `sandboxSelfBootstrap` | `true`（引擎自行初始化沙盒） | `false`（宿主 `SandboxBootstrap.initOnce`，未初始化时拒绝执行命令） |
| `bashAsyncEnabled` | `false` | `false` |
| `memorySessionIsolation` | `false` | `true`（长期记忆按会话隔离） |
| `runtimeMutable` | `true` | `false`（运行期改引擎级全局配置抛 `IllegalStateException`） |
| `scopeRequired` | `false` | `true`（解析不到作用域就拒绝受理） |
| goal 内置验证器 / 验证策略 | 注册 / `FAIL_OPEN` | 不注册 / `FAIL_CLOSED` |

`build()` 在 SERVER 模式下额外校验三件事，不满足就抛 `IllegalStateException`：

1. 必须**显式**配置 `stateSource`，本地来源时不能落在工作区内（否则模型的文件工具能读写 harness 状态）；
2. 至少有一个模型；
3. `defaultModel` 若配置了，必须存在。

沙盒被显式放宽（`sandboxEnabled=false` 或 `sandboxSystemRestrict=false`）时只打 WARN，不会拒绝构建。

---

## 三、请求级作用域：`TurnScope`

作用域回答「这一轮能用什么」。它是**强制边界**：在引擎配置的基础上只能收窄，工具执行前由守卫拦截，委派子代理继承同一作用域。

```java
public class Authz {
    public TurnScope scopeOf(String tenantId, AgentSession session, HarnessInput input) {
        String userId = userOf(session);                 // 从会话快照里读创建会话时写入的用户（见下方注意）
        Role role = roleOf(tenantId, userId);
        return TurnScope.builder(tenantId + "/" + userId)      // principal：审计、配额、日志用
                .tools(role.tools())                           // 逻辑名（bash / read / skill / mcp ...）或分组（pi / * / **）
                .skills(role.skills())                         // null = 不限；空集合 = 全禁
                .mcps(role.mcps())
                .chatModel(byoKeyModelOf(tenantId))            // 可选：租户自带 Key；null = 沿用引擎解析结果
                .attr("plan", planOf(tenantId))                // 可选：给宿主自己的拦截器看
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

- **扩展自带的工具不受 `tools` 名单约束**（经 `HarnessExtension#getToolNames()` 声明，如 `goal_get` / `goal_update`、`loop_*`），否则 goal 会因名单里没写 `goal_update` 而永远无法完成。不希望某类用户使用某扩展时，不要给对应引擎挂该扩展。
- **解析时机**：每次受理都会解析一次（用户输入、HITL 续流、命令产物、goal / loop / queue 的程序输入）。输入显式带了 `HarnessInput.scope(...)` 时不再调用解析器。
- **`scopeRequired=true`**（SERVER 缺省）：解析器返回 `null` 或抛异常时，`handle` 返回 `REJECTED` + `InputOutcome.REJECT_SCOPE_UNAVAILABLE`；绕过受理的旧入口（已废弃的 `engine.prompt(...)` / `engine.resume()`）由守卫禁用全部工具。

> **注意 1：服务端优先用 `scopeResolver`，不要只靠显式 `scope(...)`。** 忙时进积压的输入只保存正文、来源、模型、子代理与宿主属性，
> 不保存显式作用域（也不保存 `selectedSkill` / `selectedMcp`）；续发时会重新解析。只传显式作用域的宿主，续发那一轮会被拒绝（`scopeRequired`）或不受限（未开启）。
>
> **注意 2：用户身份存在哪。** goal / loop 的程序轮次没有「当前请求」，`InputOrigin` 也是程序来源，解析器只能从会话本身拿到用户。
> 建议创建会话时把 `userId` 写进**会被持久化的会话数据**（会话快照），而不是只放在 `session.attrs()`（进程内存，重启即丢）。
>
> **注意 3：命令不受作用域约束。** 作用域约束的是工具调用；`/model`、`/goal`、`/loop` 等命令由宿主注册，能不能用由宿主决定注册哪些命令，或在网关层拦截。

---

## 四、多节点：会话归属 `SessionOwnership`

会话的运行态（流句柄、受理占位、插话邮箱、队列锁）都在进程内存里，**同一时刻只能由一个节点执行一个会话**。有两种做法：

| 方式 | 做法 | 适用 |
|---|---|---|
| 粘滞路由 | 网关按 `sessionId` 一致性哈希路由到固定节点 | 简单；但这是**部署前提**，要写进运维文档 |
| 单写者租约 | 实现 `SessionOwnership`（如 Redis `SET key node NX PX lease` + 节点心跳续期） | 节点可随时扩缩容 |

```java
public class RedisOwnership implements SessionOwnership {
    // 引擎每次受理都会调用 tryAcquire：必须廉价（本地缓存已持有集合），对已持有的会话返回 true
    public boolean tryAcquire(String sessionId) { ... }
    public String ownerOf(String sessionId) { ... }    // 持有者节点标识，网关据此转发
    public void release(String sessionId) { ... }      // removeSession / shutdown 时调用；对未持有的会话应忽略
}
```

- 引擎在 `handle` 第 0 步（锁外）调用 `tryAcquire`。拿不到就返回 `REJECTED` + `REJECT_NOT_OWNER`，`getRejectDetail()` 为持有者标识，由网关转发；
- **租约由实现自行保活**（节点心跳批量续期、Redisson watchdog 等），只应在节点失联后过期：一次运行可能远长于租期（goal 长轮次、慢工具），靠受理续约会在运行中过期、造成双写；
- goal / loop 的程序轮次也经过 `handle`：冷会话由先触发的节点认领，其余节点被 `not_owner` 拒绝后跳过；
- 引擎在 `removeSession` 时释放该会话，在 `shutdown` 时释放本引擎认领过的全部会话。

> **租约键请自带租户前缀。** `tryAcquire(sessionId)` 只收会话 ID，而会话 ID 不保证全局唯一。多个租户的引擎共用一个 Redis 实现时，
> 请为每个引擎构造带前缀的实例（如 `new RedisOwnership("tenant:" + tenantId + ":")`），否则同名会话会互相拒绝。
>
> **门面不经过归属校验**（见 §六），管理接口打到非持有节点时，需要宿主自己先判断。

---

## 五、状态与存储

| 数据 | 存在哪 | 由谁决定 | 多节点注意 |
|---|---|---|---|
| 对话历史、会话快照（含 queue 投影、goal 投影） | 会话实现 | `sessionProvider` | 要能跨节点读到（共享存储），且只由持有节点写 |
| goal 活跃索引 | `stateSource` 的 `goal/active/`（每会话一个空标记文件） | 引擎 | 多节点共用来源时不会互相覆盖 |
| loop 任务 | `store(source, dir)` 指定，每会话一个文件 `<dir>/<encode(sid)>.json` | 宿主 | 建议传 `engine.getStateSource()` |
| 长期记忆 | `memoryProvider` | 宿主 | 缓存与索引在进程内存，其它节点写入后需重建实例才能检索到 |
| steer 邮箱、受理占位、运行句柄 | 进程内存 | — | 不落盘；重启后未注入的插话丢失 |
| 工作区文件 | 引擎工作区（本地目录） | 宿主 | 模型的文件工具、bash、LSP 都直接操作本地目录；远程工作区需要宿主自己做同步 |

会话提供者的契约：`getSession(id, createIfAbsent=false)` 在会话不存在时必须返回 `null` 且无副作用（不建实例、不写存储、不放缓存）。生产实现应缓存实例，并设上限与淘汰策略。

### 5.1 受保护状态路径

`protectedStatePaths`（缺省 `sessions, memory, channels, tasks.json, loop, goal`，相对 harnessHome）里的路径，模型的文件工具读写会被拒绝，OS 沙盒也会同时 denyRead / denyWrite。
loop 用其它目录名时，请把它加进这个名单。

---

## 六、宿主接入：使用态与管理态

### 6.1 使用态（用户发消息）

所有用户输入都走唯一入口 `engine.handle(HarnessInput)`，按六种结局分派：

```java
public void onMessage(String tenantId, String sessionId, String userId, String text, InputOrigin origin) {
    HarnessEngine engine = saas.engineOf(tenantId);
    AgentSession session = engine.getSessionOrNull(sessionId);
    if (session == null) { reply(origin, "会话不存在"); return; }     // 不要用 getSession：它会凭空新建

    InputOutcome outcome = engine.handle(HarnessInput.of(session, text).origin(origin));
    switch (outcome.getKind()) {
        case TASK:
            if (outcome.hasReply()) reply(origin, outcome.getCommandOutput());
            outcome.afterRun(() -> drain(engine, session));            // 续发积压必须挂在 afterRun，不要写在 doFinally
            outcome.stream().subscribe(e -> push(origin, e));          // 订阅时机由宿主定；开流前出错要 outcome.discard()
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
        case InputOutcome.REJECT_NOT_OWNER:         forwardTo(o.getRejectDetail()); break;  // 转发给持有节点
        case InputOutcome.REJECT_SCOPE_UNAVAILABLE: reply(origin, "无权限或授权服务不可用"); break;
        default:                                    reply(origin, o.getRejectDetail());       // busy / hitl_pending / 附件不能排队 / 积压已满
    }
}

private void drain(HarnessEngine engine, AgentSession session) {
    engine.getExtension(QueueExtension.class).ifPresent(q ->
            q.drainLoop(session, s -> !engine.status(s).isOccupied(),   // 用 isOccupied，不要用 isIdle（含积压，永远领不到）
                    claim -> onMessageFromQueue(engine, session, claim.getItem())));
}
```

队列续发在领取回调里**同线程**调用 `engine.handle(claim.getItem().toInput(session))`，引擎会识别为程序派发轮次（`outcome.isDispatched()`），
回复路由取 `outcome.getDispatch().getOrigin()`。完整写法见 [queue README](../solon-ai-harness-queue/README.md)。

### 6.2 管理态（控制台 / 后台 API）

门面（`queue.enqueue`、`steer.steer`、`goal.create/update/pause`、`loop.schedule`、`engine.interrupt` 等）是对象路径，**不经过 `handle` 的受理判定**，因此：

| 检查 | `handle` 路径 | 门面路径 | 宿主要做的 |
|---|---|---|---|
| 会话归属 | 自动（`not_owner`） | 不检查 | 先 `engine.getSessionOwnership().tryAcquire(sid)`（或查 `ownerOf`），不是持有者就转发 |
| 作用域 / 权限 | 自动（工具级） | 不检查 | 在控制器上做租户与角色鉴权 |
| 会话存在性 | 宿主传入会话对象 | goal 门面按 `getSession` 解析，**不存在会新建** | 先 `engine.getSessionOrNull(sid)`，为 null 返回 404 |
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
        if (engine.getSessionOwnership().tryAcquire(sid) == false) {
            return Result.failure(307, engine.getSessionOwnership().ownerOf(sid));   // 网关转发
        }
        return Result.succeed(engine.interrupt(session));
    }
}
```

> `engine.interrupt` 会**连带清空队列**；只想停当前流、保留排队用 `engine.cancelStream(session)`。改 goal 配置时 `goal.update(..., stopper)` 的 `stopper` 不要传 `engine::interrupt`（会清队列）。

---

## 七、资源与容量

一个引擎的开销（随租户数线性增长）：

| 资源 | 归属 | 说明 |
|---|---|---|
| 子代理执行器（`task` / `multitask`） | 每引擎一个 | 并发上限 `subagentDefaultConcurrency`（缺省 5，上限 10）或模型 `concurrency` |
| goal 轮次线程池 | 每个 GoalExtension 一个 | `roundConcurrency`（缺省 4）；租户多时调小 |
| goal 节拍 job | 每个 GoalExtension 一个 | 有活跃 goal 才注册 |
| loop job | **每个任务一个** | 用 `maxTasks` + `quota` 控总量 |
| MCP 连接 | 每引擎按配置建连 | 构建期建连；租户多时注意连接数 |
| LSP 进程 | 每引擎按需启动 | |
| 会话缓存 | `sessionProvider` 实现 | 由宿主控制上限与淘汰 |

回收规则：

- 引擎池按 `idleTtl` 回收**空闲且静默**的引擎（`engine.isQuiescent()`：无执行中的请求，且所有扩展都静默）；
- **有活跃 goal 或启用中的 loop 任务的引擎不会被回收**，它们需要引擎活着才能继续跑；
- 引擎池是懒建的：进程重启后，没人访问的租户不会自动创建引擎，它的 goal / loop 也不会恢复。需要恢复后台工作时，请在启动时为「有后台工作的租户」预热（`pool.get(tenantId)`；租户清单由宿主维护）。

池的并发约定：同一个键的创建、刷新、回收（含 `shutdown`）在该键的 `compute` 里串行完成，因此工厂函数与扩展的 `onDetach` 里**不得再调用本池**（会抛 Recursive update），也不宜长时间阻塞。

---

## 八、运行期配置

SERVER 模式下 `runtimeMutable=false`：工具权限、模型、MCP / OpenAPI / LSP、挂载、沙盒、HITL 开关、系统提示词、扩展增删等引擎级修改会抛 `IllegalStateException`。差异请在**构建期**决定，按用户的差异用 `TurnScope` 表达。

推理调优类参数（`setMaxTurns`、`setSessionWindowSize`、`setCompressionThreshold`、重试次数等）不在锁内，可以在运行期调。
会话级选择（`setSessionModel`、`setSessionAgent`、`setSessionCwd`）也不受锁约束；`setSessionCwd` 受 `cwdPolicy` 约束。

> 目前锁的覆盖还不完整（例如 `toolPermissionReset`、`setMemoryEnabled`、经 `getCommandRegistry()` / `getAgentManager()` / `getHitlInterceptor()` 的修改），
> 不要把它当作唯一防线：**不要把引擎对象暴露给不可信的代码**。

---

## 九、上线检查清单

- [ ] `profile(HarnessProfile.SERVER)`；`stateSource` 在工作区之外
- [ ] 启动时 `SandboxBootstrap.initOnce(...)`，网络白名单按全进程的需求配置
- [ ] `scopeResolver` 从**持久化的会话数据**读用户，查授权表；授权服务异常时宁可拒绝
- [ ] `cwdPolicy` 限制在租户根目录内；不用 `toolContextPut(ATTR_CWD, ...)` 换目录
- [ ] 多节点：粘滞路由或带租户前缀的 `SessionOwnership`；网关处理 `not_owner` 转发
- [ ] 管理接口：`getSessionOrNull` 校验存在性 + 归属校验 + 租户鉴权；入队 / 置顶后调用 `drain`
- [ ] 队列续发挂在 `outcome.afterRun(...)`；`drainLoop` 的谓词用 `isOccupied`
- [ ] 只注册服务端可用的命令（`terminalOnly` 命令在非终端输入下本来就不可见）
- [ ] goal：确认 `FAIL_CLOSED` 的影响；需要构建 / 测试验证时显式 `defaultValidators(true)` 并评估命令执行风险
- [ ] loop：`store(engine.getStateSource(), "loop")`、`maxTasks`、`quota`
- [ ] 引擎池：`maxEngines`、`idleTtl`；启动时预热有后台工作的租户；`goal.recover()`
- [ ] 会话提供者：实例缓存有上限；`createIfAbsent=false` 无副作用
- [ ] 删除会话：判忙 → `engine.removeSession(id)` → 移出内存 → 删除存储

---

## 十、已知限制

以下问题已经识别、尚未在框架层解决，详细分析见 [SAAS-ISSUES.md](SAAS-ISSUES.md)：

1. 门面不经过会话归属与作用域校验，需要宿主自行守卫；
2. `SessionOwnership` 的键没有租户维度，需要宿主自行加前缀；
3. `runtimeMutable` 锁的覆盖不完整；
4. 引擎之间不共享模型、MCP、执行器，也没有全局并发上限；引擎池重启后不会自动恢复后台工作；
5. 显式 `scope(...)`、`selectedSkill` / `selectedMcp` 不随积压保存；
6. goal / loop 执行器只能按 `sessionId` 认领；
7. 宿主胶水（续发、终态、停流入口选择）全靠约定，写错不报错。
