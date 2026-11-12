# 命令输入/输出拆分落地方案（CommandInput / CommandOutput）

> 状态：方案（未实施）　范围：solon-ai-harness-core / queue / steer / loop / goal / host 文档，soloncode-cli
> 前置讨论结论：`HarnessInput` 保持可写（`onInput` 要改）；命令拿只读包装 `CommandInput`；`CommandContext` 只剩输出职责，更名 `CommandOutput`。

## 0. 现状与问题

| # | 问题 | 位置 |
|---|---|---|
| 1 | 同一次输入的属性分三条路进命令：`origin` 走 `execute` 参数，`terminal`/`locale` 走工厂参数，每加一个属性就改一次工厂签名 | `Command.java:91`、`CommandContextFactory.java:43-47` |
| 2 | 宿主上下文不覆写 `getLocale()` 就静默回落默认语言（CLI 正是如此） | `CommandContext.java:54`、soloncode `CliCommandContext` |
| 3 | 二段任务手工搬字段，漏了 `origin`、`attrs`、显式 `scope`；漏 scope 会让宿主收窄的作用域在嵌套 `handle` 里被重新解析或被拒 | `CommandDispatcher.java:146-171`、`InputPipeline.java:393-440` |
| 4 | `/queue` 入队快照、模型、属性全传 null，与扩展入队（`QueueItem.snapshotOf`）口径不一致 | `QueueCommand.java:151` vs `QueueExtension.java:235-236` |
| 5 | 会话可能为 null（`InputPipeline.java:98` 分支），只有 `ClearCommand` 判空 | `RewindCommand:70`、`ModelCommand:73`、`LoopCommand:99`、`QueueCommand` list/clear、`GoalCommand:87` |
| 6 | 文档过时：工厂/接口 Javadoc 仍写“委托给 4 参/两参版本” | `CommandContextFactory.java:37`、`Command.java:84-88`、`CommandContext.java:49` |

**当前编译基线（2026-11-12 实测）**

- harness-core 主代码能编译；测试编译不过：`CommandContractTest:127,154`、`CommandDispatcherDisabledTest:29`、`CommandDispatcherTest:53,68,78,91` 调用了已不存在的 `BufferedCommandContext` 构造器。
- soloncode `CliShell.java:233-238` 覆写的是 4 参 `create(...)`，与当前 5 参接口不符，编译不过。

两处本来都要改，本方案顺带收口。

## 1. 目标 API

### 1.1 `Command`

```java
void execute(HarnessEngine engine, CommandInput input, CommandOutput output) throws Exception;

/** 是否需要会话（默认 true）；无会话的输入执行这类命令时，分发器直接回执，不进命令 */
default boolean requiresSession() { return true; }
```

- 去掉 `InputOrigin origin` 参数，来源从 `input.getOrigin()` 取（永不为 null）。
- `requiresSession()` 见决策 D3；`HelpCommand`、`MarkdownCommand` 覆写为 false。

### 1.2 `CommandInput`（新增，`harness.command` 包，final）

