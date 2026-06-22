# Solon AI Loop Engine

循环执行引擎 —— 为 Solon AI 生态提供**持久化、可编排、可验证**的任务循环执行能力。借鉴 oh-my-claudecode 的循环引擎设计，深度集成 solon-flow（流编排）、solon-ai-agent（智能体）和 solon-ai-harness（工具管理）。

## 特性

- **多种循环策略**：支持 Ralph（PRD 驱动故事循环）、Team Pipeline（多阶段管道）、UltraQA（质量门禁循环）
- **完整状态机**：IDLE → PLANNING → EXECUTING → VERIFYING → FIXING → COMPLETED/FAILED，支持暂停/恢复
- **状态持久化**：内存态 + 磁盘 JSON 持久化（原子写入），支持跨进程恢复
- **验证框架**：内置 Validator 接口 + QualityGate 质量门禁 + Architect/Critic 双重验证
- **策略互斥**：MutualExclusionGuard 确保 Ralph / UltraQA / Team Pipeline 不会同时运行
- **Autopilot 编排**：多阶段 Pipeline 编排器，可注册自定义 StageAdapter
- **监控调试**：LoopMonitor 采集运行时指标，LoopDebugger 追踪事件日志
- **Solon 深度集成**：与 solon-flow、solon-ai-agent、solon-ai-harness 无缝集成

---

## 模块结构

