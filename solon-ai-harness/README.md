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
| 接一个真产品 | **第三章**全部（两段式、事件渲染、HITL 续流、四端时序） |
| 加命令 / 子代理 | 第四章、第六章 |
| 跑 goal / queue / steer | **第五章**（5.1 / 5.2 / 5.3，三个状态型命令的装配与宿主责任） |
| 分清「命令文本 / `handle(HarnessInput)` / 门面」三条路 | **§5 引言**（同一动作的三种写法与返回物差异） |
| 停 / 改 / 重排的干预闭环 | **§5.4**（`engine.interrupt` + `steer` + `queue`，两个互斥场景） |
| 运行期开关扩展 | **§5 引言**（`engine.setExtensionEnabled`）+ §8.2 |
| 调优与定制 | 第七～十章（权限、配置、扩展、安全） |
| 查结构 / 躲坑 | 第十二章（模块图）、第十三章（常见坑） |

> **全文档只推荐一条取流路径**：`outcome.getRequest().stream()`（等价简写 `outcome.stream()`，
> 仅 `Kind.TASK` 有效）。不存在「回执 + 任务合成一条流」的便捷方法，原因见 §3.4 的四端时序对照。
> 需要一次性拿最终文本时用 `outcome.call()`，它只转发 `getRequest().call()`，不做任何时序决策。

---

## 一、五分钟上手

### 1.1 依赖

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-harness</artifactId>
    <version>${solon-ai.version}</version>
</dependency>
```

### 1.2 构建引擎

```java
HarnessEngine engine = HarnessEngine.of("/data/work", ".solon/")   // 工作区, 引擎根目录
        .systemPrompt("你是一名 AI 编程助手")
        .sessionProvider(InMemoryAgentSession::of)                  // 必填
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

> `build()` 只硬校验 `sessionProvider`。没配模型不会在 `build()` 报错，而是在**首次 `handle`**
> 时由 `ReActAgentConfig` 抛 `chatModel is required`。

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
| 历史 | `compressionModel(String)` | `null` | ~~已废弃（4.0.4）~~：压缩复用主模型 |
| 历史 | `cacheControl(CacheControl)` | `null` | 提示词缓存控制（如 Anthropic 式缓存） |
| 记忆 | `memoryProvider(MemorySolutionProvider)` | — | 长期记忆存储方案；不注入则没有 memory 工具可开关 |
| 记忆 | `memoryEnabled(Boolean)` | `true` | 长期记忆开关 |
| 记忆 | `memoryRelevanceCount(int)` | `6` | 相关性召回条数 |
| 记忆 | `memoryPriorityCount(int)` | `5` | 高优先度兜底条数 |
| 记忆 | `memorySummaryLength(int)` | `80` | `listAll` 视图摘要截断长度 |
| 沙箱 | `sandboxEnabled(Boolean)` | `true` | 文件系统沙箱 |
| 沙箱 | `sandboxAllowUserHome(Boolean)` | `true` | 允许访问用户主目录 |
| 沙箱 | `sandboxSystemRestrict(Boolean)` | `false` | 禁止访问系统路径 |
| 权限 | `toolsAdd(...)` / `disallowedToolsAdd(...)` | 空 | 主代理工具权限（§7） |
| 权限 | `permissionRuleAdd(PermissionRule)` | 空 | 权限规则 |
| 权限 | `hitlEnabled(Boolean)` | `false` | HITL 拦截总开关（三条件之一，§10.1） |
| 权限 | `hitlInterceptor(HITLInterceptor)` | 引擎自建 | 不配时构造期自建，默认只绑 `BashToolStrategy` 到 `bash` |
| 子代理 | `subagentEnabled(Boolean)` | `true` | 子代理委派功能（`task` / `multitask`） |
| 子代理 | `subagentDefaultConcurrency(Integer)` | `5` | 子代理默认并发上限（`1~10`，`0`=未设置取默认 5；模型配了 `AiConfig#concurrency` 以模型为准） |
| 子代理 | `multitaskMaxTasks(Integer)` | — | ~~已废弃~~：旧名，改用 `subagentDefaultConcurrency` |
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

> 三个拦截器位（压缩 / 刹车 / HITL）不配时都会在**构造期自建默认实现**，自建时机早于 `build()` 末尾的 `extension.initialize(engine)`——所以扩展在 `initialize` 里通过 `engine.getCompressionInterceptor()` 等拿到的必是最终实例。

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
> 它把 `handle` 与 `InputOutcome` 拆成两段并处理了四种 `Kind`，能直接长成宿主外壳。

---

## 二、核心 API：`handle` + `InputOutcome`

### 2.1 唯一的输入入口

```java
// HarnessEngine —— 两个重载，实际只有后者做事
public InputOutcome handle(AgentSession session, String text);   // 便捷：等价于 handle(HarnessInput.of(session, text))
public InputOutcome handle(HarnessInput input);                  // 唯一入口
```

一次 `handle(...)` 内部按固定顺序做七件事，顺序本身是语义的一部分：

| 步骤 | 动作 | 说明 |
|---|---|---|
| 1 | HITL 决策 | **优先于空输入判空**：审批请求通常不带文本。带 `resume(true)` 时同一次调用**尝试**续流，是否真的续流由引擎自检决定（见 §2.3） |
| 2 | 续流分流 | `resume(true)` 直接装配请求，不解析文本 |
| 3 | 空输入判空 | 无文本且无附件 → `IGNORED` |
| 4 | 命令识别与执行 | 有附件或 `commandAware(false)` 时不当作命令 |
| 5 | 子代理解析 + 附件前缀 | 解析 `@name` 前缀，文件引用拼 `[附件: x]` |
| 6 | Prompt 构建 | 多模态内容块、来源/附件/子代理元数据 |
| 7 | 请求装配 | 模型回退链 + 子代理 + `ReActRequest` |

**第 1–7 步全部同步完成，不自启推理。** 推理只在宿主订阅 `getRequest().stream()` 时才开始——这是两段式设计的根基（详见第三章）。

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
| `explicitAgent(String)` | 请求体显式指定，**优先级最高**（桌面端用） |
| `attachments(InputAttachments)` | 附件装配结果（多媒体块 + 文件引用） |
| `media(MediaBlock)` | 追加多媒体块（便捷写法） |
| `reference(path)` | 追加文件引用（便捷写法） |
| `agentNameValidator(Predicate)` | 子代理名格式校验 |
| `trimContent(boolean)` | 剥提及后去首尾空白（桌面端需要） |
| `origin(InputOrigin)` | 本次输入的渠道来源身份（渠道 / 发送者 / 返回地址 / 入站关联键） |
| `selectedSkill(String...)` | 本次请求的技能视野（收窄技能清单 + 预加载选中技能正文） |
| `selectedMcp(String...)` | 本次请求的 MCP 服务视野（按 `addMcpServer` 的配置名匹配） |
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

| 成员 | 说明 |
|---|---|
| `getKind()` | 上述四态（一个值对应一个动作） |
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
                .sessionProvider(InMemoryAgentSession::of)              // 必填
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

> `build()` 只硬校验 `sessionProvider`。「`chatModel is required`」是**首次 `getMainAgent()`** 时由 `ReActAgentConfig` 抛出——所以 `modelAdd` / `defaultModel` 缺失不会在 `build()` 报错，而是在第一次 `handle` 报错。

### 3.2 完整宿主：四态都处理到位

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
                .sessionProvider(InMemoryAgentSession::of)
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
            case TASK:    onTask(outcome);   break;
            case REPLY:   onReply(outcome);  break;
            case HITL:    onHitl(outcome);   break;
            case IGNORED: break;
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
3. **代理名必须沿用 `outcome.getAgentName()`**——它是「本轮生效代理」，由显式入参优先、会话级 `CTX_AGENT_SELECTED` 兜底解析。trace 按代理名分 key，换回主代理会取不到挂起的 `tool_calls`，表现为续跑空转。
4. **续流时机由引擎判定，宿主只管事实**——不要用 `HITL.getPendingTasks(session)` 自己数整批是否齐备（该集合含已决策项，`isEmpty()` 在刚写入首张决策后仍为 false，用它判定会把已经齐备的批永远卡住）；该集合只用于「展示下一张未决卡」。齐备判定统一读 `outcome.getPendingHitlCount()`。

---

## 四、完整示例：命令

### 4.1 引擎不自带命令，宿主负责注册

`harness` 提供了命令框架与 `command.builtin` 下的一批实现（`help`/`clear`/`model`/`queue`/`steer`/`goal`/`rewind`/`rerun`/`continue`/`interrupt`），**但不会自动注册**——因为「哪些命令可用」是端特性。裸引擎上 `handle(session, "/help")` 返回 `TASK` 而不是 `REPLY`。

