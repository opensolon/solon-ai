package features.ai.session;

import org.junit.jupiter.api.*;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

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
        session.getContext().put("user_name", "noear");
        session.updateSnapshot();

        // 2. 模拟重启：创建新的 Session 实例指向同一目录
        FileAgentSession sessionRecovered = new FileAgentSession(sessionId, tempDir.toString());

        // 验证消息恢复
        List<ChatMessage> messages = sessionRecovered.getMessages();
        Assertions.assertEquals(2, messages.size());
        Assertions.assertEquals("user", messages.get(0).getRole().name().toLowerCase());
        Assertions.assertEquals("hello", messages.get(0).getContent());

        // 验证快照恢复
        Assertions.assertEquals("noear", sessionRecovered.getContext().get("user_name"));
        Assertions.assertEquals(sessionRecovered, sessionRecovered.getContext().get(Agent.KEY_SESSION));
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

    // ============ 原子写（快照与消息文件） ============

    @Test
    public void testRemoveLatestMessageRewritesFile() throws IOException {
        FileAgentSession session = new FileAgentSession(sessionId, tempDir.toString());
        session.addMessage(Arrays.asList(ChatMessage.ofUser("1")));
        session.addMessage(Arrays.asList(ChatMessage.ofUser("2")));

        // 安全删除最近 1 条：内存与磁盘都应只剩 1 条
        session.removeLatestMessage(1);

        Assertions.assertEquals(1, session.getMessages().size());

        Path msgFile = tempDir.resolve(sessionId + ".messages.ndjson");
        long lines = Files.readAllLines(msgFile, StandardCharsets.UTF_8)
                .stream().filter(l -> !l.trim().isEmpty()).count();
        Assertions.assertEquals(1, lines, "全量重写后磁盘行数应与内存一致");

        // 模拟重启：恢复后同样只有 1 条
        Assertions.assertEquals(1,
                new FileAgentSession(sessionId, tempDir.toString()).getMessages().size());
    }

    @Test
    public void testRemoveLatestLeavesNoTmpFile() throws IOException {
        FileAgentSession session = new FileAgentSession(sessionId, tempDir.toString());
        session.addMessage(Arrays.asList(ChatMessage.ofUser("1"), ChatMessage.ofUser("2")));

        session.removeLatestMessage(1);

        try (Stream<Path> stream = Files.list(tempDir)) {
            long tmpCount = stream.filter(p -> p.getFileName().toString()
                    .startsWith(sessionId + ".messages.ndjson.tmp.")).count();
            Assertions.assertEquals(0, tmpCount, "原子重写完成后不应残留消息临时文件");
        }
    }

    @Test
    public void testUpdateSnapshotLeavesNoTmpFile() throws IOException {
        FileAgentSession session = new FileAgentSession(sessionId, tempDir.toString());
        session.getContext().put("k", "v");
        session.updateSnapshot();

        Assertions.assertEquals(0, countTmpFiles(sessionId),
                "原子写完成后不应残留临时文件");
    }

    @Test
    public void testUpdateSnapshotAtomicOverwrite() throws IOException {
        FileAgentSession session = new FileAgentSession(sessionId, tempDir.toString());
        Path snap = tempDir.resolve(sessionId + ".snapshot.json");

        session.getContext().put("v", "first");
        session.updateSnapshot();
        Assertions.assertTrue(Files.exists(snap));

        // 覆盖写：内容完整替换，不是截断或追加
        session.getContext().put("v", "second");
        session.updateSnapshot();

        String json = new String(Files.readAllBytes(snap), StandardCharsets.UTF_8);
        Assertions.assertTrue(json.contains("second"));
        Assertions.assertFalse(json.contains("first"));
        // 文件内容必须是可完整解析的 JSON（非半截）
        Assertions.assertEquals("second",
                new FileAgentSession(sessionId, tempDir.toString()).getContext().get("v"));
    }

    @Test
    public void testStaleTmpCleanedOnReopen() throws IOException {
        // 模拟上次崩溃遗留的临时文件（时间回拨，超过陈旧阈值）
        Path stale = tempDir.resolve(sessionId + ".snapshot.json.tmp." + UUID.randomUUID());
        Files.write(stale, "half-writ".getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(stale, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - 10 * 60 * 1000L));

        FileAgentSession session = new FileAgentSession(sessionId, tempDir.toString());

        Assertions.assertFalse(Files.exists(stale), "重开会话时应清理崩溃残留的临时文件");
        // 且不影响正常创建
        session.getContext().put("k", "v");
        session.updateSnapshot();
        Assertions.assertEquals("v",
                new FileAgentSession(sessionId, tempDir.toString()).getContext().get("k"));
    }

    @Test
    public void testFreshTmpKeptOnReopen() throws IOException {
        // 新近的临时文件可能属于另一个正在写的实例，不得误删
        Path fresh = tempDir.resolve(sessionId + ".snapshot.json.tmp." + UUID.randomUUID());
        Files.write(fresh, "in-flight".getBytes(StandardCharsets.UTF_8));

        new FileAgentSession(sessionId, tempDir.toString());

        Assertions.assertTrue(Files.exists(fresh), "刚写入的临时文件不应被当作陈旧文件清理");
        Files.deleteIfExists(fresh);
    }

    /** 统计本用例会话残留的快照临时文件数（按会话前缀隔离，避免用例间干扰）。 */
    private static long countTmpFiles(String sessionId) throws IOException {
        try (Stream<Path> stream = Files.list(tempDir)) {
            return stream.filter(p -> p.getFileName().toString()
                    .startsWith(sessionId + ".snapshot.json.tmp.")).count();
        }
    }

    @AfterAll
    public static void tearDown() throws IOException {
        // 清理整个临时目录（可选）
        // Files.walk(tempDir).map(Path::toFile).forEach(File::delete);
    }
}