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
| 先跑起来看看 | 第一章（五分钟上手）→ **第三章 3.1**（最小骨架） |
| 接一个真产品 | **第三章**全部（两段式、事件渲染、HITL 续流、四端时序） |
| 加命令 / 子代理 | 第四章、第六章 |
| 跑 goal / queue / steer | **第五章**（5.1 / 5.2 / 5.3，三个状态型命令的装配与宿主责任） |
| 调优与定制 | 第七～十章（权限、配置、扩展、安全） |
| 查结构 / 躲坑 | 第十二章（模块图）、第十三章（常见坑） |

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
        .origin(InputOrigin.of("dingtalk", "u1", "chat42", "msg7"))   // 渠道来源身份（IM 回复路由）
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
| `getPrompt()` | 装配好的 `Prompt`（含附件前缀与图片块；续流时为 null） |
| `getAgentName()` / `getModelName()` | 生效的子代理 / 模型（HITL 态下为 `resolveHitlAgent` 解析结果） |
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

## 五、完整示例：goal / queue / steer

这三个命令与前面 `help` / `clear` / `model` 不同：它们不是「打印一段文本就结束」，而是**跨轮维护会话级状态**。命令本身只改状态、只声明意图，真正的执行与投递由宿主装配的领域组件承担——因此每个命令都必须补上对应的宿主侧接线，否则命令会「有回执、无效果」。

| 命令 | 维护的状态 | 宿主必须额外装配 | 缺了会怎样 |
|---|---|---|---|
| `/goal` | 会话级目标（每会话一个） | `GoalExtension.builder(workspaceId)` + `addExtension` | 命令能创建目标，但永远不跑第二轮 |
| `/queue` | 会话级排队队列 | `QueueExtension` + `addExtension`，任务终态调 `queue.drainOne(...)` | 命令能入队，但队列永远不动 |
| `/steer` | 当前任务的插话邮箱 | `SteerExtension` + `addExtension`（拦截器由扩展挂载） | 命令能入队，但永不注入 |

**三个能力，三个类**：每个命令从装配到运行期操作宿主都只接触一个 Extension 实例（宿主侧与 harness 的交互面收敛到一个类），不再需要宿主逐个 new `GoalEngine`/`GoalScheduler`/`ExecutionBridge`/`GoalCommand`，也不需要分别操作 `SessionQueue` 与 `SessionQueueDrainer`。命令本身的语法与回执未变，终端用户无感知。

```java
engine.addExtension(GoalExtension.builder(workspaceId).taskExecutor(myRunner).build());  // /goal
engine.addExtension(new QueueExtension());                                             // /queue
engine.addExtension(new SteerExtension<>(() -> mySteerPort));                          // /steer
```

三者共同点：`/queue` 与 `/steer` 的 `runnableWhenBusy()` 返回 `true`（能穿透忙碌检查）；`/goal` **不覆写**、保持默认 `false`（它会启动持续轮次，不能在忙时会话里重入）。`/queue`、`/steer` 从 `Command.execute(engine, ctx, origin)` 的第三参拿到 `InputOrigin` 并带到投递链路，三者都**不自己开流**。

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

- **`taskExecutor` 是唯一有实际意义的必选项**。缺省实现就是本节原有的 `engine.handle(...).call().getContent()` 模板，所以最小用法是`GoalExtension.builder(workspaceId).build()` + `addExtension`——但那时 goal 轮次只会走通用入口，不会带宿主的专属执行语义（如 Web 的流式通道、CLI 的终端读入）。
- **`bridge` 与 loop 共享**：宿主已有 `ExecutionBridge` 时用 `.bridge(shared)` 注入，扩展不再自建；TaskHandler / BusyChecker 仍注册到同一桥上，两个能力共用一套执行通道。
- **job 名带工作区前缀**在扩展内部完成，`JobManager` 是 JVM 单例、会话 ID 不全局唯一（CLI 叫 `cli`、桌面是 `ws_<时间戳>`）——不加前缀时多个工作区会在全局注册表里互相覆盖。
- **`.scheduler(x)` 三态**：不传 = 缺省新建；传实例 = 宿主自备；**显式传 null = 关闭调度**（纯 JUnit / 无 Solon 容器的宿主，`JobManager.getInstance()` 会 NPE，只能关）。
- **`.commandEnabled(false)`**：宿主自管命令注册时用；注册本身幂等（已有同名命令则跳过），迁移期双写安全。
- 存量写法 `new GoalExtension(goalEngine, engine)` 保留并标 `@Deprecated`：只接管外部 GoalEngine、只挂 Talent，不装配命令与调度。