```
solon-ai-loop/
├── pom.xml                                              # Maven 构建配置
├── src/
│   ├── main/java/org/noear/solon/ai/loop/
│   │   ├── config/                                      # 配置层
│   │   │   ├── LoopConfig.java                          # 单次循环的配置（任务描述、策略、验证器等）
│   │   │   └── LoopEngineConfig.java                    # 循环引擎的配置（状态管理器、监控等）
│   │   ├── engine/                                      # 引擎核心
│   │   │   ├── LoopEngine.java                          # 循环引擎接口（start/pause/resume/stop）
│   │   │   ├── SimpleLoopEngine.java                    # 引擎默认实现（线程 + 状态机驱动）
│   │   │   ├── LoopSession.java                         # 循环会话接口（生命周期、监听器、查询）
│   │   │   ├── LoopResult.java                          # 循环结果（会话级统计数据）
│   │   │   └── IterationResult.java                     # 单次迭代结果
│   │   ├── strategy/                                    # 循环策略层
│   │   │   ├── LoopStrategy.java                        # 策略接口（shouldContinue / executeIteration）
│   │   │   ├── AbstractLoopStrategy.java                # 策略抽象基类
│   │   │   ├── LoopContext.java                         # 循环上下文（会话上下文数据容器）
│   │   │   ├── RalphLoopStrategy.java                   # Ralph 策略：PRD 驱动故事循环
│   │   │   ├── TeamPipelineStrategy.java                # Team Pipeline 策略：多阶段管道
│   │   │   ├── UltraQAStrategy.java                     # UltraQA 策略：质量门禁循环
│   │   │   ├── CommandExecutor.java                     # 命令执行器接口
│   │   │   └── ProcessCommandExecutor.java              # 基于 ProcessBuilder 的真实命令执行器
│   │   ├── state/                                       # 状态管理层
│   │   │   ├── LoopState.java                           # 状态枚举（8 个值）
│   │   │   ├── LoopStateManager.java                    # 状态转换管理器（校验转换合法性）
│   │   │   ├── LoopStateData.java                       # 状态数据（可序列化，用于持久化）
│   │   │   ├── StateManager.java                        # 状态管理器接口
│   │   │   ├── InMemoryStateManager.java                # 内存状态管理器
│   │   │   ├── MutualExclusionGuard.java                # 策略互斥守卫
│   │   │   ├── SessionIdentityValidator.java            # 会话身份验证器
│   │   │   └── disk/                                    # 磁盘持久化
│   │   │       ├── DiskStateManager.java                # 磁盘状态管理器（JSON 文件存储）
│   │   │       ├── AtomicWrite.java                     # 原子写入工具（防写入中断）
│   │   │       └── FilePermissionUtil.java              # 文件权限工具
│   │   ├── prd/                                         # PRD 文档系统
│   │   │   ├── PRDDocument.java                         # PRD 文档模型（项目名、分支、用户故事列表）
│   │   │   ├── PRDFileManager.java                      # PRD 文件管理器（读写、格式化、标记检测）
│   │   │   ├── PRDStatus.java                           # PRD 状态摘要（完成百分比、进度统计）
│   │   │   ├── PRDStatusCalculator.java                 # PRD 状态计算器
│   │   │   └── UserStory.java                           # 用户故事模型（ID、标题、验收条件、验证状态）
│   │   ├── progress/                                    # 进度记忆系统
│   │   │   ├── ProgressManager.java                     # 进度管理器（手写 JSON 序列化/反序列化）
│   │   │   ├── ProgressEntry.java                       # 进度条目（实现记录、文件变更、经验教训）
│   │   │   └── ProgressLog.java                         # 进度日志（条目集合 + 代码模式）
│   │   ├── pipeline/                                    # Pipeline 编排层
│   │   │   ├── AutopilotExecutor.java                   # 编排执行器（多阶段 Pipeline 协调）
│   │   │   ├── PipelineConfig.java                      # Pipeline 配置（阶段启用/禁用 + 策略映射）
│   │   │   ├── PipelineStage.java                       # 阶段枚举（5 个执行阶段 + 2 个终态）
│   │   │   ├── PipelineTracking.java                    # Pipeline 追踪信息（阶段历史、耗时）
│   │   │   ├── StageAdapter.java                        # 阶段适配器 SPI 接口（可自定义阶段执行逻辑）
│   │   │   └── DefaultStageAdapters.java                # 内置阶段适配器（EXECUTION/QA/EXPANSION/VALIDATION）
│   │   ├── integration/                                 # Solon 框架集成层
│   │   │   ├── LoopAutoConfiguration.java               # 自动配置（一行式引擎初始化 + 集成组件容器）
│   │   │   ├── SolonAgentIntegration.java               # solon-ai-agent 集成（Agent 驱动的循环执行）
│   │   │   ├── SolonFlowIntegration.java                # solon-flow 集成（流上下文驱动的循环执行）
│   │   │   └── SolonHarnessIntegration.java             # solon-ai-harness 集成（工具管理驱动的循环执行）
│   │   ├── monitor/                                     # 监控调试层
│   │   │   ├── LoopMonitor.java                         # 循环监控器（10 秒定时采集指标 + 报告生成）
│   │   │   └── LoopDebugger.java                        # 循环调试器（事件追踪 + 监听器注册）
│   │   ├── validator/                                   # 验证框架
│   │   │   ├── Validator.java                           # 验证器接口（validate/validateQualityGate/validateIteration）
│   │   │   ├── ValidationResult.java                    # 验证结果（passed + failed + needsFix 工厂方法）
│   │   │   ├── ValidationCriteria.java                  # 验证标准（simple + strict 工厂方法）
│   │   │   ├── ValidationContext.java                   # 验证上下文
│   │   │   ├── QualityGate.java                         # 质量门禁（预设 build/test/lint 门禁）
│   │   │   └── verify/                                  # 验证器实现
│   │   │       ├── ArchitectVerifier.java               # 架构师验证器
│   │   │       ├── CriticVerifier.java                  # 评审验证器（architect/critic/codex 模式）
│   │   │       └── VerificationState.java               # 验证状态机（6 个状态 + 最大尝试次数）
│   │   └── example/
│   │       └── LoopEngineExample.java                   # 完整使用示例（三种策略 + 事件监听）
│   └── test/java/org/noear/solon/ai/loop/               # 测试用例
│       ├── engine/LoopEngineTest.java
│       ├── integration/SolonIntegrationTest.java
│       ├── AutopilotExtendedTest.java
│       ├── DiskStateManagerExtendedTest.java
│       ├── E2EPipelineTest.java
│       ├── InfrastructureTest.java
│       ├── PRDExtendedTest.java
│       ├── ProgressUltraQATest.java
│       ├── SecondRoundFixTest.java
│       └── TeamPipelineTransitionTest.java
```

---

## 核心概念

### 1. 循环状态机

