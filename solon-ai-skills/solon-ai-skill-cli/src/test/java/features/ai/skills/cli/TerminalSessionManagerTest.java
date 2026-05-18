package features.ai.skills.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.skills.cli.TerminalSessionManager;

public class TerminalSessionManagerTest {

    @Test
    public void shortCommandCompletes() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-command-session-short-");
        try {
            TerminalSessionManager manager = new TerminalSessionManager();
            TerminalSessionManager.CommandSnapshot snapshot =
                    manager.exec("printf hello", workDir, null, 2_000, 1_000, 10_000);

            assertFalse(snapshot.running());
            assertTrue(snapshot.output().contains("hello"), snapshot.output());
            assertNotNull(snapshot.exitCode());
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void longCommandCanBeContinued() throws Exception {
        if (isWindows()) {
            return;
        }
        Path workDir = Files.createTempDirectory("solon-ai-command-session-long-");
        try {
            TerminalSessionManager manager = new TerminalSessionManager();
            TerminalSessionManager.CommandSnapshot first =
                    manager.exec(
                            "printf start; sleep 0.4; printf end",
                            workDir,
                            null,
                            50,
                            1_000,
                            10_000);

            assertTrue(first.running(), first.output());
            assertTrue(first.output().contains("start"), first.output());

            TerminalSessionManager.CommandSnapshot second =
                    manager.writeStdin(first.sessionId(), "", 2_000, 1_000);
            assertFalse(second.running(), second.output());
            assertTrue(second.output().contains("end"), second.output());
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void terminateDestroysChildProcessTree() throws Exception {
        if (isWindows()) {
            return;
        }
        Path workDir = Files.createTempDirectory("solon-ai-command-session-terminate-");
        Path marker = workDir.resolve("child.pid");
        try {
            TerminalSessionManager manager = new TerminalSessionManager();
            TerminalSessionManager.CommandSnapshot first =
                    manager.exec("sleep 30 & echo $! > child.pid; wait", workDir, null, 50, 1_000, 60_000);
            assertTrue(first.running(), first.output());

            long childPid = Long.parseLong(readFile(marker).trim());
            TerminalSessionManager.CommandSnapshot terminated =
                    manager.terminate(first.sessionId(), "test", 1_000);
            assertFalse(terminated.running());
            assertTrue(terminated.terminated());
            waitUntilNotAlive(childPid);
            assertFalse(isProcessAlive(childPid));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void hardTimeoutDestroysProcessEvenWithoutPolling() throws Exception {
        if (isWindows()) {
            return;
        }
        Path workDir = Files.createTempDirectory("solon-ai-command-session-hard-timeout-");
        Path marker = workDir.resolve("child.pid");
        try {
            TerminalSessionManager manager = new TerminalSessionManager();
            TerminalSessionManager.CommandSnapshot first =
                    manager.exec("sleep 30 & echo $! > child.pid; wait", workDir, null, 20, 1_000, 200);
            assertTrue(first.running(), first.output());

            long childPid = Long.parseLong(readFile(marker).trim());
            waitUntilNotAlive(childPid);
            assertFalse(isProcessAlive(childPid));

            TerminalSessionManager.CommandSnapshot second =
                    manager.writeStdin(first.sessionId(), "", 500, 1_000);
            assertFalse(second.running());
            assertTrue(second.timedOut(), second.output());
        } finally {
            deleteRecursively(workDir);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static void waitUntilNotAlive(long pid) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            if (!isProcessAlive(pid)) {
                return;
            }
            Thread.sleep(50);
        }
    }

    private static boolean isProcessAlive(long pid) {
        Process process = null;
        try {
            process = new ProcessBuilder("kill", "-0", String.valueOf(pid)).start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String readFile(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> pathStream = Files.walk(root)) {
            pathStream.forEach(paths::add);
        }
        Collections.sort(paths, Collections.reverseOrder());
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }
}
