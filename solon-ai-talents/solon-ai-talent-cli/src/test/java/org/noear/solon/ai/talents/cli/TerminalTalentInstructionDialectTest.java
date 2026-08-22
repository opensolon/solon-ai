package org.noear.solon.ai.talents.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.talents.mount.MountManager;

/**
 * 引导词（getInstruction）必须与实际 shell 方言一致。
 *
 * <p>背景：原实现只在「运行环境/终端类型」两行里写了平台名，但自我保护机制、严禁指令、
 * 参数示例三段全是硬编码的 Unix 词汇（{@code pkill}、{@code /etc}、{@code cat}），
 * 且 POWERSHELL 分支完全没有方言提示。模型据此推断自己在 Unix 环境，
 * 于是在 Windows 上生成 {@code uname -a 2>/dev/null | head -5} 这类必然失败的命令。
 *
 * <p>这些用例把「引导词不得向模型泄漏错误的平台先验」固化下来。
 */
public class TerminalTalentInstructionDialectTest {

    private String instructionOf(ShellMode mode, String shellCmd) throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-instruction-test");
        try {
            TerminalTalent talent = new TerminalTalent(
                    new MountManager(workDir.toString()),
                    new ShellCommandFactory(mode, shellCmd));
            return talent.getInstruction(null);
        } finally {
            Files.deleteIfExists(workDir);
        }
    }

    // ---------- PowerShell ----------

    @Test
    public void powershell_noUnixProcessCommands() throws Exception {
        String text = instructionOf(ShellMode.POWERSHELL, "powershell");

        assertFalse(text.contains("pkill"), "PowerShell 下不应出现 pkill");
        assertFalse(text.contains("killall"), "PowerShell 下不应出现 killall");
        assertFalse(text.contains("kill -9"), "PowerShell 下不应出现 kill -9");
        assertTrue(text.contains("taskkill") || text.contains("Stop-Process"),
                "PowerShell 下应给出 Windows 的进程终止写法");
    }

    @Test
    public void powershell_noUnixSystemPaths() throws Exception {
        String text = instructionOf(ShellMode.POWERSHELL, "powershell");

        // 「严禁指令」段不应把 /etc、/usr 当作真实存在的系统目录来举例
        assertFalse(text.contains("`/etc`, `/usr`"), "PowerShell 下不应把 /etc、/usr 当作本机系统目录");
        assertTrue(text.contains("C:\\Windows"), "PowerShell 下应改用 Windows 系统目录举例");
    }

    @Test
    public void powershell_declaresNotBashAndListsDialectRewrites() throws Exception {
        String text = instructionOf(ShellMode.POWERSHELL, "powershell");

        assertTrue(text.contains("**不是** bash/sh"), "必须显式点明当前 shell 不是 bash");
        // 负面清单 + 等价写法必须成对出现，否则模型仍会退回 Unix 习惯用法
        assertTrue(text.contains("2>$null"), "应给出 2>/dev/null 的 PowerShell 等价写法");
        assertTrue(text.contains("Select-Object -First"), "应给出 head 的等价写法");
        assertTrue(text.contains("Get-Content"), "应给出 cat 的等价写法");
        assertTrue(text.contains("Select-String"), "应给出 grep 的等价写法");
        assertTrue(text.contains("Get-ComputerInfo"), "应给出 uname / os-release 的等价写法");
        assertTrue(text.contains("`/dev/null`"), "应显式声明 /dev/null 不存在");
    }

    @Test
    public void powershell_fileExampleUsesAvailableCommand() throws Exception {
        String text = instructionOf(ShellMode.POWERSHELL, "powershell");

        assertFalse(text.contains("cat \"my file.txt\""), "PowerShell 下 cat 示例会被直接照抄");
        assertTrue(text.contains("Get-Content \"my file.txt\""), "应改为 Get-Content 示例");
    }

    // ---------- CMD ----------

    @Test
    public void cmd_usesCmdDialect() throws Exception {
        String text = instructionOf(ShellMode.CMD, "cmd");

        assertFalse(text.contains("pkill"), "CMD 下不应出现 pkill");
        assertTrue(text.contains("**不是** bash/sh"), "必须显式点明当前 shell 不是 bash");
        assertTrue(text.contains("2>nul"), "应给出 2>/dev/null 的 CMD 等价写法");
        assertTrue(text.contains("findstr"), "应给出 grep 的等价写法");
        assertTrue(text.contains("type \"my file.txt\""), "应改为 type 示例");
        // 原有的 % 语义说明不能在重构中丢失
        assertTrue(text.contains("%%i"), "CMD 的 % 语义说明必须保留");
    }

    // ---------- Unix ----------

    /**
     * 「工具优先」规约必须对三种 shell 都输出。
     *
     * <p>背景：原实现只在 POWERSHELL / CMD 两个方言分支里提了一句「优先用 read / grep / glob / ls」，
     * UNIX_SHELL 分支完全没有，等于在 Linux/macOS 上默许模型用 {@code cat} / {@code find} / {@code sed -i}
     * 代劳 read / glob / edit，从而绕开工具层的行号定位、忽略目录、分页截断与写入原子回滚。
     */
    @Test
    public void allShells_declareToolFirstRule() throws Exception {
        for (String[] it : new String[][]{{"POWERSHELL", "powershell"}, {"CMD", "cmd"}, {"UNIX_SHELL", "bash"}}) {
            String text = instructionOf(ShellMode.valueOf(it[0]), it[1]);

            assertTrue(text.contains("**工具优先"), it[0] + " 下必须给出工具优先规约");
            assertTrue(text.contains("`read` / `ls` / `glob` / `grep` / `write` / `edit`"),
                    it[0] + " 下必须完整列出应优先使用的工具（含 write / edit）");
        }
    }

    /**
     * 工具优先规约里的「不要这么写」示例必须按方言给出：在 CMD 下举 {@code cat} / {@code sed -i}
     * 会被模型当作本机可用命令，反而泄漏错误的平台先验。
     */
    @Test
    public void toolFirstRule_negativeExamplesFollowShellDialect() throws Exception {
        String ps = instructionOf(ShellMode.POWERSHELL, "powershell");
        String cmd = instructionOf(ShellMode.CMD, "cmd");
        String unix = instructionOf(ShellMode.UNIX_SHELL, "bash");

        assertTrue(ps.contains("`Set-Content`"), "PowerShell 下应以 Set-Content 等命令作为负面示例");
        assertTrue(cmd.contains("`findstr`"), "CMD 下应以 findstr 等命令作为负面示例");
        assertFalse(cmd.contains("`sed -i`"), "CMD 下不应出现 sed -i");
        assertTrue(unix.contains("`sed -i`"), "Unix 下应以 sed -i 等命令作为负面示例");
    }

    @Test
    public void unix_keepsPosixGuidance() throws Exception {
        String text = instructionOf(ShellMode.UNIX_SHELL, "bash");

        assertTrue(text.contains("pkill java"), "Unix 下应保留原有的 pkill 保护说明");
        assertTrue(text.contains("`/etc`, `/usr`"), "Unix 下应保留原有的系统目录说明");
        assertTrue(text.contains("cat \"my file.txt\""), "Unix 下应保留 cat 示例");
        assertFalse(text.contains("2>$null"), "Unix 下不应出现 PowerShell 方言");
        assertFalse(text.contains("2>nul"), "Unix 下不应出现 CMD 方言");
    }

    // ---------- 环境变量占位符 ----------

    /**
     * 预置的 PYTHON/NODE 变量必须按方言给出占位符（CMD 为 {@code %PYTHON%}、
     * PowerShell 为 {@code $env:PYTHON}）。原实现硬编码 {@code $PYTHON}，
     * 在 Windows 上会被展开为空字符串。
     */
    @Test
    public void envPlaceholderFollowsShellDialect() throws Exception {
        String ps = instructionOf(ShellMode.POWERSHELL, "powershell");
        String cmd = instructionOf(ShellMode.CMD, "cmd");

        // 仅在探测到运行时时才会输出该行；未探测到则跳过断言（不同 CI 机器环境不一致）
        if (ps.contains("Python 命令")) {
            assertTrue(ps.contains("$env:PYTHON"), "PowerShell 下应为 $env:PYTHON");
            assertFalse(ps.contains("`$PYTHON`"), "PowerShell 下不应为 $PYTHON");
        }
        if (cmd.contains("Python 命令")) {
            assertTrue(cmd.contains("%PYTHON%"), "CMD 下应为 %PYTHON%");
        }
    }
    // ---------- 编码规约 ----------

    /**
     * 启动时已注入 UTF-8 前置（{@code ShellCommandFactory.POWERSHELL_PREAMBLE}），引导词必须如实告知：
     * 模型不知道的话会自己再叠一层 {@code chcp 65001} 或到处补 {@code -Encoding UTF8}；
     * 更要紧的是必须告知唯一的例外（真正的 GBK 文件需 {@code -Encoding Default}），
     * 否则遇到 GBK 文件时它无从下手。
     */
    @Test
    public void powershell_declaresEncodingContract() throws Exception {
        String text = instructionOf(ShellMode.POWERSHELL, "powershell");

        assertTrue(text.contains("UTF-8"), "应说明默认编码已统一为 UTF-8");
        assertTrue(text.contains("-Encoding Default"), "必须给出读 GBK/ANSI 文件的例外写法");
        assertTrue(text.contains("BOM"), "5.1 写入带 BOM 这一事实应说明（本工具链会自动忽略）");
    }

    /**
     * CMD 侧无法在不破坏 {@code %} 语义的前提下注入 UTF-8 前置，因此只能明确劝退：
     * {@code type} / {@code findstr} 读 UTF-8 文件必乱码，且没有可靠的命令行解法。
     */
    @Test
    public void cmd_warnsFileReadingIsUnfixable() throws Exception {
        String text = instructionOf(ShellMode.CMD, "cmd");

        assertTrue(text.contains("乱码"), "CMD 下必须提示读文件会乱码");
        assertTrue(text.contains("read") && text.contains("grep"), "必须指向可靠替代：read / grep 工具");
    }

    /**
     * pwsh 7+ 支持 {@code &&} / {@code ||}：把 5.1 的限制当作它的限制会无故降级可用能力。
     */
    @Test
    public void powerShellCoreGetsItsOwnCapabilityDescription() throws Exception {
        String legacy = instructionOf(ShellMode.POWERSHELL, "powershell");
        String core = instructionOf(ShellMode.POWERSHELL, "pwsh");

        assertTrue(legacy.contains("5.1 不支持"), "5.1 下应声明不支持 && / ||");
        assertFalse(core.contains("5.1 不支持"), "pwsh 下不应套用 5.1 的限制");
        assertTrue(core.contains("PowerShell 7+"), "应如实标明版本: " + core);
    }

    @Test
    public void unix_hasNoWindowsEncodingSection() throws Exception {
        String text = instructionOf(ShellMode.UNIX_SHELL, "bash");

        assertFalse(text.contains("-Encoding Default"), "Unix 下不应出现 PowerShell 的编码参数");
        assertFalse(text.contains("PSDefaultParameterValues"), "Unix 下不应出现 PowerShell 内部机制");
    }

    // ---------- 别名陷阱 / stderr 已合流 ----------

    /**
     * PowerShell 里 {@code ls}/{@code rm}/{@code cp}/{@code ps} 确实存在（cmdlet 别名），但不接受 Unix 短参数。
     *
     * <p>这比「命令不存在」更难自教：{@code rm -rf dir} 报的是参数绑定错误，模型往往认为是自己引号
     * 或转义写错了，于是反复重试同一写法。</p>
     */
    @Test
    public void powershell_warnsUnixAliasArgumentTrap() throws Exception {
        String legacy = instructionOf(ShellMode.POWERSHELL, "powershell");
        String core = instructionOf(ShellMode.POWERSHELL, "pwsh");

        assertTrue(legacy.contains("rm -rf"), "必须点名 rm -rf 这类必错写法");
        assertTrue(legacy.contains("Remove-Item -Recurse -Force"), "必须给出 rm -rf 的等价写法");
        assertTrue(legacy.contains("ps aux") && legacy.contains("Get-Process"), "必须给出 ps aux 的等价写法");
        // curl 需分版本：5.1 是 Invoke-WebRequest 的别名（Unix 参数必错），7+ 已恢复为真 curl
        assertTrue(legacy.contains("curl.exe"), "5.1 下必须告知真 curl 要写 curl.exe");
        assertFalse(core.contains("curl.exe -s"), "pwsh 下不应再要求写 curl.exe");
    }

    /**
     * stderr 已由 Java 层 {@code redirectErrorStream(true)} 合流，命令里再写 {@code 2>&1} 在 PowerShell 下
     * 会把原生程序的 stderr 转成错误记录：多出几行 NativeCommandError 噪声，且退出码变 1。
     * 实测：{@code mvn test 2>&1 | Select-String ...} 在 BUILD SUCCESS 的同时报出 {@code [exit_code=1]}。
     */
    @Test
    public void windowsShells_declareStderrAlreadyMerged() throws Exception {
        String ps = instructionOf(ShellMode.POWERSHELL, "powershell");
        String cmd = instructionOf(ShellMode.CMD, "cmd");
        String unix = instructionOf(ShellMode.UNIX_SHELL, "bash");

        assertTrue(ps.contains("2>&1"), "PowerShell 下必须明确劫退 2>&1");
        assertTrue(ps.contains("exit_code=1"), "必须告知退出码会被误报为 1");
        assertTrue(cmd.contains("2>&1"), "CMD 下也应说明 stderr 已合流");
        assertFalse(unix.contains("2>&1"), "Unix 下 `2>&1` 无害且习惯，不应无故增加约束");
    }
}
