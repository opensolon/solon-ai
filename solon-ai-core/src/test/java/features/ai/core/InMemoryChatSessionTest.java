package features.ai.core;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;

import java.util.List;

/**
 *
 * @author noear 2025/8/8 created
 *
 */
@Slf4j
public class InMemoryChatSessionTest {
    @Test
    public void maxSize() {
        InMemoryChatSession session = InMemoryChatSession.builder()
                .maxMessages(3)
                .build();

        session.addMessage(ChatMessage.ofUser("1"));
        session.addMessage(ChatMessage.ofUser("2"));
        session.addMessage(ChatMessage.ofUser("3"));

        log.warn("{}", session.getMessages());
        assert "[{role=user, content='1'}, {role=user, content='2'}, {role=user, content='3'}]".equals(session.getMessages().toString());

        session.addMessage(ChatMessage.ofUser("4"));

        log.warn("{}", session.getMessages());
        assert "[{role=user, content='2'}, {role=user, content='3'}, {role=user, content='4'}]".equals(session.getMessages().toString());

        session.addMessage(ChatMessage.ofUser("5"));

        log.warn("{}", session.getMessages());
        assert "[{role=user, content='3'}, {role=user, content='4'}, {role=user, content='5'}]".equals(session.getMessages().toString());
    }

    @Test
    public void maxSize1() {
        InMemoryChatSession session = InMemoryChatSession.builder()
                .maxMessages(3)
                .build();

        session.addMessage(ChatMessage.ofSystem("system"));
        session.addMessage(ChatMessage.ofUser("1"));
        session.addMessage(ChatMessage.ofUser("2"));
        session.addMessage(ChatMessage.ofUser("3"));

        log.warn("{}", session.getMessages());
        assert "[{role=system, content='system'}, {role=user, content='2'}, {role=user, content='3'}]".equals(session.getMessages().toString());

        session.addMessage(ChatMessage.ofUser("4"));

        log.warn("{}", session.getMessages());
        assert "[{role=system, content='system'}, {role=user, content='3'}, {role=user, content='4'}]".equals(session.getMessages().toString());

        session.addMessage(ChatMessage.ofUser("5"));

        log.warn("{}", session.getMessages());
        assert "[{role=system, content='system'}, {role=user, content='4'}, {role=user, content='5'}]".equals(session.getMessages().toString());
    }

    /**
     * 测试 getLatestMessages - 基本窗口截取
     */
    @Test
    public void getLatestMessages_basic() {
        InMemoryChatSession session = InMemoryChatSession.builder().build();

        session.addMessage(ChatMessage.ofUser("msg1"));
        session.addMessage(ChatMessage.ofAssistant("ans1"));
        session.addMessage(ChatMessage.ofUser("msg2"));
        session.addMessage(ChatMessage.ofAssistant("ans2"));
        session.addMessage(ChatMessage.ofUser("msg3"));

        // 窗口大小 2，初步 start=3，向前找到 UserMessage("msg2")
        // 所以实际返回 3 条（msg2, ans2, msg3）
        List<ChatMessage> latest = session.getLatestMessages(2);
        log.warn("{}", latest);
        Assertions.assertEquals(3, latest.size());
        Assertions.assertEquals("msg2", latest.get(0).getContent());
    }

    /**
     * 测试 getLatestMessages - 截断点落在 UserMessage 上（理想情况）
     */
    @Test
    public void getLatestMessages_userAsStart() {
        InMemoryChatSession session = InMemoryChatSession.builder().build();

        // 消息序列：User -> Assistant -> User -> Assistant -> User
        session.addMessage(ChatMessage.ofUser("u1"));
        session.addMessage(ChatMessage.ofAssistant("a1"));
        session.addMessage(ChatMessage.ofUser("u2"));
        session.addMessage(ChatMessage.ofAssistant("a2"));
        session.addMessage(ChatMessage.ofUser("u3"));

        // windowSize=3，初步 start=2，刚好是 UserMessage("u2")
        List<ChatMessage> latest = session.getLatestMessages(3);
        log.warn("{}", latest);
        Assertions.assertEquals(3, latest.size());
        Assertions.assertEquals("u2", latest.get(0).getContent());
    }

    /**
     * 测试 getLatestMessages - 空会话和窗口超限
     */
    @Test
    public void getLatestMessages_edgeCases() {
        InMemoryChatSession session = InMemoryChatSession.builder().build();

        // 空会话
        List<ChatMessage> empty = session.getLatestMessages(5);
        Assertions.assertEquals(0, empty.size());

        // 消息少于窗口大小
        session.addMessage(ChatMessage.ofUser("only"));
        List<ChatMessage> all = session.getLatestMessages(10);
        Assertions.assertEquals(1, all.size());

        // windowSize <= 0 返回全部
        List<ChatMessage> zero = session.getLatestMessages(0);
        Assertions.assertEquals(1, zero.size());
    }

