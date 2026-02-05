package features.ai.session;

import org.junit.jupiter.api.*;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * FileAgentSession 单元测试
 * 验证 NDJSON 消息追加与快照 JSON 持久化
 */
public class FileAgentSessionTest {

    private static Path tempDir;
    private String sessionId;

    @BeforeAll
    public static void setup() throws IOException {
        // 创建临时测试目录
        tempDir = Files.createTempDirectory("solon_ai_tests_");
    }

    @BeforeEach
    public void init() {
        sessionId = "test-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    public void testPersistenceAndRecovery() {
        // 1. 创建会话并写入消息
        FileAgentSession session = new FileAgentSession(sessionId, tempDir.toString());
        session.addMessage(Arrays.asList(
                ChatMessage.ofUser("hello"),
                ChatMessage.ofAssistant("hi, how can I help?")
        ));

        // 修改快照数据
        session.getSnapshot().put("user_name", "noear");
        session.updateSnapshot();

        // 2. 模拟重启：创建新的 Session 实例指向同一目录
        FileAgentSession sessionRecovered = new FileAgentSession(sessionId, tempDir.toString());

        // 验证消息恢复
        List<ChatMessage> messages = sessionRecovered.getMessages();
        Assertions.assertEquals(2, messages.size());
        Assertions.assertEquals("user", messages.get(0).getRole().name().toLowerCase());
        Assertions.assertEquals("hello", messages.get(0).getContent());

        // 验证快照恢复
        Assertions.assertEquals("noear", sessionRecovered.getSnapshot().get("user_name"));
        Assertions.assertEquals(sessionRecovered, sessionRecovered.getSnapshot().get(Agent.KEY_SESSION));
    }

    @Test
    public void testAddMessage_FilterSystem() {
        FileAgentSession session = new FileAgentSession(sessionId, tempDir.toString());

        // System 消息不应被持久化到 NDJSON 文件
        session.addMessage(Arrays.asList(
                ChatMessage.ofSystem("internal system prompt"),
                ChatMessage.ofUser("real message")
        ));

        List<ChatMessage> stored = session.getMessages();
        Assertions.assertEquals(1, stored.size());
        Assertions.assertEquals("user", stored.get(0).getRole().name().toLowerCase());
    }

    @Test
    public void testLatestMessages_Window() {
        FileAgentSession session = new FileAgentSession(sessionId, tempDir.toString());

        // 追加 3 条消息
        session.addMessage(Arrays.asList(ChatMessage.ofUser("1")));
        session.addMessage(Arrays.asList(ChatMessage.ofUser("2")));
        session.addMessage(Arrays.asList(ChatMessage.ofUser("3")));

        // 验证窗口截断：只取最后 2 条
        List<ChatMessage> latest = session.getLatestMessages(2);
        Assertions.assertEquals(2, latest.size());
        Assertions.assertEquals("2", latest.get(0).getContent());
        Assertions.assertEquals("3", latest.get(1).getContent());
    }

    @Test
    public void testAppendEfficiency() {
        // 测试多次追加写入是否正常（NDJSON 核心优势）
        FileAgentSession session = new FileAgentSession(sessionId, tempDir.toString());

        session.addMessage(Arrays.asList(ChatMessage.ofUser("msg 1")));
        session.addMessage(Arrays.asList(ChatMessage.ofAssistant("msg 2")));

        Assertions.assertEquals(2, session.getMessages().size());

        // 验证文件物理存在
        File msgFile = new File(tempDir.toFile(), sessionId + ".messages.ndjson");
        Assertions.assertTrue(msgFile.exists());
        Assertions.assertTrue(msgFile.length() > 0);
    }

    @Test
    public void testClear() {
        FileAgentSession session = new FileAgentSession(sessionId, tempDir.toString());
        session.addMessage(Arrays.asList(ChatMessage.ofUser("to be deleted")));
        session.updateSnapshot();

        // 执行清理
        session.clear();

        // 验证文件已删除，Session 变为空
        Assertions.assertTrue(session.isEmpty());
        File msgFile = new File(tempDir.toFile(), sessionId + ".messages.ndjson");
        File snapFile = new File(tempDir.toFile(), sessionId + ".snapshot.json");
        Assertions.assertFalse(msgFile.exists());
        Assertions.assertFalse(snapFile.exists());
    }

    @AfterAll
    public static void tearDown() throws IOException {
        // 清理整个临时目录（可选）
        // Files.walk(tempDir).map(Path::toFile).forEach(File::delete);
    }
}