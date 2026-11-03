# AiSwitchable 全面审查报告与配套方案

> 审查基线：commit `899687532`（统一AI可开关组件契约，新增 AiSwitchable 与 AbsSwitchable…）
> 审查范围：solon-ai-core / solon-ai-agent / solon-ai-harness / solon-ai-mcp / solon-ai-talents
> 约定：`ToolProvider`、`FunctionTool` 为原子单位，本次不纳入（按用户要求）

---

## 一、现状盘点：契约层已到位，应用层只覆盖了一半

### 1.1 已实现开关的接口/基类（本次 commit）

| 组件 | 位置 | 形态 | 是否真的能关 |
|---|---|---|---|
| `AiSwitchable` / `AbsSwitchable` | `solon-ai-core/.../ai/AiSwitchable.java:24`、`AbsSwitchable.java:24` | 接口 + 基类 | — |
| `Talent` | `chat/talent/Talent.java:37` | `extends AiSwitchable` | 是（`TalentUtil.java:46`） |
| `AbsTalent` / `TalentDesc` / `McpTalentClient` / `McpTalentServer` | 各处 | `extends AbsSwitchable` | 是 |
| `ToolInterceptor` / `ChatInterceptor` | `chat/interceptor/ToolInterceptor.java:27` | `extends AiSwitchable` | 是（三条链均过滤） |
| `AgentInterceptor` / `ReActInterceptor` / `SimpleInterceptor` / `TeamInterceptor` | `solon-ai-agent/.../agent/*` | `extends AiSwitchable`（经 `AbsXxxInterceptor`） | 是（ReAct/Team/Simple 各回调点均判） |
| `HarnessExtension` / `AbsHarnessExtension` | `harness/HarnessExtension.java:39` | `extends AiSwitchable` | 部分（见 2.3） |
| `Command` / `AbsCommand`（新增） | `harness/command/Command.java:28` | `extends AiSwitchable` | 是（`CommandRegistry.find`） |
| `McpClientProvider` | `mcp/client/McpClientProvider.java:98` | `implements AiSwitchable` | 否（见 2.4） |

### 1.2 执行侧生效点（已核对）

- Chat 层：`ChatRequestDescDefault.java:208-212`（onPrepare）、`CallChain.java:52-54`、`StreamChain.java:62-66`、`ToolChain.java:55-59`（含你新加的 ToolInterceptor 链，已生效）。
- ReAct 层：`ReActAgent.java:253,331`、`ReasonTask.java:157,194,284,382`、`ActionTask.java:100,126,180,249,364,406`、`PlanTalent.java:89,121,166`。
- Team 层：`TeamAgent.java:234,256,321`、`Agent.java:137,203`、`SupervisorTask.java:79,179,227,586`。
- Simple 层：`SimpleAgent.java:164,216,336`。
- Talent 层：`TalentUtil.java:46`。
- 命令层：`CommandRegistry.find()`（`CommandRegistry.java:65-72`）。

**结论：拦截器这条线你担心的问题不存在，`ToolInterceptor` 处理链已经是通的。** 真正的缺口在别处（第二节）。

---

## 二、缺口与不一致清单

### 2.1 【A 类·真缺口】`ContextCompressionInterceptor` 关不掉

`solon-ai-agent/.../react/intercept/ContextCompressionInterceptor.java:72`

```java
public class ContextCompressionInterceptor implements ReActInterceptor {
```

它是**唯一**没有继承 `AbsReActInterceptor` 的 ReAct 拦截器实现类。因为 `AiSwitchable` 的两个方法都是 `default`，它编译通过、`isEnabled()` 恒 true、`setEnabled(false)` **静默无效**。

影响：它是 `AgentFactory.java:72` 给每个子代理挂的默认拦截器，`HarnessEngine.getCompressionInterceptor()` 也是对外暴露的。宿主调 `engine.getCompressionInterceptor().setEnabled(false)` 会以为关掉了压缩，实际还在压。

### 2.2 【A 类·行为缺陷】命令被禁用后，`/xxx` 会掉进模型提示词

`CommandRegistry.java:65-72`