```java
public final class CommandInput {
    private final HarnessInput input;      // 不提供 getter，命令拿不到可写对象
    private final ParsedCommand command;
    private final Locale locale;           // 生效语言，构造时算一次

    @Internal
    public static CommandInput of(HarnessEngine engine, HarnessInput input, ParsedCommand command) {
        return new CommandInput(input, command, engine.localeOf(input));
    }

    // 会话（requiresSession()=true 的命令可视为非 null；否则可能为 null）
    public @Nullable AgentSession getSession();

    // 命令
    public ParsedCommand getCommand();
    public String getCommandName();        // = command.getName()（已小写、无前缀）
    public String getRawInput();
    public List<String> getArgs();         // 不可变
    public @Nullable String argAt(int i);  // 越界（含负数）返回 null，见 D4
    public int argCount();
    public String getArgsJoined();

    // 渠道
    public InputOrigin getOrigin();        // 永不为 null
    public boolean isTerminal();

    // 语言：生效语言 engine.localeOf(input)，与 HarnessInput.getLocale()（原始值）含义不同
    public @Nullable Locale getLocale();

    // 本轮属性（只读）
    public @Nullable String getSystemPrompt();
    public @Nullable String getSelectedModel();
    public @Nullable String getSelectedAgent();
    public @Nullable String getPrincipal();
    public List<String> getSelectedSkills();  public boolean hasSkillSelection();
    public List<String> getSelectedMcps();    public boolean hasMcpSelection();
    public @Nullable InputScope getScope();   public boolean isScopeExplicit();
    public @Nullable String getAttr(String key);
    public Map<String, String> getAttrs();    // 不可变视图

    // 派生下一轮（返回全新 HarnessInput，原输入不受影响），见 §2
    public HarnessInput derive(@Nullable String text);
}
```

只读性已核对：`getSelectedSkills/Mcps` 返回不可变列表（`HarnessInput.java:648`），`getAttrs` 为 unmodifiable 视图（`:403`），`InputScope` 为 final 且字段 final，`ParsedCommand.args` 不可变（`ParsedCommand.java:112-114`）。用委托而不拷贝：`onInput` 在命令分发前已跑完（`InputPipeline.java:185-189`）。

刻意不暴露：附件（可写；且有附件不会进命令分支）、hitl*、resume、program、whenBusy、commandAware、agentAware、explicitAgent、agentNameValidator、trimContent、输出工厂、全部 setter。

### 1.3 `CommandOutput`（由 `CommandContext` 更名，只剩输出）

```java
public interface CommandOutput {
    void println(String text);
    default boolean supportsAnsi() { return false; }
    default String color(String text) { ... }                 // 原样保留
    void requestAgentTask(@Nullable String prompt, @Nullable String model);
    default @Nullable AgentTaskIntent getAgentTaskIntent() { return null; }
    default @Nullable String getText() { return null; }      // 原 getCommandOutput()，见 D5
}
```

删除：`isTerminal`、`getLocale`、`getSession`、`getRawInput`、`getCommandName`、`getArgs`、`argAt`、`argCount`、`getArgsJoined`、`getCommandOutput`。

### 1.4 工厂

```java
@FunctionalInterface
public interface CommandOutputFactory {
    CommandOutput create(CommandInput input);   // 只给想按渠道区分输出的宿主用
}

public class BufferedCommandOutputFactory implements CommandOutputFactory {
    public static final BufferedCommandOutputFactory INSTANCE = new BufferedCommandOutputFactory();
    public CommandOutput create(CommandInput input) { return new BufferedCommandOutput(); }
}
```

`BufferedCommandOutput` 无构造参数，只有缓冲区和意图字段。保留 `BufferedCommandOutputFactory` 的 public 无参构造（soloncode `WebGate`/`WsGate` 在用）。

### 1.5 改名对照

| 现名 | 新名 |
|---|---|
| `CommandContext` | `CommandOutput` |
| `CommandContextFactory` | `CommandOutputFactory` |
| `BufferedCommandContext` | `BufferedCommandOutput` |
| `BufferedCommandContextFactory` | `BufferedCommandOutputFactory` |
| `CommandContext.getCommandOutput()` | `CommandOutput.getText()` |
| `HarnessInput.commandContextFactory(...)` / `getCommandContextFactory()` | `commandOutputFactory(...)` / `getCommandOutputFactory()` |
| soloncode `CliCommandContext` | `CliCommandOutput` |

`InputOutcome.getCommandOutput()` **不改**（宿主侧 API，约 50 处调用，返回 String，与新类型名不冲突）。

### 1.6 `CommandDispatcher`

