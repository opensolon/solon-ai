package org.noear.solon.ai.talents.cli;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

        // Windows 专属规则不得泄漏到类 Unix 宿主：这些在 Unix 上是普通字符串，不该被拦。
        // 但 Windows 宿主上即使方言是 UNIX_SHELL（Git Bash 类会话），taskkill / Stop-Process 仍然
        // 真能杀掉宿主 JVM，护栏必须生效，此处不适用——见 windowsGuard_appliesOnWindowsHostEvenInUnixDialect。
        if (EnvironmentResolver.isWindows() == false) {
            assertNull(support.validateCommandNoKill("echo 'Stop-Process -Name java'"),
                    "类 Unix 宿主下不应套用 Windows 规则");
        }
        assertNull(support.validateCommandNoKill("rm -rf target"), "Unix 下清理相对路径不应拦截");
        assertNull(support.validateCommandNoKill("ls -la"));
    }

    /**
     * 红线适用范围的判据是「宿主 OS 是 Windows」，不是「shell 方言是 Windows 系」。
     *
     * <p>旧实现只看 {@code shellMode == CMD || POWERSHELL}，于是 Windows 宿主上一旦方言被定为
     * {@link ShellMode#UNIX_SHELL}，{@code taskkill /IM java.exe} 与
     * {@code Remove-Item -Recurse -Force C:\} 两条红线整体失效——而这两条命令在那种会话里
     * 照样能执行并杀掉宿主 JVM。本用例把修正后的语义固定下来。</p>
     *
     * <p>只能在 Windows 宿主上验证（{@code os.name} 不可注入），其它平台跳过。</p>
     */
    @Test
    public void windowsGuard_appliesOnWindowsHostEvenInUnixDialect() throws Exception {
        assumeTrue(EnvironmentResolver.isWindows(), "仅 Windows 宿主适用");

        TerminalSupport support = supportOf(ShellMode.UNIX_SHELL);
        assertNotNull(support.validateCommandNoKill("taskkill /F /PID " + PID),
                "Windows 宿主 + UNIX_SHELL 方言下，taskkill 仍能杀宿主，必须拦截");
        assertNotNull(support.validateCommandNoKill("taskkill /IM java.exe"),
                "按进程名批量杀 java 必须拦截");
        assertNotNull(support.validateCommandNoKill("Remove-Item -Recurse -Force C:\\"),
                "盘符根删除必须拦截");

        // 不得因为放宽适用范围而误伤日常清理
        assertNull(support.validateCommandNoKill("rm -rf target"), "清理相对路径不应拦截");
        assertNull(support.validateCommandNoKill("taskkill /IM node.exe /F"), "终止 node 不应拦截");
    }

    // ---------- Unix rm 护栏：开关形态、引号、目标粒度 ----------

    /**
     * 递归 + 强制的各种写法都必须命中。
     *
     * <p>旧正则 {@code .*rm\s+.*-[rR].*f\s+/.*} 要求 r 出现在 f 之前且同处一个 token，
     * 于是 {@code -fr}、{@code -r -f} 直接漏；而只枚举短选项组合的话，
     * {@code --recursive --force} 这种合法 GNU 写法一个长选项就能绕过整条护栏。</p>
     */
    @Test
    public void unix_blocksEveryRecursiveForceFlagForm() throws Exception {
        TerminalSupport support = supportOf(ShellMode.UNIX_SHELL);
        for (String cmd : new String[]{
                "rm -rf /", "rm -fr /", "rm -Rf /", "rm -r -f /", "rm -f -r /",
                "rm -rf /*", "rm --recursive --force /", "rm --force --recursive /",
                "rm -r --force /", "rm --recursive -f /", "rm -rfv /", "sudo rm -rf /",
                "rm -rf --no-preserve-root /", "echo hi; rm -rf /"}) {
            assertNotNull(support.validateCommandNoKill(cmd), cmd + " 必须拦截");
        }
    }

    /**
     * 目标被引号包起来时同样必须命中：危害与裸写法完全相同，
     * 而 {@code \s+/} 会因为中间夹了一个引号而整条不命中。
     */
    @Test
    public void unix_blocksQuotedCriticalTargets() throws Exception {
        TerminalSupport support = supportOf(ShellMode.UNIX_SHELL);
        assertNotNull(support.validateCommandNoKill("rm -rf \"/\""), "双引号包裹的根目录必须拦截");
        assertNotNull(support.validateCommandNoKill("rm -rf '/'"), "单引号包裹的根目录必须拦截");
        assertNotNull(support.validateCommandNoKill("rm -rf '/usr'"), "单引号包裹的系统目录必须拦截");
    }

    /**
     * 系统目录本身拦、其子路径放行。这是本次收窄的核心：
     * 子路径清理（构建缓存、Homebrew 区、临时目录）是日常操作，全拦等于工具不可用。
     */
    @Test
    public void unix_allowsSubPathCleanupUnderSystemDirs() throws Exception {
        TerminalSupport support = supportOf(ShellMode.UNIX_SHELL);
        assertNotNull(support.validateCommandNoKill("rm -rf /usr"), "系统目录自身必须拦截");
        assertNotNull(support.validateCommandNoKill("rm -rf /var/"), "带尾部斜杠的系统目录自身必须拦截");

        assertNull(support.validateCommandNoKill("rm -rf /tmp/build"), "临时目录子路径应放行");
        assertNull(support.validateCommandNoKill("rm -rf /var/folders/xx/T/zz"),
                "macOS 临时目录（已在白名单内）应放行");
        assertNull(support.validateCommandNoKill("rm -rf /usr/local/lib/node_modules/x"),
                "Homebrew 区子路径应放行");
        assertNull(support.validateCommandNoKill("rm -rf ./target"), "相对路径应放行");
        assertNull(support.validateCommandNoKill("rm -f /tmp/x.lock"), "非递归删单文件应放行");
    }

    /**
     * {@code ~} / {@code $HOME} 也是临界目标。
     *
     * <p>校验跑在 {@code translateCommandToEnv} 之前，看到的是未展开的原文，
     * 所以 {@code rm -rf ~} 不会命中 {@code /home} 字面量；而 Windows 侧的
     * {@code %USERPROFILE%} 是拦的，两侧强度必须一致。同样只拦目录自身。</p>
     */
    @Test
    public void unix_blocksHomeItselfButAllowsHomeSubPaths() throws Exception {
        TerminalSupport support = supportOf(ShellMode.UNIX_SHELL);
        assertNotNull(support.validateCommandNoKill("rm -rf ~"), "删整个 home 必须拦截");
        assertNotNull(support.validateCommandNoKill("rm -rf ~/"), "带尾部斜杠同样必须拦截");
        assertNotNull(support.validateCommandNoKill("rm -rf $HOME"), "$HOME 写法必须拦截");
        assertNotNull(support.validateCommandNoKill("rm -rf ${HOME}"), "${HOME} 写法必须拦截");
        assertNotNull(support.validateCommandNoKill("rm -rf \"$HOME\""), "带引号的 $HOME 必须拦截");
        assertNotNull(support.validateCommandNoKill("rm -rf /home"), "/home 自身必须拦截");

        assertNull(support.validateCommandNoKill("rm -rf ~/.gradle/caches"),
                "清理 home 下的构建缓存是日常操作，必须放行");
        assertNull(support.validateCommandNoKill("rm -rf $HOME/.m2/repository/x"), "必须放行");
        assertNull(support.validateCommandNoKill("rm -rf /home/bob/tmp"), "必须放行");
    }

    /**
     * Windows 侧的对称约定：用户目录只拦自身，子路径放行。
     * 连子路径一起拦会误伤 {@code $env:USERPROFILE\.gradle\caches} 这类日常清理。
     */
    @Test
    public void windows_blocksUserProfileItselfButAllowsSubPaths() throws Exception {
        TerminalSupport support = supportOf(ShellMode.POWERSHELL);
        assertNotNull(support.validateCommandNoKill("Remove-Item -Recurse -Force $env:USERPROFILE"),
                "删整个用户目录必须拦截");
        assertNotNull(support.validateCommandNoKill("rd /s /q %USERPROFILE%"),
                "%USERPROFILE% 写法必须拦截");
        assertNotNull(support.validateCommandNoKill("Remove-Item -Recurse -Force C:\\Users\\"),
                "C:\\Users 自身必须拦截");

        assertNull(support.validateCommandNoKill("Remove-Item -Recurse -Force $env:USERPROFILE\\.gradle\\caches"),
                "清理 home 下的构建缓存必须放行（与 Unix 侧 rm -rf ~/.gradle/caches 对等）");
        assertNull(support.validateCommandNoKill("del %USERPROFILE%\\Downloads\\x.zip"),
                "删单个文件必须放行");
        assertNull(support.validateCommandNoKill("Remove-Item -Recurse -Force C:\\Users\\bob\\tmp"),
                "用户目录子路径必须放行");
    }

    @Test
    public void emptyCommandRejectedOnAllModes() throws Exception {
        for (ShellMode mode : ShellMode.values()) {
            assertNotNull(supportOf(mode).validateCommandNoKill(""), mode + " 空命令应报错");
            assertNotNull(supportOf(mode).validateCommandNoKill(null), mode + " null 命令应报错");
        }
    }
}