```
                    ┌─────────┐
                    │  IDLE   │
                    └────┬────┘
                         │
                    ┌────▼────┐
                    │ PLANNING│
                    └────┬────┘
                         │
                    ┌────▼──────┐
              ┌─────► EXECUTING │◄─────────┐
              │     └────┬──────┘          │
              │          │                 │
              │     ┌────▼──────┐    ┌─────┴───┐
              │     │ VERIFYING │    │  FIXING │
              │     └────┬──────┘    └────┬────┘
              │          │                │
              │     ┌────▼──────┐         │
              │     │ COMPLETED │         │
              │     └───────────┘         │
              │                          │
              └──────────────────────────┘
                      (修复循环)

    PAUSED 可从任何活跃状态进入，恢复后回到执行状态
    FAILED 可从任何非终态进入
```

**状态说明**：

| 状态 | 说明 | 活跃 | 终态 | 可暂停 | 可恢复 | 可停止 |
|------|------|------|------|--------|--------|--------|
| IDLE | 空闲 | ✗ | ✗ | ✗ | ✗ | ✗ |
| PLANNING | 规划中 | ✓ | ✗ | ✓ | ✗ | ✓ |
| EXECUTING | 执行中 | ✓ | ✗ | ✓ | ✗ | ✓ |
| VERIFYING | 验证中 | ✓ | ✗ | ✓ | ✗ | ✓ |
| FIXING | 修复中 | ✓ | ✗ | ✓ | ✗ | ✓ |
| PAUSED | 已暂停 | ✗ | ✗ | ✗ | ✓ | ✓ |
| COMPLETED | 已完成 | ✗ | ✓ | ✗ | ✗ | ✗ |
| FAILED | 失败 | ✗ | ✓ | ✗ | ✗ | ✗ |

**状态转换规则**（来自 `LoopStateManager`）：

| 当前状态 | 允许转换到 |
|----------|-----------|
| IDLE | PLANNING |
| PLANNING | EXECUTING, PAUSED, FAILED |
| EXECUTING | VERIFYING, PAUSED, FAILED |
| VERIFYING | COMPLETED, FIXING, PAUSED, FAILED |
| FIXING | EXECUTING, PAUSED, FAILED |
| PAUSED | PLANNING, EXECUTING, VERIFYING, FIXING, FAILED |
| COMPLETED | （终态） |
| FAILED | （终态） |

### 2. 三种循环策略

#### Ralph Loop Strategy (PRD 驱动)

对标 oh-my-claudecode 的 `ralph/loop.ts`。从 PRD（产品需求文档）中获取用户故事，逐个实现、验证（Architect + Critic），并记录进度，直到所有故事完成。

```java
RalphLoopStrategy strategy = RalphLoopStrategy.builder()
    .verificationRequired(true)
    .criticMode("architect")     // architect / critic / none
    .maxIterations(50)
    .storyImplementor((story, ctx) -> { /* 自定义实现逻辑 */ })
    .storyValidator((story, result, ctx) -> { /* 自定义验证逻辑 */ })
    .build();
```

**Ralph 循环流程**：
1. 从 PRD 获取下一个待实现的故事（按优先级排序）
2. 使用 StoryImplementor 实现故事（可注入 Agent）
3. Architect 验证实现质量（检查验收条件覆盖）
4. Critic 评审（支持 architect/critic/codex 三种模式）
5. 记录进度到 ProgressManager（实现内容、文件变更、经验教训）
6. 更新 PRD 状态并重复，直到所有故事完成并通过验证

#### Team Pipeline Strategy (多阶段管道)

对标 oh-my-claudecode 的 `team-pipeline/types.ts`。按 Plan → PRD → Exec → Verify → Fix 顺序执行，支持阶段历史追踪、取消恢复、修复次数上限。

```java
TeamPipelineStrategy strategy = TeamPipelineStrategy.builder()
    .phases(Arrays.asList(
        TeamPipelineStrategy.Phase.PLAN,
        TeamPipelineStrategy.Phase.PRD,
        TeamPipelineStrategy.Phase.EXEC,
        TeamPipelineStrategy.Phase.VERIFY,
        TeamPipelineStrategy.Phase.FIX
    ))
    .maxFixAttempts(3)
    .parallelExecution(false)
    .build();
```

**阶段枚举**（`TeamPipelineStrategy.Phase`）：`PLAN`、`PRD`、`EXEC`、`VERIFY`、`FIX`、`COMPLETED`、`FAILED`、`CANCELLED`

**转换守卫**：
- VERIFY 阶段要求 `tasksCompleted >= tasksTotal`
- FIX 阶段超过 `maxFixAttempts` 次后进入 FAILED
- 支持 `requestCancel()` + `resumeFromCancel()` 取消恢复机制

