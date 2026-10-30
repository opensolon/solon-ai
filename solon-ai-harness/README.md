# solon-ai-harness — AI 代理编排引擎

## 简介

`solon-ai-harness` 是 Solon AI 的 **Agent 运行时与编排容器**：把「ReAct 引擎 + 一组 Talent 能力 + 子代理体系 + 命令系统 + 安全策略」装配成一个可直接跑起来的 Coding Agent。

它面向的使用者是**宿主应用**（CLI、Web、桌面、IM 网关）。宿主只需要做三件事：

1. 构造 `HarnessEngine`（给工作区、给模型、给工具权限）；
2. 把用户输入交给 `engine.handle(...)`；
3. 消费 `InputOutcome`——按 `getKind()` 一次 `switch` 分派；任务流取 `getRequest().stream()`，回执取 `getCommandOutput()`。

命令识别、子代理解析、附件装配与落盘（由宿主完成）、Prompt 构建、请求装配，在一次调用内串联完成。

### 怎么读这份文档

| 你想做什么 | 读哪里 |
|---|---|
| 先跑起来看看 | 第一章（五分钟上手）→ **第三章 3.1**（30 行骨架） |
| 接一个真产品 | **第三章**全部（两段式、事件渲染、HITL 续流、四端时序） |
| 加命令 / 子代理 | 第四章、第五章 |
| 调优与定制 | 第六～九章（权限、配置、扩展、安全） |
| 查结构 / 躲坑 | 第十一章（模块图）、第十二章（常见坑） |

> **全文档只推荐一条取流路径**：`outcome.getRequest().stream()`。不存在「回执 + 任务合成一条流」
> 的便捷方法，原因见 §3.4 的四端时序对照。

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
        }))
        .defaultModel("deepseek")
        .toolsAdd(ToolName.TOOL_ALL_FULL)                           // 主代理工具权限
        .build();
```

> `build()` 只硬校验 `sessionProvider`。没配模型不会在 `build()` 报错，而是在**首次 `handle`**
> 时由 `ReActAgentConfig` 抛 `chatModel is required`。

### 1.3 跑第一轮对话

```java
AgentSession session = engine.getSession("cli");

InputOutcome outcome = engine.handle(HarnessInput.of(session, "看下当前项目结构"));
outcome.getRequest().stream().blockLast();   // 事件流；也可 subscribe 逐条渲染
```

取最终答复文本：

```java
String answer = engine.handle(HarnessInput.of(session, "总结一下这个仓库"))
        .getRequest()
        .call()                       // 注意：call() 声明 throws Throwable
        .getContent();
```

> 上面两段是**最简写法**，只为验证环境可用。真产品请直接用 §3.1 的骨架——
> 它把 `handle` 与 `InputOutcome` 拆成两段并处理了四种 `Kind`，能直接长成宿主外壳。

---

## 二、核心 API：`handle` + `InputOutcome`

### 2.1 唯一的输入入口

```java
// HarnessEngine —— 三个重载，实际只有最后一个做事
public InputOutcome handle(AgentSession session, String text);                       // 便捷：无命令上下文工厂
public InputOutcome handle(HarnessInput input);                                      // 便捷：传 null 工厂
public InputOutcome handle(HarnessInput input, @Nullable CommandContextFactory factory);  // 唯一入口
```

一次 `handle(...)` 内部按固定顺序做七件事，顺序本身是语义的一部分：

| 步骤 | 动作 | 说明 |
|---|---|---|
| 1 | HITL 决策 | **优先于空输入判空**：审批请求通常不带文本 |
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
        .model("deepseek")                             // 显式模型
        .agent("explore")                              // 选择器兜底（提及未出现时）
        .attachments(atts)                             // 多媒体项 + 文件引用
        .route("dingtalk", "u1", "chat42", "msg7")    // IM 回复路由
        .trimContent(false);                           // 剥提及后不去空白
```

`InputAttachments` 两条通道：`media(MediaBlock)` 作为多模态输入进入消息内容（块支持 base64
与 url 两种形态，已落盘时把相对路径写在 `block.filePath(...)` 上）；`reference(path)` 以 `[附件: path]`
前缀告知模型，由 Agent 自行读取。**引擎不落盘**——由宿主保存到工作区后再交来结果。

