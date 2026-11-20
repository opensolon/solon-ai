package features.ai.mount;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.mount.Mount;
import org.noear.solon.ai.mount.MountManager;
import org.noear.solon.ai.mount.MountType;
import org.noear.solon.ai.mount.catalog.DefaultAgentCatalog;
import org.noear.solon.ai.mount.catalog.DefaultSkillCatalog;
import org.noear.solon.ai.mount.source.FileMountSource;
import org.noear.solon.ai.mount.source.FindOptions;
import org.noear.solon.ai.mount.source.MountCapabilities;
import org.noear.solon.ai.mount.source.MountEntry;
import org.noear.solon.ai.mount.source.MountSource;
import org.noear.solon.ai.mount.source.MoveOptions;
import org.noear.solon.ai.mount.source.WriteOptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能/子代理目录只在相关挂载变化时刷新：
 *
 * <ul>
 *   <li>普通文件挂载的注册/移除不再触发技能、子代理挂载的重扫；</li>
 *   <li>技能/子代理挂载的注册、移除、禁用照常生效；</li>
 *   <li>同名的技能/子代理挂载被其他类型挂载替换时，旧条目必须消失（监听器只拿到新挂载）。</li>
 * </ul>
 */
public class MountCatalogRefreshTest {
    @TempDir
    Path tempDir;

    private MountManager manager;
    private DefaultSkillCatalog skills;
    private DefaultAgentCatalog agents;
    private CountingSource skillSource;
    private CountingSource agentSource;

    private void setUp() throws Exception {
        manager = new MountManager(tempDir.toString());
        skills = new DefaultSkillCatalog(manager);
        agents = new DefaultAgentCatalog(manager);

        Path skillRoot = Files.createDirectories(tempDir.resolve("skills/demo"));
        Files.write(skillRoot.resolve("SKILL.md"),
                "---\nname: demo\ndescription: demo skill\n---\nbody".getBytes(StandardCharsets.UTF_8));
        Path agentRoot = Files.createDirectories(tempDir.resolve("agents"));
        Files.write(agentRoot.resolve("coder.md"),
                "---\nname: \"coder\"\ndescription: \"Code expert\"\n---\nYou are a code expert.".getBytes(StandardCharsets.UTF_8));

        skillSource = new CountingSource(FileMountSource.of(tempDir.resolve("skills")));
        agentSource = new CountingSource(FileMountSource.of(agentRoot));
        manager.register(Mount.builder().alias("skills").type(MountType.SKILLS).source(skillSource).build());
        manager.register(Mount.builder().alias("agents").type(MountType.AGENTS).source(agentSource).build());

        assertNotNull(skills.getDescriptor("@skills/demo"));
        assertNotNull(agents.getAgent("coder"));
    }

