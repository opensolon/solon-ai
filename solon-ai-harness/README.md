# solon-ai-harness — AI 代理编排引擎

## 简介

`solon-ai-harness` 是 Solon AI 的 **Agent 运行时与编排容器**：把「ReAct 引擎 + 一组 Talent 能力 + 子代理体系 + 命令系统 + 安全策略」装配成一个可直接跑起来的 Coding Agent。

它面向的使用者是**宿主应用**（CLI、Web、桌面、IM 网关）。宿主只需要做三件事：

1. 构造 `HarnessEngine`（给工作区、给模型、给工具权限）；
2. 把用户输入交给 `engine.handle(...)`；
3. 消费 `InputOutcome`——按 `getKind()` 一次 `switch` 分派；任务流取 `getRequest().stream()`（或直接 `outcome.stream()`），回执取 `getCommandOutput()`。

命令识别、子代理解析、附件装配与落盘（由宿主完成）、Prompt 构建、请求装配，在一次调用内串联完成。

### 怎么读这份文档

| 你想做什么 | 读哪里 |
|---|---|
| 先跑起来看看 | 第一章（五分钟上手）→ **第三章 3.1**（最小骨架） |
| 做多租户 / 服务端（SaaS） | **[QUICKSTART-SAAS.md](QUICKSTART-SAAS.md)**（宿主骨架 `HarnessHost`，只实现两个输出回调）→ [CONCEPTS.md](CONCEPTS.md)（术语）→ [SAAS.md](SAAS.md)（`HarnessProfile.SERVER`、作用域、会话归属、引擎池、部署清单） |
| 接一个真产品 | **第三章**全部（两段式、事件渲染、HITL 续流、四端时序） |
| 加命令 / 子代理 | 第四章、第六章 |
| 跑 goal / queue / steer / loop | **第五章**（5.1 / 5.2 / 5.3，状态型命令的装配与宿主责任）；完整参考见各插件 README：[goal](../solon-ai-harness-goal/README.md) · [loop](../solon-ai-harness-loop/README.md) · [queue](../solon-ai-harness-queue/README.md) · [steer](../solon-ai-harness-steer/README.md) |
| 分清「命令文本 / `handle(HarnessInput)` / 门面」三条路 | **§5 引言**（同一动作的三种写法与返回物差异） |
| 停 / 改 / 重排的干预闭环 | **§5.4**（`InterruptMode.INTERRUPT` + `steer` + `queue`，两个互斥场景） |
| 运行期开关扩展 | **§5 引言**（`engine.setExtensionEnabled`）+ §8.2 |
| 调优与定制 | 第七～十章（权限、配置、扩展、安全） |
| 查结构 / 躲坑 | 第十二章（模块图）、第十三章（常见坑） |

> **术语**：**input** 是一次 `handle` 的输入（`HarnessInput` / `InputOutcome` / `InputScope`）；**run** 是受理后开出的一条流
> （`runId` / `RunTicket` / `RunDispatch` / `steerOnRun`）；**step** 是 ReAct 内部的一步推理加工具调用（`maxTurns` 沿用旧名，计的是 step）。
> 正文里的「一轮」、「本轮」指 run。

> **全文档只推荐一条取流路径**：`outcome.getRequest().stream()`（等价简写 `outcome.stream()`，
> 仅 `Kind.TASK` 有效）。不存在「回执 + 任务合成一条流」的便捷方法，原因见 §3.4 的四端时序对照。
> 需要一次性拿最终文本时用 `outcome.call()`，它只转发 `getRequest().call()`，不做任何时序决策。

---

## 一、五分钟上手

### 1.1 依赖

| 坐标 | 内容 | 适用 |
|---|---|---|
| `solon-ai-harness` | **聚合包**：只做引用（没有任何源码），引入核心 + 四个会话插件（goal / queue / steer / loop）+ 宿主骨架 `-host` | 应用宿主（推荐） |
| `solon-ai-harness-host` | 宿主骨架（L0）：`HarnessHost` / `HostSink` / `AdminGateway`，依赖核心与四个插件，见 [host README](../solon-ai-harness-host/README.md) | SaaS / IM 网关 / 普通 Web 服务 |
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

> 聚合包**不自动装配**：插件上了类路径不会自己生效，需在构建期装配——逐个 `Builder.extensionAdd(...)`（见 §5）；运行期 `addExtensionIfAbsent` 只在 `runtimeMutable` 时可用（SERVER 下抛 `IllegalStateException`）。写插件的模块请依赖 `solon-ai-harness-core`，不要依赖聚合包。

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

> 缺省（`CODING` 模式）下 `build()` 只硬校验 `sessionProvider`。没配模型不会在 `build()` 报错，而是在**首次 `handle`**
> 时由 `ReActAgentConfig` 抛 `chatModel is required`。`profile(HarnessProfile.SERVER)` 会在构建期额外校验
> 状态来源、模型与 `defaultModel`（见 [SAAS.md](SAAS.md)）。

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
| 资源 | `extensionAdd(HarnessExtension)` | 空 | 扩展（§5 的 Goal/Queue/Steer 也从这里装，§9） |
| 资源 | `mountAdd(Mount)` | — | 挂载（`@harness-agents` 内置挂载不可替换，会抛 `IllegalArgumentException`） |
| 资源 | `mcpServerAdd(name, params)` | 空 | MCP 服务器 |
| 资源 | `apiServerAdd(name, ApiSource)` | 空 | OpenAPI 来源 |
| 资源 | `lspServerAdd(name, params)` | 空 | LSP 服务器 |
| 前缀 | `commandPrefix(String)` | `"/"` | 命令前缀，支持多字符（§4.5） |
| 前缀 | `agentPrefix(String)` | `"@"` | 子代理提及前缀，支持多字符（§4.5） |
| 刹车 | `stopLoopInterceptor(StopLoopInterceptor)` | `StopLoopInterceptor(3, 8)` | 重复调用刹车；不配时构造期自建 |
| 受理 | `admissionTimeoutMs(Long)` | `60000` | `handle` 返回 `TASK` 后占位的时限（§3.6） |
| 存储 | `stateSource(MountSource)` | `<workspace>/<harnessHome>` 本地目录 | harness 状态来源（会话级状态、goal 索引、loop 任务等），模型不可见 |
| 服务端 | `profile(HarnessProfile)` | `CODING` | 运行模式预设；`SERVER` 的缺省与校验见 [SAAS.md](SAAS.md) |
| 服务端 | `scopeResolver(InputScopeResolver)` / `scopeRequired(boolean)` | `null` / `false`（SERVER 为 `true`） | 输入级作用域（只能收窄的强制边界）；解析器在归属检查之后、会话锁之外调用 |
| 服务端 | `sessionOwnership(SessionOwnership)` | `SessionOwnership.LOCAL` | 多节点单写者租约；引擎池里共用同一个实现时用 `SessionOwnership.namespaced(tenantId, delegate)` |
| 服务端 | `cwdPolicy(Predicate<String>)` | 不校验 | 会话工作目录的边界策略：`setSessionCwd` 与终端工具使用端都按真实路径校验，不满足抛 `SecurityException` |
| 服务端 | `runtimeMutable(boolean)` | `true`（SERVER 为 `false`） | 运行期是否允许改引擎级全局配置；为 `false` 时命令与子代理注册表在 `build()` 末尾封口 |
| 服务端 | `agentPersistPolicy(BiPredicate<AgentSession, AgentDefinition>)` | 跟随 `runtimeMutable`（CODING 允许，SERVER 拒绝） | `generate(saveToFile=true)` 是否允许创建全局子代理（§6.4） |
| 资源 | `taskExecutor(ExecutorService)` | 引擎私有，上限 64 线程 | 子代理执行器；宿主注入的共享执行器由宿主关闭，引擎 `shutdown` 不动它 |
| 服务端 | `memorySessionIsolation(boolean)` | `false`（SERVER 为 `true`） | 长期记忆按会话隔离 |
| 服务端 | `sandboxSelfBootstrap(boolean)` | `true`（SERVER 为 `false`） | 是否由引擎自行初始化进程级 OS 沙盒 |
| 服务端 | `protectedStatePaths(List<String>)` | `sessions, memory, channels, tasks.json, loop, goal` | 相对 harnessHome，模型文件工具与 bash 都不得读写 |

> 三个拦截器位（压缩 / 刹车 / HITL）不配时都会在**构造期自建默认实现**，自建时机早于 `build()` 末尾的 `extension.onAttach(engine)`——所以扩展在 `onAttach` 里通过 `engine.getCompressionInterceptor()` 等拿到的必是最终实例。

### 1.3 跑第一轮对话

```java
AgentSession session = engine.getSession("cli");

InputOutcome outcome = engine.handle(HarnessInput.of(session, "看下当前项目结构"));
outcome.getRequest().stream().blockLast();   // 事件流；也可 subscribe 逐条渲染
```

取最终答复文本：

```java
String answer = engine.handle(HarnessInput.of(session, "总结一下这个仓库"))
        .call()                       // 等价 getRequest().call()；非 TASK 立即 IllegalStateException
        .getContent();
```

> 上面两段是**最简写法**，只为验证环境可用。真产品请直接用 §3.1 的骨架——
> 它把 `handle` 与 `InputOutcome` 拆成两段并处理了全部六种 `Kind`，能直接长成宿主外壳。

---

## 二、核心 API：`handle` + `InputOutcome`

### 2.1 唯一的输入入口

```java
// HarnessEngine —— 两个重载，实际只有后者做事
public InputOutcome handle(AgentSession session, String text);   // 便捷：等价于 handle(HarnessInput.of(session, text))
public InputOutcome handle(HarnessInput input);                  // 唯一入口
```

一次 `handle(...)` 内部按固定顺序做事，顺序本身是语义的一部分：

| 步骤 | 动作 | 说明 |
|---|---|---|
| 0 | 受理判定 | 引擎已关闭 → `REJECTED(engine_closed)`；会话归属（`SessionOwnership`，非持有 → `not_owner`）→ 作用域预检（锁外；显式 `scope(...)` 优先，其次 `scopeResolver`，`scopeRequired` 且解析不到 → `scope_unavailable`，不占积压名额）→ HITL 挂起拦截 → 忙态放行 / 入积压 / 拒绝 → 占位；未通过时直接返回 `DEFERRED` / `REJECTED`（§3.6） |
| 0.5 | `onInput` 钩子 | 扩展仍可在这里设置 `input.scope(...)`，装配时以显式作用域为准；解析器看不到 `onInput` 的副作用 |
| 1 | HITL 决策 | **优先于空输入判空**：审批请求通常不带文本。带 `resume(true)` 时同一次调用**尝试**续流，是否真的续流由引擎自检决定（见 §2.3） |
| 2 | 续流分流 | `resume(true)` 直接装配请求，不解析文本 |
| 3 | 空输入判空 | 无文本且无附件 → `IGNORED` |
| 4 | 命令识别与执行 | 有附件或 `commandAware(false)` 时不当作命令 |
| 5 | 子代理解析 + 附件前缀 | 解析 `@name` 前缀，文件引用拼 `[附件: x]` |
| 6 | Prompt 构建 | 多模态内容块、来源/附件/子代理元数据 |
| 7 | 请求装配 | 模型回退链 + 子代理 + `ReActRequest` |

**第 0–7 步全部同步完成，不自启推理。** 推理只在宿主订阅 `getRequest().stream()` 时才开始——这是两段式设计的根基（详见第三章）。

### 2.2 `HarnessInput`：一次输入的完整上下文

```java
// 落盘与路径校验由宿主负责（各端规则不同），引擎只装配
InputAttachments atts = InputAttachments.of()
        .media(ImageBlock.ofBase64(base64, "image/png").filePath(".uploads/shot.png"))
        .media(ImageBlock.ofUrl("https://cdn.example.com/x.png", "image/png"))
        .reference(".uploads/report.pdf");

HarnessInput input = HarnessInput.of(session, "帮我看看这个 @coder")
        .selectedModel("deepseek")                     // 显式模型
        .selectedAgent("explore")                      // 选择器兜底（提及未出现时）
        .attachments(atts)                             // 多媒体项 + 文件引用
        .origin(InputOrigin.of("dingtalk", "u1", "chat42", "msg7"))   // 渠道来源身份（IM 回复路由）。可选
        .trimContent(false);                           // 剥提及后不去空白
```

`InputAttachments` 两条通道：`media(MediaBlock)` 作为多模态输入进入消息内容（块支持 base64
与 url 两种形态，已落盘时把相对路径写在 `block.filePath(...)` 上）；`reference(path)` 以 `[附件: path]`
前缀告知模型，由 Agent 自行读取。**引擎不落盘**——由宿主保存到工作区后再交来结果。

| 方法 | 用途 |
|---|---|
| `selectedModel(String)` | 显式模型，优先于会话级选择，再回退默认模型 |
| `selectedAgent(String)` | 选择器兜底子代理 |
| `selectedSkill(String...)` | 本次请求的技能视野（收窄技能清单 + 预加载选中技能正文） |
| `selectedMcp(String...)` | 本次请求的 MCP 服务视野（按 `addMcpServer` 的配置名匹配） |
| `explicitAgent(String)` | 请求体显式指定，**优先级最高**（桌面端用） |
| `attachments(InputAttachments)` | 附件装配结果（多媒体块 + 文件引用） |
| `media(MediaBlock)` | 追加多媒体块（便捷写法） |
| `reference(path)` | 追加文件引用（便捷写法） |
| `agentNameValidator(Predicate)` | 子代理名格式校验 |
| `trimContent(boolean)` | 剥提及后去首尾空白（桌面端需要） |
| `origin(InputOrigin)` | 本次输入的渠道来源身份（渠道 / 发送者 / 返回地址 / 入站关联键） |
| `scope(InputScope)` | 本轮作用域（只能收窄的强制边界，见 [SAAS.md](SAAS.md)）；不传则走引擎级 `scopeResolver` |
| `attr(key, value)` / `attrs(Map)` | 宿主域属性（引擎不解读，进积压时原样保存） |
| `HarnessInput.ofProgram(session, text, channel)` | 程序轮次（goal / loop 缺省执行器用）：不识别命令与提及，忙时 `REJECTED(busy)`、不进积压 |
 | `hitl(action, callId)` + `hitlOutput(text)` | 审批决策与结果改写；`action` 是 `HITLAction` 枚举（APPROVE / REJECT / SKIP），线上字符串由宿主用 `HITLAction.of(action)` 解析，脏值显式报错 |
| `commandContextFactory(CommandContextFactory)` | 识别到命令后回执怎么落地（默认缓冲式） |
| `commandAware(boolean)` | 是否把输入当命令识别（默认 `true`） |
| `agentAware(boolean)` | 是否解析开头 `@xxx`（默认 `true`） |
| `terminal(boolean)` | 本次渠道是否具备终端交互能力（默认 `false`，决定 `terminalOnly()` 命令是否可见） |
| `resume(boolean)` | 续流模式（默认 `false`）；与 `hitl(...)` 合用则一次调用完成「写决策 + 尝试续流」，是否真的续流由引擎自检决定 |

四个容易踩的开关：

- **`commandAware(false)`**——文本来源不是用户手输时使用（定时任务提示词、已受理的排队输入、命令内部自造提示词）。这些即使以 `/` 开头也不应执行。
- **`agentAware(false)`**——文本由程序生成时使用（如 goal 的 objective）。即使以 `@xxx` 开头也原样交给代理，不被当作提及剥离。与 `commandAware` 成对：`/` 前缀是命令，`@` 前缀是子代理。
- **`terminal(true)`**——只有命令行宿主才开。它同时决定 `terminalOnly()` 命令是否放行、以及 `CommandContext.isTerminal()` 的返回值；不开则 `/exit` 类命令被统一拦截。与 `origin` 无关：桌面在本地运行但无终端能力。
- **`resume(true)`**——本次不产生新用户输入，只按会话中挂起的 `tool_calls` 续跑。文本与附件都被忽略。它与 `hitl(action, callId)` 合用表示「写完决策就试着续流」，但**只是一种意愿表达，不保证续流一定发生**：引擎会在写入决策后自检三项，全部成立才装配请求返回 `TASK`，否则返回 `HITL` 并携带事实（详见 §2.3）。三端的正确写法都是传 `resume(true)` 后只 `switch (kind)`——批挂起时引擎会拒绝过早续流，宿主不必自己判断「整批是否齐备」。

#### 视野选择：`selectedSkill` / `selectedMcp`

这两个方法解决的是一类问题：**技能与 MCP 服务是引擎级全局挂载的，但用户常常只想让某一次对话看见其中几个**。它们按**请求**收窄，不改全局装配，因此天然线程安全（Talent 是引擎级单例，选择随 `Prompt` 走）。

```java
HarnessInput.of(session, "用那个技能查一下订单表")
        .selectedSkill("@user-skills/investment-report")   // 只开放这一个技能
        .selectedMcp("mysql", "slack");                    // 只开放这两个 MCP 服务
```

