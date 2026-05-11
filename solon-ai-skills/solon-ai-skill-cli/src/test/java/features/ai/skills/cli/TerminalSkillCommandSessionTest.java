package features.ai.skills.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.skills.cli.PoolManager;
import org.noear.solon.ai.skills.cli.TerminalSkill;

public class TerminalSkillCommandSessionTest {

    @Test
    public void exposesCommandSessionTools() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-terminal-tools-");
        try {
            TerminalSkill skill = new TerminalSkill(workDir.toString(), new PoolManager());
            List<String> toolNames =
                    skill.getToolAry("bash_start", "bash_wait", "bash_stdin", "bash_stop").stream()
                            .map(FunctionTool::name)
                            .sorted()
                            .collect(Collectors.toList());

            assertEquals(Arrays.asList("bash_start", "bash_stdin", "bash_stop", "bash_wait"), toolNames);
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void execCommandReturnsRunningSessionThenCanContinue() throws Exception {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            return;
        }
        Path workDir = Files.createTempDirectory("solon-ai-terminal-session-");
        try {
            TerminalSkill skill = new TerminalSkill(workDir.toString(), new PoolManager());
            String first =
                    skill.bashStart(
                            "printf start; sleep 0.4; printf end",
                            null,
                            50,
                            2_000,
                            10_000,
                            workDir.toString());
            assertTrue(first.contains("Process running with session ID"), first);
            String sessionId = extractSessionId(first);

            String second = skill.bashWait(sessionId, 2_000, 2_000);
            assertTrue(second.contains("status: completed"), second);
            assertTrue(second.contains("end"), second);
        } finally {
            deleteRecursively(workDir);
        }
    }

    private static String extractSessionId(String text) {
        String marker = "session_id: ";
        int start = text.indexOf(marker);
        if (start < 0) {
            throw new AssertionError("No session_id in: " + text);
        }
        int valueStart = start + marker.length();
        int valueEnd = text.indexOf('\n', valueStart);
        return text.substring(valueStart, valueEnd).trim();
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths = new ArrayList<>();
        Files.walk(root).forEach(paths::add);
        Collections.sort(paths, Collections.reverseOrder());
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }
}
