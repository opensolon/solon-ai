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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * macOS 平台的 sandbox-exec (Seatbelt) 沙盒
 *
 * <p>原理：通过 sandbox-exec -p &lt;profile&gt; &lt;command&gt; 包装命令，
 * 利用 macOS 内核级的 Seatbelt 框架强制执行文件访问控制。</p>
 *
 * <p>基础权限清单移植自 Anthropic sandbox-runtime（基于 Chrome 沙盒策略）。</p>
 *
 * @author noear
 * @since 3.9.1
 */
public class MacOsSandboxExecutor implements OsSandboxExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(MacOsSandboxExecutor.class);

    private volatile SandboxConfig config;
    private SandboxViolationStore violationStore;

    @Override
    public void setConfig(SandboxConfig config) {
        this.config = config;
    }

    @Override
    public void setViolationStore(SandboxViolationStore store) {
        this.violationStore = store;
    }

    @Override
    public String wrapCommand(String command, Path workPath, Map<String, String> envs) {
        String profile = generateSeatbeltProfile(workPath);
        return "sandbox-exec -p " + ShellQuote.quote(profile) + " bash -c " + ShellQuote.quote(command);
    }

    @Override
    public boolean isAvailable() {
        return OsSandboxExecutorFactory.isCommandAvailable("sandbox-exec");
    }

    /**
     * 生成 Seatbelt 策略
     *
     * <p>策略设计（参考 Anthropic sandbox-runtime）：</p>
     * <ul>
     *   <li>默认拒绝所有（deny default）</li>
     *   <li>精确放行基础进程/IPC/sysctl 权限（约 60 项，基于 Chrome 沙盒策略）</li>
     *   <li>读：deny-then-allow（默认允许所有读，denyRead 黑名单禁止）</li>
     *   <li>写：allow-only（默认拒绝，显式白名单放行）</li>
     *   <li>强制拒绝 .bashrc/.gitconfig 等危险文件</li>
     *   <li>Move-Blocking：防止通过 mv/rename 绕过读写限制</li>
     * </ul>
     */
    private String generateSeatbeltProfile(Path workPath) {
        String wp = workPath.toString();
        SandboxFsConfig fsConfig = config != null ? config.getFilesystem() : null;
        StringBuilder sb = new StringBuilder();

        sb.append("(version 1)\n");
        sb.append("(deny default)\n\n");

        // ========== 基础权限（移植自 Anthropic sandbox-runtime，基于 Chrome 沙盒策略） ==========

        // 进程权限
        sb.append("(allow process-exec)\n");
        sb.append("(allow process-fork)\n");
        sb.append("(allow process-info* (target same-sandbox))\n");
        sb.append("(allow signal (target same-sandbox))\n");
        sb.append("(allow mach-priv-task-port (target same-sandbox))\n\n");

        // 用户偏好读取
        sb.append("(allow user-preference-read)\n\n");

        // Mach IPC - 精确放行
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

        // POSIX IPC
        sb.append("(allow ipc-posix-shm)\n");
        sb.append("(allow ipc-posix-sem)\n\n");

        // IOKit
        sb.append("(allow iokit-open\n");
        sb.append("  (iokit-registry-entry-class \"IOSurfaceRootUserClient\")\n");
        sb.append("  (iokit-registry-entry-class \"RootDomainUserClient\")\n");
        sb.append("  (iokit-user-client-class \"IOSurfaceSendRight\")\n");
        sb.append(")\n");
        sb.append("(allow iokit-get-properties)\n\n");

        // 安全 system-socket
        sb.append("(allow system-socket (require-all (socket-domain AF_SYSTEM) (socket-protocol 2)))\n\n");

        // sysctl-read - 精确放行
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
            sb.append("  (sysctl-name \"").append(s).append("\")\n");
        }
        String[] prefixes = {"hw.optional.arm", "hw.perflevel", "kern.proc.all",
                "kern.proc.pgrp.", "kern.proc.pid.", "machdep.cpu.", "net.routetable."};
        for (String p : prefixes) {
            sb.append("  (sysctl-name-prefix \"").append(p).append("\")\n");
        }
        sb.append(")\n\n");

        // V8 线程计算所需
        sb.append("(allow sysctl-write (sysctl-name \"kern.tcsm_enable\"))\n\n");

        // 分布式通知
        sb.append("(allow distributed-notification-post)\n");
        sb.append("(allow mach-lookup (global-name \"com.apple.SecurityServer\"))\n\n");

        // 设备文件 IO
        sb.append("(allow file-ioctl (literal \"/dev/null\"))\n");
        sb.append("(allow file-ioctl (literal \"/dev/zero\"))\n");
        sb.append("(allow file-ioctl (literal \"/dev/random\"))\n");
        sb.append("(allow file-ioctl (literal \"/dev/urandom\"))\n");
        sb.append("(allow file-ioctl (literal \"/dev/dtracehelper\"))\n");
        sb.append("(allow file-ioctl (literal \"/dev/tty\"))\n\n");

        // ========== 文件系统规则（读写分离） ==========

        if (fsConfig != null && !fsConfig.getDenyRead().isEmpty()) {
            // 读规则（deny-then-allow）
            sb.append("(allow file-read*)\n");
            for (String denyPath : fsConfig.getDenyRead()) {
                String normalized = normalizeFsPath(denyPath, workPath);
                sb.append("(deny file-read* (subpath \"").append(normalized).append("\"))\n");
            }
            for (String allowPath : fsConfig.getAllowRead()) {
                String normalized = normalizeFsPath(allowPath, workPath);
                sb.append("(allow file-read* (subpath \"").append(normalized).append("\"))\n");
            }
            // 允许 stat/lstat 以支持 realpath() 遍历
            sb.append("(allow file-read-metadata (vnode-type DIRECTORY))\n");
        } else {
            sb.append("(allow file-read*)\n");
        }

        // 写规则（allow-only）
        if (fsConfig != null && !fsConfig.getAllowWrite().isEmpty()) {
            for (String allowPath : fsConfig.getAllowWrite()) {
                String normalized = normalizeFsPath(allowPath, workPath);
                sb.append("(allow file-write* (subpath \"").append(normalized).append("\"))\n");
            }
        } else {
            sb.append("(allow file-write* (subpath \"").append(wp).append("\"))\n");
        }
        sb.append("(allow file-write* (subpath \"/tmp\"))\n");
        sb.append("(allow file-write* (subpath \"/private/tmp\"))\n\n");

        // 强制拒绝路径 + 用户配置的 deny
        List<String> denyWritePaths = new ArrayList<>();
        if (fsConfig != null) {
            denyWritePaths.addAll(fsConfig.getEffectiveDenyWrite(wp));
        } else {
            // 无配置时使用默认强制拒绝
            denyWritePaths.addAll(SandboxFsConfig.getMandatoryDenyFiles()
                    .stream().map(f -> wp + "/" + f).collect(java.util.stream.Collectors.toList()));
            denyWritePaths.addAll(SandboxFsConfig.getMandatoryDenyDirs()
                    .stream().map(d -> wp + "/" + d).collect(java.util.stream.Collectors.toList()));
        }
        for (String denyPath : denyWritePaths) {
            sb.append("(deny file-write* (subpath \"").append(denyPath).append("\"))\n");
        }
        if (!denyWritePaths.isEmpty()) {
            sb.append("\n");
        }

        // Move-Blocking：防止通过 mv/rename 绕过读写限制
        if (!denyWritePaths.isEmpty()) {
            for (String denyPath : denyWritePaths) {
                sb.append("(deny file-write-unlink (subpath \"").append(denyPath).append("\"))\n");
                sb.append("(deny file-write-create (subpath \"").append(denyPath).append("\"))\n");
            }
            sb.append("\n");
        }

        // ========== 网络 ==========
        sb.append("(allow network*)\n");

        return sb.toString();
    }

    /**
     * 规范化文件系统路径
     */
    private String normalizeFsPath(String path, Path workPath) {
        if (path == null) return workPath.toString();
        if (path.equals(".")) return workPath.toString();
        if (path.startsWith("/")) return path;
        return workPath.resolve(path).normalize().toString();
    }
}
