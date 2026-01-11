package features.ai.team.protocol;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
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

/**
 * Sequential 策略测试：严格顺序流水线模式
 */
public class TeamAgentSequentialTest {

    @Test
    public void testSequentialPipeline() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();



        // 1. 优化系统提示词：明确流水线坐标与输出契约

        // 第一步：强调结构化提取
        Agent extractor = ReActAgent.of(chatModel)
                .name("step1_extractor")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("流水线第一步：需求分析专家")
                        .instruction("### 任务说明\n" +
                                "1. 分析用户原始输入，识别核心业务对象和操作逻辑。\n" +
                                "2. **输出要求**：仅输出 JSON 格式的关键词集合，严禁包含任何自然语言描述。")
                        .build())
                .build();

        // 第二步：强调对前序结果的消费
        Agent converter = ReActAgent.of(chatModel)
                .name("step2_converter")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("流水线第二步：逻辑建模专家")
                        .instruction("### 任务说明\n" +
                                "1. 读取上游生成的 JSON 关键词。\n" +
                                "2. **处理逻辑**：将这些关键词转化为易于理解的业务伪代码（Pseudocode）。\n" +
                                "3. 不要进行额外的润色，保持逻辑纯粹。")
                        .build())
                .build();

        // 第三步：强调最终交付质量
        Agent polisher = ReActAgent.of(chatModel)
                .name("step3_polisher")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("流水线第三步：代码优化专家")
                        .instruction("### 任务说明\n" +
                                "1. 接收上游的业务伪代码。\n" +
                                "2. **优化重点**：补充必要的注释、优化代码缩进、提高可读性。\n" +
                                "3. **最终产出**：输出整理后的美化代码块。")
                        .build())
                .build();

        // 2. 构建顺序团队 (保持逻辑不变)
        TeamAgent team = TeamAgent.of(chatModel)
                .name("sequential_pipeline")
                .protocol(TeamProtocols.SEQUENTIAL)
                .agentAdd(extractor, converter, polisher)
                .build();

        System.out.println("--- Sequential Team Graph ---\n" + team.getGraph().toYaml());

        // 3. 执行任务
        AgentSession session = InMemoryAgentSession.of("session_seq_01");
        String query = "我要做一个简单的用户登录功能，包含用户名和密码验证。";
        String result = team.call(Prompt.of(query), session).getContent();

        System.out.println("=== 最终输出结果 ===\n" + result);

        // 4. 验证执行轨迹 (保持逻辑不变)
        TeamTrace trace = team.getTrace(session);
        Assertions.assertNotNull(trace);
        Assertions.assertTrue(trace.getStepCount() >= 3);

        Assertions.assertEquals("step1_extractor", trace.getSteps().get(0).getAgentName());
        Assertions.assertEquals("step2_converter", trace.getSteps().get(1).getAgentName());
        Assertions.assertEquals("step3_polisher", trace.getSteps().get(2).getAgentName());
    }

    @Test
    public void testSequentialRigidity() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 优化：赋予 Agent 明确的角色感，即使被迫先执行不擅长的任务也要守规矩
        Agent a = ReActAgent.of(chatModel).name("Agent_A")
                .description("数学预处理专家")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("加一处理器")
                        .instruction("你是流水线的起始点。无论收到什么，你的任务是确保数据传递给下一步。")
                        .build())
                .build();

        Agent b = ReActAgent.of(chatModel).name("Agent_B")
                .description("数学后处理专家")
                .systemPrompt(ReActSystemPrompt.builder()
                        .role("加二处理器")
                        .instruction("你只负责接收上游数据并执行加二操作。")
                        .build())
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.SEQUENTIAL)
                .agentAdd(a, b)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_seq_rigidity");
        team.call(Prompt.of("Agent_B 你好，请帮我加二。"), session);

        TeamTrace trace = team.getTrace(session);
        Assertions.assertEquals("Agent_A", trace.getSteps().get(0).getAgentName());
    }
}