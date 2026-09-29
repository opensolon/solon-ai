package features.ai.talents.mount;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.talents.mount.*;
import org.noear.solon.ai.talents.mount.catalog.DefaultAgentCatalog;
import org.noear.solon.ai.talents.mount.source.ClasspathMountSource;
import org.noear.solon.ai.talents.mount.source.FileMountSource;
import org.noear.solon.ai.talents.mount.source.FindOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

public class MountManagerTest {
    @TempDir
    Path tempDir;

    @Test
    public void registerAliasWithoutAtShouldBeNormalized() throws Exception {
        Path pool = Files.createDirectory(tempDir.resolve("pool"));
        MountManager manager = new MountManager(tempDir.toString());

        Mount mount = manager.register(Mount.builder()
                .alias("pool")
                .source(FileMountSource.of(pool))
                .type(MountType.SKILLS)
                .build());

        assertEquals("@pool", mount.getAlias());
        assertSame(mount, manager.getMount("pool"));
        assertTrue(manager.hasMount("@pool"));
        assertEquals(pool.resolve("note.txt"), manager.resolve(tempDir, "@pool/note.txt"));
        assertSame(mount, manager.remove("pool"));
        assertFalse(manager.hasMount("@pool"));
        assertNull(manager.remove("pool"));
    }

    @Test
    public void registerInvalidAliasShouldReject() {
        MountManager manager = new MountManager(tempDir.toString());
        assertThrows(IllegalArgumentException.class, () -> register(manager, ""));
        assertThrows(IllegalArgumentException.class, () -> register(manager, "@"));
        assertThrows(IllegalArgumentException.class, () -> register(manager, "@pool/sub"));
        assertThrows(IllegalArgumentException.class, () -> register(manager, "@pool\\sub"));
        assertThrows(IllegalArgumentException.class, () -> register(manager, "@pool name"));
    }

    @Test
    public void resolveMountPathShouldRejectDotDotEscape() throws Exception {
        Path pool = Files.createDirectory(tempDir.resolve("pool"));
        MountManager manager = new MountManager(tempDir.toString());
        register(manager, "@pool", pool);

        assertThrows(SecurityException.class, () -> manager.resolve(tempDir, "@pool/../outside.txt"));
    }

    @Test
    public void resolveDisabledOrUnknownAtPathShouldFailClosed() throws Exception {
        Path pool = Files.createDirectory(tempDir.resolve("pool"));
        MountManager manager = new MountManager(tempDir.toString());
        manager.register(Mount.builder()
                .alias("@pool")
                .source(FileMountSource.of(pool))
                .type(MountType.SKILLS)
                .enabled(false)
                .build());

        assertThrows(SecurityException.class, () -> manager.resolve(tempDir, "@pool/a.txt"));
        assertThrows(SecurityException.class, () -> manager.resolve(tempDir, "@missing/a.txt"));
    }

    @Test
    public void mountListOrderShouldFollowRegistrationOrderAndSnapshot() throws Exception {
        MountManager manager = new MountManager(tempDir.toString());
        register(manager, "@b", Files.createDirectory(tempDir.resolve("b")));
        register(manager, "@a", Files.createDirectory(tempDir.resolve("a")));
        register(manager, "@c", Files.createDirectory(tempDir.resolve("c")));

        Collection<Mount> snapshot = manager.getMounts();
        ArrayList<String> aliases = new ArrayList<>();
        snapshot.forEach(m -> aliases.add(m.getAlias()));
        assertEquals("@b", aliases.get(0));
        assertEquals("@a", aliases.get(1));
        assertEquals("@c", aliases.get(2));
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        assertThrows(UnsupportedOperationException.class, () -> manager.getMountKeySet().clear());
        manager.remove("@a");
        assertEquals(3, snapshot.size());
        assertEquals(2, manager.getMountKeySet().size());
    }

