package features.ai.team.protocol;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamProtocols;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.stream.Collectors;

/**
 * MarketBased 策略测试：基于能力描述的竞争指派
 * <p>
 * 验证目标：
 * 1. 验证 MARKET_BASED 协议下，协调者能否通过语义匹配从“人才市场”中选出最合适的 Agent。
 * 2. 验证基于 AgentSession 的状态流转与 Trace 记录的完整性。
 * </p>
 */
public class TeamAgentMarketTest {

    @Test
    public void testMarketSelectionLogic() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 提供领域细分的专家
        Agent pythonExpert = ReActAgent.of(chatModel)
                .name("python_coder")
                .description("Python 专家，擅长数据处理和自动化脚本。")
                .build();

        Agent javaExpert = ReActAgent.of(chatModel)
                .name("java_coder")
                .description("Java 专家，擅长高并发架构设计、支付结算和分布式网关。")
                .build();

        // 2. 使用 MARKET_BASED 策略组建团队
        TeamAgent team = TeamAgent.of(chatModel)
                .name("market_team")
                .protocol(TeamProtocols.MARKET_BASED)
                .agentAdd(pythonExpert)
                .agentAdd(javaExpert)
                .build();

        // 打印市场结构 YAML
        System.out.println("--- Market-Based Team Graph ---\n" + team.getGraph().toYaml());

        // 3. 使用 AgentSession 管理会话
        AgentSession session = InMemoryAgentSession.of("session_market_01");

        // 发起一个明显属于 Java 领域的高并发需求
        String query = "我需要实现一个支持每秒万级并发的支付结算网关后端。";
        String result = team.call(Prompt.of(query), session).getContent();

        System.out.println("=== 任务结果 ===\n" + result);

        // 4. 验证决策轨迹
        TeamTrace trace = team.getTrace(session);
        Assertions.assertNotNull(trace, "应该有轨迹记录");
        Assertions.assertFalse(result.isEmpty(), "结果不应该为空");

        // 检查首位执行者
        if (trace.getStepCount() > 0) {
            String firstAgentName = trace.getSteps().get(0).getSource();
            System.out.println("调解器(Mediator)在市场中选择的专家: " + firstAgentName);

            // 语义期望：Java 专家应处理高并发支付网关
            boolean selectedJavaExpert = "java_coder".equals(firstAgentName);
            System.out.println("符合预期选择: " + selectedJavaExpert);
        }

