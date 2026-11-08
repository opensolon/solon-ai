# solon-ai-harness-core — 自己编排（L1）：受理 API、两段式宿主与会话插件接线

本文写给**不用宿主骨架 `HarnessHost`、直接调 `engine.handle`** 的宿主（只引 core、交互式终端、需要自定义输出时序）。
用 L0（`HarnessHost`）时，本文的受理、开流、续发积压、HITL 续流、后台轮次都由骨架代办，不需要读；见 [host README](../../solon-ai-harness-host/README.md)。

> 引擎构建、两种时态总览、命令、子代理、权限、配置、扩展点、安全与常见坑见 [core README](../README.md)；
> 各插件（goal / queue / steer / loop）的使用态与管理态示例见各自 README；服务端（多租户）直连 `handle` 的写法另见 [SAAS.md](SAAS.md) §六。

自己编排时要做三件事：

1. 构造 `HarnessEngine`（给工作区、给模型、给工具权限，见 [core README](../README.md) §1.2）；
2. 把用户输入交给 `engine.handle(...)`；
3. 消费 `InputOutcome`——按 `getKind()` 一次 `switch` 分派；任务流取 `getRequest().stream()`（或直接 `outcome.stream()`），回执取 `getCommandOutput()`。


命令识别、子代理解析、附件装配与落盘（由宿主完成）、Prompt 构建、请求装配，在一次调用内串联完成。

> **全文档只推荐一条取流路径**：`outcome.getRequest().stream()`（等价简写 `outcome.stream()`，
> 仅 `Kind.TASK` 有效）。不存在「回执 + 任务合成一条流」的便捷方法，原因见 §2.4 的四端时序对照。
> 需要一次性拿最终文本时用 `outcome.call()`，它只转发 `getRequest().call()`，不做任何时序决策。

| 章 | 内容 |
|---|---|
| 一、核心 API | `handle` 的 0–7 步、`HarnessInput`、`InputOutcome` 六态与 HITL 三种情形 |
| 二、端到端示例 | 最小骨架、完整宿主、事件类型、四端时序、HITL 续流不变量、受理控制 |
| 三、会话插件接线 | 插件协作契约、装配、命令 / `handle` / 门面三条路径、取消与开关、停 / 改 / 重排 |

---

---

## 一、核心 API：`handle` + `InputOutcome`

### 1.1 唯一的输入入口

```java
// HarnessEngine —— 两个重载，实际只有后者做事
public InputOutcome handle(AgentSession session, String text);   // 便捷：等价于 handle(HarnessInput.of(session, text))
public InputOutcome handle(HarnessInput input);                  // 唯一入口
```

一次 `handle(...)` 内部按固定顺序做事，顺序本身是语义的一部分：

| 步骤 | 动作 | 说明 |
|---|---|---|
| 0 | 受理判定 | 引擎已关闭 → `REJECTED(engine_closed)`；会话归属（`SessionOwnership`，非持有 → `not_owner`）→ 作用域预检（锁外；显式 `scope(...)` 优先，其次 `scopeResolver`，`scopeRequired` 且解析不到 → `scope_unavailable`，不占积压名额）→ HITL 挂起拦截 → 忙态放行 / 入积压 / 拒绝 → 占位；未通过时直接返回 `DEFERRED` / `REJECTED`（§2.6） |
| 0.5 | `onInput` 钩子 | 扩展仍可在这里设置 `input.scope(...)`，装配时以显式作用域为准；解析器看不到 `onInput` 的副作用 |
| 1 | HITL 决策 | **优先于空输入判空**：审批请求通常不带文本。带 `resume(true)` 时同一次调用**尝试**续流，是否真的续流由引擎自检决定（见 §1.3） |
| 2 | 续流分流 | `resume(true)` 直接装配请求，不解析文本 |
| 3 | 空输入判空 | 无文本且无附件 → `IGNORED` |
| 4 | 命令识别与执行 | 有附件或 `commandAware(false)` 时不当作命令 |
| 5 | 子代理解析 + 附件前缀 | 解析 `@name` 前缀，文件引用拼 `[附件: x]` |
| 6 | Prompt 构建 | 多模态内容块、来源/附件/子代理元数据 |
| 7 | 请求装配 | 模型回退链 + 子代理 + `ReActRequest` |

