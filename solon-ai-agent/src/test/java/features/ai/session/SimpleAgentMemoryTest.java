package features.ai.session;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.util.Collection;

/**
 * ReActAgent 与 InMemoryAgentSession 真实记忆集成测试
 * 验证：1. 记忆准确性 2. 避免复读（逻辑不重复）
 */
public class SimpleAgentMemoryTest {

    @Test
    public void testMultiTurnMemory() throws Throwable {
        // 1. 初始化模型与智能体
        ChatModel chatModel = LlmUtil.getChatModel();

        SimpleAgent agent = SimpleAgent.of(chatModel)
                .name("MemoryAgent")
                .description("一个有记忆的推理助手")
                .historyWindowSize(10) // 启用历史窗口
                .build();

        // 2. 创建模拟会话
        AgentSession session = InMemoryAgentSession.of("session_memory_test");

        // --- 第一轮：输入事实 ---
        String p1 = "你好，我的名字叫 noear，我最喜欢的颜色是蓝色。请确认你收到了。";
        System.out.println("User R1: " + p1);

        AssistantMessage resp1 = agent.call(Prompt.of(p1), session);
        String content1 = resp1.getContent();
        System.out.println("AI R1: " + content1);

        // 断言：第一轮应该正常回应
        Assertions.assertNotNull(content1, "第一轮响应不应为空");

        // --- 第二轮：验证事实（核心测试点） ---
        String p2 = "请问，我刚才说我叫什么名字？我最喜欢的颜色是什么？";
        System.out.println("\nUser R2: " + p2);

        AssistantMessage resp2 = agent.call(Prompt.of(p2), session);
        String content2 = resp2.getContent();
        System.out.println("AI R2: " + content2);

        // --- 核心断言 ---

        // A. 检查逻辑重复：第二轮输出不应等于第一轮输出（防止 Trace 逻辑错误导致的复读）
        Assertions.assertNotEquals(content1, content2, "AI 发生了复读错误，第二轮输出与第一轮完全一致！");

        // B. 检查记忆提取：内容必须包含 R1 提到的事实
        Assertions.assertTrue(content2.contains("noear"), "AI 应该记得用户的名字叫 noear");
        Assertions.assertTrue(content2.contains("蓝") || content2.toLowerCase().contains("blue"), "AI 应该记得颜色是蓝色");

        // C. 检查消息流长度：
        // Session 历史应包含：R1_User, R1_AI, R2_User, R2_AI (共4条)
        Collection<ChatMessage> fullHistory = session.getHistoryMessages(agent.name(), 10);
        Assertions.assertEquals(4, fullHistory.size(), "Session 历史消息数量应为 4 条");


        System.out.println("\n--- Session 历史归档查询 ---");
        fullHistory.forEach(m -> System.out.println("Archive [" + m.getRole() + "]: " + m.getContent()));
    }
}