#### UltraQA Strategy (质量门禁)

对标 oh-my-claudecode 的 `ultraqa/index.ts`。反复执行质量门禁检查（编译/测试/风格等），直到全部通过、达到最大次数或检测到相同失败重复。

```java
UltraQAStrategy strategy = UltraQAStrategy.builder()
    .parallelTesting(false)
    .maxTestAttempts(10)
    .goalType(UltraQAStrategy.UltraQAGoalType.TESTS)  // TESTS / BUILD / LINT / TYPECHECK / CUSTOM
    .build();
```

**退出原因**（`UltraQAExitReason`）：
| 原因 | 说明 |
|------|------|
| GOAL_MET | 所有门禁通过，目标达成 |
| MAX_CYCLES | 达到最大循环数 |
| SAME_FAILURE | 相同失败重复出现（阈值 3 次） |
| ENV_ERROR | 环境错误 |
| CANCELLED | 被取消 |

**同失败检测**：通过 `normalizeFailure()` 去除时间戳、行号等变量信息，使失败描述可比较，连续 3 次相同失败自动终止。

### 3. 数据持久化

磁盘状态文件结构（项目根目录下的 `.solon-ai-loop/`）：

```
.solon-ai-loop/
├── state/
│   ├── ralph/{sessionId}.json        # Ralph 状态（含策略互斥锁）
│   ├── team/{sessionId}.json         # Team Pipeline 状态
│   ├── ultraqa/{sessionId}.json      # UltraQA 状态
│   └── sessions/{sessionId}.json     # 会话摘要
├── prd/{sessionId}.json              # PRD 文档
└── progress/{sessionId}.txt          # 进度记忆
```

每个状态文件采用双层 JSON 结构：`_meta` 层记录写入时间、模式、会话 ID；`data` 层记录完整的 `LoopStateData`。

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-loop</artifactId>
    <version>4.0.3-SNAPSHOT</version>
</dependency>
```

### 2. 快速初始化

```java
// 一行式创建默认引擎（内存状态管理器）
LoopEngine engine = LoopAutoConfiguration.createDefaultEngine();

// 或创建带磁盘持久化的引擎
LoopEngine engine = new LoopAutoConfiguration()
    .useDiskState("/path/to/project")
    .enableMonitoring(true)
    .build();
```

### 3. Ralph 循环示例

```java
// 创建引擎
LoopEngine engine = new SimpleLoopEngine(LoopEngineConfig.builder()
    .monitoringEnabled(true)
    .build());

// 创建 Ralph 策略
RalphLoopStrategy strategy = RalphLoopStrategy.builder()
    .verificationRequired(false)  // 跳过验证（快速演示）
    .maxIterations(5)
    .build();

// 构建循环配置
LoopConfig config = LoopConfig.builder()
    .taskDescription("Implement user login feature")
    .strategy(strategy)
    .maxIterations(5)
    .build();

// 启动循环
LoopSession session = engine.start(config);

// 等待完成（带超时）
session.waitForCompletion(java.time.Duration.ofSeconds(10));

// 查看结果
LoopResult result = session.getResult();
System.out.println("Success: " + result.isSuccess());
System.out.println("Iterations: " + result.getTotalIterations());
```

### 4. Team Pipeline 示例

```java
TeamPipelineStrategy strategy = TeamPipelineStrategy.builder()
    .phases(Arrays.asList(
        TeamPipelineStrategy.Phase.PLAN,
        TeamPipelineStrategy.Phase.EXEC,
        TeamPipelineStrategy.Phase.VERIFY
    ))
    .maxFixAttempts(2)
    .build();

LoopConfig config = LoopConfig.builder()
    .taskDescription("Build REST API")
    .strategy(strategy)
    .build();

LoopSession session = engine.start(config);
session.waitForCompletion(java.time.Duration.ofSeconds(30));
```

### 5. UltraQA 示例

```java
UltraQAStrategy strategy = UltraQAStrategy.builder()
    .goalType(UltraQAStrategy.UltraQAGoalType.TESTS)
    .maxTestAttempts(5)
    .build();

LoopConfig config = LoopConfig.builder()
    .taskDescription("QA testing")
    .strategy(strategy)
    .build();