**第 0–7 步全部同步完成，不自启推理。** 推理只在宿主订阅 `getRequest().stream()` 时才开始——这是两段式设计的根基（详见 §2）。

### 1.2 `HarnessInput`：一次输入的完整上下文

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
| `scope(InputScope)` | 本轮作用域（只能收窄的强制边界，见 [core README](../README.md) §8.3）；不传则走引擎级 `scopeResolver` |
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
- **`resume(true)`**——本次不产生新用户输入，只按会话中挂起的 `tool_calls` 续跑。文本与附件都被忽略。它与 `hitl(action, callId)` 合用表示「写完决策就试着续流」，但**只是一种意愿表达，不保证续流一定发生**：引擎会在写入决策后自检三项，全部成立才装配请求返回 `TASK`，否则返回 `HITL` 并携带事实（详见 §1.3）。三端的正确写法都是传 `resume(true)` 后只 `switch (kind)`——批挂起时引擎会拒绝过早续流，宿主不必自己判断「整批是否齐备」。

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

### 1.3 `InputOutcome`：处置态

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
    case REJECTED: // 未受理；原因 getRejectReason()（RejectReason 枚举（input 包），线上值 .code()），文案 getRejectDetail()
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
| `DEFERRED` | 会话忙，输入已进积压（`getBacklogPosition()` 从 1 计）；等待续发（§2.6、[queue README](../../solon-ai-harness-queue/README.md)） |
| `REJECTED` | 未受理；按 `getRejectReason()`（`RejectReason` 枚举（`input` 包））分流：`BUSY` / `HITL_PENDING` / `ATTACHMENTS_NOT_DEFERRABLE` / `BACKLOG_UNAVAILABLE` / `NOT_OWNER` / `SCOPE_UNAVAILABLE` / `SCOPE_NOT_DEFERRABLE` / `ENGINE_CLOSED`；线上值用 `getRejectReason().code()`（如 `busy`、`not_owner`） |

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
| `isBesideRunning()` / `isDispatched()` / `getDispatchOrigin()` | 忙时旁路执行 / 程序派发轮次（§2.6） |
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

## 二、完整示例：`handle` + `InputOutcome` 两段式（端到端）

前两章是零件清单，本章把它们拼成能跑的宿主。**两段式**是理解本模块的主线：

| 段 | 谁做 | 做什么 | 是否启动推理 |
|---|---|---|---|
| 第一段 | `engine.handle(input)` | HITL 决策 → 续流分流 → 空输入判空 → 命令识别执行 → 子代理解析 + 附件装配 → Prompt 构建 → 请求装配 | **否**，全部同步完成 |
| 第二段 | 宿主读 `InputOutcome` | 按 `getKind()` 选动作；只有 `TASK` 才订阅 `getRequest().stream()` | 是，时机与线程由宿主决定 |

第一段**永不启动推理**。这正是宿主能在两段之间插入自己准备动作（注册忙碌标记、复位流门、开 SSE 响应头、挂取消钩子）的原因，也是引擎不替宿主开流的原因。

### 2.1 最小骨架：跑通一轮

> `MinimalHost`（本节）与 `FullHost`（§2.2）都是**文档内的示例类**，没有对应的 `org.noear.*` 可 import——请直接复制到你的项目里改。harness 提供的是 `HarnessEngine` / `HarnessInput` / `InputOutcome` 这些零件，宿主外壳由你自己写。

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

**为什么骨架里写了 `REPLY` 分支，上面三行却没有一行命中它？** 因为裸引擎没有注册任何命令（[core README](../README.md) §3.1）。一旦注册，同一个 `/help` 就会走到那个分支：

```java
host.getEngine().getCommandRegistry().register(new HelpCommand());
host.send("/help");   // REPLY → 渲染回执后收尾
```