#### 运行期操作：还是这一个类

不必再持 `GoalEngine` / `GoalScheduler` 引用，门面方法直接中转：

```java
goal.state(session); goal.hasGoal(session);              // 读
goal.stateOf(sessionId); goal.sessionOf(sessionId);       // 按 id 读（免自己解析会话）
goal.find(sessionId, taskId);                           // 任务列表行 → GoalState（不是 goal 行返回 null）
goal.running(session); goal.running(sessionId);           // 是否正在跑一轮
goal.pause(session, gs); goal.resume(session, gs);        // 控（自动同步调度注销/重挂）
goal.attachAndSchedule(s, obj, tokens, ms, iter);       // 建 + 挂调度(runNow=true)
goal.attach(s, obj, tokens, ms, iter);                  // 只建不挂（首轮由宿主流驱动，如桌面端）
goal.updateAndReschedule(s, gs, obj, tokens, ms, bool);  // 改配置 + 按需恢复调度
goal.resumeScheduleOnly(sessionId);                     // 只挂 job 不立即跑（重启恢复场景）
goal.scheduleNow(sessionId); goal.unschedule(sessionId);  // 单独的调度触发/摘除
goal.runRoundAsync(session);                            // 异步跑一轮（/loop trigger 语义）
goal.clear(session); goal.stopAll(); goal.hasScheduledGoals(); goal.hasScheduler();
goal.toTaskMap(session, gs);                              // 列表/徽章视图（与 loop 任务同构）
goal.addListener(l); goal.registerValidator(matcher, v);   // 扩展点
goal.getGoalTalent().setEnabled(false);                    // 只关模型侧工具，不影响命令
```

三个易错点（宿主自己拼调度的老写法踩过）：

- **`resume()` 是 `runNow=true`**。服务重启/工作区恢复时想「静默挂上、等下一轮」必须用 `resumeScheduleOnly()`，否则会对每个活跃 goal 立刻发起一轮执行。
- **改配置只吞后半段**。「pause → interrupt → 等轮次停 → update → resume」里的中断与等待是宿主的运行时职责（依赖宿主的会话中断端口），门面不代管；`updateAndReschedule` 只覆盖 `update + 时长 commit + resume + schedule`。
- **`clear()` 内部先摘调度再清状态**，不会留一个已无目标却仍在调度的 job。

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

Flag 格式是 `--key:value`（冒号分隔，不是等号/空格）：缺冒号会打印 `Unknown flag` 并跳过该参数；数值非法直接 `Invalid --xxx value` 返回。`--max-duration` 内部转毫秒（`×60_000`）。三个预算默认都是 `0`，表示不限制。

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
GoalState gs = goalEngine.state(session);
Map<String, Object> item = goalEngine.toTaskMap(session, gs);   // 与 loop 任务同构，type = "GOAL"