```java
CommandRegistry registry = engine.getCommandRegistry();

// 1) 从目录加载 Markdown 自定义命令
registry.load(Paths.get(userHome, engine.getHarnessCommands()));      // ~/.solon/commands/
registry.load(Paths.get(workspace, engine.getHarnessCommands()));     // ./.solon/commands/

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
| `/goal` | 会话级目标（每会话一个） | `GoalExtension.builder(workspaceId)` + `addExtension` | 命令能创建目标，但永远不跑第二轮 |
| `/queue` | 会话级排队队列 | `QueueExtension` + `addExtension`，任务终态调 `queue.drainOne(...)` | 命令能入队，但队列永远不动 |
| `/steer` | 当前任务的插话邮箱 | `SteerExtension` + `addExtension`（拦截器由扩展挂载） | 命令能入队，但永不注入 |
| `/interrupt` | 无状态（只停） | 无（现成的 `engine.interrupt(session)` 即可） | 不装命令就只能由宿主自带停止按钮 |

**三个能力，三个类**：每个命令从装配到运行期操作宿主都只接触一个 Extension 实例（宿主侧与 harness 的交互面收敛到一个类），不再需要宿主逐个 new `GoalEngine`/`GoalScheduler`/`ExecutionBridge`/`GoalCommand`，也不需要分别操作 `SessionQueue` 与 `SessionQueueDrainer`。命令本身的语法与回执未变，终端用户无感知。

```java
GoalExtension goal = GoalExtension.builder(workspaceId).taskExecutor(myRunner).build();
engine.addExtension(goal); // /goal

QueueExtension queue = new QueueExtension();
engine.addExtension(queue); // /queue

SteerExtension steer = new SteerExtension();
engine.addExtension(steer); // /steer
```

三者共同点：`/queue` 与 `/steer` 的 `runnableWhenBusy()` 返回 `true`（宿主据此允许它们在任务执行中被受理）；`/goal` **不覆写**、保持默认 `false`（它会启动持续轮次，不能在忙时会话里重入）。注意这只是**给宿主读的声明**，引擎内部不做忙碌拦截（详见下文边界）。`/queue`、`/steer` 从 `Command.execute(engine, ctx, origin)` 的第三参拿到 `InputOrigin` 并带到投递链路，三者都**不自己开流**。

**同一个动作，三条到达路径**：命令不是另一套实现，它是「文本入口」。同一能力既可以由文本进来（终端用户敲、IM 渠道转发），也可以由代码直接进门面；中间还有一条「代码把文本交给引擎」。以 `/queue` 为例三者并排：

```java
// ① 文本入口：终端用户敲的那一条（IM 转发也是它）
InputOutcome o = engine.handle(HarnessInput.of(session, "/queue 顺便更新 README")
        .origin(InputOrigin.channel("wechat")));   // Kind.REPLY，回执在 o.getCommandOutput()

// ② 同一段文本，由代码交给引擎：前缀识别、命令切分、回执文案全由引擎与命令产出
InputOutcome o2 = engine.handle(HarnessInput.of(session, "/queue 顺便更新 README"));
// 与 ① 唯一差别：origin 未给（回执归由宿主自己路由）

// ③ 直接调门面：跳过文本，宿主自己编排（命令内部调的就是它）
int pos = queue.enqueue(session, "顺便更新 README");  // 2 参，返回队列位置（从 1 计），-1 = 失败
```

①② 是文本路径，③ 是对象路径，**动作等价但返回物性质不同**：前两者给你一段供人读的回执，后者给你一个可判定的值。对照如下：

| 维度 | ① / ② `engine.handle(HarnessInput.of(session, "/queue …"))` | ③ `queue.enqueue(session, text)` |
|---|---|---|
| 参数由谁解析 | 引擎（命令前缀 + `ParsedCommand` 切分） | 宿主（直接给强类型值） |
| 返回值 | `InputOutcome`：`Kind.REPLY` + `getCommandOutput()` | `int` / `SteerResult` / `GoalState` |
| 失败如何得知 | 回执文本里（人读：「排队失败（队列已满，上限 20 条）」） | 返回值（程序判定：`-1` / 状态码） |
| 回执文案 | 命令内置，可直接渲染 | **没有**，要展示得宿主自己写 |
| 停用拦截点 | `CommandDispatcher` 判 `isEnabled()` → 直接回 `REPLY` | 门面内 `isEnabled() == false` → `-1` / `false` / `DISABLED` |
| 来源身份 | `HarnessInput.origin(...)`，命令再按渠道兜底 | 最简重载给缺省值（`queue` 补 `channel("queue")`、`steer` 用 `NONE`、`goal` 不带） |
| 忙碌放行 | 宿主读 `Command#runnableWhenBusy()` 决定要不要受理 | 宿主自行判定 |
| 需要 `.terminal(true)` 吗 | 不需要（这四个命令都不是 `terminalOnly`） | 不适用 |

四个能力的最简门面形态（① 内部最终都落到这里，与命令走同一条写路径；§5.4 还给出了“运行中干预”与“停下来重排”两个互斥场景）：

```java
goal.attachAndSchedule(session, "让测试全绿");   // 2 参，返回 GoalState

int pos = queue.enqueue(session, "顺便更新 README");  // 2 参，返回队列位置（从 1 计），-1 = 失败

SteerResult r = steer.steer(session, "改用 PostgreSQL 方言");  // 2 参

engine.interrupt(session);                       // 1 参，true = 确实停了
```

**同源是可验证的**，不是口头承诺——三条命令的实现体就是这三个方法，文档里不必写两套语义：

| 命令 | 命令内部的实际调用 |
|---|---|
| `/goal <obj>` | 解析 flag 得到 `GoalSpec` 后 `goal.attachAndSchedule(session, spec)`（`GoalCommand` 注释明写「命令行与编程调用的唯一汇合点」） |
| `/queue <msg>` | `SessionQueue.enqueue(session, text, resolved)`——`QueueExtension.enqueue` 转调的就是这个静态方法 |
| `/steer <msg>` | `SteerInterceptor.steer(session, null, null, text, origin, null)`——正是 `steer.steer(session, text, origin)` 的三参实现 |

三条由此推出的边界：

- **这三个命令的 `Kind` 恒为 `REPLY`**：它们只改会话状态、不声明 Agent 任务意图，所以命令路径下不会出现 `TASK`。`/goal` 虽然会自己起持续轮次，但那是调度器驱动的、不在本次 `handle` 的返回里——别等 `getRequest()`。
- **忙碌放行不会自动发生**：`runnableWhenBusy()` 只是一个可读声明，引擎在任何路径上都不做忙碌拦截（`HarnessEngine` 与 `input` 包内零 busy 引用）。宿主不论走 ①② 还是 ③，都得自己先判“会话是否正忙”再决定受理——差别只在于 ①② 能直接读 `engine.getCommandRegistry().get("queue").runnableWhenBusy()`，③ 需自己写死这个知识。
- **不要两条路都走**：宿主若已用门面完成了动作，就不要再把同样的文本拼成 `/queue …` 交给 `handle`，那会入队两次（命令路径没有幂等保护，`id` 去重只对带 `id` 的 `enqueue` 生效）。

除了 `attachAndSchedule`（失败时抛 `IllegalStateException`），其余三个入口都**用返回值报告失败、不抛异常**，宿主应按返回值分流（命令路径的每种失败文案就是据此产生的）：

| 调用 | 失败形态 | 处理建议 |
|---|---|---|
| `enqueue` | 返回 `-1`：队列已满 / 文本超长 / 扩展已停用 | 提示用户稍后再试，或改为直接发送 |
| `steer` | `isSteered() == false`，状态码见 §5.3 表格 | `isNotRunning()` → 改走 queue；`isBoxFull()` → 改走 queue 或直发 |
| `interrupt` | 返回 `false`：当前没有在跑的流 | 不写取消消息、不改快照；**但队列已被 `cancelPending` 清空**（见 §5.4 第 ① 步） |
| `attachAndSchedule` | 抛 `IllegalStateException`：已有进行中 goal | 回 409，提示先 `/goal clear` |

**运行期开关：统一走引擎，不要 `new` 一个关着的扩展**

三个扩展都继承 `AbsSwitchable`，`setEnabled(null)` 表示“不改变”。推荐入口是引擎级方法，它会**先传播给扩展的子组件（命令 / Talent / 拦截器），再重建主代理**（Talent 与拦截器是装配期下发的，不重建就不会生效）：

```java
engine.setExtensionEnabled(queue, false);   // 停用：命令 + enqueue/drainOne 一起失效
engine.setExtensionEnabled(steer, false);   // 停用：命令 + 拦截器 + steer() 返回 DISABLED
engine.setExtensionEnabled(goal,  false);   // 停用：命令 + Talent，并 stopAll() 停掉所有 goal 调度
engine.setExtensionEnabled(goal,  true);    // 重新启用
```

三处容易误解的地方：

- **未加入引擎的扩展会先被加入**（再直接返回），所以 `setExtensionEnabled` 可以用于“先建后开”，但第一次调用不会立即应用 `enabled`——想控制初始状态请在 `addExtension` **之前**调 `ext.setEnabled(false)`；
- **命令停用不等于退化为普通输入**：`/queue` 会回 `REPLY`（`/queue 已停用。发送 /help 查看可用命令。`），且 `isCommand()` 仍为 `true`，宿主不必写额外分支；
- **“只保留门面、不注册命令”这一档目前不可达**：三个扩展的构造器/builder 都没有关闭命令注册的开关，`initialize` 里一律 `register`（注册本身幂等，已有同名命令则跳过）。要让整个能力下线只有 `setExtensionEnabled(..., false)` 一条路，它是“命令 + 门面”一起关。