```java
public static @Nullable InputOutcome dispatch(HarnessEngine engine, HarnessInput input,
                                              @Nullable ParsedCommand command) throws Exception {
    if (command == null) return null;
    Command target = engine.getCommandRegistry().get(command.getName());
    if (target == null) return null;

    CommandInput cin = CommandInput.of(engine, input, command);

    if (target.isEnabled() == false)                       -> unavailable(disabledHint(name, cin.getLocale()))
    if (target.terminalOnly() && cin.isTerminal() == false) -> unavailable(unavailableHint(name, cin.getLocale()))
    if (target.requiresSession() && cin.getSession() == null)
                                                           -> unavailable(sessionRequiredHint(name, cin.getLocale()))   // 第二步

    CommandOutputFactory f = input.getCommandOutputFactory();
    CommandOutput out = (f != null ? f : BufferedCommandOutputFactory.INSTANCE).create(cin);
    target.execute(engine, cin, out);

    InputOutcome nested = null;
    AgentTaskIntent intent = out.getAgentTaskIntent();
    if (intent != null) {
        nested = engine.handle(nestedInput(cin, intent));   // 第一步：沿用旧装配；第二步：改为 derive
    }
    return InputOutcome.command(target.name(), out.getText(), command, nested);
}
```

- 删除旧的 6 参、7 参 `dispatch` 重载。
- 工厂兜底从 `InputPipeline.java:591-592` 挪进分发器；`InputPipeline.dispatchCommand` 只剩解析、作用域命令名单、`CommandDispatcher.dispatch(engine, input, command)`，其 `session` 形参可删。
- `unavailableHint` / `disabledHint` 两组公开静态方法保持不变。

## 2. 派生规则：`HarnessInput.derive(text)`（第二步）

与 `copyFor` 相邻，`@Internal public`：

```java
@Internal
public HarnessInput derive(@Nullable String text) {
    HarnessInput c = new HarnessInput(session, text);
    c.systemPrompt = systemPrompt;
    c.locale = locale;
    c.selectedModel = selectedModel;
    c.selectedAgent = selectedAgent;
    c.selectedSkills = selectedSkills;          // 不可变列表，可共享
    c.selectedMcps = selectedMcps;
    c.scope = scope;
    c.scopeResolved = scopeResolved;
    c.scopeRestored = scopeRestored;
    c.origin = origin;
    c.terminal = terminal;
    c.principal = principal;
    c.attrs = attrs == null ? null : new LinkedHashMap<>(attrs);
    c.commandAware = false;                     // 命令产物不再识别为命令
    return c;
}
```

| 类别 | 字段 | 理由 |
|---|---|---|
| 带上（本轮属性） | systemPrompt、locale、selectedModel、selectedAgent、selectedSkills、selectedMcps、scope 及两个标志、origin、terminal、principal、attrs（复制） | 命令触发的任务属于同一轮 |
| 重置（按条消息） | attachments、hitl*、resume、program、whenBusy、explicitAgent、agentNameValidator、trimContent、agentAware、commandOutputFactory | 只对原始那条文本有意义 |
| 强制 | commandAware=false | 与现有口径一致 |

作用域标志照搬是对的：命令执行时 `precheckScope` 已跑完——显式的保持显式（嵌套 `handle` 直接放行，`InputPipeline.java:398-400`）；积压还原的已被 `input.scope(narrowed)` 改成显式（`:432`）；解析所得的保持“解析所得”，嵌套时重新解析（与现有语义一致）。

分发器的二段任务改为：

```java
HarnessInput nested = cin.derive(intent.getPrompt())
        .resume(intent.isResume())
        .selectedAgent(SessionSelections.agentOf(cin.getSession()));   // D2：保持现口径
if (intent.getModel() != null) {
    nested.selectedModel(intent.getModel());                           // D1：为空时沿用本轮
}
```

## 3. 实施步骤

分两次提交。第一次是纯签名/改名，行为不变（渲染快照逐字不变）；第二次是行为修正。harness 与 soloncode 每次都要同版本合入（接口抽象方法变更是编译期破坏）。

