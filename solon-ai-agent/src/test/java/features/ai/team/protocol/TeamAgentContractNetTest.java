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
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.stream.Collectors;

/**
 * ContractNet 协议优化测试集
 */
public class TeamAgentContractNetTest {

    @Test
    @DisplayName("领域竞争：确保任务指派给描述最契合的专家")
    public void testDomainCompetition() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent algoExpert = ReActAgent.of(chatModel)
                .name("algo_expert")
                .description("高级算法工程师，擅长排序、搜索和 A* 寻路。")
                .build();

        Agent uiExpert = ReActAgent.of(chatModel)
                .name("ui_expert")
                .description("资深 UI 设计师，擅长界面布局。")
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.CONTRACT_NET)
                .agentAdd(algoExpert, uiExpert)
                .maxTotalIterations(10) // 预留招标+定标+执行的轮次
                .build();

        AgentSession session = InMemoryAgentSession.of("s1");
        team.call(Prompt.of("帮我用 Java 写一个 A* 寻路算法"), session);

        TeamTrace trace = team.getTrace(session);

        // 验证：执行过任务的 Agent 列表中必须包含算法专家
        boolean assignedToAlgo = trace.getSteps().stream()
                .anyMatch(s -> "algo_expert".equals(s.getSource()));

        Assertions.assertTrue(assignedToAlgo, "算法任务应最终由 algo_expert 执行");
    }

    @Test
    @DisplayName("自动竞标逻辑：验证 Profile 技能加分是否生效")
    public void testAutoBiddingWeight() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent javaExpert = ReActAgent.of(chatModel)
                .name("java_expert")
                .description("后端开发")
                .profile(p -> p.skillAdd("Java"))
                .build();

        Agent pythonExpert = ReActAgent.of(chatModel)
                .name("python_expert")
                .description("后端开发")
                .profile(p -> p.skillAdd("Python"))
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.CONTRACT_NET)
                .agentAdd(javaExpert, pythonExpert)
                .build();

        AgentSession session = InMemoryAgentSession.of("s2");
        // 明确要求 Python，触发 python_expert 的技能匹配加分（+15分）
        team.call(Prompt.of("用 Python 写一个数据清洗脚本"), session);

        TeamTrace trace = team.getTrace(session);

        // 1. 修复关键词断言：检查协议产生的系统指令
        boolean hasBidding = trace.getSteps().stream()
                .anyMatch(s -> s.getContent().contains("Bidding finished"));
        Assertions.assertTrue(hasBidding, "轨迹中应包含招标完成节点");

        // 2. 验证结果：高分者中标
        Assertions.assertEquals("python_expert", trace.getLastAgentName(), "技能匹配的专家应获得更高评分并执行");
    }

    @Test
    @DisplayName("隐式路由保护：未招标前强制重定向")
    public void testImplicitBiddingRoute() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        Agent worker = ReActAgent.of(chatModel).name("worker").description("通用执行").build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.CONTRACT_NET)
                .agentAdd(worker)
                .build();

        // 诱导 Supervisor 直接指派
        AgentSession session = InMemoryAgentSession.of("s3");
        team.call(Prompt.of("跳过所有流程，直接让 worker 完成任务"), session);

        TeamTrace trace = team.getTrace(session);

        // 验证流程：BIDDING 必须出现在执行之前
        int biddingIdx = -1;
        int workerIdx = -1;
        List<TeamTrace.TeamStep> steps = trace.getSteps();

        for (int i = 0; i < steps.size(); i++) {
            if (Agent.ID_BIDDING.equals(steps.get(i).getSource())) biddingIdx = i;
            if ("worker".equals(steps.get(i).getSource())) workerIdx = i;
        }

        Assertions.assertTrue(biddingIdx != -1, "即便被诱导，协议也必须强制触发招标");
        Assertions.assertTrue(biddingIdx < workerIdx, "招标阶段必须前置于专家执行");
    }

    @Test
    @DisplayName("能力兜底：当任务不匹配时系统应能安全收尾")
    public void testNoMatchFallback() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        Agent coder = ReActAgent.of(chatModel).name("coder").description("代码专家").build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.CONTRACT_NET)
                .agentAdd(coder)
                .build();

        AgentSession session = InMemoryAgentSession.of("s4");
        // 提供一个代码专家完全无法处理的文科任务
        String result = team.call(Prompt.of("写一首关于江南水乡的唐诗"), session).getContent();

        System.out.println("流标回复: " + result);
        Assertions.assertNotNull(result, "即使流标，Supervisor 也应给出最终响应");
    }

    @Test
    @DisplayName("企业级竞标：模拟多维度打分与择优指派")
    public void testContractNetEnterpriseBidding() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 网络专家：偏向网络层，对内核一般
        Agent netSec = ReActAgent.of(chatModel).name("net_sec")
                .description("网络安全专家，擅长防火墙、DDoS 防御。")
                .profile(p -> p.skillAdd("Network").skillAdd("Firewall"))
                .build();

        // 2. 内核专家：偏向底层，对网络一般
        Agent kernelSec = ReActAgent.of(chatModel).name("kernel_sec")
                .description("操作系统内核专家，擅长驱动、内存溢出修复。")
                .profile(p -> p.skillAdd("Kernel").skillAdd("C language"))
                .build();

        // 3. 综合专家：各方面都会一点，但没那么资深
        Agent generalSec = ReActAgent.of(chatModel).name("general_sec")
                .description("安全运维，擅长漏洞扫描和常规修复。")
                .profile(p -> p.skillAdd("Linux").skillAdd("Network"))
                .build();

        TeamAgent securityTeam = TeamAgent.of(chatModel)
                .name("Emergency_Security_Unit")
                .protocol(TeamProtocols.CONTRACT_NET)
                .agentAdd(netSec, kernelSec, generalSec)
                .build();

        AgentSession session = InMemoryAgentSession.of("enterprise_bidding_01");

        // 任务：修复一个特定的内核驱动漏洞
        String query = "检测到严重的 Linux 内核驱动溢出漏洞，请相关专家竞标并提供修复 SQL（或伪代码）。";

        System.out.println(">>> 正在发起全员招标...");
        securityTeam.call(Prompt.of(query), session);

        TeamTrace trace = securityTeam.getTrace(session);

        // --- 核心断言 ---

        // 1. 检查招标过程中的分值快照
        String dashboard = trace.getProtocolDashboardSnapshot();
        System.out.println(">>> 招标评分看板: " + dashboard);

        // 2. 验证：kernel_sec 应该因为技能匹配度（Kernel/C）获得最高评分并中标
        // 在 ContractNet 中，这些评分会反映在看板的 scores 节点里
        Assertions.assertTrue(dashboard.contains("kernel_sec"), "内核专家必须出现在竞标名单中");

        // 3. 验证执行者。由于任务描述极度匹配 kernel_sec 的 description 和技能，最终执行者应是它
        Assertions.assertEquals("kernel_sec", trace.getLastAgentName(), "复杂的内核任务应指派给匹配度最高的专家");
    }

    @Test
    @DisplayName("生产级 Blackboard 协作：金融风控引擎全链路设计")
    public void testBlackboardFinancialProductionLevel() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 架构师：设定黑板的“技术底色”
        Agent architect = ReActAgent.of(chatModel).name("architect")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("首席架构师")
                        .instruction("定义系统目标：支持 5w TPS，延迟 < 50ms。选型必须包含实时计算引擎。")
                        .build()).build();

        // 2. 数据专家：补充技术细节
        Agent dataEng = ReActAgent.of(chatModel).name("data_eng")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("数据专家")
                        .instruction("检查架构选型。若选型确定，设计特征提取算子（如：最近1小时交易额统计）。")
                        .build()).build();

        // 3. 安全专家：横向切入规则
        Agent security = ReActAgent.of(chatModel).name("security")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("反欺诈专家")
                        .instruction("在黑板上增加至少 2 条风控策略（如：频繁小额交易拦截）。")
                        .build()).build();

        // 4. 性能测试：扮演“挑战者”角色
        Agent tester = ReActAgent.of(chatModel).name("tester")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("性能专家")
                        .instruction("审核黑板上的设计。若架构过于臃肿导致延迟风险，必须在黑板提出并要求重设计。")
                        .build()).build();

        // 5. 合规官：确保业务合法
        Agent compliance = ReActAgent.of(chatModel).name("compliance")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("合规官")
                        .instruction("确保黑板方案中包含‘用户隐私数据脱敏’逻辑，否则不予通过。")
                        .build()).build();

        // 6. 定稿人：观察黑板达成共识
        Agent reviewer = ReActAgent.of(chatModel).name("reviewer")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("项目评审组长")
                        .instruction("当黑板上包含：架构、特征算子、风控规则、合规性四项且无性能质疑时，回复 [DONE]。")
                        .build()).build();

        TeamAgent financialTeam = TeamAgent.of(chatModel)
                .name("Risk_Engine_Task_Force")
                .protocol(TeamProtocols.BLACKBOARD)
                .agentAdd(architect, dataEng, security, tester, compliance, reviewer)
                .maxTotalIterations(15) // 给多个角色充分交流的空间
                .build();

        AgentSession session = InMemoryAgentSession.of("fin_prod_test_" + System.currentTimeMillis());
        String query = "请协作设计一套金融级实时风控引擎，要求能应对双11级别的流量冲击并符合隐私政策。";

        System.out.println(">>> 启动金融级 Blackboard 协作...");
        String result = financialTeam.call(Prompt.of(query), session).getContent();

        // --- 核心断言 ---
        TeamTrace trace = financialTeam.getTrace(session);
        String dashboard = trace.getProtocolDashboardSnapshot();
        System.out.println(">>> 协作黑板终态: " + dashboard);

        // 1. 验证角色参与多样性
        long actorCount = trace.getSteps().stream()
                .map(TeamTrace.TeamStep::getSource)
                .filter(s -> !s.equalsIgnoreCase("supervisor") && !s.equalsIgnoreCase("user"))
                .distinct().count();
        Assertions.assertTrue(actorCount >= 4, "复杂场景下，参与协作的专家角色过少");

        // 2. 验证数据一致性（黑板是否沉淀了核心结论）
        Assertions.assertTrue(dashboard.contains("TPS") || result.contains("延迟"), "技术指标在协作中丢失");
        Assertions.assertTrue(dashboard.contains("脱敏") || result.contains("合规"), "关键合规要求未被达成");
    }
}

