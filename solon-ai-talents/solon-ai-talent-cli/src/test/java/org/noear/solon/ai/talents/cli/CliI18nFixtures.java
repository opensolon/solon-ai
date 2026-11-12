package org.noear.solon.ai.talents.cli;

import org.noear.solon.Utils;
import org.noear.solon.ai.mount.catalog.SkillCatalog;
import org.noear.solon.ai.mount.catalog.SkillContent;
import org.noear.solon.ai.mount.catalog.SkillDescriptor;
import org.noear.solon.ai.mount.source.FindOptions;
import org.noear.solon.ai.mount.source.MountCapabilities;
import org.noear.solon.ai.mount.source.MountEntry;
import org.noear.solon.ai.mount.source.MountSource;
import org.noear.solon.ai.mount.source.MoveOptions;
import org.noear.solon.ai.mount.source.WriteOptions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * cli 文案测试的公共夹具（内存挂载来源、技能目录、环境相关文本的归一化）
 */
final class CliI18nFixtures {
    private CliI18nFixtures() {
    }

    /**
     * 把与环境相关的文本（PID、操作系统、临时目录）换成占位，保证快照跨机器稳定
     */
    static String norm(String text, Path root) {
        if (text == null) {
            return null;
        }

        String out = text;
        if (root != null) {
            try {
                out = out.replace(root.toRealPath().toString(), "<tmp>");
            } catch (IOException ignored) {
            }
            out = out.replace(root.toAbsolutePath().toString(), "<tmp>");
        }

        out = out.replace("(PID: " + Utils.pid() + ")", "(PID: <pid>)");
        out = out.replace(System.getProperty("os.name") + " (" + System.getProperty("os.arch") + ")", "<os> (<arch>)");
        return out;
    }