语义三条（都有测试锁定）：

| 写法 | 含义 |
|---|---|
| 不调用 | 不限制，沿用全量目录 / 全部已注册服务 |
| 传空数组 | **等价于不调用**（归一为不限制），避免序列化空列表时把资源全关掉 |
| 传名称 | 收窄到这些项；trim + 去重保序，返回值不可变 |

两个容易误解的点：

- **它不校验存在性**。传错 / 过期的名字只是「匹配不到」，不会抛异常——一个拼错的技能名不该打死整轮对话。宿主若需要严格校验，请自己先查 `SkillCatalog`。
- **技能标识不做前缀补全**。`@user-skills/a`、`user-skills/a`、唯一短名 `a` 三种写法都接受，解析口径统一在 `SkillCatalog`；`selectedSkill("a")` 不会因为书写形态不同而失效。

生效方式不是「过滤掉工具」，而是两层：

1. **收窄视野**：`SkillTalent` 的清单、档位判断、`skilllist` 输出都跟着变；`McpGatewayTalent` 的四档模式按**收窄后**的工具数重新分档——只勾一个服务时工具数通常 ≤ `dynamicThreshold`，于是自动落入 FULL 模式，模型拿到完整 schema 直接调用，省掉 `search_tools → get_tool_detail → call_tool` 三跳。
2. **预加载正文**：选中的技能正文会拼进指令，省掉一次 `skillread` 往返。按字符预算（`preloadCharLimit`，默认 12000）控制，超预算的技能降级为 `skipped="over_budget"` 只留名字。

> **`skillread` 故意不受视野限制**：被选技能的 `SKILL.md` 里引用另一个技能时，限制读取会直接断链。视野是「模型无需搜索就能看到什么」，不是权限边界。

MCP 侧另有一处执行侧校验：`call_tool` 是中转入口，模型可能绕过 `getTools` 的过滤直接点名未选中的服务，因此它在执行前会比对视野并拒绝越界调用（`工具 'x' 不在本次选中的 MCP 服务范围内`）。

**穿透范围**：子代理（`task` / `multitask`）与命令触发的二段任务会**继承**父级视野。否则会出现「主代理只看得到 A 技能、子代理却能看见全部」的静默不一致。

### 2.3 `InputOutcome`：处置态

```java
switch (outcome.getKind()) {
    case TASK:    // 有可执行的任务（命令触发的也在 TASK，用 isCommand() 区分来源）
        if (outcome.hasReply()) {
            render(outcome.getCommandOutput());   // 命令回执（若有），任务继续跑
        }
        outcome.stream().subscribe(e -> render(e));   // 等价 getRequest().stream()
        break;
    case REPLY:   // 只有回执：命令已执行，或命令在本渠道不可用
        render(outcome.getCommandOutput());
        break;
    case DEFERRED: // 会话忙，已入积压（需装 queue）；位置 getBacklogPosition()
        render("已排队（第 " + outcome.getBacklogPosition() + " 位）");
        break;
    case REJECTED: // 未受理；原因 getRejectReason()（InputOutcome.RejectReason 枚举，线上值 .code()），文案 getRejectDetail()
        render(outcome.getRejectDetail());
        break;
    case HITL:    // 决策已写入但未续流；后续动作读事实，不只看 kind
    case IGNORED: // 空输入
    default:
        break;
}
```

<b>kind 只回答「宿主该做什么」，不回答「输入是什么」。</b>
「这条输入是不是命令」属于<b>事实</b>，由 `isCommand()` / `getCommandName()` 读回。
因此「命令触发的任务」与「用户手输的任务」共用 `TASK` 一个分支，差异只体现在「有没有回执」这份数据上。

| Kind | 宿主动作 |
|---|---|
| `TASK` | 执行 `getRequest()`；`isCommand()` 为 true 时先渲染 `getCommandOutput()` |
| `REPLY` | 渲染 `getCommandOutput()` 后收尾 |
| `HITL` | 决策已写入但未续流；读 `isHitlApplied()` / `getPendingHitlCount()` 决定后续动作（见下） |
| `IGNORED` | 无事可做 |
| `DEFERRED` | 会话忙，输入已进积压（`getBacklogPosition()` 从 1 计）；等待续发（§3.6、§5.2） |
| `REJECTED` | 未受理；按 `getRejectReason()`（`InputOutcome.RejectReason` 枚举）分流：`BUSY` / `HITL_PENDING` / `ATTACHMENTS_NOT_DEFERRABLE` / `BACKLOG_UNAVAILABLE` / `NOT_OWNER` / `SCOPE_UNAVAILABLE` / `SCOPE_NOT_DEFERRABLE` / `ENGINE_CLOSED`；线上值用 `getRejectReason().code()`（如 `busy`、`not_owner`） |

| 成员 | 说明 |
|---|---|
| `getKind()` | 上述六态（一个值对应一个动作） |
| `isCommand()` / `getCommandName()` / `getCommandOutput()` / `getCommand()` | 命令事实与回执 |
| `hasRequest()` / `hasReply()` | 有无任务 / 有无回执（派生判定，便捷入口） |
| `getRequest()` | 装配好的 `ReActRequest`（无任务时为 null） |
| `call()` / `stream()` | 终态收束：前转 `getRequest().call()` / `.stream()`，仅 `TASK` 有效（非 TASK 抛 `IllegalStateException` / 返回 `Flux.error`） |
| `getPrompt()` | 装配好的 `Prompt`（含附件前缀与图片块；续流时为 null） |
| `getAgentName()` / `getModelName()` | 生效的子代理 / 模型（HITL 态下为 `resolveHitlAgent` 解析结果） |
| `getSelectedSkills()` / `getSelectedMcps()` | 本次生效的技能 / MCP 视野（未指定、非消息路径或续流时为空集合） |
| `isHitlInput()` | 本次输入是否为审批决策（审批触发的 `TASK` 也为 true） |
| `isHitlApplied()` | 决策是否真的写入；`false` = 过期审批卡（无匹配挂起任务），非审批输入为 null |
| `getPendingHitlCount()` | 决策写入后仍待审批的挂起项数量；0 表示本批已齐备 |
| `getBacklogPosition()` / `getRejectReason()` / `getRejectDetail()` | `DEFERRED` / `REJECTED` 的事实 |
| `isBesideRunning()` / `isDispatched()` / `getDispatch()` | 忙时旁路执行 / 程序派发轮次（§3.6） |
| `discard()` / `afterRun(Runnable)` | 拿到 `TASK` 却不执行时释放占位；登记「本轮终态后」回调（队列续发应挂在这里） |

**`HITL` 态下的三种情形（必须读事实区分）**：

| 情形 | 判据 | 宿主该做什么 |
|---|---|---|
| 本批已齐备但未传 `resume` | `isHitlApplied()==true` 且 `getPendingHitlCount()==0` | 自行决定何时续流：再发一次 `.resume(true)`（传 `getAgentName()` / `getModelName()`） |
| 本批尚有未决卡 | `isHitlApplied()==true` 且 `getPendingHitlCount()>0` | 展示 / 等待下一张卡；**切勿续流**（会带走未决任务） |
| 过期审批卡 | `isHitlApplied()==false` | 提示卡已失效并丢弃；**切勿续流**（空提示词会回落 `originalPrompt`，把上一轮问题重跑一遍） |

三种情形都以 `resume(true)` 传入时，前两种会合并为「齐备才续流、不齐备返回 `HITL`」一次调用搞定，宿主只需 `switch`；只有第三种需要额外提示。

**三条刻意不做的事**：

1. **不提供「回执 + 任务合成一条流」的便捷方法**——真实宿主对两者顺序的要求各不相同：CLI 由命令即时写终端（合成会重复打印）、Web 要求「流门复位早于回执」否则回执被前端丢弃、桌面要求序号回放与 HITL 拦截器重挂、ACP 需要 `takeWhile` 取消判定。任何替宿主拼好的流都得被拆开重拼，反而遮住了必须由宿主掌握的时序。取流只有一条路径：`getRequest().stream()`。

   ⚠️ `outcome.call()` / `outcome.stream()` 与本条**不矛盾**：它们只是 `getRequest()` 的**空判包一层**（`Kind.TASK` 之外立即失败），既不合成流、也不替宿主选订阅时机与线程。需要「回执先落地、流门先复位」的宿主照旧分两步写：

   ```java
   render(outcome.getCommandOutput());   // 先回执
   resetStreamGate();                    // 再复位门
   outcome.stream().subscribe(...);      // 最后订阅（与 getRequest().stream() 等价）
   ```
2. **不内置 `subscribeOn`**——调度权归宿主。Web 端必须先注册 `Disposable` 再订阅，否则忙碌判定会出现空闲窗口、同一会话开出第二条流。
3. **不对 HITL 自动续流**——引擎不会隐式续流：没传 `resume` 就只写决策。传了 `resume(true)` 也**不保证**续流：引擎会自检「决策命中挂起任务 + 本批已齐备 + 宿主显式要求」三项，全成立才返回 `TASK`，否则返回 `HITL`。因此三端写法统一——`.hitl(action, callId).resume(true)` 一次调用，然后只 `switch (kind)`，不必自己拼两次。

---

## 三、完整示例：`handle` + `InputOutcome` 两段式（端到端）

前两章是零件清单，本章把它们拼成能跑的宿主。**两段式**是理解本模块的主线：

| 段 | 谁做 | 做什么 | 是否启动推理 |
|---|---|---|---|
| 第一段 | `engine.handle(input)` | HITL 决策 → 续流分流 → 空输入判空 → 命令识别执行 → 子代理解析 + 附件装配 → Prompt 构建 → 请求装配 | **否**，全部同步完成 |
| 第二段 | 宿主读 `InputOutcome` | 按 `getKind()` 选动作；只有 `TASK` 才订阅 `getRequest().stream()` | 是，时机与线程由宿主决定 |

第一段**永不启动推理**。这正是宿主能在两段之间插入自己准备动作（注册忙碌标记、复位流门、开 SSE 响应头、挂取消钩子）的原因，也是引擎不替宿主开流的原因。

### 3.1 最小骨架：跑通一轮

> `MinimalHost`（本节）与 `FullHost`（§3.2）都是**文档内的示例类**，没有对应的 `org.noear.*` 可 import——请直接复制到你的项目里改。harness 提供的是 `HarnessEngine` / `HarnessInput` / `InputOutcome` 这些零件，宿主外壳由你自己写。

```java
public class MinimalHost {
    private final HarnessEngine engine;
    private final AgentSession session;

    public MinimalHost(String workspace) {
        this.engine = HarnessEngine.of(workspace, ".solon/")
                .sessionProvider((id, create) -> InMemoryAgentSession.of(id)) // 必填
                .modelAdd(new ChatConfig().then(cfg -> {
                    cfg.setName("deepseek");
                    cfg.setApiUrl("https://api.deepseek.com");
                    cfg.setApiKey(System.getenv("DEEPSEEK_API_KEY"));
                    cfg.setModel("deepseek-chat");
                }))
                .defaultModel("deepseek")
                .toolsAdd(ToolName.TOOL_ALL_FULL)
                .build();
        this.session = engine.getSession("demo");
    }

    /** 只为下方示例注册命令而暴露；真实宿主按自己的架构决定 */
    public HarnessEngine getEngine() { return engine; }

    public void send(String userInput) {
        // ---------- 第一段：一次同步调用，装配出「宿主该做什么」 ----------
        InputOutcome outcome = engine.handle(HarnessInput.of(session, userInput));

        // ---------- 第二段：按 kind 分派 ----------
        switch (outcome.getKind()) {
            case TASK:                       // 有可执行任务
                if (outcome.hasReply()) {
                    System.out.print(outcome.getCommandOutput());   // 命令回执先渲染
                }
                outcome.getRequest().stream()
                        .doOnNext(e -> { if (e.hasText()) System.out.print(e.getText()); })
                        .blockLast();                                // 订阅时机由宿主决定
                break;
            case REPLY:                      // 只有回执（命令已执行，或命令在本渠道不可用）
                System.out.print(outcome.getCommandOutput());
                break;
            case HITL:                       // 决策已写入但未续流（过期卡 / 本批尚有未决卡）
                System.out.println("[未续流] 生效代理=" + outcome.getAgentName()
                        + " , 已生效=" + outcome.isHitlApplied()
                        + " , 剩余待审=" + outcome.getPendingHitlCount());
                break;
            case DEFERRED:                   // 会话忙且装了 queue：已排队（本骨架同步阻塞，实际不会命中）
                System.out.println("[已排队] 第 " + outcome.getBacklogPosition() + " 位");
                break;
            case REJECTED:                   // 未受理：忙 / 待审批 / 非归属节点 / 缺作用域 ...
                System.out.println("[未受理] " + outcome.getRejectReason() + " " + outcome.getRejectDetail());
                break;
            case IGNORED:                    // 空输入
                break;
        }
    }
}
```

```java
MinimalHost host = new MinimalHost("/data/work");
host.send("看下当前项目结构");   // TASK    → 跑任务
host.send("/help");             // 裸引擎未注册命令 → 按普通消息处理，也是 TASK
host.send("");                  // IGNORED
```

**为什么骨架里写了 `REPLY` 分支，上面三行却没有一行命中它？** 因为裸引擎没有注册任何命令（§4.1）。一旦注册，同一个 `/help` 就会走到那个分支：

```java
host.getEngine().getCommandRegistry().register(new HelpCommand());
host.send("/help");   // REPLY → 渲染回执后收尾
```

**注册命令并不需要配 `commandContextFactory`**——这一点是本节曾被误读的地方。引擎在 `dispatchCommand` 里就会兜底创建 `BufferedCommandContextFactory`（`HarnessEngine` 内），回执写入内存、由 `getCommandOutput()` 一次取回；骨架里 `System.out.print(outcome.getCommandOutput())` 的写法**已经**是这个默认工厂的正确消费方式。

只有在需要改动「回执怎么落地」时才提供工厂——CLI 要实时写终端并读键盘（`/help` 分页、`/model` 选择器），这时才用 `commandContextFactory(...)`（§4.4）。因此最小骨架刻意不配它：多配一个默认值的等价物，只会让读者以为「不配就不工作」。

一个值得提前知道的副作用：默认 `terminal=false`，所以 `/help` 会裁掉所有 `terminalOnly()` 命令（如 `/exit`）。
若你的宿主就是命令行程序，用 `.terminal(true)` 打开即可（§4.4）。

再看 `TASK` 分支里的 `hasReply()`：命令触发的任务（如 `/queue` 排队后立刻开跑）回执与任务并存，那份回执同样出自默认工厂。

> `CODING` 模式下 `build()` 只硬校验 `sessionProvider`。「`chatModel is required`」是**首次 `getMainAgent()`** 时由 `ReActAgentConfig` 抛出——所以 `modelAdd` / `defaultModel` 缺失不会在 `build()` 报错，而是在第一次 `handle` 报错（`SERVER` 模式在构建期就报）。

### 3.2 完整宿主：六态都处理到位

补上命令回执、附件、子代理、事件渲染与 HITL 续流，就是一个能直接接产品外壳的宿主。

