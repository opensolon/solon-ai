package org.noear.solon.ai.talents.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.ai.talents.mount.MountManager;

/**
 * 逻辑路径 @alias 展开：物理路径含空格时自动加引号，避免 shell 按空白拆分成多个参数。
 *
 * <p>三种方言的占位符与引号策略并不相同，必须显式指定 shellMode 逐一验证，
 * 不能依赖当前宿主机的探测结果（否则同一份断言在 Linux 上过、在 Windows 上挂）：
 * <ul>
 *   <li>UNIX_SHELL：{@code "$POOL"/a.py} —— 引号段与裸字符相邻即拼接</li>
 *   <li>CMD：{@code "%POOL%"/a.py} —— 同上，%VAR% 在解析后才展开，故须由我们补引号</li>
 *   <li>POWERSHELL：{@code "$env:POOL/a.py"} —— PowerShell 不做相邻拼接，
 *       {@code "$env:POOL"/a.py} 会被切成两个参数，必须整体包进同一对引号</li>
 * </ul>
 */
public class TerminalTalentMountSpacePathTest {

    @TempDir
    Path workDir;

    private TerminalTalent newTalent(Path mountPath, ShellMode mode) {
        MountManager mm = new MountManager(workDir.toString());
        MountDir mount = MountDir.builder()
                .alias("@pool")
                .path(mountPath.toString())
                .enabled(true)
                .build();
        mm.register(mount);
        return new TerminalTalent(mm, new ShellCommandFactory(mode, shellCmdOf(mode)));
    }

    private static String shellCmdOf(ShellMode mode) {
        switch (mode) {
            case CMD:
                return "cmd";
            case POWERSHELL:
                return "powershell";
            default:
                return "bash";
        }
    }

    /** 期望的占位符写法（显式列出，不复用生产代码逻辑，避免断言退化为同义反复）。 */
    private static String placeholder(ShellMode mode) {
        switch (mode) {
            case CMD:
                return "%POOL%";
            case POWERSHELL:
                return "$env:POOL";
            default:
                return "$POOL";
        }
    }

    /** 含空格路径 + 后跟路径片段时的期望形态。 */
    private static String quotedWithSuffix(ShellMode mode, String suffix) {
        if (mode == ShellMode.POWERSHELL) {
            return "\"" + placeholder(mode) + suffix + "\"";
        }
        return "\"" + placeholder(mode) + "\"" + suffix;
    }

    @ParameterizedTest
    @EnumSource(ShellMode.class)
    public void mountPathWithSpace_isQuotedAutomatically(ShellMode mode) throws Exception {
        Path mount = Files.createTempDirectory("my pool");
        try {
            TerminalTalent talent = newTalent(mount, mode);
            String translated = talent.translateCommandToEnv(
                    "python3 @pool/a.py --name \"hello world\"", new HashMap<>());

            assertTrue(translated.contains("python3 " + quotedWithSuffix(mode, "/a.py")),
                    "含空格路径应自动加引号: " + translated);
            assertTrue(translated.contains("--name \"hello world\""),
                    "参数自身的引号应保留: " + translated);
        } finally {
            Files.deleteIfExists(mount);
        }
    }

    @ParameterizedTest
    @EnumSource(ShellMode.class)
    public void mountPathWithSpace_alreadyQuotedNotDoubleQuoted(ShellMode mode) throws Exception {
        Path mount = Files.createTempDirectory("my pool");
        try {
            TerminalTalent talent = newTalent(mount, mode);
            String translated = talent.translateCommandToEnv(
                    "cd \"@pool/bin\"", new HashMap<>());

            assertEquals("cd \"" + placeholder(mode) + "/bin\"", translated,
                    "AI 已加引号时不应重复加引号: " + translated);
        } finally {
            Files.deleteIfExists(mount);
        }
    }

    @ParameterizedTest
    @EnumSource(ShellMode.class)
    public void mountPathWithoutSpace_keepsUnquoted(ShellMode mode) throws Exception {
        Path mount = Files.createTempDirectory("pool_no_space");
        try {
            TerminalTalent talent = newTalent(mount, mode);
            String translated = talent.translateCommandToEnv(
                    "ls @pool/bin", new HashMap<>());

            assertEquals("ls " + placeholder(mode) + "/bin", translated,
                    "无空格路径不应加引号: " + translated);
        } finally {
            Files.deleteIfExists(mount);
        }
    }

    @ParameterizedTest
    @EnumSource(ShellMode.class)
    public void mountPathWithSpace_placeholderEnvIsInjected(ShellMode mode) throws Exception {
        Path mount = Files.createTempDirectory("my pool");
        try {
            TerminalTalent talent = newTalent(mount, mode);
            Map<String, String> envs = new HashMap<>();
            String translated = talent.translateCommandToEnv("python3 @pool/a.py", envs);

            assertEquals("python3 " + quotedWithSuffix(mode, "/a.py"), translated,
                    mode + " 的占位符展开形态不符: " + translated);
            assertEquals(mount.toString(), envs.get("POOL"));
        } finally {
            Files.deleteIfExists(mount);
        }
    }

    @ParameterizedTest
    @EnumSource(ShellMode.class)
    public void mountPathWithSpace_aiQuotedWholeArgKeepsSingleQuotes(ShellMode mode) throws Exception {
        Path mount = Files.createTempDirectory("my pool");
        try {
            TerminalTalent talent = newTalent(mount, mode);
            // AI 对整个含空格参数加了引号：不应再加一层引号，保持单层
            String translated = talent.translateCommandToEnv(
                    "python3 @pool/run.py --name \"@pool/sub dir\"", new HashMap<>());

            assertEquals("python3 " + quotedWithSuffix(mode, "/run.py")
                            + " --name \"" + placeholder(mode) + "/sub dir\"", translated,
                    "AI 已引号包裹的参数不应重复加引号: " + translated);
        } finally {
            Files.deleteIfExists(mount);
        }
    }

    /**
     * PowerShell 专项回归：{@code "$env:POOL"/a.py} 这种「引号段 + 裸后缀」的写法在 PowerShell 下
     * 会被切成两个参数而报「找不到接受实际参数 /a.py 的位置形式参数」，不允许再出现。
     */
    @Test
    public void powershell_doesNotEmitQuotedPlaceholderWithBareSuffix() throws Exception {
        Path mount = Files.createTempDirectory("my pool");
        try {
            TerminalTalent talent = newTalent(mount, ShellMode.POWERSHELL);
            String translated = talent.translateCommandToEnv(
                    "Get-Content @pool/a.txt", new HashMap<>());

            assertFalse(translated.contains("\"$env:POOL\"/"),
                    "PowerShell 不支持引号段与裸后缀拼接: " + translated);
            assertEquals("Get-Content \"$env:POOL/a.txt\"", translated);
        } finally {
            Files.deleteIfExists(mount);
        }
    }

    /**
     * PowerShell 整体加引号时，后缀必须在 shell 元字符处断开，
     * 否则 {@code cd @pool/bin; ls} 会把分号一起包进引号，变成路径的一部分。
     */
    @Test
    public void powershell_quotedSuffixStopsAtShellMetaChar() throws Exception {
        Path mount = Files.createTempDirectory("my pool");
        try {
            TerminalTalent talent = newTalent(mount, ShellMode.POWERSHELL);
            String translated = talent.translateCommandToEnv(
                    "cd @pool/bin; ls", new HashMap<>());

            assertEquals("cd \"$env:POOL/bin\"; ls", translated,
                    "分号不应被包进引号: " + translated);
        } finally {
            Files.deleteIfExists(mount);
        }
    }
}