```java
public Command find(String name) {
    Command tmp = commands.get(name);
    if (tmp != null && tmp.isEnabled()) {
        return tmp;
    } else {
        return null;
    }
}
```

`CommandDispatcher.java:62-64` 拿到 null 就 `return null`，`HarnessEngine.dispatchCommand()`（`HarnessEngine.java:1596-1615`）也返回 null，于是 `handle()` 继续走第 4 步（`HarnessEngine.java:1451` 之后）——**整条 `/clear` 字符串被当成用户输入喂给模型**。

`find()` 把「未注册」与「已禁用」压成了同一个 null，这是语义丢失，不是过滤。

### 2.3 【B 类·不一致】命令可见性三处口径不同

| 入口 | 是否过滤 `isEnabled` | 位置 |
|---|---|---|
| `find(name)` | 过滤 | `CommandRegistry.java:65` |
| `names()` | 过滤 | `CommandRegistry.java:76-84` |
| `all()` | **不过滤** | `CommandRegistry.java:87-93` |

`HelpCommand.java:63` 用的是 `all()`，所以 `/help` 会列出已被禁用的命令，用户点进去又执行不了（`find` 返回 null）。同时 `names()` 是给 Tab 补全用的，`all()` 是给 help 用的——两个「可见集合」必须一致。

### 2.4 【B 类·不一致】扩展禁用了，但它的门面 API 还在工作

三个扩展 `setEnabled` 只把内部 Command/Interceptor/Talent 关掉（`GoalExtension.java:87-99`、`QueueExtension.java:53-56`、`SteerExtension.java:78-82`），但：

- `QueueExtension`：`enqueue/drainOne/ack/cancelItem/promote/view…`（`QueueExtension.java:61-139`）全部无 `isEnabled` 判断 → 扩展 `isEnabled()==false` 时队列照样能收能发。
- `SteerExtension`：`steer(...)`、`cancel(...)`（`SteerExtension.java:96-104`）无判断 → 插话照样入邮箱，只是拦截器不消费，消息会一直积压到重新启用。
- `GoalExtension`：**`GoalScheduler` 仍在跑**。`initialize()` 里 `goalScheduler = … new GoalScheduler(goalEngine, workspaceId)`（`GoalExtension.java:137-141`）只由 `schedulerDisabled` 控制，与 `enabled` 无关 → 关掉 goal 扩展后，定时任务仍会 `engine.handle()` 拉起目标会话。

### 2.5 【B 类·不对称】`isEnabled` 在扩展的两条生命周期上口径相反

| 时机 | 是否看 `isEnabled` |
|---|---|
| `initialize()` | **不看**（本 commit 删了 `HarnessEngine.java:1013`、`1985` 的守卫） |
| `configure()` | **看**（`AgentFactory.java:112-116` 仍是 `if (extension.isEnabled())`） |

后果：
1. 构造期加一个 `enabled=false` 的扩展 → `onAttach` 照跑（注册命令、建调度器、建 GoalEngine），`configure` 跳过（Talent 不挂）。半开半闭。
2. 运行期把扩展从 false 改回 true → `configure` 不会重跑，被跳过的 Talent/Interceptor **永远不会挂上**，除非重建 mainAgent。

这个不对称是当前最容易被宿主踩到的语义坑。

### 2.6 【A 类·真缺口】`AiSwitchable` 默认 no-op 造成的「假开关」

`AiSwitchable.java:28-37` 两个方法都是 default（`isEnabled()`→true，`setEnabled`→空）。这是为兼容既有实现类必须的，但它有个副作用：**任何 new 出来的 AiSwitchable 实现，开关都是静默无效的**，只有显式选了 `AbsSwitchable` 才会真生效。`ContextCompressionInterceptor` 就是活例子（2.1）。

同类风险：`ToolInterceptor`、`Talent`、`Command` 三个接口的直接实现者（不继承 AbsXxx 的，如 `TerminalTalentProxy.java:18` 这种代理除外，它已正确委托）。

### 2.7 【C 类·风险】`McpClientProvider.setEnabled(false)` 运行期不撤工具