**注册命令并不需要配 `commandContextFactory`**——这一点是本节曾被误读的地方。引擎在 `dispatchCommand` 里就会兜底创建 `BufferedCommandContextFactory`（`HarnessEngine` 内），回执写入内存、由 `getCommandOutput()` 一次取回；骨架里 `System.out.print(outcome.getCommandOutput())` 的写法**已经**是这个默认工厂的正确消费方式。

只有在需要改动「回执怎么落地」时才提供工厂——CLI 要实时写终端并读键盘（`/help` 分页、`/model` 选择器），这时才用 `commandContextFactory(...)`（[core README](../README.md) §3.4）。因此最小骨架刻意不配它：多配一个默认值的等价物，只会让读者以为「不配就不工作」。

一个值得提前知道的副作用：默认 `terminal=false`，所以 `/help` 会裁掉所有 `terminalOnly()` 命令（如 `/exit`）。
若你的宿主就是命令行程序，用 `.terminal(true)` 打开即可（[core README](../README.md) §3.4）。

再看 `TASK` 分支里的 `hasReply()`：命令触发的任务（如 `/queue` 排队后立刻开跑）回执与任务并存，那份回执同样出自默认工厂。

> `STANDALONE` 模式下 `build()` 只硬校验 `sessionProvider`。「`chatModel is required`」是**首次 `getMainAgent()`** 时由 `ReActAgentConfig` 抛出——所以 `modelAdd` / `defaultModel` 缺失不会在 `build()` 报错，而是在第一次 `handle` 报错（`SERVER` 模式在构建期就报）。

### 2.2 完整宿主：六态都处理到位

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

        // 命令必须显式注册（core README §3.1），不注册则 /help 仍按普通消息处理
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

### 2.3 事件类型一览

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

### 2.4 两段之间能插什么：三端时序对照

这是「引擎不替宿主开流」的真正原因——同一份 `InputOutcome`，四端在两段之间要做的事完全不同：

| 端 | 两段之间必须做的事 | 跳过会怎样 |
|---|---|---|
| CLI | 回执直接写终端；不预注册订阅 | 回执重复打印 |
| Web | 先复位前端流门、注册 `Disposable` | 回执被前端丢弃；忙碌判定出现空闲窗口、同会话开出第二条流 |
| 桌面 | `streamHub.begin` 拿序号（供回放）；重挂 HITL 拦截器 | 事件序号错乱；续流时无拦截器 |
| ACP | 包一层 `takeWhile(ctx::isCancelled)` | 取消请求不生效 |

### 2.5 HITL 续流的四条不变量

1. **决策与续流合并为一次 `handle`**——三端统一写法：`.hitl(action, callId).resume(true)`，一次调用后只 `switch (kind)`：`TASK` 即已续流，`HITL` 则读事实处理。**不要自己分两次**（先 `.hitl(...)` 再 `.resume(true)`）：那个序列需宿主自行判断整批是否齐备，而引擎已在写入决策后准确计数。两个一次调用的典型错法：不传 `resume` 导致永远不续流（任务卡死）、或在批未齐备时就调 `.resume(true)` 而不用引擎事实（会带走未决任务）。
2. **续流要 `.resume(true)` 且不传正文**——传了也会被忽略（见 `InputHandleTest.resumeIgnoresText`）。
3. **代理名必须沿用 `outcome.getAgentName()`**——它是「本轮生效代理」，由显式入参优先、会话级子代理选择（`engine.getSessionAgent`）兜底解析。trace 按代理名分 key，换回主代理会取不到挂起的 `tool_calls`，表现为续跑空转。
4. **续流时机由引擎判定，宿主只管事实**——不要用 `HITL.getPendingTasks(session)` 自己数整批是否齐备（该集合含已决策项，`isEmpty()` 在刚写入首张决策后仍为 false，用它判定会把已经齐备的批永远卡住）；该集合只用于「展示下一张未决卡」。齐备判定统一读 `outcome.getPendingHitlCount()`。

