package features.ai.talents.mount;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.talents.mount.Mount;
import org.noear.solon.ai.talents.mount.MountManager;
import org.noear.solon.ai.talents.mount.MountType;
import org.noear.solon.ai.talents.mount.catalog.SkillDescriptor;
import org.noear.solon.ai.talents.mount.source.FileMountSource;
import org.noear.solon.ai.talents.mount.source.FindOptions;
import org.noear.solon.ai.talents.mount.source.MountCapabilities;
import org.noear.solon.ai.talents.mount.source.MountEntry;
import org.noear.solon.ai.talents.mount.source.MountSource;
import org.noear.solon.ai.talents.mount.source.MoveOptions;
import org.noear.solon.ai.talents.mount.source.WriteOptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 挂载发现阶段的健壮性：单个来源或条目异常（越界符号链接/junction、权限不足、
 * 来源自身故障）只能跳过，不得冒泡中断挂载注册与技能/子代理目录刷新，
 * 否则个别坏目录会让整个工作区启动失败。
 */
public class MountScanResilienceTest {
    @TempDir
    Path tempDir;

    /** 发现阶段即抛异常的来源，模拟 Windows junction 越界等真实故障。 */
    private static final class BrokenMountSource implements MountSource {
        private final RuntimeException error;

        BrokenMountSource(RuntimeException error) {
            this.error = error;
        }

        @Override
        public String getScheme() {
            return "broken";
        }

        @Override
        public String normalize(String path) {
            return path == null ? "" : path;
        }

        @Override
        public MountEntry stat(String path) {
            throw error;
        }

        @Override
        public List<MountEntry> list(String path) {
            throw error;
        }

        @Override
        public List<MountEntry> find(String path, FindOptions options) {
            throw error;
        }

        @Override
        public InputStream openRead(String path) {
            throw error;
        }

        @Override
        public OutputStream openWrite(String path, WriteOptions options) {
            throw error;
        }

        @Override
        public void delete(String path) {
            throw error;
        }

        @Override
        public void move(String source, String target, MoveOptions options) {
            throw error;
        }

        @Override
        public MountCapabilities capabilities() {
            return new MountCapabilities(true, true, true, true, true, true, true, false, false, false);
        }
    }

    /**
     * 构造真实布局的挂载根：root 下每个技能一个子目录，各自带 SKILL.md
     * （发现阶段按 **\/SKILL.md 扫描，至少一层子目录才命中，与 /Users/noear/.agents/skills 形态一致）。
     */
    private Path newSkillsRoot(String rootName, String skillName, String description) throws Exception {
        Path root = Files.createDirectories(tempDir.resolve(rootName));
        Path dir = Files.createDirectories(root.resolve(skillName));
        Files.write(dir.resolve("SKILL.md"),
                ("---\nname: " + skillName + "\ndescription: " + description + "\n---\nbody")
                        .getBytes(StandardCharsets.UTF_8));
        return root;
    }

    @Test
    public void brokenSourceShouldNotBreakRegisterOrOtherSkills() throws Exception {
        Path healthy = newSkillsRoot("healthy-mount", "healthy", "healthy skill");
        MountManager manager = new MountManager(tempDir.toString());

        // 注册坏来源：确认不抛异常（异常在发现阶段被吞掉）
        Mount broken = manager.register(Mount.builder()
                .alias("broken")
                .type(MountType.SKILLS)
                .source(new BrokenMountSource(new SecurityException("Symbolic link escapes mount root: ego-browser/SKILL.md")))
                .build());
        assertEquals("@broken", broken.getAlias());

        manager.register(Mount.builder()
                .alias("healthy")
                .type(MountType.SKILLS)
                .source(FileMountSource.of(healthy))
                .build());

        // 坏来源不产生技能，但健康来源照常可发现
        SkillDescriptor descriptor = manager.getSkillCatalog().getDescriptor("@healthy/healthy");
        assertNotNull(descriptor);
        assertEquals("healthy skill", descriptor.getDescription());
        assertEquals(1, manager.getSkillCatalog().getSkillCount());
    }

    @Test
    public void brokenAgentSourceShouldNotBreakRegister() throws Exception {
        MountManager manager = new MountManager(tempDir.toString());

        manager.register(Mount.builder()
                .alias("broken-agents")
                .type(MountType.AGENTS)
                .source(new BrokenMountSource(new SecurityException("Symbolic link escapes mount root: link/agent.md")))
                .build());

        assertTrue(manager.getAgentCatalog().getAgents().isEmpty());
    }