### 5.1 `/goal`：会话级有终止判据的持续执行

Goal 是「给它一个目标，它一轮轮自己跑，直到达成 / 预算耗尽 / 熔断」的能力。归属是**会话**，每会话最多一个，状态随会话快照持久化，重启后仍在。

#### 装配：一个类搞定

GoalExtension 已把执行桥、引擎、调度器、命令、Talent 全部收进一次装配。宿主要写的只有**一个 builder 链**：

```java
GoalExtension goal = GoalExtension.builder(workspaceId)   // job 名工作区前缀（跨工作区防撞）
        .taskExecutor((sessionId, prompt, agentName) -> runOneTurn(sessionId, prompt))
        .busyChecker(sessionId -> isSessionBusy(sessionId))    // 可选，缺省恒 false
        .budget(70, 85)                                        // 可选，缺省 70/85
        .stagnation(3).maxErrors(3).validatorEnabled(true)      // 可选，均为缺省值
        .workspace(workspacePath)                              // 可选，验证器执行目录；缺省 = workspaceId
        .build();

engine.addExtension(goal);   // initialize 内部依次完成：
                             // bridge → GoalEngine → GoalScheduler → /goal 命令注册 → Talent 挂载
```

要点：

- **`taskExecutor` 是唯一有实际意义的必选项**。最小用法是 `GoalExtension.builder(workspaceId).build()` + `addExtension`，此时走内置缺省执行器——它不是裸的 `engine.handle(...).call().getContent()`，而是带三个关键修饰：`commandAware(false)`（目标正文里的 `/xxx` 不被当命令）、`agentAware(false)`（开头的 `@name` 不被当子代理提及）、`origin(channel("goal"))`（来源标记，便于宿主分流）。即便有这些修饰，它仍不会带宿主的专属执行语义（Web 的流式通道、CLI 的终端读入），所以宿主一般仍应显式提供 `taskExecutor`。
- **`bridge` 与 loop 共享**：宿主已有 `ExecutionBridge` 时用 `.bridge(shared)` 注入，扩展不再自建；TaskHandler / BusyChecker 仍注册到同一桥上，两个能力共用一套执行通道。
- **job 名带工作区前缀**在扩展内部完成，`JobManager` 是 JVM 单例、会话 ID 不全局唯一（CLI 叫 `cli`、桌面是 `ws_<时间戳>`）——不加前缀时多个工作区会在全局注册表里互相覆盖。
- **`.scheduler(x)` 三态**：不传 = 缺省新建；传实例 = 宿主自备；**显式传 null = 关闭调度**（纯 JUnit / 无 Solon 容器的宿主，`JobManager.getInstance()` 会 NPE，只能关）。关闭后 `hasScheduler()` 返回 `false`，`scheduleNow` / `resumeScheduleOnly` / `unschedule` / `stopAll` 全部变成**静默空操作**——这是刻意的，但排查“调了 `scheduleNow` 却没跑”时先看这里。
- **没有“只关命令”的开关**：`GoalExtension` 的构造器私有，builder 也不提供 `commandEnabled`。注册本身是幂等的（已有同名命令则跳过），迁移期双写安全；要整体下线用 `engine.setExtensionEnabled(goal, false)`（连带停调度，见本章引言）。
- 存量写法是静态工厂 **`GoalExtension.of(goalEngine, engine)`**（构造器不可见）：只接管外部 GoalEngine、只挂 Talent 与门面，**不装配命令、也不建调度**（`getGoalScheduler()` 为 `null`）。它**未标 `@Deprecated`**——存量宿主可以继续用，但新代码推荐 builder。

#### 运行期操作：还是这一个类

不必再持 `GoalEngine` / `GoalScheduler` 引用，门面方法直接中转：

```java
goal.state(session); goal.hasGoal(session);              // 读
goal.stateOf(sessionId); goal.sessionOf(sessionId);       // 按 id 读（免自己解析会话）
goal.find(sessionId, taskId);                           // 任务列表行 → GoalState（不是 goal 行返回 null）
goal.running(session); goal.running(sessionId);           // 是否正在跑一轮
goal.pause(session, gs); goal.resume(session, gs);        // 控（自动同步调度注销/重挂）
goal.attachAndSchedule(s, objective);                   // 建 + 挂调度（最简形态，不设预算）
goal.attachAndSchedule(s, spec);                        //   同上，参数对象（GoalSpec）
goal.attachAndSchedule(s, obj, tokens, ms, iter);       //   同上，五裸参（存量写法，保留）
goal.attach(s, objective);                              // 只建不挂（首轮由宿主流驱动，如桌面端）
goal.attach(s, spec); goal.attach(s, obj, tokens, ms, iter);   //   同上另两种形态
goal.updateAndReschedule(s, gs, obj, tokens, ms, bool);  // 改配置 + 按需恢复调度
goal.resumeScheduleOnly(sessionId);                     // 只挂 job 不立即跑（重启恢复场景）
goal.scheduleNow(sessionId); goal.unschedule(sessionId);  // 单独的调度触发/摘除
goal.runRoundAsync(session);                            // 异步跑一轮（/loop trigger 语义）
goal.clear(session); goal.stopAll(); goal.hasScheduledGoals(); goal.hasScheduler();
goal.toTaskMap(session, gs);                              // 列表/徽章视图（与 loop 任务同构）
goal.addListener(l); goal.registerValidator(matcher, v);   // 扩展点
goal.getGoalTalent().setEnabled(false);                    // 只关模型侧工具，不影响命令
```

#### 创建：优先用 `GoalSpec`，而不是五个裸参

编程调用的创建入口有三个形态，**推荐参数对象**：

```java
// 最简：不设任何预算（= 不限制），等价于 GoalSpec.of(objective) 全缺省
goal.attachAndSchedule(session, "让测试全绿");

// 带预算：单位自描述，可选参数自然缺省
goal.attachAndSchedule(session, GoalSpec.of("修复登录模块的鉴权缺陷")
        .maxTokens(2000)
        .maxDuration(Duration.ofMinutes(30))    // 不再写 30 * 60_000L
        .maxIterations(5));

// 存量：五裸参，第四个是毫秒（靠 javadoc 才能看出单位）
goal.attachAndSchedule(session, "修复登录模块的鉴权缺陷", 2000, 1_800_000L, 5);
```

`GoalSpec` 的口径与命令完全一致：未设置为 `0` 表示不限制；负数一律夹到 `0`；空正文在 `GoalSpec.of(...)` 当场抛 `IllegalArgumentException`。

**命令行与编程 API 走同一条写路径**：`/goal` 命令把 `--flag:value` 解析成 `GoalSpec` 后调的就是 `attachAndSchedule(session, spec)`，`GoalCommand` 自身不再持有 `GoalEngine` / `GoalScheduler`（`pause` / `resume` / `clear` 也是直接转调扩展方法），因此不存在「命令能跑但门面行为不同」的双实现漂移。

三个易错点（宿主自己拼调度的老写法踩过）：

- **`resume()` 是 `runNow=true`**。服务重启/工作区恢复时想「静默挂上、等下一轮」必须用 `resumeScheduleOnly()`，否则会对每个活跃 goal 立刻发起一轮执行。
- **改配置只吞后半段**。「pause → interrupt → 等轮次停 → update → resume」里的中断与等待是宿主的运行时职责，门面不代管；`updateAndReschedule` 只覆盖 `update + 时长 commit + resume + schedule`。中断本身现在有引擎级入口 `engine.interrupt(session)`（§5.4），不用再拼 `/interrupt` 文本，但**「什么时候中断」仍由宿主决定**。
- **`clear()` 内部先摘调度再清状态**，不会留一个已无目标却仍在调度的 job。

这张清单之外还有两个宿主常忘的写路径：

```java
goal.commit(session, gs);   // 直接改了 GoalState 字段后必须调，否则投影不落盘、监听器不广播
goal.resumeAndSchedule(session, gs);  // 与 resume 同义，命名上强调“连带挂调度”
```

确需细粒度访问时 `getGoalEngine()` / `getGoalScheduler()` 仍可取到内部实例。

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
| `/goal --max-tokens:2000 <objective>` | 带 Token 预算 |
| `/goal --max-duration:30 <objective>` | 带时间预算，**单位是分钟** |
| `/goal --max-iterations:10 <objective>` | 带轮次预算 |
| `/goal pause` \| `resume` \| `clear` | 暂停 / 恢复 / 清除 |

Flag 格式是 `--key:value`（冒号分隔，不是等号/空格）：缺冒号会打印 `Unknown flag` 并跳过该参数；数值非法直接 `Invalid --xxx value` 返回。`--max-duration` 内部转毫秒（`×60_000`，**唯一换算点**）。三个预算默认都是 `0`，表示不限制。若正文为空，回执会同时打印 Usage 与一行最简示例。

