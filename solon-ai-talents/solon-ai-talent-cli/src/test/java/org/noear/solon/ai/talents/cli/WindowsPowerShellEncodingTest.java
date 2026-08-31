package org.noear.solon.ai.talents.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Windows 下 {@code bash} 的乱码根治。
 *
 * <p><b>被修复的现象</b>：{@code Get-Content .soloncode/CODE.md | Select-Object -First 80}
 * 返回的中文全是「鏋勫缓涓庢祴璇曟寚浠」这类乱码。
 *
 * <p><b>根因</b>：乱码不在传输层，而在 PowerShell <b>内部</b>。Windows PowerShell 5.1 的
 * {@code Get-Content} 默认按 ANSI 代码页（中文系统 GBK）读文件，于是它把 UTF-8 文件读成了错字，
 * 再把错字按 UTF-8 正确地写给我们——字节层完全合法，{@link OutputDecoder} 的字符集判定
 * 永远无从下手。同类问题还有：{@code Set-Content} 默认 ANSI（emoji/日韩文直接丢字）、
 * {@code >} 默认 UTF-16LE（产物在任何按 UTF-8 读的工具里都是乱码）、
 * {@code [Console]::InputEncoding} 默认 ANSI（{@code bash_wait(chars)} 的非 ASCII 应答变乱码）。
 *
 * <p><b>对策</b>：只能在源头修——启动前置脚本把这四个决策点全部归一到 UTF-8
 * （见 {@link ShellCommandFactory#POWERSHELL_PREAMBLE}）。
 */
public class WindowsPowerShellEncodingTest {

    /** 覆盖多个书写系统：只测中文会漏掉「GBK 能表示中文、但表示不了日韩文/emoji」这类丢字 */
    private static final String MIXED_TEXT =
            "中文构建与测试 / 日本語のテスト / 한국어 테스트 / Кириллица / emoji 🚀 / ok";

    private static ShellCommandFactory powerShellFactory() {
        return new ShellCommandFactory(
                ShellMode.POWERSHELL, ShellCommandFactory.defaultWindowsPowerShellCmd());
    }

    private static String decodeEncodedCommand(List<String> argv) {
        String base64 = argv.get(argv.size() - 1);
        return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_16LE);
    }

    // ================= 前置脚本本身（与平台无关） =================

    @Test
    public void preamble_pinsAllFourEncodingDecisionPoints() {
        String preamble = ShellCommandFactory.POWERSHELL_PREAMBLE;

        // ① 引擎输出流：不设则为 ANSI，Java 侧按 UTF-8 解码必乱
        assertTrue(preamble.contains("$OutputEncoding"), preamble);
        assertTrue(preamble.contains("[Console]::OutputEncoding"), preamble);
        // ② 引擎输入流：bash_wait(chars) 写入的是 UTF-8 字节
        assertTrue(preamble.contains("[Console]::InputEncoding"), preamble);
        // ③/④ 文件读写默认编码
        assertTrue(preamble.contains("$PSDefaultParameterValues"), preamble);
    }

    @Test
    public void preamble_coversReadAndWriteCmdlets() {
        String preamble = ShellCommandFactory.POWERSHELL_PREAMBLE;

        // 读侧：这三个是「读 UTF-8 文件变乱码」的直接来源
        assertTrue(preamble.contains("'Get-Content'"), preamble);
        assertTrue(preamble.contains("'Select-String'"), preamble);
        assertTrue(preamble.contains("'Import-Csv'"), preamble);
        // 写侧：Out-File 的默认值同时决定 `>` / `>>` 的编码（实测生效），是 UTF-16LE 产物的解法
        assertTrue(preamble.contains("'Out-File'"), preamble);
        assertTrue(preamble.contains("'Set-Content'"), preamble);
        assertTrue(preamble.contains("'Add-Content'"), preamble);
    }

    /**
     * {@code $PSDefaultParameterValues} 的键必须是「该 cmdlet 真实存在的参数」。
     *
     * <p>给一个没有 {@code -Encoding} 参数的 cmdlet（如 {@code Get-Item}）设默认值，会让此后
     * <b>每一次</b>调用它都报参数绑定错误——用一条编码修复换来一个更广的功能故障。
     */
    @Test
    public void preamble_doesNotTouchCmdletsWithoutEncodingParam() {
        String preamble = ShellCommandFactory.POWERSHELL_PREAMBLE;

        assertFalse(preamble.contains("'Get-Item'"), "Get-Item 没有 -Encoding 参数");
        assertFalse(preamble.contains("'*:Encoding'"),
                "通配符会波及 Send-MailMessage 这类 -Encoding 为 Encoding 对象的 cmdlet，传字符串必报错");
    }

    /**
     * 前置脚本的所有赋值都必须能失败而不影响主命令：无控制台句柄时这些 setter 会抛 IOException，
     * 一旦首行报错就会混进合并后的输出流，模型会把它当成命令自身的错误。
     */
    @Test
    public void preamble_isFailSafe() {
        String preamble = ShellCommandFactory.POWERSHELL_PREAMBLE;

        assertTrue(preamble.contains("try {") && preamble.contains("catch"), preamble);
        // 带 BOM 的 [Text.Encoding]::UTF8 会让输出开头多出 \uFEFF
        assertTrue(preamble.contains("New-Object System.Text.UTF8Encoding $false"), preamble);
        assertFalse(preamble.contains("[System.Text.Encoding]::UTF8"), preamble);
        // chcp 会污染共享控制台的代码页，本方案不依赖它
        assertFalse(preamble.contains("chcp"), preamble);
    }

    @Test
    public void powerShellPrepare_prependsPreambleBeforeUserCommand() throws IOException {
        String command = "Get-Content 'a b.md' | Select-Object -First 3";
        ShellCommandFactory.PreparedCommand prepared = powerShellFactory().prepare(command);

        String script = decodeEncodedCommand(prepared.argv());
        assertTrue(script.startsWith(ShellCommandFactory.POWERSHELL_PREAMBLE),
                "前置脚本必须在用户命令之前: " + script);
        assertTrue(script.endsWith(command), "用户命令必须原样保留: " + script);
        assertNull(prepared.tempScript(), "-EncodedCommand 不应落临时文件");
    }

    /**
     * 前置脚本是 PowerShell 语法，绝不能泄漏到 CMD 或 Unix 分支。
     */
    @Test
    public void preamble_doesNotLeakToOtherShells() throws IOException {
        ShellCommandFactory.PreparedCommand cmd =
                new ShellCommandFactory(ShellMode.CMD, "cmd").prepare("echo hi");
        assertFalse(String.join(" ", cmd.argv()).contains("PSDefaultParameterValues"),
                "CMD 不应带 PowerShell 前置: " + cmd.argv());

        assertNull(new ShellCommandFactory(ShellMode.UNIX_SHELL, "bash").prepare("echo hi"),
                "Unix 必须保持 shell -lc 直连，不做任何前置注入");
    }

    // ================= 真实 PowerShell 执行（Windows） =================

    /**
     * 用户报告的原始复现：读取一个 UTF-8 文件并分页输出。
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    public void getContent_readsUtf8FileWithoutMojibake() throws Exception {
        Path dir = Files.createTempDirectory("solon-ai-enc-read");
        try {
            Path file = dir.resolve("CODE.md");
            Files.write(file, ("## " + MIXED_TEXT + "\n第二行\n").getBytes(StandardCharsets.UTF_8));

            String output = runPowerShell(dir,
                    "if (Test-Path CODE.md) { Get-Content CODE.md | Select-Object -First 80 } else { 'no CODE.md' }");

            assertTrue(output.contains(MIXED_TEXT), "读取 UTF-8 文件不应乱码，实际: " + output);
            assertTrue(output.contains("第二行"), output);
            assertNoMojibake(output);
        } finally {
            deleteRecursively(dir);
        }
    }

    /**
     * {@code Select-String} 与 {@code Get-Content} 走的是同一套默认编码，必须一并覆盖。
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    public void selectString_matchesUtf8Content() throws Exception {
        Path dir = Files.createTempDirectory("solon-ai-enc-grep");
        try {
            Files.write(dir.resolve("a.txt"),
                    ("first\n" + MIXED_TEXT + "\nlast\n").getBytes(StandardCharsets.UTF_8));

            String output = runPowerShell(dir,
                    "(Select-String -Path a.txt -Pattern '日本語').Line");

            assertTrue(output.contains("日本語"), "Select-String 应能按 UTF-8 匹配，实际: " + output);
            assertNoMojibake(output);
        } finally {
            deleteRecursively(dir);
        }
    }

    /**
     * {@code >} 在 5.1 下默认写 UTF-16LE：产物会在所有按 UTF-8 读的工具里变乱码。
     * 前置脚本通过 {@code Out-File:Encoding} 把它纠正为 UTF-8（BOM 由本工具链自行忽略）。
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    public void redirectOperator_writesUtf8NotUtf16() throws Exception {
        Path dir = Files.createTempDirectory("solon-ai-enc-write");
        try {
            runPowerShell(dir, "'" + MIXED_TEXT + "' > out.txt");

            Path out = dir.resolve("out.txt");
            assertTrue(Files.exists(out), "重定向应产生文件");
            byte[] bytes = Files.readAllBytes(out);

            assertFalse(bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE,
                    "不应再是 UTF-16LE（FF FE 开头）");
            String text = TerminalSupport.stripUtf8Bom(new String(bytes, StandardCharsets.UTF_8));
            assertTrue(text.contains(MIXED_TEXT), "按 UTF-8 读应得到原文，实际: " + text);
        } finally {
            deleteRecursively(dir);
        }
    }

    /**
     * 写入再读回：{@code Set-Content} 默认 ANSI 时 emoji / 日韩文会不可逆丢字，
     * 这条用例保证整条链路无损。
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    public void setContentThenGetContent_roundTripsLossless() throws Exception {
        Path dir = Files.createTempDirectory("solon-ai-enc-round");
        try {
            String output = runPowerShell(dir,
                    "'" + MIXED_TEXT + "' | Set-Content r.txt\r\nGet-Content r.txt");

            assertTrue(output.contains(MIXED_TEXT), "写入再读回应无损，实际: " + output);
            assertNoMojibake(output);
        } finally {
            deleteRecursively(dir);
        }
    }

    /**
     * 非零退出码必须显式给出：否则命令失败时模型只能从输出文本里猜成败，
     * 而 PowerShell 的错误对象经 CLIXML 剥离后已丢失与正文的交错顺序。
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    public void nonZeroExitCodeIsReported() throws Exception {
        Path dir = Files.createTempDirectory("solon-ai-exit-code");
        try {
            String output = runPowerShell(dir, "Write-Output 'before'; exit 3");
            assertTrue(output.contains("[exit_code=3]"), "应显式给出退出码，实际: " + output);
            assertTrue(output.contains("before"), "退出码不应挤掉正文: " + output);

            String ok = runPowerShell(dir, "Write-Output 'fine'");
            assertFalse(ok.contains("exit_code"), "退出码为 0 时不应有噪声: " + ok);
        } finally {
            deleteRecursively(dir);
        }
    }

    // ================= helpers =================

    private String runPowerShell(Path workDir, String command) throws IOException {
        ShellCommandFactory factory = powerShellFactory();
        ShellCommandFactory.PreparedCommand prepared = factory.prepare(command);
        try {
            return new ProcessExecutor()
                    .executeCmd(workDir, prepared.argv(), null, 60_000, 64_000, null);
        } finally {
            prepared.cleanup();
        }
    }

    /**
     * 「UTF-8 被按 GBK 解读」的典型特征字符。断言它们不出现，比只断言正文存在更能兜住部分乱码。
     */
    private static void assertNoMojibake(String output) {
        assertFalse(output.indexOf('\uFFFD') >= 0, "输出不应包含替换符 U+FFFD: " + output);
        // 「UTF-8 被按 ANSI 代码页解读」的实际形态无需硬编码：把期望文本的 UTF-8 字节按本机 ANSI
        // 代码页重解一遍就是它。这样断言在任何语言的 Windows 上都成立，也不会因源码编码而失真。
        java.nio.charset.Charset ansi = OutputDecoder.ansiCharset(StandardCharsets.UTF_8);
        if (ansi != null) {
            String mojibake = new String(MIXED_TEXT.getBytes(StandardCharsets.UTF_8), ansi);
            String head = mojibake.substring(0, Math.min(8, mojibake.length()));
            assertFalse(output.contains(head), "检测到 UTF-8 被按 ANSI 代码页解读的特征: " + output);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (Files.notExists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    @Test
    public void bomHelpers_stripOnlyLeadingBom() {
        assertEquals("abc", TerminalSupport.stripUtf8Bom("\uFEFFabc"));
        assertEquals("abc", TerminalSupport.stripUtf8Bom("abc"), "无 BOM 时必须原样返回");
        assertEquals("a\uFEFFb", TerminalSupport.stripUtf8Bom("a\uFEFFb"), "只剥首部，中间的不动");
        assertEquals("", TerminalSupport.stripUtf8Bom(""));
        assertNull(TerminalSupport.stripUtf8Bom(null));
        assertTrue(TerminalSupport.hasUtf8Bom("\uFEFF"));
        assertFalse(TerminalSupport.hasUtf8Bom(""));
        assertFalse(TerminalSupport.hasUtf8Bom(null));
    }
}