LoopSession session = engine.start(config);
session.waitForCompletion(java.time.Duration.ofSeconds(30));
```

### 6. 使用事件监听

```java
session.onStateChange(state ->
    System.out.println("State: " + state));

session.onIterationComplete(result ->
    System.out.println("Iteration " + result.getNumber()
        + ": " + (result.isSuccess() ? "SUCCESS" : "FAILED")));

session.onValidationResult(result ->
    System.out.println("Validation: "
        + (result.isPassed() ? "PASSED" : "FAILED")
        + " - " + result.getMessage()));
```

---

## 与 Solon 框架集成

### 一键初始化

```java
// 创建包含所有集成组件的默认配置
LoopAutoConfiguration.IntegratedComponents components =
    LoopAutoConfiguration.createDefault();

LoopEngine engine = components.loopEngine;
SolonAgentIntegration agentIntegration = components.agentIntegration;
SolonFlowIntegration flowIntegration = components.flowIntegration;
SolonHarnessIntegration harnessIntegration = components.harnessIntegration;
```

### 与 solon-ai-agent 集成

```java
// 使用 Agent 驱动 Ralph 循环（支持 SimpleAgent、ReActAgent、TeamAgent 等）
SimpleAgent agent = SimpleAgent.of()
    .chatModel(chatModel)  // 传入 ChatModel 实例
    .build();

LoopSession session = agentIntegration
    .startAgentDrivenRalphLoop("实现用户管理功能", agent);
```

> **注意**：`SimpleAgent.of()` 返回 `SimpleAgent.Builder`，也可以通过 `SimpleAgent.of(chatModel)` 一步完成。Builder 提供 `chatModel(ChatModel)`、`instruction(String)`、`name(String)`、`role(String)` 等链式配置方法。

`SolonAgentIntegration` 提供的 API：
| 方法 | 说明 |
|------|------|
| `startRalphLoop(task, validator)` | 快捷启动 Ralph 循环 |
| `startUltraQALoop(task, validator)` | 快捷启动 UltraQA 循环 |
| `startAgentDrivenRalphLoop(task, agent)` | Agent 驱动的 Ralph 循环 |
| `pauseAgentExecution(sessionId)` | 暂停 Agent 执行 |
| `resumeAgentExecution(sessionId)` | 恢复 Agent 执行 |

### 与 solon-flow 集成

```java
// 使用 Flow 上下文驱动 Team Pipeline
TeamPipelineStrategy strategy = TeamPipelineStrategy.builder().build();
LoopSession session = flowIntegration
    .startFlowDrivenPipeline("my-flow-id", strategy);
```

`SolonFlowIntegration` 提供的 API：
| 方法 | 说明 |
|------|------|
| `startFlowRalphLoop(flowId, task, validator)` | Flow 驱动的 Ralph 循环 |
| `startFlowDrivenPipeline(flowId, strategy)` | Flow 驱动的 Team Pipeline |
| `getFlowBridge()` | 获取 FlowBridage（操作 FlowContext） |

### 与 solon-ai-harness 集成

```java
// 使用 HarnessEngine 驱动 UltraQA 循环
HarnessEngine harnessEngine = /* 获取 HarnessEngine */;
LoopSession session = harnessIntegration
    .startHarnessDrivenUltraQA("质量测试", harnessEngine);
```

`SolonHarnessIntegration` 提供的 API：
| 方法 | 说明 |
|------|------|
| `startToolUltraQALoop(task, validator)` | 工具驱动的 UltraQA 循环 |
| `startHarnessDrivenUltraQA(task, harnessEngine)` | Harness 驱动的 UltraQA 循环 |
| `getHarnessBridge()` | 获取 HarnessBridge（操作 HarnessEngine） |

---

## Autopilot 多阶段 Pipeline

`AutopilotExecutor` 提供更高层次的编排能力，将多个阶段串联。每个阶段可绑定不同的 `LoopStrategy`，支持自定义 `StageAdapter`。

### Pipeline 阶段

`PipelineStage` 枚举定义了 5 个执行阶段和 2 个终态：

| 阶段 | 说明 | 默认策略 |
|------|------|---------|
| EXPANSION | 需求扩展分析 | TeamPipelineStrategy |
| PLANNING | 规划制定 | TeamPipelineStrategy |
| EXECUTION | 执行实现 | RalphLoopStrategy |
| QA | 质量检查 | UltraQAStrategy |
| VALIDATION | 最终验证 | TeamPipelineStrategy |
| COMPLETED | 完成（终态） | - |
| FAILED | 失败（终态） | - |

### 基础用法

```java
PipelineConfig pipelineConfig = PipelineConfig.builder()
    .expansionEnabled(true)
    .planningEnabled(true)
    .executionEnabled(true)
    .qaEnabled(true)
    .validationEnabled(true)
    .build();

