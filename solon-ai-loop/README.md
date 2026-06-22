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
│   │   │   ├── SimpleLoopEngine.java                    # 引擎默认实现（线程池 + 状态机驱动）
│   │   │   ├── LoopSession.java                         # 循环会话接口（生命周期、监听器、查询）
│   │   │   ├── LoopResult.java                          # 循环结果（会话级统计数据）
│   │   │   └── IterationResult.java                     # 单次迭代结果
│   │   ├── strategy/                                    # 循环策略层
│   │   │   ├── LoopStrategy.java                        # 策略接口（shouldContinue / executeIteration）
│   │   │   ├── AbstractLoopStrategy.java                # 策略抽象基类
│   │   │   ├── LoopContext.java                         # 循环上下文（会话上下文数据的容器）
│   │   │   ├── RalphLoopStrategy.java                   # Ralph 策略：PRD 驱动故事循环
│   │   │   ├── TeamPipelineStrategy.java                # Team Pipeline 策略：多阶段管道
│   │   │   ├── UltraQAStrategy.java                     # UltraQA 策略：质量门禁循环
│   │   │   ├── CommandExecutor.java                     # 命令执行器接口
│   │   │   └── ProcessCommandExecutor.java              # 进程命令执行器实现
│   │   ├── state/                                       # 状态管理层
│   │   │   ├── LoopState.java                           # 状态枚举（IDLE/PLANNING/EXECUTING/VERIFYING/FIXING/PAUSED/COMPLETED/FAILED）
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
│   │   │   ├── PRDFileManager.java                      # PRD 文件管理器（读写/初始化/检测标记）
│   │   │   ├── PRDStatus.java                           # PRD 状态摘要
│   │   │   ├── PRDStatusCalculator.java                 # PRD 状态计算器
│   │   │   └── UserStory.java                           # 用户故事模型
│   │   ├── progress/                                    # 进度记忆系统
│   │   │   ├── ProgressManager.java                     # 进度管理器（序列化/反序列化）
│   │   │   ├── ProgressEntry.java                       # 进度条目（实现记录、文件变更、经验教训）
│   │   │   └── ProgressLog.java                         # 进度日志（条目集合 + 代码模式）
│   │   ├── pipeline/                                    # Pipeline 编排层
│   │   │   ├── AutopilotExecutor.java                   # 编排执行器（多阶段 Pipeline 协调）
│   │   │   ├── PipelineConfig.java                      # Pipeline 配置（阶段列表与策略映射）
│   │   │   ├── PipelineStage.java                       # 阶段枚举（EXPANSION/PLANNING/EXECUTION/QA/VALIDATION）
│   │   │   ├── PipelineTracking.java                    # Pipeline 追踪信息
│   │   │   ├── StageAdapter.java                        # 阶段适配器接口（可自定义阶段执行逻辑）
│   │   │   └── DefaultStageAdapters.java                # 默认阶段适配器（EXECUTION/QA）
│   │   ├── integration/                                 # Solon 框架集成层
│   │   │   ├── LoopAutoConfiguration.java               # 自动配置（一行式引擎初始化）
│   │   │   ├── SolonAgentIntegration.java               # solon-ai-agent 集成（Agent 驱动循环）
│   │   │   ├── SolonFlowIntegration.java                # solon-flow 集成（流编排驱动循环）
│   │   │   └── SolonHarnessIntegration.java             # solon-ai-harness 集成（工具管理驱动循环）
│   │   ├── monitor/                                     # 监控调试层
│   │   │   ├── LoopMonitor.java                         # 循环监控器（指标采集 + 报告生成）
│   │   │   └── LoopDebugger.java                        # 循环调试器（事件追踪 + 调试报告）
│   │   ├── validator/                                   # 验证框架
│   │   │   ├── Validator.java                           # 验证器接口
│   │   │   ├── ValidationResult.java                    # 验证结果
│   │   │   ├── ValidationCriteria.java                  # 验证标准
│   │   │   ├── ValidationContext.java                   # 验证上下文
│   │   │   ├── QualityGate.java                         # 质量门禁（构建/测试/代码质量）
│   │   │   └── verify/                                  # 验证器实现
│   │   │       ├── ArchitectVerifier.java               # 架构师验证器
│   │   │       ├── CriticVerifier.java                  # 评审验证器
│   │   │       └── VerificationState.java               # 验证状态机
│   │   └── example/
│   │       └── LoopEngineExample.java                   # 完整使用示例
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

    PAUSED 可从任何活跃状态进入，恢复后回到原状态
    FAILED 可从任何非终态进入
