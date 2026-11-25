# solon-ai-harness-core — AI 代理编排引擎

`solon-ai-harness-core` 是 Solon AI 的 **Agent 运行时与编排容器**：把「ReAct 引擎 + 一组 Talent 能力 + 子代理体系 + 命令系统 + 安全策略」装配成一个可直接跑起来的 Agent（默认能力面向编码场景）。

- 包名：`org.noear.solon.ai.harness`
- 本模块**不依赖任何会话插件**；goal / queue / steer / loop 是独立模块，按需引入、构建期装配（§1.4）

本文只讲**引擎本身**：怎么构建、`HarnessInput` 怎么装配一份输入、`handle` 的两段式怎么用、以及各类场景的完整示例；命令、子代理、工具权限、扩展点、安全、模块结构与常见坑放在后几章。

> 宿主骨架（会话绑定、推送外壳、受理外壳）不在本文范围，见 `solon-ai-harness-host` 模块的 README。
> 需要「更细的受理语义」时看 [docs/ENGINE-L1.md](docs/ENGINE-L1.md)：`handle` 的逐步拆解、四端时序对照、HITL 续流不变量、插件接线。

| 文档 | 内容 |
|---|---|
| [docs/ENGINE-L1.md](docs/ENGINE-L1.md) | 受理 API 详解：`handle` 内部步骤、`InputOutcome` 六态逐项、事件类型、四端时序、停 / 改 / 重排、插件接线 |
| [docs/SAAS.md](docs/SAAS.md) | 服务端（多租户）的引擎侧约定：隔离模型、`DeployMode.SERVER`、作用域、会话归属、存储、容量与上线清单 |
| [docs/hitl.md](docs/hitl.md) | HITL 人工介入：权限规则、干预策略、决策 API |
| [docs/I18n.md](docs/I18n.md) | 国际化：引擎与输入的语言、后台轮次与续流用什么语言、资源包、内置子代理多语言、测试 |
| 插件 README | [goal](../solon-ai-harness-goal/README.md) · [queue](../solon-ai-harness-queue/README.md) · [steer](../solon-ai-harness-steer/README.md) · [loop](../solon-ai-harness-loop/README.md)（每份都有命令用法、门面 API 与注意事项） |

### 怎么读这份文档

| 你想做什么 | 读哪里 |
|---|---|
| 先把引擎跑起来 | 第一章（1.1 依赖 → 1.2 构建 → 1.3 跑第一轮） |
| 搞清楚一份输入能装什么 | 第二章（input 的装配能力） |
| 接一个真产品（自己处理流、审批、停止） | 第三章（二段处理的完整示例） |
| 照着场景抄代码 | 第四章（CLI / Web SSE / IM / 同步问答 / 多模态 / 子代理 / 命令任务 / 排队 / 程序轮次 / 多租户） |
| 加命令 / 子代理 / 扩展 | 第五章、第六章、第八章 |
| 调权限与安全 | 第七章、第九章 |
| 输出英文或多语言 | §1.5（国际化） |
| 查结构 / 躲坑 | 第十章、第十一章 |

> **术语**：**input** 是一次 `handle` 的输入（`HarnessInput` / `InputOutcome` / `InputScope`）；**run** 是受理后开出的一条流
> （`runId` / `RunTicket` / `RunDispatch`）；**step** 是 ReAct 内部的一步推理加工具调用（`maxTurns` 沿用旧名，计的是 step）。
> 正文里的「一轮」、「本轮」指 run。

---

## 一、引擎

### 1.1 依赖

| 坐标 | 内容 | 适用 |
|---|---|---|
| `solon-ai-harness-core` | 引擎、输入、命令、权限、作用域、SPI | 只用引擎，或自己写插件 |
| `solon-ai-harness-goal` / `-queue` / `-steer` / `-loop` | 单个插件（各自只依赖 core） | 按需挑选会话插件 |
| `solon-ai-harness` | **聚合包**：只做引用（没有源码），一次引齐核心与四个会话插件 | 想要「一次引全」的应用 |

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-harness-core</artifactId>
    <version>${solon-ai.version}</version>
</dependency>
```

> 聚合包**不自动装配**：插件上了类路径不会自己生效，需在构建期装配——逐个 `Builder.extensionAdd(...)`（见 §1.4）；运行期 `addExtensionIfAbsent` 只在 `runtimeMutable` 时可用（SERVER 下抛 `IllegalStateException`）。写插件的模块请依赖 `solon-ai-harness-core`，不要依赖聚合包。

### 1.2 构建引擎

```java
HarnessEngine engine = HarnessEngine.of("/data/work", ".solon/")   // 工作区, 引擎根目录
        .systemPrompt("你是一名 AI 编程助手")
        .sessionProvider((id, parentId, create) -> InMemoryAgentSession.of(id)) // 必填（生产环境应缓存实例，见「八、扩展点」）
        .modelAdd(new ChatConfig().then(cfg -> {
            cfg.setName("deepseek");                               // 模型别名
            cfg.setApiUrl("https://api.deepseek.com");
            cfg.setApiKey(System.getenv("DEEPSEEK_API_KEY"));
            cfg.setModel("deepseek-chat");
            cfg.setContextLength(128_000);
            cfg.setConcurrency(5);                                 // 该模型的并发上限（子代理取槽用）
        }))
        .defaultModel("deepseek")
        .toolsAdd(ToolName.TOOL_ALL_FULL)                           // 主代理工具权限（§7）
        .build();
```

> 缺省（`STANDALONE` 模式）下 `build()` 只硬校验 `sessionProvider`。没配模型（或 `defaultModel` 不在模型表里）时 `build()` 只打一条告警、不报错
> （允许构建后再 `addModel`），没补上的话**首次 `handle`** 时由 `ReActAgentConfig` 抛 `chatModel is required`。
> `deployMode(DeployMode.SERVER)` 会在构建期直接校验状态来源、模型、`defaultModel` 与 `scopeResolver`，不满足抛 `IllegalStateException`。

`of(workspace, harnessHome)` 与 `sessionProvider` 之外的全部 builder 方法：

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
| 权限 | `hitlEnabled(Boolean)` | `false` | HITL 拦截总开关（三条件之一，§9.2） |
| 权限 | `hitlInterceptor(HITLInterceptor)` | 引擎自建 | 不配时构造期自建，默认只绑 `BashToolStrategy` 到 `bash` |
| 子代理 | `subagentEnabled(Boolean)` | `true` | 子代理委派功能（`task` / `multitask`） |
| 子代理 | `subagentDefaultConcurrency(Integer)` | `5` | 子代理默认并发上限（`1~10`，`0`=未设置取默认 5；模型配了 `AiConfig#concurrency` 以模型为准） |
| 工具 | `bashAsyncEnabled(Boolean)` | `false` | Bash 异步执行 |
| 重试 | `apiRetries` / `mcpRetries` / `modelRetries` | 各 `3` | OpenAPI / MCP / 模型调用重试次数 |
| 资源 | `modelAdd(ChatConfig)` / `defaultModel(String)` | 空 | 模型与默认模型 |
| 资源 | `extensionAdd(HarnessExtension)` | 空 | 扩展（§1.4 的四个插件也从这里装，§8） |
| 资源 | `mountAdd(Mount)` | — | 挂载（`@harness-agents` 内置挂载不可替换，会抛 `IllegalArgumentException`） |
| 资源 | `mcpServerAdd(name, params)` | 空 | MCP 服务器 |
| 资源 | `apiServerAdd(name, ApiSource)` | 空 | OpenAPI 来源 |
| 资源 | `lspServerAdd(name, params)` | 空 | LSP 服务器 |
| 命令 | `commandAdd(Command)` / `commandLoad(source, dir)` | 空 | 构建期注册命令（SERVER 下只能这样，§5.1） |
| 前缀 | `commandPrefix(String)` | `"/"` | 命令前缀，支持多字符（§5.5） |
| 前缀 | `agentPrefix(String)` | `"@"` | 子代理提及前缀，支持多字符（§5.5） |
| 刹车 | `stopLoopInterceptor(StopLoopInterceptor)` | `StopLoopInterceptor(3, 8)` | 重复调用刹车；不配时构造期自建 |
| 受理 | `admissionTimeoutMs(Long)` | `60000` | `handle` 返回 `TASK` 后占位的时限（§3.7） |
| 存储 | `stateSource(MountSource)` | `<workspace>/<harnessHome>` 本地目录 | harness 状态来源（会话级状态、goal 索引、loop 任务等），模型不可见 |
| 语言 | `locale(Locale)` | `null`（全局默认语言，中文） | 引擎实例的默认语言：输入没带语言时用它；goal 后台轮次先用目标创建时记下的语言，没有才用它；loop 后台轮次总是用它（§1.5） |
| 服务端 | `deployMode(DeployMode)` | `STANDALONE` | 部署模式（本机单用户 / 多租户服务端）；`SERVER` 的缺省见本表「服务端为」各项，完整对照见 [SAAS.md](docs/SAAS.md) §2.1 |
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

**会话提供者**（`AgentSessionProvider#getSession(sessionId, parentSessionId, createIfAbsent)`，唯一方法）：`createIfAbsent=false` 且会话不存在时必须返回 null 且无副作用（不建实例、不写存储、不放缓存）。`parentSessionId` 为 null 表示顶层会话；非 null 表示子会话（子代理任务），可据此存在父会话之下、删父会话时连带删除，不关心就忽略（见「子会话的存储与清理」）。生产实现应缓存实例：

```java
Map<String, AgentSession> map = new ConcurrentHashMap<>();
AgentSessionProvider provider = (id, parentId, create) -> create
        ? map.computeIfAbsent(id, InMemoryAgentSession::of)
        : map.get(id);
```

### 1.3 跑第一轮

最简形态：一份 `HarnessInput` + 一次 `handle` + 订阅装配好的请求（两段式的全部内容见第三章）。

```java
AgentSession session = engine.getSession("demo");               // 不存在则新建（见 1.4 的取会话口径）

InputOutcome outcome = engine.handle(HarnessInput.of(session, "看下当前项目结构"));
if (outcome.getKind() == InputOutcome.Kind.TASK) {
    outcome.getRequest().stream().blockLast();                  // 事件流；订阅时机由你定
}

// 一次性拿最终文本（只适合短任务：它同样只转发 getRequest().call()）
String answer = engine.handle(HarnessInput.of(session, "总结一下这个仓库"))
        .call()
        .getContent();
```

三个取会话的方法别混用：

| 方法 | 语义 |
|---|---|
| `engine.getSession(id)` | 不存在就**新建**（终端、一次性场景） |
| `engine.getSessionOrNull(id)` | 只取不建，不存在返回 null（管理接口、只读查询） |
| `engine.requireSession(id)` | 不存在抛 `IllegalArgumentException`（外部传入的 ID 先校验） |

### 1.4 装配会话插件

goal / queue / steer / loop 四个插件**跨轮维护会话级状态**，命令只改状态、只声明意图，执行与投递要有人接线。装配一律在**构建期**用 `Builder.extensionAdd` 完成（STANDALONE / SERVER 通用，SERVER 下 build 之后不能再挂扩展）；不要哪个删哪一行，自己的扩展也是同一写法：