### 第一步：签名收口与改名（行为不变）

**A1. core 命令包**（`solon-ai-harness-core/src/main/java/org/noear/solon/ai/harness/command/`）

- [ ] 新增 `CommandInput.java`（§1.2，暂不含 `derive`）。
- [ ] `CommandContext.java` → `CommandOutput.java`（§1.3）。
- [ ] `CommandContextFactory.java` → `CommandOutputFactory.java`（§1.4），删掉过时 Javadoc。
- [ ] `BufferedCommandContext.java` → `BufferedCommandOutput.java`，删掉 6 个字段与构造参数。
- [ ] `BufferedCommandContextFactory.java` → `BufferedCommandOutputFactory.java`，加 `INSTANCE`，类注释改为“工厂无状态，渠道信息一律从 `CommandInput` 取”。
- [ ] `Command.java`：`execute(engine, CommandInput, CommandOutput)`，删除 `InputOrigin` 导入与过时注释。
- [ ] `CommandDispatcher.java`：§1.6；二段任务**暂时保留旧装配**（从 `input` 读 skills/mcps/systemPrompt/locale，`selectedModel(intent.getModel())`），保证本步行为不变。
- [ ] `MarkdownCommand.java:111`：`input.getArgs()`、`output.requestAgentTask(...)`。

**A2. 内置命令**（`command/builtin/`，7 个）

逐个把 `ctx.getXxx()`（输入类）换成 `input.getXxx()`，`ctx.println/color/requestAgentTask` 换成 `output.xxx`：

- [ ] `HelpCommand`：`input.isTerminal()`、`input.argAt(0)`、`input.getLocale()`
- [ ] `ClearCommand`、`ContinueCommand`、`InterruptCommand`、`RerunCommand`、`RewindCommand`、`ModelCommand`

**A3. 引擎与输入**

- [ ] `InputPipeline.java:573-600`：去掉工厂兜底，调用 `CommandDispatcher.dispatch(engine, input, command)`；清理 `BufferedCommandContextFactory`/`CommandContextFactory` 导入。
- [ ] `HarnessInput`：字段与方法改名为 `commandOutputFactory`；同步 Javadoc（类注释 `:63`、字段注释 `:108`、`:132`、`:653`、`:844` 改为“经分发器成为 `CommandInput.isTerminal()`”）；`copyFor` 同步。
- [ ] `InputOrigin.java:28` 注释里的 `CommandContext` 改为 `CommandInput`。
- [ ] `HarnessHost.java:1258` 注释改为 `commandOutputFactory`。

**A4. 扩展命令**

- [ ] `QueueCommand`：签名；`origin` 改为 `input.getOrigin()`；私有方法参数 `CommandContext ctx` 拆为 `(CommandInput input, CommandOutput out)` 或只传 `out + Locale`。本步**不改**入队参数（仍是一串 null）。
- [ ] `SteerCommand`：签名；`InputOrigin resolved = input.getOrigin()`（去掉 null 判断）。
- [ ] `LoopCommand`：签名；12 处 `ctx.argAt/argCount/getSession/getLocale` 改到 `input`。
- [ ] `GoalCommand`：签名；`parseSpec`、`pauseSub` 等私有方法参数同上。

**A5. 测试（让 test-compile 通过、断言语义不变）**

harness-core：

