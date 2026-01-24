package features.ai.memory;

import demo.ai.llm.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.util.Collection;

/**
 * TeamAgent 与 InMemoryAgentSession 集成测试
 * 验证：1. 跨轮次记忆 2. 会话隔离 3. 自动清理逻辑 4. 过滤系统消息
 */
public class TeamAgentMemoryTest {

    /**
     * 测试 1：基础多轮对话记忆
     * 验证 TeamAgent 作为一个整体，能否通过 Session 记住之前的关键信息
     */
    @Test
    public void testTeamMultiTurnMemory() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 构建团队：由主管 + 一个事实核对专家组成
        ReActAgent worker = ReActAgent.of(chatModel)
                .name("fact_checker")
                .description("负责记录和查询用户提供的事实信息")
                .build();

        TeamAgent team = TeamAgent.of(chatModel)
                .name("support_team")
                .agentAdd(worker)
                .build();

        AgentSession session = InMemoryAgentSession.of("session_001");

        // 第一轮：存入事实
        String p1 = "我的秘密口令是 'Solon-AI-666'，请确认收到。";
        team.call(Prompt.of(p1), session);

        // 第二轮：提取事实
        String p2 = "刚才我告诉你的秘密口令是什么？";
        AssistantMessage resp = team.call(Prompt.of(p2), session);
        String content = resp.getContent();

        System.out.println("AI 记忆提取结果: " + content);

        // 断言：AI 能够从 Session 历史中找回第一轮的口令
        Assertions.assertTrue(content.contains("Solon-AI-666"), "AI 应该记得第一轮提供的口令");

        // 验证历史消息数量：User(2) + Assistant(2) = 4
        Collection<ChatMessage> history = session.getLatestMessages(10);
        Assertions.assertEquals(4, history.size(), "会话历史条数应为 4 条");
    }

    /**
     * 测试 2：会话隔离性
     * 验证不同的 Session ID 是否互不干扰
     */
    @Test
    public void testSessionIsolation() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();
        TeamAgent team = TeamAgent.of(chatModel).name("iso_team").agentAdd(
                ReActAgent.of(chatModel).name("helper").description("助手").build()
        ).build();

        // Session A 叫 Alice
        AgentSession sessionA = InMemoryAgentSession.of("SA");
        team.call(Prompt.of("我叫 Alice"), sessionA);

        // Session B 叫 Bob
        AgentSession sessionB = InMemoryAgentSession.of("SB");
        team.call(Prompt.of("我叫 Bob"), sessionB);

        // 验证 A 的记忆
        String respA = team.call(Prompt.of("我叫什么名字？"), sessionA).getContent();
        Assertions.assertTrue(respA.contains("Alice") && !respA.contains("Bob"), "SA 应该只记得 Alice");

        // 验证 B 的记忆
        String respB = team.call(Prompt.of("我叫什么名字？"), sessionB).getContent();
        Assertions.assertTrue(respB.contains("Bob") && !respB.contains("Alice"), "SB 应该只记得 Bob");
    }

    /**
     * 测试 3：内存清理与窗口滚动
     * 验证 maxAgentMessages 限制下，旧记忆是否被正确“挤出”，且不删光最新消息
     */
    @Test
    public void testMemoryEviction() {
        // 限制只能存 2 条消息（1 组对话）
        AgentSession session = InMemoryAgentSession.of("limit_session", 2);
        String agentName = "test_agent";

        session.addMessage(ChatMessage.ofUser("第一轮问题"));
        session.addMessage(ChatMessage.ofAssistant("第一轮回答"));

        // 此时添加第三条，应该触发清理，删掉最旧的 "第一轮问题"
        session.addMessage(ChatMessage.ofUser("第二轮问题"));

        Collection<ChatMessage> history = session.getLatestMessages(10);

        // 断言：大小应维持在 2
        Assertions.assertEquals(2, history.size(), "记忆窗口应保持为 2 条");
        // 断言：第一条消息应该是 "第一轮回答"
        Assertions.assertEquals("第一轮回答", history.iterator().next().getContent());
    }

    /**
     * 测试 4：系统消息过滤
     * 验证 SystemMessage 是否被 InMemoryAgentSession 忽略（不占用记忆空间）
     */
    @Test
    public void testIgnoreSystemMessage() {
        AgentSession session = InMemoryAgentSession.of("sys_ignore_test", 10);
        String agentName = "test_agent";

        // 存入系统消息
        session.addMessage(ChatMessage.ofSystem("你是一个翻译官"));
        // 存入用户消息
        session.addMessage(ChatMessage.ofUser("你好"));

        Collection<ChatMessage> history = session.getLatestMessages(10);

        // 断言：历史中只应该有用户消息，系统消息不应被持久化到 Session 历史
        Assertions.assertEquals(1, history.size(), "Session 应忽略 SystemMessage");
        Assertions.assertTrue(history.iterator().next() instanceof org.noear.solon.ai.chat.message.UserMessage);
    }
}