```java
HarnessEngine engine = HarnessEngine.of(ws, ".solon/").stateSource(stateSrc) /* ... */
        .extensionAdd(QueueExtension.builder().maxSize(20).build())       // 会话忙时排队
        .extensionAdd(new SteerExtension())                               // 运行中插话
        .extensionAdd(GoalExtension.builder().roundConcurrency(2).build()) // 按需
        .extensionAdd(LoopExtension.builder().store("loop").quota(sid -> 20).build())  // 按需；挂接时取引擎状态来源
        .build();
```

插件自带的命令（`/goal`、`/queue`、`/steer`、`/loop`）在 `onAttach` 时注册；`/queue`、`/steer` 忙时旁路执行，`/goal`、`/loop` 忙时进积压或被拒绝。运行期 `engine.addExtensionIfAbsent(...)` 只在 `runtimeMutable` 时可用（STANDALONE 缺省；SERVER 下抛 `IllegalStateException`）。

装配之后，核心里能读到的公共事实（宿主配置面板常用的那几项）：

```java
SessionStatus st = engine.status(session);        // 运行流 / HITL / 受理窗口 / 取消代数 + 各插件贡献的键
boolean idle     = engine.isIdle(session);        // 不占用且无积压
boolean occupied = st.isOccupied();               // 不该开新回合（含 HITL 挂起、受理占位）
```

插件协作契约（`spi.InputBacklog`、`spi.RunDispatch`、各状态键）见 [ENGINE-L1](docs/ENGINE-L1.md) 第三章。

### 1.5 国际化（语言）

给模型和用户的文案（协议指令、工具返回文案、命令回执、HITL 理由、取消消息、内置子代理等）内置中文和英文。不配置时为中文，与 4.1 逐字一致。

```java
// 引擎实例的默认语言：一个引擎只服务一种界面语言时，配这里就够了
HarnessEngine engine = HarnessEngine.of(ws, ".solon/")
        .locale(Locale.ENGLISH)
        /* ... */
        .build();

// 单次输入指定语言（优先于引擎）：同一引擎服务多种语言时，每次输入带上用户的语言
engine.handle(HarnessInput.of(session, "list the files").locale(Locale.ENGLISH));

// 全局默认语言（智能体、harness、talent 共用）
AiI18n.setDefaultLocale(Locale.ENGLISH);
```

语言怎么定：

| 场景 | 用哪个语言 |
|---|---|
| 一次输入 | `HarnessInput.locale` > 引擎 `locale` > `AiI18n` 全局默认（`engine.localeOf(input)`），受理时定一次，下传到这一轮的整条链路，不回写输入 |
| 续流（`decide`、`resume(true)`、`/continue`） | 沿用原来那一轮的语言（会话重新加载后也一样）；续流输入显式带了语言时以输入为准 |
| goal / loop 后台轮次、插件门面 | 引擎实例的语言 `engine.getLocale()` |
| 命令 | `input.getLocale()`（即本次输入的生效语言），`description(Locale)` / `examples(Locale)` 按它输出 |
| 自己要按会话出文案 | `engine.localeOf(session)`；取消回执用 `HarnessI18n.get(engine.localeOf(session), HarnessI18nKeys.RUN_CANCELLED)`（当前文案：`用户已取消任务.`） |

> 才能（Talent）不保存语言，需要时从请求里取：指令用 `prompt.locale()`，工具返回文案用工具方法末尾的 `String __locale` 参数（框架注入，不进 schema；直接调用工具方法时传 `null` 表示默认语言）。
> 工具描述与参数说明只给模型看，不做国际化。

资源包位置、扩展新语言（如加 `_ja.yml`）、覆盖文案、内置子代理多语言与测试，见 [docs/I18n.md](docs/I18n.md)。

### 1.6 运行时目录

```
{harnessHome}/          # 默认 .solon/
├── sessions/           # 会话快照
├── skills/             # 技能（SKILL.md）
├── agents/             # 子代理定义（*.md）
├── commands/           # Markdown 自定义命令
├── memory/             # 长期记忆
└── channels/           # IM 通道配置
```

派生方法（引擎上直接读取）：`getHarnessHome()` / `getHarnessSessions()` / `getHarnessSkills()` / `getHarnessAgents()` / `getHarnessCommands()` / `getHarnessMemory()` / `getHarnessChannels()`；工作区与状态来源是 `getWorkspace()` / `getWorkspaceSource()` / `getStateSource()`。

> 上面是缺省布局。配了 `stateSource(...)` 后，harness 自己的状态（会话级状态、`goal/active/` 索引、`loop/` 任务）落在该来源里；
> 会话快照的存放位置由 `sessionProvider` 决定。`protectedStatePaths`（缺省 `sessions, memory, channels, tasks.json, loop, goal`，
> 相对 harnessHome）对模型的文件工具与 bash 不可读写。

### 1.7 生命周期

```java
engine.addMcpServer("slack", mcpServerParams);       // 运行期加资源
engine.addApiServer(apiSource);
engine.addLspServer("java-lsp", lspServerParams);
engine.addMount(Mount.builder().alias("@project-agents").type(MountType.AGENTS)
        .source(FileMountSource.of(Paths.get("project-agents"))).build());

engine.shutdown();     // 幂等：遍历回调每个扩展的 onDetach + 关闭 LSP 进程 / MCP 连接 / 子代理执行器
```

`shutdown()` 之后的 `handle` 返回 `REJECTED(engine_closed)`（`isClosed()` 为 true），据此可以判断手里的引擎引用是否还有效。

---

## 二、input 的装配能力（`HarnessInput`）

一次 `handle` 的全部输入上下文都在 `HarnessInput` 里：会话与正文、选择项、附件、渠道特性、作用域、审批与续流、宿主域属性。**引擎只装配，不落盘、不解析线上协议**——拿不准的字符串转枚举、文件先存到工作区，都由调用方在交给引擎之前完成。

### 2.1 全景

```java
HarnessInput input = HarnessInput.of(session, "帮我看看这个 @explore")   // 会话 + 原始正文
        .systemPrompt(null)                     // 本轮整体替换系统提示词（null = 沿用智能体配置）
        .selectedModel("deepseek")              // 显式模型（> 会话级 > 默认模型）
        .selectedAgent("explore")               // 选择器兜底（正文里的 @提及优先）
        .selectedSkill("@user-skills/chart-image")   // 本轮技能视野
        .selectedMcp("mysql")                   // 本轮 MCP 视野
        .attachments(atts)                      // 多媒体块 + 文件引用
        .origin(InputOrigin.of("web", "u1", "s1", null))   // 渠道来源身份
        .locale(Locale.CHINESE)                 // 本轮语言
        .principal("u1")                        // 提交者主体（与会话主体校验）
        .attr("reasoningEffort", "low")          // 宿主域属性（引擎不解读，进积压原样保存）
        .terminal(true)                          // 本渠道是否具备终端能力
        .trimContent(true);                      // 剥提及后去空白

InputOutcome outcome = engine.handle(input);
```

| 分类 | 方法 | 说明 |
|---|---|---|
| 正文 | `of(session, text)` / `of(text)` / `engine.handle(session, text)` | `of(text)` 的会话由外层绑定（`copyFor`）；直连引擎时用两参 |
| 提示词 | `systemPrompt(String)` | 整体替换目标智能体的系统提示词；为空沿用配置 |
| 选择 | `selectedModel(String)` | 显式模型，优先于会话级选择，再回退默认模型 |
| 选择 | `selectedAgent(String)` | 选择器兜底子代理；HITL 决策与续流路径上优先级最高 |
| 选择 | `selectedSkill(String...)` | 本轮技能视野：收窄清单 + 预加载选中技能正文 |
| 选择 | `selectedMcp(String...)` | 本轮 MCP 服务视野（按 `addMcpServer` 的配置名匹配） |
| 选择 | `explicitAgent(String)` | 请求体显式指定，**优先级最高**（面板/桌面端用） |
| 附件 | `attachments(InputAttachments)` / `media(MediaBlock)` / `reference(path)` | 多模态块与文件引用 |
| 渠道 | `origin(InputOrigin)` | 渠道 / 发送者 / 返回地址 / 入站关联键 |
| 渠道 | `terminal(boolean)` | 本渠道是否有终端交互能力（默认 false） |
| 渠道 | `commandAware(boolean)` / `agentAware(boolean)` | 是否识别 `/命令` / `@提及`（默认 true） |
| 渠道 | `commandOutputFactory(CommandOutputFactory)` | 命令回执怎么落地（默认缓冲式，§5.4） |
| 边界 | `scope(InputScope)` | 本轮强制边界（只能收窄，§2.6） |
| 边界 | `principal(String)` | 提交者主体；非空时必须与会话主体一致 |
| 忙态 | `whenBusy(BusyPolicy)` | 会话忙时：进积压 / 先插话 / 直接拒绝（§2.7） |
| 审批 | `hitl(action, callId)` + `hitlOutput(text)` + `resume(true)` | 写决策 + 尝试续流（§2.8） |
| 续流 | `resume(boolean)` | 不产生新输入，按挂起的 `tool_calls` 续跑 |
| 其他 | `locale(Locale)` / `attr(k, v)` / `attrs(Map)` / `agentNameValidator(Predicate)` / `trimContent(boolean)` | 语言、宿主域属性、子代理名校验、剥提及后去空白 |
| 派生 | `derive(text)` | 用「本轮的全部属性 + 新正文」派生一份新输入（命令产物走它） |
| 框架内 | `ofProgram(session, text, channel)` / `copyFor(session)` / `restoredScope` / `resolvedScope` | 程序轮次、受理与积压内部使用，业务代码不要调 |

### 2.2 选择项：模型 / 子代理 / 技能 / MCP

四者的回退口径一致而清晰：

| 选择项 | 本次输入 | 会话级 | 智能体声明 | 兜底 |
|---|---|---|---|---|
| 模型 | `selectedModel` | `engine.setSessionModel` | 代理定义里的 `model` | `defaultModel` |
| 子代理（顶层） | `explicitAgent` > 正文 `@提及` > `selectedAgent` | `engine.setSessionAgent` | — | 主代理 |
| 技能视野 | `selectedSkill` | `engine.setSessionSkills` | 定义里的 `skills` | 不限制（全量目录） |
| MCP 视野 | `selectedMcp` | `engine.setSessionMcps` | 定义里的 `mcpServers` | 不限制（全部已注册服务） |

会话级的三个选择随快照持久化，之后该会话每条新消息自动回退到它：

```java
engine.setSessionModel(session, "gpt-4o");
engine.setSessionAgent(session, null);                                  // null = 清掉选择
engine.setSessionSkills(session, Arrays.asList("@user-skills/office"));  // 失效名会被剔除，全失效按「不限制」
```

**技能 / MCP 视野是「收窄 + 预加载」，不是权限边界**：

- 传空数组等价于不传（避免误把全部资源关掉）；传错名字只是「匹配不到」，不抛异常；
- 技能标识支持 `aliasPath` / `@aliasPath` / 唯一短名三种写法；
- `skillread` 不受视野限制（被选技能的 SKILL.md 里引用另一个技能时不能断链）；
- 真要硬隔离用 `scope`（§2.6），名单外的项按「不存在」处理。

### 2.3 附件：多媒体与文件引用

两条通道，语义不同：

```java
InputAttachments atts = InputAttachments.of()
        .media(ImageBlock.ofBase64(base64, "image/png").filePath(".uploads/shot.png")) // 进消息内容（多模态块）
        .media(ImageBlock.ofUrl("https://cdn.example.com/x.png", "image/png"))
        .reference(".uploads/report.pdf");                                            // 只拼 [附件: path] 前缀，由代理自己读
```

