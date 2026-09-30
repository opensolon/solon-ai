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
package org.noear.solon.ai.talents.cli;

import org.noear.solon.ai.talents.mount.Mount;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * 可物化虚拟挂载的本地缓存管理器。
 *
 * <p>将 Classpath（及未来的 JDBC 等纯虚拟来源）整体落地到
 * {@code <cacheRoot>/<alias>/<fingerprint>} 目录，供 bash 命令以本地路径执行其中的脚本。
 * 缓存以来源指纹为键：来源内容变化（jar 升级等）后指纹改变，旧目录自然失效。</p>
 *
 * <p>并发与一致性：同一挂载的物化由 JVM 内按别名分段的锁和缓存目录中的跨进程锁串行化；落盘走唯一 staging 目录 + 原子 rename，读者要么看到完整快照（{@code .ready} 标记存在），要么触发重物化，不会读到半成品。不同指纹的快照保留在缓存中，便于后续命令复用。</p>
 *
 * <p>安全：文件工具层拒绝直接访问缓存；启用 OS 级沙盒时由 denyWrite 阻止 bash 写入，命令侧未启用 OS 沙盒时则通过 manifest 在下一次命中时发现缓存变更并重建；本类同时拒绝缓存内部符号链接越界。</p>
 *
 * @author noear
 * @since 4.1.1
 */
final class MountMaterializer implements AutoCloseable {
    /** 物化完成标记文件名；存在表示目录内容完整可见。 */
    static final String READY_MARKER = ".ready";

    /** staging 目录前缀；与最终目录区分，未完成的残留可在下次物化时清理。 */
    private static final String STAGING_PREFIX = ".tmp-";
    /** 缓存完整性摘要；与 .ready 一样属于缓存元数据，不能作为挂载内容暴露。 */
    static final String MANIFEST_MARKER = ".manifest";

    private final Path cacheRoot;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private static final Map<String, ReentrantLock> PROCESS_LOCKS = new ConcurrentHashMap<>();

    /**
     * 创建物化缓存管理器。
     *
     * @param cacheRoot 缓存根目录（通常为 {@code ~/.soloncode/cache/mount}）
     */
    MountMaterializer(Path cacheRoot) {
        if (cacheRoot == null) {
            throw new IllegalArgumentException("cacheRoot is required");
        }
        this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
    }

    Path getCacheRoot() {
        return cacheRoot;
    }

    /**
     * 获取挂载的物化根目录；缓存未命中时执行物化。
     *
     * <p>能力复核（capabilities().isMaterializable()）由调用方负责；此处再核一次
     * 是防御注册后被降级的场景。</p>
     *
     * @param mount 待物化的挂载
     * @return 物化后的本地根目录；挂载不可物化时为空
     * @throws MaterializationException 物化失败时；调用方不得退回执行原始 @alias 命令
     */
    Optional<Path> ensureMaterialized(Mount mount) {
        if (mount == null || !mount.getSource().capabilities().isMaterializable()) {
            return Optional.empty();
        }
        try {
            return Optional.of(materializeInternal(mount));
        } catch (IOException | RuntimeException e) {
            throw new MaterializationException("无法物化挂载 " + mount.getAlias(), e);
        }
    }

