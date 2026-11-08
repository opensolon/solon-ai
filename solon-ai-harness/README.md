# solon-ai-harness — AI 代理编排引擎

## 简介

`solon-ai-harness` 是 Solon AI 的 **Agent 运行时与编排容器**：把「ReAct 引擎 + 一组 Talent 能力 + 子代理体系 + 命令系统 + 安全策略」装配成一个可直接跑起来的 Agent（默认能力面向编码场景）。

### 先选层：多数宿主只需要 L0

| 层 | 入口 | 适用 | 从哪读 |
|---|---|---|---|
| **L0 宿主骨架**（推荐） | `HarnessHost` + `HarnessInput`（+ 可选 `HostSink` / `RunSink`） | SaaS、IM 机器人、HTTP / SSE、多会话推送、管理控制台 | [host README](../solon-ai-harness-host/README.md) → [QUICKSTART-SAAS.md](../solon-ai-harness-host/docs/QUICKSTART-SAAS.md) → [CONCEPTS.md](docs/CONCEPTS.md) |
| L1 受理 API | `engine.handle` + `InputOutcome` + `RunTicket` | 只要核心、不要插件，或要完全自己掌握编排 | [ENGINE-L1.md](docs/ENGINE-L1.md) |
| L2 扩展 SPI | `HarnessExtension`、`InputBacklog`、`RunDispatch` | 写插件 | 本文第九章 |

L0 最小写法（引擎构建见 §1.2；受理、排队、停流、审批续流、goal / loop 后台轮次都由骨架代办）：

```java
HarnessHost host = HarnessHost.builder()
        .engines(tenant -> engine)          // 单租户：返回同一个引擎
        .build();

host.openSession("default", sid, null);
RunOutcome o = host.submit("default", sid, HarnessInput.of("hello")).getRun().await(Duration.ofMinutes(5));
```

> `HarnessInput.of(text)` 给 L0（骨架负责绑定会话），`HarnessInput.of(session, text)` 给 L1（直接交给 `engine.handle`），两种写法不要混用。

本文是**引擎本身**的参考：构建与配置、命令、子代理、工具权限、扩展点、安全、目录与常见坑，L0 / L1 宿主都适用。
另有三份专题文档：

| 文档 | 内容 |
|---|---|
| [docs/ENGINE-L1.md](docs/ENGINE-L1.md) | 自己编排（L1）：`handle` / `InputOutcome` 详解、两段式宿主端到端示例、HITL 续流与受理控制、goal / queue / steer 的接线与停 / 改 / 重排（本文第二、三、五章只留摘要，章节号与它一致） |
| [docs/SAAS.md](docs/SAAS.md) | 服务端（多租户）的引擎侧约定：隔离模型、`DeployMode.SERVER`、作用域、会话归属、存储、容量、上线清单与已知限制 |
| [docs/CONCEPTS.md](docs/CONCEPTS.md) | L0 需要掌握的术语 |

### 怎么读这份文档

| 你想做什么 | 读哪里 |
|---|---|
| 做多租户 / 服务端（SaaS）、IM、HTTP 接口 | **L0**：[host README](../solon-ai-harness-host/README.md) → [QUICKSTART-SAAS.md](../solon-ai-harness-host/docs/QUICKSTART-SAAS.md)（四步搭起来 + 上线检查清单）→ [CONCEPTS.md](docs/CONCEPTS.md)（术语）；引擎侧的隔离、作用域、多节点、存储与容量见 [SAAS.md](docs/SAAS.md) |
| 先跑起来看看 | 第一章（五分钟上手） |
| 自己编排，接一个真产品 | [ENGINE-L1.md](docs/ENGINE-L1.md) 第二、三章（两段式、事件渲染、HITL 续流、四端时序、受理控制） |
| 加命令 / 子代理 | 第四章、第六章 |
| 跑 goal / queue / steer / loop | 第五章（装配摘要）；L0 由骨架代办，见 host README；自己接线见 [ENGINE-L1.md](docs/ENGINE-L1.md) 第五章；完整参考见各插件 README：[goal](../solon-ai-harness-goal/README.md) · [loop](../solon-ai-harness-loop/README.md) · [queue](../solon-ai-harness-queue/README.md) · [steer](../solon-ai-harness-steer/README.md) |
| 分清「命令文本 / `handle(HarnessInput)` / 门面」三条路、运行期开关扩展 | [ENGINE-L1](docs/ENGINE-L1.md) §5 引言 + 本文 §8.2 |
| 停 / 改 / 重排的干预闭环 | [ENGINE-L1](docs/ENGINE-L1.md) §5.4（`InterruptMode.INTERRUPT_ALL` + `steer` + `queue`，两个互斥场景） |
| 调优与定制 | 第七～十章（权限、配置、扩展、安全） |
| 查结构 / 躲坑 | 第十二章（模块图）、第十三章（常见坑） |

> **术语**：**input** 是一次 `handle` 的输入（`HarnessInput` / `InputOutcome` / `InputScope`）；**run** 是受理后开出的一条流
> （`runId` / `RunTicket` / `RunDispatch` / `steerOnRun`）；**step** 是 ReAct 内部的一步推理加工具调用（`maxTurns` 沿用旧名，计的是 step）。
> 正文里的「一轮」、「本轮」指 run。

---

## 一、五分钟上手

### 1.1 依赖

| 坐标 | 内容 | 适用 |
|---|---|---|
| `solon-ai-harness` | **聚合包**：只做引用（没有任何源码），引入核心 + 四个会话插件（goal / queue / steer / loop）+ 宿主骨架 `-host` | 应用宿主（推荐） |
| `solon-ai-harness-host` | 宿主骨架（L0）：`HarnessHost` / `HostSink` / `RunSink` / `SubmitResult` / `SessionAdmin`，依赖核心与四个插件（引擎池 `HarnessEnginePool` 在 core），见 [host README](../solon-ai-harness-host/README.md) | SaaS / IM 网关 / 普通 Web 服务 |
| `solon-ai-harness-core` | 只有核心（引擎、输入、命令、权限、作用域、SPI） | 只需核心、或自己写插件 |
| `solon-ai-harness-goal` / `-queue` / `-steer` / `-loop` | 单个插件（各自只依赖 core） | 按需挑选插件 |

```xml
<!-- 宿主：一个依赖带齐核心与四个插件 -->
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-harness</artifactId>
    <version>${solon-ai.version}</version>
</dependency>

<!-- 只要核心契约（写插件 / 不用会话插件） -->
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-harness-core</artifactId>
    <version>${solon-ai.version}</version>
</dependency>
```

> 聚合包**不自动装配**：插件上了类路径不会自己生效，需在构建期装配——逐个 `Builder.extensionAdd(...)`（见 [ENGINE-L1](docs/ENGINE-L1.md) §5）；运行期 `addExtensionIfAbsent` 只在 `runtimeMutable` 时可用（SERVER 下抛 `IllegalStateException`）。写插件的模块请依赖 `solon-ai-harness-core`，不要依赖聚合包。

### 1.2 构建引擎

```java
HarnessEngine engine = HarnessEngine.of("/data/work", ".solon/")   // 工作区, 引擎根目录
        .systemPrompt("你是一名 AI 编程助手")
        .sessionProvider((id, create) -> InMemoryAgentSession.of(id)) // 必填（生产环境应缓存实例，见「九、扩展点」）
        .modelAdd(new ChatConfig().then(cfg -> {
            cfg.setName("deepseek");                               // 模型别名
            cfg.setApiUrl("https://api.deepseek.com");
            cfg.setApiKey(System.getenv("DEEPSEEK_API_KEY"));
            cfg.setModel("deepseek-chat");
            cfg.setContextLength(128_000);
            cfg.setConcurrency(5);
        }))
        .defaultModel("deepseek")
        .toolsAdd(ToolName.TOOL_ALL_FULL)                           // 主代理工具权限
        .build();
```

> 缺省（`STANDALONE` 模式）下 `build()` 只硬校验 `sessionProvider`。没配模型（或 `defaultModel` 不在模型表里）时 `build()` 只打一条告警、不报错
> （允许构建后再 `addModel`），没补上的话**首次 `handle`** 时由 `ReActAgentConfig` 抛 `chatModel is required`。
> `deployMode(DeployMode.SERVER)` 会在构建期直接校验状态来源、模型、`defaultModel` 与 `scopeResolver`，不满足抛 `IllegalStateException`。

builder 的可选配置一览（`of(workspace, harnessHome)` 与 `sessionProvider` 之外的全部方法；完整语义见 §8.1，运行期可调的见 §8.2）：