    /**
     * 固定 python / node 探测结果（null 表示探测失败且处于重试窗口内）
     */
    static void runtimes(TerminalTalent talent, String python, String node) throws Exception {
        set(talent, "pythonCmd", python);
        set(talent, "nodeCmd", node);
        set(talent, "pythonProbeFailedAt", python == null ? System.currentTimeMillis() : 0L);
        set(talent, "nodeProbeFailedAt", node == null ? System.currentTimeMillis() : 0L);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = TerminalTalent.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /**
     * 内存挂载来源（虚拟来源：没有本地目录）
     */
    static class MemSource implements MountSource {
        final Map<String, String> files = new TreeMap<>();
        final MountCapabilities caps;

        MemSource(MountCapabilities caps) {
            this.caps = caps;
        }

        static MemSource readWrite() {
            return new MemSource(MountCapabilities.builder().readable(true).writable(true).searchable(true).build());
        }

        MemSource put(String path, String content) {
            files.put(path, content);
            return this;
        }

        @Override
        public String getScheme() {
            return "mem";
        }

        @Override
        public String normalize(String path) {
            if (path == null) {
                return "";
            }
            String p = path.replace('\\', '/');
            while (p.startsWith("/")) {
                p = p.substring(1);
            }
            while (p.endsWith("/")) {
                p = p.substring(0, p.length() - 1);
            }
            return p;
        }

        private boolean isDir(String path) {
            if (path.isEmpty()) {
                return true;
            }
            for (String k : files.keySet()) {
                if (k.startsWith(path + "/")) {
                    return true;
                }
            }
            return false;
        }

        private static String nameOf(String path) {
            int i = path.lastIndexOf('/');
            return i < 0 ? path : path.substring(i + 1);
        }

        @Override
        public MountEntry stat(String path) {
            String p = normalize(path);
            String content = files.get(p);
            if (content != null) {
                return new MountEntry(p, nameOf(p), false, content.getBytes(StandardCharsets.UTF_8).length, null, null);
            }
            if (isDir(p)) {
                return new MountEntry(p, nameOf(p), true, 0, null, null);
            }
            return null;
        }

        @Override
        public List<MountEntry> list(String path) {
            String p = normalize(path);
            Map<String, MountEntry> out = new LinkedHashMap<>();
            String prefix = p.isEmpty() ? "" : p + "/";
            for (String k : files.keySet()) {
                if (k.startsWith(prefix)) {
                    String rest = k.substring(prefix.length());
                    int i = rest.indexOf('/');
                    String child = prefix + (i < 0 ? rest : rest.substring(0, i));
                    out.putIfAbsent(child, stat(child));
                }
            }
            return new ArrayList<>(out.values());
        }

        @Override
        public List<MountEntry> find(String path, FindOptions options) {
            String p = normalize(path);
            String prefix = p.isEmpty() ? "" : p + "/";
            PathMatcher matcher = options.getGlob() == null ? null
                    : FileSystems.getDefault().getPathMatcher("glob:" + options.getGlob());
            List<MountEntry> out = new ArrayList<>();
            for (String k : files.keySet()) {
                if (k.startsWith(prefix)) {
                    String rel = k.substring(prefix.length());
                    if (matcher == null || matcher.matches(Paths.get(rel)) || matcher.matches(Paths.get(nameOf(rel)))) {
                        out.add(stat(k));
                    }
                }
            }
            return out;
        }

        @Override
        public InputStream openRead(String path) throws IOException {
            String content = files.get(normalize(path));
            if (content == null) {
                throw new IOException("not found: " + path);
            }
            return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public OutputStream openWrite(String path, WriteOptions options) {
            String p = normalize(path);
            return new ByteArrayOutputStream() {
                @Override
                public void close() throws IOException {
                    super.close();
                    files.put(p, toString("UTF-8"));
                }
            };
        }

        @Override
        public void delete(String path) {
            files.remove(normalize(path));
        }

        @Override
        public void move(String source, String target, MoveOptions options) {
        }

        @Override
        public MountCapabilities capabilities() {
            return caps;
        }
    }

    /**
     * 第一次查询能力时可物化、之后不可物化（模拟注册后被降级，用于触达「挂载未生成物化目录」）
     */
    static class DowngradedSource extends MemSource {
        int calls;
        int limit = Integer.MAX_VALUE;

        DowngradedSource() {
            super(null);
        }

        /**
         * 之后第 n 次以后的查询都返回「不可物化」
         */
        void downgradeAfter(int n) {
            calls = 0;
            limit = n;
        }

        @Override
        public MountCapabilities capabilities() {
            calls++;
            return MountCapabilities.builder().readable(true).materializable(calls <= limit).build();
        }
    }

    /**
     * 声明了本地映射却没有本地根目录的来源（用于触达「挂载点没有本地目录」）
     */
    static class RootlessSource extends MemSource {
        final Path dir;

        RootlessSource(Path dir) {
            super(MountCapabilities.builder().readable(true).writable(true).build());
            this.dir = dir;
        }

        @Override
        public java.util.Optional<Path> getLocalPath(String path) {
            return java.util.Optional.of(dir.resolve(normalize(path)));
        }
    }

    /**
     * 让解析结果携带指定挂载（模拟挂载在解析后被移除或停用）
     */
    static class StaleMountManager extends org.noear.solon.ai.mount.MountManager {
        final String alias;
        final org.noear.solon.ai.mount.Mount mount;
        final MountSource source;

        StaleMountManager(String workDir, String alias, org.noear.solon.ai.mount.Mount mount, MountSource source) {
            super(workDir);
            this.alias = alias;
            this.mount = mount;
            this.source = source;
        }

        @Override
        public org.noear.solon.ai.mount.ResolvedResource resolveResource(String path) {
            if (path != null && path.startsWith(alias)) {
                String sp = path.length() > alias.length() ? path.substring(alias.length() + 1) : "";
                return new org.noear.solon.ai.mount.ResolvedResource(path, alias, mount, source, source.normalize(sp));
            }
            return super.resolveResource(path);
        }
    }

    /**
     * 可物化但没有指纹（物化必失败，用于触达「无法物化挂载」）
     */
    static class BrokenMaterializableSource extends MemSource {
        BrokenMaterializableSource() {
            super(MountCapabilities.builder().readable(true).materializable(true).build());
        }

        @Override
        public String getFingerprint() {
            return "";
        }
    }

    /**
     * 固定技能目录
     */
    static class FakeSkills implements SkillCatalog {
        final Map<String, SkillDescriptor> skills = new LinkedHashMap<>();
        final Map<String, SkillContent> contents = new LinkedHashMap<>();

        FakeSkills(int count) {
            for (int i = 1; i <= count; i++) {
                add("@m/s" + i, "s" + i, "技能 " + i, "正文 " + i);
            }
        }

        FakeSkills add(String id, String name, String desc, String text) {
            SkillDescriptor d = new SkillDescriptor(id, name, desc, "1.0");
            skills.put(id, d);
            contents.put(id, new SkillContent(d, text, "\n<skill_content name=\"" + name + "\">\n" + text + "\n</skill_content>\n"));
            return this;
        }

        @Override
        public void refresh() {
        }

        @Override
        public int getSkillCount() {
            return skills.size();
        }

        @Override
        public Collection<SkillDescriptor> getDescriptors() {
            return new ArrayList<>(skills.values());
        }

        @Override
        public Collection<SkillDescriptor> searchDescriptors(String query) {
            if (query == null || query.trim().isEmpty() || "none".equals(query)) {
                return Collections.emptyList();
            }
            List<SkillDescriptor> out = new ArrayList<>();
            for (SkillDescriptor d : skills.values()) {
                if (d.getId().contains(query) || d.getDescription().contains(query)) {
                    out.add(d);
                }
            }
            return out;
        }

        @Override
        public SkillDescriptor getDescriptor(String name) {
            return skills.get(name);
        }

        @Override
        public SkillContent readContent(String name) {
            SkillContent c = contents.get(name);
            return c != null ? c : contents.get("@" + name);
        }

        @Override
        public boolean isAllowed(SkillDescriptor descriptor) {
            return descriptor != null && skills.containsKey(descriptor.getId());
        }
    }
}
