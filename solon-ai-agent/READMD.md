# solon-ai-agent

Solon AI Agent 是基于 Solon-AI 和 Solon-Flow 构建的新时代“智能体”开发框架。

## 特色

* 流程图驱动：基于 solon-flow，所有 Agent 行为皆为图节点，流程清晰可控。
* 混合推理模式：支持原生 Tool Call 与 文本 ReAct (Thought-Action-Observation) 混合模式。
* 多策略协作 (Multi-Agent)：内置层级制 (Hierarchical)、合同网 (Contract Net)、群体智能 (Swarm) 等多种团队协作范式。
* 工程化保障：内置迭代熔断、死循环检测、消息穿透隔离及全链路 Trace 追踪。
* 高度可扩展：通过拦截器 (Interceptor) 和提示词提供者 (PromptProvider) 轻松定制行为。

## 核心组件

### 1. ReActAgent 自省反思型智有体 (单兵作战)

负责个体自省。它能理解任务、调用工具并观察结果，直到得出最终结论。

* 防幻觉：物理截断模型伪造的 Observation。
* 自修复：工具执行异常自动反馈给 LLM 进行修正。

### 2. TeamAgent 团队协作型智能体 (团队协作)

负责多专家（智能体）协同。通过不同的策略（Strategy）驱动多个子 Agent 共同完成复杂任务。

* Contract Net：招标-定标机制，选出最合适的专家。
* Hierarchical：主管制，层级分发与结果审计。
* Sequential：流水线制，任务按序接力。
* 等其它策略

## 简单示例，感受下

### 1. 定义一个工具

```java
@Component
public class WeatherTools {
    @ToolMapping(description = "获取指定城市的天气情况")
    public String get_weather(@Param(name = "location", description = "根据用户提到的地点推测城市") String location) {
        return "晴，24度"; 
    }
}
```

### 2. 构建 ReAct 智能体

```java
Agent agent = ReActAgent.of(chatModel)
        .addTool(weatherTool)
        .build();

agent.call(context, "帮我查一下北京的天气");  
```

### 3. 构建专家团队

```java
Agent team = TeamAgent.of(chatModel)
        .strategy(TeamStrategy.HIERARCHICAL)
        .addAgent(coderAgent)
        .addAgent(testerAgent)
        .build();

team.call(context, "编写一个 Java 快速排序并进行测试");
```

## 协作策略说明

| 策略项          | 名称 | 机制说明                                                          | 适用场景                              |
|--------------|-------|---------------------------------------------------------------|-----------------------------------|
| SEQUENTIAL   | 顺序流转  | 按照预定义的顺序（Pipe）像流水线一样执行。上一个 Agent 的输出直接作为下一个 Agent 的输入。        | 流程固定、逻辑线性的简单任务。如：翻译 -> 润色 -> 校对。  |
| HIERARCHICAL | 层级协调  | 引入一个 Supervisor（主管） 角色。由主管拆解任务、指派 Agent、审核结果。所有 Agent 只与主管通信。 | 复杂任务、需要强质量管控的场景。如：项目管理、多步分析。      |
| MARKET_BASED | 市场机制  | 基于“资源/价值”配置。Agent 会根据任务的“价格”或自身“负载”来竞争任务。                     | 资源敏感型任务、需要考虑处理成本或效率最优的场景。         |
| CONTRACT_NET | 合同网协议 | 类似“招标-投标”。主管发布需求（招标），各 Agent 根据能力提交标书，主管选出最优者执行。              | 任务具有高度专业化、需要从多个潜在方案中择优的场景。        |
| BLACKBOARD   | 黑板模式  | 所有 Agent 共享一个公共区域（黑板）。Agent 观察黑板状态，当发现自己能解决的部分时就主动介入。         | 探索性问题、非线性逻辑。如：复杂的解密、多源数据融合分析。     |
| SWARM        | 群体智能  | 无中心化决策。Agent 之间通过简单的启发式规则和局部交互（接力）来完成复杂任务。                    | 强调灵活性、去中心化的动态任务流转。如：快速响应的客服路由。    |