    static final class MaterializationException extends RuntimeException {
        MaterializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 物化指定挂载：命中缓存直接返回，否则落地到指纹目录。
     *
     * @param mount 待物化的挂载
     * @return 物化后的本地根目录
     * @throws IOException 物化失败时
     */
    private Path materializeInternal(Mount mount) throws IOException {
        String rawFingerprint = mount.getSource().getFingerprint();
        if (rawFingerprint == null || rawFingerprint.isEmpty()) {
            throw new IOException("Mount source fingerprint is empty: " + mount.getAlias());
        }
        String fingerprint = safeFingerprintDirName(mount.getSource().getScheme() + ":"
                + mount.getSource().getLocation() + ":" + rawFingerprint);
        Path safeRoot = ensureSafeCacheRoot();
        Path aliasDir = cacheRoot.resolve(aliasDirName(mount.getAlias()));
        ensureSafeChild(safeRoot, aliasDir, true);
        Path targetDir = aliasDir.resolve(fingerprint);
        ensureSafeChild(safeRoot, targetDir, false);

        // 命中检查也必须纳入同一把跨进程锁：否则另一个进程可能正在替换或删除
        // 快照，当前线程会在校验通过后拿到一个不再稳定的目录。
        ReentrantLock lock = locks.computeIfAbsent(mount.getAlias(), k -> new ReentrantLock());
        lock.lock();
        try {
            // 目录可能在第一次快速检查后被替换，拿锁后再次校验整个缓存路径。
            ensureSafeChild(safeRoot, aliasDir, true);
            Files.createDirectories(aliasDir);
            ensureSafeChild(safeRoot, aliasDir, true);
            // 用户级缓存可能被多个 JVM 共享；文件锁避免互相清理 staging 或同时发布同一快照。
            Path lockPath = aliasDir.resolve(".lock");
            ReentrantLock processLock = PROCESS_LOCKS.computeIfAbsent(
                    lockPath.toAbsolutePath().normalize().toString(), k -> new ReentrantLock());
            processLock.lock();
            try (FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                // 拿到跨进程锁后再校验快照，发布与命中遵守同一一致性协议。
                ensureSafeChild(safeRoot, targetDir, false);
                if (isReady(targetDir, rawFingerprint, safeRoot)) {
                    return targetDir;
                }

                cleanupStaging(aliasDir);
                Path staging = aliasDir.resolve(STAGING_PREFIX + fingerprint + "-"
                        + java.util.UUID.randomUUID().toString());
                ensureSafeChild(safeRoot, staging, false);
                try {
                    Optional<Path> staged = mount.getSource().materialize("", staging);
                    if (!staged.isPresent()) {
                        throw new IOException("Mount source declared materializable but materialize() returned empty: "
                                + mount.getAlias());
                    }

                    // 物化来源不得把结果写到 staging 之外，防止错误实现越过缓存根目录。
                    Path normalizedStaging = staging.toAbsolutePath().normalize();
                    Path normalizedResult = staged.get().toAbsolutePath().normalize();
                    if (!normalizedResult.equals(normalizedStaging)
                            && !normalizedResult.startsWith(normalizedStaging)) {
                        throw new IOException("Mount source materialized outside staging directory: "
                                + mount.getAlias());
                    }

                    validateMaterializedTree(staging, safeRoot);
                    String manifest = treeDigest(staging);
                    Files.write(staging.resolve(MANIFEST_MARKER),
                            manifest.getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE);
                    // .ready 写入指纹原文，便于诊断当前缓存对应的来源版本；最后写入，作为发布标记。
                    Files.write(staging.resolve(READY_MARKER),
                            (rawFingerprint == null ? "" : rawFingerprint).getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE);

                    // 同指纹的损坏/未完成目录不应阻塞发布；其它指纹快照保留以便复用。
                    if (Files.exists(targetDir, LinkOption.NOFOLLOW_LINKS)) {
                        if (Files.isSymbolicLink(targetDir)) {
                            throw new IOException("Cache target must not be a symbolic link: " + targetDir);
                        }
                        deleteRecursively(targetDir);
                    }
                    try {
                        Files.move(staging, targetDir, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(staging, targetDir);
                    }
                    ensureSafeChild(safeRoot, targetDir, false);
                    return targetDir;
                } catch (IOException | RuntimeException e) {
                    deleteQuietly(staging);
                    throw e;
                }
            } finally {
                processLock.unlock();
            }
        } finally {
            lock.unlock();
        }
    }

    private boolean isReady(Path targetDir, String fingerprint, Path safeRoot) throws IOException {
        if (!Files.exists(targetDir, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(targetDir)) {
            return false;
        }
        ensureSafeChild(safeRoot, targetDir, false);
        Path marker = targetDir.resolve(READY_MARKER);
        Path manifest = targetDir.resolve(MANIFEST_MARKER);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(marker)
                || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(manifest)) {
            return false;
        }
        if (!fingerprint.equals(new String(Files.readAllBytes(marker), StandardCharsets.UTF_8))) {
            return false;
        }
        String expectedManifest = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8).trim();
        if (expectedManifest.isEmpty()) {
            return false;
        }
        validateMaterializedTree(targetDir, safeRoot);
        return expectedManifest.equals(treeDigest(targetDir));
    }

