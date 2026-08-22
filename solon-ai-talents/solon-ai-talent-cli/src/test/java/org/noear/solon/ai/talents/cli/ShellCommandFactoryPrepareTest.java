package org.noear.solon.ai.talents.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Windows 启动方案（prepare）：
 * CMD 默认 {@code /d /c} 直连（保留命令行的 % 语义），仅多行/非 ANSI/超长命令才落 .bat；
 * PowerShell 用 -EncodedCommand（不落文件、不受 ExecutionPolicy 限制），超长才回退 .ps1；
 * Unix 返回 null 保持直连。
 */
public class ShellCommandFactoryPrepareTest {

    @Test
    public void unix_prepareReturnsNull() throws Exception {
        ShellCommandFactory factory = new ShellCommandFactory(ShellMode.UNIX_SHELL, "bash");
        assertNull(factory.prepare("echo hi"));
    }

    /**
     * 关键：单行命令（含引号也）不落临时文件。
     *
     * <p>无条件落 .bat 会静默改变 {@code %} 语义（{@code %1}/{@code %*} 被当脚本参数、
     * {@code for %i} 必须写 {@code %%i}）；而含引号的单行命令直连本身是可靠的（JDK 拼 argv 时
     * 包的外层引号正好被 {@code cmd /c} 的剥首尾引号规则抵消），因此不为引号牺牲 % 语义。</p>
     */
    @Test
    public void cmd_singleLineCommandRunsDirectlyWithoutTempFile() throws Exception {
        ShellCommandFactory factory = new ShellCommandFactory(ShellMode.CMD, "cmd");
        String command = "python3 a.py \"hello world\"";
        ShellCommandFactory.PreparedCommand prepared = factory.prepare(command);

        assertNull(prepared.tempScript(), "单行命令不应落临时脚本（保留 % 语义）");
        assertEquals(Arrays.asList("cmd", "/d", "/c", command), prepared.argv());
        prepared.cleanup(); // 无临时文件时为空操作
    }

    /**
     * for 循环等命令行形式的 {@code %i} 必须原样传递（不能被改写、也不能落盘成批处理）。
     */
    @Test
    public void cmd_keepsCommandLinePercentSemantics() throws Exception {
        ShellCommandFactory factory = new ShellCommandFactory(ShellMode.CMD, "cmd");
        String command = "for %i in (*.txt) do @echo %i";
        ShellCommandFactory.PreparedCommand prepared = factory.prepare(command);

        assertNull(prepared.tempScript(), "不得落 .bat：批处理里 %i 会报错，必须写成 %%i");
        assertEquals(command, prepared.argv().get(3));
    }

    /**
     * 多行命令：命令行里的换行会被 cmd 截断，必须落 .bat（多行脚本本就期望批处理语义）。
     */
    @Test
    public void cmd_multiLineFallsBackToBat() throws Exception {
        ShellCommandFactory factory = new ShellCommandFactory(ShellMode.CMD, "cmd");
        String command = "echo line1\r\necho line2";
        ShellCommandFactory.PreparedCommand prepared = factory.prepare(command);
        try {
            List<String> argv = prepared.argv();
            assertEquals("cmd", argv.get(0));
            assertEquals("/d", argv.get(1));
            assertEquals("/c", argv.get(2));
            assertTrue(argv.get(3).endsWith(".bat"), "bat 脚本路径: " + argv);

            Charset ansi = OutputDecoder.ansiCharset(null);
            String content = new String(Files.readAllBytes(prepared.tempScript()),
                    ansi == null ? StandardCharsets.UTF_8 : ansi);
            assertTrue(content.contains("echo line1"), "命令文本应原样写入: " + content);
            assertTrue(content.contains("echo line2"), "命令文本应原样写入: " + content);
            if (ansi != null) {
                // 纯 ASCII 命令必然可被 ANSI 表示：不切代码页（避免污染共享控制台 + 批处理中途换页解析错位）
                assertFalse(content.contains("chcp"), "ANSI 可表示时不应 chcp: " + content);
            }
        } finally {
            prepared.cleanup();
        }
        assertFalse(Files.exists(prepared.tempScript()), "cleanup 后临时脚本应删除");
    }

    /**
     * 超长命令（超出 cmd 命令行长度上限）必须落 .bat。
     */
    @Test
    public void cmd_tooLongFallsBackToBat() throws Exception {
        ShellCommandFactory factory = new ShellCommandFactory(ShellMode.CMD, "cmd");
        StringBuilder sb = new StringBuilder("echo ");
        for (int i = 0; i < 8_000; i++) {
            sb.append('x');
        }
        ShellCommandFactory.PreparedCommand prepared = factory.prepare(sb.toString());
        try {
            assertTrue(prepared.argv().get(3).endsWith(".bat"), prepared.argv().toString());
        } finally {
            prepared.cleanup();
        }
    }

