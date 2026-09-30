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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
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
 * <p>并发与一致性：同一挂载的物化由按别名分段的锁串行化；落盘走 staging 目录 +
 * 原子 rename，读者要么看到完整快照（{@code .ready} 标记存在），要么触发重物化，
 * 不会读到半成品。同指纹重复物化是幂等的（staging 固定名，rename 覆盖旧残留）。</p>
 *
 * <p>安全：缓存目录对模型侧文件工具应是不可读写的（由 TerminalTalent 的 mandatoryDeny
 * 与 ignoreDirs 兜底），防止篡改已物化的受信内容；本类自身不做越权校验。</p>
 *
 * @author noear
 * @since 4.1.1
 */
final class MountMaterializer implements AutoCloseable {
    /** 物化完成标记文件名；存在表示目录内容完整可见。 */
    static final String READY_MARKER = ".ready";

    /** staging 目录前缀；与最终目录区分，未完成的残留可在下次物化时清理。 */
    private static final String STAGING_PREFIX = ".tmp-";

    private final Path cacheRoot;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * 创建物化缓存管理器。
     *
     * @param cacheRoot 缓存根目录（如 {@code <workDir>/.soloncode/mcache}）
     */
    MountMaterializer(Path cacheRoot) {
        this.cacheRoot = cacheRoot;
    }

    /**
     * 获取挂载的物化根目录；缓存未命中时执行物化。
     *
     * <p>能力复核（capabilities().isMaterializable()）由调用方负责；此处再核一次
     * 是防御注册后被降级的场景。</p>
     *
     * @param mount 待物化的挂载
     * @return 物化后的本地根目录；挂载不可物化或物化失败时为空
     */
    Optional<Path> ensureMaterialized(Mount mount) {
        if (mount == null || !mount.getSource().capabilities().isMaterializable()) {
            return Optional.empty();
        }
        try {
            return Optional.of(materializeInternal(mount));
        } catch (IOException | RuntimeException e) {
            // 物化失败优雅降级：保持命令原样，bash 会报 "No such file or directory"，
            // 与未接入物化之前的行为一致，不把 IO 异常泄漏为工具错误。
            return Optional.empty();
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
        String fingerprint = safeFingerprintDirName(rawFingerprint);
        Path aliasDir = cacheRoot.resolve(aliasDirName(mount.getAlias()));
        Path targetDir = aliasDir.resolve(fingerprint);
        Path readyMarker = targetDir.resolve(READY_MARKER);

        if (Files.exists(readyMarker)) {
            return targetDir;
        }

        ReentrantLock lock = locks.computeIfAbsent(mount.getAlias(), k -> new ReentrantLock());
        lock.lock();
        try {
            // double-check：拿到锁后可能已被并发请求物化完成
            if (Files.exists(readyMarker)) {
                return targetDir;
            }

            Files.createDirectories(aliasDir);
            cleanupStaging(aliasDir);

            Path staging = aliasDir.resolve(STAGING_PREFIX + fingerprint);
            deleteRecursively(staging);

            try {
                Optional<Path> staged = mount.getSource().materialize("", staging);
                if (!staged.isPresent()) {
                    // 声明了可物化却返回空：视为物化失败，绝不能产出空快照目录。
                    // 否则后续 .ready 标记会把“什么都没落地”固化成有效缓存。
                    throw new IOException("Mount source declared materializable but materialize() returned empty: "
                            + mount.getAlias());
                }
            } catch (IOException | RuntimeException e) {
                // 物化失败清理现场：staging 残留必删；空别名目录顺手删除（其它指纹目录
                // 仍被占用时删除失败可忽略），避免留下只有目录没有内容的残留。
                deleteRecursively(staging);
                try {
                    Files.deleteIfExists(aliasDir);
                } catch (IOException ignored) {
                    // 别名目录非空（存在其它指纹缓存）或删除失败：无害，保留
                }
                throw e;
            }
            // .ready 内容写入指纹原文（而非哈希），便于诊断当前缓存对应的来源版本
            Files.write(staging.resolve(READY_MARKER),
                    (rawFingerprint == null ? "" : rawFingerprint).getBytes(StandardCharsets.UTF_8));

            // 原子换入：rename 不可靠地跨场景生效（目标已存在目录时部分平台失败），
            // 因此先移走旧指纹目录再换入新目录。
            if (Files.exists(targetDir)) {
                Path trash = aliasDir.resolve(STAGING_PREFIX + "old-" + fingerprint);
                deleteRecursively(trash);
                Files.move(targetDir, trash, StandardCopyOption.REPLACE_EXISTING);
                deleteRecursively(trash);
            }
            Files.move(staging, targetDir);
            return targetDir;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 清空全部物化缓存（删除缓存根目录下所有别名目录）。
     */
    void invalidateAll() {
        if (!Files.exists(cacheRoot)) return;
        try (Stream<Path> children = Files.list(cacheRoot)) {
            children.forEach(MountMaterializer::deleteQuietly);
        } catch (IOException ignored) {
            // 清理失败不致命：缓存目录会随指纹变化自然淘汰
        }
    }

    /**
     * 关闭时清空缓存，避免残留临时快照。
     */
    @Override
    public void close() {
        invalidateAll();
    }

    /**
     * 生成别名对应的缓存目录名（去掉前导 @）。
     *
     * @param alias 挂载别名
     * @return 目录名
     */
    private static String aliasDirName(String alias) {
        String raw = alias.startsWith("@") ? alias.substring(1) : alias;
        return raw.isEmpty() ? "mount" : raw;
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
     * 清理别名目录下的 staging 残留（进程异常中断时可能遗留）。
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