        System.out.println("总步数: " + trace.getStepCount());
        System.out.println("详细协作轨迹:\n" + trace.getFormattedHistory());
    }

    @Test
    public void testMarketSelectionForPythonTask() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 创建领域专家
        Agent pythonExpert = ReActAgent.of(chatModel)
                .name("python_data_scientist")
                .description("Python 数据科学家，擅长数据分析、特征工程和机器学习建模。")
                .build();

        Agent javaExpert = ReActAgent.of(chatModel)
                .name("java_backend_engineer")
                .description("Java 后端工程师，擅长微服务、分布式系统和事务处理。")
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("market_python_team")
                .protocol(TeamProtocols.MARKET_BASED)
                .agentAdd(pythonExpert)
                .agentAdd(javaExpert)
                .build();

        // 使用 AgentSession
        AgentSession session = InMemoryAgentSession.of("session_market_python");

        // 发起一个明显属于 Python 领域的数据分析任务
        String query = "我需要分析一个大型数据集，进行特征工程和机器学习建模，预测用户行为。";
        String result = team.call(Prompt.of(query), session).getContent();

        // 2. 轨迹验证
        TeamTrace trace = team.getTrace(session);
        Assertions.assertNotNull(trace);

        if (trace.getStepCount() > 0) {
            String selectedAgent = trace.getSteps().get(0).getSource();
            System.out.println("市场指派的专家: " + selectedAgent);

            boolean selectedPythonExpert = "python_data_scientist".equals(selectedAgent);
            System.out.println("符合 Python 领域匹配期望: " + selectedPythonExpert);
        }

        System.out.println("协作轨迹:\n" + trace.getFormattedHistory());
        Assertions.assertTrue(trace.getStepCount() > 0);
    }

    @Test
    @DisplayName("生产级 Market 协作：多维度能力匹配与跨界兜底")
    public void testMarketProductionComplexity() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 准备专业领域极其接近的专家
        Agent iosDev = ReActAgent.of(chatModel).name("ios_dev")
                .description("专注 Swift 和 SwiftUI。擅长苹果生态系统应用开发。")
                .build();

        Agent androidDev = ReActAgent.of(chatModel).name("android_dev")
                .description("专注 Kotlin 和 Compose。擅长安卓平台原生开发。")
                .build();

        Agent crossPlatform = ReActAgent.of(chatModel).name("flutter_dev")
                .description("全栈开发专家。擅长 Flutter 跨端方案，能同时生成 iOS 和 Android 代码。")
                .build();

        TeamAgent appMarket = TeamAgent.of(chatModel)
                .name("App_Dev_Market")
                .protocol(TeamProtocols.MARKET_BASED)
                .agentAdd(iosDev, androidDev, crossPlatform)
                .build();

        // 场景 A：明确的跨端需求。期望：市场调度给 flutter_dev
        AgentSession session1 = InMemoryAgentSession.of("s_cross");
        String res1 = appMarket.call(Prompt.of("我需要低成本一次性开发出支持双平台的 App 演示原型"), session1).getContent();

        TeamTrace trace1 = appMarket.getTrace(session1);
        System.out.println("跨端需求指派给: " + trace1.getLastAgentName());
        Assertions.assertEquals("flutter_dev", trace1.getLastAgentName(), "跨端任务应优先匹配全栈专家");

        // 场景 B：无法匹配的极端需求（如：嵌入式硬件驱动）
        AgentSession session2 = InMemoryAgentSession.of("s_hardware");
        String res2 = appMarket.call(Prompt.of("帮我写一个基于 C 语言的 STM32 芯片驱动"), session2).getContent();

        // 验证：即使无法匹配，系统也应能给出“市场中无合适专家”的响应，而不是随机指派
        System.out.println("无人接单时的回复: " + res2);
        Assertions.assertNotNull(res2);
    }

    @Test
    @DisplayName("生产级 Market 协作：多云环境下的语义博弈与精准指派")
    public void testMarketCloudNativeProductionLevel() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义具有高度竞争力的“市场专家”
        Agent aliyun = ReActAgent.of(chatModel).name("aliyun_expert")
                .description("阿里云首席架构师。精通 ACK(K8s)、SLB 和阿里云全家桶。")
                .profile(p -> p.skillAdd("ACK").skillAdd("AlibabaCloud"))
                .build();

        Agent aws = ReActAgent.of(chatModel).name("aws_expert")
                .description("AWS 认证专家。精通 EKS、IAM、S3 及 AWS 架构模式。")
                .profile(p -> p.skillAdd("EKS").skillAdd("AWS"))
                .build();

        Agent generalist = ReActAgent.of(chatModel).name("k8s_generalist")
                .description("云原生通用专家。擅长 Docker、标准 Kubernetes 编排，不依赖特定云平台。")
                .build();

        TeamAgent cloudMarket = TeamAgent.of(chatModel)
                .name("Cloud_Services_Market")
                .protocol(TeamProtocols.MARKET_BASED)
                .agentAdd(aliyun, aws, generalist)
                .build();

        // 场景 A：具有强平台偏好的任务
        AgentSession sessionA = InMemoryAgentSession.of("aliyun_task");
        String resA = cloudMarket.call(Prompt.of("帮我把业务迁移到 ACK 上，并配置 SLB 负载均衡"), sessionA).getContent();

        TeamTrace traceA = cloudMarket.getTrace(sessionA);
        System.out.println("场景 A 指派给: " + traceA.getLastAgentName());
        Assertions.assertEquals("aliyun_expert", traceA.getLastAgentName(), "应精准指派给阿里云专家");

        // 场景 B：平台无关的底层架构任务
        AgentSession sessionB = InMemoryAgentSession.of("generic_task");
        String resB = cloudMarket.call(Prompt.of("写一个通用的 Kubernetes Deployment YAML，要求支持滚动更新"), sessionB).getContent();

        TeamTrace traceB = cloudMarket.getTrace(sessionB);
        System.out.println("场景 B 指派给: " + traceB.getLastAgentName());
        // 预期：调解器应发现 generalist 或 aliyun 都能做，但 generalist 描述更匹配“通用”
        Assertions.assertNotNull(traceB.getLastAgentName());

        // 场景 C：冲突与自愈测试（故意给一个 AWS 和 阿里云 混合的任务）
        AgentSession sessionC = InMemoryAgentSession.of("hybrid_task");
        String queryC = "我们需要一个多云方案：既要在 AWS 上跑 EKS，又要在阿里云上跑 OSS。";
        String resC = cloudMarket.call(Prompt.of(queryC), sessionC).getContent();

        System.out.println(">>> 多云任务最终结果预览: " + resC);
        // 在 Market 协议下，这类任务通常由 Mediator 选出一个“主专家”来领头，或者触发接力
        Assertions.assertTrue(resC.contains("EKS") || resC.contains("OSS"), "复杂任务执行失败");
    }

    @Test
    @DisplayName("生产级 Market：硬技能过滤（Skills Filtering）")
    public void testMarketWithHardSkillConstraints() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义两个描述极其相似，但 Skill 标签不同的专家
        Agent oldJavaDev = ReActAgent.of(chatModel).name("legacy_coder")
                .description("资深 Java 开发者，熟悉后端开发。")
                .profile(p -> p.skillAdd("JDK8").skillAdd("Struts2")) // 旧栈
                .build();

        Agent modernJavaDev = ReActAgent.of(chatModel).name("modern_coder")
                .description("资深 Java 开发者，熟悉后端开发。")
                .profile(p -> p.skillAdd("JDK21").skillAdd("SpringBoot3")) // 新栈
                .build();

        TeamAgent market = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.MARKET_BASED)
                .agentAdd(oldJavaDev, modernJavaDev)
                .build();

        // 场景：明确要求 JDK21 特性。
        // 验证：Mediator 能否识别 Profile 中的 Skill 差异，而不仅仅是看 Description 文本。
        AgentSession session = InMemoryAgentSession.of("skill_check");
        String query = "请使用 JDK21 的虚拟线程（Virtual Threads）特性写一个并发 Demo。";
        market.call(Prompt.of(query), session);

        TeamTrace trace = market.getTrace(session);
        System.out.println("硬技能匹配结果: " + trace.getLastAgentName());

        Assertions.assertEquals("modern_coder", trace.getLastAgentName(),
                "应根据 Profile 中的 Skill 标签精准指派到新一代开发者");
    }

    @Test
    @DisplayName("生产级 Market：多专家接力竞标（Sequence in Market）")
    public void testMarketMultiStepProcurement() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义市场中的不同工种
        Agent designer = ReActAgent.of(chatModel).name("UI_Designer")
                .description("负责输出页面原型和 CSS 样式。").build();

        Agent coder = ReActAgent.of(chatModel).name("Web_Coder")
                .description("负责将设计稿转换为 React 代码。").build();

        TeamAgent market = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.MARKET_BASED)
                .agentAdd(designer, coder)
                .maxTotalIterations(5)
                .build();

        // 场景：一个包含“先设计、后实现”两个阶段的任务
        AgentSession session = InMemoryAgentSession.of("procurement_flow");
        String query = "请帮我先设计一个深色模式的登录框 UI，然后用 React 代码实现它。";

        market.call(Prompt.of(query), session);

        TeamTrace trace = market.getTrace(session);
        List<String> executors = trace.getSteps().stream()
                .filter(TeamTrace.TeamStep::isAgent)
                .map(TeamTrace.TeamStep::getSource)
                .distinct().collect(Collectors.toList());

        System.out.println("市场协作链: " + executors);

        // 验证：市场调解器是否能在任务不同阶段选出对应的专家
        Assertions.assertTrue(executors.contains("UI_Designer"), "应首先雇佣设计师");
        Assertions.assertTrue(executors.contains("Web_Coder"), "设计完成后应雇佣开发者");
    }
}