- [ ] `command/CommandContractTest`：`ctx(...)` 帮手改为构造 `CommandInput` + `BufferedCommandOutput`；`:47` 的命令签名。
- [ ] `command/CommandContextDefaultsTest` → 拆为 `CommandInputTest`（参数类用例）与 `CommandOutputDefaultsTest`（`color`/`supportsAnsi`/默认 `getText()` 为 null）。`:81` 断言 `argAt(-1)` 抛异常改为返回 null（D4）。
- [ ] `command/CommandDispatcherTest:53-98`：缓冲输出用例改用无参 `BufferedCommandOutput`；`bufferedContextHonorsTerminalFlag` 改为断言 `CommandInput.isTerminal()`。
- [ ] `command/CommandDispatcherDisabledTest:28-29`：工厂改为 `in -> new BufferedCommandOutput()`；调用新 `dispatch(engine, input, command)`。
- [ ] `command/CommandRegistryAndLoaderTest:49,144`、`command/builtin/Fakes:27`、`command/builtin/BuiltinCommandsSystemTest:274`：签名与桩类缩短。
- [ ] `SaasContractTest:300,426`、`HandleAdmissionContractTest:288,344,426`、`features/.../InputCommandAwareTest:65`、`InputHandleTest:80`、`CommandAgentTaskTest:69`、`command/HelpCommandTest:64`：签名。
- [ ] `features/.../i18n/HarnessFixtures:93-94`：`ctx(...)` 改为返回一对 `(CommandInput, BufferedCommandOutput)`；`CommandInput` 用 `CommandInput.of(engine, HarnessInput.of(session, raw).locale(locale), ParsedCommand.parse(raw))` 构造。
- [ ] `features/.../i18n/HarnessRenderGoldenTest:327-395`：同上；`:388-394` 改调新 `dispatch`。
- [ ] `HarnessEngineTalentAccessorTest:133,137`、`HarnessInputSubmitOptionsTest:163`：方法改名。
- [ ] `input/InputBoundaryTest:137-144`：护栏改为检查 `CommandOutputFactory.java` 存在且为 `interface CommandOutputFactory`。
- [ ] `HarnessPackageBoundaryTest:49` 注释改名。

扩展模块：

- [ ] loop `LoopExtensionTest:487` 的 `StubContext`。
- [ ] queue `QueueCommandTest`、steer `SteerCommandTest`（`ctx.getSession()` 改为从 `CommandInput` 取）。

**A6. soloncode-cli**

- [ ] `command/CliCommandContext.java` → `CliCommandOutput.java`：只留 `Terminal terminal` 字段（`reader` 未被使用，删除）、`println`、`supportsAnsi()=true`、意图字段。
- [ ] `entry/cli/CliShell.java:225,232-240`：`.commandOutputFactory(in -> new CliCommandOutput(terminal))`，删除匿名类及 `CommandContext/CommandContextFactory/ParsedCommand/AgentSession` 无用导入。
- [ ] `command/builtin/ExitCommand.java:63`：签名。
- [ ] `api/web/WebGate.java:108`、`api/desktop/WsGate.java:108`：`in.commandOutputFactory(new BufferedCommandOutputFactory())`（也可直接删除这两行，默认就是缓冲式）。

**A7. 验收**

- harness-core、queue、steer、loop、goal、host 全量测试；失败数不高于既有基线（见 §5）。
- `HarnessRenderGoldenTest` 以及各扩展的 RenderGolden、两层 i18n 覆盖率测试全部不重生成、逐字通过。这一步只改签名，回执文案不该有任何变化。
- soloncode-cli 全量测试。

### 第二步：行为修正

**B1. 派生**

- [ ] `HarnessInput.derive(text)`（§2）。
- [ ] `CommandInput.derive(text)` 委托给它。
- [ ] `CommandDispatcher` 二段任务改用 `cin.derive(...)`，删掉手工复制字段的代码。

**B2. 会话为空**

- [ ] `Command.requiresSession()`（默认 true）；`HelpCommand`、`MarkdownCommand` 覆写 false。
- [ ] 分发器在 `terminalOnly` 判断之后检查；新增 `HarnessI18nKeys.COMMAND_SESSION_REQUIRED = "command.session_required"`，在 `harness.yml` / `harness_zh.yml` 补文案（中文建议：“需要会话才能执行，请在会话中使用。”），新增公开静态 `sessionRequiredHint(name, locale)`，与另外两个 hint 同形。
- [ ] 去掉 `ClearCommand.java:58` 自带的判空（由分发器统一处理）。

