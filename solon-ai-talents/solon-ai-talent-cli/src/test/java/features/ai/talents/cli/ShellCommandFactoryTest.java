package features.ai.talents.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.talents.cli.ShellCommandFactory;
import org.noear.solon.ai.talents.cli.ShellMode;

/**
 * 统一 shell 启动契约：bash / bash_start 共用拼装结果。
 */
public class ShellCommandFactoryTest {

    @Test
    public void build_unixUsesLoginShellC() {
        ShellCommandFactory factory = new ShellCommandFactory(ShellMode.UNIX_SHELL, "bash");
        List<String> cmd = factory.build("echo hi");

        assertEquals(3, cmd.size());
        assertEquals("bash", cmd.get(0));
        assertEquals("-lc", cmd.get(1));
        assertEquals("echo hi", cmd.get(2));
    }

    @Test
    public void build_cmdUsesSlashC() {
        ShellCommandFactory factory = new ShellCommandFactory(ShellMode.CMD, "cmd");
        List<String> cmd = factory.build("echo hi");

        assertEquals(Arrays.asList("cmd", "/c", "echo hi"), cmd);
    }

    @Test
    public void build_powershellUsesCommandFlag() {
        ShellCommandFactory factory = new ShellCommandFactory(ShellMode.POWERSHELL, "powershell");
        List<String> cmd = factory.build("Write-Output hi");

        assertEquals(Arrays.asList("powershell", "-Command", "Write-Output hi"), cmd);
    }

    @Test
    public void build_rejectsNullCommand() {
        ShellCommandFactory factory = new ShellCommandFactory(ShellMode.UNIX_SHELL, "bash");
        assertThrows(IllegalArgumentException.class, () -> factory.build(null));
    }

    @Test
    public void detect_returnsCurrentOsFactory() {
        ShellCommandFactory factory = ShellCommandFactory.detect();
        List<String> cmd = factory.build("echo detect-ok");

        assertEquals(3, cmd.size());
        assertEquals("echo detect-ok", cmd.get(2));
        if (isWindows()) {
            assertTrue(factory.getShellMode() == ShellMode.CMD || factory.getShellMode() == ShellMode.POWERSHELL);
            assertTrue("/c".equals(cmd.get(1)) || "-Command".equals(cmd.get(1)), "windows flag: " + cmd);
        } else {
            assertEquals(ShellMode.UNIX_SHELL, factory.getShellMode());
            assertEquals("-lc", cmd.get(1));
            assertTrue("bash".equals(cmd.get(0)) || "/bin/sh".equals(cmd.get(0)), "unix shell: " + cmd);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