    @Test
    public void fileMountFindShouldReturnNoEntriesWhenMaxEntriesIsZero() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("find-root"));
        Files.write(root.resolve("file.txt"), "content".getBytes());
        FileMountSource source = FileMountSource.of(root);

        assertTrue(source.find("", FindOptions.builder().maxEntries(0).build()).isEmpty());
    }

    @Test
    public void fileMountShouldResolveResource() throws Exception {
        Path pool = Files.createDirectory(tempDir.resolve("skills"));
        Path demo = Files.createDirectory(pool.resolve("demo"));
        Files.write(demo.resolve("SKILL.md"), "---\nname: demo\ndescription: Demo skill\n---\n".getBytes());
        MountManager manager = new MountManager(tempDir.toString());
        Mount mount = register(manager, "skills", pool);

        assertSame(mount, manager.getMount("skills"));
        assertEquals(pool, ((FileMountSource) mount.getSource()).getRootPath());
        assertSame(mount, manager.resolveResource("@skills/demo/SKILL.md").getMount());
        assertEquals(1, manager.getMounts().size());
        assertSame(mount, manager.remove("skills"));
        assertTrue(manager.getMounts().isEmpty());
    }

    @Test
    public void agentCatalogShouldFilterByMountAndRefresh() throws Exception {
        Path first = Files.createDirectory(tempDir.resolve("agents-a"));
        Path second = Files.createDirectory(tempDir.resolve("agents-b"));
        Files.write(first.resolve("review.md"), "---\nname: review\ndescription: test\n---\nreview".getBytes());
        Files.write(second.resolve("deploy.md"), "---\nname: deploy\ndescription: test\n---\ndeploy".getBytes());
        MountManager manager = new MountManager(tempDir.toString());
        manager.register(Mount.builder().alias("agents-a").source(FileMountSource.of(first))
                .type(MountType.AGENTS).build());
        manager.register(Mount.builder().alias("agents-b").source(FileMountSource.of(second))
                .type(MountType.AGENTS).build());
        DefaultAgentCatalog catalog = new DefaultAgentCatalog(manager);

        assertEquals(2, catalog.getAgents().size());
        assertEquals(1, catalog.getAgentsByMount("agents-a").size());
        assertEquals("review", catalog.getAgentsByMount("@agents-a").iterator().next().getName());
        assertTrue(catalog.getAgentsByMount("missing").isEmpty());

        Files.delete(first.resolve("review.md"));
        catalog.refreshByMount("agents-a");
        assertTrue(catalog.getAgentsByMount("agents-a").isEmpty());
        assertSame(first, ((FileMountSource) manager.getMount("agents-a").getSource()).getRootPath());
    }

    @Test
    public void agentNameShouldComeFromFrontmatterAndMissingNameShouldBeSkipped() throws Exception {
        Path pool = Files.createDirectory(tempDir.resolve("named-agents"));
        Files.write(pool.resolve("file-name.md"), "---\nname: declared-name\ndescription: declared description\n---\nprompt".getBytes());
        Files.write(pool.resolve("missing-name.md"), "---\ndescription: no name\n---\nprompt".getBytes());
        MountManager manager = new MountManager(tempDir.toString());
        manager.register(Mount.builder().alias("named-agents").source(FileMountSource.of(pool))
                .type(MountType.AGENTS).build());
        DefaultAgentCatalog catalog = new DefaultAgentCatalog(manager);

        assertNotNull(catalog.getAgent("declared-name"));
        assertNull(catalog.getAgent("file-name"));
        assertEquals("declared description", catalog.getAgent("declared-name").getDescription());
        assertEquals(1, catalog.getAgents().size());
    }

    @Test
    public void replacingMountShouldUpdateCatalogAfterRefresh() throws Exception {
        Path pool = Files.createDirectory(tempDir.resolve("old"));
        Files.write(pool.resolve("old.md"), "---\nname: old\ndescription: test\n---\nold".getBytes());
        MountManager manager = new MountManager(tempDir.toString());
        manager.register(Mount.builder().alias("agents").source(FileMountSource.of(pool))
                .type(MountType.AGENTS).build());
        DefaultAgentCatalog catalog = new DefaultAgentCatalog(manager);
        assertNotNull(catalog.getAgent("old"));
        Mount replacement = manager.register(Mount.builder().alias("agents").type(MountType.FILES)
                .source(FileMountSource.of(tempDir)).build());
        catalog.refreshByMount("agents");
        assertSame(replacement, manager.getMount("agents"));
        assertNull(catalog.getAgent("old"));
        assertEquals(1, manager.getMounts().size());
        assertEquals(tempDir, ((FileMountSource) manager.resolveResource("@agents").getSource()).getRootPath());
    }

    @Test
    public void workspaceAliasShouldBeReservedAndResolveConsistently() {
        MountManager manager = new MountManager(tempDir.toString());
        assertThrows(IllegalArgumentException.class, () -> register(manager, "workspace"));
        assertThrows(IllegalArgumentException.class, () -> manager.register(Mount.builder()
                .alias("@workspace").source(FileMountSource.of(tempDir)).build()));
        assertEquals(tempDir, manager.resolve(tempDir, "@workspace"));
        assertEquals(tempDir.resolve("note.txt"), manager.resolve(tempDir, "@workspace\\note.txt"));
        assertEquals(tempDir.resolve("note.txt"), manager.resolveResource("@workspace/note.txt").getLocalPath().orElse(null));
        assertNull(manager.resolveResource("@workspace").getMount());
        assertThrows(SecurityException.class, () -> manager.resolveResource("@workspace/../outside"));
        assertThrows(SecurityException.class, () -> manager.resolve(tempDir, "@workspace/../outside"));
        assertThrows(SecurityException.class, () -> manager.resolveResource("@workspace-other/file"));
    }

    @Test
    public void localResolveShouldRejectEscapesAndNonLocalSources() throws Exception {
        MountManager manager = new MountManager(tempDir.toString());
        assertThrows(SecurityException.class, () -> manager.resolve(tempDir, "../outside"));
        assertThrows(SecurityException.class, () -> manager.resolve(tempDir, "/outside"));
        Path link = tempDir.resolve("link");
        Files.createSymbolicLink(link, tempDir.getParent());
        assertThrows(SecurityException.class, () -> manager.resolve(tempDir, "link/other"));
        manager.register(Mount.builder().alias("virtual").type(MountType.SKILLS)
                .source(ClasspathMountSource.of("test-skills")).build());
        assertThrows(SecurityException.class, () -> manager.resolve(tempDir, "@virtual/demo/SKILL.md"));
        assertEquals("classpath", manager.resolveResource("@virtual/demo/SKILL.md").getSource().getScheme());
    }

    @Test
    public void disallowSkillShouldSurviveRefresh() {
        MountManager manager = new MountManager(tempDir.toString());
        manager.disallowSkill("@skills/demo");
        assertTrue(manager.getDisallowSkills().contains("@skills/demo"));
        assertTrue(manager.isSkillDisallowed("@skills/demo"));
        manager.allowSkill("@skills/demo");
        assertFalse(manager.isSkillDisallowed("@skills/demo"));
        manager.setDisallowSkills(java.util.Collections.singleton("@skills/other"));
        assertTrue(manager.isSkillDisallowed("@skills/other"));
        // 空/null 别名不生效
        manager.disallowSkill("");
        manager.allowSkill(null);
        manager.setDisallowSkills(null);
        assertTrue(manager.getDisallowSkills().isEmpty());
    }

    // --- parseRealPath ---

    @Test
    public void parseRealPathShouldExpandHomeAndWorkspaceSyntax() {
        MountManager manager = new MountManager(tempDir.toString());
        String userHome = System.getProperty("user.home");

        // 空/null → 工作区
        assertEquals(tempDir.toAbsolutePath().normalize(), manager.parseRealPath(null));
        assertEquals(tempDir.toAbsolutePath().normalize(), manager.parseRealPath(""));
        assertEquals(tempDir.toAbsolutePath().normalize(), manager.parseRealPath("."));

        // ~/ 展开
        assertEquals(java.nio.file.Paths.get(userHome).toAbsolutePath().normalize(),
                manager.parseRealPath("~"));
        assertEquals(java.nio.file.Paths.get(userHome, "skills").toAbsolutePath().normalize(),
                manager.parseRealPath("~/skills"));

        // ./ 展开
        assertEquals(tempDir.resolve("sub").toAbsolutePath().normalize(),
                manager.parseRealPath("./sub"));

        // 相对路径 → 基于进程 cwd
        assertEquals(tempDir.toAbsolutePath().normalize(), manager.parseRealPath(tempDir.toString()));
    }

    // --- resolveResource 分支 ---

    @Test
    public void resolveResourceShouldHandleBlankDotAndWorkspaceForms() throws Exception {
        MountManager manager = new MountManager(tempDir.toString());

        // null/空/. → 工作区根
        assertEquals("", manager.resolveResource(null).getSourcePath());
        assertEquals("", manager.resolveResource("").getSourcePath());
        assertEquals("", manager.resolveResource(".").getSourcePath());
        assertEquals("@workspace", manager.resolveResource(null).getMountAlias());
        assertSame(manager.getWorkspaceSource(), manager.resolveResource(null).getSource());
        assertNull(manager.resolveResource(null).getMount());

        // 相对路径 → 工作区子路径
        assertEquals("a/b.md", manager.resolveResource("a/b.md").getSourcePath());
        assertEquals("a/b.md", manager.resolveResource("./a/b.md").getSourcePath());

        // @workspace 别名 → 工作区
        assertEquals("", manager.resolveResource("@workspace").getSourcePath());
        assertEquals("note.txt", manager.resolveResource("@workspace/note.txt").getSourcePath());

        // @workspace-xxx 不是保留别名，作为普通挂载名查找 → 未注册时拒绝
        assertThrows(SecurityException.class, () -> manager.resolveResource("@workspace-other/f"));
    }

    @Test
    public void resolveResourceShouldRejectEscapeUnderMountAndWorkspace() throws Exception {
        MountManager manager = new MountManager(tempDir.toString());
        // ".." 片段保留在归一化结果中 → 拒绝
        assertThrows(SecurityException.class, () -> manager.resolveResource("../outside"));
        assertThrows(SecurityException.class, () -> manager.resolveResource("a/../../outside"));

        // 前导 "/" 在 normalize 中被剥离，绝对路径降级为工作区相对路径（与 resolve() 的显式拦截不同）
        org.noear.solon.ai.talents.mount.ResolvedResource degraded =
                manager.resolveResource("/absolute/path");
        assertEquals("absolute/path", degraded.getSourcePath());
    }

    @Test
    public void resolveShouldReturnWorkDirForBlankAndDot() {
        MountManager manager = new MountManager(tempDir.toString());
        assertEquals(tempDir, manager.resolve(tempDir, null));
        assertEquals(tempDir, manager.resolve(tempDir, ""));
        assertEquals(tempDir, manager.resolve(tempDir, "."));
    }

    @Test
    public void registerNullMountOrSourceShouldReject() {
        MountManager manager = new MountManager(tempDir.toString());
        assertThrows(IllegalArgumentException.class, () -> manager.register(null));
        assertThrows(IllegalArgumentException.class, () -> manager.register(
                Mount.builder().alias("@x").build()));
        assertThrows(IllegalArgumentException.class, () -> manager.getMount(null));
    }

    private Mount register(MountManager manager, String alias) {
        return register(manager, alias, tempDir);
    }

    private Mount register(MountManager manager, String alias, Path path) {
        return manager.register(Mount.builder()
                .alias(alias)
                .source(FileMountSource.of(path))
                .type(MountType.SKILLS)
                .build());
    }
}
