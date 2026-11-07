package org.noear.solon.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.mount.MountManager;
import org.noear.solon.ai.sandbox.config.FilesystemConfig;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 宿主状态目录（sessions / memory 等）保护：文件工具读写都拒绝；OS 沙盒 denyRead + denyWrite 同口径。
 * 以及程序侧 exec 的结构化结果与超时。
 */
class TerminalTalentStateProtectionTest {

    private static TerminalTalent newTalent(Path ws, Path home) {
        return new TerminalTalent(new MountManager(ws.toString()), ShellCommandFactory.detect(),
                home.resolve(".solon/cache/mount"), ".solon/");
    }

    @Test
    void fileToolsCannotReadOrWriteStateDirs(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.createDirectories(ws.resolve(".solon/sessions/s1"));
        Files.write(ws.resolve(".solon/sessions/s1/TODO.md"), "secret".getBytes());

        TerminalTalent talent = newTalent(ws, tmp.resolve("home"));
        // 未设置时保持原有口径（可读）
        assertTrue(talent.read(".solon/sessions/s1/TODO.md", null, null, ws.toString()).contains("secret"));

        talent.setProtectedStatePaths(Arrays.asList("sessions", "memory/"));
        SecurityException r = assertThrows(SecurityException.class,
                () -> talent.read(".solon/sessions/s1/TODO.md", null, null, ws.toString()));
        assertTrue(r.getMessage().contains("路径受保护"), r.getMessage());
        assertThrows(SecurityException.class,
                () -> talent.write(".solon/memory/x.md", "x", ws.toString()));
        // 其它子目录不受影响
        talent.write(".solon/skills/ok.md", "ok", ws.toString());
    }

    @Test
    void invalidStatePathRejected(@TempDir Path tmp) {
        TerminalTalent talent = newTalent(tmp, tmp.resolve("home"));
        assertThrows(IllegalArgumentException.class, () -> talent.setProtectedStatePaths(Arrays.asList("../x")));
        assertThrows(IllegalArgumentException.class, () -> talent.setProtectedStatePaths(Arrays.asList(" ")));
    }

    @Test
    void osSandboxDeniesReadAndWriteOfStateDirs(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws")).toAbsolutePath().normalize();
        TerminalTalent talent = newTalent(ws, tmp.resolve("home"));
        talent.setProtectedStatePaths(Arrays.asList("sessions"));

        Method m = TerminalTalent.class.getDeclaredMethod("buildDynamicFilesystemConfig", Path.class);
        m.setAccessible(true);
        FilesystemConfig fs = (FilesystemConfig) m.invoke(talent, (Path) null);
        String p = ws.resolve(".solon/sessions").toString();
        assertTrue(fs.getDenyRead().contains(p), String.valueOf(fs.getDenyRead()));
        assertTrue(fs.getDenyWrite().contains(p));
    }

    @Test
    void execReturnsStructuredResultAndTimesOut(@TempDir Path tmp) throws Exception {
        if (ShellCommandFactory.detect().isWindowsShell()) {
            return;
        }
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        TerminalTalent talent = newTalent(ws, tmp.resolve("home"));
        talent.setSandboxEnabled(false); // 只验证执行链语义，不依赖本机 OS 沙盒

        CommandResult ok = talent.exec("echo hi", null, 10_000, 1000);
        assertEquals(CommandResult.Status.COMPLETED, ok.getStatus());
        assertTrue(ok.isSuccess());
        assertTrue(ok.getText().contains("hi"));

        CommandResult bad = talent.exec("sh -c 'echo no; false'", null, 10_000, 1000);
        assertEquals(CommandResult.Status.COMPLETED, bad.getStatus());
        assertNotEquals(0, bad.getExitCode());
        assertFalse(bad.isSuccess());

        long t0 = System.currentTimeMillis();
        CommandResult slow = talent.exec("sleep 30", null, 500, 1000);
        assertEquals(CommandResult.Status.TIMED_OUT, slow.getStatus());
        assertTrue(System.currentTimeMillis() - t0 < 10_000, "超时后必须结束进程");

        assertThrows(IllegalArgumentException.class, () -> talent.exec("echo", null, 0, 10));
    }

    @Test
    void outputOverPhysicalLimitIsFailedNotCompleted(@TempDir Path tmp) {
        if (ShellCommandFactory.detect().isWindowsShell()) {
            return;
        }
        ProcessExecutor executor = new ProcessExecutor();
        executor.setMaxOutputSize(1024);

        CommandResult r = executor.executeCmdResult(tmp, java.util.Arrays.asList("sh", "-c", "yes"),
                new java.util.HashMap<>(), 10_000, 2000, null);
        assertEquals(CommandResult.Status.FAILED, r.getStatus(), "被上限终止不是自然结束");
        assertTrue(r.getText().contains("物理上限"), r.getText());
    }

    @Test
    void notBootstrappedSandboxRefusesWithoutReset(@TempDir Path tmp) throws Exception {
        if (SandboxBootstrap.isReady()) {
            return; // 其它用例已初始化进程级沙盒，此场景不可构造
        }
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        TerminalTalent talent = newTalent(ws, tmp.resolve("home"));
        talent.setSandboxEnabled(true);
        talent.setSandboxSystemRestrict(true);
        talent.setSandboxSelfBootstrap(false);

        CommandResult r = talent.exec("echo hi", null, 5_000, 100);
        assertEquals(CommandResult.Status.FAILED, r.getStatus());
        assertTrue(r.getText().contains("not bootstrapped"), r.getText());
        assertFalse(SandboxBootstrap.isReady(), "不得自行初始化进程级沙盒");
        assertTrue(talent.bash("echo hi", 5_000, 100, null).contains("not bootstrapped"));
    }
}