- `media(...)`：块直接进入消息内容；block 上带 `filePath(...)` 时，模型看到的是一份已落盘的相对路径。
- `reference(path)`：不进消息体，只在正文前拼 `[附件: x]`，让代理用文件工具按需读取（大文件、PDF 之类）。
- **引擎不落盘**：由调用方保存到工作区（约定根 `.files/uploads`），再把结果交过来。
- 有附件时**不识别命令**（避免 `/path/to/x.png` 被误判），带附件的 `/cmd` 按普通消息处理、附件照常装配。

### 2.4 渠道特性：`origin` / `terminal` / 三个开关

```java
HarnessInput.of(session, text)
        .origin(InputOrigin.of("dingtalk", "u1", "chat42", "msg7"))  // 渠道 / 发送者 / 回复目标 / 入站消息键
        .terminal(true)                                              // 只有命令行宿主才开
        .commandAware(false)                                         // 文本不是用户手输时
        .agentAware(false);                                          // 文本由程序生成时
```

- `origin`：命令回执的路由依据，也会作为 `source` 元数据进 Prompt。`InputOrigin.NONE` 表示没有来源身份。
- `terminal(true)`：同时决定两件事——`terminalOnly()` 的命令是否放行、`CommandInput.isTerminal()` 的返回值。默认 `false`，CLI 必须显式打开，否则 `/help` 会裁掉 `terminalOnly` 命令、`/exit` 会被统一拦截。桌面端虽在本地运行也保持默认（它没有命令行终端）。
- `commandAware(false)`：定时任务提示词、排队的输入、命令内部自造的提示词都用它——这些即使以 `/` 开头也不该执行。
- `agentAware(false)`：goal 的 objective 之类程序文本用它——即使以 `@xxx` 开头也原样交给代理。

### 2.5 提前解析提及（`parseMention`）

需要在交给引擎前改写正文时（名字格式校验、规划模式加前缀、Goal 拦截等），不要自己拼 `AgentMentions.Options`——那会让同一段输入被解析两次且口径可能不同。用 `HarnessInput#parseMention`，它与 `handle` 内部走同一个入口：

```java
AgentMentions.Mention mention = HarnessInput.of(session, raw)
        .explicitAgent(requestedAgent)        // 可空
        .agentNameValidator(name -> ...)      // 可空
        .trimContent(true)
        .parseMention(engine);

String text  = mention.getText();        // 剥离提及后的正文，可安全用于改写
String agent = mention.getAgentName();   // 无有效提及时为 null

engine.handle(HarnessInput.of(session, text).explicitAgent(agent).trimContent(true));  // 二次解析幂等
```

提及识别的细节（两条路径行为不同，容易踩坑）：

- 必须有空白边界（`@bash 内容` 有效，`@bash内容` 无效）；
- **提及路径**剥掉 `@名字` 后只跳过一个字符，`@bash    x` 剩下 `  x`；
- **显式路径**（`explicitAgent`）走 `stripMention`，会吃掉其后所有空白，`@bash    x` 剩下 `x`；
- 名字不存在时不剥离，整段交给主代理自己判断；
- 需要两者一致时加 `.trimContent(true)`。

### 2.6 作用域（`scope`）：只能收窄的强制边界

```java
InputScope scope = InputScope.builder("tenant-a:u1")   // 主体标识（审计 / 配额 / 日志）
        .tools("read", "grep", "bash")     // 工具名单（逻辑名，可写 pi / * / **）
        .skills("@user-skills/office")     // 技能名单（与视野取交集）
        .mcps("mysql")                     // MCP 服务名单
        .commands("help", "model")         // 命令名单（可选）
        .build();

engine.handle(HarnessInput.of(session, text).scope(scope));
```

- 作用域由常驻的 `ScopeGuardInterceptor` 执行，优先级最高：越界调用在**执行前**就被填上拒绝结果，HITL 也不会为它弹卡。
- 显式 `scope(...)` 优先于引擎级 `scopeResolver`；`scopeRequired(true)` 且解析不到 → `REJECTED(scope_unavailable)`。
- 会话忙、输入要进积压时，只有**名单类维度**能保存（续发时与最新解析结果取交集，只会变窄）；带 `chatModel` / `attr` 的显式作用域会被拒绝（`scope_not_deferrable`），不会悄悄丢掉这些维度。
- 多租户请用引擎级 `scopeResolver(InputScopeResolver.byPrincipal(...))`，不要只靠每次输入显式传。

### 2.7 忙态策略（`whenBusy`）

会话忙（有运行流 / 正在受理另一条输入 / 积压里还有待执行输入）时怎么处理这条输入：

| 值 | 行为 | 适用 |
|---|---|---|
| `QUEUE`（缺省） | 进积压，当前一轮结束后由调用方续发；没装 queue 插件则按 `BUSY` 拒绝 | 推送型、聊天窗口 |
| `STEER` | 先作为插话交给正在运行的那一轮，插不进再排队 | 边跑边补充的输入框（需在受理前自己尝试 steer，直接走 `handle` 时按 `QUEUE` 处理） |
| `REJECT` | 直接 `REJECTED(busy)`，不进积压 | 一问一答（请求结束就没人接续发了） |

忙时可执行的命令（`runnableWhenBusy`，如 `/interrupt`、`/steer`）、审批决策、程序派发线程上的一轮不受影响。判定在会话锁内完成，不存在「先判空闲再被抢先」的窗口。

### 2.8 审批与续流（`hitl` / `resume`）

```java
// 审批：写决策 + 尝试续流，一次调用（HITLAction.of 由调用方解析线上字符串，脏值显式报错）
InputOutcome o = engine.handle(HarnessInput.of(session, null)      // 审批通常不带文本
        .hitl(HITLAction.of(action), callUuid)
        .hitlOutput(note)                                          // 可选：改写工具返回值
        .resume(true));

// 单纯续流：不产生新输入，按挂起的 tool_calls 继续跑（文本与附件都被忽略）
InputOutcome o2 = engine.handle(HarnessInput.of(session, null)
        .resume(true)
        .selectedAgent(o.getAgentName())      // ★ 必须是本轮生效代理，不能换回主代理
        .selectedModel(o.getModelName()));
```

`resume(true)` 只是**意愿表达**：引擎在写入决策后自检「决策命中挂起任务 + 本批已齐备 + 显式要求」三项，全成立才返回 `TASK`，否则返回 `HITL` 并携带事实（`isHitlApplied()` / `getPendingHitlCount()`）。所以调用方只需 `switch (kind)`，不必自己数整批是否齐备。

### 2.9 派生与程序轮次

```java
// derive：用「本轮的全部属性」换一段新正文（命令产物、队列续发、重排都走它）
HarnessInput next = input.derive("按新目标重跑一遍测试");   // commandAware(false)、作用域与来源一并带上

// ofProgram：程序代替用户发起的一轮（后台调度、定时任务、goal 执行器）
HarnessInput program = HarnessInput.ofProgram(session, prompt, "scheduler").locale(locale);
```

`ofProgram` 的三个差别：不识别命令与提及（`commandAware/agentAware` 均关闭）、忙时直接 `REJECTED(busy)`（`whenBusy` 固定为 `REJECT`）、`outcome.getDispatchOrigin()` 为该轮带上回复路由来源。

---

## 三、二段处理：`handle` + `InputOutcome`

**两段式**是理解本模块的主线：

| 段 | 谁做 | 做什么 | 是否启动推理 |
|---|---|---|---|
| 第一段 | `engine.handle(input)` | HITL 决策 → 续流分流 → 空输入判空 → 命令识别执行 → 子代理解析 + 附件装配 → Prompt 构建 → 请求装配 | **否**，全部同步完成 |
| 第二段 | 调用方读 `InputOutcome` | 按 `getKind()` 选动作；只有 `TASK` 才订阅 `getRequest().stream()` | 是，时机与线程由调用方决定 |

第一段**永不启动推理**。这正是你能在两段之间插入自己的准备动作（登记忙碌标记、复位流门、开 SSE 响应头、挂取消钩子）的原因。

### 3.1 `handle` 内部按固定顺序做事，顺序本身是语义

| 步骤 | 动作 | 说明 |
|---|---|---|
| 0 | 受理判定 | 引擎已关闭 → `REJECTED(engine_closed)`；会话归属（非持有 → `not_owner`）→ 作用域预检（锁外；`scopeRequired` 且解析不到 → `scope_unavailable`）→ HITL 挂起拦截 → 忙态放行 / 入积压 / 拒绝 → 占位；未通过时直接返回 `DEFERRED` / `REJECTED` |
| 0.5 | `onInput` 钩子 | 扩展仍可在这里设置 `input.scope(...)`，装配时以显式作用域为准 |
| 1 | HITL 决策 | **优先于空输入判空**：审批请求通常不带文本；带 `resume(true)` 时同一次调用**尝试**续流 |
| 2 | 续流分流 | `resume(true)` 直接装配请求，不解析文本 |
| 3 | 空输入判空 | 无文本且无附件 → `IGNORED` |
| 4 | 命令识别与执行 | 有附件或 `commandAware(false)` 时不当作命令 |
| 5 | 子代理解析 + 附件前缀 | 解析 `@name` 前缀，文件引用拼 `[附件: x]` |
| 6 | Prompt 构建 | 多模态内容块、来源 / 附件 / 子代理元数据 |
| 7 | 请求装配 | 模型回退链 + 子代理 + `ReActRequest` |

### 3.2 六种结局：`getKind()`

<b>kind 只回答「你该做什么」，不回答「输入是什么」。</b>「这条输入是不是命令」属于<b>事实</b>，由 `isCommand()` / `getCommandName()` 读回。因此「命令触发的任务」与「用户手输的任务」共用 `TASK` 一个分支，差异只体现在「有没有回执」这份数据上。

| Kind | 你的动作 | 读什么 |
|---|---|---|
| `TASK` | 执行 `getRequest()`；`isCommand()` 为 true 时先渲染 `getCommandOutput()` | `hasReply()` / `getAgentName()` / `getModelName()` / `getSelectedSkills()` / `getSelectedMcps()` |
| `REPLY` | 渲染 `getCommandOutput()` 后收尾（涵盖「命令已执行」与「命令在此渠道不可用」两种事实） | `getCommandName()` / `getCommandOutput()` |
| `HITL` | 决策已写入但未续流；按事实分流（见 3.5） | `isHitlApplied()` / `getPendingHitlCount()` / `getHitlCards()` / `getAgentName()` |
| `IGNORED` | 无事可做 | — |
| `DEFERRED` | 会话忙，已进积压；等这一轮结束后续发 | `getBacklogPosition()`（从 1 计） |
| `REJECTED` | 未受理，渲染 `getRejectDetail()` 后收尾 | `getRejectReason()`（`RejectReason`，线上值 `.code()`） |

`RejectReason` 的取值：`BUSY` / `HITL_PENDING` / `ATTACHMENTS_NOT_DEFERRABLE` / `BACKLOG_UNAVAILABLE` / `NOT_OWNER` / `SCOPE_UNAVAILABLE` / `SCOPE_NOT_DEFERRABLE` / `ENGINE_CLOSED`（`SESSION_NOT_FOUND` 由外层「只查不建」取会话失败时使用，`handle` 不会给出）。

