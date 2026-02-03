package features.ai.react_text.generated;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActStyle;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ReAct 协议完整性测试
 * <p>验证：Thought → Action → Observation → Thought 循环在不同场景下的正确性。</p>
 */
public class ReActAgentProtocolTest extends ReActAgentTestBase {

    /**
     * 测试完整的 ReAct 循环
     * <p>验证目标：面对复合问题，Agent 能通过工具获取外部信息并结合推理给出建议。</p>
     */
    @Test
    public void testFullReActCycle() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.of(chatModel)
                .style(ReActStyle.STRUCTURED_TEXT)
                .defaultToolAdd(new TestTools())
                .modelOptions(o -> o.temperature(0.0))
                .maxSteps(10)
                .build();

        // 使用 AgentSession 替代 FlowContext
        AgentSession session = InMemoryAgentSession.of("protocol_job_001");
        String prompt = "我需要查询北京的天气，然后根据天气建议是否带伞";

        String result = agent.call(Prompt.of(prompt), session).getContent();

        // 从 session 中获取轨迹分析
        ReActTrace trace = agent.getTrace(session);
        Assertions.assertNotNull(trace, "ReAct 轨迹不应为空");

        String history = formatReActHistory(trace);
        System.out.println("=== ReAct 执行轨迹 ===\n" + history);

        // 协议组件验证
        boolean hasThought = hasThoughtInHistory(trace);
        boolean hasAction = trace.getToolCallCount() > 0;
        boolean hasObservation = hasAction && trace.getStepCount() >= 2;

        // 验证 1：必须至少触发了一次工具调用
        // 1. 验证工具调用次数（框架已经帮你算好了）
        Assertions.assertTrue(trace.getToolCallCount() > 0, "应包含 Action 环节");
        // 2. 验证执行步数
        Assertions.assertTrue(trace.getStepCount() >= 2, "ReAct 路径必须包含【推理+执行】");
        // 3. 验证 Token 是否被记录（防止 Metrics 漏统计）
        Assertions.assertTrue(trace.getMetrics().getTotalTokens() > 0);
        // 验证 2：必须捕获到了工具的返回结果
        Assertions.assertTrue(hasAction, "应包含 Action 环节");
        Assertions.assertTrue(hasObservation, "ReAct 循环应包含 Observation 环节（观察结果）");

        // 提示：部分模型使用原生 ToolCall 可能不会显式输出 Thought 标签
        if (hasThought) {
            System.out.println("✓ 包含显式 Thought 推理逻辑");
        } else {
            System.out.println("⚠ 当前为原生 ToolCall 模式，推理逻辑可能隐藏在模型内部");
        }

        // 验证 3：最终结果的语义完整性
        Assertions.assertNotNull(result);
        boolean isRelevant = result.contains("北京") || result.contains("天气") || result.contains("伞");
        Assertions.assertTrue(isRelevant, "最终结果应包含北京天气及带伞建议的相关信息");

