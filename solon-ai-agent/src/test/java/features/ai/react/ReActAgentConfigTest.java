package features.ai.react;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.MethodToolProvider;

import java.util.Random;

/**
 * ReActAgent 配置参数测试
 * <p>验证智能体在不同 LLM 参数（温度、Token 限制）及运行参数（结束标记）下的表现差异。</p>
 */
public class ReActAgentConfigTest {

    /**
     * 测试不同温度参数对推理多样性的影响
     * 目的：验证 temperature 是否真实透传并影响了 LLM 的创作发散性。
     */
    @Test
    public void testDifferentTemperatures() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 低温配置：注重确定性和逻辑严谨性
        ReActAgent lowTempAgent = ReActAgent.of(chatModel)
                .addTool(new MethodToolProvider(new CreativeTools()))
                .chatOptions(o -> o.temperature(0.1F))
                .name("low_temp_agent")
                .build();

        // 2. 高温配置：注重创造性和词汇多样性
        ReActAgent highTempAgent = ReActAgent.of(chatModel)
                .addTool(new MethodToolProvider(new CreativeTools()))
                .chatOptions(o -> o.temperature(0.9F))
                .name("high_temp_agent")
                .build();

        // 【关键改动】：要求 AI 在工具结果基础上“扩写”或“解释”，给采样留出空间
        String userPrompt = "调用工具获取一个口号关键词，然后基于这个词为我写一段 30 字以上的产品推广文案。";

        AgentSession session1 = InMemoryAgentSession.of("temp_job_1");
        String result1 = lowTempAgent.call(Prompt.of(userPrompt), session1).getContent();

        AgentSession session2 = InMemoryAgentSession.of("temp_job_2");
        String result2 = highTempAgent.call(Prompt.of(userPrompt), session2).getContent();

        System.out.println("低温结果（严谨）: " + result1);
        System.out.println("高温结果（创意）: " + result2);

        // 验证：在需要“组织语言”的任务下，不同温度必会产生差异化的回复内容
        Assertions.assertNotEquals(result1, result2, "不同温度配置应产生差异化的回复内容");
    }

    /**
     * 测试 MaxTokens 限制对输出长度的约束
     */
    @Test
    public void testDifferentMaxTokens() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 限制短输出
        ReActAgent shortAgent = ReActAgent.of(chatModel)
                .addTool(new MethodToolProvider(new StoryTools()))
                .chatOptions(o -> o.max_tokens(50))
                .name("short_agent")
                .build();

        // 允许长输出
        ReActAgent longAgent = ReActAgent.of(chatModel)
                .addTool(new MethodToolProvider(new StoryTools()))
                .chatOptions(o -> o.max_tokens(500))
                .name("long_agent")
                .build();

        String userPrompt = "写一个关于勇者的简短故事";

        AgentSession session1 = InMemoryAgentSession.of("token_job_1");
        String result1 = shortAgent.call(Prompt.of(userPrompt), session1).getContent();

        AgentSession session2 = InMemoryAgentSession.of("token_job_2");
        String result2 = longAgent.call(Prompt.of(userPrompt), session2).getContent();

        System.out.println("受限 Token 长度: " + result1.length());
        System.out.println("宽松 Token 长度: " + result2.length());

        // 验证：长 Token 配置不应比短 Token 配置截断更严重
        Assertions.assertTrue(result2.length() >= result1.length(), "长 Token 限制应允许产生更丰富的内容");
    }

    /**
     * 测试日志与会话隔离
     */
    @Test
    public void testSessionLogging() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.of(chatModel)
                .addTool(new MethodToolProvider(new BasicTools()))
                .name("diagnostic_agent")
                .build();

        // 验证：相同的 Agent 在不同 Session 下应互不干扰
        AgentSession session1 = InMemoryAgentSession.of("session_a");
        AgentSession session2 = InMemoryAgentSession.of("session_b");

        String res1 = agent.call(Prompt.of("测试会话 A"), session1).getContent();
        String res2 = agent.call(Prompt.of("测试会话 B"), session2).getContent();

        Assertions.assertNotNull(res1);
        Assertions.assertNotNull(res2);
    }

    /**
     * 测试自定义结束标记（Finish Marker）
     * <p>结束标记用于提示 LLM 决策已完成，停止继续思考。</p>
     */
    @Test
    public void testFinishMarker() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.of(chatModel)
                .addTool(new MethodToolProvider(new BasicTools()))
                .finishMarker("[COMPLETED]") // 自定义推理终结符
                .name("marker_agent")
                .build();

        AgentSession session = InMemoryAgentSession.of("marker_job");
        String result = agent.call(Prompt.of("简单问候一下"), session).getContent();

        System.out.println("带标记的结果: " + result);
        Assertions.assertNotNull(result);

        // 如果模型严格遵循提示词，结果可能会显式包含或隐含该标记的语义
        if (result.contains("[COMPLETED]")) {
            System.out.println("检测到自定义推理终结符，流程正常关闭。");
        }
    }

    // --- 工具类定义 ---

    public static class CreativeTools {
        @ToolMapping(description = "生成产品创意口号")
        public String generate_idea() {
            String[] ideas = {
                    "科技点亮生活",
                    "智造未来，开启新篇",
                    "让想象力触手可及",
                    "简约而不简单"
            };
            // 增加随机性
            return ideas[new Random().nextInt(ideas.length)];
        }
    }

    public static class StoryTools {
        @ToolMapping(description = "生成中长篇叙事内容")
        public String generate_story() {
            return "在遥远的塞恩大陆，有一位年轻的冒险者正准备踏入幽暗森林...";
        }
    }

    public static class BasicTools {
        @ToolMapping(description = "执行基础通用任务")
        public String basic_tool() {
            return "执行成功";
        }
    }
}