| 分类 | 方法 | 默认值 | 说明 |
|---|---|---|---|
| 提示词 | `systemPrompt(String)` | `null` | 主代理系统提示词 |
| HTTP | `userAgent(String)` | `null` | 引擎级 User-Agent |
| HTTP | `httpProxy(host, port)` / `httpProxy(Proxy)` | 直连 | 引擎级代理；传 `null`（Proxy 形式）表示直连 |
| HTTP | `httpCustomizeAdd(Consumer<HttpUtils>)` | — | 每次 LLM 请求创建 `HttpUtils` 后执行，可覆盖/追加 proxy、header、timeout 等 |
| HTTP | `httpCustomizeSet(Consumer<HttpUtils>)` | — | 同上，但替换既有钩子 |
| 推理 | `maxTurns(Integer)` | `20` | 最大推理-行动轮次 |
| 推理 | `autoRethink(Boolean)` | `true` | 失败后自动重试 |
| 历史 | `sessionWindowSize(Integer)` | `12` | 保留最近 N 轮消息 |
| 历史 | `compressionThreshold(maxMessages, maxContextRatio)` | `100` / `0.75` | 触发历史压缩的阈值 |
| 历史 | `compressionDefaultContextLength(Long)` | `128000` | 未知上下文长度时按此估算 |
| 历史 | `compressionInterceptor(...)` | 引擎自建 | 替换上下文压缩拦截器；不配时构造期用上述阈值自建 `ContextCompressionInterceptor` |
| 历史 | `cacheControl(CacheControl)` | `null` | 提示词缓存控制（如 Anthropic 式缓存） |
| 记忆 | `memoryProvider(MemorySolutionProvider)` | — | 长期记忆存储方案；不注入则没有 memory 工具可开关 |
| 记忆 | `memoryEnabled(Boolean)` | `true` | 长期记忆开关 |
| 记忆 | `memoryRelevanceCount(int)` | `6` | 相关性召回条数 |
| 记忆 | `memoryPriorityCount(int)` | `5` | 高优先度兜底条数 |
| 记忆 | `memorySummaryLength(int)` | `80` | `listAll` 视图摘要截断长度 |
| 沙箱 | `sandboxEnabled(Boolean)` | `true` | 文件系统沙箱 |
| 沙箱 | `sandboxAllowUserHome(Boolean)` | `false` | 允许访问用户主目录 |
| 沙箱 | `sandboxSystemRestrict(Boolean)` | `true` | 禁止访问系统路径 |
| 权限 | `toolsAdd(...)` / `disallowedToolsAdd(...)` | 空 | 主代理工具权限（§7） |
| 权限 | `permissionRuleAdd(PermissionRule)` | 空 | 权限规则 |
| 权限 | `hitlEnabled(Boolean)` | `false` | HITL 拦截总开关（三条件之一，§10.2） |
| 权限 | `hitlInterceptor(HITLInterceptor)` | 引擎自建 | 不配时构造期自建，默认只绑 `BashToolStrategy` 到 `bash` |
| 子代理 | `subagentEnabled(Boolean)` | `true` | 子代理委派功能（`task` / `multitask`） |
| 子代理 | `subagentDefaultConcurrency(Integer)` | `5` | 子代理默认并发上限（`1~10`，`0`=未设置取默认 5；模型配了 `AiConfig#concurrency` 以模型为准） |
| 工具 | `bashAsyncEnabled(Boolean)` | `false` | Bash 异步执行 |
| 重试 | `apiRetries` / `mcpRetries` / `modelRetries` | 各 `3` | OpenAPI / MCP / 模型调用重试次数 |
| 资源 | `modelAdd(ChatConfig)` / `defaultModel(String)` | 空 | 模型与默认模型 |
| 资源 | `extensionAdd(HarnessExtension)` | 空 | 扩展（[ENGINE-L1](docs/ENGINE-L1.md) §5 的 Goal/Queue/Steer 也从这里装，§9） |
| 资源 | `mountAdd(Mount)` | — | 挂载（`@harness-agents` 内置挂载不可替换，会抛 `IllegalArgumentException`） |
| 资源 | `mcpServerAdd(name, params)` | 空 | MCP 服务器 |
| 资源 | `apiServerAdd(name, ApiSource)` | 空 | OpenAPI 来源 |
| 资源 | `lspServerAdd(name, params)` | 空 | LSP 服务器 |
| 前缀 | `commandPrefix(String)` | `"/"` | 命令前缀，支持多字符（§4.5） |
| 前缀 | `agentPrefix(String)` | `"@"` | 子代理提及前缀，支持多字符（§4.5） |
| 刹车 | `stopLoopInterceptor(StopLoopInterceptor)` | `StopLoopInterceptor(3, 8)` | 重复调用刹车；不配时构造期自建 |
| 受理 | `admissionTimeoutMs(Long)` | `60000` | `handle` 返回 `TASK` 后占位的时限（[ENGINE-L1](docs/ENGINE-L1.md) §3.6） |
| 存储 | `stateSource(MountSource)` | `<workspace>/<harnessHome>` 本地目录 | harness 状态来源（会话级状态、goal 索引、loop 任务等），模型不可见 |
| 服务端 | `deployMode(DeployMode)` | `STANDALONE` | 部署模式（本机单用户 / 多租户服务端）；`SERVER` 的缺省见本表「SERVER 为」各项，构建期校验见 §1.2 上方说明，完整对照见 [SAAS.md](docs/SAAS.md) §2.1 |
| 服务端 | `scopeResolver(InputScopeResolver)` / `scopeRequired(boolean)` | `null` / `false`（SERVER 为 `true`） | 输入级作用域（只能收窄的强制边界）；解析器在归属检查之后、会话锁之外调用 |
| 服务端 | `sessionOwnership(SessionOwnership)` | `SessionOwnership.LOCAL` | 多节点单写者租约；引擎池里共用同一个实现时用 `SessionOwnership.namespaced(tenantId, delegate)` |
| 服务端 | `cwdPolicy(Predicate<String>)` | 不校验 | 会话工作目录的边界策略：`setSessionCwd` 与终端工具使用端都按真实路径校验，不满足抛 `SecurityException` |
| 服务端 | `runtimeMutable(boolean)` | `true`（SERVER 为 `false`） | 运行期是否允许改引擎级全局配置；为 `false` 时命令与子代理注册表在 `build()` 末尾封口 |
| 服务端 | `agentPersistPolicy(BiPredicate<AgentSession, AgentDefinition>)` | 跟随 `runtimeMutable`（STANDALONE 允许，SERVER 拒绝） | `generate(saveToFile=true)` 是否允许创建全局子代理（§6.4） |
| 资源 | `taskExecutor(ExecutorService)` | 引擎私有，上限 64 线程 | 子代理执行器；宿主注入的共享执行器由宿主关闭，引擎 `shutdown` 不动它 |
| 服务端 | `memorySessionIsolation(boolean)` | `false`（SERVER 为 `true`） | 长期记忆按会话隔离 |
| 服务端 | `sandboxSelfBootstrap(boolean)` | `true`（SERVER 为 `false`） | 是否由引擎自行初始化进程级 OS 沙盒 |
| 服务端 | `protectedStatePaths(List<String>)` | `sessions, memory, channels, tasks.json, loop, goal` | 相对 harnessHome，模型文件工具与 bash 都不得读写 |

> 三个拦截器位（压缩 / 刹车 / HITL）不配时都会在**构造期自建默认实现**，自建时机早于 `build()` 末尾的 `extension.onAttach(engine)`——所以扩展在 `onAttach` 里通过 `engine.getCompressionInterceptor()` 等拿到的必是最终实例。

### 1.3 跑第一轮对话

L0（推荐）：交给骨架，一问一答不用写任何回调：

```java
HarnessHost host = HarnessHost.builder().engines(tenant -> engine).build();
host.openSession("default", "demo", null);

RunOutcome o = host.submit("default", "demo", HarnessInput.of("看下当前项目结构"))
        .getRun().await(Duration.ofMinutes(5));
System.out.println(o.getResultText());
```

推送型（WebSocket / IM）覆盖 `HostSink` 的 `reply` / `event` 两个出口，见 [host README](../solon-ai-harness-host/README.md)。

L1（只引 core）：直接调引擎，验证环境可用的最简写法：

```java
AgentSession session = engine.getSession("cli");

engine.handle(HarnessInput.of(session, "看下当前项目结构"))
        .stream().blockLast();        // 事件流；也可 subscribe 逐条渲染

String answer = engine.handle(HarnessInput.of(session, "总结一下这个仓库"))
        .call()                       // 等价 getRequest().call()；非 TASK 立即 IllegalStateException
        .getContent();
```

> 这两段只为验证环境。自己编排真产品时用 [ENGINE-L1](docs/ENGINE-L1.md) §3.1 的骨架——
> 它把 `handle` 与 `InputOutcome` 拆成两段并处理了全部六种 `Kind`。

---

## 二、核心 API：`handle` + `InputOutcome`（L1，已移至 ENGINE-L1）

完整内容见 [ENGINE-L1.md 第二章](docs/ENGINE-L1.md)。用 L0（`HarnessHost`）时不需要读。要点：

- `engine.handle(HarnessInput)` 是唯一输入入口：受理判定、HITL 决策、命令识别执行、子代理解析、Prompt 构建、请求装配在一次调用内**同步**完成，**不自启推理**；
- `InputOutcome.getKind()` 六态 `TASK` / `REPLY` / `DEFERRED` / `REJECTED` / `HITL` / `IGNORED`，一个值对应一个宿主动作；「是不是命令」是事实，读 `isCommand()`；
- 取流只有一条路径：`outcome.getRequest().stream()`（简写 `outcome.stream()`，仅 `TASK` 有效）；
- `HarnessInput` 的常用开关：`commandAware` / `agentAware` / `terminal` / `resume`，以及视野选择 `selectedSkill` / `selectedMcp`。

## 三、完整示例：两段式宿主（L1，已移至 ENGINE-L1）

完整内容见 [ENGINE-L1.md 第三章](docs/ENGINE-L1.md)：§3.1 最小骨架、§3.2 完整宿主（六态都处理）、§3.3 事件类型一览、§3.4 四端时序对照、
§3.5 HITL 续流的四条不变量、§3.6 受理控制（占位、HITL 挂起拦截、忙态放行、`isBusy` / `isOccupied` / `hasPendingHitl` 三种口径）。

