package org.noear.solon.ai.talents.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.noear.solon.ai.talents.mount.MountManager;

/**
 * Windows 侧 shell 方案推定，以及自有文件工具对 UTF-8 BOM 的容忍。
 *
 * <p>两者是一套改动的两半：把 PowerShell 的写入默认编码定为 UTF-8 后，5.1 必然产出带 BOM 的文件
 * （5.1 无法写无 BOM 的 UTF-8），因此 {@code read} / {@code edit} / {@code grep} 必须忽略首部 BOM，
 * 否则「修好了乱码，却让首行匹配与首行 {@code ^} 锚定静默失效」。
 */
public class WindowsShellDetectAndBomTest {

    // ================= shell 方案推定 =================

    /**
     * 显式覆盖必须优先于父进程探测。
     *
     * <p>Java CLI 工具极常见的启动方式是 {@code xxx.bat} / {@code xxx.cmd} 包装脚本，那会让祖先链里
     * 真的出现 {@code cmd.exe}，于是被判成 CMD 方言——三种方言里能力最弱、且无法治理编码的那个。
     * 用户必须有办法把它掰回 PowerShell（反之亦然），否则就因为“用了官方启动脚本”被锁死。</p>
     */
    @Test
    public void explicitOverrideBeatsParentProbe() {
        String old = System.getProperty(ShellCommandFactory.SHELL_OVERRIDE_PROPERTY);
        try {
            System.setProperty(ShellCommandFactory.SHELL_OVERRIDE_PROPERTY, "powershell");
            assertEquals(ShellMode.POWERSHELL, ShellCommandFactory.windowsShellOverride().getShellMode());

            System.setProperty(ShellCommandFactory.SHELL_OVERRIDE_PROPERTY, " PWSH ");
            assertTrue(ShellCommandFactory.windowsShellOverride().isPowerShellCore(),
                    "取值应忽略大小写与首尾空白");

            System.setProperty(ShellCommandFactory.SHELL_OVERRIDE_PROPERTY, "cmd");
            assertEquals(ShellMode.CMD, ShellCommandFactory.windowsShellOverride().getShellMode());

            // 无法识别的取值不猜，交回自动探测（环境变量已配置时该分支不适用）
            if (System.getenv(ShellCommandFactory.SHELL_OVERRIDE_ENV) == null) {
                System.setProperty(ShellCommandFactory.SHELL_OVERRIDE_PROPERTY, "fish");
                assertNull(ShellCommandFactory.windowsShellOverride());
            }
        } finally {
            if (old == null) {
                System.clearProperty(ShellCommandFactory.SHELL_OVERRIDE_PROPERTY);
            } else {
                System.setProperty(ShellCommandFactory.SHELL_OVERRIDE_PROPERTY, old);
            }
        }
    }


    /**
     * 不可判定父 shell 时必须回退 PowerShell 而非 CMD。
     *
     * <p>从 IDE（{@code idea64.exe → java.exe}）、服务、计划任务启动时，祖先链里根本没有 shell，
     * 而这是开发场景里极常见的启动方式。CMD 是三种方言里能力最弱的一个，且无法在不破坏 {@code %}
     * 语义的前提下注入 UTF-8 前置——回退到它等于连编码治理能力一起丢掉。
     */
    @Test
    public void unresolvableParentFallsBackToPowerShell() {
        ShellCommandFactory fallback = ShellCommandFactory.windowsShellOf(null);

        assertEquals(ShellMode.POWERSHELL, fallback.getShellMode(),
                "祖先链里没有 shell 时必须回退 PowerShell，不能回退到能力最弱的 CMD");
        assertNotNull(fallback.getShellCmd());
    }