// 生命周期观察者：桌面端用它把后台多轮执行持续推回同一个对话流
// state 在 removed = true 时仍是清除前的快照
goalEngine.addListener((sessionId, state, removed) -> { /* push to client */ });
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
QueueExtension queue = new QueueExtension();
engine.addExtension(queue);   // initialize 内部注册 /queue 命令（幂等）
```

`new QueueExtension(false)` = 只保留操作门面、不注册命令（宿主自管注册时用）。

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

完整门面一览：

| 方法 | 作用 |
|---|---|
| `enqueue(session, text, origin)` | 入队，返回位置（从 1 计），-1 = 失败 |
| `enqueue(...)` 九参重载 | 带 id 去重 / model / effort / agent / files（HTTP 重试幂等） |
| `drainOne(session, runner)` | 领取并执行队首一条（同步语义：返回即确认） |
| `drainOne(session, runner, false)` | 异步语义：终态再 `ack` |
| `ack(session, itemId)` | 异步终态确认（校验 in-flight 身份） |
| `pendingSize` / `size` / `hasItems` / `view` | 待执行数 / 含 in-flight 总数 / 是否有任务 / 统一视图 |
| `hasPersistedItems(context)` | 快照探测（不实例化会话） |
| `cancelItem` / `promote` / `cancelPending` | 取消单条 / 置顶 / 清空并递增代际 |

`drainOne` 内部顺序：`tryClaim`（同一时刻只允许一个运行者 poll）→ `poll`（队首出队并置为 RUNNING + 记为 in-flight）→ 执行回调 → `acknowledge` / 失败则 `requeueFront` → `releaseClaim`。

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
SteerExtension<WorkspaceContext> steer =
        new SteerExtension<>(() -> webGate /* SteerOutputPort */, wsContext);
engine.addExtension(steer);   // initialize 注册 /steer 命令；configure 把拦截器挂为主代理默认拦截器
```

参数与变体：

- **供应商惰性解析**：`SteerOutputPort` 在每次事件回调时取值，不在构造期快照。工作区可能在入口绑定之前创建（那时端口还拿不到），惰性供应避开了这个时序坑。
- **`wsContext` 构造期定值**：它是不透明路由令牌。若同一引擎服务多个工作区、每请求上下文不同，则**保留请求级挂载**（见下方），请求级实例会按 Class 去重覆盖默认实例。
- **`null` 供应商**：headless 宿主（无前端可通知），dropped 只记 warn 日志。
- **`new SteerExtension<>(port, ctx, false)`**：不注册命令（宿主自管）。

运行期操作也在这一个实例上：

```java
steer.steer(session, null, null, text, origin, null);   // 投递（与 /steer 命令、/web/chat/steer 同源）
steer.cancel(session, steerId);                        // 取消仍在邮箱中的插话
```

命令只做投递，**不做降级**：邮箱满、任务未运行、轮次已切换时如实告知原因，由用户自行决定改排队还是直接发送（与 `/web/chat/steer` 的 409 纯语义对齐）。

```java
// runId 传 null = 绑定「当前活跃任务」（IM 场景没有 runId 概念，直接信任后端权威值）
// busyRecheck 传 null = 仅按 activeRunId 判定
SteerInterceptor.SteerResult r = SteerInterceptor.steer(session, null, null, text, origin, null);
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

#### 拦截器挂载：扩展已代劳

`SteerExtension.configure` 会把拦截器挂到**主代理的默认拦截器列表**，宿主无需每请求 new + add。顺序安全：拦截器容器按 Class 去重（`LinkedHashMap<Class, RankEntity>`），AgentFactory 先挂压缩/StopLoop、后轮到 extension 的 configure，因此 steer 拦截器天然排在上下文压缩之后。

只有「一个引擎多工作区、每请求 wsContext 不同」时才需要请求级挂载（如 Web 端 `WebStreamBuilder`）：

```java
SteerInterceptor<WorkspaceContext> steerInterceptor = new SteerInterceptor<>(outputPort, wsContext);

outcome.getRequest()
        .options(o -> o.interceptorAdd(steerInterceptor))   // 同 Class 覆盖默认挂载，不会双份
        .stream()
        .doOnNext(this::render)
        .blockLast();
```

> **不挂拦截器 = 插话永不生效**：消息能入队，但没人消费，任务结束时由 `onAgentEnd` 清空并转入 dropped。

`options(Consumer)` 是累加语义，且拦截器按 `LinkedHashMap` 插入序排列——天然排在默认的 `ContextCompressionInterceptor` 之后，是向压缩产物尾部追加，不破坏消息序列。

#### 输出端口（宿主实现）

```java
public interface SteerOutputPort<C> {
    /** 注入已生效：客户端据此清除待生效态并落气泡，实现方必须尽力送达 */
    void emitSteerApplied(C wsContext, AgentSession session, String runId, List<SteerMessage> items);

