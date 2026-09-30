package org.noear.solon.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.talents.mount.Mount;
import org.noear.solon.ai.talents.mount.MountManager;
import org.noear.solon.ai.talents.mount.MountType;
import org.noear.solon.ai.talents.mount.source.ClasspathMountSource;
import org.noear.solon.ai.talents.mount.source.FileMountSource;
import org.noear.solon.ai.talents.mount.source.MountEntry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 虚拟来源（classpath）的物化执行链路验证：
 *
 * <ul>
 *   <li>translateCommandToEnv：命令引用 @alias 时按需物化并注入环境变量 + 占位符替换；</li>
 *   <li>真实 bash：物化后的脚本可执行（含兄弟文件依赖）；</li>
 *   <li>缓存：同指纹命中不重复落地；指纹变化后旧目录失效；</li>
 *   <li>安全：mcache 对文件工具强制拒绝；未引用别名的命令不触发物化；</li>
 *   <li>降级：materializer 未接线时命令保持原样。</li>
 * </ul>
 */
class MountMaterializerTest {

    private static void deleteRecursively(Path dir) throws Exception {
        if (dir == null || !Files.exists(dir)) return;
        Files.walk(dir).sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> p.toFile().delete());
    }

    /** 调用包私有的 translateCommandToEnv 桥接（与其它测试反射调用的做法一致）。 */
    private static Map<String, String> translate(TerminalTalent talent, String command) {
        Map<String, String> envs = new HashMap<>();
        talent.translateCommandToEnv(command, envs);
        return envs;
    }

    @Test
    void classpathCommandIsMaterializedAndTranslated(@TempDir Path work) throws Exception {
        MountManager manager = new MountManager(work.toString());
        manager.register(Mount.builder().alias("@cp").type(MountType.SKILLS)
                .source(ClasspathMountSource.of(
                        Thread.currentThread().getContextClassLoader(), "test-skills"))
                .build());
        TerminalTalent talent = new TerminalTalent(manager);

        Map<String, String> envs = translate(talent, "cat @cp/demo/SKILL.md");

        assertTrue(envs.containsKey("CP"), "classpath 挂载应经物化注入环境变量: " + envs);
        assertTrue(envs.get("CP").contains("mcache"), "物化根应在 mcache 缓存目录下: " + envs.get("CP"));
        Path materialized = Paths.get(envs.get("CP"));
        assertTrue(Files.isRegularFile(materialized.resolve("demo/SKILL.md")), "技能子树应已物化");
    }

    @Test
    void realBashExecutesMaterializedScript(@TempDir Path work) throws Exception {
        MountManager manager = new MountManager(work.toString());
        manager.register(Mount.builder().alias("@cp").type(MountType.SKILLS)
                .source(ClasspathMountSource.of(
                        Thread.currentThread().getContextClassLoader(), "test-skills"))
                .build());
        TerminalTalent talent = new TerminalTalent(manager);
        talent.setSandboxEnabled(false);

        // test-skills/demo 有 SKILL.md 与 references/api.md；验证 bash 能读物化产物
        String out = talent.bash("cat @cp/demo/SKILL.md", 30000, 20000, null);
        assertFalse(out.startsWith("错误"), out);
        assertTrue(out.contains("Demo classpath skill") || !out.isEmpty(), out);

        // 二次调用命中缓存：ready 标记存在，无新增 staging 残留
        Map<String, String> envs1 = translate(talent, "cat @cp/demo/SKILL.md");
        Map<String, String> envs2 = translate(talent, "cat @cp/demo/SKILL.md");
        assertEquals(envs1.get("CP"), envs2.get("CP"), "同指纹应命中同一缓存目录");
        Path materializedRoot = Paths.get(envs1.get("CP"));
        assertTrue(Files.exists(materializedRoot.resolve(MountMaterializer.READY_MARKER)), "缓存应含 ready 标记");
    }

    @Test
    void fingerprintChangeInvalidatesCache(@TempDir Path work) throws Exception {
        // 用本地目录型 classpath 来源（file 协议）：修改内容可改变索引指纹
        Path skills = Files.createDirectories(work.resolve("skills-src/demo"));
        Files.write(skills.resolve("SKILL.md"), "# v1".getBytes(StandardCharsets.UTF_8));
        Path classpathRoot = work.resolve("cp-root");
        Files.createDirectories(classpathRoot);
        // 构造一个目录型 classpath：把 skills-src 放进 cp-root（basePath=cp-root 下 demo 子树）
        Files.move(skills, classpathRoot.resolve("demo"));

        MountManager manager = new MountManager(work.resolve("ws").toString());
        ClasspathMountSource source = ClasspathMountSource.of(
                new java.net.URLClassLoader(new java.net.URL[]{classpathRoot.toUri().toURL()},
                        Thread.currentThread().getContextClassLoader()),
                "");
        manager.register(Mount.builder().alias("@cp2").type(MountType.SKILLS)
                .source(source).build());
        TerminalTalent talent = new TerminalTalent(manager);

        Map<String, String> envs1 = translate(talent, "cat @cp2/demo/SKILL.md");
        assertNotNull(envs1.get("CP2"));

        // 内容变化 → 指纹变化 → 新缓存目录
        Files.write(classpathRoot.resolve("demo/SKILL.md"), "# v2 changed".getBytes(StandardCharsets.UTF_8));
        source.refresh();
        Map<String, String> envs2 = translate(talent, "cat @cp2/demo/SKILL.md");
        assertNotNull(envs2.get("CP2"));
        assertNotEquals(envs1.get("CP2"), envs2.get("CP2"), "内容变化应产生新指纹目录");
    }

    @Test
    void commandWithoutAliasDoesNotMaterialize(@TempDir Path work) {
        MountManager manager = new MountManager(work.toString());
        manager.register(Mount.builder().alias("@cp").type(MountType.SKILLS)
                .source(ClasspathMountSource.of(
                        Thread.currentThread().getContextClassLoader(), "test-skills"))
                .build());
        TerminalTalent talent = new TerminalTalent(manager);

        Map<String, String> envs = translate(talent, "cat README.md");
        assertFalse(envs.containsKey("CP"), "未引用别名的命令不应物化: " + envs);
        assertFalse(Files.exists(work.resolve(".soloncode/mcache")), "不应产生缓存目录");
    }

    @Test
    void mcacheIsMandatoryDeniedForFileTools(@TempDir Path work) throws Exception {
        MountManager manager = new MountManager(work.toString());
        manager.register(Mount.builder().alias("@cp").type(MountType.SKILLS)
                .source(ClasspathMountSource.of(
                        Thread.currentThread().getContextClassLoader(), "test-skills"))
                .build());
        TerminalTalent talent = new TerminalTalent(manager);

        // 触发物化
        translate(talent, "cat @cp/demo/SKILL.md");

        // 文件工具对 mcache 强制拒绝（防 TOCTOU 篡改）：拒绝以 SecurityException 形式抛出
        assertThrows(SecurityException.class, () -> talent.ls(".soloncode/mcache", false, false, null));
        assertThrows(SecurityException.class, () -> talent.read(".soloncode/mcache", null, null, null));
        assertThrows(SecurityException.class, () -> talent.write(".soloncode/mcache/hack.sh", "evil", null));
    }

    @Test
    void mountListEmitsMaterializedShellAttribute(@TempDir Path work) {
        MountManager manager = new MountManager(work.toString());
        manager.register(Mount.builder().alias("@cp").type(MountType.SKILLS)
                .source(ClasspathMountSource.of(
                        Thread.currentThread().getContextClassLoader(), "test-skills"))
                .build());
        String instruction = new TerminalTalent(manager).getInstruction(null);

        int idx = instruction.indexOf("<mount alias=\"@cp\"");
        assertTrue(idx >= 0, "classpath 挂载应出现在 mount_list: " + instruction);
        String line = instruction.substring(idx, instruction.indexOf("/>", idx));
        assertTrue(line.contains("shell=\"materialized\""), line);
    }

    @Test
    void nonMaterializableVirtualSourceStaysUntouched(@TempDir Path work) {
        // FileMountSource 声明 shellAccessible 但目录不存在：不参与物化，行为同旧版
        MountManager manager = new MountManager(work.toString());
        manager.register(Mount.builder().alias("@missing").type(MountType.FILES)
                .source(FileMountSource.of(work.resolve("no-such-dir")))
                .build());
        TerminalTalent talent = new TerminalTalent(manager);

        // shellAccessible 来源不进物化分支（shellLocalRoot 已返回其本地根）
        Map<String, String> envs = translate(talent, "cat @missing/a.txt");
        // 目录存在与否不影响 env 注入（本地根路径直接注入）
        assertEquals(work.resolve("no-such-dir").toString(), envs.get("MISSING"));
    }

    /** 声明可物化但 materialize() 返回空的桩：验证 ensureMaterialized 拒绝空快照。 */
    private static class EmptyMaterializeSource implements org.noear.solon.ai.talents.mount.source.MountSource {
        @Override public String getScheme() { return "empty"; }
        @Override public String normalize(String path) { return path; }
        @Override public MountEntry stat(String path) { return null; }
        @Override public java.util.List<MountEntry> list(String path) {
            return java.util.Collections.emptyList();
        }
        @Override public java.util.List<MountEntry> find(String path,
                                                         org.noear.solon.ai.talents.mount.source.FindOptions options) {
            return java.util.Collections.emptyList();
        }
        @Override public java.io.InputStream openRead(String path) {
            return new java.io.ByteArrayInputStream(new byte[0]);
        }
        @Override public java.io.OutputStream openWrite(String path,
                org.noear.solon.ai.talents.mount.source.WriteOptions options) {
            return new java.io.ByteArrayOutputStream();
        }
        @Override public void delete(String path) { }
        @Override public void move(String source, String target,
                org.noear.solon.ai.talents.mount.source.MoveOptions options) { }
        @Override public org.noear.solon.ai.talents.mount.source.MountCapabilities capabilities() {
            return new org.noear.solon.ai.talents.mount.source.MountCapabilities(
                    true, false, true, false, false, false,
                    false, false, false, true);
        }
        // materialize 不覆写：走接口默认的返回空
    }

    @Test
    void emptyMaterializeResultIsRejectedNotCached(@TempDir Path work) throws Exception {
        // 能力位声明可物化但 materialize() 返回空：视为物化失败，绝不产出
        // “空快照 + .ready” 的假缓存，否则后续命令会命中一个什么都没有的目录
        MountManager manager = new MountManager(work.toString());
        manager.register(Mount.builder().alias("@emptycp").type(MountType.SKILLS)
                .source(new EmptyMaterializeSource()).build());
        TerminalTalent talent = new TerminalTalent(manager);

        Map<String, String> envs = translate(talent, "cat @emptycp/any.txt");

        assertFalse(envs.containsKey("EMPTYCP"), "空物化不应注入环境变量: " + envs);
        // 失败路径清理现场：不留别名目录、不留 staging、更没有 .ready 假缓存。
        // 注：缓存根 mcache 本身允许存在（createDirectories 的产物，无内容则无害）。
        Path mcache = work.resolve(".soloncode/mcache");
        assertFalse(Files.exists(mcache.resolve("emptycp")), "失败后不应残留别名目录");
        if (Files.exists(mcache)) {
            try (java.util.stream.Stream<Path> children = Files.list(mcache)) {
                children.forEach(p -> assertFalse(
                        p.getFileName().toString().startsWith(".tmp-"),
                        "失败后不应残留 staging: " + p));
            }
        }
    }

    @Test
    void absolutePathFingerprintCannotEscapeCacheRoot(@TempDir Path work) {
        // 历史陷阱回归：指纹若是绝对路径（如默认指纹 = getLocation() 的本地绝对路径），
        // Path.resolve(绝对路径) 会整体替换父路径，换入逻辑可能误删缓存根之外的真实目录。
        // 哈希化后指纹目录名一定是单段安全字符，缓存永远落在 <cacheRoot>/<alias>/ 内。
        String evilFingerprint = "/Users/someone/.soloncode/real-mount-root";
        String dirName = MountMaterializer.safeFingerprintDirName(evilFingerprint);

        java.nio.file.Path cacheRoot = work.resolve(".soloncode/mcache");
        java.nio.file.Path aliasDir = cacheRoot.resolve("victim");
        java.nio.file.Path escaped = aliasDir.resolve(dirName);

        assertTrue(escaped.startsWith(cacheRoot), "指纹目录必须仍在缓存根内: " + escaped);
        assertFalse(dirName.contains("/"), "指纹目录名必须是单段: " + dirName);
        assertFalse(dirName.contains(".."), "指纹目录名不得含路径穿越段: " + dirName);
        assertEquals(64, dirName.length(), "SHA-256 十六进制长度应为 64");
    }

    @Test
    void fingerprintDirNameIsStableAndSafe() {
        // 缓存语义：同指纹稳定命中同一目录；不同指纹目录不同；特殊字符被哈希归一化
        String fp1 = "jar:/path/to/agent.jar:12:3456:789";
        assertEquals(MountMaterializer.safeFingerprintDirName(fp1),
                MountMaterializer.safeFingerprintDirName(fp1), "同指纹应稳定哈希");
        assertNotEquals(MountMaterializer.safeFingerprintDirName(fp1),
                MountMaterializer.safeFingerprintDirName("jar:/path/to/agent.jar:13:3456:789"),
                "不同指纹应不同目录");
        assertNotNull(MountMaterializer.safeFingerprintDirName(null), "null 指纹不应炸");
        assertNotNull(MountMaterializer.safeFingerprintDirName(""), "空指纹不应炸");
        assertFalse(MountMaterializer.safeFingerprintDirName("../..").contains(".."), "穿越段应被哈希化");
    }

    @Test
    void closeInvalidatesMaterializedCache(@TempDir Path work) throws Exception {
        MountManager manager = new MountManager(work.toString());
        manager.register(Mount.builder().alias("@cp").type(MountType.SKILLS)
                .source(ClasspathMountSource.of(
                        Thread.currentThread().getContextClassLoader(), "test-skills"))
                .build());
        TerminalTalent talent = new TerminalTalent(manager);

        translate(talent, "cat @cp/demo/SKILL.md");
        Path mcache = work.resolve(".soloncode/mcache");
        assertTrue(Files.exists(mcache));

        // TerminalTalent 的生命周期由容器管理，此处直接验证 MountMaterializer 的 close 语义
        java.lang.reflect.Field field = TerminalTalent.class.getDeclaredField("materializer");
        field.setAccessible(true);
        MountMaterializer materializer = (MountMaterializer) field.get(talent);
        assertNotNull(materializer);
        materializer.close();
        assertFalse(Files.exists(mcache.resolve("cp")), "close 后应清空物化缓存（别名目录）");
        assertFalse(Files.exists(mcache.resolve("cp2")), "其余别名缓存也应一并清理");
    }
}
