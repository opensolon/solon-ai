# Solon AI Agent

**基于 Solon 框架构建的现代化“图驱动”多智能体 (Multi-Agent) 开发框架。**

Solon AI Agent 为企业级智能体应用设计，将 LLM 的推理逻辑转化为可编排、可观测、可治理的工作流图。

## 一、核心特性

### 多层次智能体架构

* 简单智能体 (Simple Agent)：标准 AI 接口封装，支持自定义角色人格与 Profile 档案。
* ReAct 智能体 (ReAct Agent)：基于 Reasoning-Acting 循环，具备强大的自省与自主工具调用能力。
* 团队智能体 (Team Agent)：智能体容器，通过协作协议驱动多专家协同作业。

### 丰富的团队协作协议


| 协议           | 模式    | 协作特征   | 核心价值                   | 最佳应用场景          |
|--------------|-------|--------|------------------------|---------------|
| NONE         | 透明式   | 无预设编排  | 完全的编排自由度，零框架干预         | 外部手绘流程、极高定制化业务 |
| HIERARCHICAL | 层级式   | 中心化决策  | 严格的任务拆解、指派与质量审计        | 复杂项目管理、多级合规审查、强质量管控任务 |
| SEQUENTIAL   | 顺序式   | 线性单向流  | 确定性的状态接力，减少上下文损失       | 翻译->校对->润色流水线、自动化发布流程  |
| SWARM        | 蜂群式   | 动态自组织  | 去中心化的快速接力，响应速度极快       | 智能客服路由、简单的多轮对话接力、高并发任务  |
| A2A          | 对等式   | 点对点移交  | 授权式移交，减少中间层干扰          | 专家咨询接力、技术支持转接、特定领域的垂直深度协作 |
| CONTRACT_NET | 合同网   | 招标投标制  | 通过竞争机制获取任务处理的最佳方案      | 寻找最优解任务、分布式计算分配、多方案择优场景    |
| MARKET_BASED | 市场式   | 经济博弈制  | 基于“算力/Token成本”等资源的最优配置 | 资源敏感型任务、高成本模型与低成本模型的混合调度    |
| BLACKBOARD   | 黑板式   | 共享上下文  | 异步协同，专家根据黑板状态主动介入      | 复杂故障排查、非线性逻辑推理、多源数据融合分析  |



## 二、快速开始


### 1. 添加依赖


```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-agent</artifactId>
    <version>${solon.version}</version>
</dependency>
```


### 2. 构建 ReAct 智能体 (单体)


```java
// 创建智能体构建器
ReActAgent agent = ReActAgent.of(chatModel)
    .name("weather_agent")
    .title("天气查询助手")
    .description("专业查询全球天气信息")
    .addTool(weatherTool)  // 添加天气查询工具
    .addInterceptor(new ToolRetryInterceptor())  // 添加工具重试拦截器
    .maxSteps(10)  // 设置最大推理步数
    .build();

// 执行智能体
AssistantMessage response = agent.prompt("今天北京的天气如何？")
    .call();
```


### 3. 构建团队智能体 (团队，多专家协同)


```java
// 创建多智能体团队
TeamAgent team = TeamAgent.of(chatModel)
    .name("design_team")
    .description("UI设计开发团队")
    .addAgent(uiDesignerAgent)     // UI设计师
    .addAgent(frontendDeveloperAgent)  // 前端开发
    .addAgent(codeReviewerAgent)    // 代码审核
    .protocol(TeamProtocols.SEQUENTIAL)  // 顺序协作协议
    .finishMarker("[DESIGN_TEAM_FINISH]")
    .build();

// 执行团队协作
AssistantMessage result = team.prompt("设计一个用户登录页面，包含表单验证和响应式布局")
    .call();
```


## 三、核心概念


### 档案系统 (Agent Profile)

Profile 是 Agent 的“身份证”，用于结构化地定义智能体的行为边界：

* 动态描述：支持占位符（如 `#{platform}`），根据会话上下文动态渲染 Agent 的职责。
* 能力标记：显式声明智能体擅长的技能（Skills）及支持的模态（如 Text、Image）。
* 硬性约束：通过 constraintAdd 注入必须遵守的准则（如“严禁使用感叹号”、“回复不超过50字”）。
* 风格定义：统一 Agent 的语言风格（如“极简主义”、“充满亲和力”）。


### 执行轨迹 (Trace)

每一轮思考、每一次决策、每一个 Token 消耗都被完整记录。

* 观测性：trace.getFormattedHistory() 还原 AI 完整思考路径。
* 度量性：内置性能指标（耗时、步数、Token 统计）。


### 会话治理 (Session)

管理智能体的短期与长期记忆，支持不同维度的持久化。：

* InMemoryAgentSession - 快速原型开发（默认）。
* RedisAgentSession - 分布式生产环境的状态保持。


### 协议 (Protocol)

协议定义智能体间的协作逻辑：

* 构建期 - 定义执行图拓扑结构
* 执行期 - 注入指令、工具和上下文
* 治理期 - 路由决策和异常处理

### 拦截器 (Interceptor)

提供 Agent 运行全生命周期的 AOP 切面：

* ReActInterceptor - 监控思考、行动、观察各个节点。
* TeamInterceptor - 干预团队调度逻辑，如实现 Human-in-the-loop (人工审批)。
* 内置拦截器：循环检测、结果净化、上下文压缩等

## 四、使用示例


### 示例 1：定义具有档案约束的 Agent

