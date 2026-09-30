package org.noear.solon.ai.talents.cli;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.sandbox.config.FilesystemConfig;
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
 *   <li>真实 bash：物化后的 skill 脚本可执行（含 POSIX 可执行位）；</li>
 *   <li>持久缓存：同指纹命中、指纹变化生成新快照、跨工作区实例复用；</li>
 *   <li>安全：用户级缓存对文件工具拒绝读写并加入 OS 沙盒写拒绝；未引用别名不触发物化；</li>
 *   <li>失败关闭：可物化挂载物化失败时直接报错，不把原始 @alias 命令交给 shell。</li>
 * </ul>
 */
class MountMaterializerTest {

    private static TerminalTalent newTalent(MountManager manager, Path work) {
        return new TerminalTalent(manager, ShellCommandFactory.detect(),
                work.resolve("user-home-cache").resolve(".soloncode/cache/mount"));
    }

    private static Path cacheRoot(Path work) {
        return work.resolve("user-home-cache").resolve(".soloncode/cache/mount");
    }

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
        TerminalTalent talent = newTalent(manager, work);

        Map<String, String> envs = translate(talent, "cat @cp/demo/SKILL.md");

        assertTrue(envs.containsKey("CP"), "classpath 挂载应经物化注入环境变量: " + envs);
        assertTrue(Paths.get(envs.get("CP")).startsWith(cacheRoot(work)),
                "物化根应在用户级 mount 缓存目录下: " + envs.get("CP"));
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
        TerminalTalent talent = newTalent(manager, work);
        talent.setSandboxEnabled(false);

        // 直接执行 classpath 中物化出来的 shell 脚本，闭环验证 bash 的脚本执行能力。
        String out = talent.bash("sh @cp/demo/scripts/run.sh", 30000, 20000, null);
        assertFalse(out.startsWith("错误"), out);
        assertTrue(out.contains("materialized-skill-script"), out);