`McpClientProvider.java:150-158` 改的是 `AtomicBoolean`，但消费点在**注册期**：
`McpGatewayTalent.java:109-116`（`addMcpServer` 里 `if (mcpProvider.isEnabled() == false) return`）。

所以运行期 `setEnabled(false)` 后：
- `McpGatewayTalent.allTools` 里那批工具仍在，模型仍能看到、仍能调用；
- 调用时才可能失败。

按你的约定 `ToolProvider` 是原子单位，这条列为**已知限制**，但至少要文档化，或者给 `McpGatewayTalent` 补一个 `refreshMcpServer(name)` 复用已有逻辑（`McpGatewayTalent.java:229-236` 那段旧代码其实已经是 refresh 的实现）。

### 2.8 【C 类·风险】`AgentDefinition.Metadata.enabled` 从未生效

`AgentDefinition.java:204` 有 `enabled = true`，但：
- `AgentManager.getAgents()`（`AgentManager.java:96-110`）只过滤 `isHidden()`，不过滤 `enabled`；
- `TaskTalent.getInstruction()`（`TaskTalent.java:84`）直接把全部 agent 列给模型；
- `GenerateTalent.java:91` 还主动 `setEnabled(true)`。

即 agent md 里写 `enabled: false` 完全无效果。这不是 `AiSwitchable` 的活（`Metadata` 是值对象），但属于同一主题下的开关缺口，建议一并处理。

### 2.9 【D 类·编译】三个测试类已编译不过

```
GoalExtensionTest.java:214         new GoalExtension(external, engine)         // 构造器已删
QueueExtensionTest.java:105        new QueueExtension(false)                   // 构造器已删
SteerExtensionTest.java:49         new SteerExtension<>(() -> null, null, false) // 三参构造已删
```

`mvn -pl solon-ai-harness -am test-compile` 报 3 处 ERROR。原因是本 commit 删掉了 `commandEnabled` 开关，但测试没跟着改。**这是当前主干测试红。** 修法见方案 P0-1。

### 2.10 其它小问题（顺手修）

- `UtilityCommand` 类冗余：`MarkdownCommand.java:39`、`SteerInterceptor.java:59` 等 `extends AbsXxx implements Xxx` 重复声明接口，去掉 `implements`。
- `CommandRegistry.java:22` 用 `import java.util.*;`，与文件内既有显式 import 风格不一致。
- `AbsHarnessExtension.java`、`CommandRegistry.java` 缺行尾换行（`\ No newline at end of file`）。
- `Command.java:26` 新增 `@since 4.0` 与实现类 `2026.4.28` 并存，建议统一。

---

## 三、还应加（或不加）`AiSwitchable` 的清单

### 3.1 建议加 / 改为 AbsSwitchable

| # | 目标 | 类型 | 理由 | 优先级 |
|---|---|---|---|---|
| 1 | `ContextCompressionInterceptor` | 改继承 `AbsReActInterceptor` | 唯一假开关的拦截器实现类（2.1） | P0 |
| 2 | `TeamProtocol` | 接口 `extends AiSwitchable`；`TeamProtocolBase extends AbsSwitchable` | Team 的执行协议（Sequential/Swarm/Market/Hierarchical）是典型「想扣掉某个阶段」的组件；目前 `trace.getProtocol()` 的 `onAgentStart/onAgentEnd` 调用**完全不判 enabled**，加了要在 `TeamAgent`/各 Protocol 调用点补判断 | P2 |
| 3 | `Channel` | 接口 `extends AiSwitchable` | IM 通道（微信/飞书/钉钉）天生需要单独停用；目前 0 个实现类在主干，加接口成本低 | P2 |
| 4 | `GoalValidator` | 不建议：`@FunctionalInterface`，加方法会破坏 lambda | — | — |
| 5 | `CompressionStrategy` / `HITLStrategy` | 不建议：`@FunctionalInterface`，同上；若要开关，应由持有它的 `ContextCompressionInterceptor` / `HITLInterceptor` 通过策略替换实现 | — | — |

### 3.2 明确不加（契约边界要写进 javadoc）