```java
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLAction;
import org.noear.solon.ai.agent.react.intercept.HITLTask;

public class FullHost {
    private final HarnessEngine engine;
    private final AgentSession session;

    public FullHost(String workspace) {
        this.engine = HarnessEngine.of(workspace, ".solon/")
                .sessionProvider((id, create) -> InMemoryAgentSession.of(id))
                .modelAdd(new ChatConfig().then(cfg -> {
                    cfg.setName("deepseek");
                    cfg.setApiUrl("https://api.deepseek.com");
                    cfg.setApiKey(System.getenv("DEEPSEEK_API_KEY"));
                    cfg.setModel("deepseek-chat");
                }))
                .defaultModel("deepseek")
                .toolsAdd(ToolName.TOOL_ALL_FULL)      // 含 hitl 才有审批事件
                .hitlEnabled(true)
                .build();
        this.session = engine.getSession("demo");

        // 命令必须显式注册（§4.1），不注册则 /help 仍按普通消息处理
        engine.getCommandRegistry().register(new HelpCommand());
        engine.getCommandRegistry().register(new ClearCommand());
        engine.getCommandRegistry().register(new ModelCommand());
    }

    /** 一次完整输入：附件由宿主先落盘，再交给引擎装配 */
    public void send(String text, InputAttachments attachments, String selectedAgent) {
        // ===== 第一段 =====
        HarnessInput input = HarnessInput.of(session, text)
                .attachments(attachments)      // 多媒体块 + 文件引用
                .selectedAgent(selectedAgent)   // 选择器兜底；正文里的 @xxx 优先
                .trimContent(true)
                // 本宿主是命令行程序：true 让 /help 不要裁掉 terminalOnly 命令，/exit 也不被拦截
                .terminal(true);

        InputOutcome outcome = engine.handle(input);

        // ===== 第二段 =====
        switch (outcome.getKind()) {
            case TASK:     onTask(outcome);   break;
            case REPLY:    onReply(outcome);  break;
            case HITL:     onHitl(outcome);   break;
            case DEFERRED: onText("\n[已排队] 第 " + outcome.getBacklogPosition() + " 位"); break;
            case REJECTED: onText("\n[未受理] " + outcome.getRejectDetail()); break;
            case IGNORED:  break;
        }
    }

    private void onTask(InputOutcome outcome) {
        // 命令触发的任务：回执 + 任务并存，谁先谁后由宿主定（这里选「先回执」）
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
        // 涵盖「命令已执行」与「命令在本渠道不可用」两种事实，宿主动作相同
        if (outcome.hasReply()) {
            onText(outcome.getCommandOutput());
        }
    }

    /** HITL 决策已写入会话：按事实决定后续动作（不只看 kind） */
    private void onHitl(InputOutcome outcome) {
        if (outcome.isHitlApplied() == Boolean.FALSE) {
            onText("\n[审批卡已失效]");      // 过期卡：切勿续流，否则会把上一轮问题重跑一遍
            return;
        }

        // 本批已齐备但未传 resume：此时自行续流；传了 resume 则本方法不会被调到（引擎已返回 TASK）
        if (outcome.getPendingHitlCount() == 0) {
            resume(outcome.getAgentName(), outcome.getModelName());
            return;
        }

        // 本批尚有未决卡：展示下一张（跳过已决策项），等用户继续点
        for (HITLTask t : HITL.getPendingTasks(session)) {
            if (HITL.getDecision(session, t) == null) {
                onText("\n[待审批] " + t.getToolName() + " " + t.getArgs());
                break;
            }
        }
    }

    /** 提交一条审批决策（线上字符串由这里解析为枚举，脏值直接报错而不是静默当拒绝）
     *
     * <p>一次调用完成「写决策 + 尝试续流」，宿主只 switch kind：
     * <ul>
     * <li>{@code TASK} —— 本批已齐备，引擎已装配好续流请求，直接执行；</li>
     * <li>{@code HITL} —— 未续流（过期卡，或本批尚有未决卡），交给 onHitl 读事实处理。</li>
     * </ul>
     * <p>不需要自己判断「整批是否齐备」：引擎看得到挂起任务与决策状态，比宿主自己数更准。
     */
    public void decide(String callUuid, String action, String note) {
        InputOutcome outcome = engine.handle(HarnessInput.of(session, null)  // 审批通常不带文本
                .hitl(HITLAction.of(action), callUuid)  // 宿主边界：协议字符串 → 枚举
                .hitlOutput(note)             // 可选：改写工具返回值
                .resume(true));               // 齐备则续流，不齐备自动降为 HITL

        if (outcome.getKind() == InputOutcome.Kind.TASK) {
            markBusy(true);
            try {
                outcome.getRequest().stream()
                        .doOnNext(this::render)
                        .blockLast();
            } finally {
                markBusy(false);
            }
        } else {
            onHitl(outcome);
        }
    }

    /** 续流：不产生新用户输入，按挂起的 tool_calls 继续跑
     *
     * <p>只在「没传 resume 却已齐备」的写法下需要；采用 {@link #decide} 的一次调用形态后，
     * 引擎会直接返回 TASK，此处不再被调到。
     */
    private void resume(String agentName, String modelName) {
        engine.handle(HarnessInput.of(session, null)
                        .resume(true)
                        .selectedAgent(agentName)   // ★ 必须是 HITL 生效代理，不能换回主代理
                        .selectedModel(modelName))
                .getRequest().stream()
                .doOnNext(this::render)
                .blockLast();
    }

    /** 事件渲染：只演示最常用的几类，其余可按需追加 */
    private void render(AgentEvent e) {
        if (e instanceof ReasonDeltaEvent) {
            ReasonDeltaEvent d = (ReasonDeltaEvent) e;
            if (d.isThinking()) {
                onText("\n[思考] ");
            } else {
                onText(d.getText());
            }
        } else if (e instanceof ToolCallStartEvent) {
            onText("\n[调用工具] " + ((ToolCallStartEvent) e).getToolName());
        } else if (e instanceof HITLPendingEvent) {
            ((HITLPendingEvent) e).getPendingTasks()
                    .forEach(t -> onText("\n[待审批] " + t.getToolName()));
        } else if (e instanceof RunEndEvent) {
            onText("\n" + ((RunEndEvent) e).getText());     // 最终答复
        }
    }

    private void onText(String s)      { System.out.print(s); }
    private void markBusy(boolean b)   { /* 宿主自管 */ }
}
```

### 3.3 事件类型一览

`getRequest().stream()` 推的是同一套 `AgentEvent`，按需 `instanceof` 分流即可：

| 事件类 | 何时推 | 可取 |
|---|---|---|
| `ReasonStartEvent` / `ReasonEndEvent` | 一轮推理开始 / 结束 | — |
| `ReasonDeltaEvent` | 推理增量（逐字） | `getText()`、`isThinking()` |
| `ActionStartEvent` / `ActionEndEvent` | 一批工具调用开始 / 结束 | `getToolCalls()` |
| `ToolCallStartEvent` / `ToolCallEndEvent` | 单个工具调用开始 / 结束 | `getToolName()` |
| `PlanEvent` | 计划阶段 | 计划内容 |
| `RunStartEvent` / `RunEndEvent` | 整轮运行开始 / 结束 | `RunEndEvent.getText()` 为最终答复，`getMetrics()`、`getLlmErrorType()` |
| `HITLPendingEvent` | 拦截到待审批工具（需 HITL 三条件齐备） | `getPendingTasks()` → `HITLTask.getCallUuid()/getToolName()/getArgs()` |
| `LlmErrorEvent` | 模型调用出错 | 错误分类 |
| `ContextSizeEvent` | 触发历史压缩 | 压缩前后规模 |

### 3.4 两段之间能插什么：三端时序对照

这是「引擎不替宿主开流」的真正原因——同一份 `InputOutcome`，四端在两段之间要做的事完全不同：

| 端 | 两段之间必须做的事 | 跳过会怎样 |
|---|---|---|
| CLI | 回执直接写终端；不预注册订阅 | 回执重复打印 |
| Web | 先复位前端流门、注册 `Disposable` | 回执被前端丢弃；忙碌判定出现空闲窗口、同会话开出第二条流 |
| 桌面 | `streamHub.begin` 拿序号（供回放）；重挂 HITL 拦截器 | 事件序号错乱；续流时无拦截器 |
| ACP | 包一层 `takeWhile(ctx::isCancelled)` | 取消请求不生效 |

### 3.5 HITL 续流的四条不变量

1. **决策与续流合并为一次 `handle`**——三端统一写法：`.hitl(action, callId).resume(true)`，一次调用后只 `switch (kind)`：`TASK` 即已续流，`HITL` 则读事实处理。**不要自己分两次**（先 `.hitl(...)` 再 `.resume(true)`）：那个序列需宿主自行判断整批是否齐备，而引擎已在写入决策后准确计数。两个一次调用的典型错法：不传 `resume` 导致永远不续流（任务卡死）、或在批未齐备时就调 `.resume(true)` 而不用引擎事实（会带走未决任务）。
2. **续流要 `.resume(true)` 且不传正文**——传了也会被忽略（见 `InputHandleTest.resumeIgnoresText`）。
3. **代理名必须沿用 `outcome.getAgentName()`**——它是「本轮生效代理」，由显式入参优先、会话级子代理选择（`engine.getSessionAgent`）兜底解析。trace 按代理名分 key，换回主代理会取不到挂起的 `tool_calls`，表现为续跑空转。
4. **续流时机由引擎判定，宿主只管事实**——不要用 `HITL.getPendingTasks(session)` 自己数整批是否齐备（该集合含已决策项，`isEmpty()` 在刚写入首张决策后仍为 false，用它判定会把已经齐备的批永远卡住）；该集合只用于「展示下一张未决卡」。齐备判定统一读 `outcome.getPendingHitlCount()`。

### 3.6 受理控制：三个宿主常写错的地方已收归引擎

`handle` 返回后到真正接流之间有一段「已受理、尚未登记运行流」的窗口。并发请求若各自看到空闲，会同时开流、共用一个 `ReActTrace`，表现为「刚发出就秒结束」或「两条流互相覆盖」。这段窗口、HITL 挂起期的受理判定、以及「忙态放行判定」，都已收进唯一执行入口 `engine.handle(HarnessInput)` 的第 0 步：判定与占位、入积压在同一把会话锁内完成，宿主不再接触占位凭据。

**① 受理判定与占位：`handle` 第 0 步**

```java
InputOutcome outcome = engine.handle(HarnessInput.of(session, text)
        .origin(origin)
        .attr("reasoningEffort", effort));      // 宿主域属性：引擎不解读，进积压时原样保存
switch (outcome.getKind()) {
    case TASK:     run(outcome); break;            // 占位由请求自己释放；开流前失败调 outcome.discard()
    case REPLY:    reply(origin, outcome.getCommandOutput()); drain(session); break;
    case DEFERRED: reply(origin, "已排队（第 " + outcome.getBacklogPosition() + " 位）"); drain(session); break;
    case REJECTED: reply(origin, outcome.getRejectDetail()); break;
    case HITL:
    case IGNORED:  drain(session); break;
}
```

放行条件从输入本身推导，宿主不传谓词：

| 会话状态 | 输入 | 结局 |
|---|---|---|
| 空闲 | 任意 | 照常分派（`TASK` / `REPLY` / `HITL` / `IGNORED`） |
| 忙（有运行流 / 正在受理另一条输入 / 积压里还有待执行输入） | 忙时可执行命令（`runnableWhenBusy`；`terminalOnly` 的命令仅在 `input.terminal(true)` 时算）、审批决策 | 在运行任务旁边执行，`outcome.isBesideRunning() == true`，宿主不要改写回复路由 |
| 忙 | 其余可排队输入 | 引擎调用 `InputBacklog.offer(session, input)` 入积压，返回 `DEFERRED`（`getBacklogPosition()` 从 1 计） |
| 忙 | 程序轮次 `HarnessInput.ofProgram(...)`（`isProgram()`）、续流、没装积压能力 | `REJECTED`（`RejectReason.BUSY`，线上值 `busy`），不进积压 |
| 忙 | 带附件 | `REJECTED`（`RejectReason.ATTACHMENTS_NOT_DEFERRABLE`，`attachments_not_deferrable`） |
| 忙 | 积压拒收（满 / 超长 / 停用） | `REJECTED`（`RejectReason.BACKLOG_UNAVAILABLE`，`backlog_unavailable`） |
| 忙 | 显式作用域含 `chatModel` 或自定义 `attrs`（无法随积压保存） | `REJECTED`（`RejectReason.SCOPE_NOT_DEFERRABLE`，`scope_not_deferrable`），不占名额 |

- **占位跟着请求走**：返回 `TASK` 时会话保持占用（`engine.status(session).isAdmitting()` 为真），直到 `outcome.getRequest().stream()` 被订阅（订阅时运行流先登记、占位后释放，无空档——宿主把订阅调度到别的线程也安全）或 `call()` 结束；
- 宿主拿到 `TASK` 决定不执行（开流前出错、本轮已被中断等）时调用 `outcome.discard()`（幂等）；
- 兜底：`InterruptMode.INTERRUPT` / `prepareInterrupt` 会清除占位；占位有时限 `HarnessEngine.Builder#admissionTimeoutMs(Long)`（缺省 60000 毫秒），超时的占位不再算占用；
- 非 `TASK` 结局在 `handle` 返回前已释放占位；
- **程序派发线程上的一轮**（队列续发：在领取回调里同线程 `engine.handle(claim.getItem().toInput(session))`）由引擎识别（`outcome.isDispatched()` / `getDispatch()`，宿主取 `getDispatch().getOrigin()` 作回复路由），免于「有积压就延后」；其 `TASK` 同样占位，派发方据此用「返回时会话是否忙」判断已异步开流；
- 命令内部再次调用 `handle`（命令声明任务意图的二段装配）继承外层占位，不再判定；
- 扩展钩子 `onInput` 在受理判定通过之后才触发：被排队 / 拒绝的输入不触发，排队的输入续发时触发一次。

**② HITL 挂起时不开新回合（引擎已拦）**

若在 `pendingHitl > 0` 时把新输入当成**一条全新的普通回合**，新的运行流会与旧的未决 trace 并存，用户随后点「同意」时 `resume(true)` 若被运行态认领拒绝，表现为「审批点了没反应」，若没被拒绝则是两条流交叉写快照。因此 `handle` 第 0 步对普通输入直接返回 `REJECTED`（`RejectReason.HITL_PENDING`，线上值 `hitl_pending`），不入积压——入积压是更差的选择：审批可能永远不处理（用户走了），输入会堆积在审批之后，恢复时一次性执行一批过期指令。宿主只需把 `getRejectDetail()` 连同 `HitlCard.next(session)` 提示给用户。

引擎按输入本身豁免以下几类（缺一个就会把审批本身也拒掉）：

| 输入 | 为何豁免 |
|---|---|
| 审批决策（`input.hitl(...)`） | 它本身就是来解开挂起的，拒掉就死锁 |
| `resume(true)` 续流 | 同上，只是不带新文本的续跑 |
| 忙时可执行命令（`runnableWhenBusy`） | `/interrupt` 这类命令必须能随时放行 |
| 程序派发线程上的一轮 | 派发的是审批前就已入队的任务，不豁免则队列在审批期间凝固 |

口径提醒：`isBusy()` 只表示「有活跃流」，**没有**HITL。所以用 `isBusy` 做受理判定一定会漏；三者要分清：

| 判定 | 含义 | 用在哪 |
|---|---|---|
| `isBusy()` | 有活跃流 | 流生命周期、事件编排 |
| `isOccupied()` | 不应开新回合（含 HITL 挂起、受理窗口） | **受理判定** |
| `hasPendingHitl()` | 有待决策的工具调用 | 提示文案、前端状态 |

**③ 忙态放行：`isRunnableWhenBusy`**

```java
// 不要自己「解析命令名 → 查注册表 → 读 terminalOnly/runnableWhenBusy」
if (engine.isRunnableWhenBusy(HarnessInput.of(session, input))) {
    // 任务运行中仍然受理（/interrupt 等声明了 runnableWhenBusy 的命令）
}

// 需要拿到命令对象本身时
Command cmd = engine.resolveCommand(input);   // 未以命令前缀开头 / 只有前缀 / 未注册 → null
```

三处口径任一处漂移（前缀、大小写、参数切分、启用状态），忙态旁路就会**静默失效**——不报错，只是命令没反应。判定收归引擎后宿主只有一个布尔。注意它只回答「忙态是否放行」，不回答「空闲时能否启动任务」（如 `/steer`）。

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
回执写内存、从 `getCommandOutput()` 一次取回——§3.1 的最小骨架就是靠这个默认值跑通 `/help` 的。
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

## 五、完整示例：goal / queue / steer（+ 中断干预）

这三个命令与前面 `help` / `clear` / `model` 不同：它们不是「打印一段文本就结束」，而是**跨轮维护会话级状态**。命令本身只改状态、只声明意图，真正的执行与投递由宿主装配的领域组件承担——因此每个命令都必须补上对应的宿主侧接线，否则命令会「有回执、无效果」。

| 命令 | 维护的状态 | 宿主必须额外装配 | 缺了会怎样 |
|---|---|---|---|
| `/goal` | 会话级目标（每会话一个） | `GoalExtension.builder()` + `addExtension` | 命令能创建目标，但永远不跑第二轮 |
| `/queue` | 会话级排队队列 | `QueueExtension` + `addExtension`，任务终态调 `queue.drainLoop(...)` / `claimNext(...)` | 命令能入队，但队列永远不动 |
| `/steer` | 当前任务的插话邮箱 | `SteerExtension` + `addExtension`（拦截器由扩展挂载） | 命令能入队，但永不注入 |
| `/interrupt` | 无状态（只停） | 无（现成的 `engine.interrupt(session, InterruptMode.INTERRUPT)` 即可） | 不装命令就只能由宿主自带停止按钮 |
| `/loop` | 会话定时任务（间隔 / cron，可多条） | `LoopExtension.builder()` + `addExtensionIfAbsent`，宿主按通道注册执行器 | 任务能建、到点跳过（无认领只记日志，绝不记成成功） |

**三个能力，三个类**：每个命令从装配到运行期操作宿主都只接触一个 Extension 实例（宿主侧与 harness 的交互面收敛到一个类），宿主不接触 goal 运行态、调度器、命令类，也不接触 queue 的存储实现。命令本身的语法与回执未变，终端用户无感知。

