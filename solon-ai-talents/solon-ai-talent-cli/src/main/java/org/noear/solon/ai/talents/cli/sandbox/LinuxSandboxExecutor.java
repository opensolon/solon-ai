/*
 * Copyright 2017-2025 noear.org and authors
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
package org.noear.solon.ai.talents.cli.sandbox;

import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.ai.talents.mount.MountManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Linux 平台的 bubblewrap (bwrap) 沙盒
 *
 * <p>通过 mount namespace 控制文件系统可见性，通过 network namespace 控制网络访问。</p>
 *
 * @author noear
 * @since 3.9.1
 */
public class LinuxSandboxExecutor implements OsSandboxExecutor {

    private volatile SandboxConfig config;
    private volatile Supplier<Collection<MountDir>> mountsSupplier;
    private volatile boolean allowUserHome = true;

    @Override
    public void setMounts(Supplier<Collection<MountDir>> mountsSupplier) {
        this.mountsSupplier = mountsSupplier;
    }

    @Override
    public void setConfig(SandboxConfig config) {
        this.config = config;
    }

    @Override
    public void setAllowUserHome(boolean allowUserHome) {
        this.allowUserHome = allowUserHome;
    }

    @Override
    public String wrapCommand(String command, Path workPath, Map<String, String> envs) {
        List<String> args = buildBwrapArgs(workPath);
        args.add("--");
        args.add("bash");
        args.add("-c");
        // 统一覆盖 TMPDIR=/tmp，避免部分发行版 TMPDIR 指向不可写路径
        // 同时将 java.io.tmpdir 也指向 /tmp，确保 surefire 等构建工具写文件到可写区域
        String wrappedCommand = "export TMPDIR=/tmp; export JAVA_OPTS=\"$JAVA_OPTS -Djava.io.tmpdir=/tmp\"; " + command;
        args.add(wrappedCommand);
        return ShellQuote.quote(args.toArray(new String[0]));
    }

    @Override
    public boolean isAvailable() {
        return OsSandboxExecutorFactory.isCommandAvailable("bwrap");
    }

    /**
     * 构建 bubblewrap 参数。
     */
    List<String> buildBwrapArgs(Path workPath) {
        List<String> args = new ArrayList<>();
        args.add("bwrap");
        args.add("--new-session");
        args.add("--die-with-parent");

        SandboxFsConfig fsConfig = config != null ? config.getFilesystem() : new SandboxFsConfig();
        SandboxNetConfig netConfig = config != null ? config.getNetwork() : null;

        boolean fineGrained = config != null;
        if (fineGrained) {
            // 精细模式：根只读；只对白名单路径重新 bind 为可写。
            args.add("--ro-bind"); args.add("/"); args.add("/");
            if (!allowUserHome) {
                addDenyMount(args, System.getProperty("user.home"));
            }

            for (MountDir mount : mountsSupplier.get()) {
                addMountBinding(args, mount, mount != null && mount.isWriteable() && mountWriteAllowed(mount, fsConfig, workPath));
            }

            for (String allowPath : fsConfig.getAllowWrite()) {
                String normalized = normalizeFsPath(allowPath, workPath);
                addBindIfPossible(args, normalized, normalized, false);
            }

            // allowRead 在 denyRead 内打洞：在遮蔽 denyRead 后重新 ro-bind allowRead。
            for (String denyPath : fsConfig.getDenyRead()) {
                String normalized = normalizeFsPath(denyPath, workPath);
                addDenyMount(args, normalized);
            }
            for (String allowPath : fsConfig.getAllowRead()) {
                String normalized = normalizeFsPath(allowPath, workPath);
                addBindIfPossible(args, normalized, normalized, true);
            }
            addMountReadRules(args, fsConfig);

            for (String denyPath : fsConfig.getEffectiveDenyWrite(workPath.toString())) {
                String normalized = normalizeFsPath(denyPath, workPath);
                addDenyMount(args, normalized);
            }
            addRecursiveMandatoryDenyRules(args, workPath);
            addMountWriteDenyRules(args, fsConfig);
        } else {
            // 默认兼容模式：系统目录只读，工作区和 /tmp 可写，同时叠加强制拒绝路径。
            addBindIfPossible(args, "/usr", "/usr", true);
            addBindIfPossible(args, "/bin", "/bin", true);
            addBindIfPossible(args, "/lib", "/lib", true);
            addBindIfPossible(args, "/lib64", "/lib64", true);
            addBindIfPossible(args, "/etc/alternatives", "/etc/alternatives", true);

            addBindIfPossible(args, workPath.toString(), workPath.toString(), false);
            addBindIfPossible(args, "/tmp", "/tmp", false);
            if (allowUserHome) {
                String home = System.getProperty("user.home");
                addBindIfPossible(args, home, home, true);
                // 构建工具缓存目录需要写权限（Maven / Gradle / npm）
                addBindIfPossible(args, home + "/.m2", home + "/.m2", false);
                addBindIfPossible(args, home + "/.gradle/caches", home + "/.gradle/caches", false);
                addBindIfPossible(args, home + "/.gradle/wrapper", home + "/.gradle/wrapper", false);
                addBindIfPossible(args, home + "/.gradle/daemon", home + "/.gradle/daemon", false);
                addBindIfPossible(args, home + "/.npm/_logs", home + "/.npm/_logs", false);
                addBindIfPossible(args, home + "/.npm/_cacache", home + "/.npm/_cacache", false);
            }
            addMountBindings(args);

            for (String denyPath : fsConfig.getEffectiveDenyWrite(workPath.toString())) {
                addDenyMount(args, normalizeFsPath(denyPath, workPath));
            }
            addRecursiveMandatoryDenyRules(args, workPath);
            addMountWriteDenyRules(args, fsConfig);
        }

        args.add("--proc"); args.add("/proc");
        args.add("--dev"); args.add("/dev");
        args.add("--unshare-pid");

        // 配置了 network 即进入网络管控模式：先禁止直连；后续代理桥接可在此基础上放行。
        if (netConfig != null) {
            args.add("--unshare-net");
        }

        return args;
    }