---

## 四、完整示例：命令

### 4.1 引擎不自带命令，宿主负责注册

`harness` 提供了命令框架与一批实现：通用基础命令在 `command.builtin`（`help`/`clear`/`model`/`rewind`/`rerun`/`continue`/`interrupt`），会话类命令随各插件自包含（`extension.goal.GoalCommand`、`extension.loop.LoopCommand`、`extension.queue.QueueCommand`、`extension.steer.SteerCommand`，由各自 Extension 在 `onAttach` 注册），基础命令**不会自动注册**——因为「哪些命令可用」是端特性。裸引擎上 `handle(session, "/help")` 返回 `TASK` 而不是 `REPLY`。

```java
CommandRegistry registry = engine.getCommandRegistry();

// 1) 从挂载来源加载 Markdown 自定义命令（本地/db/http 均可）
registry.load(FileMountSource.of(Paths.get(userHome, engine.getSettings().getHarnessCommands())), ""); // ~/.solon/commands/
registry.load(engine.getWorkspaceSource(), engine.getSettings().getHarnessCommands());                // ./.solon/commands/

// 2) 注册代码实现的命令
registry.register(new HelpCommand());
registry.register(new ClearCommand());
registry.register(new ModelCommand());
```

### 4.2 写一个命令（代码方式）

```java
public class StatusCommand implements Command {
    @Override
    public String name() {
        return "status";                     // 不含 "/" 前缀
    }

    @Override
    public String description() {
        return "显示当前会话状态";
    }

    @Override
    public String[] examples() {
        return new String[]{"/status"};
    }

    @Override
    public boolean terminalOnly() {
        return false;                        // true 时无终端能力的渠道（Web/IM/桌面）不展示也不执行
    }

    @Override
    public boolean runnableWhenBusy() {
        return true;                         // 只读命令可在任务执行中运行
    }

    @Override
    public void execute(HarnessEngine engine, CommandContext ctx, InputOrigin origin) {
        ctx.println("session = " + ctx.getSession().getSessionId());
        ctx.println("args    = " + ctx.getArgsJoined());
        ctx.println("color   = " + ctx.color("\033[32mOK\033[0m"));
    }
}

engine.getCommandRegistry().register(new StatusCommand());
```

`CommandContext` 是端适配层：CLI 实现可实时写终端并读键盘（`/model` 选择器、`/help` 分页），Web 实现可以是内存缓冲。

**命令内部要触发 Agent 任务**时调 `ctx.requestAgentTask(prompt, model)`（只声明意图，不自己开流）：

```java
@Override
public void execute(HarnessEngine engine, CommandContext ctx, InputOrigin origin) {
    // 第二个参数为模型名；传 null 则走会话级回退
    ctx.requestAgentTask("请审查本次 diff 里的并发问题", "deepseek");
}
```

> **`execute` 只有三参一形**：`origin` 是本次输入在渠道侧的身份（无来源时为 `InputOrigin.NONE`）。
> 不需要来源的命令照样写三参、忽略它即可；`/queue`、`/steer` 靠它把来源带到投递链路。

分发器会把这个意图交回引擎的同一个入口 `engine.handle(...)` 完成二段装配（`commandAware(false)` 不再识别命令、`resume(...)` 让空正文成为续流，因此不用另开方法），
`InputOutcome.getKind()` 因此返回 `TASK`、`isCommand()` 为 true，`getRequest()` 直接可用——
宿主按 `kind` 分路由即可，不要自己再拼 Prompt 或开流。

意图只有两个字段：`prompt`（<b>为空表示续流</b>，如 `/continue`）、`model`（为空走会话级回退）。
「是否续流」由 `prompt` 为空派生（`AgentTaskIntent.isResume()`），不是独立字段——
引擎侧以 `HarnessInput.resume(true)` 接住，所以宿主不用为续流命令单写分支。

### 4.3 写一个命令（Markdown 方式）

放在 `<harnessHome>/commands/` 下即自动生效：

```markdown
---
description: 创建一个 git 提交
argument-hint: [message]
model: deepseek
---

检查 `git status` 与 `git diff`，然后用下面的信息创建提交：

$ARGUMENTS
```

变量替换规则：

| 变量 | 含义 |
|---|---|
| `$ARGUMENTS` | 全部参数拼接为单个字符串 |
| `$1` `$2` `$3` ... | 按位置取单个参数 |

子目录即命名空间：`commands/deploy/staging.md` → `/deploy:staging`。

### 4.4 自定义命令上下文（宿主接管回执）

`commandContextFactory(...)` **是可选项**：不设时引擎自动用 `new BufferedCommandContextFactory()`，
回执写内存、从 `getCommandOutput()` 一次取回——[ENGINE-L1](docs/ENGINE-L1.md) §3.1 的最小骨架就是靠这个默认值跑通 `/help` 的。
**命令内部声明的 Agent 任务同样会真实执行**——`CommandDispatcher` 在 `dispatch` 尾部直接调
`engine.handle(...)` 完成二段装配，与是否有自定义工厂无关。

需要接管回执输出方式（实时写终端、自定义分页）时才提供工厂：

```java
InputOutcome outcome = engine.handle(
        HarnessInput.of(session, "/help")
                .commandContextFactory((engine0, session0, command, terminal) ->
                        new MyTerminalCommandContext(session0, command, terminal)));
```

终端能力是**输入属性**，不是工厂属性。`HarnessInput.terminal(true)` 决定两件事：
`terminalOnly()` 的命令是否放行，以及 `CommandContext.isTerminal()` 的返回值。
两者同源——工厂只负责构造上下文，不再持有渠道能力。

> 默认 `terminal=false`。CLI 宿主必须把 `.terminal(true)` 显式打开，否则 `/help` 会裁掉 `terminalOnly` 命令，
> `/exit` 也会被统一拦截。桌面端虽在本地运行，同样保持默认：它没有命令行终端。

### 4.5 改前缀

```java
HarnessEngine.of("/data/work", ".solon/")
        .commandPrefix("::")   // 默认 "/"
        .agentPrefix("#")      // 默认 "@"
```

改前缀后原前缀自动降级为普通文本。

---

## 五、goal / queue / steer / loop（+ 中断干预）

这四个插件**跨轮维护会话级状态**，命令只改状态、只声明意图，真正的执行与投递要有人接线：

- **L0**：`HarnessHost` 代办续发积压、goal / loop 后台轮次、停流与管理门面（`host.admin(tenant).call(...)`），见 [host README](../solon-ai-harness-host/README.md)；
- **自己接线（L1）**：插件协作契约、「命令文本 / `handle` / 门面」三条到达路径、goal / queue / steer 的宿主责任、停 / 改 / 重排的干预闭环，
  见 [ENGINE-L1.md 第五章](docs/ENGINE-L1.md)（§5.1 goal、§5.2 queue、§5.3 steer、§5.4 干预）；
- 完整参考见各插件 README：[goal](../solon-ai-harness-goal/README.md) · [loop](../solon-ai-harness-loop/README.md) · [queue](../solon-ai-harness-queue/README.md) · [steer](../solon-ai-harness-steer/README.md)。

装配都在**构建期**用 `Builder.extensionAdd` 完成（STANDALONE / SERVER 通用，SERVER 下 build 之后不能再挂扩展）；不要哪个删哪一行，宿主自己的扩展也是同一写法：

```java
HarnessEngine engine = HarnessEngine.of(ws, ".solon/").stateSource(stateSrc) /* ... */
        .extensionAdd(QueueExtension.builder().maxSize(20).build())       // 会话忙时排队
        .extensionAdd(new SteerExtension())                               // 运行中插话
        .extensionAdd(GoalExtension.builder().roundConcurrency(2).build()) // 按需
        .extensionAdd(LoopExtension.builder().store("loop").quota(sid -> 20).build())  // 按需；挂接时取引擎状态来源
        .build();
```

插件自带的命令（`/goal`、`/queue`、`/steer`、`/loop`）在 `onAttach` 时注册；`/queue`、`/steer` 忙时旁路执行，`/goal`、`/loop` 忙时进积压或被拒绝。

---

## 六、完整示例：子代理输入

### 6.1 三种指定方式与优先级

```
explicitAgent（请求体显式）  >  输入开头的 @提及  >  selectedAgent（选择器兜底）
```

```java
// 方式一：输入里的提及——必须是有效子代理名，否则整段原样交给主代理
engine.handle(HarnessInput.of(session, "@bash 列出所有 Java 文件"));

// 方式二：请求体显式指定（桌面端用法，优先级最高）
engine.handle(HarnessInput.of(session, "列出所有 Java 文件").explicitAgent("bash"));

// 方式三：选择器兜底（前两者都没命中时才生效）
engine.handle(HarnessInput.of(session, "列出所有 Java 文件").selectedAgent("bash"));

// 查看实际生效的子代理
InputOutcome outcome = engine.handle(...);
System.out.println(outcome.getAgentName());   // "bash" 或 null（主代理）
```

提及识别的细节（两条路径行为不同，容易踩坑）：

- 必须有空白边界（`@bash 内容` 有效，`@bash内容` 无效）；
- **提及路径**（方式一）剥掉 `@名字` 后**只跳过一个字符**（`text.substring(idx + 1)`），
  所以 `@bash    x` 剩下的是 `  x`（保留多余空格）；
