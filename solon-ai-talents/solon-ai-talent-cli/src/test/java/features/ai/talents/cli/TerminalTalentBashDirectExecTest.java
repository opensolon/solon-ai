package features.ai.talents.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Field;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.talents.cli.ShellCommandFactory;
import org.noear.solon.ai.talents.cli.ShellMode;
import org.noear.solon.ai.talents.cli.TerminalSessionManager;
import org.noear.solon.ai.talents.cli.TerminalTalent;
import org.noear.solon.ai.talents.mount.MountManager;

/**
 * 验证同步 bash 与 bash_start 对齐：共用 ShellCommandFactory，直接 shell -c 执行，不落临时脚本。
 */
public class TerminalTalentBashDirectExecTest {

    @TempDir
    Path workDir;

    @Test
    public void bash_executesDirectlyWithoutTempScript() throws Exception {
        assumeTrue(!isWindows(), "Unix shell test");

        Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        Set<String> before = listSolonScriptNames(tmpDir);

        TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
        String result = talent.bash("echo direct-exec-ok; printf 'line2\\n'", 10_000, 64_000, workDir.toString());

        assertTrue(result.contains("direct-exec-ok"), "应直接执行命令, result=" + result);
        assertTrue(result.contains("line2") || result.contains("执行成功"), "应支持简单多语句, result=" + result);

        Set<String> after = listSolonScriptNames(tmpDir);
        Set<String> leftover = new HashSet<>(after);
        leftover.removeAll(before);
        assertTrue(leftover.isEmpty(), "同步 bash 不应创建 solon-ai-script- 临时文件: " + leftover);

        Set<String> workFiles = listFileNames(workDir);
        assertTrue(workFiles.stream().noneMatch(n -> n.startsWith("_script_") || n.startsWith("solon-ai-script-")),
                "工作区不应出现临时脚本: " + workFiles);
    }

    @Test
    public void bash_keepsWorkingDirectorySemantics() throws Exception {
        assumeTrue(!isWindows(), "Unix shell test");

        Files.write(workDir.resolve("marker.txt"), "x".getBytes());
        TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
        String result = talent.bash("test -f marker.txt && echo WORKDIR_OK", 10_000, 64_000, workDir.toString());

        assertTrue(result.contains("WORKDIR_OK"), "工作目录语义应保持, result=" + result);
    }

    @Test
    public void bashAndBashStart_shareSameShellFactory() throws Exception {
        TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));

        Field factoryField = TerminalTalent.class.getDeclaredField("shellCommandFactory");
        factoryField.setAccessible(true);
        ShellCommandFactory talentFactory = (ShellCommandFactory) factoryField.get(talent);

        Field sessionField = TerminalTalent.class.getDeclaredField("bashSessionManager");
        sessionField.setAccessible(true);
        TerminalSessionManager sessionManager = (TerminalSessionManager) sessionField.get(talent);

        Field sessionFactoryField = TerminalSessionManager.class.getDeclaredField("shellCommandFactory");
        sessionFactoryField.setAccessible(true);
        ShellCommandFactory sessionFactory = (ShellCommandFactory) sessionFactoryField.get(sessionManager);

        assertTrue(talentFactory == sessionFactory, "bash 与 bash_start 应共用同一个 ShellCommandFactory 实例");

        List<String> fromTalent = talentFactory.build("echo hi");
        List<String> fromSession = sessionFactory.build("echo hi");
        assertTrue(fromTalent.equals(fromSession), "两边 build 结果应完全一致: talent=" + fromTalent + ", session=" + fromSession);
        assertTrue(fromTalent.size() == 3, "应为 [shell, flag, command], actual=" + fromTalent);
        assertTrue("echo hi".equals(fromTalent.get(2)), "命令字符串应原样作为参数: " + fromTalent);

        if (talentFactory.getShellMode() == ShellMode.UNIX_SHELL) {
            assertTrue("-lc".equals(fromTalent.get(1)), "Unix 应使用 -lc: " + fromTalent);
        } else if (talentFactory.getShellMode() == ShellMode.CMD) {
            assertTrue("/c".equals(fromTalent.get(1)), "CMD 应使用 /c: " + fromTalent);
        } else {
            assertTrue("-Command".equals(fromTalent.get(1)), "PowerShell 应使用 -Command: " + fromTalent);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static Set<String> listFileNames(Path dir) throws Exception {
        Set<String> names = new HashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                names.add(p.getFileName().toString());
            }
        }
        return names;
    }

    private static Set<String> listSolonScriptNames(Path tmpDir) throws Exception {
        Set<String> names = new HashSet<>();
        if (!Files.isDirectory(tmpDir)) {
            return names;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tmpDir, "solon-ai-script-*")) {
            for (Path p : stream) {
                names.add(p.getFileName().toString());
            }
        }
        return names;
    }
}