另外三个不体现在 kind 里的常用成员：

- `isBesideRunning()`：本条是「忙时可执行命令」，在正在运行的任务旁边执行（如 `/interrupt`），不要改写回复路由；
- `isDispatched()` / `getDispatchOrigin()`：本轮属于程序派发（积压续发）；
- `discard()` / `afterRun(Runnable)`：拿到 `TASK` 却不执行时释放占位（幂等）；登记「本轮终态后」回调——**积压续发应挂在这里**。

### 3.3 完整示例：一个能接产品的引擎侧外壳

> `EngineShell` 是**文档内的示例类**，没有对应的 `org.noear.*` 可 import——请直接复制到项目里改。harness 提供的是 `HarnessEngine` / `HarnessInput` / `InputOutcome` 这些零件。

```java
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.react.RunEndEvent;
import org.noear.solon.ai.agent.react.task.ReasonDeltaEvent;
import org.noear.solon.ai.agent.react.task.ToolCallStartEvent;
import org.noear.solon.ai.agent.react.intercept.HITLPendingEvent;
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.agent.ToolName;
import org.noear.solon.ai.harness.command.builtin.ClearCommand;
import org.noear.solon.ai.harness.command.builtin.HelpCommand;
import org.noear.solon.ai.harness.command.builtin.ModelCommand;
import org.noear.solon.ai.harness.hitl.HitlCard;
// 其余：org.noear.solon.ai.harness.{HarnessEngine, InterruptMode}、harness.input.{HarnessInput, InputOutcome, InputAttachments}、org.noear.solon.ai.agent.AgentSession

public class EngineShell {
    private final HarnessEngine engine;
    private final AgentSession session;

    public EngineShell(String workspace) {
        this.engine = HarnessEngine.of(workspace, ".solon/")
                .sessionProvider((id, parentId, create) -> InMemoryAgentSession.of(id))
                .modelAdd(new ChatConfig().then(cfg -> {
                    cfg.setName("deepseek");
                    cfg.setApiUrl("https://api.deepseek.com");
                    cfg.setApiKey(System.getenv("DEEPSEEK_API_KEY"));
                    cfg.setModel("deepseek-chat");
                }))
                .defaultModel("deepseek")
                .toolsAdd(ToolName.TOOL_ALL_FULL)   // 含 hitl 才有审批事件
                .hitlEnabled(true)
                .build();

        this.session = engine.getSession("demo");

        // 命令必须显式注册（§5.1）：不注册则 /help 按普通消息处理
        engine.getCommandRegistry().register(new HelpCommand());
        engine.getCommandRegistry().register(new ClearCommand());
        engine.getCommandRegistry().register(new ModelCommand());
    }

    /** 一次完整输入：附件由调用方先落盘，再交给引擎装配 */
    public void send(String text, InputAttachments attachments, String selectedAgent) {
        // ===== 第一段：一次同步调用，装配出「该做什么」 =====
        InputOutcome outcome = engine.handle(HarnessInput.of(session, text)
                .attachments(attachments)
                .selectedAgent(selectedAgent)
                .trimContent(true)
                .terminal(true));          // 命令行外壳：放行 terminalOnly 命令

        // ===== 第二段：按 kind 分派 =====
        switch (outcome.getKind()) {
            case TASK:     onTask(outcome);  break;
            case REPLY:    onReply(outcome); break;
            case HITL:     onHitl(outcome);  break;
            case DEFERRED: onText("\n[已排队] 第 " + outcome.getBacklogPosition() + " 位"); break;
            case REJECTED: onText("\n[未受理] " + outcome.getRejectDetail()); break;
            case IGNORED:  break;
        }
    }

    private void onTask(InputOutcome outcome) {
        // 命令触发的任务：回执 + 任务并存，谁先谁后由你定（这里选「先回执」）
        if (outcome.hasReply()) {
            onText(outcome.getCommandOutput());
        }

        // ★ 两段之间是唯一的插入点：忙碌标记 / 流门复位 / 响应头，都必须早于订阅
        markBusy(true);
        try {
            outcome.getRequest().stream()
                    .doOnNext(this::render)
                    .doOnError(err -> onText("\n[错误] " + err.getMessage()))
                    .blockLast();
        } finally {
            markBusy(false);
        }
    }

    private void onReply(InputOutcome outcome) {
        if (outcome.hasReply()) {
            onText(outcome.getCommandOutput());
        }
    }

    /** HITL 决策已写入会话：按事实决定后续动作（不只看 kind） */
    private void onHitl(InputOutcome outcome) {
        if (outcome.isHitlApplied() == Boolean.FALSE) {
            onText("\n[审批卡已失效]");          // 过期卡：切勿续流，否则会把上一轮问题重跑一遍
            return;
        }
        if (outcome.getPendingHitlCount() == 0) {
            // 本批已齐备但没传 resume：自行续流（传了 resume 的话引擎已直接返回 TASK）
            resume(outcome.getAgentName(), outcome.getModelName());
            return;
        }
        // 本批尚有未决卡：展示下一张未决卡，等用户继续点
        for (HitlCard card : outcome.getHitlCards()) {
            if (card.isDecided() == false) {
                onText("\n[待审批] " + card.getToolName() + " " + card.getArgs());
                break;
            }
        }
    }

    /** 提交一条审批决策：一次调用完成「写决策 + 尝试续流」，之后只 switch kind */
    public void decide(String callUuid, String action, String note) {
        InputOutcome outcome = engine.handle(HarnessInput.of(session, null)
                .hitl(HITLAction.of(action), callUuid)   // 协议字符串 → 枚举，脏值显式报错
                .hitlOutput(note)                        // 可选：改写工具返回值
                .resume(true));

        if (outcome.getKind() == InputOutcome.Kind.TASK) {
            onTask(outcome);
        } else {
            onHitl(outcome);
        }
    }

    /** 续流：只在「没传 resume 却已齐备」的写法下需要 */
    private void resume(String agentName, String modelName) {
        InputOutcome resumed = engine.handle(HarnessInput.of(session, null)
                .resume(true)
                .selectedAgent(agentName)     // ★ 必须是生效代理
                .selectedModel(modelName));

        if (resumed.getKind() == InputOutcome.Kind.TASK) {
            onTask(resumed);
        } else {
            onText("\n[未受理] " + resumed.getRejectDetail());
        }
    }

    /** 停止：一步到位（连带清队列）；只停流不停队列用 CANCEL_STREAM */
    public boolean stop() {
        return Boolean.TRUE.equals(engine.interrupt(session, InterruptMode.INTERRUPT_ALL).getValue());
    }

    private void render(AgentEvent e) {
        if (e instanceof ReasonDeltaEvent) {
            onText(((ReasonDeltaEvent) e).getText());            // isThinking() 可区分思考段
        } else if (e instanceof ToolCallStartEvent) {
            onText("\n[调用工具] " + ((ToolCallStartEvent) e).getToolName());
        } else if (e instanceof HITLPendingEvent) {
            ((HITLPendingEvent) e).getPendingTasks()
                    .forEach(t -> onText("\n[待审批] " + t.getToolName()));
        } else if (e instanceof RunEndEvent) {
            onText("\n" + ((RunEndEvent) e).getText());          // 最终答复
        }
    }

    private void onText(String s)    { System.out.print(s); }
    private void markBusy(boolean b) { /* 自管：Web 端在这里复位流门 / 注册 Disposable */ }
}
```

注意 `TASK` 分支里的 `hasReply()`：命令触发的任务（如 `/queue` 入队后立刻开跑）回执与任务并存，那份回执同样出自默认缓冲工厂。

### 3.4 事件类型

`getRequest().stream()` 推的是同一套 `AgentEvent`，按需 `instanceof` 分流：

| 事件类 | 何时推 | 可取 |
|---|---|---|
| `ReasonStartEvent` / `ReasonEndEvent` | 一轮推理开始 / 结束 | — |
| `ReasonDeltaEvent` | 推理增量（逐字） | `getText()`、`isThinking()` |
| `ActionStartEvent` / `ActionEndEvent` | 一批工具调用开始 / 结束 | `getToolCalls()` |
| `ToolCallStartEvent` / `ToolCallEndEvent` | 单个工具调用开始 / 结束 | `getToolName()` |
| `PlanEvent` | 计划阶段 | 计划内容 |
| `RunStartEvent` / `RunEndEvent` | 整轮运行开始 / 结束 | `RunEndEvent.getText()` 为最终答复，`getMetrics()`、`getLlmErrorType()` |
| `HITLPendingEvent` | 拦截到待审批工具（需 HITL 三条件齐备） | `getPendingTasks()` |
| `LlmErrorEvent` | 模型调用出错 | 错误分类 |
| `ContextSizeEvent` | 触发历史压缩 | 压缩前后规模 |

### 3.5 HITL 续流的四条不变量

1. **决策与续流合并为一次 `handle`**——`.hitl(action, callId).resume(true)`，一次调用后只 `switch (kind)`：`TASK` 即已续流，`HITL` 则读事实处理。不要自己分两次（先 `.hitl(...)` 再 `.resume(true)`）：那个序列需要你自己判断整批是否齐备。
2. **续流要 `.resume(true)` 且不传正文**——传了也会被忽略。
3. **代理名必须沿用 `outcome.getAgentName()`**——它是「本轮生效代理」；trace 按代理名分 key，换回主代理会取不到挂起的 `tool_calls`，表现为续跑空转。
4. **续流时机由引擎判定，你只管事实**——不要用 `HitlCard.list(session)` 自己数整批是否齐备（该集合含已决策项）；齐备判定统一读 `outcome.getPendingHitlCount()`，该集合只用于「展示下一张未决卡」（`HitlCard.next(session)` / `HitlCard.undecided(session)` / `HitlCard.pendingCount(session)`）。

`HITL` 态的三种情形：

| 情形 | 判据 | 该做什么 |
|---|---|---|
| 本批已齐备但未传 `resume` | `isHitlApplied()==true` 且 `getPendingHitlCount()==0` | 自行续流（传 `getAgentName()` / `getModelName()`） |
| 本批尚有未决卡 | `isHitlApplied()==true` 且 `getPendingHitlCount()>0` | 展示 / 等待下一张卡；**切勿续流**（会带走未决任务） |
| 过期审批卡 | `isHitlApplied()==false` | 提示卡已失效并丢弃；**切勿续流**（空提示词会回落 `originalPrompt`，把上一轮问题重跑一遍） |

### 3.6 受理控制：三个容易写错的判定

| 判定 | 含义 | 用在哪 |
|---|---|---|
| `engine.status(session).isBusy()` | 有活跃流 | 流生命周期、事件编排 |
| `engine.status(session).isOccupied()` | 不应开新回合（含 HITL 挂起、受理窗口） | **受理判定** |
| `engine.status(session).hasPendingHitl()` | 有待决策的工具调用 | 提示文案、前端状态 |

- **占位跟着请求走**：返回 `TASK` 时会话保持占用，直到 `getRequest().stream()` 被订阅（订阅时运行流先登记、占位后释放，无空档——把订阅调度到别的线程也安全）或 `call()` 结束；
- 拿到 `TASK` 却不执行时调 `outcome.discard()`（幂等），否则要等 `admissionTimeoutMs` 超时才释放；
- 判断「忙态是否放行」用引擎，不要自己解析命令名再查注册表：`engine.isRunnableWhenBusy(input)`、`engine.resolveCommand(input)`（未以命令前缀开头 / 只有前缀 / 未注册 → null）；
- HITL 挂起时普通输入会被拒（`hitl_pending`）而不入积压——入积压会让指令堆在审批之后一次性执行。审批决策、`resume(true)`、忙时可执行命令与程序派发轮次豁免。