AutopilotExecutor autopilot = new AutopilotExecutor(engine, pipelineConfig);

// 启动 Pipeline
AutopilotExecutor.PipelineRequest request =
    AutopilotExecutor.PipelineRequest.create(
        java.util.UUID.randomUUID().toString(), "Build feature X");

java.util.concurrent.CompletableFuture<AutopilotExecutor.PipelineResult> future =
    autopilot.startPipeline(request);

// 获取 HUD 信息
String sessionId = request.sessionId;
System.out.println(autopilot.formatPipelineHUD(sessionId));
```

### 自定义阶段适配器

```java
autopilot.registerAdapter(new StageAdapter() {
    @Override
    public PipelineStage supportedStage() { return PipelineStage.EXPANSION; }

    @Override
    public StageResult execute(PipelineStage stage,
                                Map<String, Object> context,
                                LoopEngine loopEngine) {
        // 自定义需求扩展逻辑
        String desc = (String) context.getOrDefault("description", "");
        context.put("expandedDescription", "[EXPANDED] " + desc);
        return StageResult.ok("Expansion completed");
    }
});
```

### 绑定策略到特定阶段

```java
PipelineConfig pipelineConfig = PipelineConfig.builder()
    .executionEnabled(true)
    .qaEnabled(true)
    .strategyForStage(PipelineStage.EXECUTION,
        RalphLoopStrategy.builder().maxIterations(20).build())
    .strategyForStage(PipelineStage.QA,
        UltraQAStrategy.builder().maxTestAttempts(5).build())
    .build();
```

### Pipeline 控制

| 方法 | 说明 |
|------|------|
| `startPipeline(request)` | 启动完整的 Pipeline |
| `cancelPipeline(sessionId)` | 取消整个 Pipeline，停止所有活跃会话 |
| `skipStage(sessionId, stage)` | 跳过某个阶段 |
| `advanceStage(sessionId)` | 手动推进到下一个阶段 |
| `formatPipelineHUD(sessionId)` | 获取格式化的 HUD 显示信息 |
| `getPipelineStatus(sessionId)` | 获取管道状态文本 |

---

## 验证框架

### Validator 接口

```java
public interface Validator {
    ValidationResult validate(Object result, ValidationCriteria criteria);
    ValidationResult validateQualityGate(QualityGate gate, Object result);
    ValidationResult validateIteration(Object iterationResult, ValidationContext context);
}
```

### 内置验证器

```java
// 架构师验证器（ArchitectVerifier）
//   验证用户故事的实现是否符合架构验收条件
// 评审验证器（CriticVerifier）
//   三种模式：
//   - "architect" — 架构师评审（检查架构级变更）
//   - "critic"    — 通用评审
//   - "codex"     — 代码评审（检查测试覆盖、空值处理）
//
// 通过 RalphLoopStrategy.Builder 配置验证器

RalphLoopStrategy strategy = RalphLoopStrategy.builder()
    .criticMode("architect")   // 仅架构师验证
    .criticMode("critic")      // 架构师 + 评审
    .criticMode("none")        // 跳过验证
    .build();
```

### 自定义验证器

```java
Validator validator = new Validator() {
    @Override
    public ValidationResult validate(Object result, ValidationCriteria criteria) {
        if (result != null && result.toString().contains("success")) {
            return ValidationResult.passed("All good");
        }
        return ValidationResult.needsFix("Fix required",
            java.util.Arrays.asList("Issue 1", "Issue 2"));
    }

    @Override
    public ValidationResult validateQualityGate(QualityGate gate, Object result) {
        return validate(result, null);
    }

    @Override
    public ValidationResult validateIteration(Object iterationResult,
                                               ValidationContext context) {
        return validate(iterationResult, null);
    }
};
```

### 质量门禁

```java
// 预设门禁
QualityGate buildGate = QualityGate.build();    // checks: ["compilation", "dependencies"]
QualityGate testGate  = QualityGate.test();     // checks: ["unit-tests", "integration-tests"]
QualityGate lintGate  = QualityGate.lint();     // checks: ["style", "complexity", "duplication"]

