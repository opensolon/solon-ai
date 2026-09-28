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
import org.noear.solon.lang.Preview;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
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

    public ClasspathMountSource(ClassLoader classLoader, String basePath) {
        this.classLoader = classLoader == null ? Thread.currentThread().getContextClassLoader() : classLoader;
        this.basePath = normalizeBase(basePath);
    }

    public static ClasspathMountSource of(ClassLoader classLoader, String basePath) {
        return new ClasspathMountSource(classLoader, basePath);
    }

    public static ClasspathMountSource of(String basePath) {
        return new ClasspathMountSource(Thread.currentThread().getContextClassLoader(), basePath);
    }

    @Override
    public String getScheme() {
        return "classpath";
    }

    @Override
    public String normalize(String path) {
        if (path == null || path.isEmpty() || ".".equals(path)) return "";
        String value = path.replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        String[] parts = value.split("/");
        List<String> clean = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) throw new SecurityException("Path escapes classpath mount: " + path);
            clean.add(part);
        }
        return String.join("/", clean);
    }

    @Override
    public MountEntry stat(String path) throws IOException {
        String normalized = normalize(path);
        Entry entry = entries().get(normalized);
        if (entry == null && normalized.isEmpty() && !entries().isEmpty()) {
            return new MountEntry("", basePath.isEmpty() ? "" : basePath.substring(0, basePath.length() - 1), true, -1, null, null);
        }
        return entry == null ? null : entry.toMountEntry();
    }

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

    @Override
    public InputStream openRead(String path) throws IOException {
        String normalized = normalize(path);
        String resource = resourcePath(normalized);
        InputStream input = classLoader.getResourceAsStream(resource);
        if (input == null) throw new IOException("Classpath resource not found: " + normalized);
        return input;
    }

    @Override
    public OutputStream openWrite(String path, WriteOptions options) throws IOException {
        throw new UnsupportedOperationException("Classpath mount is read-only");
    }

    @Override
    public void delete(String path) throws IOException {
        throw new UnsupportedOperationException("Classpath mount is read-only");
    }

    @Override
    public void move(String source, String target, MoveOptions options) throws IOException {
        throw new UnsupportedOperationException("Classpath mount is read-only");
    }

    @Override
    public MountCapabilities capabilities() {
        return capabilities;
    }

    /** 丢弃索引，下一次查询时重新枚举 classpath。 */
    public void refresh() {
        index = null;
    }

    private Map<String, Entry> entries() throws IOException {
        Map<String, Entry> cached = index;
        if (cached != null) return cached;
        Map<String, Entry> result = new LinkedHashMap<>();
        Enumeration<URL> resources = classLoader.getResources(basePath);
        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            scanUrl(url, result);
        }
        // 资源索引解决 Jar 没有目录条目的情况。
        scanIndex(result, basePath + "index");
        index = result;
        return result;
    }

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

    private void scanDirectory(Path root, Path current, Map<String, Entry> result) throws IOException {
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
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

    private void scanIndex(Map<String, Entry> result, String resource) throws IOException {
        InputStream input = classLoader.getResourceAsStream(resource);
        if (input == null) return;
        try (InputStream in = input) {
            String text = new String(readAll(in), StandardCharsets.UTF_8);
            for (String line : text.split("\\R")) {
                String value = line.trim();
                if (!value.isEmpty() && !value.startsWith("#")) addEntry(value, false, -1, result);
            }
        }
    }

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

    private String resourcePath(String path) {
        return basePath + normalize(path);
    }

    private static String normalizeBase(String value) {
        String base = value == null ? "" : value.replace('\\', '/');
        while (base.startsWith("/")) base = base.substring(1);
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base.isEmpty() ? "" : base + "/";
    }

    private static String relative(String base, String value) {
        if (base.isEmpty()) return value;
        if (value.equals(base)) return "";
        return value.startsWith(base + "/") ? value.substring(base.length() + 1) : "";
    }

    private static int depth(String value) {
        if (value.isEmpty()) return 0;
        return value.split("/").length;
    }

    private static boolean globMatches(String glob, String path) {
        String[] pattern = glob.replace('\\', '/').split("/", -1);
        String[] value = path.split("/", -1);
        return globMatch(pattern, 0, value, 0);
    }

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

    private static String join(String[] parts, int endExclusive) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < endExclusive; i++) {
            if (i > 0) builder.append('/');
            builder.append(parts[i]);
        }
        return builder.toString();
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static final class Entry {
        private final String path;
        private final boolean directory;
        private final long size;
        private final Instant modified;

        private Entry(String path, boolean directory, long size, Instant modified) {
            this.path = path;
            this.directory = directory;
            this.size = size;
            this.modified = modified;
        }

        static Entry directory(String path) {
            return new Entry(path, true, -1, null);
        }

        MountEntry toMountEntry() {
            String name = path.substring(path.lastIndexOf('/') + 1);
            return new MountEntry(path, name, directory, size, modified, null);
        }
    }
}