### 3.7 停止

```java
engine.interrupt(session, InterruptMode.INTERRUPT_ALL);   // 停流 + 清空队列（用户点「停止」的默认解）
engine.interrupt(session, InterruptMode.CANCEL_STREAM);   // 只停流：不写取消消息、不动队列
engine.interrupt(session, InterruptMode.PREEMPT_STREAM);  // 新一轮腾位：不推取消代数
```

| 写法 | 队列 | 取消消息 | 取消代数 | 适用 |
|---|---|---|---|---|
| `INTERRUPT_ALL` | 清空 | 补写（带 `META_RUN_ID`） | 推进 | 用户主动停 |
| `CANCEL_STREAM` | 不动 | 不写 | 推进 | 新目标顶掉旧轮次（goal / 自动循环） |
| `PREEMPT_STREAM` | 不动 | 不写 | **不推进** | 旧流本该自然结束（Stop-Replace） |
| `InterruptPlan#writeCancelEarly()` | 不动 | 只补写 | 不动 | 流已停但取消消息丢失 |

四条必知语义：

- 返回值 `true` = 确实停掉了一条活跃流；`false` = 当前没有已登记的活跃流，但**取消代数仍会推进**（能拦住「随后才到」的幽灵流），不要当成「点击无效」；
- 取消以 `onComplete` 收尾，**不走 `onError`**：用户主动停止是正常结束，别用 `doOnError` 判定失败；
- 取消的轮次不会推 `RunEndEvent`（避免把占位文本当最终答复）；
- 需要自定义终态时序（Web 要先落库、发 error/done、最后才停流）时用两段式：

```java
String cancelText = HarnessI18n.get(engine.localeOf(session), HarnessI18nKeys.RUN_CANCELLED);

try (InterruptPlan plan = engine.prepareInterrupt(session)) {
    if (plan == null || !plan.hasActiveRun()) { emitFallbackDone(); return; }

    plan.writeCancelEarly();                        // ① 提前落库（幂等）
    emitError(cancelText);                          // ② 渠道事件（文案见 §1.5）
    emitDoneOnce();                                 // ③ 停流前发 done
    plan.commit();                                  // ④ 停流（幂等）
}
```

> 必须闭合：认领意味着句柄已从登记表摘下，忘记 `commit()` 会留下一条再也无法取消的孤儿流（`close()` 等价 `commit()`）。

---

## 四、场景示例

### 4.1 命令行（CLI）：终端能力 + 实时回执

CLI 与其它端的两点差异：`terminal(true)`，以及命令回执要**实时写终端**（而不是攒到最后一并取回）。

```java
public void send(String line, Terminal terminal) {
    InputOutcome outcome = engine.handle(HarnessInput.of(session, line)
            .terminal(true)                          // 放行 terminalOnly 命令，/exit 不被拦截
            .commandOutputFactory(in -> new CliCommandOutput(terminal)));  // 回执边执行边写

    switch (outcome.getKind()) {
        case TASK:     outcome.getRequest().stream().blockLast(); break;   // 回执已由工厂写过，不再打印
        case REPLY:    break;                                             // 同上
        case HITL:     printPending(session); break;
        default:       System.out.println(outcome.getRejectDetail()); break;  // DEFERRED / REJECTED
    }
}
```

- `CliCommandOutput` 是你自己实现的 `CommandOutput`（`println` / `color` / `requestAgentTask` 三个方法），不是框架类；不配工厂时默认 `BufferedCommandOutputFactory`，回执走 `outcome.getCommandOutput()` 一次取回。
- 工厂只收一个参数 `CommandInput`（想按渠道区分输出时读它），输出对象只管写，不持有会话、参数或语言。
- `terminal` 是**输入属性**：`CommandInput.isTerminal()` 由它决定；输出侧看 `CommandOutput.supportsAnsi()`，为真时 `output.color(...)` 才加 ANSI 序列。

### 4.2 Web / SSE：流式输出

```java
@Post
@Mapping("/chat/{sid}")
public SseEmitter chat(String sid, String text, Context ctx) {
    AgentSession session = engine.requireSession(sid);

    InputOutcome outcome = engine.handle(HarnessInput.of(session, text)
            .origin(InputOrigin.of("web", userId(ctx), sid, null))
            .whenBusy(BusyPolicy.REJECT));                  // 一问一答型 HTTP：忙就拒，别排

    SseEmitter sse = new SseEmitter(0L);                   // 响应体先建（两段之间插入）

    switch (outcome.getKind()) {
        case TASK: {
            Disposable d = outcome.getRequest().stream()
                    .map(this::toFrame)
                    .subscribe(f -> send(sse, f),
                            err -> sse.error(err),
                            () -> { sse.complete(); drain(session); });
            registerDisposable(session, d);                // 先注册再订阅＝不出现空闲窗口
            break;
        }
        case REPLY:    sendAndComplete(sse, outcome.getCommandOutput()); break;
        case DEFERRED: sendAndComplete(sse, "排队中（第 " + outcome.getBacklogPosition() + " 位）"); break;
        case REJECTED:
            if (outcome.getRejectReason() == RejectReason.HITL_PENDING) {
                sendAndComplete(sse, "有待审批工具：" + HitlCard.next(session).getToolName());
            } else {
                sendAndComplete(sse, outcome.getRejectDetail());
            }
            break;
        case HITL:
        case IGNORED:  sse.complete(); break;
    }
    return sse;
}
```

要点：

- `handle` 第一段同步完成、不启动推理，所以你可以在两段之间做「开 SSE 头 → 注册 `Disposable` → 订阅」；
- 引擎**不内置 `subscribeOn`**：调度权归你，需要切线程自己 `subscribeOn`；
- 审批用同一条 HTTP 接：`engine.handle(HarnessInput.of(session, null).hitl(...).resume(true))`，`TASK` 就开新流。

### 4.3 IM / 推送：来源身份与异步投递

IM 场景没有「请求-响应」的天然时序，靠 `origin` 决定回执发回哪里，且默认排队（`BusyPolicy.QUEUE`，需装 queue 插件）。

```java
public void onMessage(ImMessage msg) {
    AgentSession session = engine.getSession(msg.getChatId());

    InputOutcome outcome = engine.handle(HarnessInput.of(session, msg.getText())
            .origin(InputOrigin.of("dingtalk", msg.getSenderId(), msg.getChatId(), msg.getId())));

    switch (outcome.getKind()) {
        case TASK:     outcome.getRequest().stream().subscribe(e -> push(msg, e)); break;
        case REPLY:    pushText(msg, outcome.getCommandOutput()); break;
        case DEFERRED: pushText(msg, "已排队（第 " + outcome.getBacklogPosition() + " 位）"); break;
        default:       pushText(msg, outcome.getRejectDetail()); break;
    }
}
```

- 异步订阅时用 `outcome.afterRun(...)` 挂「本轮终态后」的续发，不要让队列悬着；
- 忙时可执行命令（`/interrupt`、`/steer`）会通过与运行中的流并行执行，`outcome.isBesideRunning()` 为 true 时不要改写回复路由。

### 4.4 同步一问一答

```java
AgentSession session = engine.getSession("cli-" + n);

String answer = engine.handle(HarnessInput.of(session, question)
        .whenBusy(BusyPolicy.REJECT))    // 一问一答：排队的那一轮没人接续发
        .call()
        .getContent();
```

`outcome.call()` / `outcome.stream()` 只在 `TASK` 上有意义（前者抛 `IllegalStateException`、后者返回 `Flux.error`），所以要先判 kind，不要用它们取代 `switch`。长任务请走流式，否则无法中途取消、也拿不到过程事件。

### 4.5 多模态输入：图片 + 文件引用

```java
// 1) 调用方先落盘（各端规则不同），把结果交过来
Path saved = saveToWorkspace(session, upload);            // 如 .files/uploads/a.png

InputAttachments atts = InputAttachments.of()
        .media(ImageBlock.ofBase64(upload.getBase64(), "image/png")
                .filePath(relWorkspace(saved)))            // 模型也能看到相对路径
        .reference(relWorkspace(report));                   // 大文件只给引用，由代理自己读

InputOutcome outcome = engine.handle(HarnessInput.of(session, "对比这两张图的差异")
        .attachments(atts));
```

- 有附件时不识别命令（`/path/x.png` 不会被当命令）；
- 带附件的输入**不进积压**：会话忙时返回 `REJECTED(attachments_not_deferrable)`；
- `InputAttachments.isAllImages()` 可判断「是否纯图片」，用于选择模型（多模态 vs 文本模型）。

### 4.6 指定或委派子代理

```java
// ① 输入里的提及（必须是有效子代理名，否则整段原样交给主代理）
engine.handle(HarnessInput.of(session, "@bash 列出所有 Java 文件"));

// ② 请求体显式指定（面板/桌面端用法，优先级最高）
engine.handle(HarnessInput.of(session, "列出所有 Java 文件").explicitAgent("bash"));

// ③ 选择器兜底（前两者都没命中时才生效）
engine.handle(HarnessInput.of(session, "列出所有 Java 文件").selectedAgent("bash"));

// 查看实际生效的子代理
InputOutcome o = engine.handle(...);
System.out.println(o.getAgentName());     // "bash" 或 null（主代理）
```

主代理还会在运行中通过工具自动委派（宿主无需写代码）：`task`（串行，默认不在 `*` 里，只有 `**` 包含）、`multitask`（并行，按每个任务实际路由到的模型分别限流）。子代理只能用一层委派、且不能使用 `hitl`（挂起后无人能决策）。细节见第六章。

### 4.7 命令驱动的二段任务

命令内部要触发 Agent 任务时，只声明意图，不自己开流：

```java
public class ReviewCommand implements Command {
    @Override public String name() { return "review"; }
    @Override public String description(@Nullable Locale locale) { return "审查当前改动"; }

    @Override
    public void execute(HarnessEngine engine, CommandInput input, CommandOutput output) {
        output.println("开始审查…");                                  // 回执
        output.requestAgentTask("请审查本次改动里的并发问题", "deepseek");  // 任务意图
    }
}
```

`CommandDispatcher` 在分发尾部把这个意图交回引擎的同一个入口 `engine.handle(...)` 完成二段装配：二段输入由 `HarnessInput#derive` 派生（本轮属性、语言、视野、显式作用域、来源、宿主域属性一并带上，`commandAware(false)` 不再识别命令），`InputOutcome.getKind()` 因此返回 `TASK`、`isCommand()` 为 true、`getCommand()` 可用，并且 `hasReply()` 为 true。调用方按 kind 分路由即可，不要自己再拼 Prompt 或开流。

意图只有两个字段：`prompt`（**为空表示续流**，如 `/continue`）、`model`（为空沿用本轮选择，再回退会话级）。

### 4.8 排队与忙态