    /** 任务结束时仍有未消费插话的兜底：转入排队并广播，绝不允许「已接受但永不生效」 */
    void handleDroppedSteers(C wsContext, AgentSession session, Queue<SteerMessage> box, String runId);
}
```

泛型 `C` 是**不透明路由令牌**（典型为宿主的工作区上下文）：领域层只透传、不解释，保证 harness 不依赖任何宿主类型。端口为 `null` 时 drop 只记 warn 日志、不通知客户端。

#### 三条注入守卫

1. **首轮不注入**（`turnCount <= 1`）——原始 prompt 必须先被采样，不篡改初始意图；
2. **尾部 tool_calls 未闭合不注入**——HITL 挂起恢复的窄窗里插 user 消息会打断 `tool_use` / `tool_result` 配对，部分模型直接报错；等 `ActionTask` 补齐结果后自然恢复；
3. **无悬挂**——`onAgentEnd` 检查残留并交给 `handleDroppedSteers`，绝不允许「已接受但永不生效」。

注入形态：`ChatMessage.ofUser("[用户实时补充] " + text)`，metadata 标记 `source=steer`。不追加 systemPrompt 说明——前缀本身已足够表意。

#### 运行边界

`activeRunId` 在 `onReasonStart` 才写入。所以任务刚被受理、尚未进入推理边界时，steer 会返回 `NOT_RUNNING`（IM 传 null）或 `TURN_CHANGED`（Web 带 runId）——这不是 bug，是「此刻还没有可插话的推理现场」。

插话**零持久化**：不进 `session.addMessage`、不占 `sessionWindowSize`、不会在后续任务里被当作初心重新入场；刷新页面后插话气泡即消失（刻意取舍，展示层由前端在收到 `steer_applied` 时就地渲染进当前流式气泡内部）。

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
| `subagentDefaultConcurrency(Integer)` | `5` | 子代理任务默认并发上限，取值 `1~10`（`0`=未设置取默认 5，超 10 按 10，负数报错），**不存在「不限制」**；仅在模型未配置 `AiConfig#concurrency` 时生效（模型配了以模型为准）。超限任务排队，不报错。`task` 与 `multitask` 均取槽（信号量引擎级、跨会话共享）。旧名 `multitaskMaxTasks` / `multitaskMaxConcurrency` 已 `@Deprecated` |
| `commandPrefix(String)` | `"/"` | 支持多字符 |
| `agentPrefix(String)` | `"@"` | 支持多字符 |

### 8.2 运行期

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

### 12.1 自有源码（70 个类）

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
│   └── goal/                     目标循环（含校验器）
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
13. **`/steer` 不挂拦截器就永不生效**——报 `STEERED` 只代表已入邮箱。`SteerExtension` 已代劳默认挂载；仅当同一引擎多工作区、每请求上下文不同时才需请求级 `o.interceptorAdd(...)`（同 Class 会覆盖默认实例，不会双份），否则任务结束时转 dropped。
14. **`GoalScheduler` 的 job 名必须带工作区前缀**——`JobManager` 是 JVM 单例而会话 ID 不全局唯一，不加前缀会让多工作区的同名会话互相覆盖（`builder(workspaceId)` 已内置）。
15. **不要给零旋钮的能力套 builder**——goal 有 8 个可配点才用 builder；queue 零旋钮、steer 一个端口，用无参/单参构造即得。伪一致性只会增加仪式感。
16. **`resume()` 含 `runNow=true`，恢复期别用**——服务重启/工作区恢复要「静默挂上、等下一轮」时用 `resumeScheduleOnly(sessionId)`，否则每个活跃 goal 会立即多跑一轮。
17. **改 goal 配置不要整段交给门面**——`pause` → 宿主 `interruptSession` → 等轮次停 → `update` 这条链里，中断与等待是宿主的运行时职责；门面的 `updateAndReschedule` 只覆盖后半段（update + 时长 commit + resume + schedule）。把它整段吞进去会让旧轮次继续跑并回写覆盖刚改的配置。

---

> **测试依赖**：`solon-test`（test scope）
> **编译辅助**：`lombok`（provided scope）