    @Test
    public void cmd_fallsBackToUtf8ChcpWhenAnsiCannotEncode() throws Exception {
        ShellCommandFactory factory = new ShellCommandFactory(ShellMode.CMD, "cmd");
        Charset ansi = OutputDecoder.ansiCharset(null);
        String command = "echo \uD83D\uDE80"; // emoji：GBK/常见 ANSI 代码页均无法表示
        org.junit.jupiter.api.Assumptions.assumeTrue(
                ansi != null && !ansi.newEncoder().canEncode(command), "当前平台 ANSI 可表示该命令，跳过");

        ShellCommandFactory.PreparedCommand prepared = factory.prepare(command);
        try {
            String content = new String(Files.readAllBytes(prepared.tempScript()), StandardCharsets.UTF_8);
            assertTrue(content.contains("@chcp 65001 > nul"), "应降级为 UTF-8 + chcp: " + content);
            assertTrue(content.contains(command), "命令文本应原样写入: " + content);
        } finally {
            prepared.cleanup();
        }
    }

    @Test
    public void powershell_usesEncodedCommandWithoutTempFile() throws Exception {
        ShellCommandFactory factory = new ShellCommandFactory(ShellMode.POWERSHELL, "powershell");
        ShellCommandFactory.PreparedCommand prepared = factory.prepare("Write-Output '你好 world'");
        try {
            List<String> argv = prepared.argv();
            assertEquals("powershell", argv.get(0));
            assertTrue(argv.contains("-NoProfile"), argv.toString());
            assertEquals("-EncodedCommand", argv.get(argv.size() - 2), "应使用 -EncodedCommand: " + argv);
            assertFalse(argv.contains("-ExecutionPolicy"),
                    "命令行 -ExecutionPolicy 会被 GPO 覆盖，不应依赖: " + argv);
            assertNull(prepared.tempScript(), "-EncodedCommand 不应落临时文件");

            String decoded = new String(Base64.getDecoder().decode(argv.get(argv.size() - 1)),
                    StandardCharsets.UTF_16LE);
            assertTrue(decoded.contains("Write-Output '你好 world'"), "命令文本应完整保留: " + decoded);
            assertTrue(decoded.contains("[Console]::OutputEncoding"), "应前置输出编码设置: " + decoded);
            // 必须是无 BOM 的 UTF8Encoding：[System.Text.Encoding]::UTF8 带 preamble，会在输出头部插入 \uFEFF
            assertTrue(decoded.contains("New-Object System.Text.UTF8Encoding $false"),
                    "应用无 BOM 的 UTF8Encoding: " + decoded);
            assertFalse(decoded.contains("[System.Text.Encoding]::UTF8"),
                    "不得使用带 BOM 的 ::UTF8: " + decoded);
            // 无控制台句柄时赋值可能抛 IOException，不能让首行报错混进合并后的输出流
            assertTrue(decoded.contains("try {") && decoded.contains("catch"),
                    "控制台编码赋值应包 try/catch: " + decoded);
        } finally {
            prepared.cleanup(); // 无临时文件时为空操作
        }
    }

    /**
     * 会话式执行（支持 bash_stdin）不能加 -NonInteractive，否则需要确认输入的命令会直接失败而不是等待输入。
     */
    @Test
    public void powershell_interactiveKeepsStdinUsable() throws Exception {
        ShellCommandFactory factory = new ShellCommandFactory(ShellMode.POWERSHELL, "powershell");
        ShellCommandFactory.PreparedCommand nonInteractive = factory.prepare("Read-Host", false);
        ShellCommandFactory.PreparedCommand interactive = factory.prepare("Read-Host", true);

        assertTrue(nonInteractive.argv().contains("-NonInteractive"), nonInteractive.argv().toString());
        assertFalse(interactive.argv().contains("-NonInteractive"),
                "会话式执行不应加 -NonInteractive: " + interactive.argv());
        assertTrue(interactive.argv().contains("-NoProfile"), interactive.argv().toString());
    }

    @Test
    public void powershell_fallsBackToScriptFileWhenCommandTooLong() throws Exception {
        ShellCommandFactory factory = new ShellCommandFactory(ShellMode.POWERSHELL, "powershell");
        StringBuilder sb = new StringBuilder("Write-Output '");
        for (int i = 0; i < 10_000; i++) {
            sb.append('x');
        }
        sb.append('\'');

        ShellCommandFactory.PreparedCommand prepared = factory.prepare(sb.toString());
        try {
            List<String> argv = prepared.argv();
            assertTrue(argv.contains("-File"), "超长命令应回退 -File: " + argv);
            assertTrue(argv.get(argv.size() - 1).endsWith(".ps1"), argv.toString());

            byte[] bytes = Files.readAllBytes(prepared.tempScript());
            assertEquals((byte) 0xEF, bytes[0]); // UTF-8 BOM：PowerShell 5.1 依赖 BOM 识别 UTF-8
            assertEquals((byte) 0xBB, bytes[1]);
            assertEquals((byte) 0xBF, bytes[2]);
        } finally {
            prepared.cleanup();
        }
    }

    @Test
    public void prepare_rejectsNullCommand() {
        ShellCommandFactory factory = new ShellCommandFactory(ShellMode.CMD, "cmd");
        try {
            factory.prepare(null);
            assertTrue(false, "应拒绝 null 命令");
        } catch (IllegalArgumentException expected) {
        } catch (Exception unexpected) {
            assertTrue(false, "应抛 IllegalArgumentException: " + unexpected);
        }
    }
}