### 2.6 受理控制：三个宿主常写错的地方已收归引擎

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
- 兜底：`InterruptMode.INTERRUPT_ALL` / `prepareInterrupt` 会清除占位；占位有时限 `HarnessEngine.Builder#admissionTimeoutMs(Long)`（缺省 60000 毫秒），超时的占位不再算占用；
- 非 `TASK` 结局在 `handle` 返回前已释放占位；
- **程序派发线程上的一轮**（队列续发：在领取回调里同线程 `engine.handle(claim.getItem().toInput(session))`）由引擎识别（`outcome.isDispatched()`，宿主取 `getDispatchOrigin()` 作回复路由），免于「有积压就延后」；其 `TASK` 同样占位，派发方据此用「返回时会话是否忙」判断已异步开流；
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

## 三、会话插件接线：goal / queue / steer / loop（+ 中断干预）

这三个命令与前面 `help` / `clear` / `model` 不同：它们不是「打印一段文本就结束」，而是**跨轮维护会话级状态**。命令本身只改状态、只声明意图，真正的执行与投递由宿主装配的领域组件承担——因此每个命令都必须补上对应的宿主侧接线，否则命令会「有回执、无效果」。

| 命令 | 维护的状态 | 宿主必须额外装配 | 缺了会怎样 |
|---|---|---|---|
| `/goal` | 会话级目标（每会话一个） | `GoalExtension.builder()` + `Builder.extensionAdd` | 命令能创建目标，但永远不跑第二轮 |
| `/queue` | 会话级排队队列 | `QueueExtension` + `Builder.extensionAdd`，任务终态调 `queue.drainLoop(...)` / `claimNext(...)` | 命令能入队，但队列永远不动 |
| `/steer` | 当前任务的插话邮箱 | `SteerExtension` + `Builder.extensionAdd`（拦截器由扩展挂载） | 命令能入队，但永不注入 |
| `/interrupt` | 无状态（只停） | 无（现成的 `engine.interrupt(session, InterruptMode.INTERRUPT_ALL)` 即可） | 不装命令就只能由宿主自带停止按钮 |
| `/loop` | 会话定时任务（间隔 / cron，可多条） | `LoopExtension.builder()` + `Builder.extensionAdd`，宿主按通道注册执行器 | 任务能建、到点跳过（无认领只记日志，绝不记成成功） |

**三个能力，三个类**：每个命令从装配到运行期操作宿主都只接触一个 Extension 实例（宿主侧与 harness 的交互面收敛到一个类），宿主不接触 goal 运行态、调度器、命令类，也不接触 queue 的存储实现。命令本身的语法与回执未变，终端用户无感知。

### 3.1 插件协作契约

**会话能力是独立插件模块（4.2.0+）**：`solon-ai-harness-goal` / `solon-ai-harness-queue` / `solon-ai-harness-steer` / `solon-ai-harness-loop`，按需引入（或用聚合包 `solon-ai-harness` 一次引齐，见 [core README](../README.md) §1.1；引入不等于装配）。核心模块不认识它们，它们之间也互不依赖，只经核心契约协作（见 `HarnessExtension` 类注释「插件化协作规则」）：

| 契约 | 类型 | 提供方 | 消费方 |
|---|---|---|---|
| `HarnessExtension#onSessionInterrupt` | 钩子（广播） | 引擎 `interrupt` / `prepareInterrupt` 先于停流回调 | queue 清队列 |
| `HarnessExtension#onSessionRemove` | 钩子（广播） | 引擎 `removeSession`（宿主永久删除会话时，先于删存储调用） | loop 删该会话任务、goal 注销该会话 job |
| `HarnessExtension#contributeSessionStatus` | 钩子（汇集） | 引擎 `status` 读快照时回调 | 各插件贡献**自己定义**的键：queue `QueueExtension.STATUS_PENDING`、steer `SteerExtension.STATUS_PENDING`、goal `GoalExtension.STATUS_GOAL`、loop `LoopExtension.STATUS_TASKS`（只供展示/宿主业务，内核不读） |
| `spi.InputBacklog` | 能力接口（`offer(session, HarnessInput)` / `hasPending`） | queue | 引擎 `handle` 受理判定（有无积压能力、忙时 `offer` 入积压、`hasPending` 保先进先出）、`engine.isIdle`、steer 残留转积压（`getExtension(InputBacklog.class)`） |
| `spi.RunDispatch` | 会话槽位（`session.run.dispatch`） | queue 派发 | 引擎 `handle` 受理判定（识别派发轮次）、`RunTicket` |
| `HarnessExtension#isQuiescent` | 钩子（汇集） | goal（有活跃 goal）、loop（有启用任务） | `engine.isQuiescent()` → 宿主的空闲回收判定（如 core 的 `HarnessEnginePool`） |
| `HarnessExtension#getToolNames` | 声明 | goal、loop（取自各自 Talent 的工具表） | 作用域守卫：已声明的工具不受 `InputScope.tools` 名单约束 |