    /**
     * 测试 getLatestMessages - 带ToolCall链的回溯补全
     * 场景：[User, Assistant(toolCalls), ToolMessage, ToolMessage, User, Assistant]
     * windowSize=1 时，初步 start=5，向前找 UserMessage=4
     * 但 start-1 位置是 ToolMessage，且再前面是带 toolCalls 的 AssistantMessage
     * 步骤 5 应回退把整个 ToolCall 链包含进来
     */
    @Test
    public void getLatestMessages_withToolCallChain() {
        InMemoryChatSession session = InMemoryChatSession.builder().build();

        // 构造：User -> Assistant(带toolCalls) -> ToolMessage -> ToolMessage -> User -> Assistant
        session.addMessage(ChatMessage.ofUser("u1"));

        // 构造带 toolCalls 的 AssistantMessage
        java.util.Map<String, Object> funcMap = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> funcNameMap = new java.util.LinkedHashMap<>();
        funcNameMap.put("name", "getWeather");
        funcMap.put("function", funcNameMap);

        java.util.Map<String, Object> argMap = new java.util.LinkedHashMap<>();

        AssistantMessage toolCallMsg = new AssistantMessage("", false, null,
                java.util.Collections.singletonList(funcMap),
                java.util.Collections.singletonList(new org.noear.solon.ai.chat.tool.ToolCall("0", "call_1", "getWeather", "{}", argMap)),
                null);
        session.addMessage(toolCallMsg);
        session.addMessage(ChatMessage.ofTool("sunny", "getWeather", "call_1"));
        session.addMessage(ChatMessage.ofTool("25°C", "getTemperature", "call_2"));
        session.addMessage(ChatMessage.ofUser("u2"));
        session.addMessage(ChatMessage.ofAssistant("final answer"));

        // windowSize=2，初步 start=4 (u2)
        // 步骤5：start-1 是 ToolMessage -> 回退；再 start-1 是 ToolMessage -> 回退；
        //        再 start-1 是 AssistantMessage(toolCalls) -> 回退包含
        List<ChatMessage> latest = session.getLatestMessages(2);
        log.warn("{}", latest);
        // 应包含完整的 ToolCall 链 + User + Assistant
        // 预期：[Assistant(toolCalls), ToolMessage, ToolMessage, User(u2), Assistant(final answer)]
        Assertions.assertTrue(latest.size() >= 2);
        // 第一个元素应该是带 toolCalls 的 AssistantMessage
        Assertions.assertTrue(latest.get(0) instanceof AssistantMessage);
        Assertions.assertTrue(((AssistantMessage) latest.get(0)).isToolCalls());
    }

    /**
     * 测试 getLatestMessages - 截断点落在 AssistantMessage 上，向前回退找 UserMessage
     */
    @Test
    public void getLatestMessages_startAtAssistant() {
        InMemoryChatSession session = InMemoryChatSession.builder().build();

        session.addMessage(ChatMessage.ofUser("u1"));
        session.addMessage(ChatMessage.ofAssistant("a1"));
        session.addMessage(ChatMessage.ofUser("u2"));
        session.addMessage(ChatMessage.ofAssistant("a2"));

        // windowSize=1, 初步 start=3 (Assistant a2), 向前找到 UserMessage u2
        List<ChatMessage> latest = session.getLatestMessages(1);
        log.warn("{}", latest);
        Assertions.assertEquals(2, latest.size());
        Assertions.assertEquals("u2", latest.get(0).getContent());
        Assertions.assertEquals("a2", latest.get(1).getContent());
    }

    // ==================== removeLatestMessage 测试 ====================

    /**
     * 测试 removeLatestMessage - 基本删除（普通消息）
     */
    @Test
    public void removeLatestMessage_basic() {
        InMemoryChatSession session = InMemoryChatSession.builder().build();

        session.addMessage(ChatMessage.ofUser("u1"));
        session.addMessage(ChatMessage.ofAssistant("a1"));
        session.addMessage(ChatMessage.ofUser("u2"));
        session.addMessage(ChatMessage.ofAssistant("a2"));

        // 删除最后 1 条（a2）
        session.removeLatestMessage(1);
        Assertions.assertEquals(3, session.getMessages().size());
        Assertions.assertEquals("u2", session.getMessages().get(2).getContent());

        // 再删除 1 条（u2）
        session.removeLatestMessage(1);
        Assertions.assertEquals(2, session.getMessages().size());
        Assertions.assertEquals("a1", session.getMessages().get(1).getContent());
    }