| 目标 | 不加的理由 |
|---|---|
| `Mount` | `enabled` 是 `final`，构建期不可变（`Mount.java:60`）；改成可开关会破坏 `MountManager` 的注册期过滤（`MountManager.java:211,254`）缓存假设 |
| `SkillDescriptor` / `AgentDescriptor` | 值对象，每次 catalog 查询都新建，没有稳定身份承载开关 |
| `Metadata.enabled`（AgentDefinition） | 值对象，但**语义要落地**（见 2.8）：在 `AgentManager.getAgents()` 过滤即可，不必实现接口 |
| `ChatConfig.enabled`（`AiConfig.java:209-215`） | 模型配置的「可用性」，与「运行期开关」是两条轴；`HarnessOptions.getModelOrNil` 已按它过滤 |
| `McpServerParameters` / `LspServerParameters` / `ApiSource` | 同上，是配置值对象；且各自已有 `enabled` 字段，语义为「注册期是否纳入」 |
| `LoopMonitor` / `LoopDebugger` | `enabled` 是 `final` 且构造期决定「起不起线程池」，属于生命周期而非开关 |

---

## 四、配套方案

分四批，每批独立可编译可测。

### P0-1 修复主干测试（必须最先做，否则任何改动都无法验证）

按 `commandEnabled` 移除后的新语义重写断言：

```java
// QueueExtensionTest.java:99-106 —— 改为「禁用命令」而非「不注册」
@Test
void extensionDisabledHidesCommandButKeepsFacade() {
    HarnessEngine engine = newEngine();
    QueueExtension queue = new QueueExtension();
    queue.setEnabled(false);
    engine.addExtension(queue);

    assertNull(engine.getCommandRegistry().find("queue"), "禁用后命令不可见");
    assertTrue(engine.getCommandRegistry().names().stream().noneMatch("queue"::equals));
}

// SteerExtensionTest.java:44-51 —— 同理
HarnessEngine engine2 = newEngine();
SteerExtension<Object> ext = new SteerExtension<>(() -> null, null);
ext.setEnabled(false);
engine2.addExtension(ext);
assertNull(engine2.getCommandRegistry().find("steer"));

// GoalExtensionTest.java:214 —— 构造器已删，改走 builder
GoalExtension ext = GoalExtension.builder(null).build();   // 存量形态断言需同步调整
```

> 注：`GoalExtension` 双参构造（外部 GoalEngine 直接接管）被删了，但 `initialize()` 里仍留着 `if (goalEngine != null && bridge == null) return;` 这条守卫（`GoalExtension.java:111-113`），说明「外部 GoalEngine 形态」这个场景还在。**建议保留一个 `GoalExtension.of(GoalEngine, HarnessEngine)` 静态工厂**替代被删构造器，而不是让测试去迁就实现。

### P0-2 `ContextCompressionInterceptor` 接入 AbsSwitchable

```java
public class ContextCompressionInterceptor extends AbsReActInterceptor {
```

一行改动。同时 `StopLoopInterceptor.java:62` 已有的 `if (!isEnabled())` 属冗余，可保留（防御性）。

### P0-3 `CommandRegistry` 区分「不存在」与「已禁用」

不要继续用 null 表示三态。推荐最小侵入方案：**`find` 保持过滤语义不变，新增 `get`（不过滤）**，让分发器显式判禁用：

```java
public class CommandRegistry {
    /** 原始查找（不过滤启用状态） */
    public Command get(String name) { return commands.get(name); }

    /** 生效查找：未注册或已禁用均返回 null */
    public Command find(String name) {
        Command tmp = get(name);
        return (tmp != null && tmp.isEnabled()) ? tmp : null;
    }

    /** 生效命令列表（help 用），过滤禁用 */
    public List<Command> all() {
        return commands.values().stream()
                .filter(Command::isEnabled)
                .sorted(Comparator.comparing(Command::name))
                .collect(Collectors.toList());
    }

    public List<String> names() { /* 不变 */ }
}
```

`CommandDispatcher.dispatch` 改为：

```java
Command target = registry.get(command.getName());
if (target == null) {
    return null;                       // 真的不是命令 → 当普通输入
}
if (target.isEnabled() == false) {
    return InputOutcome.unavailable(target.name(),
            "/" + target.name() + " 已停用" + COMMAND_UNAVAILABLE_HINT, command);
}
```

