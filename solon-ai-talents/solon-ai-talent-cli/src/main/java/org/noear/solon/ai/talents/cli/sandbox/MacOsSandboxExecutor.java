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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * macOS 平台的 sandbox-exec (Seatbelt) 沙盒
 *
 * <p>基础权限清单移植自 Anthropic sandbox-runtime（基于 Chrome 沙盒策略）。</p>
 *
 * @author noear
 * @since 3.9.1
 */
public class MacOsSandboxExecutor implements SandboxExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(MacOsSandboxExecutor.class);

    private volatile SandboxConfig config;
    private volatile Collection<MountDir> mounts = Collections.emptyList();
    private volatile boolean allowUserHome = true;
    private SandboxViolationStore violationStore;

    @Override
    public void setMounts(Collection<MountDir> mounts) {
        this.mounts = mounts != null ? mounts : Collections.emptyList();
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
    public void setViolationStore(SandboxViolationStore store) {
        this.violationStore = store;
    }

    @Override
    public String wrapCommand(String command, Path workPath, Map<String, String> envs) {
        String profile = generateSeatbeltProfile(workPath);
        return ShellQuote.quote(new String[]{"sandbox-exec", "-p", profile, "bash", "-c", command});
    }

    @Override
    public boolean isAvailable() {
        return SandboxExecutorFactory.isCommandAvailable("sandbox-exec");
    }

    /**
     * 生成 Seatbelt 策略
     */
    String generateSeatbeltProfile(Path workPath) {
        String wp = workPath.toString();
        SandboxFsConfig fsConfig = config != null ? config.getFilesystem() : new SandboxFsConfig();
        SandboxNetConfig netConfig = config != null ? config.getNetwork() : null;
        String logTag = generateLogTag();
        StringBuilder sb = new StringBuilder();

        sb.append("(version 1)\n");
        sb.append("(deny default (with message ").append(seatbeltString(logTag)).append("))\n\n");

        // ========== 基础权限（移植自 Anthropic sandbox-runtime，基于 Chrome 沙盒策略） ==========
        sb.append("(allow process-exec)\n");
        sb.append("(allow process-fork)\n");
        sb.append("(allow process-info* (target same-sandbox))\n");
        sb.append("(allow signal (target same-sandbox))\n");
        sb.append("(allow mach-priv-task-port (target same-sandbox))\n\n");

        sb.append("(allow user-preference-read)\n\n");

        sb.append("(allow mach-lookup\n");
        sb.append("  (global-name \"com.apple.audio.systemsoundserver\")\n");
        sb.append("  (global-name \"com.apple.distributed_notifications@Uv3\")\n");
        sb.append("  (global-name \"com.apple.FontObjectsServer\")\n");
        sb.append("  (global-name \"com.apple.fonts\")\n");
        sb.append("  (global-name \"com.apple.logd\")\n");
        sb.append("  (global-name \"com.apple.lsd.mapdb\")\n");
        sb.append("  (global-name \"com.apple.PowerManagement.control\")\n");
        sb.append("  (global-name \"com.apple.system.logger\")\n");
        sb.append("  (global-name \"com.apple.system.notification_center\")\n");
        sb.append("  (global-name \"com.apple.system.opendirectoryd.libinfo\")\n");
        sb.append("  (global-name \"com.apple.system.opendirectoryd.membership\")\n");
        sb.append("  (global-name \"com.apple.bsd.dirhelper\")\n");
        sb.append("  (global-name \"com.apple.securityd.xpc\")\n");
        sb.append("  (global-name \"com.apple.coreservices.launchservicesd\")\n");
        sb.append(")\n\n");

        sb.append("(allow ipc-posix-shm)\n");
        sb.append("(allow ipc-posix-sem)\n\n");

        sb.append("(allow iokit-open\n");
        sb.append("  (iokit-registry-entry-class \"IOSurfaceRootUserClient\")\n");
        sb.append("  (iokit-registry-entry-class \"RootDomainUserClient\")\n");
        sb.append("  (iokit-user-client-class \"IOSurfaceSendRight\")\n");
        sb.append(")\n");
        sb.append("(allow iokit-get-properties)\n\n");

        sb.append("(allow system-socket (require-all (socket-domain AF_SYSTEM) (socket-protocol 2)))\n\n");

        sb.append("(allow sysctl-read\n");
        String[] sysctls = {
                "hw.activecpu", "hw.byteorder", "hw.cacheconfig", "hw.cachelinesize_compat",
                "hw.cpufamily", "hw.cputype", "hw.l1dcachesize_compat", "hw.l1icachesize_compat",
                "hw.l2cachesize_compat", "hw.l3cachesize_compat", "hw.logicalcpu",
                "hw.logicalcpu_max", "hw.machine", "hw.memsize", "hw.ncpu",
                "hw.nperflevels", "hw.packages", "hw.pagesize_compat", "hw.pagesize",
                "hw.physicalcpu", "hw.physicalcpu_max", "hw.vectorunit",
                "kern.argmax", "kern.hostname", "kern.maxfiles", "kern.maxfilesperproc",
                "kern.maxproc", "kern.ngroups", "kern.osproductversion", "kern.osrelease",
                "kern.ostype", "kern.osversion", "kern.secure_kernel", "kern.usrstack64",
                "kern.version", "machdep.cpu.brand_string", "vm.loadavg"
        };
        for (String s : sysctls) {
            sb.append("  (sysctl-name ").append(seatbeltString(s)).append(")\n");
        }
        String[] prefixes = {"hw.optional.arm", "hw.perflevel", "kern.proc.all",
                "kern.proc.pgrp.", "kern.proc.pid.", "machdep.cpu.", "net.routetable."};
        for (String p : prefixes) {
            sb.append("  (sysctl-name-prefix ").append(seatbeltString(p)).append(")\n");
        }
        sb.append(")\n\n");

        sb.append("(allow sysctl-write (sysctl-name \"kern.tcsm_enable\"))\n\n");
        sb.append("(allow distributed-notification-post)\n");
        sb.append("(allow mach-lookup (global-name \"com.apple.SecurityServer\"))\n\n");

        // 设备节点：file-ioctl（ioctl 操作）+ file-write*（写重定向，git 等工具需 open /dev/null 写入）
        sb.append("(allow file-ioctl (literal \"/dev/null\"))\n");
        sb.append("(allow file-write* (literal \"/dev/null\"))\n");
        sb.append("(allow file-ioctl (literal \"/dev/zero\"))\n");
        sb.append("(allow file-ioctl (literal \"/dev/random\"))\n");
        sb.append("(allow file-ioctl (literal \"/dev/urandom\"))\n");
        sb.append("(allow file-ioctl (literal \"/dev/dtracehelper\"))\n");
        sb.append("(allow file-ioctl (literal \"/dev/tty\"))\n");
        sb.append("(allow file-write* (literal \"/dev/tty\"))\n\n");

        // ========== 文件系统规则（读写分离） ==========
        sb.append("(allow file-read*)\n");
        if (!allowUserHome) {
            appendPathRule(sb, "deny", "file-read*", System.getProperty("user.home"), logTag);
        }
        for (MountDir mount : mounts) {
            if (mount != null && mount.isEnabled() && mount.getRealPath() != null) {
                appendPathRule(sb, "allow", "file-read*", mount.getRealPath().toString(), null);
            }
        }
        for (String denyPath : fsConfig.getDenyRead()) {
            appendPathRule(sb, "deny", "file-read*", normalizeFsPath(denyPath, workPath), logTag);
        }
        for (String allowPath : fsConfig.getAllowRead()) {
            appendPathRule(sb, "allow", "file-read*", normalizeFsPath(allowPath, workPath), null);
        }
        appendMountReadRules(sb, fsConfig, logTag);
        if (!fsConfig.getDenyRead().isEmpty()) {
            sb.append("(allow file-read-metadata (vnode-type DIRECTORY))\n");
        }
        sb.append("\n");

        for (String allowPath : fsConfig.getAllowWrite()) {
            appendPathRule(sb, "allow", "file-write*", normalizeFsPath(allowPath, workPath), null);
        }
        for (MountDir mount : mounts) {
            if (mount != null && mount.isEnabled() && mount.isWriteable() && mount.getRealPath() != null
                    && mountWriteAllowed(mount, fsConfig)) {
                appendPathRule(sb, "allow", "file-write*", mount.getRealPath().toString(), null);
            }
        }
        if (fsConfig.getAllowWrite().stream().noneMatch("/tmp"::equals)) {
            appendPathRule(sb, "allow", "file-write*", "/tmp", null);
        }
        appendPathRule(sb, "allow", "file-write*", "/private/tmp", null);
        sb.append("\n");

        List<String> denyWritePaths = new ArrayList<>();
        for (String denyPath : fsConfig.getEffectiveDenyWrite(wp)) {
            denyWritePaths.add(normalizeFsPath(denyPath, workPath));
        }
        for (String denyPath : denyWritePaths) {
            appendPathRule(sb, "deny", "file-write*", denyPath, logTag);
        }
        for (String pattern : mandatoryDenyRegexes(wp)) {
            appendRegexRule(sb, "deny", "file-write*", pattern, logTag);
        }
        for (MountDir mount : mounts) {
            if (mount != null && mount.isEnabled() && mount.getRealPath() != null) {
                for (String pattern : mandatoryDenyRegexes(mount.getRealPath().toString())) {
                    appendRegexRule(sb, "deny", "file-write*", pattern, logTag);
                }
                String mountRoot = mount.getRealPath().toString();
                for (String denyPath : fsConfig.getEffectiveDenyWrite(mountRoot)) {
                    appendPathRule(sb, "deny", "file-write*", normalizeFsPath(denyPath, mount.getRealPath()), logTag);
                }
                if (!mount.isWriteable() || !mountWriteAllowed(mount, fsConfig)) {
                    appendPathRule(sb, "deny", "file-write*", mount.getRealPath().toString(), logTag);
                }
            }
        }
        sb.append("\n");

        // Move-Blocking：阻断目标路径和祖先目录的 create/unlink，防止 rename/mv 绕过。
        Set<String> moveBlockingPaths = new LinkedHashSet<>();
        for (String denyPath : denyWritePaths) {
            moveBlockingPaths.addAll(ancestorPaths(denyPath, wp));
        }
        for (String denyPath : fsConfig.getDenyRead()) {
            moveBlockingPaths.add(normalizeFsPath(denyPath, workPath));
        }
        for (String denyPath : moveBlockingPaths) {
            String normalized = normalizeFsPath(denyPath, workPath);
            appendPathRule(sb, "deny", "file-write-unlink", normalized, logTag);
            appendPathRule(sb, "deny", "file-write-create", normalized, logTag);
        }
        for (MountDir mount : mounts) {
            if (mount == null || !mount.isEnabled() || mount.getRealPath() == null) {
                continue;
            }
            String mountRoot = mount.getRealPath().toString();
            for (String pattern : mandatoryDenyRegexes(mountRoot)) {
                appendRegexRule(sb, "deny", "file-write-unlink", pattern, logTag);
                appendRegexRule(sb, "deny", "file-write-create", pattern, logTag);
            }
            for (String denyPath : fsConfig.getEffectiveDenyWrite(mountRoot)) {
                String normalized = normalizeFsPath(denyPath, mount.getRealPath());
                appendPathRule(sb, "deny", "file-write-unlink", normalized, logTag);
                appendPathRule(sb, "deny", "file-write-create", normalized, logTag);
            }
        }
        for (String pattern : mandatoryDenyRegexes(wp)) {
            appendRegexRule(sb, "deny", "file-write-unlink", pattern, logTag);
            appendRegexRule(sb, "deny", "file-write-create", pattern, logTag);
        }
        sb.append("\n");

        // ========== 网络 ==========
        if (netConfig == null) {
            sb.append("(allow network*)\n");
        } else if (!netConfig.getAllowedDomains().isEmpty()) {
            // 未接入域名代理前，避免直接放开外网：仅允许本机代理/环回地址。
            sb.append("(allow network-outbound (remote ip \"127.0.0.1:*\"))\n");
            sb.append("(allow network-outbound (remote ip \"localhost:*\"))\n");
            sb.append("(allow network-bind (local ip \"127.0.0.1:*\"))\n");
            sb.append("(allow network-inbound (local ip \"127.0.0.1:*\"))\n");
        }

        return sb.toString();
    }

    private void appendMountReadRules(StringBuilder sb, SandboxFsConfig fsConfig, String logTag) {
        for (MountDir mount : mounts) {
            if (mount == null || !mount.isEnabled() || mount.getRealPath() == null) {
                continue;
            }
            Path mountRoot = mount.getRealPath();
            for (String denyPath : fsConfig.getDenyRead()) {
                appendPathRule(sb, "deny", "file-read*", normalizeFsPath(denyPath, mountRoot), logTag);
            }
            for (String allowPath : fsConfig.getAllowRead()) {
                appendPathRule(sb, "allow", "file-read*", normalizeFsPath(allowPath, mountRoot), null);
            }
        }
    }

    private boolean mountWriteAllowed(MountDir mount, SandboxFsConfig fsConfig) {
        if (mount == null || !mount.isEnabled() || !mount.isWriteable() || mount.getRealPath() == null) {
            return false;
        }
        Path mountRoot = mount.getRealPath().normalize();
        boolean allowed = false;
        for (String allowPath : fsConfig.getAllowWrite()) {
            if (mountRoot.startsWith(Paths.get(normalizeFsPath(allowPath, mountRoot)).normalize())) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            return false;
        }
        for (String denyPath : fsConfig.getDenyWrite()) {
            if (mountRoot.startsWith(Paths.get(normalizeFsPath(denyPath, mountRoot)).normalize())) {
                return false;
            }
        }
        return true;
    }

    private void appendPathRule(StringBuilder sb, String action, String operation, String path, String logTag) {
        sb.append('(').append(action).append(' ').append(operation)
                .append(" (subpath ").append(seatbeltString(path)).append(')');
        if (logTag != null) {
            sb.append(" (with message ").append(seatbeltString(logTag)).append(')');
        }
        sb.append(")\n");
    }

    private void appendRegexRule(StringBuilder sb, String action, String operation, String regex, String logTag) {
        sb.append('(').append(action).append(' ').append(operation)
                .append(" (regex ").append(seatbeltString(regex)).append(')');
        if (logTag != null) {
            sb.append(" (with message ").append(seatbeltString(logTag)).append(')');
        }
        sb.append(")\n");
    }

    private String normalizeFsPath(String path, Path workPath) {
        if (path == null || path.equals(".")) return workPath.toString();
        if (path.startsWith("/")) return path;
        return workPath.resolve(path).normalize().toString();
    }

    private String seatbeltString(String value) {
        if (value == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private List<String> mandatoryDenyRegexes(String workPath) {
        String root = regexQuote(workPath);
        List<String> patterns = new ArrayList<>();
        for (String file : SandboxFsConfig.getMandatoryDenyFiles()) {
            patterns.add(root + "(/.*)?/" + regexQuote(file) + "(/.*)?$");
        }
        for (String dir : SandboxFsConfig.getMandatoryDenyDirs()) {
            patterns.add(root + "(/.*)?/" + regexQuote(dir) + "(/.*)?$");
        }
        return patterns;
    }

    private String regexQuote(String value) {
        StringBuilder sb = new StringBuilder();
        String meta = "\\.[]{}()+-*?^$|";
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (meta.indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private List<String> ancestorPaths(String path, String stopAt) {
        List<String> result = new ArrayList<>();
        if (path == null || path.isEmpty()) {
            return result;
        }
        Path current = Paths.get(path).normalize();
        Path stop = Paths.get(stopAt).normalize();
        while (current != null && current.startsWith(stop)) {
            result.add(current.toString());
            if (current.equals(stop)) {
                break;
            }
            current = current.getParent();
        }
        return result;
    }

    private String generateLogTag() {
        return "CMD64_" + Long.toHexString(System.nanoTime()) + "_SBX";
    }
}
