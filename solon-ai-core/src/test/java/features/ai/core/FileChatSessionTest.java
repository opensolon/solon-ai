package features.ai.core;

import lombok.extern.slf4j.Slf4j;
import lombok.var;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.MessageProtocolState;
import org.noear.solon.ai.chat.message.MessageSemanticHasher;
import org.noear.solon.ai.chat.session.FileChatSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * FileChatSession 单元测试
 *
 * @author noear
 */
@Slf4j
public class FileChatSessionTest {
    private static final String LEGACY_ASSISTANT_JSON =
            "{\"role\":\"assistant\",\"text\":\"answer\",\"thinking\":\"thinking\"," +
                    "\"contentRaw\":{\"legacy\":\"content\"}," +
                    "\"toolCallsRaw\":[{\"id\":\"call-old\",\"type\":\"function\"," +
                    "\"function\":{\"name\":\"lookup\"," +
                    "\"arguments\":\"{\\\"b\\\":2,\\\"a\\\":1}\"}}]," +
                    "\"searchResultsRaw\":[{\"url\":\"https://legacy.example\"}]," +
                    "\"reasoningFieldName\":\"reasoning_content\"}";

    private static String tempDir;

    @BeforeAll
    public static void setup() throws IOException {
        // 创建临时目录存放 ndjson 文件
        Path path = Files.createTempDirectory("solon_ai_file_test_");
        tempDir = path.toString();
    }

    @Test
    public void testPersistenceAndEviction() {
        String sessionId = "s-" + UUID.randomUUID();
        int maxSize = 3;

        // 1. 构造初始 Session
        // 注意：由于当前构造函数没有开放 maxMessages 接口，我们先测试基础持久化
        // 若要测试淘汰，建议 FileChatSession 增加带 maxMessages 的构造或通过 Builder
        FileChatSession session = new FileChatSession(sessionId, tempDir);

        session.addMessage(ChatMessage.ofUser("1"));
        session.addMessage(ChatMessage.ofUser("2"));
        session.addMessage(ChatMessage.ofUser("3"));

        log.warn("Session messages: {}", session.getMessages());
        Assertions.assertEquals(3, session.getMessages().size());

        // 2. 模拟重启：新建实例加载同一文件
        FileChatSession sessionRecovered = new FileChatSession(sessionId, tempDir);

        log.warn("Recovered messages: {}", sessionRecovered.getMessages());
        Assertions.assertEquals(3, sessionRecovered.getMessages().size());
        Assertions.assertEquals("1", sessionRecovered.getMessages().get(0).getContent());
        Assertions.assertEquals("3", sessionRecovered.getMessages().get(2).getContent());
    }

    @Test
    public void testProtocolStatesAndLegacyRawRoundTrip() {
        String sessionId = "s-protocol-" + UUID.randomUUID();
        AssistantMessage message = legacyMessageWithProtocolState();

        FileChatSession session = new FileChatSession(sessionId, tempDir);
        session.addMessage(message);
        AssistantMessage restored = (AssistantMessage) new FileChatSession(sessionId, tempDir)
                .getMessages().get(0);

        assertProtocolAndLegacyPayload(restored);
        Assertions.assertEquals("call-old", restored.getToolCallsRaw().get(0).get("id"));
        Assertions.assertEquals("{\"b\":2,\"a\":1}",
                ((Map<?, ?>) restored.getToolCallsRaw().get(0).get("function")).get("arguments"));
        Assertions.assertTrue(MessageSemanticHasher.matches(restored,
                restored.getProtocolState("vendor.protocol")));
    }

    @Test
    public void testNdjsonProtocolStatesAndLegacyRawRoundTrip() throws IOException {
        AssistantMessage message = legacyMessageWithProtocolState();

        AssistantMessage restored = (AssistantMessage) ChatMessage.fromNdjson(
                ChatMessage.toNdjson(Collections.<ChatMessage>singletonList(message))).get(0);

        assertProtocolAndLegacyPayload(restored);
    }

    private AssistantMessage legacyMessageWithProtocolState() {
        AssistantMessage legacy = (AssistantMessage) ChatMessage.fromJson(LEGACY_ASSISTANT_JSON);
        MessageProtocolState state = new MessageProtocolState(1).dataPut("cursor", "next");
        state.setSemanticHash(MessageSemanticHasher.hash(legacy));

        ONode historical = ONode.ofJson(LEGACY_ASSISTANT_JSON);
        historical.set("protocolStates", Collections.singletonMap("vendor.protocol", state));
        return (AssistantMessage) ChatMessage.fromJson(historical.toJson());
    }

    private void assertProtocolAndLegacyPayload(AssistantMessage restored) {
        Assertions.assertEquals("next", restored.getProtocolState("vendor.protocol")
                .getData().get("cursor"));
        Assertions.assertEquals("content", ((Map<?, ?>) restored.getContentRaw()).get("legacy"));
        Assertions.assertEquals("https://legacy.example", restored.getSearchResultsRaw().get(0).get("url"));
        Assertions.assertEquals("reasoning_content", restored.getReasoningFieldName());
    }

    @Test
    public void testClear() {
        String sessionId = "s-clear-" + UUID.randomUUID();
        FileChatSession session = new FileChatSession(sessionId, tempDir);

        session.addMessage(ChatMessage.ofUser("to be deleted"));
        Assertions.assertFalse(session.isEmpty());

        // 执行物理清理
        session.clear();

        // 验证内存和文件是否都已移除
        Assertions.assertTrue(session.isEmpty());
        FileChatSession sessionNew = new FileChatSession(sessionId, tempDir);
        Assertions.assertTrue(sessionNew.isEmpty());
    }

    @Test
    public void testLatestMessages() {
        String sessionId = "s-latest-" + UUID.randomUUID();
        FileChatSession session = new FileChatSession(sessionId, tempDir);

        session.addMessage(ChatMessage.ofUser("msg 1"));
        session.addMessage(ChatMessage.ofAssistant("ans 1"));
        session.addMessage(ChatMessage.ofUser("msg 2"));

        // 验证窗口截断（InMemoryChatSession 提供的逻辑）
        var latest = session.getLatestMessages(1);
        Assertions.assertEquals(1, latest.size());
        Assertions.assertEquals("msg 2", latest.get(0).getContent());
    }

    @Test
    public void testRemoveLatestMessage_persistence() {
        String sessionId = "s-remove-" + UUID.randomUUID();
        FileChatSession session = new FileChatSession(sessionId, tempDir);

        session.addMessage(ChatMessage.ofUser("msg 1"));
        session.addMessage(ChatMessage.ofAssistant("ans 1"));
        session.addMessage(ChatMessage.ofUser("msg 2"));
        session.addMessage(ChatMessage.ofAssistant("ans 2"));

        // 删除最后 2 条
        session.removeLatestMessage(2);
        Assertions.assertEquals(2, session.getMessages().size());
        Assertions.assertEquals("msg 1", session.getMessages().get(0).getContent());

        // 模拟重启：重新加载磁盘文件
        FileChatSession sessionRecovered = new FileChatSession(sessionId, tempDir);
        Assertions.assertEquals(2, sessionRecovered.getMessages().size());
        Assertions.assertEquals("msg 1", sessionRecovered.getMessages().get(0).getContent());
        Assertions.assertEquals("ans 1", sessionRecovered.getMessages().get(1).getContent());
    }
}