```

**状态说明**：

| 状态 | 说明 | 活跃 | 终态 |
|------|------|------|------|
| IDLE | 空闲 | ✗ | ✗ |
| PLANNING | 规划中 | ✓ | ✗ |
| EXECUTING | 执行中 | ✓ | ✗ |
| VERIFYING | 验证中 | ✓ | ✗ |
| FIXING | 修复中 | ✓ | ✗ |
| PAUSED | 已暂停 | ✗ | ✗ |
| COMPLETED | 已完成 | ✗ | ✓ |
| FAILED | 失败 | ✗ | ✓ |

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
    .build();
```

#### UltraQA Strategy (质量门禁)
对标 oh-my-claudecode 的 `ultraqa/index.ts`。反复执行质量门禁检查（编译/测试/风格等），直到全部通过、达到最大次数或检测到相同失败重复。

```java
UltraQAStrategy strategy = UltraQAStrategy.builder()
    .parallelTesting(false)
    .maxTestAttempts(10)
    .goalType(UltraQAStrategy.UltraQAGoalType.TESTS)  // TESTS / BUILD / LINT / TYPECHECK / CUSTOM
    .build();
```

### 3. 数据持久化

磁盘状态文件结构（项目根目录下的 `.solon-ai-loop/`）：

```
.solon-ai-loop/
├── state/
│   ├── ralph/{sessionId}.json        # Ralph 状态
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
// 一行式创建默认引擎
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

// 等待完成
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

// 设置执行数据（任务数）
String sessionId = UUID.randomUUID().toString();
LoopContext ctx = /* 获取上下文 */;
strategy.setExecutionData(ctx, 1, 5);  // 1 worker, 5 tasks

LoopConfig config = LoopConfig.builder()
    .taskDescription("Build REST API")
    .strategy(strategy)
    .build();

LoopSession session = engine.start(config);
session.waitForCompletion(Duration.ofSeconds(30));
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
session.waitForCompletion(Duration.ofSeconds(30));
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
// 使用 SimpleAgent 驱动 Ralph 循环
SimpleAgent agent = SimpleAgent.builder()
    .llm(/* LLM 配置 */)
    .build();

LoopSession session = agentIntegration
    .startAgentDrivenRalphLoop("实现用户管理功能", agent);
```

### 与 solon-flow 集成

```java
// 使用 Flow 上下文驱动 Team Pipeline
TeamPipelineStrategy strategy = TeamPipelineStrategy.builder().build();
LoopSession session = flowIntegration
    .startFlowDrivenPipeline("my-flow-id", strategy);
```

### 与 solon-ai-harness 集成

```java
// 使用 HarnessEngine 驱动 UltraQA 循环
Object harnessEngine = /* 获取 HarnessEngine */;
LoopSession session = harnessIntegration
    .startHarnessDrivenUltraQA("质量测试", harnessEngine);
```

---

## Autopilot 多阶段 Pipeline

`AutopilotExecutor` 提供更高层次的编排能力，将多个阶段串联：