**会话能力是独立插件模块（4.2.0+）**：`solon-ai-harness-goal` / `solon-ai-harness-queue` / `solon-ai-harness-steer` / `solon-ai-harness-loop`，按需引入（或用聚合包 `solon-ai-harness` 一次引齐，见 §1.1；引入不等于装配）。核心模块不认识它们，它们之间也互不依赖，只经核心契约协作（见 `HarnessExtension` 类注释「插件化协作规则」）：

| 契约 | 类型 | 提供方 | 消费方 |
|---|---|---|---|
| `HarnessExtension#onSessionInterrupt` | 钩子（广播） | 引擎 `interrupt` / `prepareInterrupt` 先于停流回调 | queue 清队列 |
| `HarnessExtension#onSessionRemove` | 钩子（广播） | 引擎 `removeSession`（宿主永久删除会话时，先于删存储调用） | loop 删该会话任务、goal 注销该会话 job |
| `HarnessExtension#contributeSessionStatus` | 钩子（汇集） | 引擎 `status` 读快照时回调 | 各插件贡献**自己定义**的键：queue `QueueExtension.STATUS_PENDING`、steer `SteerExtension.STATUS_PENDING`、goal `GoalExtension.STATUS_GOAL`、loop `LoopExtension.STATUS_TASKS`（只供展示/宿主业务，内核不读） |
| `spi.InputBacklog` | 能力接口（`offer(session, HarnessInput)` / `hasPending`） | queue | 引擎 `handle` 受理判定（有无积压能力、忙时 `offer` 入积压、`hasPending` 保先进先出）、`engine.isIdle`、steer 残留转积压（`getExtension(InputBacklog.class)`） |
| `spi.RunDispatch` | 会话槽位（`session.run.dispatch`） | queue 派发 | 引擎 `handle` 受理判定（识别派发轮次）、`RunTicket` |
| `HarnessExtension#isQuiescent` | 钩子（汇集） | goal（有活跃 goal）、loop（有启用任务） | `engine.isQuiescent()` → `HarnessEnginePool` 空闲回收判定 |
| `HarnessExtension#getToolNames` | 声明 | goal、loop（取自各自 Talent 的工具表） | 作用域守卫：已声明的工具不受 `InputScope.tools` 名单约束 |

**内核没有 queue**：`SessionStatus` 不含排队数，`InputOutcome` 的延后结局叫 `Kind.DEFERRED`（不是 QUEUE），程序派发判定叫 `isDispatched()`。入积压由引擎经 `InputBacklog#offer(session, HarnessInput)` 完成，宿主域属性（`HarnessInput#attr`）由实现方原样保存。内核自身依赖积压的判定（受理先进先出、空闲判定）走能力方法 `InputBacklog#hasPending`——由类型系统保证实现方提供，而不是「必须贡献某个状态键」的约定。goal 调度与宿主定时任务判断「能否注入」统一用 `engine.isIdle(session)`（不占用且无积压，同一把会话锁内读取）。

没装 queue 时：`handle` 忙态返回 `REJECTED("busy")`（无处可延）；steer 残留按转排队失败处理（暂存并推 `SteerDroppedEvent`）。

**会话状态是开放的**：`SessionStatus` 只有核心事实是固定字段（运行流 / HITL / 受理窗口 / 取消代数），插件事实经 `contributeSessionStatus` 贡献到类型化键 `SessionStatus.Key<T>`（`Key.count` 求和、`Key.of` 后写覆盖），新增插件事实不改核心。宿主一律 `engine.status(session)` 读全；静态 `SessionStatus.of(session)` 只有核心事实。

```java
SessionStatus st = engine.status(session);
int queued  = st.get(QueueExtension.STATUS_PENDING);        // 待执行排队数（没装 queue 为 0）
int steers  = st.get(SteerExtension.STATUS_PENDING);        // 待生效插话数
GoalSnapshot.Status goal = st.get(GoalExtension.STATUS_GOAL);  // 无 goal 为 null
```

**会话属性键各自登记**：核心 `HarnessAttrs` 只放引擎键与核心槽位；插件键在插件模块内——`QueueAttrs`（`session.queue.*`）、`GoalAttrs`（`session.goal.*`）、`SteerAttrs`（`session.steer`）。三者都是包内可见类（4.2.0 起），宿主无法也不应读写（`HarnessStructureGuardTest` 与 soloncode 的 `PackageBoundaryTest` 守卫）。

**推荐：构建期用 `Builder.extensionAdd` 装配**（CODING / SERVER 通用，SERVER 下 build 之后不能再挂扩展）。插件就是普通扩展，
不要哪个删哪一行，宿主自己的扩展也是同一写法：

```java
GoalExtension goal = GoalExtension.builder().roundConcurrency(2).build();
HarnessEngine engine = HarnessEngine.of(ws, ".solon/").stateSource(stateSrc) /* ... */
        .extensionAdd(QueueExtension.builder().maxSize(20).build())
        .extensionAdd(new SteerExtension())
        .extensionAdd(goal)                                                    // 按需
        .extensionAdd(LoopExtension.builder().store("loop").quota(sid -> 20).build())  // 按需；挂接时取引擎状态来源
        .build();
goal.addExecutor("web", id -> id.startsWith("web-"), executor);   // 可选：按通道认领
goal.recover();                                                   // 重启恢复（用 HarnessHost 时由骨架代办）
```

同一实例重复 `extensionAdd` / `addExtension` 只算一次（按引用查重，不会二次 `onAttach`）；同类的另一个实例照常挂载。

运行期装配（仅 `runtimeMutable` 时，即 CODING 缺省）用 `addExtensionIfAbsent`（按类型幂等，返回实际生效的实例）：

```java
GoalExtension goal = engine.addExtensionIfAbsent(
        GoalExtension.builder().build()); // /goal（执行器挂接后 addExecutor）
QueueExtension queue = engine.addExtensionIfAbsent(new QueueExtension()); // /queue
SteerExtension steer = engine.addExtensionIfAbsent(new SteerExtension()); // /steer
LoopExtension loop = engine.addExtensionIfAbsent(LoopExtension.builder()  // /loop
        .store(engine.getStateSource(), "loop")          // 按会话分存：<dir>/<encode(sid)>.json；"loop" 在缺省受保护名单内；缺省 = 只在内存
        .maxTasks(200)                                   // 本实例任务总量上限（跨会话）；按会话配额用 .quota(sid -> 20)
        .sessionAlive(id -> sessionExists(id))           // 加载时孤儿清理；缺省恒 true
        .maxErrors(() -> 3)                              // 任务级连续失败阈值（实时读）
        .build());
// 执行器只有这一种注册方式；返回三态结论（不注册则走缺省执行器 engine.handle(...).call()）
loop.addExecutor("web", id -> id.startsWith("web-"), (sid, prompt) ->
        runtimeReady() ? LoopOutcome.done(run(sid, prompt))      // 抛异常 = 失败（计错误、退避、达阈值停用）
                       : LoopOutcome.skip("runtime not ready")); // 跳过：不计错误

// 宿主增删改查：请求参数原样传入（null = 未设置 / 保持原值），校验由插件统一完成（IllegalArgumentException）
LoopTask t = loop.schedule(sid, LoopSpec.of(prompt).everyMinutes(interval).cron(cron).runNow(runNow));
loop.schedule(sid, LoopSpec.of("remind").at(LocalDateTime.of(2026, 9, 5, 18, 0)));  // 一次性
loop.update(sid, t.getId(), LoopPatch.create().prompt(newPrompt).cron(""));     // cron 空串 = 回到固定间隔
loop.pause(sid, t.getId()); loop.resume(sid, t.getId());
Map<String, Object> view = t.toMap();                                            // 标准展示视图
```

loop（会话定时任务）与 goal（会话目标）的区别：goal 每会话一个、由状态驱动续轮、有预算与完成判定；loop 可多条、按时间表独立触发、只记最终文本。loop 的开关（`setEnabled`）只关模型工具 `loop_*`，`/loop` 命令与已有定时任务照常；会话任务文件里不认识的条目与任务上的宿主扩展字段（`LoopSpec.extra`/`LoopPatch.extra` 写、`getExtra` 读）一律原样写回（旧版单文件 `tasks.json` 用 `store(source, "loop", "tasks.json")` 首次加载时拆分迁移）；文件读不懂时先备份为 `*.corrupt-<时间戳>`，读不出来时本进程不写盘；扩展摘除后不再写盘。`LoopTask` 对宿主完全只读（无公开构造器与修改方法），改定义走 `loop.update(sid, id, LoopPatch)`：新版本与在途一轮共享运行锁，不会并发。会话忙由插件在调用执行器前统一判定（`engine.isIdle`），执行器只管本通道此刻能不能跑（如 CLI 终端是否空闲→`skip`）；执行器返回 null 按失败计。`hasActiveTasks()` 只算启用任务（只剩暂停任务的工作区可闲置回收）。未显式 `jobManager(null)` 却取不到 JobManager 时会告警（任务只存不跑）。

三者共同点：`/queue` 与 `/steer` 的 `runnableWhenBusy()` 返回 `true`（`handle` 第 0 步据此让它们在任务执行中旁路执行）；`/goal`、`/loop` **不覆写**、保持默认 `false`，忙时会进积压（`DEFERRED`）或被拒绝。`/queue`、`/steer` 从 `Command.execute(engine, ctx, origin)` 的第三参拿到 `InputOrigin` 并带到投递链路，三者都**不自己开流**。

**同一个动作，三条到达路径**：命令不是另一套实现，它是「文本入口」。同一能力既可以由文本进来（终端用户敲、IM 渠道转发），也可以由代码直接进门面；中间还有一条「代码把文本交给引擎」。以 `/queue` 为例三者并排：

```java
// ① 文本入口：终端用户敲的那一条（IM 转发也是它）
InputOutcome o = engine.handle(HarnessInput.of(session, "/queue 顺便更新 README")
        .origin(InputOrigin.channel("wechat")));   // Kind.REPLY，回执在 o.getCommandOutput()

// ② 同一段文本，由代码交给引擎：前缀识别、命令切分、回执文案全由引擎与命令产出
InputOutcome o2 = engine.handle(HarnessInput.of(session, "/queue 顺便更新 README"));
// 与 ① 唯一差别：origin 未给（回执归由宿主自己路由）

// ③ 直接调门面：跳过文本，宿主自己编排（命令内部调的就是它）
HarnessResult<Integer> pos = queue.enqueue(session, "顺便更新 README");  // OK 时值为队列位置（从 1 计）
```

①② 是文本路径，③ 是对象路径，**动作等价但返回物性质不同**：前两者给你一段供人读的回执，后者给你一个可判定的值。对照如下：

| 维度 | ① / ② `engine.handle(HarnessInput.of(session, "/queue …"))` | ③ `queue.enqueue(session, text)` |
|---|---|---|
| 参数由谁解析 | 引擎（命令前缀 + `ParsedCommand` 切分） | 宿主（直接给强类型值） |
| 返回值 | `InputOutcome`：`Kind.REPLY` + `getCommandOutput()` | 所有插件门面统一返回 `HarnessResult<T>`（steer 为 `HarnessResult<SteerReceipt>`） |
| 失败如何得知 | 回执文本里（人读：「排队失败（队列已满，上限 20 条）」） | 结果码（程序判定：粗码 `FULL` / `INVALID` / `CONFLICT` / `NOT_FOUND` ……，必要时再看细分码 `getReason()`） |
| 回执文案 | 命令内置，可直接渲染 | **没有**，要展示得宿主自己写（`getDetail()` 可作开发期提示） |
| 停用拦截点 | `CommandDispatcher` 判 `isEnabled()` → 直接回 `REPLY` | 门面内 `isEnabled() == false` → `DISABLED` |
| 来源身份 | `HarnessInput.origin(...)`，命令再按渠道兜底 | 最简重载给缺省值（`queue` 补 `channel("queue")`、`steer` 用 `NONE`、`goal` 不带） |
| 忙碌放行 | `handle` 第 0 步按 `Command#runnableWhenBusy()` 自动放行或入积压 | 门面不经受理：`enqueue` 忙闲都可调，`steer` 自算忙态 |
| 会话归属 | 受 `SessionOwnership` 约束（非持有节点 `REJECTED(not_owner)`） | 写门面同样先查归属：goal / loop / queue / steer 返回 `HarnessResult` 的 `NOT_OWNER`（`getOwner()` 为持有者）、`interrupt` 返回 false（`prepareInterrupt` 的 `isNotOwner()` 可区分） |
| 作用域 | 受 `InputScope` 约束 | **不受约束**，多租户管理接口由宿主先鉴权（见 [SAAS.md](SAAS.md) §6） |
| 需要 `.terminal(true)` 吗 | 不需要（这四个命令都不是 `terminalOnly`） | 不适用 |

四个能力的最简门面形态（① 内部最终都落到这里，与命令走同一条写路径；§5.4 还给出了“运行中干预”与“停下来重排”两个互斥场景）：

```java
goal.create(sessionId, GoalSpec.of("让测试全绿"));   // HarnessResult<GoalSnapshot>

queue.enqueue(session, "顺便更新 README");             // HarnessResult<Integer>：OK 时值为队列位置

HarnessResult<SteerReceipt> r = steer.steer(session, "改用 PostgreSQL 方言");  // 2 参

engine.interrupt(session, InterruptMode.INTERRUPT);  // HarnessResult<Boolean>：值 true = 确实停了
```

**同源是可验证的**，不是口头承诺——三条命令的实现体就是这三个方法，文档里不必写两套语义：

| 命令 | 命令内部的实际调用 |
|---|---|
| `/goal <obj>` | 解析 flag 得到 `GoalSpec` 后 `goal.create(session, spec)`（`GoalCommand` 注释明写「命令行与编程调用的唯一汇合点」） |
| `/queue <msg>` | `QueueExtension.enqueue(session, text, resolved)` |
 | `/steer <msg>` | `steer.steer(session, text, origin)`（命令经门面投递，开关、忙判定与容量上限都与门面同源） |

三条由此推出的边界：

- **这三个命令的 `Kind` 恒为 `REPLY`**：它们只改会话状态、不声明 Agent 任务意图，所以命令路径下不会出现 `TASK`。`/goal` 虽然会自己起持续轮次，但那是调度器驱动的、不在本次 `handle` 的返回里——别等 `getRequest()`。
- **忙碌放行只在文本路径上自动发生**：①② 经 `handle` 第 0 步，`runnableWhenBusy` 的命令忙时旁路执行（`isBesideRunning()`），其余输入入积压或拒绝（§3.6）；③ 是门面直调，不经受理判定，也不经作用域（会话归属仍会检查）。
- **不要两条路都走**：宿主若已用门面完成了动作，就不要再把同样的文本拼成 `/queue …` 交给 `handle`，那会入队两次（命令路径没有幂等保护，`id` 去重只对带 `id` 的 `enqueue` 生效）。

除了 `create`（失败时抛 `IllegalStateException`），其余三个入口都**用返回值报告失败、不抛异常**，宿主应按返回值分流（命令路径的每种失败文案就是据此产生的）：

| 调用 | 失败形态 | 处理建议 |
|---|---|---|
| `enqueue` | 返回 `HarnessResult`：`FULL`（队列已满）/ `INVALID`（文本为空或超长）/ `CONFLICT`（id 重复）/ `DISABLED`（扩展已停用）/ `NOT_OWNER` | 提示用户稍后再试，或改为直接发送 |
| `steer` | 返回 `HarnessResult<SteerReceipt>`，`isOk() == false`，细分码见 §5.3 表格 | `hasReason(SteerExtension.REASON_NOT_RUNNING)` → 改走 queue；`hasReason(SteerExtension.REASON_BOX_FULL)` → 改走 queue 或直发 |
| `interrupt` | 返回 `false`：当前没有在跑的流 | 不写取消消息、不改快照；**但队列已被中断回调 `onSessionInterrupt` 清空**（见 §5.4 第 ① 步） |
| `create` | 抛 `IllegalStateException`：已有进行中 goal | 回 409，提示先 `/goal clear` |

**取消是「请求」，不是「处决」**：`interrupt` / `cancelAll` 的实现是向执行线程发中断（`Thread#interrupt`），而不是强制终止线程。因此——

- 任务卡在**可中断**的阻塞点（`sleep`、`wait`、`FileChannel` IO、多数网络 IO）时，取消会立即生效；
- 任务卡在**不可中断**的阻塞点（部分 socket 读、native 调用、`synchronized` 等锁）时，取消只会在该阻塞点返回后才被看到，期间任务仍在跑；
- 这是有意的取舍：任务可能持续数天，用超时强行切断会把「还在正常干活」误判成失败。**宁可让取消是「请求」，也不做成「处决」**。宿主若需要硬保证，应自己在具体的 IO 上设超时，而不是指望 `interrupt` 能穿透一切。