        System.out.println("最终回复内容: " + result);
    }

    /**
     * 测试无需工具的直接回答
     * <p>验证目标：对于简单问题，Agent 能够识别出无需调用工具，直接给出回复，避免浪费 Token。</p>
     */
    @Test
    public void testDirectAnswerWithoutTools() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.of(chatModel)
                .style(ReActStyle.STRUCTURED_TEXT)
                .defaultToolAdd(new TestTools())
                .build();

        AgentSession session = InMemoryAgentSession.of("direct_job");
        String result = agent.call(Prompt.of("你好，请问你是谁？"), session).getContent();

        Assertions.assertNotNull(result);

        ReActTrace trace = agent.getTrace(session);
        if (trace != null) {
            int toolCallCount = trace.getToolCallCount();
            Assertions.assertEquals(0, toolCallCount, "对于常规问候，不应误触发工具调用");
            System.out.println("轨迹步数: " + trace.getStepCount());
        }

        System.out.println("直接回答结果: " + result);
    }

    /**
     * 测试多工具连续调用（链式推理）
     * <p>验证目标：Agent 是否能根据任务拆解，有序地调用多个不同的工具来组合最终答案。</p>
     */
    @Test
    public void testMultiToolCalls() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        ReActAgent agent = ReActAgent.of(chatModel)
                .style(ReActStyle.STRUCTURED_TEXT)
                .defaultToolAdd(new SequentialTools())
                .modelOptions(o -> o.temperature(0.0))
                .maxSteps(8)
                .build();

        AgentSession session = InMemoryAgentSession.of("multi_tool_job");
        String prompt = "请先获取我的用户信息，然后查询余额，最后检查是否有优惠券";

        String result = agent.call(Prompt.of(prompt), session).getContent();

        ReActTrace trace = agent.getTrace(session);
        Assertions.assertNotNull(trace);

        // 获取并打印调用的工具序列
        List<String> calledTools = getCalledToolNames(trace);
        System.out.println("执行工具链: " + String.join(" -> ", calledTools));

        // 验证：至少调用了 2 个以上的工具来完成复合任务
        Assertions.assertTrue(calledTools.size() >= 2, "复合任务应触发多工具链式调用");

        // 验证结果内容覆盖率
        Assertions.assertTrue(result.contains("张三") || result.contains("用户"), "结果应包含用户信息");
        Assertions.assertTrue(result.contains("1000"), "结果应包含余额信息");
        Assertions.assertTrue(result.contains("优惠券"), "结果应包含优惠券信息");
    }


    private List<String> getCalledToolNames(ReActTrace trace) {
        List<String> tools = new ArrayList<>();
        if (trace == null) return tools;

        // 定义提取 Action JSON 中工具名的正则
        java.util.regex.Pattern actionPattern = java.util.regex.Pattern.compile(
                "Action:\\s*\\{.*?\"name\"\\s*:\\s*\"([^\"]+)\".*?\\}",
                java.util.regex.Pattern.DOTALL
        );

        for (ChatMessage msg : trace.getWorkingMemory().getMessages()) {
            // 1. 适配原生协议模式 (AssistantMessage 带有 ToolCalls 对象)
            if (msg instanceof AssistantMessage) {
                AssistantMessage am = (AssistantMessage) msg;
                if (Assert.isNotEmpty(am.getToolCalls())) {
                    am.getToolCalls().forEach(c -> tools.add(c.name()));
                }

                // 2. 适配文本 ReAct 模式 (从 AssistantMessage 的 content 文本中正则提取)
                else if (am.getContent() != null) {
                    java.util.regex.Matcher matcher = actionPattern.matcher(am.getContent());
                    if (matcher.find()) {
                        tools.add(matcher.group(1));
                    }
                }
            }

            // 3. 适配标准 ToolMessage 模式
            if (msg instanceof ToolMessage) {
                ToolMessage tm = (ToolMessage) msg;
                if (Assert.isNotEmpty(tm.getName())) {
                    tools.add(tm.getName());
                }
            }
        }

        // 去重并返回
        return tools.stream().distinct().collect(Collectors.toList());
    }

    private boolean hasThoughtInHistory(ReActTrace trace) {
        if (trace == null) return false;
        String history = formatReActHistory(trace);
        if (history.contains("Thought")) return true;

        for (ChatMessage msg : trace.getWorkingMemory().getMessages()) {
            if (msg instanceof AssistantMessage) {
                String content = msg.getContent();
                if (content != null && content.contains("Thought")) return true;
            }
        }
        return false;
    }


    // --- 业务工具类 ---

    public static class TestTools {
        @ToolMapping(description = "实时查询指定城市的天气预报")
        public String get_weather(@Param(description = "城市名称，如：北京") String city) {
            if ("北京".equals(city)) {
                return "【气象局数据】北京今日多云，傍晚有阵雨，气温 15°C-25°C，建议随身携带雨具。";
            }
            return city + " 暂无气象监测覆盖。";
        }
    }

    public static class SequentialTools {
        @ToolMapping(description = "获取当前登录用户的核心资料")
        public String get_user_info() {
            return "{\"uid\": 123456, \"name\": \"张三\", \"level\": \"VIP\"}";
        }

        @ToolMapping(description = "查询用户钱包账户余额")
        public String get_balance() {
            return "{\"currency\": \"CNY\", \"balance\": 1000.00}";
        }

        @ToolMapping(description = "检索用户账户下的有效优惠券列表")
        public String check_coupons() {
            return "{\"count\": 3, \"items\": [\"满100减10\", \"免邮券\"]}";
        }
    }
}