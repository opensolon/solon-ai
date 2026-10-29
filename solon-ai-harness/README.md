# solon-ai-harness — AI 代理编排引擎

## 简介

`solon-ai-harness` 是 Solon AI 的 **Agent 运行时与编排容器**：把「ReAct 引擎 + 一组 Talent 能力 + 子代理体系 + 命令系统 + 安全策略」装配成一个可直接跑起来的 Coding Agent。

它面向的使用者是**宿主应用**（CLI、Web、桌面、IM 网关）。宿主只需要做三件事：

1. 构造 `HarnessEngine`（给工作区、给模型、给工具权限）；
2. 把用户输入交给 `engine.handle(...)`；
3. 消费 `InputOutcome`——要决策读 `getKind()`，要结果读 `stream()`。

命令识别、子代理解析、附件装配与落盘（由宿主完成）、Prompt 构建、请求装配，在一次调用内串联完成。

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

> `build()` 有两处硬校验：`sessionProvider` 与「至少一个可用模型」。裸构造会先抛
> `sessionProvider`、再抛 `chatModel is required`。

### 1.3 跑第一轮对话

```java
AgentSession session = engine.getSession("cli");

InputOutcome outcome = engine.handle(HarnessInput.of(session, "看下当前项目结构"));
outcome.stream().blockLast();   // 事件流；也可 subscribe 逐条渲染
```

取最终答复文本：

```java
String answer = engine.handle(HarnessInput.of(session, "总结一下这个仓库"))
        .getRequest()
        .call()
        .getContent();
```

---

## 二、核心 API：`handle` + `InputOutcome`

### 2.1 唯一的输入入口

```java
// HarnessEngine
public InputOutcome handle(HarnessInput input);
public InputOutcome handle(HarnessInput input, @Nullable CommandContextFactory factory);
```

一次 `input(...)` 内部按固定顺序做六件事，顺序本身是语义的一部分：

| 步骤 | 动作 | 说明 |
|---|---|---|
| 1 | HITL 决策 | **优先于空输入判空**：审批请求通常不带文本 |
| 2 | 续流分流 | `resume(true)` 直接装配请求，不解析文本 |
| 3 | 命令识别与执行 | 有附件时不当作命令 |
| 4 | 子代理解析 + 附件前缀 | 解析 `@name` 前缀，文件引用拼 `[附件: x]` |
| 5 | Prompt 构建 | 多模态内容块、来源/附件/子代理元数据 |
| 6 | 请求装配 | 模型回退链 + 子代理 + `ReActRequest` |

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

### 2.3 `InputOutcome`：处置态 + 可选流

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
| `getPrompt()` | 装配好的 `Prompt`（含附件前缀与图片块） |
| `getAgentName()` / `getModelName()` | 生效的子代理 / 模型 |
| `stream()` | 把已发生的事忠实映射成事件流 |

`stream()` 的映射规则：

| Kind | `stream()` |
|---|---|
| `TASK` | 回执（若有）后接 `request.stream()`；两者拼成同一条流 |
| `REPLY` | `InputTextEvent`（回执为空时为空流） |
| `HITL` / `IGNORED` | 空流 |

**两条刻意不做的事**：

1. **不内置 `subscribeOn`**——调度权归宿主。Web 端必须先注册 `Disposable` 再订阅，否则忙碌判定会出现空闲窗口、同一会话开出第二条流。
2. **不对 HITL 自动续流**——三端续流时机不同（Web 要等整批审批完成、桌面要重挂拦截器、CLI 是多轮循环）。续流永远显式：`handle(HarnessInput.of(session, null).resume(true)...)`。

---

## 三、完整示例：命令

### 3.1 引擎不自带命令，宿主负责注册

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

### 3.2 写一个命令（代码方式）

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

意图的三个字段分别对应：`session`（为空表示当前会话，跨会话命令才有值）、
`prompt`（<b>为空表示续流</b>，如 `/continue`）、`model`（为空走会话级回退）。

### 3.3 写一个命令（Markdown 方式）

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

### 3.4 自定义命令上下文（宿主接管回执）

不传 factory 时使用 `BufferedCommandContextFactory`：回执写内存，从 `getCommandOutput()` 一次取回，命令内部触发的 Agent 任务**只做标记、不实际执行**。要真正执行，宿主需提供自己的工厂：

```java
InputOutcome outcome = engine.handle(
        HarnessInput.of(session, "/help"),
        (engine0, session0, command) -> new MyTerminalCommandContext(session0, command));
```

`CommandContextFactory.isRemote()` 返回 `true` 时，`cliOnly()` 的命令会被统一拦截——这就是桌面端 `/exit` 不会终止进程的原因。

### 3.5 改前缀

```java
HarnessEngine.of("/data/work", ".solon/")
        .commandPrefix("::")   // 默认 "/"
        .agentPrefix("#")      // 默认 "@"
```

改前缀后原前缀自动降级为普通文本。

---

## 四、完整示例：子代理输入

### 4.1 三种指定方式与优先级

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

提及识别的细节：

- 必须有空白边界（`@bash 内容` 有效，`@bash内容` 无效）；
- 剥掉前缀名后，其后所有空白一并吃掉；
- 名字不存在时不剥离，整段交给主代理自己判断。

### 4.2 用引擎装配好的请求执行