> 命令行的 `--max-duration:30` 在 `GoalCommand#parseSpec` 里就被换算成 `GoalSpec.maxDurationMs(1_800_000)`，之后一路是同一个 `GoalSpec` ——文本 flag 形态到此为止，不会再往下传。

已有进行中（非终态）的目标时创建会被拒绝：

```
WARN: 当前会话已有进行中的 Goal，请先清除后再创建
  Active: <当前目标>
  Use /goal clear first to replace the goal.
```

#### 运行机制

调度是**状态驱动**，不是时间表驱动：固定 `fixedDelay = 5s` 轮询，每轮自判还要不要继续。`schedule(sessionId, runNow)` 的 `runNow = true` 时首轮 `initialDelay = 0`（立即跑），否则等一个间隔。

每轮 `runRound(session)` 的守卫顺序（任一条命中就跳过或收尾退出）：

1. 目标不存在或状态非活跃 → 退出并注销 job；
2. `bridge` 没有 `TaskHandler` → 跳过（日志 `execution bridge unavailable`）；
3. `bridge.isBusy(sessionId)` 为 true → 跳过（不抢宿主正在跑的任务）；
4. 轮前预算守卫（时间 / Token / 轮次）→ 命中则先跑一轮「收尾总结」再标记终结态；
5. CAS 防重入（`session.goal.running`）。

轮内流程：`buildEffectivePrompt` → `bridge.execute(...)` 取 `RoundResult` → 累计 Token / 轮次 / 执行指纹（无进展检测）→ 完成判定复核 → `commit` 落盘。

> **只有一个驱动源**：错误后不再 `delay` 补发，重试靠下一个 5s 轮询。双驱动会叠加轮次。

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

验证器默认注册两个，按目标文本（小写）匹配，多命中按 AND 链式生效：

| 验证器 | 匹配关键词 |
|---|---|
| `TestsPassValidator` | `test` / `pass` / `check` / `ensure` / `verify` |
| `BuildPassValidator` | `build` / `compile` / `implement` / `refactor` / `fix` / `create` / `add` / `write` |

无命中时用 `NoopValidator`（恒通过）。`validatorEnabled = false` 时第 4 道闸整体跳过。

#### 读取与监听

```java
// 供列表 / 徽章渲染：字段名与前端 goal.* 约定严格对齐（改键名会直接让状态徽章与预算条失效）
GoalState gs = goal.state(session);
Map<String, Object> item = goal.toTaskMap(session, gs);   // 与 loop 任务同构，type = "GOAL"

// 按 id 读，免去宿主自己 sessionOf → state → taskId 比对的三连样板（不是 goal 行返回 null）
GoalState byId = goal.find(sessionId, taskId);

// 生命周期观察者：桌面端用它把后台多轮执行持续推回同一个对话流
// state 在 removed = true 时仍是清除前的快照
goal.addListener((sessionId, state, removed) -> { /* push to client */ });
```

#### 存储分层

与 `SessionQueue` 完全一致：**存储介质是会话实现的细节**（文件 / 内存 / Redis / JDBC），`GoalEngine` 只依赖 `AgentSession`。

| 位置 | 内容 | 是否落盘 |
|---|---|---|
| `getContext().data()` 的 `session.goal` | Map 投影（单槽，一条记录） | 是，随 `updateSnapshot()` |
| `attrs()` 的 `session.goal.state` | 运行态 `GoalState` 实例 | 否（瞬态，首次访问从投影 hydrate） |
| `attrs()` 的 `session.goal.running` | 轮次防重入标记 | 否 |

### 5.2 `/queue`：把消息排在当前任务之后

`/queue` 与 `/steer` 的分工：**排队不影响进行中的任务**，只是登记一条待执行，等当前任务结束、会话空闲后自动作为新任务发送。

```java
QueueExtension queue = new QueueExtension();   // 零旋钮，无参构造即全部缺省
engine.addExtension(queue);                    // initialize 内部注册 /queue 命令（幂等）
```

`QueueExtension` **只有隐式无参构造器**，没有 `(boolean)` 变体，也没有关闭命令注册的 builder——`initialize` 里无条件 `register`。要让 `/queue` 从命令表中消失，只能整体停用：

```java
engine.setExtensionEnabled(queue, false);
```

停用后命令回 `REPLY`（`/queue 已停用…`），`enqueue` / `drainOne` 返回失败，但清理类接口照常可用（见下方“受控/不受控”）。

```
用户：/queue 接着帮我把文档也更新一下
回执：已加入队列（第 1 位），将按顺序在任务空闲后自动发送
```

命令只做一件事——`SessionQueue.enqueue(session, text, origin)`，返回值是队列位置（从 1 计），`-1` 表示失败。所有失败在入队前就被拦下并给出明确文案：

| 限制 | 值 | 触发时文案 |
|---|---|---|
| 单条文本长度 | `MAX_TEXT_LENGTH = 4096` | `排队内容过长（上限 4096 字符）` |
| 队列容量 | `MAX_QUEUE_SIZE = 20` | `排队失败（队列已满，上限 20 条），请稍后再试` |

#### 宿主侧：入队只是登记，续发靠 drain

所有队列操作都在这一个实例上（内部中转静态实现，`session.queue.*` 契约不变）：

```java
// 同步运行者：回调返回即认为执行完毕并确认（CLI 交互循环）
while (queue.drainOne(session, item -> runOneTurn(session, item))) {
    // claim / poll / ack / requeue 全部由 drainOne 负责，宿主不要自己 poll
}

// 异步运行者：回调只是「启动」，终态再确认（Web，任务结束才 ack）
queue.drainOne(session, item -> startTurnAsync(session, item), false);
// ...该轮到达终态时
queue.ack(session, item.getId());
```

> 第二个参数传 `false` 时，`drainOne` **既不做 ack 也不回队**：回调正常返回即 `true`，那条任务的命运完全由宿主在终态自己 `ack` / `cancelItem` 决定。只有“要求同步确认但 `acknowledge` 失败”（如 in-flight 身份已变）或“回调抛异常”时，才会 `requeueFront` 并返回 `false`。

完整门面一览：

| 方法 | 作用 |
|---|---|
| `enqueue(session, text)` | 入队，返回位置（从 1 计），-1 = 失败；来源缺省为 `channel("queue")` |
| `enqueue(session, text, origin)` | 入队，携带渠道来源（命令路径用） |
| `enqueue(...)` 九参重载 | 带 id 去重 / model / effort / agent / files（HTTP 重试幂等） |
| `drainOne(session, runner)` | 领取并执行队首一条（同步语义：返回即确认） |
| `drainOne(session, runner, false)` | 异步语义：宿主终态再 `ack`（本方法不 ack、不回队） |
| `ack(session, itemId)` | 异步终态确认（校验 in-flight 身份） |
| `pendingSize` / `size` / `hasItems` / `view` | 待执行数 / 含 in-flight 总数 / 是否有任务 / 统一视图 |
| `hasPersistedItems(context)` | 快照探测（不实例化会话） |
| `cancelItem` / `promote` / `cancelPending` | 取消单条 / 置顶 / 清空并递增代际 |
| `importItems(session, rows)` | 旧 `queue-tasks.json` 一次性迁移，返回实际导入条数 |

#### 运行期操作：任务在跑时改队列

排队只在「当前任务结束、会话空闲」时才被消费，所以任务运行期间队列里的东西都是**可以改的**——这正是 `/queue` 的可操作窗口，比 `/steer` 宽得多（后者只在推理边界可用）。

```java
// ① 看：运行中任务在首位（status = RUNNING），其余为 PENDING
List<SessionQueueItem> items = queue.view(session);
for (SessionQueueItem it : items) {
    System.out.println(it.getId() + " " + it.getStatus() + " " + it.getText());
}

// ② 取消某一条待执行任务（只能取消 PENDING；运行中那条不在队列里，取消返回 false）
queue.cancelItem(session, itemId);

// ③ 把某条提到队首（下一个就执行它）
queue.promote(session, itemId);

// ④ 清空全部待执行，包括已被 drain 暂存的那条；in-flight 不受影响
queue.cancelPending(session);
```

三个语义细节：

- **`cancelItem` / `promote` 都只能作用于 PENDING**。运行中那条已从队列出队并记为 in-flight，不在 `Deque` 里，对它的 id 调用一律返回 `false`。要停运行中那条，用 `engine.interrupt(session)`。
- **`cancelPending` 会递增代际**（`session.queue.generation`）：它之后到达的 `requeueFront`（旧代际的回队请求）会被拒绝，避免「用户清了队列又冒出一条」。但它**不动 in-flight**，所以“清队列”不等于“停任务”。
- **`promote` 没有重排位次的概念**：只有置顶，没有“插到第 3 位”。需要精确排序请 `cancelItem` 后重新 `enqueue`。

**任务在跑时“再插一条”就是普通 `enqueue`**——它不需要任何特殊入口，也不会打扰运行中的任务：

```java
// 任务正在跑，用户又补了一句：直接入队，等当前任务结束后自动续发
int pos = queue.enqueue(session, "跑完顺便把文档也更新一下");
// pos = 队列位置（从 1 计）；-1 = 已满 / 超长 / 扩展已停用
```