| 方法 | 用途 |
|---|---|
| `model(String)` | 显式模型，优先于会话级选择，再回退默认模型 |
| `agent(String)` | 选择器兜底子代理 |
| `explicitAgent(String)` | 请求体显式指定，**优先级最高**（桌面端用） |
| `attachments(InputAttachments)` | 附件装配结果（多媒体块 + 文件引用） |
| `media(MediaBlock)` | 追加多媒体块（便捷写法） |
| `reference(path)` | 追加文件引用（便捷写法） |
| `agentNameValidator(Predicate)` | 子代理名格式校验 |
| `trimContent(boolean)` | 剥提及后去首尾空白（桌面端需要） |
| `route(...)` | source / sourceUserId / replyTarget / messageId |
| `hitl(action, callId)` + `hitlOutput(text)` | 审批决策与结果改写 |
| `commandAware(boolean)` | 是否把输入当命令识别（默认 `true`） |
| `mentionAware(boolean)` | 是否解析开头 `@xxx`（默认 `true`） |
| `resume(boolean)` | 续流模式（默认 `false`） |

三个容易踩的开关：

- **`commandAware(false)`**——文本来源不是用户手输时使用（定时任务提示词、已受理的排队输入、命令内部自造提示词）。这些即使以 `/` 开头也不应执行。
- **`mentionAware(false)`**——文本由程序生成时使用（如 goal 的 objective）。即使以 `@xxx` 开头也原样交给代理，不被当作提及剥离。
- **`resume(true)`**——本次不产生新用户输入，只按会话中挂起的 `tool_calls` 续跑。文本与附件都被忽略。

### 2.3 `InputOutcome`：处置态