```java
// ① 文本路径：会话忙时自动入积压（需装 queue 插件）
InputOutcome o = engine.handle(HarnessInput.of(session, "顺便更新 README"));
if (o.getKind() == InputOutcome.Kind.DEFERRED) {
    System.out.println("排队第 " + o.getBacklogPosition() + " 位");
}

// ② 拿到 TASK 后决定不跑：释放占位
o.discard();

// ③ 续发积压：挂在本轮终态之后（不要和终态回调抢时序）
o.afterRun(() -> drain(session));      // drain 内部用 queue 的 claim 拿队首，再 handle(claim.getItem().toInput(session))
```

要点：

- 没装 queue 时忙态直接 `REJECTED(busy)`（无处可延）；
- 显式 `scope` 进积压只保存名单类维度，续发时与最新解析结果取交集（只会变窄）；
- 「停」是彻底的：`INTERRUPT_ALL` 会清空队列；要「停当前、留队列」用 `CANCEL_STREAM`；
- 插话（steer）与 interrupt 互斥：`interrupt` 之后 `activeRunId` 已清，`steer` 恒为 `NOT_RUNNING`，想「先停再改」只能走排队。

### 4.9 程序轮次：后台 / 定时任务

```java
// 调度线程上跑一轮：不识别命令与提及，忙时拒绝（不进积压）
InputOutcome o = engine.handle(HarnessInput.ofProgram(session, prompt, "scheduler")
        .locale(engine.getLocale()));          // 语言跟着创建这一轮的上下文走

if (o.getKind() == InputOutcome.Kind.TASK) {
    String text = o.call().getContent();
} else if (o.getRejectReason() == RejectReason.BUSY) {
    // 跳过本轮（不是失败）：会话正忙
}
```

这是 goal / loop 缺省执行器的形态。注意几点：`isProgram()` 为 true；`whenBusy` 固定 `REJECT`；`outcome.isDispatched()` 表示「程序派发线程上的一轮」（积压续发），此时引擎不会因为「有积压」而延后。

### 4.10 多租户服务端（SERVER）

```java
HarnessEngine engine = HarnessEngine.of(wsRoot, ".solon/")
        .stateSource(stateSource)
        .sessionProvider(sharedProvider)                    // 共享存储 + 缓存
        .deployMode(DeployMode.SERVER)                      // 构建期校验模型 / defaultModel / scopeRequired
        .scopeResolver(InputScopeResolver.byPrincipal(...)) // 强制边界：按登录身份解析
        .sessionOwnership(SessionOwnership.namespaced(tenantId, delegate))  // 多节点单写者
        .cwdPolicy(path -> path.startsWith("/data/" + tenantId))           // 会话工作目录边界
        .runtimeMutable(false)                              // 运行期不许改引擎级配置
        .build();

// 每个输入带上主体身份：与会话主体不一致会被拒（SCOPE_UNAVAILABLE）
engine.handle(HarnessInput.of(session, text).principal(userId));
```

- 引擎池 `HarnessEnginePool`（也在 core）按 key 管理多个引擎实例，回收前用 `engine.isQuiescent()` 判断是否还有后台工作：

```java
HarnessEnginePool pool = HarnessEnginePool.builder(key -> buildEngine(key))
        .maxEngines(64)
        .idleTtl(Duration.ofMinutes(30))
        .build();

HarnessEngine engine = pool.get(tenantId);
```

- 会话归属：`engine.checkOwner(session)` / `requireOwner(id)`；非持有节点返回 `REJECTED(not_owner)`，`getRejectDetail()` 是持有者标识，据此转发。
- 隔离模型、存储、容量与上线清单见 [docs/SAAS.md](docs/SAAS.md)。

---

## 五、命令

### 5.1 引擎不自带命令，调用方负责注册

`harness` 提供了命令框架与一批实现：通用基础命令在 `command.builtin`（`help` / `clear` / `model` / `rewind` / `rerun` / `continue` / `interrupt`），会话类命令随各插件自包含（`GoalCommand`、`LoopCommand`、`QueueCommand`、`SteerCommand`，由各自 Extension 在 `onAttach` 注册）。基础命令**不会自动注册**——「哪些命令可用」是端特性。裸引擎上 `handle(session, "/help")` 返回 `TASK` 而不是 `REPLY`。

```java
// 1) 构建期（SERVER 下只能这样，build() 末尾注册表封口）
HarnessEngine engine = HarnessEngine.of(ws, ".solon/")
        .commandAdd(new HelpCommand())
        .commandLoad(engine.getWorkspaceSource(), "commands")     // Markdown 命令目录
        .build();

// 2) 运行期（仅 runtimeMutable 时）
CommandRegistry registry = engine.getCommandRegistry();
registry.register(new ClearCommand());
registry.load(FileMountSource.of(Paths.get(userHome, engine.getHarnessCommands())), "");  // ~/.solon/commands/
registry.load(engine.getWorkspaceSource(), engine.getHarnessCommands());                 // ./.solon/commands/
```

注册后**不需要**配 `commandOutputFactory`：默认 `BufferedCommandOutputFactory` 已能把回执交给 `getCommandOutput()`。

### 5.2 写一个命令（代码方式）

```java
public class StatusCommand implements Command {
    @Override
    public String name() {
        return "status";                     // 不含 "/" 前缀
    }

    @Override
    public String description(@Nullable Locale locale) {
        return "显示当前会话状态";
    }

    @Override
    public String[] examples(@Nullable Locale locale) {
        return new String[]{"/status"};
    }

    @Override
    public boolean terminalOnly() {
        return false;                        // true 时无终端能力的渠道不展示也不执行
    }

    @Override
    public boolean runnableWhenBusy() {
        return true;                         // 只读命令可在任务执行中运行
    }

    @Override
    public void execute(HarnessEngine engine, CommandInput input, CommandOutput output) {
        output.println("session = " + input.getSession().getSessionId());
        output.println("args    = " + input.getArgsJoined());
        output.println("color   = " + output.color("\033[32mOK\033[0m"));
    }
}

engine.getCommandRegistry().register(new StatusCommand());
```

命令拿到两个对象，读写分开：

| 对象 | 职责 | 内容 |
|---|---|---|
| `CommandInput` | 只读输入（`HarnessInput` 的只读包装） | 会话、命令名与参数、来源 `getOrigin()`、终端能力 `isTerminal()`、**生效语言** `getLocale()`、本轮属性（提示词、模型 / 子代理选择、技能 / MCP 视野、作用域、宿主域属性） |
| `CommandOutput` | 输出（端适配层） | `println` / `color` 回执、`requestAgentTask` 任务意图；CLI 实现实时写终端，Web / IM 默认是内存缓冲 |

> `HarnessInput` 本身仍可写（`onInput` 要改它），但命令拿不到可写对象；命令要派生新输入时用 `input.derive(text)`，返回全新的 `HarnessInput`。
>
> 命令默认**需要会话**（`requiresSession()` 为 true）：无会话的输入（`HarnessInput.of(null, "/xxx")`）由分发器统一回执「需要会话」，命令里 `input.getSession()` 因此不为 null。不碰会话的命令（如 `/help`、Markdown 命令）覆写为 false。

### 5.3 写一个命令（Markdown 方式）

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

| 变量 | 含义 |
|---|---|
| `$ARGUMENTS` | 全部参数拼接为单个字符串 |
| `$1` `$2` `$3` ... | 按位置取单个参数 |

子目录即命名空间：`commands/deploy/staging.md` → `/deploy:staging`。

### 5.4 自定义命令输出

`commandOutputFactory(...)` **是可选项**：不设时分发器自动用 `BufferedCommandOutputFactory.INSTANCE`，回执写内存、从 `InputOutcome.getCommandOutput()` 一次取回。需要接管回执输出方式（实时写终端、自定义分页）时才提供工厂：

```java
InputOutcome outcome = engine.handle(
        HarnessInput.of(session, "/help")
                .commandOutputFactory(in -> new MyTerminalCommandOutput(terminal)));
```

工厂只收一个参数 `CommandInput`（想按渠道区分输出时读它），输出对象只管写，不持有会话、参数或语言。终端能力是**输入属性**（`HarnessInput.terminal(true)`），不是工厂属性。

### 5.5 改前缀

```java
HarnessEngine.of("/data/work", ".solon/")
        .commandPrefix("::")   // 默认 "/"
        .agentPrefix("#")      // 默认 "@"
```

改前缀后原前缀自动降级为普通文本。

---

## 六、子代理

### 6.1 内置子代理

内置定义随包发布在 `META-INF/solon/ai/harness/agents/`：

| 代理 | 工具集 | 定位 |
|---|---|---|
| `general` | `*` | 通用全能，其它子代理不合适时的默认解 |
| `bash` | `list, read, bash` | 命令行执行（无文件写权限） |
| `explore` | `code, list, read, grep, glob, skill, webfetch, websearch, codesearch` | 信息探索（无写权限） |
| `plan` | `list, read, grep, glob, skill, webfetch, websearch, codesearch` | 方案规划（无写权限） |
| `git-summary` | 无（`hidden: true`） | 生成提交摘要 |

```java
// 推荐：走两段式，由引擎装配请求（含命令识别与附件装配）
InputOutcome outcome = engine.handle(
        HarnessInput.of(session, "统计各语言的代码行数").explicitAgent("bash"));
outcome.getRequest().stream().blockLast();
```

> 直接用 `createSubagent(def).build()` 拿到的 `ReActAgent` 也能独立调用（`createSubagent` 只是把定义交给 `ReActAgent.Builder`），但那样就绕过了 `handle` 的命令识别与附件装配，只适用于「不走统一入口」的特例；该路径 `call()` 声明的异常是 `Throwable`，需自行捕获。

### 6.2 指定与解析的优先级

```
explicitAgent（请求体显式）  >  输入开头的 @提及  >  selectedAgent（选择器兜底）
```

无有效提及时整段原样交给主代理；名字不存在时同样不剥离（除非 `agentNameValidator` 判定失败）。统一入口是 `engine.resolveAgent(session, name)`：先会话级，再引擎级。

### 6.3 主代理委派（`task` / `multitask`）

主代理通过工具自动委派，宿主无需写代码：

```java
engine.setSubagentEnabled(true);              // 默认 true
engine.setSubagentDefaultConcurrency(5);      // 子代理任务默认并发上限（模型未配置时生效）
config.setConcurrency(2);                     // 模型级并发上限（配了就完全覆盖默认值，不再取 min）
```

- 生效上限 = 模型 `AiConfig#concurrency`（0 = 未设置）?: `subagentDefaultConcurrency`（默认 5，取值 1~10）；**不存在「不限制」**（不限会退化为只受任务数约束，最容易打爆上游与 OOM）；
- 超限不报错，在调用线程排队；排队时「谁的目标模型有空配额就提交谁」；限流是引擎级、跨会话共享的；
- 结果外壳：每个子任务包在 `<task_result>` 里，`<result_content>` 用 CDATA，**有 20000 字符上限**；超出时完整结果落盘到 `<workspace>/.files/task/<task_id>/result.txt`，头部保留截断说明与相对路径；
- 用户取消时父代理不再逐个等到底：中断在跑的子任务，不再等待剩余任务；
- 接着做（同 opencode 的 `task_id`）：子代理跑起来过的结果成败都带 `<task_id>`，模型带上它再调 `task` / `multitask`，就在原子会话上继续（失败时从断点重试，完成后追问）；只校验归属（必须是本会话委派的），不限次数。

**子会话的存储与清理（宿主须知）**：每个子任务都在引擎的 `sessionProvider` 里开一个子会话（成败都保留，供接着做与过程回放），ID 形如 `task-<32 位小写十六进制>`。引擎不删子会话，清理归宿主：