这也是 §5.4 场景 A 的降级目标：`steer` 返回 `BOX_FULL` 或 `NOT_RUNNING` 时改走 `enqueue`，语义上就是从「注入当前任务」变成「排在它后面」。

`drainOne` 内部顺序：`tryClaim`（同一时刻只允许一个运行者 poll）→ `poll`（队首出队并置为 RUNNING + 记为 in-flight）→ 执行回调 → **按分支收尾**（见下表）→ `releaseClaim`。

| 分支 | 行为 | 返回值 |
|---|---|---|
| 回调正常返回且 `acknowledgeOnReturn = true` 且 `acknowledge` 成功 | 出队完成 | `true` |
| 回调正常返回、`acknowledgeOnReturn = true`、`acknowledge` 失败（in-flight 身份已变） | `requeueFront`（受代际保护） | `false` |
| 回调正常返回且 `acknowledgeOnReturn = false` | **既不全也不回队**，留给宿主终态处理 | `true` |
| 回调抛异常 | `requeueFront`（受代际保护） | `false` |
| `tryClaim` 失败 / 队首为空 | 不执行，失败时 `releaseClaim` | `false` |

**受控 / 不受控（停用后行为不同）**：

| 类别 | 方法 | `setEnabled(false)` 后 |
|---|---|---|
| 受控 | `enqueue` / `drainOne` | 不再接单、不再续发（返回 `-1` / `false`） |
| 不受控 | `ack` / `cancelItem` / `promote` / `cancelPending` / `pendingSize` 等 | 照常可用——否则停用前已入队的项将永远无法取消 |

三条容易踩的语义：

- `acknowledge` **必须带正确的 item id**——它校验 in-flight 身份，不匹配返回 false；
- `requeueFront` 受**代际**保护：`cancelPending` 会递增 `session.queue.generation`，旧代际的回队请求被拒绝，避免「用户清了队列又冒出一条」；
- `pendingSize` **不含** in-flight，所以容量判定按待执行数算——运行中的那条不占容量。

#### 持久化与重启恢复

队列不用独立文件：投影存在 `getContext().data()` 的 `session.queue.items`（`List<Map>`，**运行中的任务在首位**），任何变更即 `updateSnapshot()` 随会话快照一并落盘。

重启后首次访问队列时 hydrate，`RUNNING` 项**重置为 `PENDING` 并恢复到队首**——上一次进程只是「领取过」，不代表执行完了。只读操作（`pendingSize` / `poll` / `view`）同样会 hydrate，因此重启后队列对只读接口依然可见。

```java
// 启动时探测待恢复的会话
drainSessionQueue(session);   // 只做两件事：判定 busy、然后 drainOne

// 旧版 queue-tasks.json 的一次性迁移（RUNNING 项会重置为 PENDING 并插到队首）
int imported = queue.importItems(session, legacyRows);   // rows 为 Map 列表，超容量自动截断、按 id 去重
```

> **探测不要实例化会话**：`getSession(id)` 是 `computeIfAbsent` 语义，会把会话连全量历史常驻内存。内存会话用 `SessionQueue.hasItems(session)`；只有快照时用 `SessionQueue.hasPersistedItems(context)`（宿主先把快照反序列化成 `FlowContext` 再判定，存储无关）。

#### attrs 运行时契约

以下 key 在 harness 与宿主之间是**一字不改**的约定，任何一侧不得私自重命名：

| key | 内容 |
|---|---|
| `session.queue` | 待执行队列本体（`Deque<SessionQueueItem>`，运行态） |
| `session.queue.generation` | 清空代际（`cancelPending` 递增） |
| `session.queue.claim` | 领取权（同一时刻仅一个运行者） |
| `session.queue.inFlight` | 当前运行中的任务 |

Web 前端的排队编排在浏览器完成，IM 渠道（微信/飞书/钉钉）没有前端，才由本命令承担入队——同一入口不要重复入队。`enqueue` 的 `id` 非空时按 id 去重（含 in-flight），可用于 HTTP 重试幂等。

### 5.3 `/steer`：往正在跑的任务里插一句话

`/steer` 把补充消息注入**当前**任务的工作记忆，由插话拦截器在下一个采样边界消费，**不打断进行中的模型流与工具调用**。

| | `/queue` | `/steer` |
|---|---|---|
| 影响对象 | 当前任务之后的新任务 | 当前任务本轮的工作记忆 |
| 生效时机 | 当前任务结束后 | 下一个推理边界（`onReasonStart`） |
| 任务未运行 | 照常入队，等空闲续发 | 失败（`NOT_RUNNING` / `TURN_CHANGED`） |
| 持久化 | 随会话快照落盘 | **零持久化**，不写 ndjson |
| 容量 | 20 条 | 5 条（`MAX_BOX_SIZE`） |
| 溢出策略 | 拒绝入队并告知 | 返回 `BOX_FULL`，由调用方决定改排队还是直发 |

高频 steer 会加速上下文膨胀，所以邮箱只有 5 格；超出场景应引导用户改用 `/queue`。

#### 装配

```java
SteerExtension steer = new SteerExtension();
engine.addExtension(steer);   // initialize 注册 /steer 命令；configure 把拦截器挂为主代理默认拦截器
```

**装配就这一行，没有任何端口要注入。** 输出走 `AgentEvent`：

- **`steer_applied` → `SteerAppliedEvent`**：插话已注入工作记忆。这是「已生效」的唯一信号，客户端据此清除待生效态并落气泡。
- **`steer_dropped` → `SteerDroppedEvent`**：任务结束时仍有未消费插话。`getQueued()` 已转入 session 队列（客户端应清除气泡），`getFailed()` 未入队且已回填易失邮箱待下次开流重试（客户端应保留并提示尚未排队）。
- **展示与持久化分离**：转 `SessionQueue` 在 harness 内完成，**不看有没有订阅者**。`pushAgentEvent` 在非流式（无 sink）或订阅已取消时静默丢弃——`steer_applied` 无副作用可以丢，但 `steer_dropped` 的排队动作在推事件之前已完成，因此「已接受但永不生效」在任何宿主下都不会发生（CLI / Desktop 也能把残留插话转为排队任务）。
- **宿主零成本接入**：Web 在 `WebEventMapper` 加两个类型分支映射为 `system.steer_applied` / `system.steer_dropped`；不认识的宿主不写任何代码即自然忽略。**不需要实现端口接口、不需要每请求挂载拦截器**。
- **专用构造器只有一个 `()`**：没有注入入口，也就没有「漏传端口」这种陷阱。要整体下线用 `engine.setExtensionEnabled(steer, false)`——它会同时停命令、停拦截器（`steer()` 返回 `DISABLED`），`cancel` 等清理接口不受控。

运行期操作也在这一个实例上：

```java
steer.steer(session, "改用 PostgreSQL 方言");              // 最简：投给当前活跃任务
steer.steer(session, "改用 PostgreSQL 方言", origin);      // 带渠道来源
steer.steer(session, null, null, text, origin, null);   // 全参（与 /steer 命令、/web/chat/steer 同源）
steer.pending(session);                                // 待生效插话快照（按投递顺序，不创建邮箱）
steer.cancel(session, steerId);                        // 取消仍在邮箱中的插话
```

四个方法的相对关系：

| 场景 | 入口 | 返回值 |
|---|---|---|
| 任务在跑，插一句 | `steer(session, text)` | `SteerResult`（`STEERED` 才算入队） |
| 刷新/重连后看还有哪几条未生效 | `pending(session)` | `List<SteerMessage>`（副本，不持锁） |
| 取消其中一条 | `cancel(session, steerId)` | `boolean`，`false` = 已被消费或 id 不存在 |
| 全不要了 | 逐条 `cancel`（无批量入口） | — |

**为什么需要 `pending`**：`cancel` 需要 `steerId`，而 `steerId` 只出现在投递返回值里。没有 `pending` 时，宿主一旦刷新页面或重连，就再也无法取消一条尚未生效的插话——它还会在下一个推理边界被注入，用户“明明取消了却生效”。`pending` 返回的是副本，推理线程同时 `poll` 不会影响遍历，宿主也不该拿它当持久数据。

两参重载把 `runId` / `steerId` / `busyRecheck` 三个高级参数全缺省：`runId=null` = 不比对轮次、绑当前活跃任务，`steerId=null` = 框架生成，`busyRecheck=null` = 仅按 `activeRunId` 判定。它**不降级、不改语义**，返回的仍是同一个 `SteerResult`。两参版的来源缺省为 `InputOrigin.NONE`，要保留渠道身份用三参版。

命令只做投递，**不做降级**：邮箱满、任务未运行、轮次已切换时如实告知原因，由用户自行决定改排队还是直接发送（与 `/web/chat/steer` 的 409 纯语义对齐）。

```java
// runId 传 null = 绑定「当前活跃任务」（IM 场景没有 runId 概念，直接信任后端权威值）
// busyRecheck 传 null = 仅按 activeRunId 判定
SteerResult r = SteerInterceptor.steer(session, null, null, text, origin, null);
```