**B3. `/queue` 与扩展入队对齐**

```java
HarnessInput next = input.derive(text);
if (next.isScopeExplicit() && next.getScope().isDeferrable() == false) {
    out.println(QueueI18n.get(input.getLocale(), QueueI18nKeys.COMMAND_QUEUE_SCOPE_NOT_DEFERRABLE));
    return;
}
int n = QueueStore.enqueue(session, next.getText(), next.getOrigin(), null, next.getSelectedModel(),
        next.getSelectedAgent(), next.getAttrs(), QueueItem.snapshotOf(next),
        limits.maxSize, limits.maxTextLength);
```

- [ ] 新增 `QueueI18nKeys.COMMAND_QUEUE_SCOPE_NOT_DEFERRABLE`，文案参照 harness 的 `INPUT_BUSY_SCOPE`（`SessionControl.java:302-304`）。

**B4. 新增回归测试**

| 测试 | 断言 |
|---|---|
| core `HarnessInputDeriveTest` | §2 表格逐字段：带上的相等，重置的为默认值；`attrs` 是副本（改派生不影响原输入）；三种作用域状态（显式、还原后收窄、解析所得）派生后的 `isScopeExplicit/isScopeRestored` |
| core `CommandInputTest`（补） | `getAttrs()` / 列表修改抛 `UnsupportedOperationException`；`getLocale()` 等于 `engine.localeOf(input)`（输入无语言时取引擎语言） |
| core `features/.../CommandNestedTurnTest` | ① `scopeRequired` 且无解析器、输入带显式 scope 时 Markdown 命令的二段任务不被拒，且请求作用域等于原 scope；② origin、attrs、systemPrompt、locale、skills/mcps 带进二段；③ intent 未指定模型时沿用本轮 `selectedModel`（D1）；④ 二段 `commandAware=false` |
| core 会话为空 | `HarnessInput.of(null, "/model")` → REPLY + `sessionRequiredHint`，不抛 NPE；`HarnessInput.of(null, "/help")` 正常执行 |
| queue `QueueCommandSnapshotTest` | 同一输入（带 skills、systemPrompt、locale、显式可保存 scope、attrs、model）经 `/queue x` 和经 `QueueExtension.offer` 入队，`QueueItem` 的快照/模型/属性/来源一致；不可保存的显式 scope 回执拒绝且不入队 |

**B5. i18n 配套**

- harness-core、queue 用 `-Dgolden.update=true` 重生成 messages 快照，只应新增两条 key。
- 新 key 必须进各自 `XxxI18nCoverageTest` 的两层 100%：被产品代码读取过，并被断言过（用 `FrozenMessages` 取期望值，不用 `Xxx.get` 现取）。用到的新用例类要登记到 SUITES。
- 新增的会话为空、scope 不可保存两种回执补进 RenderGolden（中英各一份）。

## 4. 文档

- [ ] `solon-ai-harness-core/README.md`：`:368`、`:384` 示例签名改为 `execute(engine, input, output)`；`:378` 改述“`CommandInput` 是只读输入、`CommandOutput` 是端适配输出”；`:428-443` 工厂示例改为 `.commandOutputFactory(in -> new MyTerminalOutput(terminal))`，并说明 `terminal` 决定 `CommandInput.isTerminal()`；`:1054` 目录说明；`:1086` 改名。
- [ ] `solon-ai-harness-core/docs/ENGINE-L1.md:96,106,312,314`：改名，`isTerminal()` 改到 `CommandInput`。
- [ ] `solon-ai-harness-host/README.md:575`、`docs/QUICKSTART-SAAS.md:198`、`solon-ai-harness-steer/README.md:438`：`commandOutputFactory`。
- [ ] `UPDATE_LOG.md` vNEXT 新增一条，写明：
  - 动机（§0 的 1-4 项）
  - 改名对照表（§1.5）
  - `execute` 新签名、`requiresSession()`
  - 行为变化：二段任务保留显式 scope/origin/attrs，模型沿用本轮（D1）；`/queue` 保存快照；无会话执行需要会话的命令时回执而非 NPE
  - 迁移指引：自定义命令把 `ctx.getArgs()/argAt/getSession/getLocale/isTerminal` 改到 `input`，`ctx.println` 改到 `output`；宿主工厂改为 `in -> new XxxOutput(...)`