取消的终止信号是 `onComplete`（不是 `onError`）：框架把「用户主动停止」当成正常结束，宿主不得据此判定执行失败（`CliShell` 不打印 Error 段、headless 退出码为 0、Web/桌面不补发 error 帧）。同时框架会在收尾前**清除执行线程的中断位**——`boundedElastic` 会复用线程，残留的中断标志会让下一个不相干的任务在首个阻塞点随机抛 `InterruptedException`。

**运行期开关：统一走引擎，不要 `new` 一个关着的扩展**

三个扩展都继承 `AbsSwitchable`，`setEnabled(null)` 表示“不改变”。推荐入口是引擎级方法，它会**先传播给扩展的子组件（命令 / Talent / 拦截器），再重建主代理**（Talent 与拦截器是装配期下发的，不重建就不会生效）：

```java
engine.setExtensionEnabled(queue, false);   // 停用：命令 + enqueue/drain 一起失效
engine.setExtensionEnabled(steer, false);   // 停用：命令 + 拦截器 + steer() 返回 DISABLED
engine.setExtensionEnabled(goal,  false);   // 停用：命令 + Talent，并停掉所有 goal 调度
engine.setExtensionEnabled(goal,  true);    // 重新启用
```

三处容易误解的地方：

- **未加入引擎的扩展会先被加入**（再直接返回），所以 `setExtensionEnabled` 可以用于“先建后开”，但第一次调用不会立即应用 `enabled`——想控制初始状态请在 `addExtension` **之前**调 `ext.setEnabled(false)`；
- **命令停用不等于退化为普通输入**：`/queue` 会回 `REPLY`（`/queue 已停用。发送 /help 查看可用命令。`），且 `isCommand()` 仍为 `true`，宿主不必写额外分支；
- **“只保留门面、不注册命令”这一档目前不可达**：三个扩展的构造器/builder 都没有关闭命令注册的开关，`onAttach` 里一律 `register`（注册本身幂等，已有同名命令则跳过）。要让整个能力下线只有 `setExtensionEnabled(..., false)` 一条路，它是“命令 + 门面”一起关。

### 5.1 `/goal`：会话级有终止判据的持续执行

Goal 是「给它一个目标，它一轮轮自己跑，直到达成 / 预算耗尽 / 熔断」的能力。归属是**会话**，每会话最多一个，状态随会话快照持久化，重启后仍在。

#### 装配：一个类搞定

GoalExtension 已把运行态、调度器、命令、Talent 全部收进一次装配；执行器在挂接后按通道注册：

```java
GoalExtension goal = engine.addExtensionIfAbsent(GoalExtension.builder() // job 名前缀按实例自动生成
        .budget(70, 85)                                        // 可选，缺省 70/85
        .stagnation(3).maxErrors(3).validatorEnabled(true)      // 可选，均为缺省值；验证器在本轮会话工作目录（getSessionCwd）下执行
        .roundConcurrency(4)                                   // 可选，本扩展实例的 goal 轮次并发上限（缺省 4）
        .build());                                             // onAttach：运行态 → 调度 → /goal 命令 → Talent

// 谓词入参是 sessionId（下例按宿主自己的会话 ID 前缀分通道；SessionIdOwnership 是宿主自己的工具类）
goal.addExecutor("cli",     SessionIdOwnership::isCli,     cliRunner);
goal.addExecutor("web",     SessionIdOwnership::isWeb,     webRunner);
goal.addExecutor("desktop", SessionIdOwnership::isDesktop, desktopRunner);
```

要点：

- **不注册执行器也能跑**：此时走内置缺省执行器 `engine.handle(HarnessInput.ofProgram(session, prompt, "goal")).call()`（不识别命令与 @提及、来源标记为 goal）。它不带宿主的专属执行语义（Web 流式推送、CLI 终端），所以宿主一般仍应注册自己的通道。
- **执行器只有一种注册形态**：`addExecutor(channel, claim, executor)`，挂接后调用（之前调用抛异常）。谓词必须互斥；多个同时命中时取首个并告警。内置兜底单独登记，只在全部不认领时使用。
- **每个扩展实例只有一个节拍 job**（`goal-<实例序号>:tick`，1 秒一拍），到期会话派发到有界线程池（`roundConcurrency`）；第一个会话登记时注册、最后一个注销时移除，空闲引擎不占定时任务。实例序号保证多个引擎共用 JVM 单例 `JobManager` 不撞名。
- **`.jobManager(x)`**：不传 = 缺省取 `JobManager.getInstance()`；**显式传 null = 关闭调度**（纯 JUnit / 无容器宿主），此时 `hasScheduler()` 为 false；未显式关闭却取不到时 warn 一次。
- **没有“只关命令”的开关**：`GoalExtension` 的构造器私有，builder 也不提供 `commandEnabled`。注册本身是幂等的（已有同名命令则跳过），迁移期双写安全；要整体下线用 `engine.setExtensionEnabled(goal, false)`（连带停调度，见本章引言）。

#### 运行期操作：还是这一个类

每个会话永远只有一个 goal，门面一律只按 `sessionId` 定位（没有 `taskId` 参数），对外只交只读快照 `GoalSnapshot`。写操作返回 `HarnessResult`，业务失败不抛异常；门面只取已有会话，不存在时为 `NOT_FOUND`。

```java
HarnessResult<GoalSnapshot> t = goal.create(sid, GoalSpec.of(prompt).maxTokens(2000)); // 建 + 挂调度；已有非终态 goal → CONFLICT，规格非法 → INVALID
GoalSnapshot cur = goal.get(sid);                                     // 只读快照；无则 null
goal.update(sid, GoalPatch.create().objective(p).maxTokens(n), stopper);  // null 字段保持原值
goal.pause(sid); goal.resume(sid);                                    // NOT_FOUND = 无 goal；CONFLICT = 状态不允许
goal.trigger(sid);                                                    // 立即异步跑一轮（仅活跃态）
goal.remove(sid);                                                     // 先摘调度再清除
goal.recover();                                                       // 重启恢复：按状态来源 goal/active/ 索引挂调度、不立即跑
goal.recover(sessionIds, id -> readStatus(id));                       // 或：宿主自己枚举会话
goal.hasActiveGoals(); goal.hasScheduler();
Map<String, Object> row = cur.toMap();                                // 列表/徽章标准视图（type = "GOAL"；不含 loop 字段，混排时宿主自补）
```

- **update 内含停流时序**：goal 活跃时按「暂停并摘调度 → 停流 → 等旧轮次停稳 → 改写 → 恢复并立即跑一轮」执行；`stopper` 由宿主提供（渠道侧可能还要推终态帧、清积压），传 null 退回 `InterruptMode.CANCEL_STREAM`。旧轮次 2 秒内未停稳或 goal 已是终态时返回 `CONFLICT`（宿主回 409；未停稳时 goal 保持暂停，可重试）。不要把 `InterruptMode.INTERRUPT` 作为 `stopper`：那会清空用户的排队。
- **执行器**：`addExecutor(channel, claim, executor)`，必须在挂接后注册（挂接前调用直接抛异常）。谓词命中的首个执行器即本轮执行者，结论不穿透；结论用 `GoalOutcome`：`done(ReActResponse)`（推荐，工具证据与 token 由响应统一取口径）或 `done(text, hasToolCalls, tokens)` 记成功、`skip(reason)` 本轮放弃不计错误、`failed(msg)`/抛异常/返回 null 记失败（参与熔断）。没有「从文本推测工具证据」的入口。
- **调度配置**：`Builder.jobManager(IJobManager)`，缺省取 `JobManager.getInstance()`，显式传 null = 关闭调度；未显式关闭却取不到时 warn 一次。
- **开关**：`setEnabled(false)` 同时关闭模型工具、`/goal` 命令与全部调度。

#### 模型侧工具

`GoalTalent` 被挂到**主代理**上，提供两个工具（创建目标由用户命令或界面驱动，模型侧只读 + 更新）：

| 工具 | 作用 |
|---|---|
| `goal_get` | 读当前目标：状态、轮次、剩余轮次、耗时、Token 消耗与预算 |
| `goal_update(status)` | `complete` 标记完成（需过四道闸，见下）/ `blocked` 声明阻塞 |

`GoalTalent.isSupported()` 要求会话里已有目标，因此不设目标时这两个工具对模型不可见。

#### 命令用法

| 命令 | 行为 |
|---|---|
| `/goal` | 打印当前目标状态（状态 / 轮次 / Token / 耗时 / 上次结果）；无目标时打印用法 |
| `/goal <objective>` | 创建目标并立即调度首轮 |
| `/goal --max-tokens 2000 <objective>` | 带 Token 预算 |
| `/goal --max-duration 30m <objective>` | 带时间预算（单位 s / m / h，纯数字按分钟） |
| `/goal --max-iterations 10 <objective>` | 带轮次预算 |
| `/goal pause` \| `resume` \| `clear` | 暂停 / 恢复 / 清除（`remove` 为 `clear` 的别名） |
| `/goal -- <objective>` | 转义：目标正文以 `pause` 等子命令词或 `--` 开头时使用 |

Flag 写法是 `--key value`（空格分隔），旧写法 `--key:value` 仍可用；未知 flag 打印 `Unknown flag` 并跳过，缺值或数值非法直接 `Invalid --xxx value` 返回。子命令只在恰好一个参数时生效，`/goal pause the build` 会当作目标正文。三个预算默认都是 `0`，表示不限制。若正文为空，回执会同时打印 Usage 与一行最简示例。

> 命令行的 `--max-duration 30m` 在 `GoalCommand#parseSpec` 里就被换算成 `GoalSpec.maxDurationMs(1_800_000)`，之后一路是同一个 `GoalSpec` ——文本 flag 形态到此为止，不会再往下传。

已有进行中（非终态）的目标时创建会被拒绝：

```
WARN: 当前会话已有进行中的 Goal，请先清除后再创建
  Active: <当前目标>
  Use /goal clear first to replace the goal.
```

#### 运行机制

调度是**状态驱动**，不是时间表驱动：单节拍（1 秒）扫描到期会话，同一会话上一轮结束后间隔 5 秒再跑下一轮，每轮自判还要不要继续；线程池满额时本拍不再派发（背压）。`runNow = true` 时首轮立即到期，否则等一个间隔。

每轮 `runRound(session)` 的守卫顺序（任一条命中就跳过或收尾退出）：

1. 目标不存在或状态非活跃 → 退出并注销 job；
2. 没有 `TaskHandler` → 跳过（日志 `no task executor registered`）；
3. `engine.isIdle(session) == false`（占用或有积压）→ 跳过（不抢宿主正在跑的任务，也不插队；HITL 挂起算占用）；
4. 轮前预算守卫（时间 / Token / 轮次）→ 命中则先跑一轮「收尾总结」再标记终结态；
5. CAS 防重入（`session.goal.running`）。

轮内流程：`buildEffectivePrompt` → `execute(...)` 取 `RoundResult` → 累计 Token / 轮次 / 执行指纹（无进展检测）→ 完成判定复核 → `commit` 落盘。

> **只有一个驱动源**：错误后不另行补发，重试靠下一次到期。双驱动会叠加轮次。

#### 状态机

```
PURSUING ⇄ PAUSED
PURSUING → ACHIEVED | BUDGET_LIMITED | ITERATION_LIMITED   终态，不可恢复
PURSUING → BLOCKED
BLOCKED  --resume-->  PURSUING                              可恢复
```

`BLOCKED` 有两个来源：模型主动 `goal_update(blocked)`，或异常分级熔断（不可恢复错误如 SSL / HTTP_4XX 连续 2 次；同类型错误连续 3 次；连续错误达 `maxConsecutiveErrors`）。熔断后调度自动停，`/goal resume` 可续。

#### 完成判定的四道闸

`goal_update(complete)` 只是**声明**，不是判决。四条全部通过才真正转 `ACHIEVED`：

| # | 闸门 | 不满足时的错误码 |
|---|---|---|
| 1 | 模型调用 `goal_update(complete)` | — |
| 2 | **执行证据**：目标含创建/修改/运行/文件类关键词时，本轮必须有实际工具调用（`goal_get` / `goal_update` 不算证据） | `ACTION_EVIDENCE_REQUIRED` |
| 3 | **TODO 联动**：任务清单里没有 `- [ ]` 或 `- [/]` 残留项 | `TODO_UNFINISHED` |
| 4 | **验证器**：按目标条件匹配验证器并执行 | `VALIDATION_FAILED` |

`CODING` 模式下验证器默认注册两个（`SERVER` 模式缺省**不注册**内置验证器，且策略为 `FAIL_CLOSED`；用 `defaultValidators(boolean)` / `validatorPolicy(...)` / `validatorTimeoutMs(...)`（缺省 180 秒）调整，自定义验证器用 `registerValidator(matcher, validator)`），按目标文本（小写）匹配，多命中按 AND 链式生效：

| 验证器 | 匹配关键词 |
|---|---|
| `TestsPassValidator` | `test` / `pass` / `check` / `ensure` / `verify` |
| `BuildPassValidator` | `build` / `compile` / `implement` / `refactor` / `fix` / `create` / `add` / `write` |

无命中时用 `NoopValidator`（恒通过）。`validatorEnabled = false` 时第 4 道闸整体跳过。验证命令超时或执行失败时结果为「无法判定」（`INCONCLUSIVE`），按 `ValidatorPolicy` 收敛：`FAIL_OPEN` 放行、`FAIL_CLOSED` 拒绝。

#### 读取与监听

```java
GoalSnapshot t = goal.get(sessionId);                 // 只读快照；修改只能经门面
Map<String, Object> item = t.toMap();             // 与 loop 任务同构，type = "GOAL"；字段名与前端 goal.* 约定对齐

// 生命周期观察者：每次变更收到只读快照（removed = true 时为清除前的快照；isRunning() 为快照时刻是否在跑）
goal.addListener((sessionId, task, removed) -> { /* push to client */ });
```

#### 存储分层

与 queue 的 `QueueStore` 完全一致：**存储介质是会话实现的细节**（文件 / 内存 / Redis / JDBC），goal 运行态只依赖 `AgentSession`。

| 位置 | 内容 | 是否落盘 |
|---|---|---|
| `getContext().data()` 的 `session.goal` | Map 投影（单槽，一条记录） | 是，随 `updateSnapshot()` |
| `attrs()` 的 `session.goal.state` | 包内运行态实例（宿主不可见） | 否（瞬态，首次访问从投影 hydrate） |
| `attrs()` 的 `session.goal.running` | 轮次防重入标记 | 否 |

**投影读写一律走 `GoalProjection`，不要在宿主里碰键名。** 这张 Map 的字段名是持久化契约，
此前宿主为了做启动恢复与历史数据迁移，自己 `map.get("status")`、甚至手工拼一张字段齐全的 Map
写回去——等于把存储格式复制进了宿主，harness 改字段就静默失效：

```java
// 读：类型化，得到 Status 枚举而不是字符串
GoalSnapshot.Status status = GoalProjection.statusOf(context);
if (status != null && status.isActive()) { /* 恢复调度 */ }

// 或拿完整投影
GoalProjection p = GoalProjection.from(context);
if (p != null && p.isActive()) { /* ... p.getConsumedTokens() 等 */ }

// 写（如历史数据迁移）：字段集合由 harness 定义，调用方只填值
GoalProjection.builder()
        .goalId(id).condition(objective)
        .statusName(rawStatus)          // 非法名不会抛，getStatus() 返回 null
        .maxTokens(n).maxIterations(i)
        .build()
        .writeTo(context);
```

> 包内运行态的 `toMap/fromMap` 委托 `GoalProjection`，字段集不会漂移（`GoalProjectionTest` 锁定双向一致性）。`GoalProjection.from` 对「非 Map / 缺 status / 状态名非法」一律返回 `null`，不抛异常。

### 5.2 `/queue`：把消息排在当前任务之后

`/queue` 与 `/steer` 的分工：**排队不影响进行中的任务**，只是登记一条待执行，等当前任务结束、会话空闲后自动作为新任务发送。

```java
QueueExtension queue = engine.addExtensionIfAbsent(new QueueExtension());  // 缺省上限：20 条 / 4096 字符
// 或调整上限：QueueExtension.builder().maxSize(50).maxTextLength(8192).build()
```

上限读取用实例方法 `getMaxSize()` / `getMaxTextLength()`。builder 没有关闭命令注册的选项——`onAttach` 里无条件 `register`。要让 `/queue` 从命令表中消失，只能整体停用：

```java
engine.setExtensionEnabled(queue, false);
```

停用后命令回 `REPLY`（`/queue 已停用…`），`enqueue` / `claimNext` / `drainLoop` 返回失败，但清理类接口照常可用（见下方“受控/不受控”）。

```
用户：/queue 接着帮我把文档也更新一下
回执：已加入队列（第 1 位），将按顺序在任务空闲后自动发送
```