**内核没有 queue**：`SessionStatus` 不含排队数，`InputOutcome` 的延后结局叫 `Kind.DEFERRED`（不是 QUEUE），程序派发判定叫 `isDispatched()`。入积压由引擎经 `InputBacklog#offer(session, HarnessInput)` 完成，宿主域属性（`HarnessInput#attr`）由实现方原样保存。内核自身依赖积压的判定（受理先进先出、空闲判定）走能力方法 `InputBacklog#hasPending`——由类型系统保证实现方提供，而不是「必须贡献某个状态键」的约定。goal 调度与宿主定时任务判断「能否注入」统一用 `engine.isIdle(session)`（不占用且无积压，同一把会话锁内读取）。

没装 queue 时：`handle` 忙态返回 `REJECTED("busy")`（无处可延）；steer 残留按转排队失败处理（暂存并推 `SteerDroppedEvent`）。

**会话状态是开放的**：`SessionStatus` 只有核心事实是固定字段（运行流 / HITL / 受理窗口 / 取消代数），插件事实经 `contributeSessionStatus` 贡献到类型化键 `SessionStatus.Key<T>`（`Key.count` 求和、`Key.of` 后写覆盖），新增插件事实不改核心。宿主一律 `engine.status(session)` 读全；静态 `SessionStatus.of(session)` 只有核心事实。

```java
SessionStatus st = engine.status(session);
int queued  = st.get(QueueExtension.STATUS_PENDING);        // 待执行排队数（没装 queue 为 0）
int steers  = st.get(SteerExtension.STATUS_PENDING);        // 待生效插话数
GoalStatus goal = st.get(GoalExtension.STATUS_GOAL);  // 无 goal 为 null
```

**会话属性键各自登记**：核心 `HarnessAttrs` 只放引擎键与核心槽位；插件键在插件模块内——`QueueAttrs`（`session.queue.*`）、`GoalAttrs`（`session.goal.*`）、`SteerAttrs`（`session.steer`）。三者都是包内可见类（4.2.0 起），宿主无法也不应读写（`HarnessStructureGuardTest` 与 soloncode 的 `PackageBoundaryTest` 守卫）。

### 3.2 装配

**推荐：构建期用 `Builder.extensionAdd` 装配**（STANDALONE / SERVER 通用，SERVER 下 build 之后不能再挂扩展）。插件就是普通扩展，
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

运行期装配（仅 `runtimeMutable` 时，即 STANDALONE 缺省）用 `addExtensionIfAbsent`（按类型幂等，返回实际生效的实例）：

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

