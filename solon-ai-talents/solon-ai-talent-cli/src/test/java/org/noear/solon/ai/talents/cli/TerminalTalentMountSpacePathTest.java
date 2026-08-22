package org.noear.solon.ai.talents.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.ai.talents.mount.MountManager;

/**
 * 逻辑路径 @alias 展开：物理路径含空格时自动加引号，避免 shell 按空白拆分成多个参数。
 */
public class TerminalTalentMountSpacePathTest {

    @TempDir
    Path workDir;

    private TerminalTalent newTalent(Path mountPath) {
        MountManager mm = new MountManager(workDir.toString());
        MountDir mount = MountDir.builder()
                .alias("@pool")
                .path(mountPath.toString())
                .enabled(true)
                .build();
        mm.register(mount);
        return new TerminalTalent(mm);
    }

    @Test
    public void mountPathWithSpace_isQuotedAutomatically() throws Exception {
        Path mount = Files.createTempDirectory("my pool");
        try {
            TerminalTalent talent = newTalent(mount);
            String translated = talent.translateCommandToEnv(
                    "python3 @pool/a.py --name \"hello world\"", new HashMap<>());

            // 占位符被引号包裹，与 /a.py 拼接后仍是单一参数（bash/cmd 均支持 "prefix"/suffix 拼接）
            assertTrue(translated.contains("python3 \"$POOL\"/a.py"),
                    "含空格路径应自动加引号: " + translated);
            assertTrue(translated.contains("--name \"hello world\""),
                    "参数自身的引号应保留: " + translated);
        } finally {
            Files.deleteIfExists(mount);
        }
    }

    @Test
    public void mountPathWithSpace_alreadyQuotedNotDoubleQuoted() throws Exception {
        Path mount = Files.createTempDirectory("my pool");
        try {
            TerminalTalent talent = newTalent(mount);
            String translated = talent.translateCommandToEnv(
                    "cd \"@pool/bin\"", new HashMap<>());

            assertEquals("cd \"$POOL/bin\"", translated,
                    "AI 已加引号时不应重复加引号: " + translated);
        } finally {
            Files.deleteIfExists(mount);
        }
    }

    @Test
    public void mountPathWithoutSpace_keepsUnquoted() throws Exception {
        Path mount = Files.createTempDirectory("pool_no_space");
        try {
            TerminalTalent talent = newTalent(mount);
            String translated = talent.translateCommandToEnv(
                    "ls @pool/bin", new HashMap<>());

            assertEquals("ls $POOL/bin", translated, "无空格路径不应加引号: " + translated);
        } finally {
            Files.deleteIfExists(mount);
        }
    }

    @Test
    public void mountPathWithSpace_cmdPlaceholderQuoted() throws Exception {
        Path mount = Files.createTempDirectory("my pool");
        try {
            // 直接构造 CMD 模式的 TerminalSupport：验证 %VAR% 占位符同样被引号包裹
            MountManager mm = new MountManager(workDir.toString());
            MountDir mountDir = MountDir.builder()
                    .alias("@pool")
                    .path(mount.toString())
                    .enabled(true)
                    .build();
            mm.register(mountDir);

            TerminalSupport support = new TerminalSupport(mm, Collections.emptySet(), ShellMode.CMD);
            Map<String, String> envs = new HashMap<>();
            String translated = support.translateCommandToEnv("python3 @pool/a.py", envs, true, true);

            assertEquals("python3 \"%POOL%\"/a.py", translated,
                    "CMD 的 %VAR% 展开应被引号包裹: " + translated);
            assertEquals(mount.toString(), envs.get("POOL"));
        } finally {
            Files.deleteIfExists(mount);
        }
    }

    @Test
    public void mountPathWithSpace_aiQuotedWholeArgKeepsSingleQuotes() throws Exception {
        Path mount = Files.createTempDirectory("my pool");
        try {
            TerminalTalent talent = newTalent(mount);
            // AI 对整个含空格参数加了引号：不应再加一层引号，保持单层
            String translated = talent.translateCommandToEnv(
                    "python3 @pool/run.py --name \"@pool/sub dir\"", new HashMap<>());

            assertEquals("python3 \"$POOL\"/run.py --name \"$POOL/sub dir\"", translated,
                    "AI 已引号包裹的参数不应重复加引号: " + translated);
        } finally {
            Files.deleteIfExists(mount);
        }
    }
}
