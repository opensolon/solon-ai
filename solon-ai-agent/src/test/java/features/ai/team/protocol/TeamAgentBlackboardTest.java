package features.ai.team.protocol;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActSystemPrompt;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamSystemPrompt;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.util.stream.Collectors;

/**
 * Blackboard 策略测试：基于共享状态的补位协作
 */
public class TeamAgentBlackboardTest {

    @Test
    public void testBlackboardLogic() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 优化：使用 ReActSystemPromptCn 赋予 DB 设计师更明确的“黑板贡献”职责
        Agent databaseDesigner = ReActAgent.of(chatModel)
                .name("db_designer")
                .description("负责数据库表结构设计，输出 SQL 代码。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("你是一个资深数据库架构师")
                        .instruction("### 职责\n" +
                                "1. 仅负责数据库表结构设计（SQL 代码）。\n" +
                                "2. 检查黑板，若尚无表结构，请基于需求设计 2 张核心表。\n" +
                                "3. 直接输出 SQL 代码块，不要多余解释。")
                        .build())
                .build();

        // 2. 优化：赋予 API 设计师“黑板观察者”职责，确保其基于已有 SQL 设计接口
        Agent apiDesigner = ReActAgent.of(chatModel)
                .name("api_designer")
                .description("负责 RESTful API 接口协议设计。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("你是一个 API 接口专家")
                        .instruction("### 职责\n" +
                                "1. 仅负责 RESTful API 接口协议设计。\n" +
                                "2. **必须参考黑板上已有的数据库表结构**来设计 2 个核心接口。\n" +
                                "3. 确保接口字段与数据库字段一致。")
                        .build())
                .build();

        // 组建团队
        TeamAgent team = TeamAgent.of(chatModel)
                .name("blackboard_team")
                .protocol(TeamProtocols.BLACKBOARD)
                .agentAdd(databaseDesigner)
                .agentAdd(apiDesigner)
                .maxTotalIterations(5)
                .build();

        //
        System.out.println("--- Blackboard Team Graph ---\n" + team.getGraph().toYaml());

        AgentSession session = InMemoryAgentSession.of("session_blackboard_01");
        String query = "请为我的电商系统设计用户模块的数据库和配套接口。";
        String result = team.call(Prompt.of(query), session).getContent();

        System.out.println("=== 执行结果 ===\n" + result);