| 状态 | 含义 |
|---|---|
| `STEERED` | 已入邮箱，等待注入 |
| `NOT_RUNNING` | 当前没有正在执行的任务 |
| `TURN_CHANGED` | 调用方所见的 runId 与当前活跃 runId 不一致（Web 防跨任务错投） |
| `BOX_FULL` | 邮箱已满（5 条） |
| `DUPLICATE_STEER_ID` | 同 steerId 已在邮箱中 |
| `EMPTY_TEXT` / `TEXT_TOO_LONG` | 文本为空 / 超过 4096 字符 |
| `INVALID_STEER_ID` | steerId 超过 128 字符 |
| `DISABLED` | 扩展/拦截器已 `setEnabled(false)`，本次未入队 |

#### 拦截器挂载：扩展已代劳，无需请求级挂载

`SteerExtension.configure` 会把拦截器挂到**主代理的默认拦截器列表**，宿主无需每请求 new + add。顺序安全：拦截器容器按 Class 去重（`LinkedHashMap<Class, RankEntity>`），AgentFactory 先挂压缩/StopLoop、后轮到 extension 的 configure，因此 steer 拦截器天然排在上下文压缩之后。

**不再需要请求级挂载**：输出走 `AgentEvent`，路由信息由事件消费方（如 Web 的 `WebEventMapper`，构造时就持有工作区）自带，宿主无需在 `session.attrs()` 上打任何标，也不需要每请求 `new SteerInterceptor(...)`。

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

1. **事件只是展示，持久化在 harness 内**。`SteerInterceptor#enqueueIntoSessionQueue` 先完成转排队、再推 `SteerDroppedEvent`。`pushAgentEvent` 在非流式或订阅已取消时静默丢弃，顺序颠倒就会在无订阅者时丢插话。
2. **不覆写 `getText()`**。这两个事件不属于文本流；一旦给出文本，按文本兜底渲染的宿主（ACP 的 `concatMap` 收尾分支、Desktop 的 frame 转换）会把插话内容顺手打进对话。需要展示的宿主必须显式按类型分支。
3. **映射即接入**。soloncode 的做法是在 `WebEventMapper` 加两个 `instanceof` 分支（→ `system.steer_applied` / `system.steer_dropped`），其余宿主不写代码即自然忽略。**harness 不认识任何宿主类型**，也没有端口接口可实现。

#### 三条注入守卫

1. **首轮不注入**（`turnCount <= 1`）——原始 prompt 必须先被采样，不篡改初始意图；
2. **尾部 tool_calls 未闭合不注入**——HITL 挂起恢复的窄窗里插 user 消息会打断 `tool_use` / `tool_result` 配对，部分模型直接报错；等 `ActionTask` 补齐结果后自然恢复；
3. **无悬挂**——`onAgentEnd` 检查残留并交给 `SteerInterceptor#enqueueIntoSessionQueue` 转排队，绝不允许「已接受但永不生效」。

注入形态：`ChatMessage.ofUser("[用户实时补充] " + text)`，metadata 标记 `source=steer`。不追加 systemPrompt 说明——前缀本身已足够表意。

#### 运行边界

`activeRunId` 在 `onReasonStart` 才写入。所以任务刚被受理、尚未进入推理边界时，steer 会返回 `NOT_RUNNING`（IM 传 null）或 `TURN_CHANGED`（Web 带 runId）——这不是 bug，是「此刻还没有可插话的推理现场」。

插话**零持久化**：不进 `session.addMessage`、不占 `sessionWindowSize`、不会在后续任务里被当作初心重新入场；刷新页面后插话气泡即消失（刻意取舍，展示层由前端在收到 `steer_applied` 时就地渲染进当前流式气泡内部）。

### 5.4 停 / 改 / 重排：干预的两个互斥场景

goal / queue / steer 各自只解决一个方向。真实场景里用户往往想「停」「改」「重排」，但**这三者不是一条串行管道**：steer 只服务于“任务还在跑”的窗口，`interrupt` 之后那个窗口就关了。正确做法是先判断现在处于哪个阶段，再选对应的入口：

```java
// 场景 A：任务还在跑，改主意了——不打断，直接插话（首选）
SteerResult r = steer.steer(session, "先别改了，回到上一个方案");
if (r.isNotRunning()) {                 // 判定窗口内任务正好结束：降级为排队，而不是丢弃
    queue.enqueue(session, "回到上一个方案并跑一遍测试");
}

// 场景 B：必须停下来重来——先停，再排新任务
if (engine.interrupt(session)) {        // 停（连带清空队列）
    queue.enqueue(session, "回到上一个方案并跑一遍测试");   // 重排
    // 此处不要再调 steer：interrupt 已清掉 activeRunId，只会拿到 NOT_RUNNING
}
```

两条铁律：

- **steer 与 interrupt 互斥**：`activeRunId` 在 `onReasonStart` 写入、在任务结束时清掉；`interrupt` 之后它必然已不在，此时 `steer` 恒为 `NOT_RUNNING`。想“先停再改”只能走 queue。
- **interrupt 会清空队列**：场景 B 里若队列里原本还有别的任务，那些也会一起丢——这是刻意的（见下）。

#### `engine.interrupt(session)`

```java
boolean engine.interrupt(@Nullable AgentSession session);
```

`true` = 确实中断了一个正在执行的任务；`false` = 当前没有在跑的任务（会话为 `null` 也返回 `false`）。

它与此前的 `/interrupt` 命令**同源**（都调 `SessionInterrupt.interrupt`），内部四步顺序是语义的一部分：

| # | 动作 | 为何要这个顺序 |
|---|---|---|
| ① | 清排队（`SessionQueue.cancelPending`，含已被 drain 暂存的项） | **必须早于停流**：停流后宿主常在终态回调里续发队首，顺序颠倒会把刚停掉的任务重新推起来 |
| ② | 取消 `SessionRuns` 登记的全部运行句柄 | 无活跃句柄时直接返回 `false`，不写取消消息（但**取消代数仍会推进**，见下） |
| ③ | 补写取消消息（带 `META_RUN_ID`） | 让前端把气泡挂到正确轮次 |
| ④ | `updateSnapshot()` | 中断时 agent 收尾被跳过，trace 与取消消息需一并持久化 |

> **返回 `false` ≠ 什么都没发生**：取消代数（`SessionRuns.cancelCount`）依然推进。这是刻意的——
> 宿主可能刚把订阅调度到别的线程、句柄尚未登记，此刻的 `interrupt` 必须能拦住那条「随后才到」的流。
> 不要用 `false` 去判断「用户这次点击无效」，它只表示「点击时没有已登记的活跃流」。

> **行为变更点**：早期 `/interrupt` 只停当前流、队列里排着的任务之后还会继续跑；现在「停」是彻底的（队列一并清空）。这正是 `//todl: SessionQueue.cancelPending(session)` 那条旧待办的本意——「停了还在跑」比「停得干净」更违反直觉。

宿主已有的停止入口可以改成调它，不必再拼 `"/interrupt"` 文本，也不必关心句柄存在哪里：

```java
// 之前：session.attrs().remove("disposable") → dispose() → 自己补消息与快照
// 现在：
if (engine.interrupt(session)) { /* 通知前端已停止 */ }
```

> **句柄登记已上收到框架**：`ReActRequest#stream()` 订阅时会自动登记到 `SessionRuns`，
> 结束时按值注销，宿主<b>不需要（也不应该）</b>往 `session.attrs()` 里写任何 key。
> 早期版本要求宿主写 `attrs("disposable")`，那个约定已废弃；现在写它不会生效，也不会报错。

#### 三者的职责边界

| 能力 | 入口 | 语义 | 前置条件 | 任务未运行时 |
|---|---|---|---|---|
| 停 | `engine.interrupt(session)` | 立即终止当前流，**并清空队列** | 有在跑的流（`SessionRuns.isBusy(session)`） | 返回 `false`，不写任何消息（但推进取消代数） |
| 改 | `steer.steer(session, text)` | 注入当前任务的工作记忆，下一采样边界生效 | `activeRunId` 已建立（首轮推理开始后） | 返回 `NOT_RUNNING` / `TURN_CHANGED` |
| 重排 | `queue.enqueue(session, text)` | 登记一条待执行，等空闲后续发 | 无 | 照常入队 |

#### 运行期操作速查

三个能力各自的「运行中能做什么」分散在 §5.1/5.2/5.3，这里汇总成一张表，方便宿主对着写控制面板：

| 能力 | 能看什么 | 能改什么 | 入口 |
|---|---|---|---|
| goal | `goal.state(session)` / `goal.running(session)` / `goal.toTaskMap(...)` | 暂停 / 恢复 / 改配置 / 清除 | `pause` · `resume` · `updateAndReschedule` · `clear`（§5.1） |
| queue | `queue.view(session)`（运行中在首位） | 取消单条 / 置顶 / 清空 / 再入队 | `cancelItem` · `promote` · `cancelPending` · `enqueue`（§5.2） |
| steer | `steer.pending(session)` | 取消单条（无批量） | `steer` · `cancel`（§5.3） |
| 停 | — | 停流 + 清队列 | `engine.interrupt(session)`（本节） |

