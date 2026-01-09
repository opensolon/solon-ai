package features.ai.session;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.SystemMessage;

import java.util.List;
import java.util.ArrayList;
import java.util.Collection;

@DisplayName("会话存储存储测试")
public class InMemorySessionTest {

    @Test
    @DisplayName("基础消息归档与提取测试")
    public void testBasicStorage() {
        AgentSession session = InMemoryAgentSession.of("test_s1");
        String agentName = "Worker";

        session.addHistoryMessage(agentName, ChatMessage.ofUser("Hello"));
        session.addHistoryMessage(agentName, ChatMessage.ofAssistant("Hi there"));

        Collection<ChatMessage> history = session.getHistoryMessages(agentName, 10);
        Assertions.assertEquals(2, history.size());

        // 验证提取最近条数逻辑
        Collection<ChatMessage> lastOne = session.getHistoryMessages(agentName, 1);
        Assertions.assertEquals(1, lastOne.size());
        Assertions.assertTrue(lastOne.iterator().next().getContent().contains("Hi"));
    }

    @Test
    @DisplayName("消息自动清理(MaxMessages)逻辑测试")
    public void testMessageEviction() {
        // 设置最大容量为 2
        AgentSession session = InMemoryAgentSession.of("test_s2", 2);
        String agentName = "Limiter";

        session.addHistoryMessage(agentName, ChatMessage.ofUser("Msg 1"));
        session.addHistoryMessage(agentName, ChatMessage.ofUser("Msg 2"));
        session.addHistoryMessage(agentName, ChatMessage.ofUser("Msg 3")); // 触发清理

        Collection<ChatMessage> history = session.getHistoryMessages(agentName, 10);
        // 应该是 Msg 2 和 Msg 3 被保留
        Assertions.assertEquals(2, history.size());
        Assertions.assertFalse(history.stream().anyMatch(m -> m.getContent().equals("Msg 1")));
    }

    @Test
    @DisplayName("消息自动清理(MaxMessages)逻辑修复测试")
    public void testMessageEvictionFixed() {
        // 1. 设置最大容量为 2
        int max = 2;
        AgentSession session = InMemoryAgentSession.of("test_s2", max);
        String agentName = "Limiter";

        // 2. 添加 3 条 User 消息
        session.addHistoryMessage(agentName, ChatMessage.ofUser("Msg 1"));
        session.addHistoryMessage(agentName, ChatMessage.ofUser("Msg 2"));
        session.addHistoryMessage(agentName, ChatMessage.ofUser("Msg 3"));

        Collection<ChatMessage> history = session.getHistoryMessages(agentName, 10);

        // 断言：由于 max=2，最新的 Msg 2 和 Msg 3 应该被保留
        Assertions.assertEquals(2, history.size(), "应该保留最新的 2 条消息");

        // 验证顺序（Msg 1 应该被移除）
        List<ChatMessage> list = new ArrayList<>(history);
        Assertions.assertEquals("Msg 2", list.get(0).getContent());
        Assertions.assertEquals("Msg 3", list.get(1).getContent());
    }
}