命令只做一件事——`QueueExtension.enqueue(session, text, origin)`，返回值是队列位置（从 1 计），`-1` 表示失败。所有失败在入队前就被拦下并给出明确文案：

| 限制 | 值 | 触发时文案 |
|---|---|---|
| 单条文本长度 | `maxTextLength`（缺省 4096） | `排队内容过长（上限 4096 字符）` |
| 队列容量 | `maxSize`（缺省 20） | `排队失败（队列已满，上限 20 条），请稍后再试` |

#### 宿主侧：入队只是登记，续发靠 drain

所有队列操作都在这一个实例上（内部中转静态实现，`session.queue.*` 契约不变）：

**首选：`claimNext`（一次领取 = 一个 `QueueClaim`）**

```java
// 领取并启动；终态由 dispatch 收口，异步运行者长期持有它就够了
QueueClaimStatus status = queue.claimNext(session, null, claim -> {   // 第二参是锁内谓词「现在能不能接」，null = 不预判
    QueueItem item = claim.getItem();      // 领域对象由 harness 交给你，不用自己存取强转
    setReplyRoute(session, item.getOrigin());
    startTurnAsync(session, item);          // 宿主持有的只是纯业务参数
    return false;                           // 异步启动：终态由流终态的 RunTicket.settle 收口（同步完成则返回 true）
});
if (status == QueueClaimStatus.STARTED) {
    // 终态时（任意线程、任意时刻），从会话取回派发事务并收口：
    //   RunTicket.settle(...) 或 RunDispatch.current(session).settle(true/false)
}
```

> **为什么代际必须由 claim 持有**：引擎中断时 queue 的 `onSessionInterrupt` 会递增代际并移除 in-flight。若宿主在终态「现取」`generation(session)`，取到的必然是新代际，`isGenerationActive` 恒真，于是**被用户 Stop 掉的任务会重新入队继续执行**——丢弃语义被破坏。`QueueClaim` 在领取瞬间绑定代际，对已清空的会话 `settle(false)`（回队）必然被拒。
>
> `QueueClaimStatus` 三态而非布尔：`NO_TASK`（可以停）/ `STARTED` / `START_FAILED`（任务已回队首，**勿立即重试**，否则同一条失败任务会无限循环）。

**推荐：`drainLoop`（连发整条队列，宿主只回答「现在能不能接单」）**

```java
// 一次调用把当前能发的都发完；终止条件与 START_FAILED 不重试的规则由 harness 负责
// 谓词用 isOccupied（不含积压），不要用 isIdle：后者把积压也算忙，队列永远领不到
queue.drainLoop(session, s -> !engine.status(s).isOccupied(), claim -> {
    QueueItem item = claim.getItem();
    boolean startedAsync = startTurnAsync(session, item);
    // 同步完成的命令（如 /status）返回 true，由内核当场收口；异步启动返回 false
    return !startedAsync;
});
```

`canDrain` 传 `null` 表示不预判，完全由队列状态决定。终止条件：谓词返回 `false`、领取结果为 `NO_TASK`、`START_FAILED`（不重试）、或启动成功后队列已空。

> 谓词在**领取权的同一把会话锁内**求值（`claimNext(session, canAccept, handler)`）。因此宿主不需要再写 `synchronized (session.attrs())` 包住派发、也不需要领完再复核一次忙态——那种写法两次判定之间必然有窗口。

#### 派发事务：`QueueClaim` 即 `RunDispatch`（宿主零 attrs 的关键）

一次领取就是一个 `QueueClaim`，它实现核心 `spi.RunDispatch`，收口只有一个动作 `settle(boolean started)`（true = 确认，false = 回队首；幂等）。宿主的收口路径只有两条：

1. **同步任务**：领取回调返回 `true`，内核当场收口（启动异常同样由内核回滚到队首）；
2. **异步任务**：流终态调 `RunTicket.settle(...)`（它会收口会话当前的派发事务）。没有持有凭据时兜底用 `RunDispatch.current(session).settle(...)`。

| 方法 | 作用 |
|---|---|
| `enqueue(session, text[, origin])` / 九参重载 | 入队，返回位置（从 1 计），-1 = 失败 |
| `claimNext(session[, canAccept], handler)` | 领取并启动，返回 `QueueClaimStatus`（NO_TASK / STARTED / START_FAILED） |
| `drainLoop(session, canDrain, handler)` | 连发整条队列（START_FAILED 不重试） |
| `pendingSize` / `hasItems` | 待执行数 / 是否有任务 |
| `list(session)` | 展示视图（`QueueItem` 只读，`toMap()` 为展示字段，含 displayText/source） |
| `isPersisted(context)` | 快照探测（不实例化会话） |
| `cancelItem` / `promote` / `clearPending` | 取消单条 / 置顶 / 清空待执行（不动运行中那条、不递增代际）；「作废全部」只经引擎中断 |
| `importItems(session, rows)` | 旧 `queue-tasks.json` 一次性迁移 |

#### 运行期操作：任务在跑时改队列

排队只在「当前任务结束、会话空闲」时才被消费，所以任务运行期间队列里的东西都是**可以改的**——这正是 `/queue` 的可操作窗口，比 `/steer` 宽得多（后者只在推理边界可用）。

```java
// ① 看：运行中任务在首位（status = RUNNING），其余为 PENDING
List<QueueItem> items = queue.list(session);
for (QueueItem it : items) {
    System.out.println(it.getId() + " " + it.getStatus() + " " + it.getText());
}

// ② 取消某一条待执行任务（只能取消 PENDING；运行中那条不在队列里，返回 NOT_FOUND）
queue.cancelItem(session, itemId);

// ③ 把某条提到队首（下一个就执行它）
queue.promote(session, itemId);

// ④ 清空全部待执行；in-flight 不受影响、不递增代际（OK 时值为清掉的条数）
queue.clearPending(session);
```

三个语义细节：

- **`cancelItem` / `promote` 都只能作用于 PENDING**。运行中那条已从队列出队并记为 in-flight，不在 `Deque` 里，对它的 id 调用一律返回 `NOT_FOUND`。要停运行中那条，用 `engine.interrupt(session, InterruptMode.INTERRUPT)`。
- **`clearPending` 不动 in-flight、不递增代际**，所以“清队列”不等于“停任务”。要连运行中那条一起作废，用 `engine.interrupt(session, InterruptMode.INTERRUPT)`：queue 的中断回调会递增代际（`session.queue.generation`），之后到达的旧代际回队请求被拒绝，避免「用户停了又冒出一条」。
- **`promote` 没有重排位次的概念**：只有置顶，没有“插到第 3 位”。需要精确排序请 `cancelItem` 后重新 `enqueue`。

**任务在跑时“再插一条”就是普通 `enqueue`**——它不需要任何特殊入口，也不会打扰运行中的任务：

```java
// 任务正在跑，用户又补了一句：直接入队，等当前任务结束后自动续发
HarnessResult<Integer> r = queue.enqueue(session, "跑完顺便把文档也更新一下");
// OK 时 getValue() = 队列位置（从 1 计）；否则 FULL / INVALID（空或超长）/ DISABLED / NOT_OWNER
```

这也是 §5.4 场景 A 的降级目标：`steer` 返回 `BOX_FULL` 或 `NOT_RUNNING` 时改走 `enqueue`，语义上就是从「注入当前任务」变成「排在它后面」。

`claimNext` 内部顺序：`tryClaim`（同一时刻只允许一个运行者 poll）→ `poll`（队首出队并置为 RUNNING + 记为 in-flight）→ **领取瞬间绑定代际快照** → 回调 → 异常则 `rollback()` → `releaseClaim`。同步/异步只是收口时机不同（前者 handler 返回 true 由内核当场收口、后者在流终态 `RunTicket.settle`），领取流程只有这一条。

**`claimNext` / `drainLoop` 的返回值（`QueueClaimStatus`）**：

| 情形 | 行为 | 返回值 |
|---|---|---|
| 回调返回 `true`（同步已完成） | 内核当场确认收口；确认被拒（期间被 Stop）时改走丢弃 | `STARTED` / `START_FAILED` |
| 回调返回 `false`（异步启动） | 不收口：终态由宿主在流终态调 `RunTicket.settle(...)` | `STARTED` |
| 回调抛异常 | 内核用领取瞬间的代际自动回队首 | `START_FAILED` |
| `tryClaim` 失败（已有 in-flight）/ 队首为空 | 不执行，失败时 `releaseClaim` | `NO_TASK` |

**受控 / 不受控（停用后行为不同）**：

| 类别 | 方法 | `setEnabled(false)` 后 |
|---|---|---|
| 受控 | `enqueue` / `claimNext` / `drainLoop` | 不再接单、不再续发（`enqueue` 返回 `DISABLED`，领取返回 `NO_TASK`） |
| 不受控 | `settle`（收口）/ `cancelItem` / `promote` / `clearPending` / `pendingSize` 等 | 照常可用——否则停用前已入队的项将永远无法取消 |

三条容易踩的语义：

- **回调返回值必须诚实**——异步启动却返回 `true`，流失败后无法回队重试；同步完成却返回 `false`，会留下悬挂领取挡住后续领取；
- 回队（`settle(false)`）受**代际**保护：引擎中断时 queue 的 `onSessionInterrupt` 会递增 `session.queue.generation`，旧代际的回队请求被拒绝，避免「用户清了队列又冒出一条」；身份校验（in-flight 是不是这一条）由 harness 内部完成，宿主不传 item id；
- `pendingSize` **不含** in-flight，所以容量判定按待执行数算——运行中的那条不占容量。

#### 持久化与重启恢复

队列不用独立文件：投影存在 `getContext().data()` 的 `session.queue.items`（`List<Map>`，**运行中的任务在首位**），任何变更即 `updateSnapshot()` 随会话快照一并落盘。

重启后首次访问队列时 hydrate，`RUNNING` 项**重置为 `PENDING` 并恢复到队首**——上一次进程只是「领取过」，不代表执行完了。只读操作（`pendingSize` / `poll` / `view`）同样会 hydrate，因此重启后队列对只读接口依然可见。

```java
// 启动时探测待恢复的会话
drainSessionQueue(session);   // 只做两件事：判定 busy、然后 drainLoop

// 旧版 queue-tasks.json 的一次性迁移（RUNNING 项会重置为 PENDING 并插到队首）
int imported = queue.importItems(session, legacyRows);   // rows 为 Map 列表，超容量自动截断、按 id 去重
```

> **探测不要实例化会话**：`getSession(id)` 是 `computeIfAbsent` 语义，会把会话连全量历史常驻内存。内存会话用 `queue.hasItems(session)`；只有快照时用 `QueueExtension.isPersisted(context)`（宿主先把快照反序列化成 `FlowContext` 再判定，存储无关）。

#### attrs 运行时契约

以下 key 在 harness 与宿主之间是**一字不改**的约定，任何一侧不得私自重命名：

| key | 内容 |
|---|---|
| `session.queue` | 待执行队列本体（`Deque<QueueItem>`，运行态） |
| `session.queue.generation` | 清空代际（中断回调 `onSessionInterrupt` 递增） |
| `session.queue.claim` | 领取权（同一时刻仅一个运行者，`claimNext` 调用栈内存活） |
| `session.queue.inFlight` | 当前运行中的任务 |
| `session.run.dispatch`（核心 `HarnessAttrs.RUN_DISPATCH`） | **跨线程存活的派发事务**（`QueueClaim`，实现 `RunDispatch`），到流终态收口才清；槽位由核心持有，queue 只是当前唯一的登记方 |

> 宿主**不应直接读写**上表任何一个键——它们登记出来是为了「改键名时有据可查」，日常一律走 `QueueExtension` 门面。尤其是派发槽位 `session.run.dispatch`：它的两个判定（`isOnDispatchThread` / `isCurrent`）语义不同，自己实现极易混淆。

##### 命名空间规约（宿主必读）

所有契约键的**唯一清单**是 `org.noear.solon.ai.harness.session.HarnessAttrs`。规约只有两条，但违反会造成难查的静默故障：

| 前缀 | 归属 | 宿主可否写入 |
|---|---|---|
| `session.*` | **引擎契约**（`HarnessAttrs` 登记） | **禁止**。可以读，不得写自有键 |
| `cli.*` / `im.*` / `web.*` / `desktop.*` | **宿主私有** | 可用，但必须用自有前缀 |
| `_xxx` | 宿主临时态 | 可用（如桌面端的选择器 `_agent_selected_tmp`） |

为什么必须严格：引擎升级时若新增契约键，与宿主私货撞名会让读侧**无法区分「契约」与「私有键」**——旧值会被当成契约值解析，出错时没有任何异常，只有行为诡异。

实例：soloncode 宿主曾把 `session.replyRoute`、`session.queue.executing`、`session.input.admitting` 三个私有键写进引擎命名空间，后统一改为 `cli.replyRoute` / `cli.input.admitting`，并用架构护栏测试（禁止宿主源码里出现 `"session.` 字面量）锁定防止再犯。

同一轮还清掉了三类「不是命名空间问题、但同样会静默失效」的裸键，建议新宿主引以为戒：

| 反例 | 真实后果 |
|---|---|
| 宿主重抄 `"_loop_last_total_tokens"`（该键现已**彻底删除**） | 引擎改键名后宿主静默失效：loop/goal 的预算控制恒为零、熔断判定恒无工具证据。根因是执行通道返回 `String` 装不下 token 与工具证据；现 `TaskHandler` 返回 `RoundResult`，指标随调用栈回流，**不再有旁路键** |
| 宿主把 `HarnessEngine.ATTR_CWD` 当私有状态用，并硬编码默认值 `"."` | 与引擎 workspace 默认值不一致，且引擎一旦复用该键就撞车。现统一走 `engine.getSessionCwd/setSessionCwd/setSessionCwdIfAbsent` |
| 无前缀键 `"streamDoneSent"`、写入后无人读的 `"_plan_mode"` | 前者占用共享平面，后者是纯残留（3 处写、0 处读）。前者已规范为 `web.streamDoneSent`，后者已删除 |

> 建议宿主也建一份自己的 `HostAttrs` 集中登记私有键（soloncode 的做法：放在 `codecli.session` 这种中立包，供 `api.web` 与 `artifact` 等包共同依赖，避免为了一个常量新增包间反向依赖）。

Web 前端的排队编排在浏览器完成，IM 渠道（微信/飞书/钉钉）没有前端，才由本命令承担入队——同一入口不要重复入队。`enqueue` 的 `id` 非空时按 id 去重（含 in-flight），可用于 HTTP 重试幂等。

### 5.3 `/steer`：往正在跑的任务里插一句话

`/steer` 把补充消息注入**当前**任务的工作记忆，由插话拦截器在下一个采样边界消费，**不打断进行中的模型流与工具调用**。

| | `/queue` | `/steer` |
|---|---|---|
| 影响对象 | 当前任务之后的新任务 | 当前任务本轮的工作记忆 |
| 生效时机 | 当前任务结束后 | 下一个推理边界（`onReasonStart`） |
| 任务未运行 | 照常入队，等空闲续发 | 失败（`NOT_RUNNING` / `RUN_CHANGED`） |
| 持久化 | 随会话快照落盘 | **零持久化**，不写 ndjson |
| 容量 | 20 条（`maxSize` 可配） | 5 条（`maxBoxSize` 可配） |
| 溢出策略 | 拒绝入队并告知 | 返回 `BOX_FULL`，由调用方决定改排队还是直发 |

高频 steer 会加速上下文膨胀，所以邮箱只有 5 格；超出场景应引导用户改用 `/queue`。

#### 装配

```java
SteerExtension steer = new SteerExtension();
engine.addExtension(steer);   // onAttach 注册 /steer 命令；onRequestAssemble 每次顶层请求挂拦截器
```

**装配就这一行，没有任何端口要注入。** 输出走 `AgentEvent`：

- **`steer_applied` → `SteerAppliedEvent`**：插话已注入工作记忆。这是「已生效」的唯一信号，客户端据此清除待生效态并落气泡。
- **`steer_dropped` → `SteerDroppedEvent`**：任务结束时仍有未消费插话。已转入 session 队列（客户端应清除气泡），`getFailed()` 未入队且已回填易失邮箱待下次开流重试（客户端应保留并提示尚未排队）。
- **展示与持久化分离**：转排队（经 `spi.InputBacklog`）在 harness 内完成，**不看有没有订阅者**。`pushAgentEvent` 在非流式（无 sink）或订阅已取消时静默丢弃——`steer_applied` 无副作用可以丢，但 `steer_dropped` 的排队动作在推事件之前已完成，因此「已接受但永不生效」在任何宿主下都不会发生（CLI / Desktop 也能把残留插话转为排队任务）。
- **宿主零成本接入**：Web 在 `WebEventMapper` 加两个类型分支映射为 `system.steer_applied` / `system.steer_dropped`；不认识的宿主不写任何代码即自然忽略。**不需要实现端口接口、不需要每请求挂载拦截器**。
- **专用构造器只有一个 `()`（另有 `builder()`）**：没有注入入口，也就没有「漏传端口」这种陷阱。要整体下线用 `engine.setExtensionEnabled(steer, false)`——它会同时停命令、停拦截器（`steer()` 返回 `DISABLED`），`cancelOnRun` / `cancelPending` 等清理接口不受控。