// 宿主增删改查：请求参数原样传入（null = 未设置 / 保持原值），校验由插件统一完成（不合法返回 INVALID）
LoopTask t = loop.schedule(sid, LoopSpec.of(prompt).everyMinutes(interval).cron(cron).runNow(runNow)).getValue(); // OK 时有值
loop.schedule(sid, LoopSpec.of("remind").at(LocalDateTime.of(2026, 9, 5, 18, 0)));  // 一次性
loop.update(sid, t.getId(), LoopPatch.create().prompt(newPrompt).cron(""));     // cron 空串 = 回到固定间隔
loop.pause(sid, t.getId()); loop.resume(sid, t.getId());
Map<String, Object> view = t.toMap();                                            // 标准展示视图
```

loop（会话定时任务）与 goal（会话目标）的区别：goal 每会话一个、由状态驱动续轮、有预算与完成判定；loop 可多条、按时间表独立触发、只记最终文本。loop 的开关（`setEnabled`）只关模型工具 `loop_*`，`/loop` 命令与已有定时任务照常；会话任务文件里不认识的条目与任务上的宿主扩展字段（`LoopSpec.extra`/`LoopPatch.extra` 写、`getExtra` 读）一律原样写回（旧版单文件 `tasks.json` 用 `store(source, "loop", "tasks.json")` 首次加载时拆分迁移）；文件读不懂时先备份为 `*.corrupt-<时间戳>`，读不出来时本进程不写盘；扩展摘除后不再写盘。`LoopTask` 对宿主完全只读（无公开构造器与修改方法），改定义走 `loop.update(sid, id, LoopPatch)`：新版本与在途一轮共享运行锁，不会并发。会话忙由插件在调用执行器前统一判定（`engine.isIdle`），执行器只管本通道此刻能不能跑（如 CLI 终端是否空闲→`skip`）；执行器返回 null 按失败计。`hasActiveTasks()` 只算启用任务（只剩暂停任务的工作区可闲置回收）。未显式 `jobManager(null)` 却取不到 JobManager 时会告警（任务只存不跑）。

三者共同点：`/queue` 与 `/steer` 的 `runnableWhenBusy()` 返回 `true`（`handle` 第 0 步据此让它们在任务执行中旁路执行）；`/goal`、`/loop` **不覆写**、保持默认 `false`，忙时会进积压（`DEFERRED`）或被拒绝。`/queue`、`/steer` 从 `Command.execute(engine, ctx, origin)` 的第三参拿到 `InputOrigin` 并带到投递链路，三者都**不自己开流**。

### 3.3 命令文本、`handle` 与门面：同一个动作的三条路径

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
| 作用域 | 受 `InputScope` 约束 | **不受约束**，多租户管理接口由宿主先鉴权（L0 见 [host README §5](../../solon-ai-harness-host/README.md)） |
| 需要 `.terminal(true)` 吗 | 不需要（这四个命令都不是 `terminalOnly`） | 不适用 |

四个能力的最简门面形态（① 内部最终都落到这里，与命令走同一条写路径；§3.5 还给出了“运行中干预”与“停下来重排”两个互斥场景）：

```java
goal.create(sessionId, GoalSpec.of("让测试全绿"));   // HarnessResult<GoalSnapshot>

queue.enqueue(session, "顺便更新 README");             // HarnessResult<Integer>：OK 时值为队列位置

HarnessResult<SteerReceipt> r = steer.steer(session, "改用 PostgreSQL 方言");  // 2 参