三个容易搞混的边界：

- **goal 的暂停与 queue 的清空不会停当前这轮**。它们只影响「下一轮还跑不跑」：goal 的 `pause` 是状态机转 `PAUSED`，当前轮内已经在执行的工具调用不会被打断；queue 的 `cancelPending` 不动 in-flight。真正“现在立刻停”只有 `engine.interrupt`。
- **steer 没有“改”的能力**。已入邮箱的插话只能取消后重新投递，不支持就地编辑——文本不可变，且邮箱里若已满 5 条，取消一条才能再投一条。
- **goal 与 queue/steer 不同层**。goal 是会话级持续轮次，queue/steer 服务于单次任务；一个会话可以有 goal 同时又有排队任务，但 goal 的每轮也会走 `bridge.isBusy` 守卫，不与宿主正在跑的任务抢。

从这张表可以直接推出两个宿主要自己处理的边界：

- **“改”的前置条件比“停”窄**：“停”只需流句柄存在，“改”需要已进入推理边界。所以任务刚被受理、模型还没开口的那一小段时间，interrupt 能成功而 steer 会失败——这不是 bug。
- **“停”与“重排”有副作用叠加**：`interrupt` 里的 `cancelPending` 会把队列一起清掉。若宿主想“只停当前、保留已有排队”，不要调 `engine.interrupt`，只释放那一条流（但那就回到了手动复刻内部约定的老路，需自行权衡）。

三者都**不自己开流、不做跨能力降级**：steer 邮箱满或任务未运行时如实返回状态码，要不要改走 queue 由调用方决定（`SteerMessage` 的「已接受但永不生效」由 `SteerInterceptor#enqueueIntoSessionQueue` 在 harness 内兜底，不在此列）。场景 A 里的那次降级就是宿主侧完成的，不是框架代做。

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

### 6.2 用引擎装配好的请求执行

```java
InputOutcome outcome = engine.handle(HarnessInput.of(session, "@explore 找一下限流相关的实现")
        .selectedModel("deepseek"));

outcome.getRequest().options(o -> {
    o.toolContextPut(HarnessEngine.ATTR_CWD, "/data/work/sub-project");   // 动态换工作区
}).stream().blockLast();
```

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

```java
// 一行式：复用 general 定义改写（面向 LLM 的 generate 工具走的就是这条）
engine.getGenerateTalent().generate(
        "code_reviewer",                        // name（仅字母数字_-）
        "代码审查专家",                          // description
        "你是一位严格的代码审查专家，只指出真实缺陷。",   // systemPrompt
        Arrays.asList("read", "grep", "code"),  // tools（严禁全选）
        null,                                   // skills
        false,                                  // saveToFile
        engine.getWorkspace());                 // __cwd（saveToFile 为 true 时落盘到 <cwd>/.soloncode/agents/）

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
//   task      —— 委派单个子代理，串行
//   multitask —— 并行委派多个子代理，并行度按每个任务实际路由到的模型分别限流
//                生效上限 = 模型配置 concurrency（0=未设置，配了就用它）?: subagentDefaultConcurrency（默认 5，取值 1~10）
//                不存在「不限制」：生效上限恒为有限正整数（具体理由见下方注记）
//                模型配了就以模型为准；模型没配才用默认值。超限不报错，在调用线程排队，有空位就提交
//                排队时「谁的目标模型有空配额就提交谁」，低配额模型不会挡住其他模型的任务
engine.setSubagentEnabled(true);   // 默认 true
engine.setSubagentDefaultConcurrency(5);   // 子代理任务默认并发上限（模型未配置时生效）

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
> （超出保留头部并标注原始长度）——否则单个超长的子代理输出就能灌爆父会话上下文。
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

`ToolName` 是当前正解（`@Preview("4.0.4")`），`ToolPermission` 已 `@Deprecated` 但仍可用。

### 7.2 运行时调整

```java
engine.allowTool("bash");            // 触发主代理重建
engine.disallowTool("write");
engine.toolPermissionReset(tools, disallowedTools);
```

---

## 八、配置项参考

### 8.1 构造期

> 与 §1.2 的 builder 配置表同源，此处为完整语义参考。

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
| `compressionModel(String)` | `null` | ~~已废弃（4.0.4）~~：压缩复用主模型 |
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
| `sandboxAllowUserHome(Boolean)` | `true` | 允许访问用户主目录 |
| `sandboxSystemRestrict(Boolean)` | `false` | 禁止访问系统路径 |
| `hitlEnabled(Boolean)` | `false` | HITL 拦截 |
| `subagentEnabled(Boolean)` | `true` | 子代理功能 |
| `bashAsyncEnabled(Boolean)` | `false` | Bash 异步执行 |
| `apiRetries` / `mcpRetries` / `modelRetries` | `3` | 三类重试 |
| `subagentDefaultConcurrency(Integer)` | `5` | 子代理任务默认并发上限，取值 `1~10`（`0`=未设置取默认 5，超 10 按 10，负数报错），**不存在「不限制」**；仅在模型未配置 `AiConfig#concurrency` 时生效（模型配了以模型为准）。超限任务排队，不报错。`task` 与 `multitask` 均取槽（信号量引擎级、跨会话共享）。旧名 `multitaskMaxTasks` / `multitaskMaxConcurrency` 已 `@Deprecated` |
| `commandPrefix(String)` | `"/"` | 支持多字符 |
| `agentPrefix(String)` | `"@"` | 支持多字符 |

### 8.2 运行期

```java
engine.setSandboxEnabled(false);
engine.setDefaultModel("deepseek");      // 触发主代理重建
engine.addModel(new ChatConfig(...));
engine.removeModel("gpt-4");

engine.interrupt(session);               // 中断当前任务（连带清队列），true = 确实停了
engine.resume();                         // 恢复最近一个工作记忆（旧名 prompt()，已 @Deprecated）

// 扩展开关：true/false/null（null = 不改变）；会先传播给子组件，再重建主代理
engine.setExtensionEnabled(goalExtension, false);

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
public interface HarnessExtension {
    /** 引擎级初始化：服务装配完成后、首次懒加载主代理前，仅回调一次 */
    default void initialize(HarnessEngine engine) { }

    /** 代理级配置：每次构建代理（含主代理）时触发 */
    void configure(HarnessEngine engine, String agentName, ReActAgent.Builder agentBuilder);
}
```

```java
engine.addExtension(new HarnessExtension() {
    @Override
    public void initialize(HarnessEngine engine) {
        // 注册挂载、MCP/LSP、权限规则等引擎级资源
    }

    @Override
    public void configure(HarnessEngine engine, String agentName, ReActAgent.Builder builder) {
        if (AgentDefinition.AGENT_MAIN.equals(agentName)) {
            builder.defaultInterceptorAdd(myInterceptor);
        }
    }
});
```

> 注意：`addExtension` 会触发一次 `initialize`，此刻引擎已装配完毕，任意引擎方法都可安全调用。

---

## 十、安全机制

### 10.1 三套策略

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

详见 [`docs/hitl.md`](docs/hitl.md)。