运行期操作也在这一个实例上：

```java
steer.steer(session, "改用 PostgreSQL 方言");              // 最简：投给当前活跃任务
steer.steer(session, "改用 PostgreSQL 方言", origin);      // 带渠道来源
steer.steerOnRun(session, runId, steerId, text, origin); // Web 首选：runId 必填，空即 RUN_CHANGED
steer.pending(session);                                // 待生效插话快照（按投递顺序，不创建状态）
steer.cancelOnRun(session, runId, steerId);            // 撤销一条（含 steerId 校验与轮次比对）
steer.cancelPending(session, ids);                     // 批量撤销；ids 为 null = 清空全部
```

各方法的相对关系：

| 场景 | 入口 | 返回值 |
|---|---|---|
| 任务在跑，插一句 | `steer(session, text)` | `HarnessResult<SteerReceipt>`（`isOk()` 才算入邮箱） |
| Web 端投递（必须绑轮次） | `steerOnRun(...)` | `HarnessResult<SteerReceipt>`，`runId` 空 → `CONFLICT` / `RUN_CHANGED` |
| 刷新/重连后看还有哪几条未生效 | `pending(session)` | `List<SteerMessage>`（副本，不持锁） |
| 取消其中一条 | `cancelOnRun(session, runId, steerId)` | `HarnessResult<SteerReceipt>`（OK / `NOT_PENDING` / `RUN_CHANGED` / `INVALID_STEER_ID`） |
| 一次清掉多条 / 全部 | `cancelPending(session, ids\|null)` | `HarnessResult<SteerReceipt>`（`getCanceled()` 实际取消数 + `getPending()` 剩余数） |

**忙判定由内核自算**：按 `engine.status(session)` 的 busy 或 admitting（`handle` 返回 `TASK` 后占位跟着请求走，订阅或 `call()` 结束前仍算 admitting），宿主不传谓词。使用态 `/steer` 声明 `runnableWhenBusy`，忙时由 `handle` 在运行任务旁边执行（`isBesideRunning()`）。

**宿主没有开流前义务**：上一任务被 interrupt / 异常终止后的残留，由拦截器在新任务 `onAgentStart` 自动转排队并推 `SteerDroppedEvent(RUN_SUPERSEDED)`。

**容量可配**：`SteerExtension.builder().maxBoxSize(5).maxTextLength(4096).maxIdLength(128).build()`；缺省即上表数值，`new SteerExtension()` 与之等价。

**为什么需要 `pending`**：`cancelOnRun` 需要 `steerId`，而 `steerId` 只出现在投递返回值里。没有 `pending` 时，宿主一旦刷新页面或重连，就再也无法取消一条尚未生效的插话——它还会在下一个推理边界被注入，用户“明明取消了却生效”。`pending` 返回的是副本，推理线程同时 `poll` 不会影响遍历，宿主也不该拿它当持久数据。

两参重载 `steer(session, text)`：不比对轮次、绑当前活跃任务，`steerId` 由框架生成，忙判定按引擎口径。它**不降级、不改语义**，返回的仍是同一个 `HarnessResult<SteerReceipt>`。两参版的来源缺省为 `InputOrigin.NONE`，要保留渠道身份用三参版。

命令只做投递，**不做降级**：邮箱满、任务未运行、轮次已切换时如实告知原因，由用户自行决定改排队还是直接发送（与 `/web/chat/steer` 的 409 纯语义对齐）。

```java
// 门面调用（宿主标准路径）：runId=null 绑当前活跃任务，忙判定内核自算（engine 口径）
HarnessResult<SteerReceipt> r = steer.steer(session, text, origin);

// Web 严格模式：runId 必填，空即 RUN_CHANGED；入参校验/长度上限也在内核里判
HarnessResult<SteerReceipt> r2 = steer.steerOnRun(session, runId, null, text, origin);
```

（`SteerMailbox` 是包内可见的内核实现，宿主无从依赖。）

结果分两层：粗码 `getCode()` 决定处置，细分码 `getReason()`（常量见 `SteerExtension.REASON_*`）原样作应答码。

| 粗码 | 细分码 | 含义 |
|---|---|---|
| `OK` | — | 已入邮箱，等待注入（`getValue()` 为 `SteerReceipt`） |
| `CONFLICT` | `NOT_RUNNING` | 当前没有正在执行的任务 |
| `CONFLICT` | `RUN_CHANGED` | 调用方所见的 runId 与当前活跃 runId 不一致（Web 防跨任务错投） |
| `CONFLICT` | `DUPLICATE_STEER_ID` | 同 steerId 已在邮箱中 |
| `FULL` | `BOX_FULL` | 邮箱已满（5 条） |
| `INVALID` | `EMPTY_TEXT` / `TEXT_TOO_LONG` | 文本为空 / 超过 4096 字符 |
| `INVALID` | `INVALID_STEER_ID` | steerId 超过 128 字符 |
| `DISABLED` | — | 扩展/拦截器已 `setEnabled(false)`，本次未入队 |
| `NOT_OWNER` | — | 多节点：会话由其它节点持有（`getOwner()`） |

#### 拦截器挂载：扩展已代劳，无需宿主请求级挂载

`SteerExtension.onRequestAssemble` 会在**每次顶层请求**的装配阶段把拦截器挂到请求级 options（`isDelegated == false` 时），宿主无需每请求 new + add。

**为何不是「主代理默认拦截器」**：面板/`@` 提及选中的子代理也是一个**顶层请求**，只是目标代理不是主代理。挂在主代理默认拦截器上的话，这种请求根本命不中，`onReasonStart` 不会执行、`activeRunId` 恒为 null，用户插话会被 `/web/chat/steer` 判为 `RUN_CHANGED` 并静默降级为排队——表现为「选了子代理就不能插话」。挂到请求级 options 后，无论目标代理是主代理还是面板子代理都能命中。

**为何排除委派任务**：`task`/`multitask` 的子代理任务（`isDelegated = true`）跑在临时 `InMemoryAgentSession` 上，与前端会话无关，没有插话邮箱语义，挂了只会往临时会话写无用状态。

**顺序安全**：请求级 options 的拦截器在 AgentFactory 的代理默认拦截器（压缩/StopLoop）之后追加，因此 steer 拦截器天然排在上下文压缩之后——注入是向压缩产物尾部追加，不破坏消息序列。同类型按 Class 去重，重复挂载无双份。

> **不挂拦截器 = 插话永不生效**：消息能入队，但没人消费，任务结束时由 `onAgentEnd` 清空并转入 dropped。

#### 输出事件（宿主按类型映射，无需实现接口）

```java
public class SteerAppliedEvent extends AbsReActEvent {
    List<SteerMessage> getItems();          // 已注入工作记忆的插话
}

public class SteerDroppedEvent extends AbsReActEvent {
    List<SteerMessage> getQueued();         // 已转入 session 队列（转排队任务）
    List<SteerMessage> getFailed();         // 未入队，已回填易失邮箱待下次开流重试
    boolean hasFailed();
}
```

三个要点：

 1. **事件只是展示，持久化在 harness 内**。`SteerMailbox#transferToBacklog` 先完成转排队、再推 `SteerDroppedEvent`。`pushAgentEvent` 在非流式或订阅已取消时静默丢弃，顺序颠倒就会在无订阅者时丢插话。
2. **不覆写 `getText()`**。这两个事件不属于文本流；一旦给出文本，按文本兜底渲染的宿主（ACP 的 `concatMap` 收尾分支、Desktop 的 frame 转换）会把插话内容顺手打进对话。需要展示的宿主必须显式按类型分支。
3. **映射即接入**。soloncode 的做法是在 `WebEventMapper` 加两个 `instanceof` 分支（→ `system.steer_applied` / `system.steer_dropped`），其余宿主不写代码即自然忽略。**harness 不认识任何宿主类型**，也没有端口接口可实现。

#### 三条注入守卫

1. **首轮不注入**（`turnCount <= 1`）——原始 prompt 必须先被采样，不篡改初始意图；
2. **尾部 tool_calls 未闭合不注入**——HITL 挂起恢复的窄窗里插 user 消息会打断 `tool_use` / `tool_result` 配对，部分模型直接报错；等 `ActionTask` 补齐结果后自然恢复；
 3. **无悬挂**——`onAgentEnd` 检查残留并交给 `SteerMailbox#transferToBacklog` 转排队，绝不允许「已接受但永不生效」。

注入形态：`ChatMessage.ofUser("[用户实时补充] " + text)`，metadata 标记 `source=steer`。不追加 systemPrompt 说明——前缀本身已足够表意。

#### 运行边界

`activeRunId` 在 `onReasonStart` 才写入。所以任务刚被受理、尚未进入推理边界时，steer 会返回 `NOT_RUNNING`（IM 传 null）或 `RUN_CHANGED`（Web 带 runId）——这不是 bug，是「此刻还没有可插话的推理现场」。

插话**零持久化**：不进 `session.addMessage`、不占 `sessionWindowSize`、不会在后续任务里被当作初心重新入场；刷新页面后插话气泡即消失（刻意取舍，展示层由前端在收到 `steer_applied` 时就地渲染进当前流式气泡内部）。

### 5.4 停 / 改 / 重排：干预的两个互斥场景

goal / queue / steer 各自只解决一个方向。真实场景里用户往往想「停」「改」「重排」，但**这三者不是一条串行管道**：steer 只服务于“任务还在跑”的窗口，`interrupt` 之后那个窗口就关了。正确做法是先判断现在处于哪个阶段，再选对应的入口：

```java
// 场景 A：任务还在跑，改主意了——不打断，直接插话（首选）
HarnessResult<SteerReceipt> r = steer.steer(session, "先别改了，回到上一个方案");
if (r.hasReason(SteerExtension.REASON_NOT_RUNNING)) {                 // 判定窗口内任务正好结束：降级为排队，而不是丢弃
    queue.enqueue(session, "回到上一个方案并跑一遍测试");
}

// 场景 B：必须停下来重来——先停，再排新任务
if (Boolean.TRUE.equals(engine.interrupt(session, InterruptMode.INTERRUPT).getValue())) {        // 停（连带清空队列）
    queue.enqueue(session, "回到上一个方案并跑一遍测试");   // 重排
    // 此处不要再调 steer：interrupt 已清掉 activeRunId，只会拿到 NOT_RUNNING
}
```

两条铁律：

- **steer 与 interrupt 互斥**：`activeRunId` 在 `onReasonStart` 写入、在任务结束时清掉；`interrupt` 之后它必然已不在，此时 `steer` 恒为 `NOT_RUNNING`。想“先停再改”只能走 queue。
- **interrupt 会清空队列**：场景 B 里若队列里原本还有别的任务，那些也会一起丢——这是刻意的（见下）。

#### `engine.interrupt(session, InterruptMode)`

```java
HarnessResult<Boolean> engine.interrupt(@Nullable AgentSession session, InterruptMode mode);
```

OK 时值 `true` = 确实停掉了一个正在执行的流；`false` = 当前没有在跑的流（会话为 `null` 也是 `false`）。
非持有节点（多节点部署）三种方式都什么都不做，返回 `NOT_OWNER`（`getOwner()` 为持有者）。

一步式停流只有这一个方法，按 `InterruptMode` 区分「动不动队列、写不写历史、推不推进取消代数」；
需要自定义终态时序时用两段式 `prepareInterrupt`（见下文）：

| 写法 | 队列 | 取消消息 | 取消代数 | 适用 |
|---|---|---|---|---|
| `engine.interrupt(session, InterruptMode.INTERRUPT)` | 清空 | 补写（带 `META_RUN_ID`） | 推进 | 用户主动停（默认逃这个） |
| `engine.interrupt(session, InterruptMode.CANCEL_STREAM)` | 不动 | 不写 | 推进 | 只停流：新目标顶掉旧轮次、后续由程序重发新轮（goal / 自动循环） |
| `engine.interrupt(session, InterruptMode.PREEMPT_STREAM)` | 不动 | 不写 | **不推进** | 「新一轮腾位」：旧流本该自然结束，推代数会把新轮收尾牵连进去（Stop-Replace） |
| `InterruptPlan#writeCancelEarly()` | 不动 | 只补写 | 不动 | 流已停但取消消息丢失（Web 端超时/崩溃恢复） |

三种方式返回值语义一致（值 `true` = 确有活跃流被停）；取消代数差异详见 `InterruptMode` javadoc。

它与此前的 `/interrupt` 命令**同源**（`/interrupt` 命令即调 `InterruptMode.INTERRUPT`），内部四步顺序是语义的一部分：

| # | 动作 | 为何要这个顺序 |
|---|---|---|
| ① | 清排队（queue 插件的 `onSessionInterrupt`，含已被 drain 暂存的项） | **必须早于停流**：停流后宿主常在终态回调里续发队首，顺序颠倒会把刚停掉的任务重新推起来 |
| ② | 取消 `SessionRuns` 登记的全部运行句柄 | 无活跃句柄时直接返回 `false`，不写取消消息（但**取消代数仍会推进**，见下） |
| ③ | 补写取消消息（带 `META_RUN_ID`） | 让前端把气泡挂到正确轮次 |
| ④ | `updateSnapshot()` | 中断时 agent 收尾被跳过，trace 与取消消息需一并持久化 |

> **返回 `false` ≠ 什么都没发生**：取消代数（`SessionRuns.cancelCount`）依然推进。这是刻意的——
> 宿主可能刚把订阅调度到别的线程、句柄尚未登记，此刻的 `interrupt` 必须能拦住那条「随后才到」的流。
> 不要用 `false` 去判断「用户这次点击无效」，它只表示「点击时没有已登记的活跃流」。
>
> **取消以 `onComplete` 收尾，不以 `onError` 收尾**：`ReActRequest#stream()` 被取消时，
> 下游看到的是**正常终止**，不是异常。原因有两条：
> ①框架的取消句柄只打断执行线程、不 `cancel` 上游，因此 `sink.isCancelled()` 为 `false`，
> 中断异常本会一路传成 `onError`；②即便 agent 捕获了 `InterruptedException` 并正常返回，
> 那也不是本轮的有效答复。
>
> 宿主因此要遵守两个约定：
> * **不要把 `doOnError` 当作「本轮失败」**——用户主动停止不会走它；
> * **不要在取消时再发终态帧**——中断入口已同步发过（Web 的 `error`+`done`、桌面的
>   `reason`+`done`）。判断方式是在 `stream()` 之前取 `SessionRuns.cancelCount(session)`，
>   在 `doOnComplete` 里用 `SessionRuns.isCancelledSince(session, gen)` 判定。
> * 取消的轮次**不会**推 `RunEndEvent`，避免把占位文本当成最终答复落库或外发。

> **行为变更点**：早期 `/interrupt` 只停当前流、队列里排着的任务之后还会继续跑；现在「停」是彻底的（队列一并清空）。这正是 那条「停了要清队列」的旧待办的本意——「停了还在跑」比「停得干净」更违反直觉。

宿主已有的停止入口可以改成调它，不必再拼 `"/interrupt"` 文本，也不必关心句柄存在哪里：

```java
// 之前：session.attrs().remove("disposable") → dispose() → 自己补消息与快照
// 现在：
if (Boolean.TRUE.equals(engine.interrupt(session, InterruptMode.INTERRUPT).getValue())) { /* 通知前端已停止 */ }
```

> **句柄登记已上收到框架**：`ReActRequest#stream()` 订阅时会自动登记到 `SessionRuns`，
> 结束时按值注销，宿主<b>不需要（也不应该）</b>往 `session.attrs()` 里写任何 key。
> 早期版本要求宿主写 `attrs("disposable")`，那个约定已废弃；现在写它不会生效，也不会报错。

#### 两段式中断：`engine.prepareInterrupt(session)`

`engine.interrupt(session, InterruptMode.INTERRUPT)` 是「一步到位」。但有些宿主要求自定终态时序，典型是 Web：

```
取消消息落库 → 发 error 帧 → 发 IM 终态 → 发 trace → 发 done → 最后才停流
```

停流提前会让本轮流终结，前端提前收尾并丢掉后续帧。这类宿主不能再调 `interrupt`，
而此前只能自己拼 `SessionRuns.claim + plan.writeCancelEarly() + 手工 dispose`，
混用两个内部类。两段式把这段收归门面：

