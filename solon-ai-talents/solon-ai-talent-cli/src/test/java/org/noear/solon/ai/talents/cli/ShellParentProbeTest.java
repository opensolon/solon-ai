package org.noear.solon.ai.talents.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * 父 shell 探测的两半：由映像路径判定 shell 名（纯函数），以及从探测输出里解析祖先链。
 *
 * <p>探测本身改用外挂 PowerShell 查 WMI（不用 JDK 9 才有的 {@code ProcessHandle}，以便在 Java 8 上运行），
 * 因此真实祖先链依赖测试进程的启动方式（surefire fork ← mvn ← 某个 shell），只能断言「不抛、有界、可缓存」，
 * 具体取值交由上面两个纯函数覆盖。
 */
public class ShellParentProbeTest {

    // ================= 由映像路径判定 shell 名 =================

    @Test
    public void shellNameOfCommand_recognizesThreeDialects() {
        assertEquals("powershell", ShellCommandFactory.shellNameOfCommand(
                "c:\\windows\\system32\\windowspowershell\\v1.0\\powershell.exe"));
        assertEquals("pwsh", ShellCommandFactory.shellNameOfCommand("c:\\program files\\powershell\\7\\pwsh.exe"));
        assertEquals("cmd", ShellCommandFactory.shellNameOfCommand("c:\\windows\\system32\\cmd.exe"));
    }

    /**
     * 权限受限时 WMI 的 {@code ExecutablePath} 为空，退用 {@code Name}——裸映像名也必须能判定，
     * 否则「探测成功但全判不出」会静默退化成默认 PowerShell。
     */
    @Test
    public void shellNameOfCommand_acceptsBareImageName() {
        assertEquals("powershell", ShellCommandFactory.shellNameOfCommand("powershell.exe"));
        assertEquals("pwsh", ShellCommandFactory.shellNameOfCommand("pwsh.exe"));
        assertEquals("cmd", ShellCommandFactory.shellNameOfCommand("cmd.exe"));
        assertEquals("cmd", ShellCommandFactory.shellNameOfCommand("cmd"));
    }

    /**
     * 自定义安装路径（如 {@code d:\powershell\7\pwsh.exe}）会同时含 powershell 与 pwsh 两个关键字，
     * 必须判成 pwsh：降级成 5.1 会让引导词描述的语法能力也跟着错。
     */
    @Test
    public void shellNameOfCommand_prefersPwshWhenBothKeywordsPresent() {
        assertEquals("pwsh", ShellCommandFactory.shellNameOfCommand("d:\\powershell\\7\\pwsh.exe"));
    }

    @Test
    public void shellNameOfCommand_returnsNullWhenUndecidable() {
        assertNull(ShellCommandFactory.shellNameOfCommand(null));
        assertNull(ShellCommandFactory.shellNameOfCommand(""));
        assertNull(ShellCommandFactory.shellNameOfCommand("c:\\windows\\explorer.exe"));
        assertNull(ShellCommandFactory.shellNameOfCommand("c:\\jdk\\bin\\java.exe"));
        // cmdlet 名字里含 cmd 不代表调用者是 CMD
        assertNull(ShellCommandFactory.shellNameOfCommand("c:\\tools\\cmdkey.exe"));
    }

    // ================= 探测输出解析 =================

    @Test
    public void parseAncestorCommands_keepsMarkedLinesInOrder() {
        List<String> commands = ShellCommandFactory.parseAncestorCommands(
                "P#C:\\JDK\\bin\\java.exe\r\nP#C:\\Windows\\System32\\cmd.exe\r\n");

        assertEquals(2, commands.size(), commands.toString());
        assertEquals("c:\\jdk\\bin\\java.exe", commands.get(0));
        assertEquals("c:\\windows\\system32\\cmd.exe", commands.get(1));
    }

    /**
     * stderr 被合并进 stdout，而 PowerShell 报错文本里可能出现 {@code powershell} 字样——
     * 一条错误信息就足以把「实际是 CMD」判成 PowerShell，因此无标记行必须全部丢弃。
     */
    @Test
    public void parseAncestorCommands_dropsUnmarkedErrorNoise() {
        String output = "Get-CimInstance : 拒绝访问\r\n"
                + "所在位置 行:1 字符: 1\r\n"
                + "+ powershell.exe -NoProfile ...\r\n"
                + "P#C:\\Windows\\System32\\cmd.exe\r\n";

        List<String> commands = ShellCommandFactory.parseAncestorCommands(output);

        assertEquals(1, commands.size(), commands.toString());
        assertEquals("cmd", ShellCommandFactory.shellNameOfCommand(commands.get(0)),
                "报错文本里的 powershell 字样不能污染判定");
    }

    @Test
    public void parseAncestorCommands_toleratesBomAndBlankLines() {
        List<String> commands = ShellCommandFactory.parseAncestorCommands(
                "\uFEFFP#powershell.exe\n\n   \nP#explorer.exe\n");

        assertEquals(2, commands.size(), commands.toString());
        assertEquals("powershell.exe", commands.get(0), "首行 BOM 不应吃掉行标记");
    }

    @Test
    public void parseAncestorCommands_handlesEmptyOutput() {
        assertTrue(ShellCommandFactory.parseAncestorCommands(null).isEmpty());
        assertTrue(ShellCommandFactory.parseAncestorCommands("").isEmpty());
        assertTrue(ShellCommandFactory.parseAncestorCommands("   \r\n").isEmpty());
    }

    // ================= 探测行为 =================

    /**
     * 类 Unix 下 {@code detect()} 走 {@code probeUnixShell()}，祖先链探测必须直接短路，
     * 不能去 fork 一个不存在的 powershell。
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void parentProbeIsSkippedOnNonWindows() {
        long start = System.nanoTime();
        assertNull(ShellCommandFactory.detectWindowsParentShellName());
        long costMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(costMs < 200, "非 Windows 不应启动探测进程，实际耗时: " + costMs + "ms");
    }

    /**
     * 探测有界且可缓存：单次受超时约束，第二次必须走缓存（探测要 fork 进程，而 detect() 有多个调用点）。
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    public void parentProbeIsBoundedAndCached() {
        long start = System.nanoTime();
        String first = ShellCommandFactory.detectWindowsParentShellName();
        long firstCostMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(firstCostMs < 5_000, "探测应受超时约束，实际耗时: " + firstCostMs + "ms");
        if (first != null) {
            assertTrue("cmd".equals(first) || "powershell".equals(first) || "pwsh".equals(first), first);
        }

        start = System.nanoTime();
        String second = ShellCommandFactory.detectWindowsParentShellName();
        long secondCostMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(first, second);
        assertTrue(secondCostMs < 50, "第二次应命中缓存，实际耗时: " + secondCostMs + "ms");
    }
}