engine.interrupt(session, InterruptMode.INTERRUPT_ALL);  // HarnessResult<Boolean>：值 true = 确实停了
```

**同源是可验证的**，不是口头承诺——三条命令的实现体就是这三个方法，文档里不必写两套语义：

| 命令 | 命令内部的实际调用 |
|---|---|
| `/goal <obj>` | 解析 flag 得到 `GoalSpec` 后 `goal.create(session, spec)`（`GoalCommand` 注释明写「命令行与编程调用的唯一汇合点」） |
| `/queue <msg>` | `QueueExtension.enqueue(session, text, resolved)` |
 | `/steer <msg>` | `steer.steer(session, text, origin)`（命令经门面投递，开关、忙判定与容量上限都与门面同源） |

三条由此推出的边界：

- **这三个命令的 `Kind` 恒为 `REPLY`**：它们只改会话状态、不声明 Agent 任务意图，所以命令路径下不会出现 `TASK`。`/goal` 虽然会自己起持续轮次，但那是调度器驱动的、不在本次 `handle` 的返回里——别等 `getRequest()`。
- **忙碌放行只在文本路径上自动发生**：①② 经 `handle` 第 0 步，`runnableWhenBusy` 的命令忙时旁路执行（`isBesideRunning()`），其余输入入积压或拒绝（§2.6）；③ 是门面直调，不经受理判定，也不经作用域（会话归属仍会检查）。
- **不要两条路都走**：宿主若已用门面完成了动作，就不要再把同样的文本拼成 `/queue …` 交给 `handle`，那会入队两次（命令路径没有幂等保护，`id` 去重只对带 `id` 的 `enqueue` 生效）。

除了 `create`（失败时抛 `IllegalStateException`），其余三个入口都**用返回值报告失败、不抛异常**，宿主应按返回值分流（命令路径的每种失败文案就是据此产生的）：

| 调用 | 失败形态 | 处理建议 |
|---|---|---|
| `enqueue` | 返回 `HarnessResult`：`FULL`（队列已满）/ `INVALID`（文本为空或超长）/ `CONFLICT`（id 重复）/ `DISABLED`（扩展已停用）/ `NOT_OWNER` | 提示用户稍后再试，或改为直接发送 |
| `steer` | 返回 `HarnessResult<SteerReceipt>`，`isOk() == false`，细分码见 [steer README](../../solon-ai-harness-steer/README.md) 的结果码表 | `hasReason(SteerExtension.REASON_NOT_RUNNING)` → 改走 queue；`hasReason(SteerExtension.REASON_BOX_FULL)` → 改走 queue 或直发 |
| `interrupt` | 返回 `false`：当前没有在跑的流 | 不写取消消息、不改快照；**但队列已被中断回调 `onSessionInterrupt` 清空**（见 §3.5 第 ① 步） |
| `create` | 抛 `IllegalStateException`：已有进行中 goal | 回 409，提示先 `/goal clear` |

### 3.4 取消语义与运行期开关

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

### 3.5 停 / 改 / 重排：干预的两个互斥场景

goal / queue / steer 各自只解决一个方向。真实场景里用户往往想「停」「改」「重排」，但**这三者不是一条串行管道**：steer 只服务于“任务还在跑”的窗口，`interrupt` 之后那个窗口就关了。正确做法是先判断现在处于哪个阶段，再选对应的入口：

```java
// 场景 A：任务还在跑，改主意了——不打断，直接插话（首选）
HarnessResult<SteerReceipt> r = steer.steer(session, "先别改了，回到上一个方案");
if (r.hasReason(SteerExtension.REASON_NOT_RUNNING)) {                 // 判定窗口内任务正好结束：降级为排队，而不是丢弃
    queue.enqueue(session, "回到上一个方案并跑一遍测试");
}

