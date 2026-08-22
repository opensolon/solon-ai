package org.noear.solon.ai.talents.cli;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.noear.solon.Utils;
import org.noear.solon.ai.talents.mount.MountManager;

/**
 * Windows 侧自保护护栏：{@code taskkill} / {@code Stop-Process} / 盘符根删除。
 *
 * <p>背景：原实现的自保护只认 {@code kill|pkill|killall} 与 {@code rm -rf /}，
 * 在 Windows 上全部形同虚设——{@code taskkill /F /PID <pid>} 的 PID 前面隔着含 {@code /} 的开关
 * （不属于旧正则的 {@code [\s\w]}），而 {@code Stop-Process -Id <pid>} 连 {@code kill} 字样都没有。
 * 更糟的是引导词已向模型明文承诺「严禁 taskkill /IM java.exe、Stop-Process -Name java」，
 * 即承诺了一个不存在的护栏，两个平台的自保护强度并不对等。
 *
 * <p>这些用例把「Unix 与 Windows 的两条红线强度一致」固化下来，同时守住不误伤边界。
 */
public class TerminalSupportWindowsGuardTest {

    private static final String PID = Utils.pid();

    private TerminalSupport supportOf(ShellMode mode) throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-guard-test");
        try {
            return new TerminalSupport(
                    new MountManager(workDir.toString()), Collections.emptySet(), mode);
        } finally {
            Files.deleteIfExists(workDir);
        }
    }

    // ---------- 自保护：指向宿主 PID ----------

    @Test
    public void powershell_blocksTaskkillByHostPid() throws Exception {
        TerminalSupport support = supportOf(ShellMode.POWERSHELL);
        assertNotNull(support.validateCommandNoKill("taskkill /F /PID " + PID),
                "taskkill /F /PID <宿主PID> 必须拦截");
        assertNotNull(support.validateCommandNoKill("taskkill /PID " + PID + " /T"),
                "taskkill /PID <宿主PID> /T 必须拦截");
    }

    @Test
    public void powershell_blocksStopProcessByHostId() throws Exception {
        TerminalSupport support = supportOf(ShellMode.POWERSHELL);
        assertNotNull(support.validateCommandNoKill("Stop-Process -Id " + PID),
                "Stop-Process -Id <宿主PID> 必须拦截");
        assertNotNull(support.validateCommandNoKill("Stop-Process -Force -Id " + PID),
                "带其它开关时同样必须拦截");
    }

    @Test
    public void powershell_blocksKillJavaByName() throws Exception {
        TerminalSupport support = supportOf(ShellMode.POWERSHELL);
        assertNotNull(support.validateCommandNoKill("Stop-Process -Name java"),
                "按进程名批量杀 java 必须拦截");
        assertNotNull(support.validateCommandNoKill("taskkill /IM java.exe /F"),
                "taskkill /IM java.exe 必须拦截（引导词已明文承诺）");
        assertNotNull(support.validateCommandNoKill("taskkill /IM javaw.exe"),
                "javaw 同样是 JVM 进程");
        assertNotNull(support.validateCommandNoKill("Get-Process java | Stop-Process -Force"),
                "管道式批量终止必须拦截：终止动词后面没有任何参数，只能靠进程名判定");
    }

    @Test
    public void cmd_appliesSameGuard() throws Exception {
        TerminalSupport support = supportOf(ShellMode.CMD);
        assertNotNull(support.validateCommandNoKill("taskkill /F /PID " + PID),
                "CMD 与 PowerShell 的护栏强度必须一致");
        assertNotNull(support.validateCommandNoKill("taskkill /IM java.exe"),
                "CMD 与 PowerShell 的护栏强度必须一致");
    }

    @Test
    public void windowsGuard_doesNotBreakLegitProcessCleanup() throws Exception {
        TerminalSupport support = supportOf(ShellMode.POWERSHELL);
        // 终止自己起的其它子进程是引导词明确建议的做法，不能拦
        assertNull(support.validateCommandNoKill("Stop-Process -Id 999999"),
                "终止非宿主 PID 不应拦截");
        assertNull(support.validateCommandNoKill("taskkill /IM node.exe /F"),
                "终止 node 进程不应拦截");
        assertNull(support.validateCommandNoKill("Get-Process | Select-Object -First 5"),
                "只是查看进程列表不应拦截");
    }

    // ---------- 高危删除：盘符根 / 系统目录 ----------

    @Test
    public void powershell_blocksDriveRootDeletion() throws Exception {
        TerminalSupport support = supportOf(ShellMode.POWERSHELL);
        assertNotNull(support.validateCommandNoKill("Remove-Item -Recurse -Force C:\\"),
                "Remove-Item -Recurse -Force C:\\ 是 rm -rf / 的 Windows 等价物");
        assertNotNull(support.validateCommandNoKill("Remove-Item -Recurse -Force 'D:/'"),
                "正斜杠与引号写法同样必须拦截");
        assertNotNull(support.validateCommandNoKill("ri -Recurse -Force C:\\Windows"),
                "系统目录删除必须拦截（ri 是 Remove-Item 的别名）");
        assertNotNull(support.validateCommandNoKill("Remove-Item -Recurse \"C:\\Program Files\\x\""),
                "Program Files 删除必须拦截");
    }

    @Test
    public void cmd_blocksRdAndDelOnCriticalTargets() throws Exception {
        TerminalSupport support = supportOf(ShellMode.CMD);
        assertNotNull(support.validateCommandNoKill("rd /s /q C:\\"), "rd /s /q C:\\ 必须拦截");
        assertNotNull(support.validateCommandNoKill("rmdir /s /q C:\\Windows"), "rmdir 系统目录必须拦截");
        assertNotNull(support.validateCommandNoKill("del /f /s /q C:\\Users"), "del 用户目录必须拦截");
    }

    @Test
    public void windowsDeleteGuard_usesEnvVariantsToo() throws Exception {
        TerminalSupport support = supportOf(ShellMode.CMD);
        // 只匹配字面量的话，一个环境变量就能绕过
        assertNotNull(support.validateCommandNoKill("rd /s /q %SystemRoot%"),
                "%SystemRoot% 写法必须拦截");
        assertNotNull(support.validateCommandNoKill("Remove-Item -Recurse -Force $env:windir"),
                "$env:windir 写法必须拦截");
    }

    @Test
    public void windowsDeleteGuard_allowsWorkspaceCleanup() throws Exception {
        TerminalSupport support = supportOf(ShellMode.POWERSHELL);
        // 清理构建产物是最常见的正当操作，误伤它等于让工具不可用
        assertNull(support.validateCommandNoKill("Remove-Item -Recurse -Force target"),
                "清理相对路径不应拦截");
        assertNull(support.validateCommandNoKill("Remove-Item -Recurse -Force .\\target\\classes"),
                "清理相对路径不应拦截");
        assertNull(support.validateCommandNoKill("rd /s /q build"), "清理相对路径不应拦截");
        assertNull(support.validateCommandNoKill("Get-ChildItem C:\\Windows"),
                "只读操作即使指向系统目录也不应拦截（没有删除动词）");
    }

    // ---------- Unix 分支不得受影响 ----------

    @Test
    public void unix_behaviourUnchanged() throws Exception {
        TerminalSupport support = supportOf(ShellMode.UNIX_SHELL);

        // 原有拦截保持不变（含旧实现漏掉的带信号写法：`kill -9`、`killall -9` 之间夹了 `-` 开关）
        assertNotNull(support.validateCommandNoKill("kill " + PID));
        assertNotNull(support.validateCommandNoKill("kill -9 " + PID));
        assertNotNull(support.validateCommandNoKill("kill -TERM " + PID));
        assertNotNull(support.validateCommandNoKill("pkill java"));
        assertNotNull(support.validateCommandNoKill("killall java"));
        assertNotNull(support.validateCommandNoKill("killall -9 java"));
        assertNotNull(support.validateCommandNoKill("rm -rf /"));
        assertNotNull(support.validateCommandNoKill("echo a; exit"));

        // pkill -P <宿主PID> 只终止子进程，是引导词明确推荐的清理方式，不能误伤
        assertNull(support.validateCommandNoKill("pkill -P " + PID),
                "pkill -P <宿主PID> 只杀子进程，必须放行");
        // 跨命令段的同名数字只是被打印的数字，与终止目标无关
        assertNull(support.validateCommandNoKill("kill 111; echo " + PID),
                "跨命令段不应误判");
        assertNull(support.validateCommandNoKill("kill -9 999999"), "终止非宿主 PID 不应拦截");

        // Windows 专属规则不得泄漏到 Unix：这些在 Unix 上是普通字符串，不该被拦
        assertNull(support.validateCommandNoKill("echo 'Stop-Process -Name java'"),
                "Unix 下不应套用 Windows 规则");
        assertNull(support.validateCommandNoKill("rm -rf target"), "Unix 下清理相对路径不应拦截");
        assertNull(support.validateCommandNoKill("ls -la"));
    }

    @Test
    public void emptyCommandRejectedOnAllModes() throws Exception {
        for (ShellMode mode : ShellMode.values()) {
            assertNotNull(supportOf(mode).validateCommandNoKill(""), mode + " 空命令应报错");
            assertNotNull(supportOf(mode).validateCommandNoKill(null), mode + " null 命令应报错");
        }
    }
}
