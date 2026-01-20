package demo.ai.react;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;
import org.noear.solon.flow.FlowContext;

/**
 * ReAct 智能体基础演示
 * <p>
 * 展示了：
 * 1. 如何构建带工具库的 ReActAgent。
 * 2. 如何通过 AgentSession 进行对话上下文管理。
 * 3. 如何利用 Session 快照进行持久化与状态恢复。
 * </p>
 */
public class ReActAgentDemo {
    public static void main(String[] args) throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 构建智能体：注入天气工具，设置推理温度
        ReActAgent agent = ReActAgent.of(chatModel)
                .chatOptions(o -> o.temperature(0.1F)) // 低温度保证推理逻辑的一致性
                .toolAdd(new MethodToolProvider(new WeatherTools()))
                .build();

        System.out.println("--- Agent 开始工作 ---");

        // 2. 创建会话（Session 会自动初始化底层的 FlowContext 快照）
        AgentSession session1 = InMemoryAgentSession.of("session_demo_001");

        // 3. 发起调用：触发“思考-行动-观察”循环
        String question = "北京天气怎么样？我该穿什么衣服？";
        String response = agent.call(Prompt.of(question), session1).getContent();

        System.out.println("--- 最终答复 ---");
        System.out.println(response);

        System.out.println("\n--- 状态持久化与验证 ---");

        // 4. 获取执行轨迹：从 Session 中获取底层上下文进行序列化
        FlowContext context1 = session1.getSnapshot();
        String json = context1.toJson();
        System.out.println("Context JSON: " + json);

        // 获取原始轨迹记录（Key 约定：__ + agentName）
        ReActTrace record1 = context1.getAs("__" + agent.name());

        // 5. 模拟从持久化介质恢复会话
        FlowContext context2 = FlowContext.fromJson(json);
        // 通过已有的 context 恢复 Session
        AgentSession session2 = InMemoryAgentSession.of(context2);

        ReActTrace record2 = context2.getAs("__" + agent.name());

        // 6. 验证序列化前后的数据一致性
        Assertions.assertEquals(
                record1.getMessagesSize(),
                record2.getMessagesSize(), "消息记录数量不匹配，序列化失败");

        Assertions.assertEquals(
                context1.lastNodeId(),
                context2.lastNodeId(), "最后节点状态不匹配，序列化失败");

        Assertions.assertEquals(
                record1.getStepCount(),
                record2.getStepCount(), "推理步数不匹配，序列化失败");

        // 验证二次序列化结果
        String json2 = context2.toJson();
        Assertions.assertEquals(json, json2, "二次序列化 JSON 内容不一致");

        System.out.println("--- 验证通过：会话状态已成功恢复 ---");
    }

    /**
     * 天气工具类
     */
    public static class WeatherTools {
        @ToolMapping(name = "get_weather", description = "查询指定城市的天气状况")
        public String get_weather(@Param(description = "城市名称") String city) {
            if (city.contains("北京")) {
                return "北京现在晴朗，气温 10 摄氏度，风力 4 级。";
            } else {
                return city + "小雨，气温 18 摄氏度。";
            }
        }
    }
}