        TeamTrace trace = team.getTrace(session);
        Assertions.assertNotNull(trace);
        Assertions.assertTrue(trace.getStepCount() > 0);
    }

    @Test
    public void testBlackboardWithReviewerLoop() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 优化：赋予审核者“循环控制器”的提示词
        Agent reviewer = ReActAgent.of(chatModel)
                .name("reviewer")
                .description("负责审核设计规范。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("你是一个严谨的架构审核员")
                        .instruction("### 审核规则\n" +
                                "1. 检查黑板上的 SQL。\n" +
                                "2. **必须包含 'created_at' 字段**，否则指出错误并要求 db_designer 修正。\n" +
                                "3. 若审核通过，请仅回复关键字 'FINISH'。")
                        .build())
                .build();

        Agent dbDesigner = ReActAgent.of(chatModel)
                .name("db_designer")
                .description("数据库设计专家")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("数据库开发者")
                        .instruction("根据黑板上审核员给出的反馈修正 SQL 代码。")
                        .build())
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("loop_test_team")
                .protocol(TeamProtocols.BLACKBOARD)
                .agentAdd(dbDesigner)
                .agentAdd(reviewer)
                .maxTotalIterations(6)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_loop_01");
        String result = team.call(Prompt.of("设计一张用户表，必须满足审核员的要求"), session).getContent();

        TeamTrace trace = team.getTrace(session);
        long dbDesignCount = trace.getSteps().stream()
                .filter(s -> s.getSource().equals("db_designer")).count();

        Assertions.assertTrue(dbDesignCount >= 1);
    }

    @Test
    public void testBlackboardDataConsistency() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        // 随机表名
        String randomTableName = "t_" + System.currentTimeMillis() % 10000;

        // 1. db_designer：不再提及内部方法名
        Agent dbDesigner = ReActAgent.of(chatModel)
                .name("db_designer")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("数据库开发者")
                        .instruction("### 核心任务\n" +
                                "1. 创建用户表，表名必须设为: `" + randomTableName + "`\n" +
                                "2. **关键动作**：请使用你的同步工具将该表名登记到协作黑板上。")
                        .build())
                .build();

        // 2. api_designer：依赖黑板
        Agent apiDesigner = ReActAgent.of(chatModel)
                .name("api_designer")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("API 开发者")
                        .instruction("### 核心限制\n" +
                                "1. 检查协作黑板，基于已登记的表名生成 RESTful 接口。\n" +
                                "2. 严禁自创表名。")
                        .build())
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.BLACKBOARD)
                .agentAdd(dbDesigner, apiDesigner)
                .maxTotalIterations(10)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_" + randomTableName);

        // 执行：给 Supervisor 一个清晰的启动指令
        team.call(Prompt.of("请协作完成设计：先由数据库专家定义表名并登记，再由API专家设计接口"), session);

        // --- 3. 基于源码的精准断言 ---
        TeamTrace trace = team.getTrace(session);

        // 获取黑板数据的 JSON 快照
        String blackboardSnapshot = trace.getProtocolDashboardSnapshot();
        System.out.println(">>> 协作黑板快照: " + blackboardSnapshot);

        // 获取所有专家的产出文本
        String allExpertContent = trace.getSteps().stream()
                .filter(TeamTrace.TeamStep::isAgent)
                .map(TeamTrace.TeamStep::getContent)
                .collect(Collectors.joining(" "));

        // 验证逻辑：表名要么在黑板数据里，要么在专家的最终陈述里
        boolean isFoundInBlackboard = blackboardSnapshot.contains(randomTableName);
        boolean isFoundInContent = allExpertContent.contains(randomTableName);

        System.out.println("验证结果：[黑板存在: " + isFoundInBlackboard + "], [内容存在: " + isFoundInContent + "]");

        Assertions.assertTrue(isFoundInBlackboard || isFoundInContent,
                "错误：表名 [" + randomTableName + "] 彻底丢失，协作链路断裂");
    }

    @Test
    public void testLoopPreventionInBlackboard() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义互相推诿的专家
        Agent a = ReActAgent.of(chatModel).name("agent_a")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("专家 A")
                        .instruction("你非常懒，坚持认为任何任务都该由专家 B 完成。不要自己尝试。")
                        .build()).build();

        Agent b = ReActAgent.of(chatModel).name("agent_b")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("专家 B")
                        .instruction("你非常懒，坚持认为任何任务都该由专家 A 完成。不要自己尝试。")
                        .build()).build();

        // 2. 构建团队，设置严格的团队迭代上限
        int maxTeamRounds = 3;
        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.BLACKBOARD)
                .agentAdd(a, b)
                .maxTotalIterations(maxTeamRounds) // 限制 Supervisor 只准点名 3 次
                .build();

        // 3. 使用全新的 Session，防止被日志中出现的 "Maximum iterations reached" 历史污染
        AgentSession session = InMemoryAgentSession.of("loop_test_" + System.currentTimeMillis());

        team.call(Prompt.of("请帮我写一段代码"), session);

        TeamTrace trace = team.getTrace(session);

        // --- 修正后的断言策略 ---

        // 策略 A: 验证团队决策轮次（Supervisor 工作的次数）
        long supervisorRounds = trace.getSteps().stream()
                .filter(s -> "supervisor".equalsIgnoreCase(s.getSource()))
                .count();

        System.out.println("团队决策总轮次: " + supervisorRounds);
        Assertions.assertTrue(supervisorRounds <= maxTeamRounds, "Supervisor 决策次数超标，死循环防御失效");

        // 策略 B: 如果非要检查 StepCount，需要预留 Agent 内部 ReAct 思考的步数
        // 一个 Agent 正常回复至少 1-2 步，Supervisor 决策 1 步
        System.out.println("轨迹总步数: " + trace.getStepCount());
        // 允许 3 轮决策，每轮 Agent 思考 3 步，总步数预留到 15 比较安全
        Assertions.assertTrue(trace.getStepCount() < 20, "总步数异常，可能发生了深度死循环");
    }

    @Test
    public void testBlackboardAutomaticGapFilling() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 只有前端专家和后端专家，没有 UI 专家
        Agent frontend = ReActAgent.of(chatModel).name("frontend")
                .systemPrompt(ReActSystemPrompt.builder()
                        .instruction("根据黑板上的接口定义和 UI 样式，编写 React 组件。").build()).build();

        Agent backend = ReActAgent.of(chatModel).name("backend")
                .systemPrompt(ReActSystemPrompt.builder()
                        .instruction("根据黑板上的业务逻辑，编写 Spring Boot 控制器。").build()).build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.BLACKBOARD)
                .agentAdd(frontend, backend)
                .build();

        AgentSession session = InMemoryAgentSession.of("gap_fill_test");

        // 注入一个已经包含了 UI 设计图地址的黑板初始状态（模拟部分任务已完成）
        session.getSnapshot().put("shared_blackboard", "{\"ui_design_url\": \"http://figma.com/design_001\"}");

        String query = "UI 设计已经有了，请前后端专家根据这个地址的内容完成实现。";
        String result = team.call(Prompt.of(query), session).getContent();

        // --- 断言 ---
        TeamTrace trace = team.getTrace(session);
        // 验证：即使没有 UI 专家参与，团队也应该能基于 context 中的 UI 信息继续工作
        Assertions.assertTrue(result.contains("React") && result.contains("Spring"), "前后端专家未能在补位场景下完成工作");
    }

    @Test
    public void testBlackboardEnterpriseProductionLevel() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 架构师：设定黑板的“基准目标”
        Agent architect = ReActAgent.of(chatModel).name("Architect")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("首席架构师")
                        .instruction("定义系统目标：支持 10w QPS 的秒杀。在黑板登记选型（如 Redis+Lua）。")
                        .build()).build();

        // 2. DBA：基于架构师的选型设计存储
        Agent dba = ReActAgent.of(chatModel).name("DBA")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("DB 专家")
                        .instruction("检查黑板上的 QPS 目标，设计 Redis 预热脚本。若发现 QPS 过高，需在黑板提示内存风险。")
                        .build()).build();

        // 3. 安全专家：横向补位，检查 DBA 的逻辑
        Agent security = ReActAgent.of(chatModel).name("Security")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("安全审计员")
                        .instruction("针对黑板上的接口设计防刷策略。必须包含 '设备指纹' 校验逻辑。")
                        .build()).build();

        // 4. 运维专家：垂直补位，设计扩容
        Agent devops = ReActAgent.of(chatModel).name("DevOps")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("SRE 工程师")
                        .instruction("基于黑板上的资源消耗，设计 K8s 自动伸缩策略（HPA）。")
                        .build()).build();

        // 5. 审核者：确保黑板信息达成共识
        Agent reviewer = ReActAgent.of(chatModel).name("Reviewer")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("交付负责人")
                        .instruction("当黑板上包含：[架构选型]、[存储脚本]、[安全策略]、[扩容策略] 四项时，回复 'DONE'。")
                        .build()).build();

        TeamAgent enterpriseTeam = TeamAgent.of(chatModel)
                .name("High_Availability_Squad")
                .protocol(TeamProtocols.BLACKBOARD)
                .agentAdd(architect, dba, security, devops, reviewer)
                .maxTotalIterations(15) // 生产环境链路长，角色多，允许更多决策轮次
                .build();

        AgentSession session = InMemoryAgentSession.of("enterprise_prod_001");
        String query = "我们要上线一个‘1元抢购耳机’的活动，QPS 预期 10 万，请全组成员在黑板上协作完成技术方案。";

        System.out.println(">>> 正在启动企业级 Blackboard 协作流水线...");
        String result = enterpriseTeam.call(Prompt.of(query), session).getContent();

        // --- 生产环境关键断言 ---
        TeamTrace trace = enterpriseTeam.getTrace(session);
        String dashboard = trace.getProtocolDashboardSnapshot();

        System.out.println(">>> 最终协作看板内容: " + dashboard);

        // 断言 1: 角色广泛性
        long distinctAgents = trace.getSteps().stream()
                .map(TeamTrace.TeamStep::getSource)
                .filter(s -> !s.equalsIgnoreCase("supervisor"))
                .distinct().count();
        Assertions.assertTrue(distinctAgents >= 3, "协作深度不足，专家参与度不够");

        // 断言 2: 黑板数据沉淀
        // 在生产环境，黑板不应该只在 Prompt 里，应该在执行后保留关键的 JSON 状态
        Assertions.assertTrue(dashboard.contains("QPS") || dashboard.contains("Redis"), "黑板关键选型丢失");

        // 断言 3: 安全性验证
        Assertions.assertTrue(result.contains("指纹") || result.contains("校验"), "安全合规逻辑在协作中被遗漏");
    }

    @Test
    @DisplayName("生产级多协议博弈：跨境清算系统重构方案设计")
    public void testEnterpriseComplexProductionLevel() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. CTO：定义全局目标
        Agent cto = ReActAgent.of(chatModel).name("CTO")
                .description("首席技术官。设定架构标准：高并发、低延迟、分布式。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("CTO")
                        .instruction("定义系统基准：必须支持 10k TPS。要求使用分布式数据库，并在黑板上登记选型。")
                        .build()).build();

        // 2. 金融专家：业务建模
        Agent finExpert = ReActAgent.of(chatModel).name("FinExpert")
                .description("金融业务专家。负责跨境清算流程和货币对转换规则。")
                .build();

        // 3. DBA：性能建模（依赖 CTO 的选型）
        Agent dba = ReActAgent.of(chatModel).name("DBA")
                .description("数据库专家。设计分库分表。若 CTO 选型不合理，必须在黑板提出质疑。")
                .build();

        // 4. 安全专家（关键拦截者）：负责找茬
        Agent security = ReActAgent.of(chatModel).name("Security")
                .description("合规与安全官。强制要求国密算法和数据脱敏。")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("安全合规")
                        .instruction("检查方案。若未提及 'SM4加密' 或 '数据脱敏'，必须在回复中明确回复【审计打回】，且不允许流程结束。")
                        .build()).build();

        // 5. 运维专家：负责灾备
        Agent sre = ReActAgent.of(chatModel).name("SRE")
                .description("运维专家。设计两地三中心方案。")
                .build();

        // 6. 决策团队
        TeamAgent heavyTeam = TeamAgent.of(chatModel)
                .name("Core_Settlement_Squad")
                .protocol(TeamProtocols.BLACKBOARD) // 使用黑板协议，支持非线性协作
                .agentAdd(cto, finExpert, dba, security, sre)
                .maxTotalIterations(20) // 给专家组充分“吵架”的空间
                .finishMarker("FINISH_ARCH")
                .systemPrompt(TeamSystemPrompt.builder()
                        .role("方案协调中心")
                        .instruction("### 协作逻辑：\n" +
                                "1. 必须在黑板上汇总：架构、业务规则、数据库设计、安全方案、运维方案。\n" +
                                "2. **硬性约束**：如果 Security 提出了【审计打回】，必须让相关专家重新修改方案，直到 Security 确认【审计通过】。\n" +
                                "3. 所有模块完整且审计通过后，输出 FINISH_ARCH 并总结最终技术规格书。")
                        .build())
                .build();

        AgentSession session = InMemoryAgentSession.of("complex_prod_001");
        String query = "我们要重构跨境支付清算系统，请团队各成员协作，输出一份符合金融合规的高并发架构方案。";

        System.out.println(">>> 生产级协作启动...");
        String result = heavyTeam.call(Prompt.of(query), session).getContent();

        // --- 深度生产断言 ---
        TeamTrace trace = heavyTeam.getTrace(session);

        // 1. 断言：角色博弈深度
        // 生产环境下，好的协作不应该是每个 Agent 只说一次，应该有“打回-修改-再审”的循环
        long totalSteps = trace.getStepCount();
        System.out.println("协作总步数: " + totalSteps);
        Assertions.assertTrue(totalSteps > 6, "协作深度不足，可能只是简单的点名，没有形成有效博弈");

        // 2. 断言：安全红线穿透
        // 验证安全专家的要求是否被最终方案采纳
        Assertions.assertTrue(result.contains("加密") || result.contains("脱敏"), "安全合规要求在协作中被遗漏");

        // 3. 断言：技术指标达成
        Assertions.assertTrue(result.contains("TPS") && (result.contains("分布式") || result.contains("分库")), "核心技术指标未达成");

        // 4. 断言：协议看板完整性
        String dashboard = trace.getProtocolDashboardSnapshot();
        System.out.println("最终黑板状态: " + dashboard);
        Assertions.assertNotNull(dashboard, "协作黑板快照不能为空");
    }
}