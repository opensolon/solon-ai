package features.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.talents.cli.ShellCommandFactory;
import org.noear.solon.ai.talents.cli.ShellMode;
import org.noear.solon.ai.talents.cli.TerminalTalent;
import org.noear.solon.ai.talents.mount.Mount;
import org.noear.solon.ai.talents.mount.MountManager;
import org.noear.solon.ai.talents.mount.MountType;
import org.noear.solon.ai.talents.mount.source.ClasspathMountSource;
import org.noear.solon.ai.talents.mount.source.FileMountSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * mount_list（挂载清单）展示层语义验证：
 *
 * <ul>
 *   <li>shell 属性显式声明 bash 可达性（本地来源 true，虚拟来源 false），不再让模型从
 *       “有没有 env 属性”反推；</li>
 *   <li>isVisible 只影响展示：隐藏挂载不进 mount_list、不触发挂载相关引导词段落，但
 *       translateCommandToEnv（bash 逻辑路径翻译）与文件工具完全不受影响。</li>
 * </ul>
 */
public class TerminalTalentMountListTest {

    /** 占位符写法随宿主 shell 方言不同（%VAR% / $env:VAR / $VAR），不能硬编码 Unix 形态。 */
    private static String expectedEnvPlaceholder(String key) {
        ShellMode mode = ShellCommandFactory.detect().getShellMode();
        if (mode == ShellMode.CMD) {
            return "%" + key + "%";
        }
        if (mode == ShellMode.POWERSHELL) {
            return "$env:" + key;
        }
        return "$" + key;
    }

    private static Path tempDir(String prefix) throws Exception {
        return Files.createTempDirectory(prefix);
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (dir == null || !Files.exists(dir)) return;
        Files.walk(dir).sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> p.toFile().delete());
    }

    /** 从 mount_list 行集中取出指定 alias 的属性行（去掉缩进与换行）。 */
    private static String mountLine(String instruction, String alias) {
        int idx = instruction.indexOf("<mount alias=\"" + alias + "\"");
        if (idx < 0) return null;
        int end = instruction.indexOf("/>", idx);
        return instruction.substring(idx, end).replace("\n", " ").trim();
    }

    /** 调用包私有的 translateCommandToEnv 桥接（与其它测试反射调用的做法一致）。 */
    private static Map<String, String> translate(TerminalTalent talent, String command) throws Exception {
        Map<String, String> envs = new HashMap<>();
        java.lang.reflect.Method method = TerminalTalent.class
                .getDeclaredMethod("translateCommandToEnv", String.class, Map.class);
        method.setAccessible(true);
        method.invoke(talent, command, envs);
        return envs;
    }

    @Test
    public void localMountEmitsShellTrueAndEnv() throws Exception {
        Path work = tempDir("mountlist-local");
        Path pool = tempDir("mountlist-pool");
        try {
            Files.write(pool.resolve("tool.md"), "# tool".getBytes());
            MountManager manager = new MountManager(work.toString());
            manager.register(Mount.builder().alias("@pool1").type(MountType.FILES)
                    .source(FileMountSource.of(pool)).writeable(true).build());

            String instruction = new TerminalTalent(manager).getInstruction(null);
            String line = mountLine(instruction, "@pool1");
            assertNotNull(line, "本地挂载应出现在 mount_list 中");

            assertTrue(line.contains("shell=\"true\""), line);
            assertTrue(line.contains("env=\"" + expectedEnvPlaceholder("POOL1") + "\""), line);
            assertFalse(line.contains("scheme="), "本地挂载不应再输出 scheme 属性: " + line);
        } finally {
            deleteRecursively(work);
            deleteRecursively(pool);
        }
    }

    @Test
    public void classpathMountEmitsShellFalseAndScheme() throws Exception {
        Path work = tempDir("mountlist-classpath");
        try {
            MountManager manager = new MountManager(work.toString());
            manager.register(Mount.builder().alias("@cp").type(MountType.SKILLS)
                    .source(ClasspathMountSource.of(
                            Thread.currentThread().getContextClassLoader(), "test-skills"))
                    .writeable(false).build());

            String instruction = new TerminalTalent(manager).getInstruction(null);
            String line = mountLine(instruction, "@cp");
            assertNotNull(line);

            assertTrue(line.contains("shell=\"false\""), line);
            assertTrue(line.contains("scheme=\"classpath\""), line);
            assertFalse(line.contains("env="), "虚拟来源不应输出 env 属性: " + line);
        } finally {
            deleteRecursively(work);
        }
    }

    @Test
    public void hiddenMountExcludedFromMountListButStillShellTranslatable() throws Exception {
        Path work = tempDir("mountlist-hidden");
        Path pool = tempDir("mountlist-hidden-pool");
        try {
            Files.write(pool.resolve("bin.sh"), "echo hi".getBytes());
            MountManager manager = new MountManager(work.toString());
            // 模拟内置挂载：本地 shell 可达但 visible=false（与 @harness 的注册形态一致）
            manager.register(Mount.builder().alias("@builtin").type(MountType.AGENTS)
                    .source(FileMountSource.of(pool))
                    .writeable(false)
                    .visible(false)
                    .build());

            TerminalTalent talent = new TerminalTalent(manager);
            String instruction = talent.getInstruction(null);

            // 展示层：不出现在清单与引导词中（hasMount 三兄弟也按 visible 过滤，
            // 否则只有隐藏挂载时会输出“见下方挂载点清单”却给不出清单）
            assertNull(mountLine(instruction, "@builtin"), "隐藏挂载不应出现在 mount_list: " + instruction);
            assertFalse(instruction.contains("@builtin"), instruction);
            assertFalse(instruction.contains("<mount_list>"), "唯一挂载被隐藏时不应输出清单: " + instruction);

            // 运行时层：bash 逻辑路径翻译不受 visible 影响
            Map<String, String> envs = translate(talent, "cd @builtin && ls");
            assertTrue(envs.containsKey("BUILTIN"), "隐藏挂载仍应注入环境变量: " + envs);
            assertTrue(envs.get("BUILTIN").contains("mountlist-hidden-pool"), String.valueOf(envs));

            // 文件工具不受 visible 影响
            assertTrue(talent.read("@builtin/bin.sh", null, null, null).contains("echo hi"));
        } finally {
            deleteRecursively(work);
            deleteRecursively(pool);
        }
    }

    @Test
    public void disabledMountStillExcluded() throws Exception {
        Path work = tempDir("mountlist-disabled");
        Path pool = tempDir("mountlist-disabled-pool");
        try {
            Files.write(pool.resolve("a.md"), "# a".getBytes());
            MountManager manager = new MountManager(work.toString());
            manager.register(Mount.builder().alias("@off").type(MountType.FILES)
                    .source(FileMountSource.of(pool)).enabled(false).build());

            String instruction = new TerminalTalent(manager).getInstruction(null);
            assertFalse(instruction.contains("@off"), instruction);
            assertFalse(instruction.contains("<mount_list>"), instruction);

            Map<String, String> envs = translate(new TerminalTalent(manager), "cd @off && ls");
            assertFalse(envs.containsKey("OFF"), "禁用挂载不应注入环境变量: " + envs);
        } finally {
            deleteRecursively(work);
            deleteRecursively(pool);
        }
    }
}