这样：`/clear` 被禁用时用户得到明确回执，而不是把 `/clear` 当 prompt 发给模型。

> 影响面：`CommandDispatcher.java:62`、`HelpCommand.java:63`（改用 `all()` 已自动过滤）。`find()` 现有调用点只有 `CommandDispatcher` 一处，改动可控。

### P1-1 扩展的「门面 + 调度」也要服从 `isEnabled`

三个扩展分别补：

**QueueExtension** —— 门面加守卫（`enqueue/drainOne/importItems` 三个写入口即可，读接口可保持可用）：

```java
public int enqueue(AgentSession session, String text, InputOrigin origin) {
    if (isEnabled() == false) {
        return -1;   // 与「队列满返回 -1」一致，宿主无需新分支
    }
    ...
}
```

**SteerExtension** —— `steer()` 返回既有失败态，`cancel()` 保持可用（清理不受开关影响）：

```java
public SteerResult steer(...) {
    if (isEnabled() == false) {
        return SteerResult.rejected("steer extension disabled");
    }
    ...
}
```

**GoalExtension** —— 最关键，`setEnabled` 必须联动调度器：

```java
@Override
public void setEnabled(Boolean enabled) {
    super.setEnabled(enabled);

    if (enabled != null && goalScheduler != null) {
        if (enabled) {
            goalScheduler.resumeScheduleOnly(null);   // 已有方法，见 GoalExtension 门面
        } else {
            goalScheduler.unscheduleAll();            // 若没有此方法则遍历 scheduled 集合 unschedule
        }
    }

    if (goalCommand != null) goalCommand.setEnabled(enabled);
    if (goalTalent != null)  goalTalent.setEnabled(enabled);
}
```

> `GoalScheduler` 需要一个 `unscheduleAll()`（或复用 `unschedule(sessionId)` 遍历 `scheduled` 集合，`GoalScheduler.java:50` 有这个 Set）。**注意**：`setEnabled(false)` 应只停「未来调度」，不介入当前正在跑的那一轮。

### P1-2 消除 `onAttach` / `configure` 的 isEnabled 不对称

二选一，建议**选 A**（改 `configure` 侧）：

```java
// AgentFactory.java:112-116
for (HarnessExtension extension : engine.getExtensions()) {
    extension.configure(engine, agentDefinition.getName(), builder);   // 一律回调
}
```

理由：
- `onAttach` 已经无条件调用（本 commit 改的），把 `configure` 也改成无条件，两条生命周期口径一致；
- 扩展内部已经把 `enabled` 传播给它的 Command/Talent/Interceptor（Goal/Queue/Steer 都做了），**挂上去但内部是 disabled**，比「不挂」更容易在运行期重新启用（因为 `setEnabled(true)` 立即生效，无需重建 agent）；
- 唯一的成本是：未传播 enabled 的第三方扩展，「禁用」会失效。所以配套要在 `AbsSwitchable` 的 javadoc 里写清「若你向外暴露子组件，必须在 setEnabled 里传播」。

同时把 `HarnessEngine.addExtension()`（`HarnessEngine.java:1005-1025`）的注释补上这条口径。

> 若担心第三方扩展行为变化，可选 B：`configure` 保留守卫，但 `setEnabled` 改 true 时由 engine 触发重建（见 P1-3）。成本更高，不推荐。

### P1-3 给 HarnessEngine 一个统一开关入口

宿主不应该按组件类型记一堆 setter。建议加：

```java
/**
 * 启用/禁用一个扩展（会传播到其内部命令/才能/拦截器，并重建主代理）
 */
public void setExtensionEnabled(HarnessExtension extension, Boolean enabled) {
    if (extension == null) return;

    extension.setEnabled(enabled);

    agentLock.lock();
    try {
        if (this.mainAgent != null) {
            this.mainAgent = createMainAgent();
        }
    } finally {
        agentLock.unlock();
    }
}

/**
 * 列出可开关组件（供宿主做「设置面板」）
 */
public Collection<AiSwitchable> getSwitchables() { ... }   // extensions + 已知拦截器 + 已知 talent
```

