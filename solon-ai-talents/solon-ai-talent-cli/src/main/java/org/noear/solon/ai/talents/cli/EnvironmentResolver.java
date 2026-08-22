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
package org.noear.solon.ai.talents.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * 系统环境变量解析器。
 *
 * <p>解决 Windows 上 JVM 环境变量快照不刷新的问题：JVM 启动时从父进程继承一份
 * 不可变的环境快照（{@code System.getenv()}），用户在安装新软件、修改 PATH 后，
 * 已运行的 JVM 无法感知，导致新装命令在终端里"找不到"。</p>
 *
 * <p>本组件通过 PowerShell 读取注册表中的<b>实时</b>系统 PATH
 * （Machine 级 + User 级，.NET 会自动展开 {@code %SystemRoot%} 等占位符），
 * 按 Windows 规则合并（系统在前、用户在后，去重保序）后注入子进程环境。
 * 合并时保留继承环境中未出现在注册表里的条目（如 GPO 注入、启动脚本/conda
 * 临时追加的路径），避免破坏既有语义。读取操作需要 fork 进程，代价较高，
 * 因此带 TTL 缓存：成功结果缓存 30 秒，失败结果缓存 10 秒（哨兵值），
 * 用户修改环境变量后最多 30 秒自动生效。</p>
 *
 * <p>非 Windows 平台返回 {@code null}：Unix 侧 {@code bash -lc} 会读取 login shell
 * 配置文件，多数场景可自愈，无需额外处理。</p>
 *
 * @author noear
 * @since 4.0.5
 */
public final class EnvironmentResolver {

    private static final Logger LOG = LoggerFactory.getLogger(EnvironmentResolver.class);

    /** 注册表读取（fork 进程）成功结果的缓存 TTL */
    private static final long CACHE_TTL_MS = 30_000;
    /** 注册表读取失败结果的缓存 TTL（比成功缓存短，避免长时间屏蔽新安装的环境） */
    private static final long FAILED_TTL_MS = 10_000;
    /** 读取超时 */
    private static final long READ_TIMEOUT_MS = 2_000;
    /** 解析失败哨兵值（PATH 中不可能出现 NUL 字符） */
    private static final String FAILED_SENTINEL = "\u0000";

    private static volatile String cachedPath;
    private static volatile long cachedAt;

    private EnvironmentResolver() {
    }

    /**
     * 获取实时系统 PATH（仅 Windows）。
     *
     * <p>非 Windows 或解析失败时返回 {@code null}，调用方保持继承环境不变（安全降级）。
     * 失败结果同样进入缓存（10s 哨兵），避免系统无 PowerShell 时每次调用都 fork 进程。</p>
     */
    public static String resolvePath() {
        if (!isWindows()) {
            return null;
        }
        long now = System.currentTimeMillis();
        String cached = cachedPath;
        if (isFresh(cached, now)) {
            return cached == FAILED_SENTINEL ? null : cached;
        }
        synchronized (EnvironmentResolver.class) {
            cached = cachedPath;
            if (isFresh(cached, now)) {
                return cached == FAILED_SENTINEL ? null : cached;
            }
            cachedPath = resolveWindowsPath();
            cachedAt = System.currentTimeMillis();
            return cachedPath == FAILED_SENTINEL ? null : cachedPath;
        }
    }

    private static boolean isFresh(String cached, long now) {
        if (cached == null) {
            return false;
        }
        if (cached == FAILED_SENTINEL) {
            return now - cachedAt < FAILED_TTL_MS;
        }
        return now - cachedAt < CACHE_TTL_MS;
    }

    /**
     * 将实时 PATH 与显式 envs 合并到子进程环境。
     *
     * <p>实时 PATH（注册表 Machine+User 合并）优先置顶，继承环境中未出现在注册表里的
     * 条目追加在后，保证 GPO/启动脚本等来源的路径不丢失；显式 {@code envs} 中的键
     * 优先级最高（若调用方显式指定 PATH 则以调用方为准）。</p>
     *
     * <p>Windows 下额外注入 {@code PYTHONIOENCODING=utf-8}：Python 在 stdout 被重定向（管道）时
     * 默认按 ANSI 代码页（中文系统 GBK）输出，而 chcp 只影响控制台、不影响管道；不注入则 Java 侧
     * 按 UTF-8 解码必现乱码。此处不注入 {@code PYTHONUTF8}：它会同时把 {@code open()} 的默认编码
     * 改为 UTF-8，导致用户脚本读取本地 ANSI 编码文件时报 UnicodeDecodeError（超出修复输出乱码的范围）。</p>
     */
    public static void applyTo(ProcessBuilder pb, Map<String, String> envs) {
        if (isWindows()) {
            // 仅设默认值；显式 envs 会在下方 putAll 覆盖
            pb.environment().putIfAbsent("PYTHONIOENCODING", "utf-8");
        }
        String systemPath = resolvePath();
        if (systemPath != null) {
            // Windows 上 ProcessBuilder.environment() 返回大小写不敏感的 map，
            // put("PATH") 可正确覆盖继承来的 "Path"
            String inherited = pb.environment().get("PATH");
            pb.environment().put("PATH", mergeInheritedPath(systemPath, inherited));
        }
        if (envs != null && !envs.isEmpty()) {
            pb.environment().putAll(envs);
        }
    }

