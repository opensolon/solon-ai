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

import org.noear.solon.lang.Preview;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存挂载来源：非本地来源的参考实现（无本地路径、不可 shell 访问）。
 *
 * <p>用途：测试（证明调用方不依赖本地文件系统）、临时会话数据，以及 db / http 来源的实现样板——
 * 它完整满足 {@link MountSource} 的契约：版本（单调递增序号）、原子可见（close 才提交）、越界拒绝。</p>
 *
 * <p>目录是隐式的：存在以 {@code dir/} 为前缀的文件即视为目录存在（与对象存储语义一致）。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public class MemoryMountSource implements MountSource {
    private static final class Node {
        final byte[] data;
        final String version;
        final Instant modified;

        Node(byte[] data, String version) {
            this.data = data;
            this.version = version;
            this.modified = Instant.now();
        }
    }

    private final String location;
    private final TreeMap<String, Node> files = new TreeMap<>();
    private final AtomicLong versionSeq = new AtomicLong();
    private final MountCapabilities capabilities = MountCapabilities.builder()
            .readWrite()
            .versioned(true)
            .atomicWrite(true)
            .build();

    public MemoryMountSource() {
        this("memory");
    }

    public MemoryMountSource(String location) {
        this.location = location == null ? "memory" : location;
    }

    @Override
    public String getLocation() {
        return location;
    }

    @Override
    public String getScheme() {
        return "memory";
    }

    @Override
    public String normalize(String path) {
        if (path == null || path.isEmpty() || ".".equals(path)) {
            return "";
        }
        String value = path.replace('\\', '/');
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        List<String> parts = new ArrayList<>();
        for (String seg : value.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                if (parts.isEmpty()) {
                    throw new SecurityException("Path escapes mount root: " + path);
                }
                parts.remove(parts.size() - 1);
            } else {
                parts.add(seg);
            }
        }
        return String.join("/", parts);
    }

    private static String nameOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    private boolean isDir(String normalized) {
        if (normalized.isEmpty()) {
            return true;
        }
        String prefix = normalized + "/";
        String next = files.ceilingKey(prefix);
        return next != null && next.startsWith(prefix);
    }

    @Override
    public synchronized MountEntry stat(String path) {
        String p = normalize(path);
        Node node = files.get(p);
        if (node != null) {
            return new MountEntry(p, nameOf(p), false, node.data.length, node.modified, node.version);
        }
        if (isDir(p)) {
            return new MountEntry(p, p.isEmpty() ? location : nameOf(p), true, -1, null, null);
        }
        return null;
    }

    @Override
    public synchronized List<MountEntry> list(String path) throws IOException {
        String p = normalize(path);
        if (!isDir(p)) {
            throw new IOException("Not a directory: " + path);
        }
        String prefix = p.isEmpty() ? "" : p + "/";
        TreeSet<String> children = new TreeSet<>();
        for (String key : files.tailMap(prefix, true).keySet()) {
            if (!key.startsWith(prefix)) {
                break;
            }
            String rest = key.substring(prefix.length());
            int slash = rest.indexOf('/');
            children.add(prefix + (slash < 0 ? rest : rest.substring(0, slash)));
        }
        List<MountEntry> result = new ArrayList<>();
        for (String child : children) {
            result.add(stat(child));
        }
        result.sort(Comparator.comparing(MountEntry::getName));
        return result;
    }

    @Override
    public synchronized List<MountEntry> find(String path, FindOptions options) {
        FindOptions actual = options == null ? FindOptions.defaults() : options;
        String base = normalize(path);
        String prefix = base.isEmpty() ? "" : base + "/";
        PathMatcher matcher = actual.getGlob() == null ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + actual.getGlob());

        TreeSet<String> candidates = new TreeSet<>();
        for (String key : files.tailMap(prefix, true).keySet()) {
            if (!key.startsWith(prefix)) {
                break;
            }
            String rest = key.substring(prefix.length());
            String[] segs = rest.split("/");
            StringBuilder acc = new StringBuilder();
            for (int i = 0; i < segs.length && i < actual.getMaxDepth(); i++) {
                if (i > 0) acc.append('/');
                acc.append(segs[i]);
                candidates.add(acc.toString());
            }
        }

        List<MountEntry> result = new ArrayList<>();
        for (String rel : candidates) {
            if (result.size() >= actual.getMaxEntries()) {
                break;
            }
            MountEntry entry = stat(prefix + rel);
            if (entry == null) continue;
            if (actual.isFilesOnly() && entry.isDirectory() || actual.isDirectoriesOnly() && !entry.isDirectory()) {
                continue;
            }
            if (matcher != null && !matcher.matches(Paths.get(rel))) {
                continue;
            }
            result.add(entry);
        }
        result.sort(Comparator.comparing(MountEntry::getPath));
        return result;
    }

    @Override
    public synchronized InputStream openRead(String path) throws IOException {
        Node node = files.get(normalize(path));
        if (node == null) {
            throw new FileNotFoundException(path);
        }
        return new ByteArrayInputStream(node.data);
    }

    @Override
    public OutputStream openWrite(String path, WriteOptions options) throws IOException {
        final WriteOptions actual = options == null ? WriteOptions.replace() : options;
        final String p = normalize(path);
        if (p.isEmpty()) {
            throw new IOException("Cannot write mount root");
        }
        synchronized (this) {
            checkWritable(path, p, actual);
        }
        return new ByteArrayOutputStream() {
            private boolean closed;

            @Override
            public void close() throws IOException {
                if (closed) return;
                closed = true;
                synchronized (MemoryMountSource.this) {
                    checkWritable(path, p, actual);
                    byte[] content = toByteArray();
                    Node old = files.get(p);
                    if (actual.isAppend() && old != null) {
                        byte[] merged = new byte[old.data.length + content.length];
                        System.arraycopy(old.data, 0, merged, 0, old.data.length);
                        System.arraycopy(content, 0, merged, old.data.length, content.length);
                        content = merged;
                    }
                    files.put(p, new Node(content, String.valueOf(versionSeq.incrementAndGet())));
                }
            }
        };
    }

    private void checkWritable(String path, String p, WriteOptions options) throws IOException {
        if (isDir(p)) {
            throw new IOException("Is a directory: " + path);
        }
        int slash = p.lastIndexOf('/');
        if (!options.isCreateParents() && slash > 0 && !isDir(p.substring(0, slash))) {
            throw new FileNotFoundException("Parent not found: " + path);
        }
        String expected = options.getExpectedVersion();
        if (expected != null) {
            Node node = files.get(p);
            String actual = node == null ? null : node.version;
            if (!expected.equals(actual)) {
                throw new MountConflictException(p, expected, actual);
            }
        }
    }

    @Override
    public synchronized void delete(String path) throws IOException {
        String p = normalize(path);
        if (p.isEmpty()) {
            throw new SecurityException("Cannot delete mount root");
        }
        if (files.remove(p) != null) {
            return;
        }
        if (isDir(p)) {
            throw new IOException("Directory not empty: " + path);
        }
        throw new FileNotFoundException(path);
    }

    @Override
    public synchronized void move(String source, String target, MoveOptions options) throws IOException {
        String from = normalize(source);
        String to = normalize(target);
        boolean replace = options != null && options.isReplaceExisting();
        Node node = files.get(from);
        if (node != null) {
            if (files.containsKey(to) && !replace) {
                throw new java.nio.file.FileAlreadyExistsException(target);
            }
            files.remove(from);
            files.put(to, new Node(node.data, String.valueOf(versionSeq.incrementAndGet())));
            return;
        }
        if (!isDir(from) || from.isEmpty()) {
            throw new FileNotFoundException(source);
        }
        if (stat(to) != null && !replace) {
            throw new java.nio.file.FileAlreadyExistsException(target);
        }
        String prefix = from + "/";
        Map<String, Node> moved = new TreeMap<>(files.subMap(prefix, prefix + Character.MAX_VALUE));
        for (Map.Entry<String, Node> e : moved.entrySet()) {
            files.remove(e.getKey());
            files.put(to + "/" + e.getKey().substring(prefix.length()), e.getValue());
        }
    }

    @Override
    public MountCapabilities capabilities() {
        return capabilities;
    }
}