并把既有的 `setMemoryEnabled/setSandboxEnabled/setHitlEnabled/setSubagentEnabled`（`HarnessEngine.java:573-639`）保留（它们是**语义化别名**，带 config 回写），内部可委托到统一入口。

> `getSwitchables()` 是否要做，取决于宿主是否要「列出全部开关」。如果只做 P0/P1，可以先不做。

### P2-1 `AgentDefinition.Metadata.enabled` 落地

```java
// AgentManager.java:107-110
return all.values().stream()
        .filter(a -> !a.getMetadata().isHidden())
        .filter(a -> a.getMetadata().isEnabled())      // 新增
        .collect(Collectors.toList());
```

注意 `AgentManager.getAgent(name)`（按名取）**不能**过滤 enabled —— 否则禁用的子代理会退化成 `IllegalArgumentException`，而正确行为应是「列表里看不到，但显式 @ 提到时仍可解析（或给出明确提示）」。这一点需要你拍板。

### P2-2 `McpClientProvider` 运行期开关（可选）

若要在运行期真撤工具，给 `McpGatewayTalent` 加：

```java
/**
 * 按当前 isEnabled() 状态重扫某 MCP server 的工具集
 */
public McpGatewayTalent refreshMcpServer(String name) { ... }   // 复用现有 refresh 逻辑 229-236 行
```

引擎侧在 `setEnabled(false)` 后调用一次。若不做，就在 `setEnabled` javadoc 写明「仅注册期生效，运行期需 refresh」。

---

## 五、验证计划

| 批次 | 验证命令 | 断言点 |
|---|---|---|
| P0-1 | `mvn -pl solon-ai-harness -am test-compile` | 编译通过 |
| P0-2 | 新增 `ContextCompressionInterceptorSwitchTest` | `setEnabled(false)` 后 `onReasonStart` 不压缩 WorkingMemory |
| P0-3 | 新增 `CommandDisabledDispatchTest` | 禁用 `/clear` 后 `handle("/clear")` 返回 `InputOutcome` 为 unavailable，**不是** TASK |
| P1-1 | 扩 `QueueExtensionTest`/`SteerExtensionTest`/`GoalExtensionTest` | 禁用后 enqueue 返回 -1、steer 被拒、`hasScheduledGoals()==false` |
| P1-2 | 扩 `SteerExtensionTest` | `enabled=false` 的扩展仍被 `configure` 回调，但 `getInterceptor().isEnabled()==false` |
| P1-3 | 新增 `HarnessEngineSwitchableTest` | `setExtensionEnabled(ext,false)` 后 mainAgent 已重建，且新 agent 上该扩展的 talent/interceptor 均为 disabled |

回归重点：`solon-ai-agent` 全量（拦截器语义改动风险最高）+ `solon-ai-harness` 全量。

---

## 六、需要你决策的三点

1. **`GoalExtension` 存量双参构造**：是补回 `static of(GoalEngine, HarnessEngine)` 工厂（便利旧宿主），还是彻底废弃（测试改 builder）？我倾向补工厂——`initialize()` 里那条 `bridge == null` 守卫说明这个场景仍然成立。
2. **`configure` 是否无条件回调**（P1-2 方案 A/B）：A 更简洁但要求第三方扩展自己传播 enabled；B 更保守但需要重建机制。我倾向 A。
3. **`AgentManager.getAgent(name)` 对禁用子代理的行为**：抛异常 / 返回但仍可执行 / 返回但附提示？我倾向「返回且可执行，只是不出现在可用列表里」，对模型语义最自然。

---

## 七、实施结果（已落地）

### 已完成的改动