        // 二次调用命中持久缓存：ready 标记存在，目录保持可复用。
        Map<String, String> envs1 = translate(talent, "cat @cp/demo/SKILL.md");
        Map<String, String> envs2 = translate(talent, "cat @cp/demo/SKILL.md");
        assertEquals(envs1.get("CP"), envs2.get("CP"), "同指纹应命中同一缓存目录");
        Path materializedRoot = Paths.get(envs1.get("CP"));
        assertTrue(Files.exists(materializedRoot.resolve(MountMaterializer.READY_MARKER)), "缓存应含 ready 标记");
        assertTrue(Files.exists(materializedRoot.resolve(MountMaterializer.MANIFEST_MARKER)), "缓存应含完整性摘要");
        assertTrue(Files.isExecutable(materializedRoot.resolve("demo/scripts/run.sh"))
                        || !materializedRoot.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "POSIX 下 shell 脚本应保留可执行位");
    }

    @Test
    void modifiedCacheIsDetectedAndRebuilt(@TempDir Path work) throws Exception {
        MountManager manager = new MountManager(work.toString());
        manager.register(Mount.builder().alias("@cp").type(MountType.SKILLS)
                .source(ClasspathMountSource.of(
                        Thread.currentThread().getContextClassLoader(), "test-skills"))
                .build());
        TerminalTalent talent = newTalent(manager, work);

        Path root = Paths.get(translate(talent, "cat @cp/demo/SKILL.md").get("CP"));
        Path script = root.resolve("demo/scripts/run.sh");
        Files.write(script, "#!/bin/sh\necho tampered\n".getBytes(StandardCharsets.UTF_8));

        Map<String, String> rebuilt = translate(talent, "cat @cp/demo/SKILL.md");
        Path rebuiltRoot = Paths.get(rebuilt.get("CP"));
        assertEquals(root, rebuiltRoot, "同一来源仍使用同一内容寻址目录");
        assertTrue(new String(Files.readAllBytes(rebuiltRoot.resolve("demo/scripts/run.sh")),
                StandardCharsets.UTF_8).contains("materialized-skill-script"),
                "缓存内容被修改后应重新物化，而不是继续执行被篡改脚本");
    }

    @Test
    void secondTalentReusesUserLevelCache(@TempDir Path work) throws Exception {
        MountManager firstManager = new MountManager(work.resolve("workspace-a").toString());
        firstManager.register(Mount.builder().alias("@cp").type(MountType.SKILLS)
                .source(ClasspathMountSource.of(
                        Thread.currentThread().getContextClassLoader(), "test-skills"))
                .build());
        TerminalTalent first = newTalent(firstManager, work);
        Map<String, String> firstEnv = translate(first, "cat @cp/demo/SKILL.md");

        MountManager secondManager = new MountManager(work.resolve("workspace-b").toString());
        secondManager.register(Mount.builder().alias("@cp").type(MountType.SKILLS)
                .source(ClasspathMountSource.of(
                        Thread.currentThread().getContextClassLoader(), "test-skills"))
                .build());
        TerminalTalent second = newTalent(secondManager, work);
        Map<String, String> secondEnv = translate(second, "cat @cp/demo/SKILL.md");

        assertEquals(firstEnv.get("CP"), secondEnv.get("CP"), "同一用户级缓存应跨工作区复用");
        assertTrue(Files.isRegularFile(Paths.get(secondEnv.get("CP")).resolve("demo/SKILL.md")));
    }

    @Test
    void materializeCacheIsReadableButNotWritableInDynamicSandboxConfig(@TempDir Path work) throws Exception {
        MountManager manager = new MountManager(work.toString());
        TerminalTalent talent = newTalent(manager, work);
        talent.setSandboxAllowUserHome(false);

        java.lang.reflect.Method method = TerminalTalent.class.getDeclaredMethod("buildDynamicFilesystemConfig");
        method.setAccessible(true);
        FilesystemConfig fs = (FilesystemConfig) method.invoke(talent);

        String root = cacheRoot(work).toString();
        assertTrue(fs.getAllowRead().contains(root), "缓存根应加入 OS 沙盒读白名单");
        assertTrue(fs.getDenyWrite().contains(root), "缓存根应加入 OS 沙盒写拒绝名单");
        assertTrue(fs.getAllowWrite() == null || !fs.getAllowWrite().contains(root),
                "缓存根不应加入写白名单");
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
        TerminalTalent talent = newTalent(manager, work);

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
        TerminalTalent talent = newTalent(manager, work);

        Map<String, String> envs = translate(talent, "cat README.md");
        assertFalse(envs.containsKey("CP"), "未引用别名的命令不应物化: " + envs);
        assertFalse(Files.exists(cacheRoot(work)), "未引用别名不应产生缓存目录");
    }

    @Test
    void mcacheIsMandatoryDeniedForFileTools(@TempDir Path work) throws Exception {
        MountManager manager = new MountManager(work.toString());
        manager.register(Mount.builder().alias("@cp").type(MountType.SKILLS)
                .source(ClasspathMountSource.of(
                        Thread.currentThread().getContextClassLoader(), "test-skills"))
                .build());
        TerminalTalent talent = newTalent(manager, work);

        // 触发物化
        translate(talent, "cat @cp/demo/SKILL.md");

        // 文件工具对用户级物化缓存强制拒绝（防 TOCTOU 篡改）。
        String cachePath = cacheRoot(work).toString();
        assertThrows(SecurityException.class, () -> talent.ls(cachePath, false, false, null));
        assertThrows(SecurityException.class, () -> talent.read(cachePath, null, null, null));
        assertThrows(SecurityException.class, () -> talent.write(cachePath + "/hack.sh", "evil", null));
    }

    @Test
    void materializeCacheRemainsDeniedWhenSandboxIsDisabled(@TempDir Path work) throws Exception {
        MountManager manager = new MountManager(work.toString());
        manager.register(Mount.builder().alias("@cp").type(MountType.SKILLS)
                .source(ClasspathMountSource.of(
                        Thread.currentThread().getContextClassLoader(), "test-skills"))
                .build());
        TerminalTalent talent = newTalent(manager, work);
        talent.setSandboxEnabled(false);
        translate(talent, "cat @cp/demo/SKILL.md");

        String cachePath = cacheRoot(work).toString();
        assertThrows(SecurityException.class, () -> talent.ls(cachePath, false, false, null));
        assertThrows(SecurityException.class, () -> talent.read(cachePath, null, null, null));
        assertThrows(SecurityException.class, () -> talent.write(cachePath + "/hack.sh", "evil", null));
    }

    @Test
    void cacheAliasSymlinkIsRejected(@TempDir Path work) throws Exception {
        MountManager manager = new MountManager(work.toString());
        manager.register(Mount.builder().alias("@cp").type(MountType.SKILLS)
                .source(ClasspathMountSource.of(
                        Thread.currentThread().getContextClassLoader(), "test-skills"))
                .build());
        Path cache = cacheRoot(work);
        Files.createDirectories(cache);
        Path outside = Files.createDirectories(work.resolve("outside-cache"));
        Path alias = cache.resolve("cp");
        try {
            Files.createSymbolicLink(alias, outside);
        } catch (UnsupportedOperationException | SecurityException | java.io.IOException e) {
            Assumptions.assumeTrue(false, "当前文件系统不支持创建符号链接");
        }

        TerminalTalent talent = newTalent(manager, work);
        assertThrows(MountMaterializer.MaterializationException.class,
                () -> translate(talent, "cat @cp/demo/SKILL.md"));
        assertFalse(Files.exists(outside.resolve(".ready")), "不能把缓存写到 alias 符号链接指向的位置");
    }

    @Test
    void mountListEmitsMaterializedShellAttribute(@TempDir Path work) {
        MountManager manager = new MountManager(work.toString());
        manager.register(Mount.builder().alias("@cp").type(MountType.SKILLS)
                .source(ClasspathMountSource.of(
                        Thread.currentThread().getContextClassLoader(), "test-skills"))
                .build());
        String instruction = newTalent(manager, work).getInstruction(null);

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
        TerminalTalent talent = newTalent(manager, work);

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
        TerminalTalent talent = newTalent(manager, work);

        MountMaterializer.MaterializationException error = assertThrows(
                MountMaterializer.MaterializationException.class,
                () -> translate(talent, "cat @emptycp/any.txt"));
        assertTrue(error.getMessage().contains("无法物化挂载"), error.getMessage());
        // 失败后只保留跨进程锁元数据，不得产出 staging 或 .ready 假缓存。
        Path aliasDir = cacheRoot(work).resolve("emptycp");
        assertTrue(Files.isRegularFile(aliasDir.resolve(".lock")), "跨进程锁元数据可保留");
        try (java.util.stream.Stream<Path> children = Files.list(aliasDir)) {
            children.forEach(p -> assertEquals(".lock", p.getFileName().toString(),
                    "失败后不应残留 staging 或假缓存: " + p));
        }
    }

    @Test
    void absolutePathFingerprintCannotEscapeCacheRoot(@TempDir Path work) {
        // 历史陷阱回归：指纹若是绝对路径（如默认指纹 = getLocation() 的本地绝对路径），
        // Path.resolve(绝对路径) 会整体替换父路径，换入逻辑可能误删缓存根之外的真实目录。
        // 哈希化后指纹目录名一定是单段安全字符，缓存永远落在 <cacheRoot>/<alias>/ 内。
        String evilFingerprint = "/Users/someone/.soloncode/real-mount-root";
        String dirName = MountMaterializer.safeFingerprintDirName(evilFingerprint);

        java.nio.file.Path cacheRoot = cacheRoot(work);
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
    void closeKeepsMaterializedCacheReusable(@TempDir Path work) throws Exception {
        MountManager manager = new MountManager(work.toString());
        manager.register(Mount.builder().alias("@cp").type(MountType.SKILLS)
                .source(ClasspathMountSource.of(
                        Thread.currentThread().getContextClassLoader(), "test-skills"))
                .build());
        TerminalTalent talent = newTalent(manager, work);

        translate(talent, "cat @cp/demo/SKILL.md");
        Path mcache = cacheRoot(work);
        assertTrue(Files.exists(mcache));

        // 持久缓存不绑定 TerminalTalent 生命周期，close 不应删除后续命令仍可复用的快照。
        java.lang.reflect.Field field = TerminalTalent.class.getDeclaredField("materializer");
        field.setAccessible(true);
        MountMaterializer materializer = (MountMaterializer) field.get(talent);
        assertNotNull(materializer);
        materializer.close();
        assertTrue(Files.exists(mcache.resolve("cp")), "close 不应清空持久物化缓存");
    }
}
