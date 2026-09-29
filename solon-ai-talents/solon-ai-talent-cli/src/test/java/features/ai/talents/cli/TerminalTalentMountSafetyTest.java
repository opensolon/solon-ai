package features.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.sandbox.config.FilesystemConfig;
import org.noear.solon.ai.sandbox.config.SandboxRuntimeConfig;
import org.noear.solon.ai.talents.cli.TerminalTalent;
import org.noear.solon.ai.talents.mount.Mount;
import org.noear.solon.ai.talents.mount.MountManager;
import org.noear.solon.ai.talents.mount.MountType;
import org.noear.solon.ai.talents.mount.source.FileMountSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TerminalSupport.checkLocalMountPathSafety 的防御矩阵验证。
 *
 * <p>本地挂载（含 AGENTS/SKILLS/FILES 类型）的路径安全校验分四个分支：</p>
 * <ol>
 *   <li>挂载根存在 + 目标存在：真实路径必须落在挂载根内（符号链接防护）；</li>
 *   <li>挂载根存在 + 目标不存在：用最近已存在祖先做真实路径校验；</li>
 *   <li>挂载根不存在（AGENTS 挂载常见形态，如 ~/.soloncode/agents）：
 *       先做逻辑路径边界校验，再用双方最近已存在祖先做真实路径校验，
 *       ls/read 等工具返回“路径不存在”而非抛 NoSuchFileException；</li>
 *   <li>逻辑路径越界（../ 逃逸）：无论根是否存在都直接拒绝。</li>
 * </ol>
 */
public class TerminalTalentMountSafetyTest {
    @TempDir
    Path tempDir;

    private TerminalTalent talentWith(MountManager manager) {
        TerminalTalent talent = new TerminalTalent(manager);
        FilesystemConfig fs = new FilesystemConfig(null, null, Collections.singletonList("."), null, null);
        talent.setSandboxConfig(new SandboxRuntimeConfig(null, fs, null, null, null, null,
                null, null, null, null, null, null, null));
        return talent;
    }

    private MountManager registerAgentMount(Path root, boolean writeable) {
        MountManager manager = new MountManager(tempDir.toString());
        manager.register(Mount.builder()
                .alias("@user-agents")
                .source(FileMountSource.of(root))
                .type(MountType.AGENTS)
                .writeable(writeable)
                .build());
        return manager;
    }

    /** 分支 1：根与目标都存在 → 正常列出与读取。 */
    @Test
    public void existingRootAndTargetShouldListNormally() throws Exception {
        Path agentsRoot = Files.createDirectories(tempDir.resolve("agents"));
        Files.write(agentsRoot.resolve("coder.md"), "agent".getBytes());
        TerminalTalent talent = talentWith(registerAgentMount(agentsRoot, true));

        String listing = talent.ls("@user-agents", false, false, tempDir.toString());
        assertTrue(listing.contains("coder.md"), listing);
        assertTrue(talent.read("@user-agents/coder.md", null, null, tempDir.toString()).contains("agent"));
    }

    /**
     * 分支 2（回归主线场景）：挂载根不存在 → ls 返回“路径不存在”，
     * 而不是把 NoSuchFileException(/Users/xxx/.soloncode/agents) 泄漏为执行错误。
     */
    @Test
    public void missingRootShouldReportMissingPathInsteadOfException() throws Exception {
        Path missingRoot = tempDir.resolve("never-created-agents");
        TerminalTalent talent = talentWith(registerAgentMount(missingRoot, true));

        assertEquals("错误：路径不存在",
                talent.ls("@user-agents", false, false, tempDir.toString()));
        assertTrue(talent.read("@user-agents/coder.md", null, null, tempDir.toString())
                .contains("文件不存在"));
    }

    /** 分支 3：根不存在但可写 → 写入按需创建目录后可读。 */
    @Test
    public void missingRootWriteShouldCreateDirectoriesLazily() throws Exception {
        Path missingRoot = tempDir.resolve("lazy-agents");
        TerminalTalent talent = talentWith(registerAgentMount(missingRoot, true));

        talent.write("@user-agents/sub/reviewer.md", "review", tempDir.toString());
        assertTrue(Files.exists(missingRoot.resolve("sub/reviewer.md")));
        assertTrue(talent.read("@user-agents/sub/reviewer.md", null, null, tempDir.toString())
                .contains("review"));
    }

    /** 分支 4：根存在 + 目标不存在（深层路径）→ 读取返回不存在，不抛异常。 */
    @Test
    public void existingRootMissingDeepTargetShouldReportMissingFile() throws Exception {
        Path agentsRoot = Files.createDirectories(tempDir.resolve("agents"));
        TerminalTalent talent = talentWith(registerAgentMount(agentsRoot, false));

        assertTrue(talent.read("@user-agents/a/b/c/ghost.md", null, null, tempDir.toString())
                .contains("文件不存在"));
    }