```java
PipelineConfig pipelineConfig = PipelineConfig.builder()
    .enableStage(PipelineStage.EXPANSION)
    .enableStage(PipelineStage.PLANNING)
    .enableStage(PipelineStage.EXECUTION)
    .enableStage(PipelineStage.QA)
    .enableStage(PipelineStage.VALIDATION)
    .build();

AutopilotExecutor autopilot = new AutopilotExecutor(engine, pipelineConfig);

// 自定义阶段适配器
autopilot.registerAdapter(new StageAdapter() {
    @Override
    public PipelineStage supportedStage() { return PipelineStage.EXPANSION; }

    @Override
    public StageResult execute(PipelineStage stage,
                                Map<String, Object> context,
                                LoopEngine engine) {
        // 自定义需求扩展逻辑
        return StageResult.success("Expansion done");
    }
});

// 启动 Pipeline
AutopilotExecutor.PipelineRequest request =
    AutopilotExecutor.PipelineRequest.create(
        UUID.randomUUID().toString(), "Build feature X");

CompletableFuture<AutopilotExecutor.PipelineResult> future =
    autopilot.startPipeline(request);

// 获取 HUD 信息
System.out.println(autopilot.formatPipelineHUD(sessionId));
```

---

## 验证框架

### 内置验证器

```java
// 架构师验证（ArchitectVerifier）
//   验证用户故事的实现是否符合架构要求
// 评审验证（CriticVerifier）
//   提供 architect / critic / none 三种模式

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
            Arrays.asList("Issue 1", "Issue 2"));
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
QualityGate buildGate = QualityGate.build();    // compilation + dependencies
QualityGate testGate  = QualityGate.test();     // unit-tests + integration-tests
QualityGate lintGate  = QualityGate.lint();     // style + complexity + duplication

// 自定义门禁
QualityGate customGate = new QualityGate(
    "security", "安全检查",
    Arrays.asList("dependency-check", "code-scan"),
    null, true  // blocking=true 阻塞式
);
```

---

## 监控与调试

### LoopMonitor

```java
LoopMonitor monitor = new LoopMonitor(engine);

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

### LoopDebugger

```java
LoopDebugger debugger = new LoopDebugger(engine);

// 开始调试会话
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

1. 创建 LoopEngine
2. Ralph 循环（带自定义验证器）
3. Team Pipeline 循环
4. UltraQA 循环
5. 事件监听
6. 结果获取与统计

---

## 配置选项

### LoopEngineConfig

| 选项 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| stateManager | StateManager | InMemoryStateManager | 状态管理器实现 |
| monitoringEnabled | boolean | true | 启用监控 |
| debuggingEnabled | boolean | false | 启用调试 |
| cleanupInterval | int | 300s | 状态清理间隔 |
| stateExpirationTime | long | 3600000ms | 状态过期时间 |

### LoopConfig

| 选项 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| taskDescription | String | — | 任务描述 |
| strategy | LoopStrategy | — | 循环策略 |
| validator | Validator | null | 验证器 |
| maxIterations | int | 100 | 最大迭代次数 |
| verificationRequired | boolean | true | 是否需要验证 |
| statePersistenceEnabled | boolean | true | 启用状态持久化 |
| parameters | Map | null | 自定义参数 |

---

## 最佳实践

1. **选择合适的策略**：编码实现任务用 Ralph，多阶段协作用 Team Pipeline，质量保障用 UltraQA
2. **设置合理的迭代上限**：避免无限循环，Ralph 建议 50，Team Pipeline 建议 100，UltraQA 建议 10
3. **启用磁盘持久化**：长时间运行的任务建议启用 `DiskStateManager`，支持跨重启恢复
4. **实现有效的验证器**：验证器是循环退出的关键判断点，确保逻辑完备
5. **利用监控调试**：开发环境启用 `LoopDebugger`，生产环境启用 `LoopMonitor`
6. **策略互斥**：Ralph / UltraQA / Team Pipeline 三者在同一个 Session 内互斥，启动前通过 `MutualExclusionGuard` 检查
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