### 10.2 宿主侧拿到审批请求

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
├── download/           # 下载缓存
└── channels/           # IM 通道配置
```

派生方法：`getHarnessSessions()` / `getHarnessSkills()` / `getHarnessAgents()` / `getHarnessCommands()` / `getHarnessMemory()` / `getHarnessDownload()` / `getHarnessChannels()`。

---

## 十二、模块结构

### 12.1 自有源码（76 个类）

```
org.noear.solon.ai.harness
├── HarnessEngine                 ★ 引擎（Builder + 编排 + input 入口）
├── HarnessOptions                运行时配置（包私有）
├── HarnessExtension / AbsHarnessExtension   扩展 SPI
│
├── input/                        输入装配层
│   ├── HarnessInput              ★ 一次输入的完整上下文
│   ├── InputOutcome              ★ 输入处置（四态，不含流）
│   ├── InputAttachments          附件装配结果（多媒体块 + 文件引用；落盘由宿主负责）
│   ├── InputOrigin               渠道来源身份（渠道 / 发送者 / 返回地址 / 入站关联键）
│   ├── ParsedCommand             前缀感知的命令解析
│   ├── AgentMentions             提及解析（Options 承载三端差异）
│   ├── BufferedCommandContext    缓冲式回执（无自定义工厂时的默认实现）
│   └── CommandDispatcher / CommandContextFactory / BufferedCommandContextFactory
│
├── agent/                        代理层
│   ├── AgentDefinition / AgentFactory / AgentManager
│   ├── TaskTalent                task / multitask 委派
│   ├── GenerateTalent            运行时创建子代理
│   └── ToolName                  工具名枚举
│
├── command/                      命令框架
│   ├── Command / CommandContext / CommandRegistry
│   ├── MarkdownCommand(Loader)   Markdown 自定义命令
│   └── builtin/                  帮助、清理、模型、队列、插话、目标等
│
├── hitl/                         干预策略（Bash / Write / Web）
├── permission/                   权限引擎（Rule / Context / Decision）
├── session/                      会话级能力
│   ├── queue/                    排队
│   ├── steer/                    插话
│   ├── goal/                     目标循环（含 GoalSpec 参数对象与校验器）
│   └── interrupt/                中断（SessionInterrupt：命令与 engine.interrupt 的唯一实现）
├── runtime/                      执行桥（ExecutionBridge / RoundResult / ErrorClassifier）
├── channel/                      IM 通道接口
└── util/                         追踪工具
```

### 12.2 依赖包（能力来源）

| 依赖 | 提供 |
|---|---|
| `solon-ai-agent` | `ReActAgent` / `ChatModel` / 压缩与 HITL 拦截器 |
| `solon-ai-talent-cli` | `TerminalTalent`（沙箱文件与命令）、`SkillTalent`、`TodoTalent`、`ClockTalent`、`MountManager` |
| `solon-ai-talent-code` | `CodeTalent`（技术栈识别 / CODE.md） |
| `solon-ai-talent-web` | `WebsearchTalent` / `WebfetchTalent` / `CodeSearchTalent` |
| `solon-ai-talent-lsp` | `LspManager` / `LspTalent` |
| `solon-ai-talent-memory` | `MemoryTalent` / `MemorySolutionProvider` |
| `solon-ai-talent-gateway` | `McpGatewayTalent` / `OpenApiGatewayTalent` |
| `solon-lib` | IoC / 配置 / 生命周期 |
| `solon-serialization-snack4` | JSON 序列化 |

---

## 十三、常见坑

1. **裸引擎没有命令**——`/help` 会返回 `TASK`。测试命令必须先 `getCommandRegistry().register(...)`；注册后**不需要**配 `commandContextFactory`，默认 `BufferedCommandContextFactory` 已经能把回执交给 `getCommandOutput()`。
2. **HITL 续流必须显式，但一次调用就够**——`Kind.HITL` 不携带请求。三端统一写 `.hitl(action, callId).resume(true)`，引擎自检「决策命中 + 本批齐备 + 显式要求」三项，全成立返回 `TASK`，否则返回 `HITL`（并携带 `isHitlApplied()` / `getPendingHitlCount()` 供宿主判断）。续流时**不要**传正文。
3. **HITL 续流要用 `outcome.getAgentName()`**——它是「本轮生效代理」（显式入参 → `CTX_AGENT_SELECTED` 兜底）。换回主代理会取不到挂起的 `tool_calls`，续跑空转。
4. **`@` 提及必须带空白边界**——`@bash 内容` 有效，`@bash内容` 走普通文本。
5. **有附件时不识别命令**——避免 `/path/to/file.png` 之类被误判；带附件的 `/cmd` 按普通消息处理，附件正常落盘。
6. **`options(Consumer)` 是累加语义**——`getRequest()` 之后再追加 `options` 是安全的叠加。
7. **`getRequest().stream()` 是冷流**——何时订阅、在哪个线程订阅由宿主决定；需要切换线程请自行 `subscribeOn`。
8. **`build()` 只校验 `sessionProvider`**——缺模型要到首次 `handle` 才报 `chatModel is required`。
9. **HITL 默认只绑了 `bash`**——`Write` / `Web` 两套策略需自行 `onTool(...)` 绑定，否则不生效且不报错。
10. **新建子代理要注册**——`AgentDefinition` 构造完必须 `getAgentManager().addAgent(def)`，否则 `explicitAgent("xxx")` 回落主代理。
11. **`/goal` 只注册命令不会跑**——用 `GoalExtension.builder(workspaceId).taskExecutor(...)` + `addExtension` 一次装配（内部建 bridge / engine / scheduler / 命令）。缺 `taskExecutor` 时每轮都被守卫静默跳过（日志 `execution bridge unavailable`）；纯 JUnit 无 Solon 容器时须 `.scheduler(null)` 关闭调度（`JobManager.getInstance()` 会 NPE）。
12. **`/queue` 入队不等于会发送**——宿主必须在任务终态调 `queue.drainOne(...)`（`QueueExtension` 实例上）；并且不要自己 `poll`，claim / ack / requeue 全在 drainOne 内，重复实现会丢任务。
13. **`/steer` 不挂拦截器就永不生效**——报 `STEERED` 只代表已入邮箱。`SteerExtension` 已代劳默认挂载，**无需请求级挂载**：投递路由已改由 session 承载（宿主在轮次边界把工作区写进 `session.attrs()`），默认实例即能定位正确工作区；实现方不写路由也不丢数据（dropped 仍排入 session 队列，只是不广播）。
14. **`GoalScheduler` 的 job 名必须带工作区前缀**——`JobManager` 是 JVM 单例而会话 ID 不全局唯一，不加前缀会让多工作区的同名会话互相覆盖（`builder(workspaceId)` 已内置）。
15. **不要给零旋钮的能力套 builder**——goal 有 8 个可配点才用 builder；queue 零旋钮（**只有无参构造**）、steer 一个端口，用无参/单参构造即得。伪一致性只会增加仪式感。
16. **`GoalExtension.resume()` 含 `runNow=true`，恢复期别用**——服务重启/工作区恢复要「静默挂上、等下一轮」时用 `resumeScheduleOnly(sessionId)`，否则每个活跃 goal 会立即多跑一轮。（注意与 `HarnessEngine.resume()` 同名但无关：后者是「恢复最近一个工作记忆」的请求入口，即 `prompt()` 的新名字。）
17. **改 goal 配置不要整段交给门面**——`pause` → `engine.interrupt(session)` → 等轮次停 → `update` 这条链里，「何时中断」仍是宿主的运行时职责；门面的 `updateAndReschedule` 只覆盖后半段（update + 时长 commit + resume + schedule）。把它整段吞进去会让旧轮次继续跑并回写覆盖刚改的配置。
18. **`engine.interrupt()` 会连带清队列**——它不是「只停当前流」。早期 `/interrupt` 保留队列，现在「停」是彻底的（这也补上了 `SessionQueue.cancelPending` 那条旧待办）。若你需要「停当前、留队列」，只停药那一条流，别调 `engine.interrupt`。
19. **`outcome.call()` / `outcome.stream()` 只在 `TASK` 上有意义**——它们在 `REPLY` / `HITL` / `IGNORED` 上会立即失败（前者抛 `IllegalStateException`、后者返回 `Flux.error`）。不要用它们取代 `switch (kind)`：先判 kind 再取流，顺序不能颠倒。
20. **`GoalSpec` 的负数会被夹到 0**——`maxTokens(-1)` 不是「报错」而是「不限制」；命令行的 `--max-tokens:-1` 也一样（`Long.parseLong` 通过后交给 `GoalSpec` 再夹到 0）。想表达「非法参数」请在调用前/解析层自己校验。
21. **三个扩展都没有「只关命令」的开关**——`QueueExtension` 只有无参构造，`SteerExtension` 只有 `(Supplier)` / `()`，`GoalExtension` 的构造器私有且 builder 无 `commandEnabled`；`initialize` 里一律 `register`（幂等）。要下线只有 `engine.setExtensionEnabled(ext, false)` 一条路，它是「命令 + 门面」一起关；且**未加入引擎的扩展会先被加入再直接返回**，初始状态请在 `addExtension` 前用 `ext.setEnabled(false)`。
22. **`interrupt` 之后不要再 `steer`**——`activeRunId` 已随任务结束被清掉，此时只能拿到 `NOT_RUNNING`；「停下来重来」请直接用 `queue.enqueue`。「运行中改主意」才用 steer，两者是互斥场景而非串行步骤。
23. **`.scheduler(null)` 之后调度类方法全是空操作**——`hasScheduler()` 为 `false`，`scheduleNow` / `resumeScheduleOnly` / `unschedule` / `stopAll` 静默返回。纯测试宿主排查「调了 `scheduleNow` 却没跑」先看这里。
24. **`GoalExtension.of(...)` 不建命令也不建调度**——它是存量入口（未标 `@Deprecated`），适合宿主已有 `GoalEngine`、只借 Talent 与门面的场景；`getGoalScheduler()` 为 `null`，与 builder 形态的能力不等价。
25. **命令文本与门面调用是同一个动作，别把两者的返回值当一回事**——`engine.handle(HarnessInput.of(s, "/queue 跑测试"))` 得到的是 `Kind.REPLY` + 人读回执，`queue.enqueue(s, "跑测试")` 得到的是位置 `int`（`-1` = 失败）。宿主要程序判定就走门面，要现成文案就走文本路径；**两条都走会入队两次**（命令路径无幂等保护）。
26. **`steer` 取消了也可能已经生效**——`cancel(session, steerId)` 返回 `false` 不代表出错，只代表那条已被推理线程 `poll` 出去了（已经或即将注入）。同理 `pending(session)` 是快照，读到的下一瞬就可能被消费；宿主不要用它做“一定还能取消”的保证，只能作为 UI 展示依据。

---

> **测试依赖**：`solon-test`（test scope）
> **编译辅助**：`lombok`（provided scope）