    private Mount filesMount(String alias) throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("pool-" + alias.replace("@", "")));
        return Mount.builder().alias(alias).type(MountType.FILES).source(FileMountSource.of(dir)).build();
    }

    @Test
    public void registeringOrRemovingFileMountsDoesNotRescan() throws Exception {
        setUp();
        int skillFinds = skillSource.finds.get();
        int agentFinds = agentSource.finds.get();

        for (int i = 0; i < 10; i++) {
            manager.register(filesMount("pool" + i));
        }
        manager.remove("pool3");

        assertEquals(skillFinds, skillSource.finds.get(), "文件挂载不影响技能目录：不重扫技能挂载");
        assertEquals(agentFinds, agentSource.finds.get(), "文件挂载不影响子代理目录：不重扫子代理挂载");
        assertNotNull(skills.getDescriptor("@skills/demo"));
        assertNotNull(agents.getAgent("coder"));
    }

    @Test
    public void registeringSkillOrAgentMountsStillRefreshes() throws Exception {
        setUp();
        Path second = Files.createDirectories(tempDir.resolve("skills2/other"));
        Files.write(second.resolve("SKILL.md"),
                "---\nname: other\ndescription: other skill\n---\nbody".getBytes(StandardCharsets.UTF_8));
        manager.register(Mount.builder().alias("skills2").type(MountType.SKILLS)
                .source(FileMountSource.of(tempDir.resolve("skills2"))).build());
        assertNotNull(skills.getDescriptor("@skills2/other"));

        Path agents2 = Files.createDirectories(tempDir.resolve("agents2"));
        Files.write(agents2.resolve("reviewer.md"),
                "---\nname: \"reviewer\"\ndescription: \"Reviewer\"\n---\nReview.".getBytes(StandardCharsets.UTF_8));
        manager.register(Mount.builder().alias("agents2").type(MountType.AGENTS)
                .source(FileMountSource.of(agents2)).build());
        assertNotNull(agents.getAgent("reviewer"));
    }

    @Test
    public void replacingWithSameAliasFileMountDropsOldEntries() throws Exception {
        setUp();
        manager.register(filesMount("skills"));
        assertNull(skills.getDescriptor("@skills/demo"), "技能挂载被同名文件挂载替换：旧技能消失");
        assertEquals(0, skills.getSkillCount());

        manager.register(filesMount("agents"));
        assertNull(agents.getAgent("coder"), "子代理挂载被同名文件挂载替换：旧子代理消失");
        assertTrue(agents.getAgents().isEmpty());
    }

    @Test
    public void replacingRootLevelSkillMountDropsIt() throws Exception {
        // 标记文件在挂载根：技能 id 就是挂载别名本身（不带 "/"）。
        // FileMountSource 的 **/SKILL.md 至少要一层目录，这里用 glob 语义不同的来源模拟（根上也能命中）
        setUp();
        Path single = Files.createDirectories(tempDir.resolve("single"));
        Files.write(single.resolve("SKILL.md"),
                "---\nname: single\ndescription: single skill\n---\nbody".getBytes(StandardCharsets.UTF_8));
        CountingSource rootMarker = new CountingSource(FileMountSource.of(single));
        rootMarker.rootSkillMarker = true;
        manager.register(Mount.builder().alias("single").type(MountType.SKILLS)
                .source(rootMarker).build());
        assertNotNull(skills.getDescriptor("@single"));

        manager.register(filesMount("single"));
        assertNull(skills.getDescriptor("@single"));
    }

    @Test
    public void removingSkillOrAgentMountDropsEntries() throws Exception {
        setUp();
        manager.remove("skills");
        assertNull(skills.getDescriptor("@skills/demo"));
        manager.remove("agents");
        assertNull(agents.getAgent("coder"));
    }

    @Test
    public void disablingSkillOrAgentMountDropsEntries() throws Exception {
        setUp();
        manager.register(Mount.builder().alias("skills").type(MountType.SKILLS).enabled(false).source(skillSource).build());
        assertNull(skills.getDescriptor("@skills/demo"));
        manager.register(Mount.builder().alias("agents").type(MountType.AGENTS).enabled(false).source(agentSource).build());
        assertNull(agents.getAgent("coder"));
    }

    @Test
    public void explicitRefreshByMountStillRefreshesFully() throws Exception {
        setUp();
        Files.delete(tempDir.resolve("skills/demo/SKILL.md"));
        Files.delete(tempDir.resolve("agents/coder.md"));
        // 显式调用的语义不变：不看别名、全量刷新
        skills.refreshByMount("@pool-unrelated");
        agents.refreshByMount("@pool-unrelated");
        assertNull(skills.getDescriptor("@skills/demo"));
        assertNull(agents.getAgent("coder"));
    }

    /** 委托式来源：只统计 find 调用次数（技能/子代理目录的扫描都经 find）。 */
    private static final class CountingSource implements MountSource {
        private final MountSource d;
        final AtomicInteger finds = new AtomicInteger();
        /** 为 true 时技能标记扫描也命中挂载根上的 SKILL.md（模拟 glob 语义不同的来源） */
        boolean rootSkillMarker;

        CountingSource(MountSource d) {
            this.d = d;
        }

        @Override
        public List<MountEntry> find(String path, FindOptions options) throws IOException {
            finds.incrementAndGet();
            List<MountEntry> result = new ArrayList<>(d.find(path, options));
            if (rootSkillMarker && options != null && "**/SKILL.md".equals(options.getGlob())) {
                MountEntry marker = d.stat("SKILL.md");
                if (marker != null) {
                    result.add(marker);
                }
            }
            return result;
        }

        @Override
        public String getLocation() {
            return d.getLocation();
        }

        @Override
        public String getScheme() {
            return d.getScheme();
        }

        @Override
        public String normalize(String path) {
            return d.normalize(path);
        }

        @Override
        public MountEntry stat(String path) throws IOException {
            return d.stat(path);
        }

        @Override
        public List<MountEntry> list(String path) throws IOException {
            return d.list(path);
        }

        @Override
        public InputStream openRead(String path) throws IOException {
            return d.openRead(path);
        }

        @Override
        public OutputStream openWrite(String path, WriteOptions options) throws IOException {
            return d.openWrite(path, options);
        }

        @Override
        public void delete(String path) throws IOException {
            d.delete(path);
        }

        @Override
        public void move(String source, String target, MoveOptions options) throws IOException {
            d.move(source, target, options);
        }

        @Override
        public MountCapabilities capabilities() {
            return d.capabilities();
        }

        @Override
        public Optional<Path> getLocalRoot() {
            return d.getLocalRoot();
        }

        @Override
        public Optional<Path> getLocalPath(String path) {
            return d.getLocalPath(path);
        }
    }
}