```java
switch (outcome.getKind()) {
    case TASK:    // 有可执行的任务（命令触发的也在 TASK，用 isCommand() 区分来源）
        if (outcome.hasReply()) {
            render(outcome.getCommandOutput());   // 命令回执（若有），任务继续跑
        }
        outcome.getRequest().stream().subscribe(e -> render(e));
        break;
    case REPLY:   // 只有回执：命令已执行，或命令在本渠道不可用
        render(outcome.getCommandOutput());
        break;
    case HITL:    // 决策已写入，续流时机由宿主决定
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
| `HITL` | 决策已写入，续流时机由宿主决定 |
| `IGNORED` | 无事可做 |

| 成员 | 说明 |
|---|---|
| `getKind()` | 上述四态（一个值对应一个动作） |
| `isCommand()` / `getCommandName()` / `getCommandOutput()` / `getCommand()` | 命令事实与回执 |
| `hasRequest()` / `hasReply()` | 有无任务 / 有无回执（派生判定，便捷入口） |
| `getRequest()` | 装配好的 `ReActRequest`（无任务时为 null） |
| `getPrompt()` | 装配好的 `Prompt`（含附件前缀与图片块；续流时为 null） |
| `getAgentName()` / `getModelName()` | 生效的子代理 / 模型（HITL 态下为 `resolveHitlAgent` 解析结果） |

**三条刻意不做的事**：

1. **不提供「回执 + 任务合成一条流」的便捷方法**——真实宿主对两者顺序的要求各不相同：CLI 由命令即时写终端（合成会重复打印）、Web 要求「流门复位早于回执」否则回执被前端丢弃、桌面要求序号回放与 HITL 拦截器重挂、ACP 需要 `takeWhile` 取消判定。任何替宿主拼好的流都得被拆开重拼，反而遮住了必须由宿主掌握的时序。取流只有一条路径：`getRequest().stream()`。
2. **不内置 `subscribeOn`**——调度权归宿主。Web 端必须先注册 `Disposable` 再订阅，否则忙碌判定会出现空闲窗口、同一会话开出第二条流。
3. **不对 HITL 自动续流**——三端续流时机不同（Web 要等整批审批完成、桌面要重挂拦截器、CLI 是多轮循环）。续流永远显式：`handle(HarnessInput.of(session, null).resume(true)...)`。

---

## 三、完整示例：`handle` + `InputOutcome` 两段式（端到端）

前两章是零件清单，本章把它们拼成能跑的宿主。**两段式**是理解本模块的主线：

| 段 | 谁做 | 做什么 | 是否启动推理 |
|---|---|---|---|
| 第一段 | `engine.handle(input)` | HITL 决策 → 续流分流 → 空输入判空 → 命令识别执行 → 子代理解析 + 附件装配 → Prompt 构建 → 请求装配 | **否**，全部同步完成 |
| 第二段 | 宿主读 `InputOutcome` | 按 `getKind()` 选动作；只有 `TASK` 才订阅 `getRequest().stream()` | 是，时机与线程由宿主决定 |

第一段**永不启动推理**。这正是宿主能在两段之间插入自己准备动作（注册忙碌标记、复位流门、开 SSE 响应头、挂取消钩子）的原因，也是引擎不替宿主开流的原因。

### 3.1 最小骨架：30 行跑通一轮

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
            case REPLY:                      // 只有回执
                System.out.print(outcome.getCommandOutput());
                break;
            case HITL:                       // 决策已写入，续流时机由宿主决定
                System.out.println("[等待审批] 生效代理=" + outcome.getAgentName());
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

> `build()` 只硬校验 `sessionProvider`。「`chatModel is required`」是**首次 `getMainAgent()`** 时由 `ReActAgentConfig` 抛出——所以 `modelAdd` / `defaultModel` 缺失不会在 `build()` 报错，而是在第一次 `handle` 报错。

### 3.2 完整宿主：四态都处理到位

补上命令回执、附件、子代理、事件渲染与 HITL 续流，就是一个能直接接产品外壳的宿主。

```java
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
    }

    /** 一次完整输入：附件由宿主先落盘，再交给引擎装配 */
    public void send(String text, InputAttachments attachments, String selectedAgent) {
        // ===== 第一段 =====
        HarnessInput input = HarnessInput.of(session, text)
                .attachments(attachments)      // 多媒体块 + 文件引用
                .agent(selectedAgent)          // 选择器兜底；正文里的 @xxx 优先
                .trimContent(true);

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

    /** HITL 决策已写入会话：整批审批完成后才续流 */
    private void onHitl(InputOutcome outcome) {
        List<HITLTask> pending = HITL.getPendingTasks(session);
        if (pending.isEmpty()) {
            resume(outcome.getAgentName(), outcome.getModelName());
        } else {
            pending.forEach(t -> onText("\n[待审批] " + t.getToolName() + " " + t.getArgs()));
        }
    }

    /** 提交一条审批决策（action 为 "approve" 时其余值视为拒绝） */
    public void decide(String callUuid, String action, String note) {
        InputOutcome outcome = engine.handle(HarnessInput.of(session, null)  // 审批通常不带文本
                .hitl(action, callUuid)
                .hitlOutput(note));          // 可选：改写工具返回值
        onHitl(outcome);
    }

    /** 续流：不产生新用户输入，按挂起的 tool_calls 继续跑 */
    private void resume(String agentName, String modelName) {
        engine.handle(HarnessInput.of(session, null)
                        .resume(true)
                        .agent(agentName)     // ★ 必须是 HITL 生效代理，不能换回主代理
                        .model(modelName))
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

1. **决策与续流是两次 `handle`**——审批那次 `Kind` 为 `HITL`，不携带请求；续流那次才返回 `TASK`。
2. **续流要 `.resume(true)` 且不传正文**——传了也会被忽略（见 `InputHandleTest.resumeIgnoresText`）。
3. **代理名必须沿用 `outcome.getAgentName()`**——它是「本轮生效代理」，由显式入参优先、会话级 `CTX_AGENT_SELECTED` 兜底解析。trace 按代理名分 key，换回主代理会取不到挂起的 `tool_calls`，表现为续跑空转。
4. **整批还是逐条续流由宿主定**——用 `HITL.getPendingTasks(session)` 判断是否还有未决项，引擎不替你决定。

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
    public boolean cliOnly() {
        return false;                        // true 时 Web/IM 端不展示也不执行
    }

    @Override
    public boolean runnableWhenBusy() {
        return true;                         // 只读命令可在任务执行中运行
    }

    @Override
    public void execute(HarnessEngine engine, CommandContext ctx) {
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
public void execute(HarnessEngine engine, CommandContext ctx) {
    ctx.requestAgentTask("请审查本次 diff 里的并发问题", model());
}
```

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

不传 factory 时使用 `BufferedCommandContextFactory`：回执写内存，从 `getCommandOutput()` 一次取回。
**命令内部声明的 Agent 任务同样会真实执行**——`CommandDispatcher` 在 `dispatch` 尾部直接调
`engine.handle(...)` 完成二段装配，与是否有自定义工厂无关。

需要接管回执输出方式（实时写终端、自定义分页）时才提供工厂：

```java
InputOutcome outcome = engine.handle(
        HarnessInput.of(session, "/help"),
        (engine0, session0, command) -> new MyTerminalCommandContext(session0, command));
```

`CommandContextFactory.isRemote()` 返回 `true` 时，`cliOnly()` 的命令会被统一拦截——这就是桌面端 `/exit` 不会终止进程的原因。

### 4.5 改前缀

```java
HarnessEngine.of("/data/work", ".solon/")
        .commandPrefix("::")   // 默认 "/"
        .agentPrefix("#")      // 默认 "@"
```

改前缀后原前缀自动降级为普通文本。

---

## 五、完整示例：子代理输入

### 5.1 三种指定方式与优先级

```
explicitAgent（请求体显式）  >  输入开头的 @提及  >  selectedAgent（选择器兜底）
```

```java
// 方式一：输入里的提及——必须是有效子代理名，否则整段原样交给主代理
engine.handle(HarnessInput.of(session, "@bash 列出所有 Java 文件"));

// 方式二：请求体显式指定（桌面端用法，优先级最高）
engine.handle(HarnessInput.of(session, "列出所有 Java 文件").explicitAgent("bash"));

// 方式三：选择器兜底（前两者都没命中时才生效）
engine.handle(HarnessInput.of(session, "列出所有 Java 文件").agent("bash"));

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

### 5.2 用引擎装配好的请求执行

```java
InputOutcome outcome = engine.handle(HarnessInput.of(session, "@explore 找一下限流相关的实现")
        .model("deepseek"));

outcome.getRequest().options(o -> {
    o.toolContextPut(HarnessEngine.ATTR_CWD, "/data/work/sub-project");   // 动态换工作区
}).stream().blockLast();
```

### 5.3 直接用内置子代理

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

### 5.4 运行时创建子代理

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

### 5.5 主代理委派（`task` / `multitask`）

主代理通过工具自动委派，宿主无需写代码：

```java
// 工具集含 task 时（* 或 ** 或显式 task），主代理可调用：
//   task      —— 委派单个子代理，串行
//   multitask —— 并行委派多个子代理，单次上限 multitaskMaxTasks（默认 5）
engine.setSubagentEnabled(true);   // 默认 true
engine.setMultitaskMaxTasks(3);
```

`task` 默认不在 `*`（公有）里，只有 `**`（完整）才包含——这是为了让子代理不能再委派子代理，天然防止无限嵌套。

### 5.6 把子代理定义成文件

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
| `primary` | 是否为主代理 |
| `enabled` | 是否启用 |
| `permissionRules` | 权限规则（`toolName` / `behavior` / `pattern` / `priority`） |

> `skills` / `mcpServers` / `memory` / `hooks` / `background` / `isolation` / `teamName` 已标记 `@Deprecated`，保留字段但不再解析。技能与 MCP 现在走**挂载**与 `engine.addMcpServer(...)`。

---

## 六、工具权限

### 6.1 三级快捷权限

| 权限 | 标识 | 实际展开 |
|---|---|---|
| 核心操作 | `pi` | `read, write, edit, bash` |
| 全部公有 | `*` | `read, write, edit, glob, grep, ls, bash, skill, todo, code, codesearch, websearch, webfetch, lsp` |
| 全部完整 | `**` | `*` + `task, generate, mcp, openapi, hitl, memory` |

`ToolName` 是当前正解（`@Preview("4.0.4")`），`ToolPermission` 已 `@Deprecated` 但仍可用。

### 6.2 运行时调整

```java
engine.allowTool("bash");            // 触发主代理重建
engine.disallowTool("write");
engine.toolPermissionReset(tools, disallowedTools);
```

---

## 七、配置项参考

### 7.1 构造期

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
| `compressionModel(String)` | `null` | 压缩专用模型（复用主模型） |
| `memoryEnabled(Boolean)` | `true` | 长期记忆 |
| `memoryRelevanceCount(int)` | `6` | 相关性召回条数 |
| `memoryPriorityCount(int)` | `5` | 高优先度条目数 |
| `memorySummaryLength(int)` | `80` | 条目摘要长度 |
| `stopLoopInterceptor(...)` | `StopLoopInterceptor(3, 8)` | 重复调用刹车（阈值, 窗口） |
| `sandboxEnabled(Boolean)` | `true` | 文件系统沙箱 |
| `sandboxAllowUserHome(Boolean)` | `true` | 允许访问用户主目录 |
| `sandboxSystemRestrict(Boolean)` | `false` | 禁止访问系统路径 |
| `hitlEnabled(Boolean)` | `false` | HITL 拦截 |
| `subagentEnabled(Boolean)` | `true` | 子代理功能 |
| `bashAsyncEnabled(Boolean)` | `false` | Bash 异步执行 |
| `apiRetries` / `mcpRetries` / `modelRetries` | `3` | 三类重试 |
| `multitaskMaxTasks(Integer)` | `5` | 并行任务上限，0 为不限 |
| `commandPrefix(String)` | `"/"` | 支持多字符 |
| `agentPrefix(String)` | `"@"` | 支持多字符 |

### 7.2 运行期

```java
engine.setSandboxEnabled(false);
engine.setDefaultModel("deepseek");      // 触发主代理重建
engine.addModel(new ChatConfig(...));
engine.removeModel("gpt-4");

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

## 八、扩展点

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

## 九、安全机制

### 9.1 三套策略

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

### 9.2 宿主侧拿到审批请求

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

审批与续流走两段式，见 §3.5。

---

## 十、运行时目录

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

## 十一、模块结构

### 11.1 自有源码（69 个类）

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
│   └── goal/                     目标循环（含校验器）
├── runtime/                      执行桥（ExecutionBridge / RoundResult / ErrorClassifier）
├── channel/                      IM 通道接口
└── util/                         追踪工具
```

### 11.2 依赖包（能力来源）

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

## 十二、常见坑

1. **裸引擎没有命令**——`/help` 会返回 `TASK`。测试命令必须先 `getCommandRegistry().register(...)`。
2. **HITL 续流必须显式**——`Kind.HITL` 不携带请求；续流用 `.resume(true)`，且**不要**传正文。
3. **HITL 续流要用 `outcome.getAgentName()`**——它是「本轮生效代理」（显式入参 → `CTX_AGENT_SELECTED` 兜底）。换回主代理会取不到挂起的 `tool_calls`，续跑空转。
4. **`@` 提及必须带空白边界**——`@bash 内容` 有效，`@bash内容` 走普通文本。
5. **有附件时不识别命令**——避免 `/path/to/file.png` 之类被误判；带附件的 `/cmd` 按普通消息处理，附件正常落盘。
6. **`options(Consumer)` 是累加语义**——`getRequest()` 之后再追加 `options` 是安全的叠加。
7. **`getRequest().stream()` 是冷流**——何时订阅、在哪个线程订阅由宿主决定；需要切换线程请自行 `subscribeOn`。
8. **`build()` 只校验 `sessionProvider`**——缺模型要到首次 `handle` 才报 `chatModel is required`。
9. **HITL 默认只绑了 `bash`**——`Write` / `Web` 两套策略需自行 `onTool(...)` 绑定，否则不生效且不报错。
10. **新建子代理要注册**——`AgentDefinition` 构造完必须 `getAgentManager().addAgent(def)`，否则 `explicitAgent("xxx")` 回落主代理。

---

> **测试依赖**：`solon-test`（test scope）
> **编译辅助**：`lombok`（provided scope）