```java
SimpleAgent blogger = SimpleAgent.of(chatModel)
    .name("blogger")
    .description("负责 [#{platform}] 平台的文案创作") // 动态占位符
    .profile(p -> p
        .skillAdd("爆款标题制作", "小红书风写作")
        .constraintAdd("必须包含 Emoji", "严禁使用感叹号")
        .style("活泼亲切")
        .metaPut("version", "1.0.2"))
    .build();

// 执行时，描述中的 #{platform} 会自动从 session context 中取值
```


### 示例 2：工具调用智能体

```java
// 创建工具
FunctionTool calculator = new FunctionTool("calculator")
    .title("计算器")
    .description("执行数学计算")
    .stringParamAdd("expression", "数学表达式，如: 2+3*4")
    .doHandle(args -> {
        String expr = args.get("expression").toString();
        // 执行计算逻辑
        return "计算结果: " + eval(expr);
    });

// 创建智能体
ReActAgent mathAgent = ReActAgent.of(chatModel)
    .name("math_assistant")
    .addTool(calculator)
    .systemPrompt(ReActSystemPrompt.builder()
        .role("你是专业的数学助手")
        .instruction("请帮助用户解决数学问题")
        .build())
    .build();
```

### 示例 3：多协议协作


```java
// 创建不同协议的团队
TeamAgent sequentialTeam = TeamAgent.of(chatModel)
    .name("sequential_team")
    .addAgent(researcher, designer, developer)
    .protocol(TeamProtocols.SEQUENTIAL)  // 顺序执行
    .build();

TeamAgent swarmTeam = TeamAgent.of(chatModel)
    .name("swarm_team")
    .addAgent(researcher, designer, developer)
    .protocol(TeamProtocols.SWARM)  // 动态接力
    .build();

TeamAgent a2aTeam = TeamAgent.of(chatModel)
    .name("a2a_team")
    .addAgent(researcher, designer, developer)
    .protocol(TeamProtocols.A2A)  // 对等移交
    .build();
```


### 示例 4：自定义协议

```java
// 自定义协作协议
public class CustomProtocol extends TeamProtocolBase {
    public CustomProtocol(TeamConfig config) {
        super(config);
    }
    
    @Override
    public String name() {
        return "CUSTOM";
    }
    
    @Override
    public void buildGraph(GraphSpec spec) {
        // 自定义执行图
        spec.addStart("start").linkAdd("analysis");
        spec.addActivity("analysis").linkAdd("planning");
        spec.addActivity("planning").linkAdd("execution");
        spec.addActivity("execution").linkAdd("review");
        spec.addActivity("review").linkAdd("end");
        spec.addEnd("end");
    }
    
    @Override
    public void injectSupervisorInstruction(Locale locale, StringBuilder sb) {
        sb.append("\n## 自定义协作协议\n");
        sb.append("1. 按照分析->规划->执行->评审的流程执行\n");
        sb.append("2. 每个阶段必须由专业成员处理\n");
    }
}
```


## 五、集成配置

### 与 Solon 集成


```java
@Configuration
public class AgentConfig {
    @Bean
    public ReActAgent mathAgent(@Autowired ChatModel chatModel) {
        return ReActAgent.of(chatModel)
            .name("math_agent")...
            .build();
    }
    
    @Bean
    public TeamAgent designTeam(@Autowired ChatModel chatModel) {
        return TeamAgent.of(chatModel)
            .name("design_team")...
            .build();
    }
}
```

### 会话存储配置

```java
//简单示意
public class AgentSessoinConfig {
    // 使用 Redis 会话存储
    //@Bean
    public AgentSessionProvider redisSession(RedisClient redisClient) {
        Map<String, AgentSession> map = new ConcurrentHashMap<>();
        return (sessionId) -> map.computeIfAbsent(sessionId, k -> new RedisAgentSession(k, redisClient));
    }

    // 使用内存会话存储（默认）
    @Bean
    public AgentSessionProvider inMemorySessoin() {
        Map<String, AgentSession> map = new ConcurrentHashMap<>();
        return (sessionId) -> map.computeIfAbsent(sessionId, k -> new InMemoryAgentSession(k));
    }

    public static class Demo {
        @Inject
        AgentSessionProvider sessionProvider;
        @Inject
        ReActAgent reActAgent;

        public String hello() throws Throwable{
            AgentSession session = sessionProvider.getSession("test");

            return reActAgent.prompt("你好呀!")
                    .session(session)
                    .call()
                    .getContent();
        }
    }
}
```


## 六、监控与调试


### 性能监控


```java
// 获取并打印轨迹度量指标
TeamTrace trace = team.getTrace(session);
System.out.println("Total Time: " + trace.getMetrics().getDurationMs() + "ms");
System.out.println("Token Usage: " + trace.getMetrics().getTotalTokens());
```

### 拦截器监控

```java
// 自定义监控拦截器
public class MonitoringInterceptor implements ReActInterceptor {
    @Override
    public void onThought(ReActTrace trace, String thought) {
        logger.info("Agent {} 思考: {}", trace.getAgentName(), thought);
    }
    
    @Override
    public void onAction(ReActTrace trace, String toolName, Map<String, Object> args) {
        logger.info("Agent {} 调用工具: {} 参数: {}", 
            trace.getAgentName(), toolName, args);
    }
    
    @Override
    public void onObservation(ReActTrace trace, String result) {
        logger.info("Agent {} 观察结果: {}", trace.getAgentName(), result);
    }
}
```
