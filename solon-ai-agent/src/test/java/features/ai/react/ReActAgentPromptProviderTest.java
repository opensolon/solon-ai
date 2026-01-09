package features.ai.react;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActPromptProvider;
import org.noear.solon.ai.agent.react.ReActPromptProviderCn;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;

/**
 * ReActAgent 提示词提供者（PromptProvider）测试
 * <p>验证如何通过自定义 Prompt 模板来改变 Agent 的推理风格、语言偏好和输出格式约束。</p>
 */
public class ReActAgentPromptProviderTest {

    /**
     * 测试：自定义提示词提供者
     * <p>目标：强制模型进入“数学专家”角色，并遵循特定的思考步骤。</p>
     */
    @Test
    public void testCustomPromptProvider() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 定义自定义 Prompt 模板
        // 通过 trace 可以获取当前工具列表、配置信息等动态数据
        ReActPromptProvider customProvider = trace -> {
            return "你是专门处理数学问题的专家。\n" +
                    "当前可用工具数量: " + trace.getConfig().getTools().size() + "\n" +
                    "请严格按照以下格式进行推理:\n" +
                    "分析: [描述解题思路]\n" +
                    "计算: [调用工具计算]\n" +
                    "验证: [核对结果正确性]\n" +
                    "最终答案请以 '答案:' 开头。";
        };

        // 2. 构建 Agent 并注入自定义提供者
        ReActAgent agent = ReActAgent.of(chatModel)
                .addTool(new MethodToolProvider(new MathTools()))
                .systemPrompt(customProvider)
                .chatOptions(o -> o.temperature(0.0F)) // 严格执行指令
                .build();

        // 3. 使用 AgentSession 替代 FlowContext
        AgentSession session = InMemoryAgentSession.of("math_expert_job");
        String result = agent.call(Prompt.of("计算 25 + 37"), session).getContent();

        Assertions.assertNotNull(result);
        System.out.println("【专家模式结果】:\n" + result);

        // 4. 验证是否遵循了自定义格式
        boolean hasCustomFormat = result.contains("分析") || result.contains("答案:");
        Assertions.assertTrue(hasCustomFormat, "Agent 应该遵循自定义 Prompt 要求的输出格式");
    }

    /**
     * 测试：内置中文提示词提供者
     * <p>使用框架提供的 ReActPromptProviderCn，增强 Agent 在中文语境下的工具调用能力。</p>
     */
    @Test
    public void testChinesePromptProvider() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.of(chatModel)
                .addTool(new MethodToolProvider(new ChineseTools()))
                .systemPrompt(ReActPromptProviderCn.getInstance()) // 使用单例中文增强
                .chatOptions(o -> o.temperature(0.0F))
                .build();

        AgentSession session = InMemoryAgentSession.of("weather_job_cn");
        String result = agent.call(Prompt.of("帮我查下北京的天气怎么样？"), session).getContent();

        Assertions.assertNotNull(result);
        System.out.println("【中文模式结果】:\n" + result);

        // 验证中文理解与输出
        Assertions.assertTrue(result.contains("北京") || result.contains("天气"),
                "Agent 应准确识别中文参数并返回包含关键词的结果");
    }

    /**
     * 测试：空系统提示词（边界情况）
     * <p>验证当不提供任何引导指令时，Agent 是否能基于默认底层逻辑运行（取决于 LLM 的本能）。</p>
     */
    @Test
    public void testEmptySystemPrompt() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 提供一个无任何指令的提供者
        ReActPromptProvider emptyProvider = trace -> "";

        ReActAgent agent = ReActAgent.of(chatModel)
                .addTool(new MethodToolProvider(new BasicTools()))
                .systemPrompt(emptyProvider)
                .build();

        AgentSession session = InMemoryAgentSession.of("empty_prompt_job");
        String result = agent.call(Prompt.of("你好"), session).getContent();

        Assertions.assertNotNull(result);
        System.out.println("【空指令结果】: " + result);
    }

    // --- 工具集定义 ---

    public static class MathTools {
        @ToolMapping(description = "执行加法运算")
        public double add(@Param(description = "加数 a") double a,
                          @Param(description = "加数 b") double b) {
            return a + b;
        }
    }

    public static class ChineseTools {
        @ToolMapping(description = "查询指定城市的天气状况")
        public String get_weather(@Param(description = "城市名称，如：北京") String city) {
            return city + " 今天气温 20°C，多云转晴，非常适合户外活动。";
        }
    }

    public static class BasicTools {
        @ToolMapping(description = "基础回复工具")
        public String basic() {
            return "基础工具响应成功";
        }
    }
}