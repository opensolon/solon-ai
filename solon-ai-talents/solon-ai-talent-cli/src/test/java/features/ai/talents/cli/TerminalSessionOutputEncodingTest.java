package features.ai.talents.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.talents.cli.TerminalSessionManager;

/**
 * 会话输出编码：中文（多字节）输出跨读取分块不得出现替换符；增量快照只返回新增文本。
 */
public class TerminalSessionOutputEncodingTest {

    @Test
    public void largeChineseOutputHasNoReplacementChar() throws Exception {
        if (isWindows()) {
            return;
        }
        Path workDir = Files.createTempDirectory("solon-ai-session-encoding-");
        try {
            TerminalSessionManager manager = new TerminalSessionManager();
            // 约 2000 行中文（远超 4096 字节的读取分块），必然出现多字节字符跨块的情况
            TerminalSessionManager.CommandSnapshot snapshot =
                    manager.exec(
                            "for i in $(seq 1 2000); do printf '中文测试行%s\\n' \"$i\"; done",
                            workDir,
                            null,
                            10_000,
                            2_000_000,
                            30_000);

            assertFalse(snapshot.running(), snapshot.output());
            String output = snapshot.output();
            assertFalse(output.contains("\uFFFD"),
                    "多字节字符被分块边界截断: " + output.substring(0, Math.min(200, output.length())));
            assertTrue(output.contains("中文测试行1\n"), "首行应完整: " + output.substring(0, 40));
            assertTrue(output.contains("中文测试行2000"), "末行应完整");
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void incrementalSnapshotReturnsOnlyNewOutput() throws Exception {
        if (isWindows()) {
            return;
        }
        Path workDir = Files.createTempDirectory("solon-ai-session-incremental-");
        try {
            TerminalSessionManager manager = new TerminalSessionManager();
            TerminalSessionManager.CommandSnapshot first =
                    manager.exec("printf 第一段; sleep 0.4; printf 第二段", workDir, null, 50, 100_000, 10_000);
            assertTrue(first.running(), first.output());
            assertTrue(first.output().contains("第一段"), first.output());

            TerminalSessionManager.CommandSnapshot second =
                    manager.writeStdin(first.sessionId(), "", 3_000, 100_000);
            assertFalse(second.running(), second.output());
            assertTrue(second.output().contains("第二段"), second.output());
            assertFalse(second.output().contains("第一段"),
                    "已消费的输出不应重复返回: " + second.output());
        } finally {
            deleteRecursively(workDir);
        }
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase().contains("win");
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        }
    }
}