| 编号 | 文件 | 改动 | 验证 |
|---|---|---|---|
| P0-2 | `ContextCompressionInterceptor.java:72` | `implements ReActInterceptor` → `extends AbsReActInterceptor`，假开关修复 | `ContextCompressionInterceptorTest` 79 项全过 |
| P0-2 | `LoopingTeamInterceptor.java:37` | `implements TeamInterceptor` → `extends AbsTeamInterceptor`，假开关修复 | team 相关用例全过 |
| P0-3 | `CommandRegistry.java` | 新增 `get(name)`（不过滤）；`find()` 改为 `get` + 判 `isEnabled`；`all()` 加 `filter(Command::isEnabled)` | 新增 `CommandDispatcherDisabledTest` 3 项 |
| P0-3 | `CommandDispatcher.java` | 分发改用 `get`：未注册 → `null`（普通输入）；已停用 → `unavailable` 回执 + `COMMAND_DISABLED_HINT` | 同上 |
| P1-1 | `GoalExtension.setEnabled` | 关闭时 `goalScheduler.stopAll()`；`scheduleNow`/`resumeScheduleOnly` 增加 `isEnabled()` 守卫 | `GoalExtensionTest` 10 项 |
| P1-1 | `GoalExtension.of(GoalEngine, HarnessEngine)` | 补回存量工厂（不注册命令、不装配调度） | `legacyConstructorStillAttachesTalentOnly` |
| P1-2 | `QueueExtension` | `enqueue`/`drainOne` 停用返回 -1/false；ack/cancel 不受控 | `disabledExtensionRejectsEnqueue` |
| P1-2 | `SteerExtension.steer` | 停用返回新增的 `SteerStatus.DISABLED`；`SteerCommand` 增加对应回执 | `disabledExtensionRejectsSteer` |
| P1-3 | `AgentFactory.java:112` | 去掉 `isEnabled()` 守卫，`configure` 无条件回调 | `HarnessExtensionLifecycleTest` |
| P1-4 | `HarnessEngine.setExtensionEnabled` | 新增统一入口：传播 + 重建 mainAgent | `setExtensionEnabledTogglesAbsExtensionAndRegistersNewOne` |
| 契约 | `AiSwitchable.java` | javadoc 写死：默认 no-op 的成因、继承 `AbsSwitchable` 的要求、传播义务、不适用对象 | — |
| 契约 | `AbsSwitchable.java` | 补齐 javadoc（volatile、为何不直接 implements） | — |

### 决策结论（原第六节三点）

1. **`GoalExtension` 存量构造**：补回为 `static of(GoalEngine, HarnessEngine)`，内部走 `builder(null).scheduler(null).build()` 再注入 engine/talent。保留 `bridge == null` 守卫语义，测试改用工厂。
2. **`configure` 无条件回调**：采用方案 A。理由：不挂载就永远无法在运行期恢复；配套已在 `AiSwitchable` javadoc 写明「扩展必须向子组件传播状态」。
3. **`AgentManager.getAgent(name)`**：保持「抛异常/返回不加过滤」现状，只在 `getAgents()` 列表侧过滤 `enabled`。本次未改动（属 P2-1 独立缺陷）。

### 回归结论

对比基线 `e873e87ba`（独立 worktree 实测）：

| | 测试数 | 失败 | 错误 |
|---|---|---|---|
| 基线 | 736 | 29 | 3 |
| 改动后 | 743 | 29 | 3 |

失败集合完全相同，全部落在 `ToolStrategyDefaultsTest` / `WebToolStrategyTest` / `WriteToolStrategyTest` / `BashToolStrategyTest` 四个权限策略测试上，基线即红，与本改动无关。新增 7 个用例全部通过。

`solon-ai-agent` 全量：`ContextCompressionInterceptorTest` 79 项 0 失败；仅 `ReActAgentTest.testMathAndLogic` 2 处失败，原因是外部模型服务不可用（`抱歉，暂时无法使用模型服务`）。

### 未纳入本次（P2，建议单独开单）

1. `AgentDefinition.Metadata.enabled` 从未生效：`AgentManager.getAgents()` 只过滤 `isHidden()`，`GenerateTalent.getInstruction()` 把全部 agent 列给模型，`GenerateTalent:91` 还主动置 true。
2. `McpClientProvider` 运行期开关仅在注册期生效，需 `refreshMcpServer` 才真撤工具。
3. `TeamProtocol`、`Channel` 暂不加 `AiSwitchable`（主干缺少消费点，属提前投资）。