    @Test
    public void parentShellNameDecidesLaunchPlan() {
        assertEquals(ShellMode.CMD, ShellCommandFactory.windowsShellOf("cmd").getShellMode());
        assertEquals("cmd", ShellCommandFactory.windowsShellOf("cmd").getShellCmd());

        assertEquals(ShellMode.POWERSHELL, ShellCommandFactory.windowsShellOf("powershell").getShellMode());
        assertEquals("powershell", ShellCommandFactory.windowsShellOf("powershell").getShellCmd());

        // pwsh 必须原名启动：降级成 powershell(5.1) 会让“用户在 7 里工作、命令却由 5.1 执行”，
        // 且在已移除 5.1 的精简环境里直接启动失败
        assertEquals(ShellMode.POWERSHELL, ShellCommandFactory.windowsShellOf("pwsh").getShellMode());
        assertEquals("pwsh", ShellCommandFactory.windowsShellOf("pwsh").getShellCmd());
        assertTrue(ShellCommandFactory.windowsShellOf("pwsh").isPowerShellCore());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    public void detectOnWindowsAlwaysProducesRunnableShell() {
        ShellCommandFactory factory = ShellCommandFactory.detect();

        assertTrue(factory.isWindowsShell(), "Windows 上必须是 CMD 或 PowerShell");
        assertNotNull(factory.getShellCmd());
        if (factory.getShellMode() == ShellMode.POWERSHELL) {
            // 不能硬编码 "powershell"：只装 pwsh 而移除了 5.1 的机器上会直接启动失败
            assertTrue("powershell".equals(factory.getShellCmd()) || "pwsh".equals(factory.getShellCmd()),
                    "实际可执行名: " + factory.getShellCmd());
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    public void defaultPowerShellCmdPrefersInstalledOne() {
        String cmd = ShellCommandFactory.defaultWindowsPowerShellCmd();
        assertTrue("powershell".equals(cmd) || "pwsh".equals(cmd), cmd);
    }

    /**
     * {@code pwsh} 与 5.1 的能力不同（7+ 支持 {@code &&} / {@code ||}），必须能区分，
     * 否则引导词会把 5.1 的限制当作 pwsh 的限制，无故降级可用能力。
     */
    @Test
    public void powerShellCoreIsDistinguishedFromLegacy() {
        assertTrue(new ShellCommandFactory(ShellMode.POWERSHELL, "pwsh").isPowerShellCore());
        assertFalse(new ShellCommandFactory(ShellMode.POWERSHELL, "powershell").isPowerShellCore());
        assertFalse(new ShellCommandFactory(ShellMode.CMD, "cmd").isPowerShellCore());
        assertFalse(new ShellCommandFactory(ShellMode.UNIX_SHELL, "bash").isPowerShellCore());
    }

    @Test
    public void talentRejectsNullShellFactory() {
        assertThrows(IllegalArgumentException.class,
                () -> new TerminalTalent(new MountManager("."), null));
    }

    // ================= BOM 容忍 =================

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static void writeWithBom(Path file, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, all, 0, BOM.length);
        System.arraycopy(body, 0, all, BOM.length, body.length);
        Files.write(file, all);
    }

    @Test
    public void read_doesNotExposeBomOnFirstLine() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-bom-read");
        try {
            writeWithBom(workDir.resolve("a.md"), "# 标题\n正文\n");
            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));

            String out = talent.read("a.md", null, null, workDir.toString());

            assertTrue(out.contains("# 标题"), out);
            assertFalse(out.contains("\uFEFF"), "首行不应把 BOM 交给模型: " + out);
        } finally {
            deleteRecursively(workDir);
        }
    }

    /**
     * BOM 会让首行的 {@code old_str} 匹配无声失败——模型看到的首行与文件里的首行差一个不可见字符。
     */
    @Test
    public void edit_matchesFirstLineOfBomFile() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-bom-edit");
        try {
            Path file = workDir.resolve("a.md");
            writeWithBom(file, "# 旧标题\n正文\n");
            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));

            TerminalTalent.EditOp op = new TerminalTalent.EditOp();
            op.oldStrStartLine = 1;
            op.oldStr = "# 旧标题";
            op.newStr = "# 新标题";
            String result = talent.edit("a.md", Collections.singletonList(op), workDir.toString());

            assertTrue(result.contains("成功"), result);

            byte[] bytes = Files.readAllBytes(file);
            // BOM 必须保留：edit 只该改内容，不该顺手改文件的字节形态
            assertEquals((byte) 0xEF, bytes[0]);
            assertEquals((byte) 0xBB, bytes[1]);
            assertEquals((byte) 0xBF, bytes[2]);
            String text = new String(bytes, StandardCharsets.UTF_8);
            assertTrue(text.contains("# 新标题"), text);
            assertFalse(text.contains("旧标题"), text);
            assertEquals(1, text.indexOf('\uFEFF') + 1, "不应出现第二个 BOM: " + text);
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void grep_anchorsFirstLineOfBomFile() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-bom-grep");
        try {
            writeWithBom(workDir.resolve("a.md"), "package demo;\nclass A {}\n");
            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));

            String out = talent.grep("^package", ".", "*.md", workDir.toString());

            assertTrue(out.contains("a.md"), "行首锚定的正则应能命中带 BOM 文件的首行: " + out);
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void write_doesNotIntroduceBom() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-bom-write");
        try {
            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            talent.write("a.md", "中文内容\n", workDir.toString());

            byte[] bytes = Files.readAllBytes(workDir.resolve("a.md"));
            assertFalse(bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB,
                    "write 工具自己写出的文件不应带 BOM");
        } finally {
            deleteRecursively(workDir);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (Files.notExists(root)) {
            return;
        }
        List<Path> paths = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            stream.forEach(paths::add);
        }
        Collections.sort(paths, Collections.reverseOrder());
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }
}