- **显式路径**（方式二 `explicitAgent`）走 `stripMention`，会吃掉其后**所有**空白，
  `@bash    x` 剩下 `x`；
- 名字不存在时不剥离，整段交给主代理自己判断；
- 需要两者一致时加 `.trimContent(true)`。

**需要提前拿到解析结果时**（典型：要在交给引擎前改写正文，如桌面端的 Goal 拦截、
名字格式校验、规划模式前缀），不要自己拼 `AgentMentions.Options`——那会让同一段输入
被解析两次且口径可能不同。用 `HarnessInput#parseMention`，它与 `handle` 内部走同一个入口：

```java
// 宿主提前解析：与 handle 内部逐字一致
AgentMentions.Mention mention = HarnessInput.of(session, raw)
        .explicitAgent(requestedAgent)        // 可空
        .agentNameValidator(name -> ...)      // 可空
        .trimContent(true)                    // 桌面端：输入框允许换行
        .parseMention(engine);

String text = mention.getText();       // 剥离提及后的正文，可安全用于改写
String agent = mention.getAgentName(); // 无有效提及时为 null
```

改写后重新构造输入即可（二次解析幂等，不会重复剥离）：

```java
engine.handle(HarnessInput.of(session, text).explicitAgent(agent).trimContent(true));
```

### 6.2 用引擎装配好的请求执行

```java
InputOutcome outcome = engine.handle(HarnessInput.of(session, "@explore 找一下限流相关的实现")
        .selectedModel("deepseek"));

// 换本会话的工作目录：走门面（受 cwdPolicy 约束），引擎装配请求时自动注入
engine.setSessionCwd(session, "/data/work/sub-project");

outcome.getRequest().stream().blockLast();
```

> 不要再用 `request.options(o -> o.toolContextPut(HarnessEngine.ATTR_CWD, ...))` 换目录：它绕过 `cwdPolicy`，
> 多租户下等于把任意目录加进文件工具根与沙盒白名单。

### 6.3 直接用内置子代理

内置定义随包发布在 `META-INF/solon/ai/harness/agents/`：

| 代理 | 工具集 | 定位 |
|---|---|---|
| `general` | `*` | 通用全能，其它子代理不合适时的默认解 |
| `bash` | `list, read, bash` | 命令行执行（无文件写权限） |
| `explore` | `code, list, read, grep, glob, skill, webfetch, websearch, codesearch` | 信息探索（无写权限） |
| `plan` | `list, read, grep, glob, skill, webfetch, websearch, codesearch` | 方案规划（无写权限） |
| `git-summary` | 无（`hidden: true`） | 生成提交摘要 |

```java
// 方式一（推荐）：走上章的两段式，由引擎装配请求（含附件/命令识别）
InputOutcome outcome = engine.handle(
        HarnessInput.of(session, "统计各语言的代码行数")
                .explicitAgent("bash"));

outcome.getRequest().stream().blockLast();
```

> 直接用 `createSubagent(def).build()` 拿到的 `ReActAgent` 也能独立调用
> （`createSubagent` 只是把定义交给 `ReActAgent.Builder`），但那样就绕过了
> `handle` 的命令识别与附件装配，也只适用于「不走统一入口」的特例场景。
> 注意 该路径的 `call()` 声明的异常是 `Throwable`，需自行捕获；
> 走 `handle` 路径时异常从流的 `doOnError` / `blockLast()` 出来。

### 6.4 运行时创建子代理

面向模型的 `generate` 工具按是否持久化分两级：

| `saveToFile` | 可见范围 | 存放位置 | 约束 |
|---|---|---|---|
| `false`（缺省） | 只有创建它的会话 | 会话上下文（`session.agents`），随会话快照保存，重启后仍可调用 | 每会话最多 16 个；不得与全局子代理同名 |
| `true` | 本引擎所有会话 | 工作区代理目录 `<harnessAgents>/<name>.md` | 受 `agentPersistPolicy` 约束（STANDALONE 缺省允许、SERVER 缺省拒绝）；同名文件或同名代理已存在时拒绝，不覆盖 |

两级都不允许使用保留名（`main`、`general` 及内置挂载提供的代理名，见 `engine.isReservedAgentName`）。会话级代理在 `task` / `multitask`、`@` 提及、
`explicitAgent` 里都能解析到（统一入口 `engine.resolveAgent(session, name)`：先会话级，再引擎级）。

```java
// 一行式：复用 general 定义改写（面向 LLM 的 generate 工具走的就是这条）
engine.getTalents().getGenerate().generate(
        "code_reviewer",                        // name（仅字母数字_-）
        "代码审查专家",                          // description
        "你是一位严格的代码审查专家，只指出真实缺陷。",   // systemPrompt
        Arrays.asList("read", "grep", "code"),  // tools（严禁全选）
        null,                                   // skills
        false,                                  // saveToFile：false = 仅本会话；true = 全局持久化
        engine.getWorkspace(),                  // __cwd
        session.getSessionId());                // __sessionId（会话级代理必需）

// Builder 式（toolsAdd 收 List<String> 或 String...，不收 ToolName）
AgentDefinition definition = AgentDefinition.builder()
        .name("frontend")
        .description("前端专家")
        .systemPrompt("你是前端专家")
        .toolsAdd(ToolName.TOOL_READ.getName(), ToolName.TOOL_GREP.getName())
        .build();

// ★ 必须注册，否则下面 handle 里的 "frontend" 不存在，整段会回落到主代理
engine.getAgentManager().addAgent(definition);

engine.handle(HarnessInput.of(session, "分析 src/main.js").explicitAgent("frontend"));
```

### 6.5 主代理委派（`task` / `multitask`）

主代理通过工具自动委派，宿主无需写代码：

```java
// 工具集含 task 时（* 或 ** 或显式 task），主代理可调用：
//   task              —— 委派单个子代理，串行
//   multitask         —— 并行委派多个子代理，并行度按每个任务实际路由到的模型分别限流
//                生效上限 = 模型配置 concurrency（0=未设置，配了就用它）?: subagentDefaultConcurrency（默认 5，取值 1~10）
//                不存在「不限制」：生效上限恒为有限正整数（具体理由见下方注记）
//                模型配了就以模型为准；模型没配才用默认值。超限不报错，在调用线程排队，有空位就提交
//                排队时「谁的目标模型有空配额就提交谁」，低配额模型不会挡住其他模型的任务
engine.getSettings().setSubagentEnabled(true);   // 默认 true
engine.getSettings().setSubagentDefaultConcurrency(5);   // 子代理任务默认并发上限（模型未配置时生效）

// 模型级并发上限：按模型配额差异配置（如免费中转 Key 配 2、官方 Key 配 8）
// 语义与 contextLength 的「0=未设置」一致；配了就完全覆盖默认值，不再取 min
config.setConcurrency(2);
```

> 4.2 起并行上限的语义与命名都做了收敛：
> - `multitaskMaxTasks` → `subagentDefaultConcurrency`（旧名 `multitaskMaxTasks`、中间名
>   `multitaskMaxConcurrency` 均保留为 `@Deprecated` 桥接，行为一致）
>   **但存量语义已变**：旧版该值只用于生成提示词里「单次建议 2-N 个」的文案，并不真正限流；
>   新版它是真实的默认并发上限（模型未配置 `concurrency` 时生效），且不再参与提示词。
>   升级后该值的实际影响力变强，存量配置建议复核。
> - `ChatConfig#maxParallelTasks` → `AiConfig#concurrency`，且 **0 的含义由「不限制」改为「未设置」**，
>   与 `contextLength` 对齐：未设置时回退 `subagentDefaultConcurrency`
> - 不再是「两级同时生效取 min」。此前模型配 8 却只跑 5 且需查日志才知道原因的问题不再存在
>
> 注意：**不存在「不限制」这个档位**——生效上限恒为有限正整数。原因是「不限」在物理上不成立：
> 线程数、上游 QPS、堆内存会退化为只受任务数约束，恰好是最容易打爆上游（429）与 OOM 的形态；
> 且信号量一旦缺席，「模型级 `concurrency`」也就形同虚设。
>
> `subagentDefaultConcurrency` 的取值契约：`1~10` 原样生效（缺省 5）；**`0` 视为「未设置」取默认 5**
> （与 `contextLength`、`concurrency` 的 0=未设置 同构）；超出 10 按 10 处理；负数报错。
> 归一（0 或超上界）都会打 WARN，不会「配了却没生效」而无声。
> 需要更高并发请显式配置模型级 `AiConfig#concurrency`——那是你为某个模型亲自写下的具体数字，
> 意图与责任都明确；把「默认值」调大只会让所有未调优模型一起失去护栏。
> <br/>（若确实需要远超 10 的并发，请逐个模型写 `concurrency`——那不属于「默认值」的职责。）
>
> 另：限流是引擎级、跨会话共享的，`task` 单任务调用与 `multitask` 的并行/串行快路径都会取槽——
> 否则多个会话各跑一个 `task` 时同一个模型上的并发会失去约束。
>
> 上限可在运行期调整：调低时不会打断已在跑的任务，但会在在跑数降到新上限之前暂停放行新任务
> （不会像「新建信号量」那样瞬时超额）。
>
> 结果外壳：每个子任务包在 `<task_result>` 里，`<result_content>` 用 CDATA 包裹并**有 20000 字符上限**
> ——否则单个超长的子代理输出就能灌爆父会话上下文。
> 超出上限时完整结果会落盘到 `<workspace>/.files/task/<task_id>/result.txt`，
> 头部保留截断说明并给出该 workspace 相对路径，供宿主的文件读取工具（如 read）按 offset/limit
> 分页读回，无需重新委派（harness 不提供专属召回工具，也不假设宿主一定有文件工具）；
> 落盘不可用（无 workspace 或写入失败）时退回纯截断，行为与历史版本一致。
> `{@code .files}` 是与宿主共享的 workspace 级文件根（宿主的上传附件落 `.files/uploads`）。
> 内容里的 `]]>` 会被拆开而不会提前闭合 CDATA；`description`/`agent_name` 会做 XML 转义。
> 排队超过 30 秒会打一条 WARN（带模型名），但**不会超时终止**（会误杀长任务）。
> 用户取消时，父代理不再逐个等到底：会中断在跑的子任务并不再等待剩下的任务。