    private void addMountBindings(List<String> args) {
        for (MountDir mount : mountsSupplier.get()) {
            addMountBinding(args, mount, mount != null && mount.isWriteable());
        }
    }

    private void addMountBinding(List<String> args, MountDir mount, boolean writable) {
        if (mount == null || !mount.isEnabled() || mount.getRealPath() == null) {
            return;
        }

        String path = mount.getRealPath().toString();
        addBindIfPossible(args, path, path, !writable);
    }

    private void addMountReadRules(List<String> args, SandboxFsConfig fsConfig) {
        for (MountDir mount : mountsSupplier.get()) {
            if (mount == null || !mount.isEnabled() || mount.getRealPath() == null) {
                continue;
            }
            Path mountPath = mount.getRealPath();
            for (String denyPath : fsConfig.getDenyRead()) {
                addDenyMount(args, normalizeFsPath(denyPath, mountPath));
            }
            for (String allowPath : fsConfig.getAllowRead()) {
                String normalized = normalizeFsPath(allowPath, mountPath);
                addBindIfPossible(args, normalized, normalized, true);
            }
        }
    }

    private void addMountWriteDenyRules(List<String> args, SandboxFsConfig fsConfig) {
        for (MountDir mount : mountsSupplier.get()) {
            if (mount == null || !mount.isEnabled() || mount.getRealPath() == null) {
                continue;
            }
            String mountRoot = mount.getRealPath().toString();
            for (String denyPath : fsConfig.getEffectiveDenyWrite(mountRoot)) {
                addDenyMount(args, normalizeFsPath(denyPath, mount.getRealPath()));
            }
            addRecursiveMandatoryDenyRules(args, mount.getRealPath());
        }
    }

    private void addRecursiveMandatoryDenyRules(List<String> args, Path rootPath) {
        if (rootPath == null || !Files.exists(rootPath)) {
            return;
        }
        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(rootPath) && SandboxFsConfig.isMandatoryDenyPath(rootPath.relativize(dir).toString().replace("\\", "/"))) {
                        addDenyMount(args, dir.toString());
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (SandboxFsConfig.isMandatoryDenyPath(rootPath.relativize(file).toString().replace("\\", "/"))) {
                        addDenyMount(args, file.toString());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Best effort: static top-level deny rules are still applied by getEffectiveDenyWrite.
        }
    }

    private boolean mountWriteAllowed(MountDir mount, SandboxFsConfig fsConfig, Path workPath) {
        if (mount == null || !mount.isEnabled() || !mount.isWriteable() || mount.getRealPath() == null) {
            return false;
        }

        Path mountPath = mount.getRealPath().normalize();
        boolean allowed = false;
        for (String allowPath : fsConfig.getAllowWrite()) {
            String normalized = normalizeFsPath(allowPath, mountPath);
            if (mountPath.startsWith(Paths.get(normalized).normalize())) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            return false;
        }
        for (String denyPath : fsConfig.getDenyWrite()) {
            String normalized = normalizeFsPath(denyPath, mountPath);
            if (mountPath.startsWith(Paths.get(normalized).normalize())) {
                return false;
            }
        }
        return true;
    }

    private void addBindIfPossible(List<String> args, String source, String dest, boolean readOnly) {
        if (new File(source).exists()) {
            args.add(readOnly ? "--ro-bind" : "--bind");
            args.add(source);
            args.add(dest);
        }
    }

    private void addDenyMount(List<String> args, String path) {
        File f = new File(path);
        if (f.exists() && f.isDirectory()) {
            args.add("--tmpfs");
            args.add(path);
        } else {
            // 对不存在的危险文件也保留覆盖规则，尽量防止命令在沙盒内创建。
            args.add("--ro-bind");
            args.add("/dev/null");
            args.add(path);
        }
    }

    private String normalizeFsPath(String path, Path workPath) {
        if (path == null || path.equals(".")) return workPath.toString();
        if (path.startsWith("/")) return path;
        return workPath.resolve(path).normalize().toString();
    }
}
