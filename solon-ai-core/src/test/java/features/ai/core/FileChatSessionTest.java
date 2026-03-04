package features.ai.core;

import lombok.extern.slf4j.Slf4j;
import lombok.var;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.FileChatSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * FileChatSession 单元测试
 *
 * @author noear
 */
@Slf4j
public class FileChatSessionTest {
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
}