`task` 默认不在 `*`（公有）里，只有 `**`（完整）才包含——这是为了让子代理不能再委派子代理，天然防止无限嵌套。

### 6.6 把子代理定义成文件

`<harnessHome>/agents/*.md` 或挂载到 `MountType.AGENTS` 的目录：

```markdown
---
name: "reviewer"
description: "代码审查专家：只读不写，专注缺陷识别"
tools: ["read", "grep", "glob", "code"]
model: "deepseek"
disallowedTools: ["bash", "write"]
hidden: false
primary: false
enabled: true
---

你是一位严格的代码审查专家。

输出规范：

- 只列出真实缺陷，按严重程度排序。
- 禁止修改文件，禁止执行命令。
```

字段说明（`AgentDefinition.Metadata`）：

| 字段 | 说明 |
|---|---|
| `name` / `description` | 必需 |
| `tools` | 工具名或快捷权限 `pi` / `*` / `**` |
| `disallowedTools` | 在 `tools` 结果上做减法 |
| `model` | 指定模型 |
| `hidden` | 不在列表里展示 |
| `primary` | 是否为主代理。**只有主代理能使用 `hitl`**：子代理即使配了 `hitl` 或 `**`（会展开出 `hitl`）也不会被挂载，因为子代理挂起后无人能决策，而父代理正阻塞等待它（互等不可解） |
| `enabled` | 是否启用 |
| `permissionRules` | 权限规则（`toolName` / `behavior` / `pattern` / `priority`） |

> `skills` / `mcpServers` / `memory` / `hooks` / `background` / `isolation` / `teamName` 已标记 `@Deprecated`，保留字段但不再解析。技能与 MCP 现在走**挂载**与 `engine.addMcpServer(...)`。

> 子代理同样只能用一层委派（不能再 `task` / `multitask`），且**不能使用 `hitl`**（装配期就不会挂载）。

---

## 七、工具权限

### 7.1 三级快捷权限

| 权限 | 标识 | 实际展开 |
|---|---|---|
| 核心操作 | `pi` | `read, write, edit, bash` |
| 全部公有 | `*` | `read, write, edit, glob, grep, ls, bash, skill, todo, code, codesearch, websearch, webfetch, lsp` |
| 全部完整 | `**` | `*` + `task, generate, mcp, openapi, hitl, memory` |

以上标识均定义在 `ToolName`（`@Preview("4.0.4")`），如 `ToolName.TOOL_ALL_PUBLIC`。

### 7.2 运行时调整

```java
engine.allowTool("bash");            // 触发主代理重建
engine.disallowTool("write");
engine.toolPermissionReset(tools, disallowedTools);
```

---

## 八、配置项参考

### 8.1 构造期

> 与 §1.2 的 builder 配置表同源，此处为完整语义参考。`deployMode(SERVER)` 会改变部分缺省值（显式值 > 预设 > 默认，与调用顺序无关）。

| 配置 | 默认值 | 说明 |
|---|---|---|
| `of(workspace, harnessHome)` | `harnessHome = ".solon/"` | 工作区与引擎根目录 |
| `sessionProvider(...)` | — | **必填** |
| `systemPrompt(...)` | `null` | 主代理系统提示词 |
| `modelAdd(...)` / `defaultModel(...)` | — | 模型与默认模型 |
| `toolsAdd(...)` / `disallowedToolsAdd(...)` | 空 | 主代理工具权限 |
| `maxTurns(Integer)` | `20` | 最大推理-行动轮次 |
| `autoRethink(Boolean)` | `true` | 失败后自动重试 |
| `sessionWindowSize(Integer)` | `12` | 保留最近 N 轮消息 |
| `compressionThreshold(maxMessages, ratio)` | `100` / `0.75` | 触发历史压缩 |
| `compressionDefaultContextLength(Long)` | `128000` | 未知上下文长度时按此估算 |
| `memoryEnabled(Boolean)` | `true` | 长期记忆 |
| `memoryRelevanceCount(int)` | `6` | 相关性召回条数 |
| `memoryPriorityCount(int)` | `5` | 高优先度条目数 |
| `memorySummaryLength(int)` | `80` | 条目摘要长度 |
| `stopLoopInterceptor(...)` | `StopLoopInterceptor(3, 8)` | 重复调用刹车（阈值, 窗口），不配时构造期自建 |
| `compressionInterceptor(...)` | 引擎自建 | 替换上下文压缩拦截器（§1.2 表） |
| `cacheControl(CacheControl)` | `null` | 提示词缓存控制 |
| `userAgent(String)` | `null` | 引擎级 User-Agent |
| `httpProxy(...)` / `httpCustomizeAdd/Set(...)` | 直连 / — | 引擎级代理与 HTTP 定制钩子（4.0.5） |
| `memoryProvider(MemorySolutionProvider)` | — | 长期记忆存储方案；不注入则没有 memory 工具可开关 |
| `hitlInterceptor(HITLInterceptor)` | 引擎自建 | 不配时默认只绑 `BashToolStrategy` 到 `bash` |
| `permissionRuleAdd(PermissionRule)` | 空 | 权限规则 |
| `extensionAdd(...)` / `mountAdd(...)` / `mcpServerAdd(...)` / `apiServerAdd(...)` / `lspServerAdd(...)` | 空 / — | 扩展与挂载、MCP / OpenAPI / LSP 资源 |
| `sandboxEnabled(Boolean)` | `true` | 文件系统沙箱 |
| `sandboxAllowUserHome(Boolean)` | `false` | 允许访问用户主目录 |
| `sandboxSystemRestrict(Boolean)` | `true` | 禁止访问系统路径 |
| `hitlEnabled(Boolean)` | `false` | HITL 拦截 |
| `subagentEnabled(Boolean)` | `true` | 子代理功能 |
| `bashAsyncEnabled(Boolean)` | `false` | Bash 异步执行 |
| `apiRetries` / `mcpRetries` / `modelRetries` | `3` | 三类重试 |
| `subagentDefaultConcurrency(Integer)` | `5` | 子代理任务默认并发上限，取值 `1~10`（`0`=未设置取默认 5，超 10 按 10，负数报错），**不存在「不限制」**；仅在模型未配置 `AiConfig#concurrency` 时生效（模型配了以模型为准）。超限任务排队，不报错。`task` 与 `multitask` 均取槽（信号量引擎级、跨会话共享） |
| `admissionTimeoutMs(Long)` | `60000` | 受理占位时限 |
| `stateSource(MountSource)` | `<workspace>/<harnessHome>` | harness 状态来源（算存分离） |
| `deployMode` / `scopeResolver` / `scopeRequired` / `sessionOwnership` / `cwdPolicy` / `runtimeMutable` / `memorySessionIsolation` / `sandboxSelfBootstrap` / `protectedStatePaths` | 见 §1.2 | 服务端与多租户配置，逐项语义见 §1.2 配置表「服务端」各行，部署取舍见 [SAAS.md](docs/SAAS.md) |
| `commandPrefix(String)` | `"/"` | 支持多字符 |
| `agentPrefix(String)` | `"@"` | 支持多字符 |

### 8.2 运行期

```java
engine.getSettings().setSandboxEnabled(false);   // 运行期配置统一经 engine.getSettings()；内置能力经 engine.getTalents()
engine.setDefaultModel("deepseek");      // 触发主代理重建
engine.addModel(new ChatConfig(...));
engine.removeModel("gpt-4");

engine.interrupt(session, InterruptMode.INTERRUPT_ALL);               // 中断当前任务（连带清队列），true = 确实停了
engine.handle(HarnessInput.of(session, null).resume(true));   // 续跑挂起的工作记忆（engine.resume()/prompt() 已废弃：它们绕过受理、归属与作用域）

// 扩展开关：true/false/null（null = 不改变）；会先传播给子组件，再重建主代理
engine.setExtensionEnabled(goalExtension, false);

// 引擎释放：遍历回调每个扩展的 onDetach（扩展自己回收引擎之外的资源）+ 关闭子代理执行器
engine.shutdown();

engine.addMcpServer("slack", mcpServerParams);
engine.addApiServer(apiSource);
engine.addLspServer("java-lsp", lspServerParams);

engine.addMount(Mount.builder()
        .alias("@project-agents")
        .type(MountType.AGENTS)
        .source(FileMountSource.of(Paths.get("project-agents")))
        .build());
```

---

## 九、扩展点