// 场景 B：必须停下来重来——先停，再排新任务
if (Boolean.TRUE.equals(engine.interrupt(session, InterruptMode.INTERRUPT_ALL).getValue())) {        // 停（连带清空队列）
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
| `engine.interrupt(session, InterruptMode.INTERRUPT_ALL)` | 清空 | 补写（带 `META_RUN_ID`） | 推进 | 用户主动停（默认逃这个） |
| `engine.interrupt(session, InterruptMode.CANCEL_STREAM)` | 不动 | 不写 | 推进 | 只停流：新目标顶掉旧轮次、后续由程序重发新轮（goal / 自动循环） |
| `engine.interrupt(session, InterruptMode.PREEMPT_STREAM)` | 不动 | 不写 | **不推进** | 「新一轮腾位」：旧流本该自然结束，推代数会把新轮收尾牵连进去（Stop-Replace） |
| `InterruptPlan#writeCancelEarly()` | 不动 | 只补写 | 不动 | 流已停但取消消息丢失（Web 端超时/崩溃恢复） |

三种方式返回值语义一致（值 `true` = 确有活跃流被停）；取消代数差异详见 `InterruptMode` javadoc。

它与此前的 `/interrupt` 命令**同源**（`/interrupt` 命令即调 `InterruptMode.INTERRUPT_ALL`），内部四步顺序是语义的一部分：

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
if (Boolean.TRUE.equals(engine.interrupt(session, InterruptMode.INTERRUPT_ALL).getValue())) { /* 通知前端已停止 */ }
```

> **句柄登记已上收到框架**：`ReActRequest#stream()` 订阅时会自动登记到 `SessionRuns`，
> 结束时按值注销，宿主<b>不需要（也不应该）</b>往 `session.attrs()` 里写任何 key。
> 早期版本要求宿主写 `attrs("disposable")`，那个约定已废弃；现在写它不会生效，也不会报错。

#### 两段式中断：`engine.prepareInterrupt(session)`

`engine.interrupt(session, InterruptMode.INTERRUPT_ALL)` 是「一步到位」。但有些宿主要求自定终态时序，典型是 Web：

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
| 停 | `engine.interrupt(session, InterruptMode.INTERRUPT_ALL)` | 立即终止当前流，**并清空队列** | 有在跑的流（`SessionRuns.isBusy(session)`） | 返回 `false`，不写任何消息（但推进取消代数） |
| 改 | `steer.steer(session, text)` | 注入当前任务的工作记忆，下一采样边界生效 | `activeRunId` 已建立（首轮推理开始后） | 返回 `NOT_RUNNING` / `RUN_CHANGED` |
| 重排 | `queue.enqueue(session, text)` | 登记一条待执行，等空闲后续发 | 无 | 照常入队 |

#### 运行期操作速查

三个能力各自的「运行中能做什么」分散在各插件 README，这里汇总成一张表，方便宿主对着写控制面板：

| 能力 | 能看什么 | 能改什么 | 入口 |
|---|---|---|---|
| goal | `goal.get(sessionId)` / `GoalSnapshot.toMap()` | 暂停 / 恢复 / 改配置 / 清除 | `pause` · `resume` · `update(sid, GoalPatch, stopper)` · `clear`（[goal README](../../solon-ai-harness-goal/README.md)） |
| queue | `queue.list(session)`（运行中在首位） | 取消单条 / 置顶 / 清空 / 再入队 | `cancelItem` · `promote` · `clearPending` · `enqueue`（[queue README](../../solon-ai-harness-queue/README.md)） |
| steer | `steer.pending(session)` | 取消单条 / 批量 / 清空 | `steer` · `cancelOnRun` · `cancelPending`（[steer README](../../solon-ai-harness-steer/README.md)） |
| 停 | — | 停流 + 清队列 | `engine.interrupt(session, InterruptMode.INTERRUPT_ALL)`（本节） |

三个容易搞混的边界：

- **goal 的暂停与 queue 的清空不会停当前这轮**。它们只影响「下一轮还跑不跑」：goal 的 `pause` 是状态机转 `PAUSED`，当前轮内已经在执行的工具调用不会被打断；queue 的 `clearPending` 不动 in-flight。真正“现在立刻停”只有 `InterruptMode.INTERRUPT_ALL`。
- **steer 没有“改”的能力**。已入邮箱的插话只能取消后重新投递，不支持就地编辑——文本不可变，且邮箱里若已满 5 条，取消一条才能再投一条。
- **goal 与 queue/steer 不同层**。goal 是会话级持续轮次，queue/steer 服务于单次任务；一个会话可以有 goal 同时又有排队任务，但 goal 的每轮也会走 `SessionStatus.isOccupied()` 守卫，不与宿主正在跑的任务抢。

从这张表可以直接推出两个宿主要自己处理的边界：

- **“改”的前置条件比“停”窄**：“停”只需流句柄存在，“改”需要已进入推理边界。所以任务刚被受理、模型还没开口的那一小段时间，interrupt 能成功而 steer 会失败——这不是 bug。
- **“停”与“重排”有副作用叠加**：`InterruptMode.INTERRUPT_ALL` 会经 queue 的中断回调把队列一起清掉。若宿主想“只停当前、保留已有排队”，用 `engine.interrupt(session, InterruptMode.CANCEL_STREAM)`——只停流、不写取消消息、不动队列（自动循环被新目标顶掉旧轮次的场景用它，避免历史里出现假“用户已取消”）。

 三者都**不自己开流、不做跨能力降级**：steer 邮箱满或任务未运行时如实返回状态码，要不要改走 queue 由调用方决定（`SteerMessage` 的「已接受但永不生效」由 `SteerMailbox#transferToBacklog` 在 harness 内兜底，不在此列）。场景 A 里的那次降级就是宿主侧完成的，不是框架代做。