    /**
     * 合并 Machine/User 级 PATH（Windows 规则：系统在前、用户在后，按 ';' 拆分去重保序）。
     * Windows 路径大小写不敏感，去重时忽略大小写（保留首次出现的形式）。
     * 全部为空返回 {@code null}。
     */
    static String mergePathLists(String machinePath, String userPath) {
        Map<String, String> parts = new LinkedHashMap<>();
        addPathParts(parts, machinePath);
        addPathParts(parts, userPath);
        if (parts.isEmpty()) {
            return null;
        }
        return String.join(";", parts.values());
    }

    /**
     * 合并实时系统 PATH 与继承 PATH：实时 PATH 优先，继承 PATH 中未出现在实时 PATH
     * 里的条目追加在后（同样忽略大小写去重）。任一为 {@code null} 时返回另一方。
     */
    static String mergeInheritedPath(String systemPath, String inheritedPath) {
        if (systemPath == null) {
            return inheritedPath;
        }
        if (inheritedPath == null || inheritedPath.isEmpty()) {
            return systemPath;
        }
        Set<String> known = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        addPathParts(known, systemPath);
        StringBuilder sb = new StringBuilder(systemPath);
        for (String part : inheritedPath.split(";")) {
            String p = part.trim();
            if (!p.isEmpty() && known.add(p)) {
                sb.append(';').append(p);
            }
        }
        return sb.toString();
    }

    private static void addPathParts(Map<String, String> parts, String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        for (String part : path.split(";")) {
            String p = part.trim();
            if (!p.isEmpty()) {
                parts.putIfAbsent(p.toLowerCase(Locale.ROOT), p);
            }
        }
    }

    private static void addPathParts(Set<String> parts, String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        for (String part : path.split(";")) {
            String p = part.trim();
            if (!p.isEmpty()) {
                parts.add(p);
            }
        }
    }

    private static String resolveWindowsPath() {
        Process process = null;
        try {
            // .NET 读取注册表 REG_EXPAND_SZ 时会自动展开 %SystemRoot% 等占位符，免去手动展开逻辑；
            // 前置 [Console]::OutputEncoding 强制 PowerShell 5.x 以 UTF-8 输出（默认管道为 UTF-16，会乱码）；
            // Machine 与 User 之间用 ';' 占位拼接，由 mergePathLists 清理
            String script = "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8;"
                    + "[Environment]::GetEnvironmentVariable('Path','Machine')"
                    + " + ';' + [Environment]::GetEnvironmentVariable('Path','User')";
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive", "-Command", script);
            pb.redirectErrorStream(true);
            process = pb.start();

            // 用独立线程消费 stdout，避免同步读流阻塞导致 waitFor 超时失效：
            // 若 PowerShell 冷启动慢，同步 read 会无限期阻塞，READ_TIMEOUT_MS 将形同虚设。
            final Process proc = process;
            final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream in = proc.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        synchronized (buf) {
                            buf.write(buffer, 0, n);
                            if (buf.size() > 64 * 1024) {
                                proc.destroyForcibly();
                                break;
                            }
                        }
                    }
                } catch (Throwable ignore) {
                    // 进程被强杀或流关闭，读取线程静默退出
                }
            }, "env-path-reader");
            reader.setDaemon(true);
            reader.start();

            // 主线程按超时等待进程结束，超时即强杀（总耗时受 READ_TIMEOUT_MS 约束）
            if (!process.waitFor(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                LOG.debug("Resolve system PATH timed out");
                return FAILED_SENTINEL;
            }
            // 进程已退出，给读取线程留少量时间收尾（管道剩余数据）
            reader.join(200);

            String output;
            synchronized (buf) {
                output = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
            String machineAndUser = output.trim();
            if (machineAndUser.isEmpty()) {
                return FAILED_SENTINEL;
            }
            return mergePathLists(machineAndUser, null);
        } catch (Throwable e) {
            if (process != null) {
                process.destroyForcibly();
            }
            LOG.debug("Resolve system PATH failed: {}", e.getMessage());
            return FAILED_SENTINEL;
        }
    }

    /**
     * 是否 Windows 平台（模块内统一入口，避免多处重复定义）。
     */
    static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase().contains("win");
    }
}