```java
InputOutcome outcome = engine.handle(HarnessInput.of(session, "@explore 找一下限流相关的实现")
        .model("deepseek"));

outcome.getRequest().options(o -> {
    o.toolContextPut(HarnessEngine.ATTR_CWD, "/data/work/sub-project");   // 动态换工作区
}).stream().blockLast();
```

### 4.3 直接用内置子代理

内置定义随包发布在 `META-INF/solon/ai/harness/agents/`：

| 代理 | 工具集 | 定位 |
|---|---|---|
| `general` | `*` | 通用全能，其它子代理不合适时的默认解 |
| `bash` | `list, read, bash` | 命令行执行（无文件写权限） |
| `explore` | `code, list, read, grep, glob, skill, webfetch, websearch, codesearch` | 信息探索（无写权限） |
| `plan` | `list, read, grep, glob, skill, webfetch, websearch, codesearch` | 方案规划（无写权限） |
| `git-summary` | 无（`hidden: true`） | 生成提交摘要 |

```java
AgentDefinition bashDef = engine.getAgentManager().getAgent("bash");
ReActAgent bashAgent = engine.createSubagent(bashDef).build();   // 注意：不是 bashDef.builder(engine)

bashAgent.prompt("统计各语言的代码行数")
        .session(session)
        .call();
```

### 4.4 运行时创建子代理

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

engine.handle(HarnessInput.of(session, "分析 src/main.js").explicitAgent("frontend"));
```

### 4.5 主代理委派（`task` / `multitask`）

主代理通过工具自动委派，宿主无需写代码：

```java
// 工具集含 task 时（* 或 ** 或显式 task），主代理可调用：
//   task      —— 委派单个子代理，串行
//   multitask —— 并行委派多个子代理，单次上限 multitaskMaxTasks（默认 5）
engine.setSubagentEnabled(true);   // 默认 true
engine.setMultitaskMaxTasks(3);
```

`task` 默认不在 `*`（公有）里，只有 `**`（完整）才包含——这是为了让子代理不能再委派子代理，天然防止无限嵌套。

### 4.6 把子代理定义成文件

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

## 五、工具权限

### 5.1 三级快捷权限

| 权限 | 标识 | 实际展开 |
|---|---|---|
| 核心操作 | `pi` | `read, write, edit, bash` |
| 全部公有 | `*` | `read, write, edit, glob, grep, ls, bash, skill, todo, code, codesearch, websearch, webfetch, lsp` |
| 全部完整 | `**` | `*` + `task, generate, mcp, openapi, hitl, memory` |

`ToolName` 是当前正解（`@Preview("4.0.4")`），`ToolPermission` 已 `@Deprecated` 但仍可用。

### 5.2 运行时调整

```java
engine.allowTool("bash");            // 触发主代理重建
engine.disallowTool("write");
engine.toolPermissionReset(tools, disallowedTools);
```

---

## 六、配置项参考

### 6.1 构造期

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

### 6.2 运行期

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

## 七、扩展点

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

## 八、安全机制

`hitl` 包提供三套内置干预策略，默认绑定到对应工具：

| 策略 | 覆盖 |
|---|---|
| `BashToolStrategy` | 注入防御、系统命令黑名单、路径边界、环境变更分级、网络零信任、管道安全链、破坏性操作 |
| `WriteToolStrategy` | 写入路径防御 + 规则引擎 |
| `WebToolStrategy` | 域名黑名单 + 规则引擎 |

启动 HITL 需要**三个条件同时满足**（缺一都不会报错，只是不生效）：

1. 主代理工具集包含 `hitl`（`**` 里包含）；
2. `hitlEnabled(true)`；
3. 把 `HITLInterceptor` 绑定到具体工具策略。

详见 [`docs/hitl.md`](docs/hitl.md)。

---

## 九、运行时目录

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

## 十、模块结构

### 10.1 自有源码（70 个类）

```
org.noear.solon.ai.harness
├── HarnessEngine                 ★ 引擎（Builder + 编排 + input 入口）
├── HarnessOptions                运行时配置（包私有）
├── HarnessExtension / AbsHarnessExtension   扩展 SPI
│
├── input/                        输入装配层
│   ├── HarnessInput              ★ 一次输入的完整上下文
│   ├── InputOutcome              ★ 输入处置（四态 + 事件流）
│   ├── InputAttachments          附件装配结果（多媒体块 + 文件引用；落盘由宿主负责）
│   ├── InputTextEvent            命令回执事件
│   ├── ParsedCommand             前缀感知的命令解析
│   ├── AgentMentions             提及解析（Options 承载三端差异）
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

### 10.2 依赖包（能力来源）

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

## 十一、常见坑

1. **裸引擎没有命令**——`/help` 会返回 `TASK`。测试命令必须先 `getCommandRegistry().register(...)`。
2. **HITL 续流必须显式**——`Kind.HITL` 的 `stream()` 是空流；续流用 `.resume(true)`，且**不要**传正文。
3. **`@` 提及必须带空白边界**——`@bash 内容` 有效，`@bash内容` 走普通文本。
4. **有附件时不识别命令**——避免 `/path/to/file.png` 之类被误判。
5. **`options(Consumer)` 是累加语义**——`getRequest()` 之后再追加 `options` 是安全的叠加。
6. **`stream()` 是冷流**——何时订阅、在哪个线程订阅由宿主决定；需要切换线程请自行 `subscribeOn`。

---

> **测试依赖**：`solon-test`（test scope）
> **编译辅助**：`lombok`（provided scope）
