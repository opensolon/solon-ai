package features.ai.react.generated;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.stream.Collectors;

/**
 * ReActAgent 边界条件和错误处理测试
 * <p>验证 Agent 在面对最大迭代限制、工具缺失、运行时异常以及非法输入时的处理机制。</p>
 */
public class ReActAgentBoundaryTest {

    @Test
    public void testMaxStepsReached() throws Throwable {
        // 测试：强制达到最大步数限制（防止 AI 进入无限思考循环）
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.of(chatModel)
                .defaultToolAdd(new LoopTools())
                .enableSuspension(false)
                .modelOptions(o -> o.temperature(0.0))
                .maxSteps(3) // 限制最多 3 步迭代
                .build();

        AgentSession session = InMemoryAgentSession.of("test_max_steps");
        String result = agent.call(Prompt.of("请通过不断思考，尽可能深入地分析这个问题"), session).getContent();

        // 验证结果是否包含迭代超限的错误提示
        Assertions.assertNotNull(result);
        System.out.println(result);
        String lowResult = result.toLowerCase();
        Assertions.assertTrue(lowResult.contains("maximum") ||
                        lowResult.contains("max steps") ||
                        lowResult.contains("迭代"),
                "未触发预期的步数限制。实际结果：" + result);

        // 验证轨迹记录
        ReActTrace trace = agent.getTrace(session);
        Assertions.assertNotNull(trace);
        Assertions.assertTrue(trace.getStepCount() >= 3, "追踪步骤应等于或大于最大限制值");
    }

    @Test
    public void testMaxStepsReached_B() throws Throwable {
        // 测试：强制达到最大步数限制（防止 AI 进入无限思考循环）
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.of(chatModel)
                .defaultToolAdd(new LoopTools())
                .modelOptions(o -> o.temperature(0.0))
                .maxSteps(3) // 限制最多 3 步迭代
                .build();

        AgentSession session = InMemoryAgentSession.of("test_max_steps");
        String result = agent.call(Prompt.of("请通过不断思考，尽可能深入地分析这个问题"), session).getContent();

        // 验证结果是否包含迭代超限的错误提示
        Assertions.assertNotNull(result);
        System.out.println(result);

        // 验证轨迹记录
        ReActTrace trace = agent.getTrace(session);
        Assertions.assertNotNull(trace);
        Assertions.assertTrue(trace.getStepCount() >= 3, "追踪步骤应等于或大于最大限制值");
    }

    @Test
    public void testToolNotFound() throws Throwable {
        // 测试：AI 试图调用一个未注册的工具
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.of(chatModel)
                .defaultToolAdd(new BasicTools())
                .build();

        AgentSession session = InMemoryAgentSession.of("test_tool_not_found");
        String result = agent.call(Prompt.of("请调用一个不存在的工具：non_existent_tool"), session).getContent();

        Assertions.assertNotNull(result);
        System.out.println("工具缺失时的 AI 反馈: " + result);
    }

    @Test
    public void testToolExceptionHandling() throws Throwable {
        // 测试：工具代码内部抛出运行时异常
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.of(chatModel)
                .defaultToolAdd(new ErrorTools())
                .build();

        AgentSession session = InMemoryAgentSession.of("test_tool_error");
        String result = agent.call(Prompt.of("调用那个会出错的工具"), session).getContent();

        Assertions.assertNotNull(result);

        ReActTrace trace = agent.getTrace(session);
        if (trace != null) {
            // 获取消息历史，验证错误是否被反馈给 LLM 或记录在轨迹中
            List<ChatMessage> messages = trace.getWorkingMemory().getMessages();
            String history = messages.stream()
                    .map(ChatMessage::toString)
                    .collect(Collectors.joining("\n"));

            System.out.println("工具异常后的执行历史:\n" + history);

            String historyStr = history.toLowerCase();
            Assertions.assertTrue(historyStr.contains("error") ||
                            historyStr.contains("异常") ||
                            historyStr.contains("runtime"),
                    "执行历史中应捕获并记录工具抛出的错误信息");
        }
    }

    @Test
    public void testEmptyPrompt() throws Throwable {
        // 测试：输入空指令时的处理
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.of(chatModel)
                .defaultToolAdd(new BasicTools())
                .build();

        AgentSession session = InMemoryAgentSession.of("test_empty");

        // 验证空提示词是否能得到某种形式的兜底响应
        String result = agent.call(Prompt.of(""), session).getContent();
        Assertions.assertNotNull(result);
        System.out.println("空输入结果: " + result);
    }

    @Test
    public void testNullSession() throws Throwable {
        // 测试：参数校验
        ChatModel chatModel = LlmUtil.getChatModel();
        ReActAgent agent = ReActAgent.of(chatModel).build();

        // 验证当 session 为空时抛出正确异常
        Assertions.assertThrows(NullPointerException.class, () -> agent.call(Prompt.of("test"), null));
    }

    // --- 辅助工具类 ---

    public static class LoopTools {
        @ToolMapping(description = "返回一个模糊结果，迫使 AI 继续思考")
        public String think_more() {
            return "分析尚不完整，请基于现有信息进行下一步推理。";
        }
    }

    public static class BasicTools {
        @ToolMapping(description = "基础回复工具")
        public String basic_tool() {
            return "OK";
        }
    }

    public static class ErrorTools {
        @ToolMapping(description = "必败工具：直接抛出异常")
        public String failing_tool() {
            throw new RuntimeException("CRITICAL_ERROR: 模拟硬件故障或数据库连接断开");
        }
    }
}