```java
public interface HarnessExtension extends AiSwitchable {
    /** ① 引擎级装配：build() 一次 + addExtension 再一次（此刻任意引擎方法可安全调用） */
    default void onAttach(HarnessEngine engine) { }

    /** ⑤ 引擎级清理：removeExtension / shutdown 时回调（回收引擎外部资源） */
    default void onDetach(HarnessEngine engine) { }

    /** ② 代理级配置：每次构建代理（含主代理、含按请求新建的子代理）时触发 */
    default void onAgentBuild(HarnessEngine engine, String agentName, ReActAgent.Builder agentBuilder) { }

    /** ③ 输入级观察：handle() 受理判定通过之后、作用域解析与 HITL/续流/判空/命令分发之前 */
    default void onInput(HarnessEngine engine, HarnessInput input) { }

    /** ④ 请求级装配：每次 ReActRequest 装配（覆盖用户输入/HITL 恢复/续流/命令产物/子代理委派） */
    default void onRequestAssemble(HarnessEngine engine, String agentName,
                                   boolean isDelegated, ReActOptionsAmend optionsAmend) { }

    /** ⑥ 会话中断：interrupt / prepareInterrupt 先于停流回调（清积压等） */
    default void onSessionInterrupt(HarnessEngine engine, AgentSession session) { }

    /** ⑦ 会话状态：engine.status(session) 时收集插件事实（只读、廉价、无副作用） */
    default void contributeSessionStatus(HarnessEngine engine, AgentSession session, SessionStatus.Builder builder) { }

    /** ⑧ 会话删除：engine.removeSession(sessionId) 时回调（撤掉该会话的定时任务；只给 id，不得 getSession） */
    default void onSessionRemove(HarnessEngine engine, String sessionId) { }

    /** ⑨ 静默判定：engine.isQuiescent() 时收集（还有后台工作就返回 false，引擎池不会回收） */
    default boolean isQuiescent(HarnessEngine engine) { return true; }

    /** ⑩ 工具声明：作用域守卫据此放行本扩展自带的工具（不受 InputScope.tools 名单约束） */
    default Collection<String> getToolNames() { return Collections.emptyList(); }
}
```

**十个钩子全部 default**，只覆写要用的那几个。命名约定：`on*` 是引擎发通知，`contribute*` 是引擎收集信息，带 `Session` 的是会话级回调：

```java
engine.addExtension(new HarnessExtension() {
    @Override
    public void onAttach(HarnessEngine engine) {
        // 注册命令 / 挂载 / MCP·LSP 连接 / 建调度器等引擎级资源
    }

    @Override
    public void onDetach(HarnessEngine engine) {
        // 停掉 onAttach 里建到引擎之外的调度 job、线程池、长连接
    }

    @Override
    public void onAgentBuild(HarnessEngine engine, String agentName, ReActAgent.Builder builder) {
        // 代理级默认值：只影响该代理实例的每次请求
        if (AgentDefinition.AGENT_MAIN.equals(agentName)) {
            builder.defaultInterceptorAdd(myInterceptor);
        }
    }

    @Override
    public void onRequestAssemble(HarnessEngine engine, String agentName,
                                  boolean isDelegated, ReActOptionsAmend o) {
        // 请求级装配：isDelegated=true 是 task/multitask 委派（临时会话），会话级能力不要挂
        if (isDelegated == false) {
            o.interceptorAdd(myRequestInterceptor);
        }
    }
});
```

#### 钩子时序与调用点

```
引擎构建 ──onAttach──> [引擎就绪] ──onAgentBuild──> [代理实例]
                                        │
                    输入通过受理判定 ──onInput──────────> [HITL/命令/委派]
                          每次请求装配 ──onRequestAssemble──> [ReActRequest]
                          用户级中断   ──onSessionInterrupt─> [先清积压再停流]
                          读会话状态   ──contributeSessionStatus─> [插件事实]
                          永久删除会话 ──onSessionRemove────> [撤掉该会话的定时任务]
                                        │
                          removeExtension/shutdown ──onDetach──> [清理]
```

| 钩子 | 调用点 | 判 `isEnabled()` |
|---|---|---|
| `onAttach` | `Builder.build()`；`addExtension()` | **否**（装配必须完成，停用时改回 true 才能挂上） |
| `onDetach` | `removeExtension()`；`shutdown()` | **否**（清理不得被开关阻止） |
| `onAgentBuild` | `AgentFactory.create()` 末尾，无条件 | **否**（停用由扩展自己传播给子组件） |
| `onInput` | `handle()` 第 0 步受理判定通过之后（被排队 / 拒绝的输入不触发；排队的输入续发时触发一次） | 是 |
| `onRequestAssemble` | `ReActRequest.options(...)` 内 | 是 |
| `onSessionInterrupt` | `interrupt()` / `prepareInterrupt()`，先于停流 | **否**（清理类） |
| `contributeSessionStatus` | `status()` | **否**（事实类） |
| `onSessionRemove` | `removeSession()`（永久删除；只移出内存不触发） | **否**（清理类） |
| `isQuiescent` | `engine.isQuiescent()`（引擎池回收前） | **否**（事实类；异常按「不静默」） |
| `getToolNames` | 作用域守卫 | **否**（声明类） |

**删除会话的固定顺序**（宿主）：判忙（忙则拒绝）→ `engine.removeSession(id)` → 移出内存 → 删除存储。`removeSession` 必须在删存储之前，否则定时任务可能在中间触发，把会话重新建出来。只想取「已存在」的会话（定时任务、只读查询）用 `engine.getSessionOrNull(id)`，它不会新建。

**会话提供者**（`AgentSessionProvider#getSession(id, createIfAbsent)`）：`createIfAbsent=false` 且会话不存在时必须返回 null 且无副作用（不建实例、不写存储、不放缓存）。生产实现应缓存实例：

```java
Map<String, AgentSession> map = new ConcurrentHashMap<>();
AgentSessionProvider provider = (id, create) -> create
        ? map.computeIfAbsent(id, InMemoryAgentSession::of)
        : map.get(id);
```

#### `onAgentBuild` vs `onRequestAssemble` 的分工

这是最容易选错的一对：

| | `onAgentBuild` | `onRequestAssemble` |
|---|---|---|
| 作用域 | **代理实例**（同一代理的每次请求都生效） | **单次请求** |
| 触发频率 | 主代理重建时（改模型/开关扩展）+ 每次子代理新建 | 每次请求（含 HITL 恢复、`/continue`、命令产物、委派任务） |
| 常用方法 | `defaultTalentAdd` / `defaultInterceptorAdd` / `systemPrompt` | `talentAdd` / `interceptorAdd` / `maxTurns` / `retryConfig` |
| 能否区分「委派任务」 | **不能**（不知道本次是谁在跑） | **能**（`isDelegated`） |
| 热路径约束 | 是（子代理按请求新建，须廉价） | 是 |

**选型口诀**：要「这个代理每次都带上」→ `onAgentBuild`；要「只挂这一次」或「子代理不挂」→ `onRequestAssemble`。

#### `onRequestAssemble` 覆盖的四类请求

| 场景 | `isDelegated` | `agentName` | 会话 |
|---|---|---|---|
| 用户新输入（主代理） | `false` | 主代理名 | 业务会话 |
| 用户新输入（**面板/`@` 选中的子代理**） | `false` | **子代理名** | 业务会话 |
| HITL 恢复 / `/continue` / 队列续发 | `false` | 生效代理名 | 业务会话 |
| `task` / `multitask` 委派 | **`true`** | 子代理名 | 临时 `InMemoryAgentSession` |

> **为什么面板选中的子代理是 `isDelegated = false`**：它是**顶层请求**，只是目标代理换成了子代理。
> 会话能力（如 steer 插话、queue 排队）必须对它生效——这正是把插话拦截器从「主代理默认拦截器」
> 迁到请求级 `onRequestAssemble` 的原因：挂在主代理上时，选了子代理就再也命不中，插话会被静默降级为排队。
> 反过来，`isDelegated = true` 的委派任务跑在临时会话上，没有前端邮箱语义，必须排除。

#### `onInput` 的能力边界

返回 `void`——**只能观察与就地改写，不能否决**（想「拦截并改成另一种结局」请走命令分发或代理侧拦截器）。
`HarnessInput` 的模型/代理/技能等选择项有 setter，可在钩子内补默认值，这是 `void` 签名下唯一能改变后续行为的方式。

命令会经内层 `handle` 再走一次（命令产物任务；内层继承外层占位，不再做受理判定），因此 `onInput` 对「命令 + 其产物」会触发**两次**，实现须能容忍重复。被排队或拒绝的输入在受理时不触发 `onInput`，排队的输入在续发时触发一次。

> 注意：`addExtension` 会触发一次 `onAttach`，此刻引擎已装配完毕，任意引擎方法都可安全调用。
> `shutdown()` 会遍历回调 `onDetach`（逐个 try/catch，清理期异常不会打断其余扩展与执行器回收）。

---

## 十、安全机制

### 10.1 门控总览：一次工具调用要过哪几道关

门控分散在三个包（`scope` / `permission` / `hitl`）和几个引擎配置项里，各管一件事。按生效顺序：