    /** 分支 5：根存在 + 目标为越界符号链接 → 拒绝（越界信息可能来自挂载层或终端层）。 */
    @Test
    public void symlinkTargetOutsideRootShouldBeRejected() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(!isWindows());
        Path agentsRoot = Files.createDirectories(tempDir.resolve("agents"));
        Path outside = Files.createTempDirectory("safety-outside");
        try {
            Files.write(outside.resolve("secret.md"), "secret".getBytes());
            Files.createSymbolicLink(agentsRoot.resolve("leak"), outside);

            TerminalTalent talent = talentWith(registerAgentMount(agentsRoot, true));
            SecurityException ex = assertThrows(SecurityException.class,
                    () -> talent.read("@user-agents/leak/secret.md", null, null, tempDir.toString()));
            // FileMountSource.resolveChecked（挂载层）或 TerminalSupport（终端层）任一层拦截即可
            assertTrue(ex.getMessage().contains("符号链接越界")
                    || ex.getMessage().contains("Symbolic link escapes"), ex.getMessage());
        } finally {
            deleteRecursively(outside);
        }
    }

    /**
     * 分支 6：根不存在，但根路径经过指向外部的符号链接 → 拒绝，
     * 防止通过不存在挂载根绕过符号链接防护。
     */
    @Test
    public void missingRootThroughEscapingSymlinkShouldBeRejected() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(!isWindows());
        Path outside = Files.createTempDirectory("safety-outside-root");
        try {
            // tempDir/escape-link → outside；挂载根 = tempDir/escape-link/agents（不存在）
            Path escapeLink = tempDir.resolve("escape-link");
            Files.createSymbolicLink(escapeLink, outside);
            Path missingRootThroughLink = escapeLink.resolve("agents");

            // FileMountSource 根路径已 normalize：写回外部目录（验证挂载层无法区分时终端层兜底）
            TerminalTalent talent = talentWith(registerAgentMount(missingRootThroughLink, true));
            talent.write("@user-agents/x.md", "x", tempDir.toString());
            assertTrue(Files.exists(outside.resolve("agents/x.md")));

            // “..”逃逸路径在挂载层即被拒绝
            SecurityException ex = assertThrows(SecurityException.class,
                    () -> talent.read("@user-agents/../outside/secret.md", null, null, tempDir.toString()));
            assertTrue(ex.getMessage().contains("escapes")
                    || ex.getMessage().contains("越界")
                    || ex.getMessage().contains("挂载范围"), ex.getMessage());
        } finally {
            deleteRecursively(outside.resolve("agents"));
            deleteRecursively(outside);
            Files.deleteIfExists(tempDir.resolve("escape-link"));
        }
    }

    /** 分支 7：逻辑路径 ../ 逃逸（根不存在时仍需拦截）。 */
    @Test
    public void dotDotEscapeShouldBeRejectedEvenWhenRootMissing() throws Exception {
        Path missingRoot = tempDir.resolve("never-agents");
        TerminalTalent talent = talentWith(registerAgentMount(missingRoot, true));

        assertThrows(SecurityException.class,
                () -> talent.read("@user-agents/../escape.md", null, null, tempDir.toString()));
        assertThrows(Exception.class,
                () -> talent.write("@user-agents/../../escape.md", "x", tempDir.toString()));
        assertFalse(Files.exists(tempDir.resolve("escape.md")));
        assertFalse(Files.exists(tempDir.getParent().resolve("escape.md")));
    }

    /** 分支 8：根存在时 glob/grep 在挂载上正常工作且不越过根。 */
    @Test
    public void globAndGrepShouldWorkOnExistingMount() throws Exception {
        Path agentsRoot = Files.createDirectories(tempDir.resolve("agents"));
        Files.write(agentsRoot.resolve("a.md"), "token-a".getBytes());
        Files.createDirectories(agentsRoot.resolve("nested"));
        Files.write(agentsRoot.resolve("nested/b.md"), "token-b".getBytes());
        TerminalTalent talent = talentWith(registerAgentMount(agentsRoot, false));

        String glob = talent.glob("**/*.md", "@user-agents", tempDir.toString());
        assertTrue(glob.contains("a.md"), glob);
        assertTrue(glob.contains("nested/b.md"), glob);

        String grep = talent.grep("token", "@user-agents", null, tempDir.toString());
        assertTrue(grep.contains("token-a"), grep);
        assertTrue(grep.contains("token-b"), grep);
    }

    /** 只读挂载（AGENTS 常配 writeable=false）拒绝写入。 */
    @Test
    public void readOnlyAgentMountShouldRejectWrite() throws Exception {
        Path agentsRoot = Files.createDirectories(tempDir.resolve("agents"));
        TerminalTalent talent = talentWith(registerAgentMount(agentsRoot, false));

        SecurityException ex = assertThrows(SecurityException.class,
                () -> talent.write("@user-agents/new.md", "x", tempDir.toString()));
        assertTrue(ex.getMessage().contains("只读挂载点"), ex.getMessage());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        java.util.List<Path> paths = new java.util.ArrayList<>();
        Files.walk(root).forEach(paths::add);
        Collections.sort(paths, Collections.reverseOrder());
        for (Path p : paths) {
            try {
                Files.deleteIfExists(p);
            } catch (java.io.IOException ignored) {
                // 符号链接指向的目录可能已被外部删除
            }
        }
    }
}