- 判断是否子会话一律用 `TaskTalent.isTaskId(id)`（完整格式，不要只比前缀）；
- 引擎一律带父会话 ID 取子会话：`getSession(taskId, parentSessionId, createIfAbsent)`。提供者据此把子会话存在父会话之下（soloncode 为 `sessions/<父>/subtasks/<taskId>/`），删父会话时一并删除；忽略它则是平铺存储，归属仍由引擎比对子会话元数据校验；
- 删除父会话前用 `engine.status(session).isRemovable()` 判定（不是 `isOccupied()`）：父流被取消后子任务可能还在跑，这时删掉，它收尾时会把子会话写回来。还要注意两点：
  - **不可删是暂时状态**：子任务只在收到下一条输出时才发现父流已取消，卡在长工具调用里时要等到该调用结束或超时。对外建议返回 409「子任务收尾中，请稍后重试」，不要当成删除失败；
  - **只对本进程有效**：运行中的子任务只登记在跑它的那个引擎里（同 `isBusy()`）。多节点部署时删除请求必须路由到持有该会话的节点，否则别的节点看到的恒为可删。同一进程内，引擎池不会回收还有子任务在跑的引擎（`isQuiescent()` 把它算在内）；
- 连带删除只对「按父会话分目录 / 分键存储」的提供者自动生效：引擎删会话（`removeSession`）只通知扩展，不通知提供者，也不会去删子会话。内存提供者即使收到父会话 ID，子会话也不会随父会话消失，需要时由宿主在删父会话时按自己的存储结构一并清掉；
- 提供者按实例缓存会话时，子会话建议不进缓存（每次从存储打开），否则会常驻内存；存数据库 / Redis 的宿主会多出大量 `task-*` 记录，按过期清理（soloncode 为 7 天）；
- 提供者在 `getSession(id, false)` 时不返回已有子会话（如每次都新建一个内存会话），接着做会安全地报「不存在」；
- 对外入口应拒绝直接打开 `task-` 会话；回放子过程时按工具结果 metadata 里的 `TaskTalent.META_SUBTASKS` 找到子会话，用 `TaskTalent.subtaskTraceOf(sub)` 取子 trace。
- 工具还没返回时（子任务运行中刷新、进程在子任务中途退出后重启），结果消息还不存在，这次调用也还没写进父 trace 的工作记忆（引擎等一步的工具全部执行完才成对写入），只在 `trace.getLastReasonMessage()` 里。此时按父 trace extras 的 `TaskTalent.EXTRA_RUNNING_SUBTASKS`（`_runningSubtasks`：调用 id → 子任务索引，调用 id 即 ToolCall 的 uuid、流式工具事件的 callId）找子会话，并核对子会话 `META_SUBTASK` 里的 `parentCallId` 与该调用相等（不等即还没启动，或已被后来的调用接着做，应忽略）。索引在委派前登记并落一次父快照（连同 `lastReasonMessage`），工具返回后不删，下一轮开始时清空；只应对「还没写进工作记忆的那一步」里的调用使用。引擎经 `ReActTrace#getActingCallId()` 取得正在执行的调用 id（不动工具参数）；取不到时（文本模式）不登记。
- 「仍在执行」的运行锁只在单个进程内有效：多个实例共用同一个 `sessionProvider` 时，同一个 `task_id` 可能被同时接着做，需要时由宿主自己加分布式锁。

### 6.4 运行时创建子代理

面向模型的 `generate` 工具按是否持久化分两级：

| `saveToFile` | 可见范围 | 存放位置 | 约束 |
|---|---|---|---|
| `false`（缺省） | 只有创建它的会话 | 会话上下文（`session.agents`），随会话快照保存，重启后仍可调用 | 每会话最多 16 个；不得与全局子代理同名 |
| `true` | 本引擎所有会话 | 工作区代理目录 `<harnessAgents>/<name>.md` | 受 `agentPersistPolicy` 约束（STANDALONE 缺省允许、SERVER 缺省拒绝）；同名文件或同名代理已存在时拒绝，不覆盖 |

两级都不允许使用保留名（`main`、`general` 及内置挂载提供的代理名，见 `engine.isReservedAgentName`）。