| 顺序 | 关卡 | 所在 | 管什么 | 宿主怎么配 |
|---|---|---|---|---|
| 1 | 工具可见性 | `agent.ToolName`、代理定义 `tools` / `disallowedTools` | 模型能看到哪些工具（看不到就调不了） | §7：`Builder.toolsAdd / disallowedToolsAdd`、`engine.allowTool / disallowTool` |
| 2 | 输入级作用域 | `scope.InputScope`（由常驻的 `ScopeGuardInterceptor` 执行，优先级最高） | 这一次输入能用哪些工具 / 技能 / MCP / 模型；越界调用在执行前预填拒绝结果，HITL 也不会为它弹卡 | 引擎级 `scopeResolver`（推荐 `InputScopeResolver.byPrincipal`）或 `HarnessInput.scope(...)` |
| 3 | 权限规则 | `permission.PermissionEngine` / `PermissionRule` | 对工具参数（命令、路径、域名）判 `ALLOW / DENY / ASK` | 由 hitl 策略内部调用；全局规则在策略上配 |
| 4 | 人工审批 | `hitl.*ToolStrategy` + `HITLInterceptor`；`hitl.SessionPermissions` 记住会话级「总是允许」 | 规则判为 ASK 的调用挂起等人决定 | §10.2、`hitlEnabled(true)` |
| 5 | 执行环境 | `TerminalTalent` 沙箱、`protectedStatePaths`、`cwdPolicy` | 工具真正执行时能碰哪些目录、哪些状态文件 | §8 配置项、§10.3、[SAAS.md](docs/SAAS.md) §5.1 / §5.2 |

前两关是「能不能用」，后三关是「用的时候允不允许」。会话归属（`session.SessionOwnership`）不属于工具门控：它在受理之前就决定「这个节点能不能跑这个会话」。

### 10.2 三套策略

`hitl` 包提供三套内置干预策略：

| 策略 | 覆盖 |
|---|---|
| `BashToolStrategy` | 注入防御、系统命令黑名单、路径边界、环境变更分级、网络零信任、管道安全链、破坏性操作 |
| `WriteToolStrategy` | 写入路径防御 + 规则引擎 |
| `WebToolStrategy` | 域名黑名单 + 规则引擎 |

**默认只有 `BashToolStrategy` 绑定到 `bash`**（`initHitlDefault`）。`Write` / `Web` 两套策略需自行绑定，
且两者构造器都要传 `toolName`：

```java
engine.getHitlInterceptor()
        .onTool("write", new WriteToolStrategy("write"))
        .onTool("edit", new WriteToolStrategy("edit"))
        .onTool("webfetch", new WebToolStrategy("webfetch"));
```

启动 HITL 需要**三个条件同时满足**（缺一不会报错，只是不生效；开了 `hitlEnabled` 但工具集里没有 `hitl` 或它被禁用时，`build()` 会打一条告警）：

1. 主代理工具集包含 `hitl`（`**` 里包含）；
2. `hitlEnabled(true)`；
3. 把 `HITLInterceptor` 绑定到具体工具策略。

详见 [`docs/hitl.md`](../solon-ai-harness-core/docs/hitl.md)。

### 10.3 服务端安全底线

多租户部署时，除 HITL 外还有三道引擎级防线：请求级作用域（`InputScope`，工具执行前否决越界调用）、
受保护状态路径（`protectedStatePaths`，文件工具与 OS 沙盒同时拒绝）、会话工作目录策略（`cwdPolicy`）。
各项缺省与语义见 §1.2 配置表「服务端」各行，隔离模型、作用域、会话归属与容量见 [SAAS.md](docs/SAAS.md)；L0 上线前对照 [QUICKSTART-SAAS.md §九 上线检查清单](../solon-ai-harness-host/docs/QUICKSTART-SAAS.md)。

### 10.4 宿主侧拿到审批请求

拦截触发时，事件流会推 `HITLPendingEvent`；也可凭会话直接查：

```java
// 事件流里拿（推荐，实时）
if (e instanceof HITLPendingEvent) {
    for (HITLTask t : ((HITLPendingEvent) e).getPendingTasks()) {
        renderApproval(t.getCallUuid(), t.getToolName(), t.getArgs());
    }
}

// 或从会话里查（重连、刷新后恢复）
List<HITLTask> pending = HITL.getPendingTasks(session);
```

审批与续流的宿主写法见 [ENGINE-L1](docs/ENGINE-L1.md) §3.5（一次 `handle` + `switch (kind)`）。

---

## 十一、运行时目录

```
{harnessHome}/          # 默认 .solon/
├── sessions/           # 会话快照
├── skills/             # 技能（SKILL.md）
├── agents/             # 子代理定义（*.md）
├── commands/           # Markdown 自定义命令
├── memory/             # 长期记忆
└── channels/           # IM 通道配置
```

派生方法（经 `engine.getSettings()`）：`getHarnessHome()` / `getHarnessSessions()` / `getHarnessSkills()` / `getHarnessAgents()` / `getHarnessCommands()` / `getHarnessMemory()` / `getHarnessChannels()`。

> 上面是缺省布局。配了 `stateSource(...)` 后，harness 自己的状态（会话级状态、`goal/active/` 索引、`loop/` 任务）落在该来源里；
> 会话快照的存放位置由 `sessionProvider` 决定。`protectedStatePaths`（缺省 `sessions, memory, channels, tasks.json, loop, goal`，
> 相对 harnessHome）对模型的文件工具与 bash 不可读写。

---

## 十二、模块结构

### 12.1 模块与依赖方向

```
solon-ai-harness            聚合包（只做引用，没有源码）
├── solon-ai-harness-core   核心：不依赖任何插件
├── solon-ai-harness-goal   ─┐
├── solon-ai-harness-loop    │ 各自只依赖 core；插件之间零 import，
├── solon-ai-harness-queue   │ 只经核心钩子 / spi / 会话槽位协作
├── solon-ai-harness-steer  ─┘
└── solon-ai-harness-host   宿主骨架：HarnessHost / HostSink / RunSink，依赖 core + 四个插件（引擎池 HarnessEnginePool 在 core），位于最上层（任何模块不得反向依赖）
```

依赖方向、聚合包无源码、插件内部类不得 public、插件主代码无废弃标记、goal / loop / queue 写门面必须返回 `HarnessResult` 等约束由 `solon-ai-harness-host` 的 `HarnessStructureGuardTest` 守护。

### 12.2 核心包结构（`solon-ai-harness-core`）

```
org.noear.solon.ai.harness
├── HarnessEngine                 ★ 引擎（Builder + 编排 + 唯一入口 handle）
├── DeployMode                    部署模式（STANDALONE / SERVER）
├── HarnessSettings / HarnessTalents   引擎的两个视图：目录布局与运行期配置、内置能力（engine.getSettings() / getTalents()）
├── HarnessOptions                运行时配置（包私有）
├── HarnessExtension / AbsHarnessExtension   扩展 SPI
├── RunTicket / InterruptPlan / InterruptMode   一条流（run）的生命周期凭据、两段式中断计划、一步式停流方式
├── HarnessResult                 插件写门面与管理入口的统一结果（粗码 OK / NOT_FOUND / NOT_OWNER / DISABLED / FULL / INVALID / CONFLICT / FORBIDDEN + 可空细分码 getReason + 自带建议 HTTP 状态码 httpStatus()）
├── InputPipeline / SessionControl / InputGate / Admission / AdmittedRequest / HarnessInterrupts   受理、装配与停流实现（包内，不是 API）
│
├── input/                        输入层：HarnessInput ★、InputOutcome ★（六态）、InputAttachments、InputOrigin、AgentMentions
├── command/                      命令框架：Command / CommandContext / CommandRegistry / CommandDispatcher、Markdown 命令、builtin/
├── agent/                        子代理（一个包覆盖定义 → 注册 → 构建 → 委派）：AgentDefinition / ToolName（定义）、AgentManager / SessionAgents / GenerateTalent（注册与创建）、AgentFactory（构建）、TaskTalent / TaskWrapEvent（委派与子事件）；SubTaskScheduler 包内、SessionAgentsTalent @Internal
├── scope/                        输入级作用域：InputScope / InputScopeResolver / ScopeGuardInterceptor
├── spi/                          插件能力接口（只放接口，可带静态槽位访问方法）：InputBacklog / RunDispatch
├── session/                      会话事实与归属：SessionStatus / HarnessAttrs / SessionSelections；SessionOwnership（宿主实现的租约）/ OwnerCheck / NotOwnerException
├── hitl/                         干预策略（Bash / Write / Web）、HitlCard、会话级「总是允许」
├── permission/                   权限引擎（PermissionRule / PermissionContext / PermissionBehavior / PermissionEngine）
└── util/                         ErrorClassifier / HarnessIds / TraceUtil
```

插件包为 `org.noear.solon.ai.harness.extension.{goal,loop,queue,steer}`，宿主只接触各自的 `XxxExtension` 门面与只读数据类。

### 12.3 依赖包（能力来源）

| 依赖 | 提供 |
|---|---|
| `solon-ai-agent` | `ReActAgent` / `ChatModel` / 压缩与 HITL 拦截器 |
| `solon-ai-talent-cli` | `TerminalTalent`（沙箱文件与命令、`SandboxBootstrap`）、`SkillTalent`、`ClockTalent`；传递引入 `solon-ai-mount`（`MountManager` / `MountSource`） |
| `solon-ai-talent-code` | `CodeTalent`（技术栈识别 / CODE.md） |
| `solon-ai-talent-todo` | `TodoTalent`（会话级任务清单，经状态来源存储；旧坐标 `talents.cli.TodoTalent` 为废弃过渡类） |
| `solon-ai-talent-web` | `WebsearchTalent` / `WebfetchTalent` / `CodeSearchTalent` |
| `solon-ai-talent-lsp` | `LspManager` / `LspTalent` |
| `solon-ai-talent-memory` | `MemoryTalent` / `MemorySolutionProvider` |
| `solon-ai-talent-gateway` | `McpGatewayTalent` / `OpenApiGatewayTalent` |
| `solon-lib` | IoC / 配置 / 生命周期 |
| `solon-serialization-snack4` | JSON 序列化 |

