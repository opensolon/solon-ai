package features.ai.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.redisx.RedisClient;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.session.RedisAgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;

import java.util.Collection;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RedisAgentSession 历史消息功能单元测试
 */
@DisplayName("Agent Session 历史消息测试")
class RedisAgentSessionTest {

    private RedisAgentSession session;
    private final String AGENT_A = "Expert_A";
    private final String AGENT_B = "Expert_B";

    @BeforeEach
    void setUp() {
        // 每个测试用例开始前初始化一个新的会话
        Properties properties = new Properties();
        properties.put("server", "redis://localhost:6379");
        properties.put("password", "123456");
        properties.put("db", "1");

        RedisClient redisClient = new RedisClient(properties);
        session = new RedisAgentSession("test_session", redisClient);
    }

    @Test
    @DisplayName("基础消息归档与读取测试")
    void testAddAndGetHistoryMessages() {
        session.clear(AGENT_A);
        session.clear(AGENT_B);

        ChatMessage msg1 = ChatMessage.ofUser("Hello");
        ChatMessage msg2 = ChatMessage.ofAssistant("Hi there");

        session.addHistoryMessage(AGENT_A, msg1);
        session.addHistoryMessage(AGENT_A, msg2);

        List<ChatMessage> history = (List<ChatMessage>)session.getHistoryMessages(AGENT_A, 10);

        assertEquals(2, history.size(), "应该存储了2条消息");
        assertTrue(history.get(0).getContent().contains(msg1.getContent()));
        assertTrue(history.get(1).getContent().contains(msg2.getContent()));
    }

    @Test
    @DisplayName("多智能体消息隔离性测试")
    void testAgentIsolation() {
        session.clear(AGENT_A);
        session.clear(AGENT_B);

        session.addHistoryMessage(AGENT_A, ChatMessage.ofUser("Msg to A"));
        session.addHistoryMessage(AGENT_B, ChatMessage.ofUser("Msg to B"));

        assertEquals(1, session.getHistoryMessages(AGENT_A, 10).size(), "A的消息数量不正确");
        assertEquals(1, session.getHistoryMessages(AGENT_B, 10).size(), "B的消息数量不正确");

        String contentA = session.getHistoryMessages(AGENT_A, 1).iterator().next().getContent();
        assertEquals("Msg to A", contentA, "读取到的消息内容与智能体不匹配");
    }

    @Test
    @DisplayName("最近消息截断测试 (Last N)")
    void testGetLastMessages() {
        session.clear(AGENT_A);
        session.clear(AGENT_B);

        // 存入 5 条消息
        for (int i = 1; i <= 5; i++) {
            session.addHistoryMessage(AGENT_A, ChatMessage.ofUser("Msg " + i));
        }

        // 仅获取最近的 3 条
        Collection<ChatMessage> lastThree = session.getHistoryMessages(AGENT_A, 3);

        assertEquals(3, lastThree.size(), "返回的消息数量应该是3条");

        // 验证顺序（subList 保持原始顺序，所以第一条应该是 Msg 3）
        ChatMessage firstOfLast = lastThree.iterator().next();
        assertEquals("Msg 3", firstOfLast.getContent(), "获取到的消息起点不正确");
    }

    @Test
    @DisplayName("空数据及越界处理测试")
    void testEmptyAndOverflow() {
        session.clear(AGENT_A);
        session.clear(AGENT_B);

        // 1. 获取不存在的 Agent 消息
        Collection<ChatMessage> emptyHistory = session.getHistoryMessages("Unknown", 10);
        assertNotNull(emptyHistory);
        assertTrue(emptyHistory.isEmpty(), "不存在的智能体应返回空列表而非 null");

        // 2. 存入 1 条，尝试获取 100 条
        session.addHistoryMessage(AGENT_A, ChatMessage.ofUser("Only one"));
        Collection<ChatMessage> history = session.getHistoryMessages(AGENT_A, 100);
        assertEquals(1, history.size(), "当请求数量大于存储数量时，应返回所有可用消息");
    }
}