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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/** 
 * 本地文件系统挂载来源。
 *
 * @author noear 
 * @since 4.1.1
 */
@Preview("4.1.1")
public final class FileMountSource implements MountSource {
    private final Path rootPath;
    private final MountCapabilities capabilities;

    public FileMountSource(Path rootPath) {
        if (rootPath == null) {
            throw new IllegalArgumentException("rootPath must not be null");
        }
        this.rootPath = rootPath.toAbsolutePath().normalize();
        this.capabilities = new MountCapabilities(true, true, true, true, true, true,
                true, true, true, true);
    }

    public static FileMountSource of(Path rootPath) {
        return new FileMountSource(rootPath);
    }

    public static FileMountSource of(String rootPath) {
        return new FileMountSource(java.nio.file.Paths.get(rootPath));
    }

    public Path getRootPath() {
        return rootPath;
    }

    @Override
    public String getLocation() {
        return rootPath.toString();
    }

    @Override
    public String getScheme() {
        return "file";
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
        Path normalized = java.nio.file.Paths.get(value).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new SecurityException("Path escapes mount root: " + path);
        }
        String result = normalized.toString().replace('\\', '/');
        return ".".equals(result) ? "" : result;
    }

    private Path resolveChecked(String path, boolean forWrite) throws IOException {
        String normalized = normalize(path);
        Path candidate = rootPath.resolve(normalized).normalize();
        if (!candidate.startsWith(rootPath)) {
            throw new SecurityException("Path escapes mount root: " + path);
        }

        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            Path real = candidate.toRealPath();
            Path realRoot = rootPath.toRealPath();
            if (!real.startsWith(realRoot)) {
                throw new SecurityException("Symbolic link escapes mount root: " + path);
            }
        } else if (forWrite) {
            Path parent = candidate.getParent();
            if (parent != null && Files.exists(parent)) {
                Path realParent = parent.toRealPath();
                Path realRoot = rootPath.toRealPath();
                if (!realParent.startsWith(realRoot)) {
                    throw new SecurityException("Parent escapes mount root: " + path);
                }
            }
        }
        return candidate;
    }

    @Override
    public MountEntry stat(String path) throws IOException {
        Path file = resolveChecked(path, false);
        if (!Files.exists(file)) {
            return null;
        }
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        String normalized = normalize(path);
        String name = normalized.isEmpty() ? rootPath.getFileName().toString() : file.getFileName().toString();
        return new MountEntry(normalized, name, attrs.isDirectory(), attrs.isRegularFile() ? attrs.size() : -1,
                Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis()), null);
    }

    @Override
    public List<MountEntry> list(String path) throws IOException {
        Path dir = resolveChecked(path, false);
        if (!Files.isDirectory(dir)) {
            throw new IOException("Not a directory: " + path);
        }
        List<MountEntry> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                String childPath = rootPath.relativize(child).toString().replace('\\', '/');
                result.add(stat(childPath));
            }
        }
        result.sort(Comparator.comparing(MountEntry::getName));
        return result;
    }

    @Override
    public List<MountEntry> find(String path, FindOptions options) throws IOException {
        final FindOptions actual = options == null ? FindOptions.defaults() : options;
        final Path start = resolveChecked(path, false);
        final String base = normalize(path);
        if (actual.getMaxEntries() == 0) {
            return new ArrayList<>();
        }
        final PathMatcher matcher = actual.getGlob() == null ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + actual.getGlob());
        List<MountEntry> result = new ArrayList<>();
        if (!Files.exists(start)) {
            return result;
        }
        Files.walkFileTree(start, EnumSet.noneOf(java.nio.file.FileVisitOption.class), actual.getMaxDepth(),
                new java.nio.file.SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        // 枚举时不跟随符号链接，也不因某个越界链接中断整个来源的发现。
                        if (!attrs.isSymbolicLink()) {
                            addIfMatches(file, false, base, matcher, actual, result);
                        }
                        return result.size() >= actual.getMaxEntries() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        if (!dir.equals(start)) {
                            addIfMatches(dir, true, base, matcher, actual, result);
                        }
                        return result.size() >= actual.getMaxEntries() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    }
                });
        result.sort(Comparator.comparing(MountEntry::getPath));
        return result;
    }

    private void addIfMatches(Path file, boolean directory, String base, PathMatcher matcher,
                              FindOptions options, List<MountEntry> result) throws IOException {
        if (options.isFilesOnly() && directory || options.isDirectoriesOnly() && !directory) {
            return;
        }
        String full = rootPath.relativize(file).toString().replace('\\', '/');
        String relative = base.isEmpty() ? full : (full.startsWith(base + "/") ? full.substring(base.length() + 1) : full);
        if (matcher != null && !matcher.matches(java.nio.file.Paths.get(relative))) {
            return;
        }
        MountEntry entry = stat(full);
        if (entry != null) {
            result.add(entry);
        }
    }

    @Override
    public InputStream openRead(String path) throws IOException {
        return Files.newInputStream(resolveChecked(path, false), StandardOpenOption.READ);
    }

    @Override
    public OutputStream openWrite(String path, WriteOptions options) throws IOException {
        WriteOptions actual = options == null ? WriteOptions.replace() : options;
        Path file = resolveChecked(path, true);
        if (actual.isCreateParents() && file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        if (actual.isAppend()) {
            return Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        }
        return Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    @Override
    public void delete(String path) throws IOException {
        Path file = resolveChecked(path, false);
        if (file.equals(rootPath)) {
            throw new SecurityException("Cannot delete mount root");
        }
        Files.delete(file);
    }

    @Override
    public void move(String source, String target, MoveOptions options) throws IOException {
        Path from = resolveChecked(source, false);
        Path to = resolveChecked(target, true);
        if (to.getParent() != null) {
            Files.createDirectories(to.getParent());
        }
        if (options != null && options.isReplaceExisting()) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.move(from, to);
        }
    }

    @Override
    public MountCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public Optional<Path> getLocalRoot() {
        return Optional.of(rootPath);
    }

    @Override
    public Optional<Path> getLocalPath(String path) {
        try {
            return Optional.of(resolveChecked(path, false));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