---

## 十三、常见坑

1. **裸引擎没有命令**——`/help` 会返回 `TASK`。测试命令必须先注册：构建期 `Builder.commandAdd(...)` / `commandLoad(source, dir)`（SERVER 模式只能这样，build 末尾注册表封口），或运行期 `getCommandRegistry().register(...)`（仅 `runtimeMutable` 时）；注册后**不需要**配 `commandContextFactory`，默认 `BufferedCommandContextFactory` 已经能把回执交给 `getCommandOutput()`。
2. **HITL 续流必须显式，但一次调用就够**——`Kind.HITL` 不携带请求。三端统一写 `.hitl(action, callId).resume(true)`，引擎自检「决策命中 + 本批齐备 + 显式要求」三项，全成立返回 `TASK`，否则返回 `HITL`（并携带 `isHitlApplied()` / `getPendingHitlCount()` 供宿主判断）。续流时**不要**传正文。
3. **HITL 续流要用 `outcome.getAgentName()`**——它是「本轮生效代理」（显式入参 → `engine.getSessionAgent` 兜底）。换回主代理会取不到挂起的 `tool_calls`，续跑空转。
4. **`@` 提及必须带空白边界**——`@bash 内容` 有效，`@bash内容` 走普通文本。
5. **有附件时不识别命令**——避免 `/path/to/file.png` 之类被误判；带附件的 `/cmd` 按普通消息处理，附件正常落盘。
6. **`options(Consumer)` 是累加语义**——`getRequest()` 之后再追加 `options` 是安全的叠加。
7. **`getRequest().stream()` 是冷流**——何时订阅、在哪个线程订阅由宿主决定；需要切换线程请自行 `subscribeOn`。
8. **`STANDALONE` 模式下 `build()` 只校验 `sessionProvider`**——缺模型时 `build()` 只告警，构建后仍没补上的话要到首次 `handle` 才报 `chatModel is required`；`SERVER` 模式在构建期就报。
9. **HITL 默认只绑了 `bash`**——`Write` / `Web` 两套策略需自行 `onTool(...)` 绑定，否则不生效且不报错。
10. **新建子代理要注册**——`AgentDefinition` 构造完必须 `getAgentManager().addAgent(def)`，否则 `explicitAgent("xxx")` 回落主代理。
11. **自定义 goal 执行器在挂接后注册**——挂接（构建期 `Builder.extensionAdd(goal)` 的 `build()` 返回后，或运行期 `engine.addExtension(goal)` 之后）再 `goal.addExecutor(channel, claim, executor)`；挂接前调用直接抛异常。**SERVER 模式下 build 之后不能再 `addExtension`**（抛 `IllegalStateException`）。没有宿主执行器时走内置缺省（同步 `engine.handle(...).call()`）。
12. **`/queue` 入队不等于会发送**——宿主必须在任务终态调 `queue.drainLoop(...)` / `claimNext(...)`（`QueueExtension` 实例上）；并且不要自己 `poll`，claim / ack / requeue 全在 harness 内，重复实现会丢任务。
13. **`/steer` 不挂拦截器就永不生效**——返回 `OK` 只代表已入邮箱。`SteerExtension` 在 `onRequestAssemble` 已代劳挂载，**宿主无需请求级挂载**；未注入的残留在任务结束或下一轮开始时经 `InputBacklog` 转排队。
14. **goal / loop 的 job 名带实例序号前缀**——`JobManager` 是 JVM 单例，同名 job 已存在时会静默沿用旧 job；插件按扩展实例自动生成进程内唯一的前缀（goal 每实例一个节拍 `goal-<序号>:tick`；loop 每任务一个 `loop-<序号>:<sessionId>:<taskId>`），宿主无需传任何标识。job 不跨重启、名字不落盘。
15. **builder 只给有可配点的能力**——goal / loop 参数多，只有 builder；queue 与 steer 只有容量类旋钮（queue 的 `maxSize` / `maxTextLength`，steer 的 `maxBoxSize` / `maxTextLength` / `maxIdLength`），无参构造与 `builder().build()` 等价。
16. **`resume` 会立即跑一轮，重启恢复用 `recover`**——`goal.recover()` 按状态来源的 `goal/active/` 索引恢复（无需枚举会话），`recover(sessionIds, statusReader)` 由宿主枚举；二者都只挂调度、不立即跑。
17. **改 goal 配置走 `update(sid, patch, stopper)`**——停流时序由门面完成，`stopper` 只负责「怎么停」（渠道侧终态帧）；停不稳返回 `CONFLICT`（goal 保持暂停，可重试）。
18. **`InterruptMode.INTERRUPT_ALL` 会连带清队列**——它不是「只停当前流」。早期 `/interrupt` 保留队列，现在「停」是彻底的（这也补上了「停了要清队列」那条旧待办）。若你需要「停当前、留队列」，用 `engine.interrupt(session, InterruptMode.CANCEL_STREAM)`：只停流、不写取消消息、不动队列，不再是手动复刻内部约定的权衡题。各方式差别见 [ENGINE-L1](docs/ENGINE-L1.md) §5.4 的对照表。
19. **`outcome.call()` / `outcome.stream()` 只在 `TASK` 上有意义**——它们在 `REPLY` / `HITL` / `IGNORED` 上会立即失败（前者抛 `IllegalStateException`、后者返回 `Flux.error`）。不要用它们取代 `switch (kind)`：先判 kind 再取流，顺序不能颠倒。
20. **`GoalSpec` 的负数会被夹到 0**——`maxTokens(-1)` 不是「报错」而是「不限制」；命令行的 `--max-tokens -1` 也一样（`Long.parseLong` 通过后交给 `GoalSpec` 再夹到 0）。想表达「非法参数」请在调用前/解析层自己校验。
21. **四个扩展都没有「只关命令」的开关**——`QueueExtension` / `SteerExtension` 只有 `()` / `builder()`，`GoalExtension` / `LoopExtension` 只有 builder且无 `commandEnabled`；`onAttach` 里一律 `register`（幂等）。要下线只有 `engine.setExtensionEnabled(ext, false)` 一条路，它是「命令 + 门面」一起关；且**未加入引擎的扩展会先被加入再直接返回**，初始状态请在 `addExtension` 前用 `ext.setEnabled(false)`。
22. **`interrupt` 之后不要再 `steer`**——`activeRunId` 已随任务结束被清掉，此时只能拿到 `NOT_RUNNING`；「停下来重来」请直接用 `queue.enqueue`。「运行中改主意」才用 steer，两者是互斥场景而非串行步骤。
23. **`.jobManager(null)` 之后 goal 不会自动推进**——`hasScheduler()` 为 false，`trigger` 仍可手动跑一轮。
24. **goal 对外只有只读快照**——`GoalSnapshot` 没有修改方法，一切状态转移经门面；运行态是包内实现。
25. **命令文本与门面调用是同一个动作，别把两者的返回值当一回事**——`engine.handle(HarnessInput.of(s, "/queue 跑测试"))` 得到的是 `Kind.REPLY` + 人读回执，`queue.enqueue(s, "跑测试")` 得到的是 `HarnessResult<Integer>`（OK 时值为位置）。宿主要程序判定就走门面，要现成文案就走文本路径；**两条都走会入队两次**（命令路径无幂等保护）。
26. **`steer` 取消了也可能已经生效**——`cancelOnRun(session, runId, steerId)` 返回细分码 `NOT_PENDING`（`CONFLICT`）不代表出错，只代表那条已被推理线程取走（已经或即将注入）。同理 `pending(session)` 是快照，读到的下一瞬就可能被消费；宿主不要用它做“一定还能取消”的保证，只能作为 UI 展示依据。
27. **门面不经受理、不经作用域**——`queue.enqueue`、`steer.steer`、`goal.create/update`、`loop.schedule`、`InterruptMode.INTERRUPT_ALL` 会先查会话归属（非持有节点返回 `NOT_OWNER`，宿主按 `getOwner()` 转发），但不校验作用域；多租户的管理接口先自行鉴权（L0 见 [host README [ENGINE-L1](docs/ENGINE-L1.md) §5](../solon-ai-harness-host/README.md)）。
28. **外部传入的会话 ID 用 `requireSession` / `getSessionOrNull`**——`engine.getSession(id)` 不存在就新建；`engine.requireSession(id)` 不存在时抛 `IllegalArgumentException`。goal 门面已改为不新建会话。
29. **引擎 `shutdown()` 之后不要再用旧引用**——`shutdown` 幂等，会关闭 LSP 进程与 MCP 连接；之后的 `handle` 返回 `REJECTED(engine_closed)`，引擎池模式下重新 `pool.get(key)` 即可。
30. **显式 `HarnessInput.scope(...)` 进积压时只保存名单类维度**——续发时与最新解析结果取交集（只会变窄）；带 `chatModel` / `attr` 的显式作用域在忙时被拒绝（`scope_not_deferrable`），不会悄悄丢掉这些维度。服务端请用引擎级 `scopeResolver`（`InputScopeResolver.byPrincipal`），不要只靠显式传入。`selectedSkill` / `selectedMcp` 视野随积压保存（见 [ENGINE-L1](docs/ENGINE-L1.md) §2.2）。

---

> **测试依赖**：`solon-test`（test scope）
> **编译辅助**：`lombok`（provided scope）
