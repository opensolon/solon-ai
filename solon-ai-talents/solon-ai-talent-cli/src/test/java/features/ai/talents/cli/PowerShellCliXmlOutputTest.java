package features.ai.talents.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.noear.solon.ai.talents.cli.ProcessExecutor;
import org.noear.solon.ai.talents.cli.ShellCommandFactory;
import org.noear.solon.ai.talents.cli.ShellMode;

/**
 * PowerShell 端到端回归：真实拉起 powershell.exe，验证输出里既没有 CLIXML 噪声，中文也不乱码。
 *
 * <p>背景：powershell.exe 的 stderr 被重定向到管道时，会把 progress / error 等非 stdout 流强制
 * 序列化成 {@code #< CLIXML ... </Objs>} 写出（无法用 {@code $ProgressPreference}、
 * {@code -OutputFormat Text} 等手段关闭）。该块由引擎启动期的 writer 按 ANSI 代码页写出，
 * 与已切成 UTF-8 的 stdout 合并到同一管道后，会让字符集判定锁错，正文中文被解成乱码。</p>
 */
@EnabledOnOs(OS.WINDOWS)
public class PowerShellCliXmlOutputTest {

    @Test
    public void noCliXmlNoMojibake() throws IOException {
        ShellCommandFactory factory = new ShellCommandFactory(ShellMode.POWERSHELL, "powershell");
        // Get-Command 会触发模块自动加载 → 稳定产生 progress 记录（CLIXML 的主要来源）
        String command = "Get-Command git -ErrorAction SilentlyContinue | Out-Null\r\n"
                + "\"MAVEN_HOME 未设置\"\r\n"
                + "\"中文输出 OK\"\r\n";

        ShellCommandFactory.PreparedCommand prepared = factory.prepare(command);
        Path work = Paths.get(".").toAbsolutePath().normalize();
        try {
            String out = new ProcessExecutor().executeCmd(work, prepared.argv(), null, 60_000, 64_000, null);

            assertFalse(out.contains("CLIXML"), "CLIXML 头未被剥离: " + out);
            assertFalse(out.contains("<Objs"), "CLIXML 载荷未被剥离: " + out);
            assertTrue(out.contains("MAVEN_HOME 未设置"), "中文被解成乱码: " + out);
            assertTrue(out.contains("中文输出 OK"), "中文被解成乱码: " + out);
        } finally {
            prepared.cleanup();
        }
    }

    /**
     * PowerShell 自身的 error 记录只存在于 CLIXML 里，剥离时必须还原成纯文本，不能丢。
     */
    @Test
    public void powerShellErrorRecordSurvivesAsText() throws IOException {
        ShellCommandFactory factory = new ShellCommandFactory(ShellMode.POWERSHELL, "powershell");
        ShellCommandFactory.PreparedCommand prepared =
                factory.prepare("$ErrorActionPreference='Continue'\r\nWrite-Error \"命令出错了\"\r\n");
        Path work = Paths.get(".").toAbsolutePath().normalize();
        try {
            String out = new ProcessExecutor().executeCmd(work, prepared.argv(), null, 60_000, 64_000, null);

            assertFalse(out.contains("<Objs"), "CLIXML 载荷未被剥离: " + out);
            assertTrue(out.contains("命令出错了"), "错误文本丢失: " + out);
        } finally {
            prepared.cleanup();
        }
    }
}