```java
// 一行式：复用 general 定义改写（就是面向 LLM 的 generate 工具方法；返回 ToolResult，失败时 isError() 为 true）
ToolResult result = engine.getGenerateTalent().generate(
        "code_reviewer",                        // name（仅字母数字_-）
        "代码审查专家",                          // description
        "你是一位严格的代码审查专家，只指出真实缺陷。",   // systemPrompt
        Arrays.asList("read", "grep", "code"),  // tools（严禁全选）
        null,                                   // skills
        false,                                  // saveToFile：false = 仅本会话；true = 全局持久化
        engine.getWorkspace(),                  // __cwd
        session.getSessionId(),                 // __sessionId（会话级代理必需）
        null);                                  // __locale：文案语言（null = 默认语言）

// Builder 式（toolsAdd 收 List<String> 或 String...）
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

### 6.5 把子代理定义成文件

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

| 字段 | 说明 |
|---|---|
| `name` / `description` | 必需 |
| `tools` | 工具名或快捷权限 `pi` / `*` / `**`（`skills` / `mcpServers` 走定义里同名字段） |
| `disallowedTools` | 在 `tools` 结果上做减法 |
| `model` | 指定模型 |
| `hidden` | 不在列表里展示 |
| `primary` | 是否为主代理。**只有主代理能使用 `hitl`**：子代理即使配了 `hitl` 或 `**` 也不会被挂载（子代理挂起后无人能决策，而父代理正阻塞等待它，互等不可解） |
| `enabled` | 是否启用 |
| `permissionRules` | 权限规则（`toolName` / `behavior` / `pattern` / `priority`） |

> `memory` / `hooks` / `background` / `isolation` / `teamName` 已标记 `@Deprecated`，保留字段但不再解析。技能与 MCP 现在走**挂载**与 `engine.addMcpServer(...)`。

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

## 八、扩展点

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
| `onAttach` | `Builder.build()`；`addExtension()` | **否**（装配必须完成） |
| `onDetach` | `removeExtension()`；`shutdown()` | **否**（清理不得被开关阻止） |
| `onAgentBuild` | `AgentFactory.create()` 末尾，无条件 | **否**（停用由扩展自己传播给子组件） |
| `onInput` | `handle()` 第 0 步受理判定通过之后（被排队 / 拒绝的输入不触发；排队的输入续发时触发一次） | 是 |
| `onRequestAssemble` | `ReActRequest.options(...)` 内 | 是 |
| `onSessionInterrupt` | `interrupt()` / `prepareInterrupt()`，先于停流 | **否**（清理类） |
| `contributeSessionStatus` | `status()` | **否**（事实类） |
| `onSessionRemove` | `removeSession()`（永久删除；只移出内存不触发） | **否**（清理类） |
| `isQuiescent` | `engine.isQuiescent()`（引擎池回收前） | **否**（事实类；异常按「不静默」） |
| `getToolNames` | 作用域守卫 | **否**（声明类） |

**删除会话的固定顺序**：判忙（忙则拒绝）→ `engine.removeSession(id)` → 移出内存 → 删除存储。`removeSession` 必须在删存储之前，否则定时任务可能在中间触发，把会话重新建出来。

#### `onAgentBuild` vs `onRequestAssemble` 的分工

这是最容易选错的一对：

| | `onAgentBuild` | `onRequestAssemble` |
|---|---|---|
| 作用域 | **代理实例**（同一代理的每次请求都生效） | **单次请求** |
| 触发频率 | 主代理重建时（改模型/开关扩展）+ 每次子代理新建 | 每次请求（含 HITL 恢复、`/continue`、命令产物、委派任务） |
| 常用方法 | `defaultTalentAdd` / `defaultInterceptorAdd` / `systemPrompt` | `talentAdd` / `interceptorAdd` / `maxTurns` / `retryConfig` |
| 能否区分「委派任务」 | **不能** | **能**（`isDelegated`） |
| 热路径约束 | 是（子代理按请求新建，须廉价） | 是 |

**选型口诀**：要「这个代理每次都带上」→ `onAgentBuild`；要「只挂这一次」或「子代理不挂」→ `onRequestAssemble`。

#### `onRequestAssemble` 覆盖的四类请求

| 场景 | `isDelegated` | `agentName` | 会话 |
|---|---|---|---|
| 用户新输入（主代理） | `false` | 主代理名 | 业务会话 |
| 用户新输入（**面板 / `@` 选中的子代理**） | `false` | **子代理名** | 业务会话 |
| HITL 恢复 / `/continue` / 队列续发 | `false` | 生效代理名 | 业务会话 |
| `task` / `multitask` 委派 | **`true`** | 子代理名 | 临时 `InMemoryAgentSession` |

> **为什么面板选中的子代理是 `isDelegated = false`**：它是**顶层请求**，只是目标代理换成了子代理。会话能力（如 steer 插话、queue 排队）必须对它生效。反过来，`isDelegated = true` 的委派任务跑在临时会话上，没有前端邮箱语义，必须排除。

#### `onInput` 的能力边界

返回 `void`——**只能观察与就地改写，不能否决**（想「拦截并改成另一种结局」请走命令分发或代理侧拦截器）。`HarnessInput` 的模型 / 代理 / 技能等选择项有 setter，可在钩子内补默认值，这是 `void` 签名下唯一能改变后续行为的方式。

命令会经内层 `handle` 再走一次（命令产物任务），因此 `onInput` 对「命令 + 其产物」会触发**两次**，实现须能容忍重复。

> 注意：`addExtension` 会触发一次 `onAttach`，此刻引擎已装配完毕，任意引擎方法都可安全调用。
> `shutdown()` 会遍历回调 `onDetach`（逐个 try/catch，清理期异常不会打断其余扩展与执行器回收）。

---

## 九、安全机制

### 9.1 门控总览：一次工具调用要过哪几道关

门控分散在三个包（`scope` / `permission` / `hitl`）和几个引擎配置项里，各管一件事。按生效顺序：

| 顺序 | 关卡 | 所在 | 管什么 | 怎么配 |
|---|---|---|---|---|
| 1 | 工具可见性 | `agent.ToolName`、代理定义 `tools` / `disallowedTools` | 模型能看到哪些工具（看不到就调不了） | §7：`Builder.toolsAdd / disallowedToolsAdd`、`engine.allowTool / disallowTool` |
| 2 | 输入级作用域 | `scope.InputScope`（由常驻的 `ScopeGuardInterceptor` 执行，优先级最高） | 这一次输入能用哪些工具 / 技能 / MCP / 模型；越界调用在执行前预填拒绝结果，HITL 也不会为它弹卡 | 引擎级 `scopeResolver`（推荐 `InputScopeResolver.byPrincipal`）或 `HarnessInput.scope(...)` |
| 3 | 权限规则 | `permission.PermissionEngine` / `PermissionRule` | 对工具参数（命令、路径、域名）判 `ALLOW / DENY / ASK` | 由 hitl 策略内部调用；全局规则在策略上配 |
| 4 | 人工审批 | `hitl.*ToolStrategy` + `HITLInterceptor`；`hitl.RememberedApprovals` 记住会话级「总是允许」 | 规则判为 ASK 的调用挂起等人决定 | §9.2、`hitlEnabled(true)` |
| 5 | 执行环境 | `TerminalTalent` 沙箱、`protectedStatePaths`、`cwdPolicy` | 工具真正执行时能碰哪些目录、哪些状态文件 | §1.2 配置表、§9.3、[SAAS.md](docs/SAAS.md) §5.1 / §5.2 |

前两关是「能不能用」，后三关是「用的时候允不允许」。会话归属（`session.SessionOwnership`）不属于工具门控：它在受理之前就决定「这个节点能不能跑这个会话」。

### 9.2 三套策略

`hitl` 包提供三套内置干预策略：

| 策略 | 覆盖 |
|---|---|
| `BashToolStrategy` | 注入防御、系统命令黑名单、路径边界、环境变更分级、网络零信任、管道安全链、破坏性操作 |
| `WriteToolStrategy` | 写入路径防御 + 规则引擎 |
| `WebToolStrategy` | 域名黑名单 + 规则引擎 |

**默认只有 `BashToolStrategy` 绑定到 `bash`**（`initHitlDefault`）。`Write` / `Web` 两套策略需自行绑定，且两者构造器都要传 `toolName`：

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

审批事件与卡片取法见 §3.3、§3.5，规则与策略细节见 [docs/hitl.md](docs/hitl.md)。

### 9.3 服务端安全底线

多租户部署时，除 HITL 外还有三道引擎级防线：请求级作用域（`InputScope`，工具执行前否决越界调用）、受保护状态路径（`protectedStatePaths`，文件工具与 OS 沙盒同时拒绝）、会话工作目录策略（`cwdPolicy`）。各项缺省与语义见 §1.2 配置表「服务端」各行，隔离模型、作用域、会话归属与容量见 [SAAS.md](docs/SAAS.md)。

---

## 十、模块结构

### 10.1 模块与依赖方向

```
solon-ai-harness            聚合包（只做引用，没有源码）
├── solon-ai-harness-core   核心：不依赖任何插件
├── solon-ai-harness-goal   ─┐
├── solon-ai-harness-loop    │ 各自只依赖 core；插件之间零 import，
├── solon-ai-harness-queue   │ 只经核心钩子 / spi / 会话槽位协作
├── solon-ai-harness-steer  ─┘
└── solon-ai-harness-host   宿主骨架（会话绑定 / 推送外壳），依赖 core + 四个插件（引擎池 HarnessEnginePool 在 core）
```

依赖方向（任何模块不得反向依赖 core）、聚合包无源码、插件内部类不得 public、插件主代码无废弃标记、goal / loop / queue 写门面必须返回 `HarnessResult` 等约束由模块结构守卫测试守护。

### 10.2 核心包结构

```
org.noear.solon.ai.harness
├── HarnessEngine                 ★ 引擎（Builder + 编排 + 唯一入口 handle）
├── DeployMode                    部署模式（STANDALONE / SERVER）
├── HarnessOptions                运行时配置（包私有）
├── HarnessExtension / AbsHarnessExtension   扩展 SPI
├── InputScopeResolver            作用域解析器（配给引擎的策略：Builder.scopeResolver(...)）
├── RunTicket / InterruptPlan / InterruptMode   一条流（run）的生命周期凭据、两段式中断计划、一步式停流方式
├── HarnessResult                 插件写门面与管理入口的统一结果（粗码 OK / NOT_FOUND / NOT_OWNER / DISABLED / FULL / INVALID / CONFLICT / FORBIDDEN + 可空细分码 getReason + 建议 HTTP 状态码 httpStatus()）
├── HarnessEnginePool             引擎池（按 key 管理多引擎、空闲回收）
├── InputPipeline / SessionControl / AdmissionGate / AdmissionDecision / AdmittedRequest / HarnessInterrupts   受理、装配与停流实现（包内，不是 API）
│
├── input/                        输入层：HarnessInput ★、InputOutcome ★（六态）、InputAttachments、InputOrigin、BusyPolicy、RejectReason、AgentMentions
├── command/                      命令框架：Command / CommandInput / CommandOutput / CommandRegistry / CommandDispatcher、Markdown 命令、builtin/
├── agent/                        子代理（定义 → 注册 → 构建 → 委派）：AgentDefinition / ToolName、AgentManager / SessionAgents / GenerateTalent、AgentFactory、TaskTalent / TaskWrapEvent；SubTaskScheduler 包内
├── scope/                        输入级作用域：InputScope / ScopeGuardInterceptor
├── spi/                          插件能力接口（只放接口，可带静态槽位访问方法）：InputBacklog / RunDispatch
├── session/                      会话事实与归属：SessionStatus / HarnessAttrs / SessionSelections；SessionOwnership / OwnerCheck / NotOwnerException
├── hitl/                         干预策略（Bash / Write / Web）、HitlCard、RememberedApprovals
├── permission/                   权限引擎（PermissionRule / PermissionContext / PermissionBehavior / PermissionEngine）
├── i18n/                         引擎内建文案资源
└── util/                         ErrorClassifier / HarnessIds / TraceUtil
```

子包之间只允许两个双包循环：`{input, command}` 与 `{agent, scope}`，其余依赖单向。白名单与循环检测由包边界测试守护，新建子包必须先登记。

插件包为 `org.noear.solon.ai.harness.extension.{goal,loop,queue,steer}`，外部只接触各自的 `XxxExtension` 门面与只读数据类。

### 10.3 依赖包（能力来源）

| 依赖 | 提供 |
|---|---|
| `solon-ai-agent` | `ReActAgent` / `ChatModel` / 压缩与 HITL 拦截器 |
| `solon-ai-talent-cli` | `TerminalTalent`（沙箱文件与命令、`SandboxBootstrap`）、`SkillTalent`、`ClockTalent`；传递引入 `solon-ai-mount`（`MountManager` / `MountSource`） |
| `solon-ai-talent-code` | `CodeTalent`（技术栈识别 / CODE.md） |
| `solon-ai-talent-todo` | `TodoTalent`（会话级任务清单，经状态来源存储） |
| `solon-ai-talent-web` | `WebsearchTalent` / `WebfetchTalent` / `CodeSearchTalent` |
| `solon-ai-talent-lsp` | `LspManager` / `LspTalent` |
| `solon-ai-talent-memory` | `MemoryTalent` / `MemorySolutionProvider` |
| `solon-ai-talent-gateway` | `McpGatewayTalent` / `OpenApiGatewayTalent` |
| `solon-lib` | IoC / 配置 / 生命周期 |
| `solon-serialization-snack4` | JSON 序列化 |

---

## 十一、常见坑

1. **裸引擎没有命令**——`/help` 会返回 `TASK`。测试命令必须先注册：构建期 `Builder.commandAdd(...)` / `commandLoad(source, dir)`（SERVER 模式只能这样，build 末尾注册表封口），或运行期 `getCommandRegistry().register(...)`（仅 `runtimeMutable` 时）；注册后**不需要**配 `commandOutputFactory`。
2. **`handle` 第一段不启动推理**——它只装配。忘了订阅 `getRequest().stream()` 会话会一直占着（直到 `admissionTimeoutMs` 超时）；决定不跑就调 `outcome.discard()`。
3. **`outcome.call()` / `outcome.stream()` 只在 `TASK` 上有意义**——它们在 `REPLY` / `HITL` / `IGNORED` 上会立即失败（前者抛 `IllegalStateException`、后者返回 `Flux.error`）。先判 kind 再取流，顺序不能颠倒。
4. **HITL 续流必须显式，但一次调用就够**——`.hitl(action, callId).resume(true)`，引擎自检「决策命中 + 本批齐备 + 显式要求」三项，全成立返回 `TASK`，否则返回 `HITL`（并携带 `isHitlApplied()` / `getPendingHitlCount()`）。续流时**不要**传正文。
5. **HITL 续流要用 `outcome.getAgentName()`**——它是「本轮生效代理」。换回主代理会取不到挂起的 `tool_calls`，续跑空转。
6. **`HitlCard.list(session)` 不能用来判「整批是否齐备」**——该集合含已决策项；齐备判定读 `outcome.getPendingHitlCount()`。
7. **`@` 提及必须带空白边界**——`@bash 内容` 有效，`@bash内容` 走普通文本；提及路径与 `explicitAgent` 路径剥空白的行为不同，需要一致就加 `.trimContent(true)`。
8. **有附件时不识别命令、也不进积压**——带附件的 `/cmd` 按普通消息处理；会话忙时返回 `attachments_not_deferrable`。
9. **`options(Consumer)` 是累加语义**——`getRequest()` 之后再追加 `options` 是安全的叠加。
10. **`getRequest().stream()` 是冷流**——何时订阅、在哪个线程订阅由调用方决定；引擎不内置 `subscribeOn`。
11. **`STANDALONE` 模式下 `build()` 只校验 `sessionProvider`**——缺模型时 `build()` 只告警，构建后仍没补上要到首次 `handle` 才报 `chatModel is required`；`SERVER` 模式在构建期就报。
12. **HITL 默认只绑了 `bash`**——`Write` / `Web` 两套策略需自行 `onTool(...)` 绑定，否则不生效且不报错。
13. **新建子代理要注册**——`AgentDefinition` 构造完必须 `getAgentManager().addAgent(def)`，否则 `explicitAgent("xxx")` 回落主代理。
14. **`InterruptMode.INTERRUPT_ALL` 会连带清队列**——它不是「只停当前流」。要「停当前、留队列」用 `CANCEL_STREAM`；`interrupt` 返回 `false` 也不代表什么都没发生（取消代数仍推进，能拦住随后才订阅的幽灵流）。
15. **取消以 `onComplete` 收尾，不走 `onError`**——别用 `doOnError` 判定本轮失败；取消的轮次也不会推 `RunEndEvent`。
16. **命令文本与门面调用是同一个动作，别把两者的返回值当一回事**——`engine.handle(HarnessInput.of(s, "/queue 跑测试"))` 得到的是 `Kind.REPLY` + 人读回执，`queue.enqueue(s, "跑测试")` 得到的是 `HarnessResult<Integer>`（OK 时值为位置）。要程序判定走门面，要现成文案走文本路径；**两条都走会入队两次**（命令路径无幂等保护）。
17. **门面不经受理、不经作用域**——`queue.enqueue`、`steer.steer`、`goal.create/update`、`loop.schedule`、`interrupt` 会先查会话归属（非持有节点返回 `NOT_OWNER`），但不校验作用域；多租户的管理接口先自行鉴权。
18. **外部传入的会话 ID 用 `requireSession` / `getSessionOrNull`**——`getSession(id)` 不存在就新建。
19. **引擎 `shutdown()` 之后不要再用旧引用**——之后的 `handle` 返回 `REJECTED(engine_closed)`；引擎池模式下重新 `pool.get(key)` 即可。
20. **显式 `HarnessInput.scope(...)` 进积压时只保存名单类维度**——续发时与最新解析结果取交集（只会变窄）；带 `chatModel` / `attr` 的显式作用域在忙时被拒绝（`scope_not_deferrable`）。服务端请用引擎级 `scopeResolver`。
21. **插件相关的坑在各插件 README 的「注意事项」**——goal 执行器注册时机、queue 续发、steer 拦截器与「interrupt 之后不要再 steer」、job 名前缀、没有「只关命令」的开关等。

---

> **测试依赖**：`solon-test`（test scope）
> **编译辅助**：`lombok`（provided scope）