    private Path ensureSafeCacheRoot() throws IOException {
        Files.createDirectories(cacheRoot);
        if (Files.isSymbolicLink(cacheRoot)
                || !Files.isDirectory(cacheRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Materialization cache root must be a real directory: " + cacheRoot);
        }
        return cacheRoot.toRealPath();
    }

    /** 校验缓存根下的路径，不跟随缓存内部的符号链接；允许系统临时目录本身存在别名解析。 */
    private static void ensureSafeChild(Path safeRoot, Path candidate, boolean createDirectory) throws IOException {
        Path root = safeRoot.toAbsolutePath().normalize();
        Path path = candidate.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Materialization cache path must not be a symbolic link: " + path);
        }
        if (createDirectory && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(path);
        }
        Path realPath;
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            realPath = path.toRealPath();
        } else {
            Path ancestor = path.getParent();
            while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                ancestor = ancestor.getParent();
            }
            if (ancestor == null) {
                throw new IOException("Materialization cache path has no existing ancestor: " + candidate);
            }
            realPath = ancestor.toRealPath().resolve(ancestor.relativize(path)).normalize();
        }
        if (!realPath.startsWith(root)) {
            throw new IOException("Materialization cache path escapes cache root: " + candidate);
        }
    }

    private static void validateMaterializedTree(Path root, Path safeRoot) throws IOException {
        ensureSafeChild(safeRoot, root, false);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Materialization result is not a directory: " + root);
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("Materialization result contains symbolic link: " + path);
                }
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Materialization result contains unsupported entry: " + path);
                }
            }
        }
    }

    /** 计算物化目录内容摘要；元数据文件本身不计入摘要，避免自引用。 */
    private static String treeDigest(Path root) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<Path> paths = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(path -> !path.equals(root))
                        .filter(path -> {
                            String relative = root.relativize(path).toString().replace('\\', '/');
                            return !READY_MARKER.equals(relative) && !MANIFEST_MARKER.equals(relative);
                        })
                        .forEach(paths::add);
            }
            paths.sort(Comparator.comparing(path -> root.relativize(path).toString().replace('\\', '/')));
            byte[] buffer = new byte[8192];
            for (Path path : paths) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                updateDigest(digest, Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                        ? "dir:" + relative : "file:" + relative);
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    try (java.io.InputStream input = Files.newInputStream(path)) {
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            digest.update(buffer, 0, count);
                        }
                    }
                }
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(bytes);
        digest.update((byte) 0);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0xF, 16));
            result.append(Character.forDigit(value & 0xF, 16));
        }
        return result.toString();
    }

    /**
     * 手动清空全部物化缓存（仅供显式缓存重置使用）。正常命令和 Talent 生命周期不会调用。
     */
    void invalidateAll() {
        if (!Files.exists(cacheRoot)) return;
        try (Stream<Path> children = Files.list(cacheRoot)) {
            children.forEach(MountMaterializer::deleteQuietly);
        } catch (IOException ignored) {
            // 清理失败不致命：缓存可在下次命令中继续使用或覆盖
        }
    }

    /**
     * 持久缓存不绑定 Talent.close 生命周期；关闭 Talent 不应让后续实例失去缓存。
     */
    @Override
    public void close() {
        // no-op: this is a reusable user-level cache, not a temporary execution directory
    }

    /**
     * 生成别名对应的缓存目录名（去掉前导 @）。
     *
     * @param alias 挂载别名
     * @return 目录名
     */
    private static String aliasDirName(String alias) {
        String raw = alias == null ? "" : (alias.startsWith("@") ? alias.substring(1) : alias);
        if (raw.isEmpty()) return "mount";
        if (".".equals(raw) || "..".equals(raw) || raw.indexOf('/') >= 0 || raw.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Invalid mount alias for materialization cache: " + alias);
        }
        return raw;
    }

    /**
     * 将来源指纹转换为安全的缓存目录名。
     *
     * <p>指纹不能直接 resolve：若实现返回绝对路径（如 FileMountSource 默认指纹是
     * getLocation() 的绝对路径），{@code Path.resolve(绝对路径)} 会整体替换掉父路径，
     * 后续的换入逻辑可能误删缓存根之外的真实目录。哈希化后：① 一定是单段安全字符；
     * ② 同指纹仍稳定命中同一目录（缓存语义不变）；③ 指纹原文仍完整写入 .ready 标记，
     * 便于诊断。</p>
     *
     * @param fingerprint 来源指纹原文
     * @return 64 位 SHA-256 十六进制目录名；摘要不可用时退化为指纹清洗结果
     */
    static String safeFingerprintDirName(String fingerprint) {
        String raw = fingerprint == null ? "" : fingerprint;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(2 * hash.length);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // JVM 规范要求必须提供 SHA-256，此分支仅防御性兼容：清洗为单段安全字符
            return raw.replaceAll("[^A-Za-z0-9._-]", "_").replace("..", "__");
        }
    }

    /**
     * 清理别名目录下的 staging 残留（进程异常中断时可能遗留）。调用方已持有跨进程锁，
     * 因此此处可以安全删除其它已中断进程留下的 staging。
     *
     * @param aliasDir 别名缓存目录
     */
    private static void cleanupStaging(Path aliasDir) {
        try (Stream<Path> children = Files.list(aliasDir)) {
            children.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith(STAGING_PREFIX);
            }).forEach(MountMaterializer::deleteQuietly);
        } catch (IOException ignored) {
            // 残留清理失败不阻塞物化
        }
    }

    /**
     * 递归删除目录树。
     *
     * @param dir 待删除目录
     * @throws IOException 删除失败时
     */
    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    /**
     * 静默递归删除（失败时忽略）。
     *
     * @param dir 待删除目录
     */
    private static void deleteQuietly(Path dir) {
        try {
            deleteRecursively(dir);
        } catch (IOException ignored) {
            // 忽略
        }
    }
}
