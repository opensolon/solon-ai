package features.ai.team.protocol;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
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

/**
 * Sequential 策略测试：严格顺序流水线模式
 * <p>
 * 验证目标：
 * 1. 验证任务是否严格按照 Agent 注册顺序执行（A -> B -> C）。
 * 2. 验证后序 Agent 是否能接收到前序 Agent 的处理结果。
 * 3. 验证即使需求更偏向后面的 Agent，系统也不会产生跳跃。
 * </p>
 */
public class TeamAgentSequentialTest {

    @Test
    public void testSequentialPipeline() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义三个流水线环节
        // 第一步：需求提取
        Agent extractor = ReActAgent.of(chatModel)
                .name("step1_extractor")
                .systemPrompt(c -> "你是第一步：提取用户需求中的关键词，以 JSON 格式输出。")
                .build();

        // 第二步：逻辑转换
        Agent converter = ReActAgent.of(chatModel)
                .name("step2_converter")
                .systemPrompt(c -> "你是第二步：根据第一步提供的 JSON 关键词，编写一段伪代码。")
                .build();

        // 第三步：代码润色
        Agent polisher = ReActAgent.of(chatModel)
                .name("step3_polisher")
                .systemPrompt(c -> "你是第三步：对第二步的伪代码进行注释补充和格式优化。")
                .build();

        // 2. 使用 SEQUENTIAL 协议构建团队（顺序：extractor -> converter -> polisher）
        TeamAgent team = TeamAgent.of(chatModel)
                .name("sequential_pipeline")
                .protocol(TeamProtocols.SEQUENTIAL)
                .addAgent(extractor)
                .addAgent(converter)
                .addAgent(polisher)
                .build();

        // 打印图结构：应为单一线性链路
        System.out.println("--- Sequential Team Graph ---\n" + team.getGraph().toYaml());

        // 3. 执行任务
        AgentSession session = InMemoryAgentSession.of("session_seq_01");
        String query = "我要做一个简单的用户登录功能，包含用户名和密码验证。";
        String result = team.call(Prompt.of(query), session).getContent();

        System.out.println("=== 最终输出结果 ===\n" + result);

        // 4. 验证执行轨迹
        TeamTrace trace = team.getTrace(session);
        Assertions.assertNotNull(trace);

        // 关键断言：执行步数至少为 3（三个 Agent 各走一遍）
        Assertions.assertTrue(trace.getStepCount() >= 3);

        // 验证执行顺序
        System.out.println("执行链条顺序检查:");
        for (int i = 0; i < trace.getSteps().size(); i++) {
            System.out.println("第 " + (i + 1) + " 步: " + trace.getSteps().get(i).getAgentName());
        }

        String firstWorker = trace.getSteps().get(0).getAgentName();
        String secondWorker = trace.getSteps().get(1).getAgentName();
        String thirdWorker = trace.getSteps().get(2).getAgentName();

        Assertions.assertEquals("step1_extractor", firstWorker);
        Assertions.assertEquals("step2_converter", secondWorker);
        Assertions.assertEquals("step3_polisher", thirdWorker);

        System.out.println("顺序验证通过！");
    }

    /**
     * 测试：Sequential 的强约束性
     * 场景：即使任务内容非常像第三步的内容，它也必须先经过第一、二步。
     */
    @Test
    public void testSequentialRigidity() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        Agent a = ReActAgent.of(chatModel).name("Agent_A").description("只负责加一").build();
        Agent b = ReActAgent.of(chatModel).name("Agent_B").description("只负责加二").build();

        TeamAgent team = TeamAgent.of(chatModel)
                .protocol(TeamProtocols.SEQUENTIAL)
                .addAgent(a).addAgent(b)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_seq_rigidity");
        // 即使询问 Agent_B 擅长的事，在 SEQUENTIAL 下也必须 A 先说话
        team.call(Prompt.of("Agent_B 你好，请帮我加二。"), session);

        TeamTrace trace = team.getTrace(session);
        Assertions.assertEquals("Agent_A", trace.getSteps().get(0).getAgentName(), "SEQUENTIAL 模式下必须由第一个定义的 Agent 启动");
    }
}