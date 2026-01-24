package features.ai.session;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.flow.FlowContext;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * InMemoryAgentSession 单元测试 (覆盖率优化版)
 */
public class InMemoryAgentSessionTest {

    @Test
    public void testConstructors() {
        // 测试 of() -> 默认 "tmp"
        InMemoryAgentSession s1 = InMemoryAgentSession.of();
        Assertions.assertEquals("tmp", s1.getSessionId());

        // 测试 sessionId 为 null 的情况
        InMemoryAgentSession s2 = new InMemoryAgentSession("tmp");
        Assertions.assertEquals("tmp", s2.getSessionId());

        InMemoryAgentSession s3 = new InMemoryAgentSession(null, 10);
        Assertions.assertEquals("tmp", s3.getSessionId());
        Assertions.assertEquals(10, s3.getMaxMessages());

        // 测试 of(sessionId, maxMessages)
        InMemoryAgentSession s4 = InMemoryAgentSession.of("s4", 5);
        Assertions.assertEquals("s4", s4.getSessionId());
        Assertions.assertEquals(5, s4.getMaxMessages());
    }

    @Test
    public void testAddMessage_EdgeCases() {
        InMemoryAgentSession session = InMemoryAgentSession.of("edge");

        // 1. 测试传入 null 或空集合 (覆盖 Utils.isNotEmpty 分支)
        session.addMessage((List<ChatMessage>) null);
        session.addMessage(Collections.emptyList());
        Assertions.assertTrue(session.isEmpty());

        // 2. 测试仅传入 SystemMessage (由于被过滤，size 应仍为 0)
        session.addMessage(Arrays.asList(ChatMessage.ofSystem("sys")));
        Assertions.assertTrue(session.isEmpty());
    }

    @Test
    public void testAddMessage_FilterSystem() {
        InMemoryAgentSession session = InMemoryAgentSession.of("test_filter");

        // 构造一组消息：包含 User, Assistant 和 System
        List<ChatMessage> incoming = Arrays.asList(
                ChatMessage.ofSystem("system prompt"),
                ChatMessage.ofUser("hello"),
                ChatMessage.ofAssistant("hi")
        );

        session.addMessage(incoming);

        // 验证：System 消息应被过滤，不进入 messages 列表
        List<ChatMessage> stored = session.getMessages();
        Assertions.assertEquals(2, stored.size());
        for (ChatMessage m : stored) {
            Assertions.assertNotEquals("system", m.getRole().name().toLowerCase());
        }
    }

    @Test
    public void testMaxMessages_Eviction() {
        // 设置最大容量为 2
        int max = 2;
        InMemoryAgentSession session = InMemoryAgentSession.of("test_evict", max);

        // 1. 测试 maxMessages > 0 但未达到上限
        session.addMessage(ChatMessage.ofUser("msg 1"));
        Assertions.assertEquals(1, session.getMessages().size());

        // 2. 连续追加达到上限并触发淘汰
        session.addMessage(ChatMessage.ofAssistant("ans 1"));
        session.addMessage(ChatMessage.ofUser("msg 2"));

        // 验证淘汰逻辑
        Assertions.assertEquals(max, session.getMessages().size());
        Assertions.assertEquals("ans 1", session.getMessages().get(0).getContent());
        Assertions.assertEquals("msg 2", session.getMessages().get(1).getContent());

        // 3. 测试 maxMessages 为 0 的情况（不限制）
        InMemoryAgentSession sNoLimit = InMemoryAgentSession.of("no_limit", 0);
        sNoLimit.addMessage(Arrays.asList(ChatMessage.ofUser("1"), ChatMessage.ofUser("2"), ChatMessage.ofUser("3")));
        Assertions.assertEquals(3, sNoLimit.getMessages().size());
    }

    @Test
    public void testSnapshotAndContext() {
        // 测试从 FlowContext 构造
        FlowContext context = FlowContext.of("ctx_123");
        InMemoryAgentSession session = InMemoryAgentSession.of(context);
        Assertions.assertEquals("ctx_123", session.getSessionId());
        Assertions.assertEquals(context, session.getSnapshot());
        Assertions.assertEquals(session, context.get(Agent.KEY_SESSION));

        // 测试更新快照
        FlowContext newContext = FlowContext.of("new");
        session.updateSnapshot(newContext);
        Assertions.assertEquals(newContext, session.getSnapshot());
    }

    @Test
    public void testBaseMethods() {
        InMemoryAgentSession session = InMemoryAgentSession.of("base", 10);
        session.addMessage(ChatMessage.ofUser("a"));
        session.addMessage(ChatMessage.ofUser("b"));
        session.addMessage(ChatMessage.ofUser("test"));

        // 测试 isEmpty
        Assertions.assertFalse(session.isEmpty());

        // 测试 getLatestMessages (继承自 ChatSession/InMemoryChatSession)
        List<ChatMessage> latest = session.getLatestMessages(1);
        Assertions.assertEquals(1, latest.size());
        Assertions.assertEquals("test", latest.get(0).getContent());

        // 测试 clear
        session.clear();
        Assertions.assertTrue(session.isEmpty());
    }
}