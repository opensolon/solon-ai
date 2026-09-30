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

    /**
     * 创建本地文件挂载来源。
     *
     * @param rootPath 挂载根目录；不可为 null
     */
    public FileMountSource(Path rootPath) {
        if (rootPath == null) {
            throw new IllegalArgumentException("rootPath must not be null");
        }
        this.rootPath = rootPath.toAbsolutePath().normalize();
        // materializable=false：本地来源 shell 可达（getLocalRoot 恒有值），物化分支对它是死代码；
        // 且本类不实现 MountSource#materialize——若误声明 true，直接调用物化器会得到能力位与实现不一致的
        // 半吊子流程（默认实现返回空却声明可物化）。诚实声明，避免未来误触发。
        this.capabilities = new MountCapabilities(true, true, true, true, true, true,
                true, true, true, false);
    }

    /**
     * 根据根目录路径创建挂载来源。
     *
     * @param rootPath 挂载根目录
     * @return 本地文件挂载来源
     */
    public static FileMountSource of(Path rootPath) {
        return new FileMountSource(rootPath);
    }

    /**
     * 根据根目录字符串创建挂载来源。
     *
     * @param rootPath 挂载根目录
     * @return 本地文件挂载来源
     */
    public static FileMountSource of(String rootPath) {
        return new FileMountSource(java.nio.file.Paths.get(rootPath));
    }

    /**
     * 获取规范化后的绝对根目录。
     *
     * @return 根目录路径
     */
    public Path getRootPath() {
        return rootPath;
    }

    /**
     * 获取挂载根目录的位置字符串。
     *
     * @return 根目录位置
     */
    @Override
    public String getLocation() {
        return rootPath.toString();
    }

    /**
     * 获取文件挂载协议。
     *
     * @return file
     */
    @Override
    public String getScheme() {
        return "file";
    }

    /**
     * 规范化相对路径并拒绝越过挂载根目录的路径。
     *
     * @param path 待处理路径
     * @return 使用斜杠分隔的相对路径
     */
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

    /**
     * 解析并检查路径及已有符号链接是否仍在挂载根目录内。
     *
     * @param path 相对路径
     * @param forWrite 是否检查待写入路径的父目录
     * @return 已解析的路径
     * @throws IOException 实际路径解析失败时
     */
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

    /**
     * 查询文件或目录的元数据。
     *
     * @param path 相对路径
     * @return 挂载条目；不存在时为 null
     * @throws IOException 文件属性读取失败时
     */
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

    /**
     * 列出目录的直接子条目。
     *
     * @param path 相对目录路径
     * @return 按名称排序的子条目
     * @throws IOException 目录不存在、非目录或读取失败时
     */
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

    /**
     * 按查找选项遍历文件树并筛选条目。
     *
     * @param path 搜索起点
     * @param options 查找选项；为 null 时使用默认选项
     * @return 按路径排序的匹配条目
     * @throws IOException 文件树遍历失败时
     */
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
                    /**
                     * 访问非符号链接文件并收集匹配条目。
                     *
                     * @param file 当前文件
                     * @param attrs 文件属性
                     * @return 继续遍历或达到数量上限后终止
                     * @throws IOException 条目读取失败时
                     */
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        // 枚举时不跟随符号链接，也不因某个越界链接中断整个来源的发现。
                        if (!attrs.isSymbolicLink()) {
                            addIfMatches(file, false, base, matcher, actual, result);
                        }
                        return result.size() >= actual.getMaxEntries() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    }

                    /**
                     * 在遍历目录前收集非起点的匹配条目。
                     *
                     * @param dir 当前目录
                     * @param attrs 目录属性
                     * @return 继续遍历或达到数量上限后终止
                     * @throws IOException 条目读取失败时
                     */
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

    /**
     * 将符合类型和路径模式的条目加入结果集。
     *
     * @param file 候选路径
     * @param directory 是否为目录
     * @param base 搜索起点的相对路径
     * @param matcher 路径匹配器；为 null 时不匹配模式
     * @param options 查找选项
     * @param result 待填充的结果集
     * @throws IOException 条目读取失败时
     */
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

    /**
     * 打开文件输入流。
     *
     * @param path 相对文件路径
     * @return 文件输入流
     * @throws IOException 文件打开失败时
     */
    @Override
    public InputStream openRead(String path) throws IOException {
        return Files.newInputStream(resolveChecked(path, false), StandardOpenOption.READ);
    }

    /**
     * 按选项打开追加或覆盖写入流。
     *
     * @param path 相对文件路径
     * @param options 写入选项；为 null 时覆盖写入
     * @return 文件输出流
     * @throws IOException 文件或父目录创建失败时
     */
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

    /**
     * 删除指定文件或空目录，禁止删除挂载根目录。
     *
     * @param path 相对路径
     * @throws IOException 删除失败时
     */
    @Override
    public void delete(String path) throws IOException {
        Path file = resolveChecked(path, false);
        if (file.equals(rootPath)) {
            throw new SecurityException("Cannot delete mount root");
        }
        Files.delete(file);
    }

    /**
     * 移动条目，并按选项决定是否覆盖目标。
     *
     * @param source 来源路径
     * @param target 目标路径
     * @param options 移动选项
     * @throws IOException 移动失败时
     */
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

    /**
     * 获取本地文件挂载能力。
     *
     * @return 挂载能力
     */
    @Override
    public MountCapabilities capabilities() {
        return capabilities;
    }

    /**
     * 获取本地挂载根目录。
     *
     * @return 包含根目录的可选值
     */
    @Override
    public Optional<Path> getLocalRoot() {
        return Optional.of(rootPath);
    }

    /**
     * 获取经过边界检查的本地路径。
     *
     * @param path 相对路径
     * @return 包含本地路径的可选值；发生 IO 错误时为空
     */
    @Override
    public Optional<Path> getLocalPath(String path) {
        try {
            return Optional.of(resolveChecked(path, false));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
