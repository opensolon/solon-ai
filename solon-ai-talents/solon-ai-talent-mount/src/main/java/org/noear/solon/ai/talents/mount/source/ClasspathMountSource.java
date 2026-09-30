/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.talents.mount.source;

import org.noear.solon.core.util.ResourceUtil;
import org.noear.solon.lang.Preview;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Classpath/Jar 只读挂载来源。
 * 资源路径相对于 basePath，统一使用 '/'，不依赖真实 Path。
 * 支持 Classpath index 索引
 *
 * @author noear 
 * @since 4.1.1
 */
@Preview("4.1.1")
public final class ClasspathMountSource implements MountSource {
    private final ClassLoader classLoader;
    private final String basePath;
    private final MountCapabilities capabilities;
    private volatile Map<String, Entry> index;

    /**
     * 创建类路径挂载来源。
     *
     * @param classLoader 类加载器；为 null 时使用当前线程上下文类加载器
     * @param basePath 资源根路径
     */
    public ClasspathMountSource(ClassLoader classLoader, String basePath) {
        this.classLoader = classLoader == null ? Thread.currentThread().getContextClassLoader() : classLoader;
        this.basePath = normalizeBase(basePath);
        this.capabilities = new MountCapabilities(true, false, true, false, false, false,
                false, false, false, true);
    }

    /**
     * 使用指定类加载器创建挂载来源。
     *
     * @param classLoader 类加载器
     * @param basePath 资源根路径
     * @return 类路径挂载来源
     */
    public static ClasspathMountSource of(ClassLoader classLoader, String basePath) {
        return new ClasspathMountSource(classLoader, basePath);
    }

    /**
     * 使用当前线程上下文类加载器创建挂载来源。
     *
     * @param basePath 资源根路径
     * @return 类路径挂载来源
     */
    public static ClasspathMountSource of(String basePath) {
        return new ClasspathMountSource(Thread.currentThread().getContextClassLoader(), basePath);
    }

    /**
     * 获取规范化后的资源根路径。
     *
     * @return 资源根路径
     */
    public String getBasePath() {
        return basePath;
    }

    /**
     * 获取挂载位置。
     *
     * @return 资源根路径
     */
    @Override
    public String getLocation() {
        return basePath;
    }

    /**
     * 获取类路径挂载协议。
     *
     * @return classpath
     */
    @Override
    public String getScheme() {
        return "classpath";
    }