    /**
     * 测试 removeLatestMessage - 删除带 ToolCall 的 AssistantMessage 时，自动清理后续 ToolMessage
     * 场景：[User, Assistant(toolCalls), ToolMsg, ToolMsg, User]
     * windowSize=3 时，删除 User -> 删除 ToolMsg -> 删除 ToolMsg -> 遇到 Assistant(toolCalls) 时连带清理
     */
    @Test
    public void removeLatestMessage_withToolCalls() {
        InMemoryChatSession session = InMemoryChatSession.builder().build();

        // 构造：User -> Assistant(带toolCalls) -> ToolMessage -> ToolMessage -> User -> Assistant
        session.addMessage(ChatMessage.ofUser("u1"));

        java.util.Map<String, Object> funcMap = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> funcNameMap = new java.util.LinkedHashMap<>();
        funcNameMap.put("name", "getWeather");
        funcMap.put("function", funcNameMap);
        java.util.Map<String, Object> argMap = new java.util.LinkedHashMap<>();
        AssistantMessage toolCallMsg = new AssistantMessage("", false, null,
                java.util.Collections.singletonList(funcMap),
                java.util.Collections.singletonList(new org.noear.solon.ai.chat.tool.ToolCall("0", "call_1", "getWeather", "{}", argMap)),
                null);
        session.addMessage(toolCallMsg);
        session.addMessage(ChatMessage.ofTool("sunny", "getWeather", "call_1"));
        session.addMessage(ChatMessage.ofTool("25C", "getTemp", "call_2"));
        session.addMessage(ChatMessage.ofUser("u2"));
        session.addMessage(ChatMessage.ofAssistant("a2"));

        // 删除最后 1 条（a2，普通 Assistant）
        session.removeLatestMessage(1);
        Assertions.assertEquals(5, session.getMessages().size());
        log.warn("After remove 1: {}", session.getMessages());

        // 再删除 1 条（u2，User）
        session.removeLatestMessage(1);
        Assertions.assertEquals(4, session.getMessages().size());
        log.warn("After remove 2: {}", session.getMessages());

        // 再删除 1 条 -> 删到 ToolMessage，会连带删除所有 ToolMessage + Assistant(toolCalls)
        session.removeLatestMessage(1);
        log.warn("After remove 3: {}", session.getMessages());
        // 预期只剩 u1（删除了 ToolMessage, ToolMessage, Assistant(toolCalls)）
        Assertions.assertEquals(1, session.getMessages().size());
        Assertions.assertEquals("u1", session.getMessages().get(0).getContent());
    }

    /**
     * 测试 removeLatestMessage - 删除全部消息后不再报错
     */
    @Test
    public void removeLatestMessage_removeAll() {
        InMemoryChatSession session = InMemoryChatSession.builder().build();

        session.addMessage(ChatMessage.ofUser("u1"));
        session.addMessage(ChatMessage.ofAssistant("a1"));

        // 删除 2 条
        session.removeLatestMessage(2);
        Assertions.assertEquals(0, session.getMessages().size());
        Assertions.assertTrue(session.isEmpty());

        // 多删也不报错
        session.removeLatestMessage(1);
        Assertions.assertEquals(0, session.getMessages().size());
    }

    /**
     * 测试 removeLatestMessage - windowSize=0 不做任何操作
     */
    @Test
    public void removeLatestMessage_zeroWindow() {
        InMemoryChatSession session = InMemoryChatSession.builder().build();

        session.addMessage(ChatMessage.ofUser("u1"));
        session.removeLatestMessage(0);
        Assertions.assertEquals(1, session.getMessages().size());
    }

    /**
     * 测试 removeLatestMessage - 删除末尾直接是 ToolMessage 的情况
     * 场景：[User, Assistant(toolCalls), ToolMessage]
     * 删除最后 1 条时，ToolMessage 被删除，然后连带删除 Assistant(toolCalls)
     */
    @Test
    public void removeLatestMessage_endingWithToolMessage() {
        InMemoryChatSession session = InMemoryChatSession.builder().build();

        session.addMessage(ChatMessage.ofUser("u1"));

        java.util.Map<String, Object> funcMap = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> funcNameMap = new java.util.LinkedHashMap<>();
        funcNameMap.put("name", "getWeather");
        funcMap.put("function", funcNameMap);
        java.util.Map<String, Object> argMap = new java.util.LinkedHashMap<>();
        AssistantMessage toolCallMsg = new AssistantMessage("", false, null,
                java.util.Collections.singletonList(funcMap),
                java.util.Collections.singletonList(new org.noear.solon.ai.chat.tool.ToolCall("0", "call_1", "getWeather", "{}", argMap)),
                null);
        session.addMessage(toolCallMsg);
        session.addMessage(ChatMessage.ofTool("sunny", "getWeather", "call_1"));

        // 删除 1 条 -> 最后一条是 ToolMessage
        // 应删除 ToolMessage + Assistant(toolCalls)，只剩 User
        session.removeLatestMessage(1);
        log.warn("After remove: {}", session.getMessages());
        Assertions.assertEquals(1, session.getMessages().size());
        Assertions.assertEquals("u1", session.getMessages().get(0).getContent());
    }
}