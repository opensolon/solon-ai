package org.noear.solon.ai.loop.state.disk;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 原子写入工具 —— 对标 oh-my-claudecode 的 lib/atomic-write.ts
 *
 * <p>核心策略：先写入临时文件（.tmp），再通过原子重命名覆盖目标文件。
 * 配合文件锁保证并发安全，可有效防止写入中断导致的文件损坏。</p>
 *
 * @since 4.0.3
 */
public class AtomicWrite {

    private static final ReentrantLock globalLock = new ReentrantLock();

    /**
     * 原子写入文件内容（字符串）。
     */
    public static void write(Path targetPath, String content) throws IOException {
        write(targetPath, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 原子写入字节内容。
     */
    public static void write(Path targetPath, byte[] data) throws IOException {
        Path parentDir = targetPath.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
        }

        // 使用 UUID 生成唯一临时文件名，避免多进程竞争
        String tmpName = "." + targetPath.getFileName() + ".tmp." + java.util.UUID.randomUUID();
        Path tmpPath = targetPath.resolveSibling(tmpName);

        globalLock.lock();
        try {
            // 使用 CREATE_NEW 独占创建，如果文件已存在则抛异常
            Files.write(tmpPath, data, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);

            try (FileChannel channel = FileChannel.open(tmpPath, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock(0, Long.MAX_VALUE, false)) {
                channel.force(true);
            } catch (OverlappingFileLockException | UnsupportedOperationException e) {
                // 文件系统不支持锁，继续执行
            }

            try {
                Files.move(tmpPath, targetPath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // 某些文件系统（如跨分区 tmpfs）不支持原子移动，回退到普通移动
                Files.move(tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            cleanupTmp(tmpPath);
            throw new IOException("Atomic write failed for: " + targetPath, e);
        } finally {
            globalLock.unlock();
        }
    }

    /**
     * 读取文件内容。
     */
    public static String read(Path targetPath) throws IOException {
        return new String(Files.readAllBytes(targetPath), StandardCharsets.UTF_8);
    }

    /**
     * 读取文件内容，文件不存在时抛出 IOException。
     * 比 exists + read 两步调用更安全（减少 TOCTOU 窗口）。
     */
    public static String readOrThrow(Path targetPath) throws IOException {
        return new String(Files.readAllBytes(targetPath), StandardCharsets.UTF_8);
    }

    /**
     * 检查文件是否存在。
     */
    public static boolean exists(Path targetPath) {
        return Files.exists(targetPath);
    }

    /**
     * 删除文件。
     */
    public static boolean delete(Path targetPath) {
        try {
            return Files.deleteIfExists(targetPath);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 清理残留的临时文件。
     */
    public static void cleanupStaleTmpFiles(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (java.util.stream.Stream<Path> stream = Files.walk(directory, 1)) {
            stream.filter(p -> {
                        String name = p.getFileName().toString();
                        // 匹配旧格式 .tmp 后缀 和 新格式 .tmp.{uuid}
                        return name.endsWith(".tmp") || name.contains(".tmp.");
                    })
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    private static void cleanupTmp(Path tmpPath) {
        try {
            Files.deleteIfExists(tmpPath);
        } catch (IOException ignored) {
        }
    }

    private AtomicWrite() {
        // 工具类，禁止实例化
    }
}