    /**
     * 规范化相对资源路径，并拒绝上级目录片段。
     *
     * @param path 待处理路径
     * @return 使用斜杠分隔的相对路径
     */
    @Override
    public String normalize(String path) {
        if (path == null || path.isEmpty() || ".".equals(path)) return "";
        String value = path.replace('\\', '/');
        while (value.startsWith("/")) {
            value = value.substring(1);
        }

        String[] parts = value.split("/");
        List<String> clean = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) throw new SecurityException("Path escapes classpath mount: " + path);
            clean.add(part);
        }
        return String.join("/", clean);
    }

    /**
     * 查询资源条目元数据。
     *
     * @param path 相对资源路径
     * @return 条目；不存在时为 null
     * @throws IOException 资源枚举失败时
     */
    @Override
    public MountEntry stat(String path) throws IOException {
        String normalized = normalize(path);
        Entry entry = entries().get(normalized);
        if (entry == null && normalized.isEmpty() && !entries().isEmpty()) {
            return new MountEntry("", basePath.isEmpty() ? "" : basePath.substring(0, basePath.length() - 1), true, -1, null, null);
        }
        return entry == null ? null : entry.toMountEntry();
    }

    /**
     * 列出指定路径的直接子条目。
     *
     * @param path 相对目录路径
     * @return 按名称排序的子条目
     * @throws IOException 资源枚举失败时
     */
    @Override
    public List<MountEntry> list(String path) throws IOException {
        String base = normalize(path);
        Map<String, MountEntry> result = new LinkedHashMap<>();
        for (Entry entry : entries().values()) {
            String relative = relative(base, entry.path);
            if (relative.isEmpty() || relative.indexOf('/') >= 0) {
                if (relative.indexOf('/') >= 0) {
                    String child = relative.substring(0, relative.indexOf('/'));
                    String childPath = base.isEmpty() ? child : base + "/" + child;
                    Entry childEntry = entries().get(childPath);
                    if (childEntry == null) childEntry = Entry.directory(childPath);
                    result.put(childPath, childEntry.toMountEntry());
                }
            } else {
                result.put(entry.path, entry.toMountEntry());
            }
        }
        List<MountEntry> list = new ArrayList<>(result.values());
        list.sort(Comparator.comparing(MountEntry::getName));
        return list;
    }

    /**
     * 按查找选项搜索资源条目。
     *
     * @param path 搜索起点
     * @param options 查找选项；为 null 时使用默认选项
     * @return 按路径排序的匹配条目
     * @throws IOException 资源枚举失败时
     */
    @Override
    public List<MountEntry> find(String path, FindOptions options) throws IOException {
        FindOptions actual = options == null ? FindOptions.defaults() : options;
        if (actual.getMaxEntries() == 0) {
            return new ArrayList<>();
        }
        String base = normalize(path);
        List<MountEntry> result = new ArrayList<>();
        for (Entry entry : entries().values()) {
            String relative = relative(base, entry.path);
            if (relative.isEmpty() || depth(relative) > actual.getMaxDepth()) continue;
            if (actual.isFilesOnly() && entry.directory || actual.isDirectoriesOnly() && !entry.directory) continue;
            if (actual.getGlob() != null && !globMatches(actual.getGlob(), relative)) continue;
            result.add(entry.toMountEntry());
            if (result.size() >= actual.getMaxEntries()) break;
        }
        result.sort(Comparator.comparing(MountEntry::getPath));
        return result;
    }

    /**
     * 打开资源输入流。
     *
     * @param path 相对资源路径
     * @return 资源输入流
     * @throws IOException 资源不存在时
     */
    @Override
    public InputStream openRead(String path) throws IOException {
        String normalized = normalize(path);
        String resource = resourcePath(normalized);
        InputStream input = ResourceUtil.getResourceAsStream(classLoader, resource);
        if (input == null) throw new IOException("Classpath resource not found: " + normalized);
        return input;
    }

    /**
     * 拒绝写入只读类路径挂载。
     *
     * @param path 目标路径
     * @param options 写入选项
     * @return 不会返回
     * @throws UnsupportedOperationException 类路径挂载不支持写入
     */
    @Override
    public OutputStream openWrite(String path, WriteOptions options) throws IOException {
        throw new UnsupportedOperationException("Classpath mount is read-only");
    }

    /**
     * 拒绝删除只读类路径资源。
     *
     * @param path 目标路径
     * @throws UnsupportedOperationException 类路径挂载不支持删除
     */
    @Override
    public void delete(String path) throws IOException {
        throw new UnsupportedOperationException("Classpath mount is read-only");
    }

    /**
     * 拒绝移动只读类路径资源。
     *
     * @param source 来源路径
     * @param target 目标路径
     * @param options 移动选项
     * @throws UnsupportedOperationException 类路径挂载不支持移动
     */
    @Override
    public void move(String source, String target, MoveOptions options) throws IOException {
        throw new UnsupportedOperationException("Classpath mount is read-only");
    }

    /**
     * 获取只读虚拟挂载能力。
     *
     * @return 挂载能力
     */
    @Override
    public MountCapabilities capabilities() {
        return capabilities;
    }

    /** 丢弃索引，下一次查询时重新枚举 classpath。 */
    public void refresh() {
        index = null;
    }

    /**
     * 将指定路径下的资源子树物化到目标目录。
     *
     * <p>基于索引递归复制（openRead → Files.copy），目录条目自动创建；
     * POSIX 文件系统下对 shell 脚本后缀补可执行位（解释器调用的 .py/.js 等无需 +x）。
     * 物化产物是内容快照：不回写、不监听，与源的生命周期解耦。</p>
     *
     * @param path 相对资源路径（目录或文件）
     * @param targetDirectory 目标目录
     * @return 物化后的本地路径；目标目录不可用时为空
     * @throws IOException 资源读取或复制失败时
     */
    @Override
    public Optional<Path> materialize(String path, Path targetDirectory) throws IOException {
        if (targetDirectory == null) {
            return Optional.empty();
        }
        String base = normalize(path);
        Map<String, Entry> snapshot = entries();
        boolean baseIsDirectory = base.isEmpty()
                || (snapshot.get(base) != null && snapshot.get(base).directory);
        if (!baseIsDirectory) {
            // 具体文件：直接复制，保留相对目录结构
            try (InputStream input = openRead(base)) {
                Path target = targetDirectory.resolve(base).getParent();
                if (target != null) {
                    Files.createDirectories(target);
                }
                Path file = targetDirectory.resolve(base.isEmpty() ? "_" : base);
                Files.copy(input, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                markExecutableIfScript(file);
            }
            return Optional.of(targetDirectory.resolve(base.isEmpty() ? "_" : base));
        }

        Files.createDirectories(targetDirectory);
        for (Entry entry : snapshot.values()) {
            String relative = relative(base, entry.path);
            if (relative.isEmpty()) continue;
            if (entry.directory) {
                Files.createDirectories(targetDirectory.resolve(relative));
            } else {
                // 目录内文件：从索引路径读，而不是拼接 prefix（相对路径已剥离基路径）
                try (InputStream input = openRead(entry.path)) {
                    Path file = targetDirectory.resolve(relative);
                    Path parent = file.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(input, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    markExecutableIfScript(file);
                }
            }
        }
        return Optional.of(targetDirectory);
    }

    /**
     * 获取来源内容指纹：jar 协议按 basePath 下条目聚合（条目数+总大小+最新时间
     * + jar 名），其余按索引条目聚合。
     *
     * <p>不用 manifest hash：技能包升级时 manifest 可能不变而资源已变；
 * 条目聚合对内容变化敏感且无 NPE 风险。仅在缓存未命中时调用，成本可接受。</p>
     *
     * @return 内容指纹
     */
    @Override
    public String getFingerprint() {
        try {
            Enumeration<URL> resources = ResourceUtil.getResources(classLoader, basePath);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                if (!"jar".equals(url.getProtocol())) continue;
                try {
                    URLConnection connection = url.openConnection();
                    if (connection instanceof JarURLConnection) {
                        JarFile jar = ((JarURLConnection) connection).getJarFile();
                        int count = 0;
                        long totalSize = 0;
                        long latestTime = -1;
                        Enumeration<JarEntry> jarEntries = jar.entries();
                        while (jarEntries.hasMoreElements()) {
                            JarEntry jarEntry = jarEntries.nextElement();
                            if (!jarEntry.getName().startsWith(basePath)) continue;
                            count++;
                            long size = jarEntry.getSize();
                            if (size > 0) totalSize += size;
                            long time = jarEntry.getTime();
                            if (time > latestTime) latestTime = time;
                        }
                        return "jar:" + jar.getName() + ":" + count + ":" + totalSize + ":" + latestTime;
                    }
                } catch (IOException ignored) {
                    // jar 指纹不可用时退回索引聚合指纹
                }
            }
        } catch (IOException ignored) {
            // 枚举失败时退回索引聚合指纹
        }
        return indexFingerprint();
    }

    /**
     * 基于索引条目聚合的指纹：条目数 + 总大小 + 最大修改时间。
     *
     * @return 索引指纹；索引不可用时为位置标识
     */
    private String indexFingerprint() {
        try {
            Map<String, Entry> snapshot = entries();
            long totalSize = 0;
            Instant latest = null;
            for (Entry entry : snapshot.values()) {
                if (entry.directory) continue;
                totalSize += Math.max(entry.size, 0);
                if (entry.modified != null && (latest == null || entry.modified.isAfter(latest))) {
                    latest = entry.modified;
                }
            }
            return "idx:" + snapshot.size() + ":" + totalSize + ":" + (latest == null ? "-" : latest);
        } catch (IOException e) {
            return getLocation();
        }
    }

    /**
     * POSIX 文件系统下为 shell 脚本后缀补可执行位。
     *
     * @param file 待标记的文件
     */
    private static void markExecutableIfScript(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String suffix = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        if (!("sh".equals(suffix) || "bash".equals(suffix) || "command".equals(suffix))) {
            return;
        }
        try {
            java.util.Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // 非 POSIX 文件系统（如 Windows/ FAT）无此概念，跳过
        }
    }

    /**
     * 获取缓存的资源索引，必要时扫描类路径并建立索引。
     *
     * @return 路径到条目的映射
     * @throws IOException 资源扫描失败时
     */
    private Map<String, Entry> entries() throws IOException {
        Map<String, Entry> cached = index;
        if (cached != null) return cached;
        Map<String, Entry> result = new LinkedHashMap<>();
        Enumeration<URL> resources = ResourceUtil.getResources(classLoader, basePath);
        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            scanUrl(url, result);
        }
        // 资源索引解决 Jar 没有目录条目的情况。
        scanIndex(result, basePath + "index");
        index = result;
        return result;
    }

    /**
     * 扫描文件目录或 Jar URL 中的资源。
     *
     * @param url 资源根 URL
     * @param result 待填充的索引
     * @throws IOException 资源扫描失败时
     */
    private void scanUrl(URL url, Map<String, Entry> result) throws IOException {
        String protocol = url.getProtocol();
        if ("file".equals(protocol)) {
            try {
                Path root = Paths.get(url.toURI());
                if (Files.isDirectory(root)) scanDirectory(root, root, result);
            } catch (Exception e) {
                throw new IOException("Cannot scan classpath directory: " + url, e);
            }
        } else if ("jar".equals(protocol)) {
            URLConnection connection = url.openConnection();
            if (connection instanceof JarURLConnection) {
                JarFile jar = ((JarURLConnection) connection).getJarFile();
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry jarEntry = entries.nextElement();
                    String name = jarEntry.getName();
                    if (!name.startsWith(basePath)) continue;
                    String relative = name.substring(basePath.length());
                    long jarTime = jarEntry.getTime();
                    Instant modified = jarTime < 0 ? null : Instant.ofEpochMilli(jarTime);
                    addEntry(relative, jarEntry.isDirectory(), jarEntry.getSize(),
                            modified, result);
                }
            }
        }
    }

    /**
     * 递归扫描类路径文件目录。
     *
     * @param root 资源根目录
     * @param current 当前目录
     * @param result 待填充的索引
     * @throws IOException 目录读取失败时
     */
    private void scanDirectory(Path root, Path current, Map<String, Entry> result) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
            for (Path child : stream) {
                String relative = root.relativize(child).toString().replace('\\', '/');
                if (Files.isDirectory(child)) {
                    addEntry(relative, true, -1, result);
                    scanDirectory(root, child, result);
                } else {
                    addEntry(relative, false, Files.size(child),
                            Files.getLastModifiedTime(child).toInstant(), result);
                }
            }
        }
    }

    /**
     * 从索引资源补充尚未扫描到的文件条目。
     *
     * @param result 待填充的索引
     * @param resource 索引资源路径
     * @throws IOException 索引资源读取失败时
     */
    private void scanIndex(Map<String, Entry> result, String resource) throws IOException {
        String text = ResourceUtil.getResourceAsString(classLoader, resource, StandardCharsets.UTF_8.name());
        if (text == null) {
            return;
        }

        // index 只是兜底：真实扫描已存在的条目（含真实 size）不被覆盖
        for (String line : text.split("\\R")) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#")) {
                continue;
            }

            String normalized = normalize(value);
            if (!normalized.isEmpty() && !result.containsKey(normalized)) {
                addEntry(value, false, -1, result);
            }
        }
    }

    /**
     * 添加资源条目及缺失的父目录条目。
     *
     * @param path 相对资源路径
     * @param directory 是否为目录
     * @param size 资源大小
     * @param result 待填充的索引
     */
    private void addEntry(String path, boolean directory, long size, Map<String, Entry> result) {
        addEntry(path, directory, size, null, result);
    }

    /**
     * 添加资源条目及缺失的父目录条目。
     *
     * @param path 相对资源路径
     * @param directory 是否为目录
     * @param size 资源大小
     * @param modified 修改时间
     * @param result 待填充的索引
     */
    private void addEntry(String path, boolean directory, long size, Instant modified,
                          Map<String, Entry> result) {
        String normalized = normalize(path);
        if (normalized.isEmpty()) return;
        String[] parts = normalized.split("/");
        for (int i = 1; i < parts.length; i++) {
            String parent = join(parts, i);
            result.putIfAbsent(parent, Entry.directory(parent));
        }
        result.put(normalized, new Entry(normalized, directory, size, modified));
    }

    /**
     * 拼接类路径资源名。
     *
     * @param path 相对资源路径
     * @return 完整资源名
     */
    private String resourcePath(String path) {
        return basePath + normalize(path);
    }

    /**
     * 规范化资源根路径并补足末尾斜杠。
     *
     * @param value 原始根路径
     * @return 规范化的根路径
     */
    private static String normalizeBase(String value) {
        String base = value == null ? "" : value.replace('\\', '/');
        while (base.startsWith("/")) base = base.substring(1);
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base.isEmpty() ? "" : base + "/";
    }

    /**
     * 获取条目相对指定基路径的部分。
     *
     * @param base 基路径
     * @param value 条目路径
     * @return 相对路径；不在基路径下时为空字符串
     */
    private static String relative(String base, String value) {
        if (base.isEmpty()) return value;
        if (value.equals(base)) return "";
        return value.startsWith(base + "/") ? value.substring(base.length() + 1) : "";
    }

    /**
     * 计算相对路径的片段数。
     *
     * @param value 相对路径
     * @return 路径深度
     */
    private static int depth(String value) {
        if (value.isEmpty()) return 0;
        return value.split("/").length;
    }

    /**
     * 判断路径是否匹配通配模式。
     *
     * @param glob 通配模式
     * @param path 相对路径
     * @return 是否匹配
     */
    private static boolean globMatches(String glob, String path) {
        String[] pattern = glob.replace('\\', '/').split("/", -1);
        String[] value = path.split("/", -1);
        return globMatch(pattern, 0, value, 0);
    }

    /**
     * 递归匹配路径片段，支持跨层级的双星号。
     *
     * @param pattern 模式片段
     * @param pi 当前模式下标
     * @param value 路径片段
     * @param vi 当前路径下标
     * @return 是否匹配
     */
    private static boolean globMatch(String[] pattern, int pi, String[] value, int vi) {
        if (pi == pattern.length) return vi == value.length;
        if ("**".equals(pattern[pi])) {
            if (globMatch(pattern, pi + 1, value, vi)) return true;
            return vi < value.length && globMatch(pattern, pi, value, vi + 1);
        }

        return vi < value.length
                && segmentMatch(pattern[pi], value[vi])
                && globMatch(pattern, pi + 1, value, vi + 1);
    }

    /**
     * 匹配单个路径片段中的星号和问号。
     *
     * @param pattern 模式片段
     * @param value 路径片段
     * @return 是否匹配
     */
    private static boolean segmentMatch(String pattern, String value) {
        int p = 0, v = 0, star = -1, mark = -1;
        while (v < value.length()) {
            if (p < pattern.length() && (pattern.charAt(p) == '?' || pattern.charAt(p) == value.charAt(v))) {
                p++; v++;
            } else if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++; mark = v;
            } else if (star >= 0) {
                p = star + 1; v = ++mark;
            } else return false;
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') p++;
        return p == pattern.length();
    }

    /**
     * 拼接指定数量的路径片段。
     *
     * @param parts 路径片段
     * @param endExclusive 结束下标（不含）
     * @return 拼接后的相对路径
     */
    private static String join(String[] parts, int endExclusive) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < endExclusive; i++) {
            if (i > 0) builder.append('/');
            builder.append(parts[i]);
        }
        return builder.toString();
    }

    private static final class Entry {
        private final String path;
        private final boolean directory;
        private final long size;
        private final Instant modified;

        /**
         * 保存资源条目的元数据。
         *
         * @param path 相对资源路径
         * @param directory 是否为目录
         * @param size 资源大小
         * @param modified 修改时间
         */
        private Entry(String path, boolean directory, long size, Instant modified) {
            this.path = path;
            this.directory = directory;
            this.size = size;
            this.modified = modified;
        }

        /**
         * 创建目录条目。
         *
         * @param path 相对目录路径
         * @return 目录条目
         */
        static Entry directory(String path) {
            return new Entry(path, true, -1, null);
        }

        /**
         * 转换为挂载条目。
         *
         * @return 挂载条目
         */
        MountEntry toMountEntry() {
            String name = path.substring(path.lastIndexOf('/') + 1);
            return new MountEntry(path, name, directory, size, modified, null);
        }
    }
}