- [ ] 旧条目（`:414-438`、`:458-459`）是历史记录，不改。
- [ ] soloncode `UPDATE_LOG.md` 同步一条；`.soloncode/memory/shared__soloncode-command-dispatch-contract.md` 中 `CommandContext` 相关表述随后更新。

## 5. 验证命令

```bash
# 第一步 / 第二步各跑一次
mvn -o -q install -DskipTests -pl solon-ai-harness-core
mvn -o test -pl solon-ai-harness-core
mvn -o -q install -DskipTests -pl solon-ai-harness-core,solon-ai-harness-queue,solon-ai-harness-steer,solon-ai-harness-loop,solon-ai-harness-goal
mvn -o test -pl solon-ai-harness-queue,solon-ai-harness-steer,solon-ai-harness-loop,solon-ai-harness-goal,solon-ai-harness-host

# soloncode（依赖上面已 install 的 harness 模块）
cd @soloncode-source/soloncode-cli && mvn -o test

# 残留检查：应无输出
grep -rn --include=*.java -E "CommandContext|commandContextFactory|getCommandContextFactory" . @soloncode-source | grep -v /target/
```

失败基线（来自上次一期回归记录）：harness-core 707 例 32F+5E（ToolStrategy/Write/Web/Bash 策略、TaskTalent、Accessor 为既有失败）；soloncode-cli 1227 例 3F（AgentSettingsController/GeneralModelDefaults/MountSettings 为既有失败）。当前 harness-core 测试编译不过，第一步 A5 完成后应先跑一次，确认失败集合与上述既有失败一致，再进入第二步。

## 6. 风险

| 风险 | 应对 |
|---|---|
| 第三方自定义命令编译破坏 | 4.2 未发布，一次改完；UPDATE_LOG 写迁移指引。`Command` 标了 `@Preview("4.0")` |
| 二段任务模型口径变化（D1） | 只影响“intent 未给模型、但本轮输入带了 selectedModel”的情形；B4 有专门用例 |
| 无会话时命令行为变化（D3） | 原来会 NPE，改成回执，属于修复；`/help` 与 Markdown 命令保持可用 |
| `getAttrs()` 是视图不是快照 | 命令执行期间输入不再变化（`onInput` 已跑完），可以接受；`derive` 内部做了复制 |
| 回滚 | 两步各自独立提交，可单独回退；第二步回退只会恢复旧的字段丢失问题，不影响编译 |

## 7. 待确认的决策

| # | 问题 | 建议 |
|---|---|---|
| D1 | 二段任务在 intent 未指定模型时：沿用本轮 `selectedModel`，还是像现在一样清空后退回会话级模型 | 沿用本轮（与普通消息一致） |
| D2 | 二段任务的子代理：继续用会话级选择覆盖，还是本轮 `selectedAgent` 优先 | 本次保持现状（会话级覆盖），不扩大变更面 |
| D3 | 无会话时：加 `Command.requiresSession()` 由分发器统一回执，还是各命令自行判空 | 加 `requiresSession()`，默认 true |
| D4 | `argAt` 负数下标：返回 null（`ParsedCommand` 现行），还是抛异常（`CommandContextDefaultsTest:81` 现行） | 返回 null |
| D5 | `CommandOutput` 上的回执取值方法名：`getText()` 还是 `getBufferedText()` | `getText()` |