// 自定义门禁
QualityGate customGate = new QualityGate(
    "security", "安全检查",
    java.util.Arrays.asList("dependency-check", "code-scan"),
    null, true  // blocking=true 阻塞式
);
```

### 验证状态机（VerificationState）

在 Ralph 策略内部，验证过程使用 `VerificationState` 追踪：

```
PENDING → IMPLEMENTED → AWAITING_REVIEW
    → ARCHITECT_APPROVED → CRITIC_APPROVED（通过）
    → FAILED（超过最大尝试次数 3）
    → SKIPPED（跳过）
```

---

## 监控与调试

### LoopMonitor（定时指标采集）

```java
LoopMonitor monitor = new LoopMonitor(engine);  // 默认每 10 秒采集

// 获取监控报告
String report = monitor.getReport();
System.out.println(report);

// 获取单个会话指标
LoopMonitor.SessionMetrics metrics = monitor.getSessionMetrics(sessionId);
System.out.println("State: " + metrics.getState());
System.out.println("Iterations: " + metrics.getIterationCount());
System.out.println("Success Rate: " + metrics.getSuccessRate());

// 停止监控
monitor.stop();
```

### LoopDebugger（事件追踪）

```java
LoopDebugger debugger = new LoopDebugger(engine);

// 开始调试会话（自动注册状态变化/迭代完成/验证结果三种监听器）
debugger.startDebugSession(sessionId);

// 获取调试报告
String debugReport = debugger.getDebugReport(sessionId);
System.out.println(debugReport);

// 获取摘要
String summary = debugger.getSummaryReport();
```

---

## 完整示例

参考 `src/main/java/org/noear/solon/ai/loop/example/LoopEngineExample.java`，它演示了：

1. 创建 LoopEngine（配置监控、调试）
2. Ralph 循环（带自定义验证器 + 事件监听）
3. Team Pipeline 循环（Plan → PRD → Exec → Verify → Fix）
4. UltraQA 循环（质量门禁）
5. 结果获取与会话级统计（总迭代数、成功率、耗时）

---

## 配置选项

### LoopEngineConfig

| 选项 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| stateManager | StateManager | InMemoryStateManager | 状态管理器实现（内存/磁盘） |
| monitoringEnabled | boolean | true | 启用 LoopMonitor 监控 |
| debuggingEnabled | boolean | false | 启用 LoopDebugger 调试 |
| cleanupInterval | int | 300s | 状态清理间隔（秒） |
| stateExpirationTime | long | 3600000ms | 状态过期时间（毫秒） |

### LoopConfig

| 选项 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| taskDescription | String | — | 任务描述（必须） |
| strategy | LoopStrategy | — | 循环策略（必须） |
| validator | Validator | null | 验证器（null 则跳过验证） |
| maxIterations | int | 100 | 最大迭代次数 |
| verificationRequired | boolean | true | 是否需要验证 |
| statePersistenceEnabled | boolean | true | 启用状态持久化 |
| parameters | Map\<String, Object\> | null | 自定义参数（注入到 LoopContext） |

---

## 最佳实践

1. **选择合适的策略**：编码实现任务用 Ralph，多阶段协作用 Team Pipeline，质量保障用 UltraQA
2. **设置合理的迭代上限**：避免无限循环，Ralph 建议 50，Team Pipeline 建议 100，UltraQA 建议 10
3. **启用磁盘持久化**：长时间运行的任务建议启用 `DiskStateManager`，支持跨重启恢复
4. **实现有效的验证器**：验证器是循环退出的关键判断点，确保逻辑完备
5. **利用监控调试**：开发环境启用 `LoopDebugger`，生产环境启用 `LoopMonitor`
6. **策略互斥**：Ralph / UltraQA / Team Pipeline 三者通过 `MutualExclusionGuard` 互斥，启动前自动检查不会同时运行
7. **自定义 Pipeline 阶段**：通过 `StageAdapter` 注册自定义执行逻辑，扩展 Autopilot 能力

---

## 运行测试

```bash
# 运行所有测试
mvn test

# 运行特定测试类
mvn test -Dtest=LoopEngineTest

# 运行集成测试
mvn verify
```

## 许可证

Apache License 2.0
