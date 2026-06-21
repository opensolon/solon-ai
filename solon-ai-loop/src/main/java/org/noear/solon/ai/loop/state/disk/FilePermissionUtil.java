package org.noear.solon.ai.loop.state.disk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * 文件权限工具 —— 对标 oh-my-claudecode 的 0o600 权限设置。
 *
 * <p>提供 POSIX 权限设置能力。在支持 POSIX 的文件系统上，
 * 将文件权限设置为 0o600（owner 读写，group/other 无权限）。</p>
 *
 * <p>在不支持 POSIX 的系统（如 Windows）上静默跳过。</p>
 *
 * @since 4.0.4
 */
public class FilePermissionUtil {

    private static final Set<PosixFilePermission> PERM_0600 = PosixFilePermissions.fromString("rw-------");

    /**
     * 将文件权限设置为 0o600（仅所有者可读写）。
     *
     * @param path 目标文件路径
     */
    public static void set0600(Path path) {
        if (path == null || !Files.exists(path)) return;

        try {
            // 检查文件系统是否支持 POSIX 权限
            if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(path, PERM_0600);
            }
            // 在非 POSIX 系统（如 Windows）上静默跳过
        } catch (IOException | UnsupportedOperationException e) {
            // 忽略权限设置失败（非关键操作）
        }
    }

    /**
     * 设置目录权限为 0o700（仅所有者可读写执行）。
     *
     * @param path 目标目录路径
     */
    public static void set0700(Path path) {
        if (path == null || !Files.exists(path)) return;

        try {
            if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
            }
        } catch (IOException | UnsupportedOperationException e) {
            // 忽略
        }
    }

    /**
     * 检查当前文件系统是否支持 POSIX 权限。
     */
    public static boolean isPosixSupported() {
        try {
            return java.nio.file.FileSystems.getDefault()
                    .supportedFileAttributeViews().contains("posix");
        } catch (Exception e) {
            return false;
        }
    }
}