```java
try (InterruptPlan plan = engine.prepareInterrupt(session)) {
    if (plan == null || !plan.hasActiveRun()) {
        emitFallbackDone();          // 调用时无活跃流，走宿主空态出口
        return;
    }

    // ==== 这里是宿主的编排窗口：顺序完全由你决定 ====
    plan.writeCancelEarly();                                  // ① 提前落库（幂等）
    emitError(InterruptPlan.CANCEL_TEXT);                     // ② 渠道事件
    replyCanceled.run();                                      // ③ IM 终态
    emitTrace(..., InterruptPlan.CANCEL_TEXT);                // ④
    emitDoneOnce();                                           // ⑤ 停流前发 done

    plan.commit();                                            // ⑥ 停流（幂等）
}
```

两段各做了什么：

| 段 | 动作 | 是否可省 |
|---|---|---|
| 第一段（`prepareInterrupt`） | 清队列 + 原子认领运行句柄 + 推进取消代数 | 不可省，`interrupt` 内部也走它 |
| 第二段（`commit`） | 补取消消息（若①已写则跳过）+ 快照 + 停流 | 可省，`close()` 会兜底 |

四条必知语义：

- **`hasActiveRun()==false` 不代表什么都没发生**：清队列与取消代数推进已经生效，
  那条「随后才订阅」的幽灵流也会被拦住。与 `interrupt` 返回 `false` 同理，别当成「点击无效」。
- **`writeCancelEarly()` 与 `commit()` 共用一把原子闩**：一次中断只落盘一次。
  早期手工编排会在窗口内写一次、`commit` 再写一次，导致 `updateSnapshot()` 被调两次——已修。
- **必须闭合**：认领意味着句柄已从登记表上摘下，忘记 `commit` 会留下一条再也无法取消的孤儿流。
  接口继承 `AutoCloseable`，`close()` 等价 `commit()` 且幂等，用 try-with-resources 兜底。
- **取消文案取 `InterruptPlan.CANCEL_TEXT`**：它是门面契约（「用户已取消任务.」）。
  中断实现类已收为包内可见（`HarnessInterrupts`），对外只有 `InterruptPlan`、`InterruptMode` 与引擎的 `interrupt(session, mode)` / `prepareInterrupt`。

只停流不停队列的两种方式（`CANCEL_STREAM` / `PREEMPT_STREAM`）走同一个 `interrupt(session, mode)`，
宿主不需要编排窗口时直接用。

#### 三者的职责边界

| 能力 | 入口 | 语义 | 前置条件 | 任务未运行时 |
|---|---|---|---|---|
| 停 | `engine.interrupt(session, InterruptMode.INTERRUPT)` | 立即终止当前流，**并清空队列** | 有在跑的流（`SessionRuns.isBusy(session)`） | 返回 `false`，不写任何消息（但推进取消代数） |
| 改 | `steer.steer(session, text)` | 注入当前任务的工作记忆，下一采样边界生效 | `activeRunId` 已建立（首轮推理开始后） | 返回 `NOT_RUNNING` / `RUN_CHANGED` |
| 重排 | `queue.enqueue(session, text)` | 登记一条待执行，等空闲后续发 | 无 | 照常入队 |

#### 运行期操作速查

三个能力各自的「运行中能做什么」分散在 §5.1/5.2/5.3，这里汇总成一张表，方便宿主对着写控制面板：

| 能力 | 能看什么 | 能改什么 | 入口 |
|---|---|---|---|
| goal | `goal.get(sessionId)` / `GoalSnapshot.toMap()` | 暂停 / 恢复 / 改配置 / 清除 | `pause` · `resume` · `update(sid, GoalPatch, stopper)` · `clear`（§5.1） |
| queue | `queue.list(session)`（运行中在首位） | 取消单条 / 置顶 / 清空 / 再入队 | `cancelItem` · `promote` · `clearPending` · `enqueue`（§5.2） |
| steer | `steer.pending(session)` | 取消单条 / 批量 / 清空 | `steer` · `cancelOnRun` · `cancelPending`（§5.3） |
| 停 | — | 停流 + 清队列 | `engine.interrupt(session, InterruptMode.INTERRUPT)`（本节） |

三个容易搞混的边界：

- **goal 的暂停与 queue 的清空不会停当前这轮**。它们只影响「下一轮还跑不跑」：goal 的 `pause` 是状态机转 `PAUSED`，当前轮内已经在执行的工具调用不会被打断；queue 的 `clearPending` 不动 in-flight。真正“现在立刻停”只有 `InterruptMode.INTERRUPT`。
- **steer 没有“改”的能力**。已入邮箱的插话只能取消后重新投递，不支持就地编辑——文本不可变，且邮箱里若已满 5 条，取消一条才能再投一条。
- **goal 与 queue/steer 不同层**。goal 是会话级持续轮次，queue/steer 服务于单次任务；一个会话可以有 goal 同时又有排队任务，但 goal 的每轮也会走 `SessionStatus.isOccupied()` 守卫，不与宿主正在跑的任务抢。

从这张表可以直接推出两个宿主要自己处理的边界：

- **“改”的前置条件比“停”窄**：“停”只需流句柄存在，“改”需要已进入推理边界。所以任务刚被受理、模型还没开口的那一小段时间，interrupt 能成功而 steer 会失败——这不是 bug。
- **“停”与“重排”有副作用叠加**：`InterruptMode.INTERRUPT` 会经 queue 的中断回调把队列一起清掉。若宿主想“只停当前、保留已有排队”，用 `engine.interrupt(session, InterruptMode.CANCEL_STREAM)`——只停流、不写取消消息、不动队列（自动循环被新目标顶掉旧轮次的场景用它，避免历史里出现假“用户已取消”）。

 三者都**不自己开流、不做跨能力降级**：steer 邮箱满或任务未运行时如实返回状态码，要不要改走 queue 由调用方决定（`SteerMessage` 的「已接受但永不生效」由 `SteerMailbox#transferToBacklog` 在 harness 内兜底，不在此列）。场景 A 里的那次降级就是宿主侧完成的，不是框架代做。

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
| `true` | 本引擎所有会话 | 工作区代理目录 `<harnessAgents>/<name>.md` | 受 `agentPersistPolicy` 约束（CODING 缺省允许、SERVER 缺省拒绝）；同名文件或同名代理已存在时拒绝，不覆盖 |

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

> 与 §1.2 的 builder 配置表同源，此处为完整语义参考。`profile(SERVER)` 会改变部分缺省值（显式值 > 预设 > 默认，与调用顺序无关）。

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
| `profile` / `scopeResolver` / `scopeRequired` / `sessionOwnership` / `cwdPolicy` / `runtimeMutable` / `memorySessionIsolation` / `sandboxSelfBootstrap` / `protectedStatePaths` | 见 §1.2 | 服务端与多租户配置，完整语义见 [SAAS.md](SAAS.md) |
| `commandPrefix(String)` | `"/"` | 支持多字符 |
| `agentPrefix(String)` | `"@"` | 支持多字符 |

### 8.2 运行期

```java
engine.getSettings().setSandboxEnabled(false);   // 运行期配置统一经 engine.getSettings()；内置能力经 engine.getTalents()
engine.setDefaultModel("deepseek");      // 触发主代理重建
engine.addModel(new ChatConfig(...));
engine.removeModel("gpt-4");

engine.interrupt(session, InterruptMode.INTERRUPT);               // 中断当前任务（连带清队列），true = 确实停了
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
| 5 | 执行环境 | `TerminalTalent` 沙箱、`protectedStatePaths`、`cwdPolicy` | 工具真正执行时能碰哪些目录、哪些状态文件 | §8 配置项、[SAAS.md](SAAS.md) |

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

启动 HITL 需要**三个条件同时满足**（缺一都不会报错，只是不生效）：

1. 主代理工具集包含 `hitl`（`**` 里包含）；
2. `hitlEnabled(true)`；
3. 把 `HITLInterceptor` 绑定到具体工具策略。

详见 [`docs/hitl.md`](../solon-ai-harness-core/docs/hitl.md)。

### 10.3 服务端安全底线

多租户部署时，除 HITL 外还有三道引擎级防线：请求级作用域（`InputScope`，工具执行前否决越界调用）、
受保护状态路径（`protectedStatePaths`，文件工具与 OS 沙盒同时拒绝）、会话工作目录策略（`cwdPolicy`）。
配置与取舍见 [SAAS.md](SAAS.md)。

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

审批与续流的宿主写法见 §3.5（一次 `handle` + `switch (kind)`）。

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
└── solon-ai-harness-host   宿主骨架：依赖 core + 四个插件，位于最上层（任何模块不得反向依赖）
```

依赖方向、聚合包无源码、插件内部类不得 public、插件主代码无废弃标记、goal / loop / queue 写门面必须返回 `HarnessResult` 等约束由 `solon-ai-harness-host` 的 `HarnessStructureGuardTest` 守护。

### 12.2 核心包结构（`solon-ai-harness-core`）

```
org.noear.solon.ai.harness
├── HarnessEngine                 ★ 引擎（Builder + 编排 + 唯一入口 handle）
├── HarnessEnginePool             引擎池（按租户 / 工作区懒建、空闲回收）
├── HarnessProfile                运行模式预设（CODING / SERVER）
├── HarnessSettings / HarnessTalents   引擎的两个视图：目录布局与运行期配置、内置能力（engine.getSettings() / getTalents()）
├── HarnessOptions                运行时配置（包私有）
├── HarnessExtension / AbsHarnessExtension   扩展 SPI
├── RunTicket / InterruptPlan / InterruptMode   一条流（run）的生命周期凭据、两段式中断计划、一步式停流方式
├── HarnessResult                 插件写门面与管理入口的统一结果（粗码 OK / NOT_FOUND / NOT_OWNER / DISABLED / FULL / INVALID / CONFLICT / FORBIDDEN + 可空细分码 getReason）
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
8. **`CODING` 模式下 `build()` 只校验 `sessionProvider`**——缺模型要到首次 `handle` 才报 `chatModel is required`；`SERVER` 模式在构建期就报。
9. **HITL 默认只绑了 `bash`**——`Write` / `Web` 两套策略需自行 `onTool(...)` 绑定，否则不生效且不报错。
10. **新建子代理要注册**——`AgentDefinition` 构造完必须 `getAgentManager().addAgent(def)`，否则 `explicitAgent("xxx")` 回落主代理。
11. **自定义 goal 执行器在挂接后注册**——挂接（构建期 `Builder.extensionAdd(goal)` 的 `build()` 返回后，或运行期 `engine.addExtension(goal)` 之后）再 `goal.addExecutor(channel, claim, executor)`；挂接前调用直接抛异常。**SERVER 模式下 build 之后不能再 `addExtension`**（抛 `IllegalStateException`）。没有宿主执行器时走内置缺省（同步 `engine.handle(...).call()`）。
12. **`/queue` 入队不等于会发送**——宿主必须在任务终态调 `queue.drainLoop(...)` / `claimNext(...)`（`QueueExtension` 实例上）；并且不要自己 `poll`，claim / ack / requeue 全在 harness 内，重复实现会丢任务。
13. **`/steer` 不挂拦截器就永不生效**——返回 `OK` 只代表已入邮箱。`SteerExtension` 在 `onRequestAssemble` 已代劳挂载，**宿主无需请求级挂载**；未注入的残留在任务结束或下一轮开始时经 `InputBacklog` 转排队。
14. **goal / loop 的 job 名带实例序号前缀**——`JobManager` 是 JVM 单例，同名 job 已存在时会静默沿用旧 job；插件按扩展实例自动生成进程内唯一的前缀（goal 每实例一个节拍 `goal-<序号>:tick`；loop 每任务一个 `loop-<序号>:<sessionId>:<taskId>`），宿主无需传任何标识。job 不跨重启、名字不落盘。
15. **builder 只给有可配点的能力**——goal / loop 参数多，只有 builder；queue 与 steer 只有容量类旋钮（queue 的 `maxSize` / `maxTextLength`，steer 的 `maxBoxSize` / `maxTextLength` / `maxIdLength`），无参构造与 `builder().build()` 等价。
16. **`resume` 会立即跑一轮，重启恢复用 `recover`**——`goal.recover()` 按状态来源的 `goal/active/` 索引恢复（无需枚举会话），`recover(sessionIds, statusReader)` 由宿主枚举；二者都只挂调度、不立即跑。
17. **改 goal 配置走 `update(sid, patch, stopper)`**——停流时序由门面完成，`stopper` 只负责「怎么停」（渠道侧终态帧）；停不稳返回 `CONFLICT`（goal 保持暂停，可重试）。
18. **`InterruptMode.INTERRUPT` 会连带清队列**——它不是「只停当前流」。早期 `/interrupt` 保留队列，现在「停」是彻底的（这也补上了「停了要清队列」那条旧待办）。若你需要「停当前、留队列」，用 `engine.interrupt(session, InterruptMode.CANCEL_STREAM)`：只停流、不写取消消息、不动队列，不再是手动复刻内部约定的权衡题。各方式差别见 §5.4 的对照表。
19. **`outcome.call()` / `outcome.stream()` 只在 `TASK` 上有意义**——它们在 `REPLY` / `HITL` / `IGNORED` 上会立即失败（前者抛 `IllegalStateException`、后者返回 `Flux.error`）。不要用它们取代 `switch (kind)`：先判 kind 再取流，顺序不能颠倒。
20. **`GoalSpec` 的负数会被夹到 0**——`maxTokens(-1)` 不是「报错」而是「不限制」；命令行的 `--max-tokens -1` 也一样（`Long.parseLong` 通过后交给 `GoalSpec` 再夹到 0）。想表达「非法参数」请在调用前/解析层自己校验。
21. **四个扩展都没有「只关命令」的开关**——`QueueExtension` / `SteerExtension` 只有 `()` / `builder()`，`GoalExtension` / `LoopExtension` 只有 builder且无 `commandEnabled`；`onAttach` 里一律 `register`（幂等）。要下线只有 `engine.setExtensionEnabled(ext, false)` 一条路，它是「命令 + 门面」一起关；且**未加入引擎的扩展会先被加入再直接返回**，初始状态请在 `addExtension` 前用 `ext.setEnabled(false)`。
22. **`interrupt` 之后不要再 `steer`**——`activeRunId` 已随任务结束被清掉，此时只能拿到 `NOT_RUNNING`；「停下来重来」请直接用 `queue.enqueue`。「运行中改主意」才用 steer，两者是互斥场景而非串行步骤。
23. **`.jobManager(null)` 之后 goal 不会自动推进**——`hasScheduler()` 为 false，`trigger` 仍可手动跑一轮。
24. **goal 对外只有只读快照**——`GoalSnapshot` 没有修改方法，一切状态转移经门面；运行态是包内实现。
25. **命令文本与门面调用是同一个动作，别把两者的返回值当一回事**——`engine.handle(HarnessInput.of(s, "/queue 跑测试"))` 得到的是 `Kind.REPLY` + 人读回执，`queue.enqueue(s, "跑测试")` 得到的是 `HarnessResult<Integer>`（OK 时值为位置）。宿主要程序判定就走门面，要现成文案就走文本路径；**两条都走会入队两次**（命令路径无幂等保护）。
26. **`steer` 取消了也可能已经生效**——`cancelOnRun(session, runId, steerId)` 返回细分码 `NOT_PENDING`（`CONFLICT`）不代表出错，只代表那条已被推理线程取走（已经或即将注入）。同理 `pending(session)` 是快照，读到的下一瞬就可能被消费；宿主不要用它做“一定还能取消”的保证，只能作为 UI 展示依据。
27. **门面不经受理、不经作用域**——`queue.enqueue`、`steer.steer`、`goal.create/update`、`loop.schedule`、`InterruptMode.INTERRUPT` 会先查会话归属（非持有节点返回 `NOT_OWNER`，宿主按 `getOwner()` 转发），但不校验作用域；多租户的管理接口先自行鉴权（[SAAS.md](SAAS.md) §6）。
28. **外部传入的会话 ID 用 `requireSession` / `getSessionOrNull`**——`engine.getSession(id)` 不存在就新建；`engine.requireSession(id)` 不存在时抛 `IllegalArgumentException`。goal 门面已改为不新建会话。
29. **引擎 `shutdown()` 之后不要再用旧引用**——`shutdown` 幂等，会关闭 LSP 进程与 MCP 连接；之后的 `handle` 返回 `REJECTED(engine_closed)`，引擎池模式下重新 `pool.get(key)` 即可。
30. **显式 `HarnessInput.scope(...)` 进积压时只保存名单类维度**——续发时与最新解析结果取交集（只会变窄）；带 `chatModel` / `attr` 的显式作用域在忙时被拒绝（`scope_not_deferrable`），不会悄悄丢掉这些维度。服务端请用引擎级 `scopeResolver`（`InputScopeResolver.byPrincipal`），不要只靠显式传入。`selectedSkill` / `selectedMcp` 视野随积压保存（见 [SAAS.md](SAAS.md) §3.3）。

---

> **测试依赖**：`solon-test`（test scope）
> **编译辅助**：`lombok`（provided scope）