    @Test
    public void brokenSourceShouldNotBreakRefreshOfOtherMounts() throws Exception {
        Path healthy = newSkillsRoot("refresh-mount", "refreshable", "refreshable skill");
        MountManager manager = new MountManager(tempDir.toString());
        manager.register(Mount.builder().alias("broken").type(MountType.SKILLS)
                .source(new BrokenMountSource(new java.io.UncheckedIOException(new IOException("mount unavailable")))).build());
        manager.register(Mount.builder().alias("healthy").type(MountType.SKILLS)
                .source(FileMountSource.of(healthy)).build());

        // 后续任意刷新（新增挂载 / 移除挂载）都不能被坏来源拖垮
        manager.register(Mount.builder().alias("second").type(MountType.SKILLS)
                .source(FileMountSource.of(newSkillsRoot("second-mount", "second", "second skill"))).build());
        assertEquals(2, manager.getSkillCatalog().getSkillCount());

        manager.remove("second");
        assertNotNull(manager.getSkillCatalog().getDescriptor("@healthy/refreshable"));
    }

    @Test
    public void skillContentShouldStillBeReadableFromHealthyMount() throws Exception {
        MountManager manager = new MountManager(tempDir.toString());
        manager.register(Mount.builder().alias("broken").type(MountType.SKILLS)
                .source(new BrokenMountSource(new IllegalStateException("boom"))).build());
        manager.register(Mount.builder().alias("healthy").type(MountType.SKILLS)
                .source(FileMountSource.of(newSkillsRoot("read-mount", "readable", "desc"))).build());

        assertNotNull(manager.getSkillCatalog().readContent("@healthy/readable"));
        assertFalse(manager.getSkillCatalog().getDescriptors().isEmpty());
    }

    @Test
    public void missingMountRootShouldBeTolerated() {
        MountManager manager = new MountManager(tempDir.toString());
        // 挂载根不存在（AGENTS/SKILLS 目录常见形态）时，注册与扫描都应静默通过
        manager.register(Mount.builder().alias("ghost").type(MountType.SKILLS)
                .source(FileMountSource.of(tempDir.resolve("never-created"))).build());

        assertEquals(0, manager.getSkillCatalog().getSkillCount());
        assertTrue(manager.hasMount("ghost"));
    }

    @Test
    public void unreadableSkillMarkerShouldOnlySkipThatSkill() throws Exception {
        Path dir = tempDir.resolve("mixed");
        Path good = Files.createDirectories(dir.resolve("good"));
        Files.write(good.resolve("SKILL.md"),
                "---\nname: good\ndescription: good skill\n---\nbody".getBytes(StandardCharsets.UTF_8));
        // 空标记文件：解析失败也不能影响同一来源的其它技能
        Files.createDirectories(dir.resolve("empty"));
        Files.write(dir.resolve("empty/SKILL.md"), new byte[0]);

        MountManager manager = new MountManager(tempDir.toString());
        manager.register(Mount.builder().alias("mixed").type(MountType.SKILLS)
                .source(FileMountSource.of(dir)).build());

        assertNotNull(manager.getSkillCatalog().getDescriptor("@mixed/good"));
    }

    @Test
    public void findShouldSkipSymlinkEntriesButKeepSiblings() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("walk-root"));
        Files.write(root.resolve("a.txt"), "a".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("b.txt"), "b".getBytes(StandardCharsets.UTF_8));

        FileMountSource source = FileMountSource.of(root);
        // 遍历本身不应因单个条目失败而抛异常
        assertEquals(2, source.find("", FindOptions.builder().filesOnly(true).maxDepth(3).build()).size());

        // 越界符号链接目录：显式访问仍应拒绝（安全语义不变），
        // 但发现阶段既不能抛异常、不能带入外部内容，也不能连累同级的健康条目。
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            Path outside = Files.createTempDirectory("walk-outside");
            try {
                Files.write(outside.resolve("secret.txt"), "s".getBytes(StandardCharsets.UTF_8));
                Files.createSymbolicLink(root.resolve("out"), outside);

                assertThrows(SecurityException.class, () -> source.stat("out/secret.txt"));

                List<MountEntry> found = source.find("", FindOptions.builder().filesOnly(true).maxDepth(5).build());
                assertTrue(found.stream().noneMatch(e -> e.getPath().contains("secret.txt")));
                // 关键：坏条目只被跳过，同级健康条目照常可见（证明是逐条跳过而非整次扫描终止）
                assertTrue(found.stream().anyMatch(e -> e.getPath().equals("a.txt")));
                assertTrue(found.stream().anyMatch(e -> e.getPath().equals("b.txt")));
            } finally {
                Files.walk(outside).sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    public void brokenSourceShouldNotAffectExplicitReadsOfOthers() throws Exception {
        MountManager manager = new MountManager(tempDir.toString());
        manager.register(Mount.builder().alias("broken").type(MountType.SKILLS)
                .source(new BrokenMountSource(new SecurityException("boom"))).build());

        // 坏来源不影响工作区本身的路径解析
        Path file = tempDir.resolve("note.txt");
        Files.write(file, "hi".getBytes(StandardCharsets.UTF_8));
        assertEquals(file, manager.resolve(tempDir, "note.txt"));
        assertTrue(Files.exists(manager.resolve(tempDir, "note.txt")));
    }
}
