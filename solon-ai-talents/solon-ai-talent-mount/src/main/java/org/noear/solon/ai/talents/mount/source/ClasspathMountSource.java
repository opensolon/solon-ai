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

import org.noear.solon.ai.talents.mount.*;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final MountCapabilities capabilities = MountCapabilities.readOnlyVirtual();
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
                    addEntry(relative, jarEntry.isDirectory(), jarEntry.getSize(), result);
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
                    addEntry(relative, false, Files.size(child), result);
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
        String normalized = normalize(path);
        if (normalized.isEmpty()) return;
        String[] parts = normalized.split("/");
        for (int i = 1; i < parts.length; i++) {
            String parent = join(parts, i);
            result.putIfAbsent(parent, Entry.directory(parent));
        }
        result.put(normalized, new Entry(normalized, directory, size, null));
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
