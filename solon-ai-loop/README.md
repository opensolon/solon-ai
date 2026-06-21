# Solon AI Loop Engine

一个强大、灵活、可扩展的循环执行引擎，为Solon AI生态提供持久化任务执行能力。

## 特性

- **多种循环策略**：支持Ralph、Team Pipeline、UltraQA等多种循环策略
- **状态管理**：完整的状态机模型和持久化机制
- **验证驱动**：内置验证框架，支持质量门禁
- **监控调试**：完善的监控和调试工具
- **与Solon框架深度集成**：与solon-flow、solon-ai-agent、solon-ai-harness无缝集成

## 架构设计

```
┌─────────────────────────────────────────────────────────────┐
│                    solon-ai-loop                             │
├─────────────────────────────────────────────────────────────┤
│  LoopEngine (循环引擎核心)                                    │
│  ├── LoopSession (会话管理)                                   │
│  ├── StateManager (状态管理)                                  │
│  └── Validator (验证器)                                      │
├─────────────────────────────────────────────────────────────┤
│  循环策略 (Loop Strategies)                                   │
│  ├── RalphLoopStrategy (PRD驱动循环)                          │
│  ├── TeamPipelineStrategy (多阶段管道)                        │
│  └── UltraQAStrategy (质量门禁循环)                           │
├─────────────────────────────────────────────────────────────┤
│  状态机 (State Machine)                                      │
│  ├── LoopState (状态枚举)                                    │
│  ├── LoopStateData (状态数据)                                │
│  └── LoopStateManager (状态转换)                             │
├─────────────────────────────────────────────────────────────┤
│  集成层 (Integration)                                        │
│  ├── SolonFlowIntegration (流编排集成)                        │
│  ├── SolonAgentIntegration (智能体集成)                       │
│  └── SolonHarnessIntegration (工具管理集成)                   │
├─────────────────────────────────────────────────────────────┤
│  监控调试 (Monitoring & Debugging)                            │
│  ├── LoopMonitor (监控器)                                    │
│  └── LoopDebugger (调试器)                                   │
└─────────────────────────────────────────────────────────────┘
```

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-loop</artifactId>
    <version>4.0.3</version>
</dependency>
```

### 2. 基本使用

```java
// 创建循环引擎
LoopEngineConfig config = LoopEngineConfig.builder()
    .monitoringEnabled(true)
    .debuggingEnabled(true)
    .build();

LoopEngine engine = new SimpleLoopEngine(config);

// 创建循环策略
RalphLoopStrategy strategy = RalphLoopStrategy.builder()
    .verificationRequired(true)
    .criticMode("architect")
    .maxIterations(50)
    .build();

// 创建验证器
Validator validator = createValidator();

// 创建循环配置
LoopConfig loopConfig = LoopConfig.builder()
    .taskDescription("Implement user authentication feature")
    .strategy(strategy)
    .validator(validator)
    .maxIterations(50)
    .verificationRequired(true)
    .statePersistenceEnabled(true)
    .build();

// 启动循环
LoopSession session = engine.start(loopConfig);

// 监听状态变化
session.onStateChange(state -> {
    System.out.println("State changed to: " + state);
});

// 监听迭代完成
session.onIterationComplete(result -> {
    System.out.println("Iteration " + result.getNumber() + " completed");
});

// 等待完成
session.waitForCompletion();

// 获取结果
LoopResult result = session.getResult();
```

## 循环策略

### 1. Ralph Loop Strategy
PRD驱动的持久化循环，直到所有用户故事完成并验证。

**基本用法：**
```java
RalphLoopStrategy strategy = RalphLoopStrategy.builder()
    .verificationRequired(true)
    .criticMode("architect")
    .maxIterations(50)
    .build();
```

**高级用法（自定义故事实现器）：**
```java
RalphLoopStrategy strategy = RalphLoopStrategy.builder()
    .verificationRequired(true)
    .maxIterations(50)
    .storyImplementor((story, context) -> {
        // 自定义故事实现逻辑
        return "Custom implementation for: " + story;
    })
    .storyValidator((story, result, context) -> {
        // 自定义故事验证逻辑
        return result != null && result.toString().contains("Custom implementation");
    })
    .build();
```

**使用验证器：**
```java
Validator validator = createValidator();

RalphLoopStrategy strategy = RalphLoopStrategy.builder()
    .verificationRequired(true)
    .maxIterations(50)
    .validator(validator)
    .build();
```

### 2. Team Pipeline Strategy
多阶段管道：plan → prd → exec → verify → fix (loop)

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

### 3. UltraQA Strategy
质量门禁循环，重复测试直到通过。

```java
UltraQAStrategy strategy = UltraQAStrategy.builder()
    .parallelTesting(false)
    .maxTestAttempts(10)
    .build();
```

## 状态机

```
IDLE → PLANNING → EXECUTING → VERIFYING → COMPLETED
                         ↓           ↓
                     PAUSED      FIXING
                         ↓           ↓
                    RESUMED    EXECUTING (loop)
```

## 监控和调试

### 监控器
```java
LoopMonitor monitor = new LoopMonitor(engine);
monitor.startMonitoring();

// 获取监控报告
String report = monitor.getReport();
System.out.println(report);
```

### 调试器
```java
LoopDebugger debugger = new LoopDebugger(engine);
debugger.startDebugSession(sessionId);

// 获取调试报告
String debugReport = debugger.getDebugReport(sessionId);
System.out.println(debugReport);
```

## 与Solon框架集成

### 1. 与solon-flow集成
```java
SolonFlowIntegration flowIntegration = new SolonFlowIntegration(engine);
LoopSession session = flowIntegration.startFlowExecution("flow-id", strategy, validator);
```

### 2. 与solon-ai-agent集成
```java
SolonAgentIntegration agentIntegration = new SolonAgentIntegration(engine);
LoopSession session = agentIntegration.startRalphLoop("task description", validator);
```

### 3. 与solon-ai-harness集成
```java
SolonHarnessIntegration harnessIntegration = new SolonHarnessIntegration(engine);
LoopSession session = harnessIntegration.startToolExecution("task description", strategy, validator);
```

## 配置选项

### LoopEngineConfig
- `stateManager`: 状态管理器实现
- `monitoringEnabled`: 启用监控
- `debuggingEnabled`: 启用调试
- `cleanupInterval`: 清理间隔（秒）
- `stateExpirationTime`: 状态过期时间（毫秒）

### LoopConfig
- `taskDescription`: 任务描述
- `strategy`: 循环策略
- `validator`: 验证器
- `maxIterations`: 最大迭代次数
- `verificationRequired`: 是否需要验证
- `statePersistenceEnabled`: 是否启用状态持久化
- `parameters`: 自定义参数

## 最佳实践

1. **选择合适的策略**：根据任务类型选择合适的循环策略
2. **设置合理的迭代次数**：避免无限循环，设置合理的最大迭代次数
3. **实现有效的验证器**：确保验证器能够准确判断任务完成情况
4. **启用状态持久化**：对于长时间运行的任务，启用状态持久化
5. **使用监控和调试**：在生产环境中启用监控，在开发环境中启用调试

## 示例项目

参考 `src/main/java/org/noear/solon/ai/loop/example/LoopEngineExample.java` 获取完整的使用示例。

## 测试

运行单元测试：
```bash
mvn test
```

运行集成测试：
```bash
mvn verify
```

## 贡献指南

1. Fork 项目
2. 创建功能分支
3. 提交更改
4. 推送到分支
5. 创建 Pull Request

## 许可证

Apache License 2.0