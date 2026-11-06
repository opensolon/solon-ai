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
package org.noear.solon.ai.mount;

import org.noear.solon.ai.mount.source.FileMountSource;
import org.noear.solon.ai.mount.source.MountSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 挂载管理器：维护挂载表（别名 → 来源）并把逻辑路径解析为来源内路径。
 *
 * <p>4.2.0 起只做 IO 层的事：不再创建或持有技能/子代理目录，也不再管理技能禁用列表；
 * 上层目录经 {@link #addListener(MountListener)} 订阅挂载变更（见 {@code DefaultSkillCatalog}）。</p>
 *
 * @author noear
 * @since 3.9.5
 */
public class MountManager {
    private static final Logger LOG = LoggerFactory.getLogger(MountManager.class);
    private static final String WORKSPACE_ALIAS = "@workspace";

    private final String workDir; //对应 `./`
    private final MountSource workspaceSource;

    private final Map<String, Mount> sourceMountMap = new LinkedHashMap<>();
    private final List<MountListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 创建挂载管理器。
     *
     * @param workDir 工作区目录
     */
    public MountManager(String workDir) {
        this(workDir, null);
    }

    /**
     * 创建挂载管理器并指定工作区来源。
     *
     * <p>为 null 时按 workDir 创建本地来源（等同 {@link #MountManager(String)}）。</p>
     *
     * <p><b>警告：远程工作区尚未打通，目前只应传入指向 workDir 的本地来源。</b>当前版本中：</p>
     * <ul>
     *   <li>模型文件工具（read/write/edit/ls/grep/glob）对相对路径始终走 workDir 本地文件，
     *       不经过本来源；{@link #resolve(String)} 也只接受本地来源；</li>
     *   <li>bash、LSP、构建校验同样只在 workDir 上运行；</li>
     *   <li>只有 harness 写入工作区的产物（子任务超长结果、生成的子代理定义）和工作区命令加载经本来源读写。</li>
     * </ul>
     * <p>因此传入非本地来源会造成读写分裂：例如子任务结果写到了远端，模型却在本地读不到。
     * 在工作区同步机制（计算侧拉取 / 回写本地副本）落地之前，宿主必须自行保证 workDir 与该来源内容一致。</p>
     *
     * @param workDir         工作区目录（计算侧本地路径）
     * @param workspaceSource 工作区来源；可为 null
     * @since 4.2.0
     */
    public MountManager(String workDir, MountSource workspaceSource) {
        this.workDir = workDir;
        this.workspaceSource = workspaceSource != null ? workspaceSource : FileMountSource.of(workDir);
    }

    /**
     * 订阅挂载变更（重复添加同一实例只生效一次）。
     *
     * @param listener 监听器
     * @since 4.2.0
     */
    public void addListener(MountListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * 取消订阅挂载变更。
     *
     * @param listener 监听器
     * @since 4.2.0
     */
    public void removeListener(MountListener listener) {
        listeners.remove(listener);
    }

    /** 工作区来源（默认是 workDir 上的 FileMountSource）。 */
    public MountSource getWorkspaceSource() {
        return workspaceSource;
    }

    /** 获取工作区目录。 */
    public String getWorkDir() {
        return workDir;
    }

    /**
     * 注册挂载并通知监听器；同别名挂载会被替换。
     *
     * @param mount 待注册挂载
     * @return 别名规范化后的挂载
     */
    public Mount register(Mount mount) {
        if (mount == null || mount.getSource() == null) {
            throw new IllegalArgumentException("mount/source must not be null");
        }

        String key = normalizeAlias(mount.getAlias());
        final Mount normalized = Mount.builder()
                .alias(key)
                .description(mount.getDescription())
                .type(mount.getType())
                .primary(mount.isPrimary())
                .enabled(mount.isEnabled())
                .writable(mount.isWritable())
                .visible(mount.isVisible())
                .source(mount.getSource())
                .build();

        synchronized (this) {
            sourceMountMap.put(key, normalized);
        }
        LOG.debug("MountSource has been registered: {} -> {}", key, mount.getSource().getScheme());

        for (MountListener listener : listeners) {
            try {
                listener.onMountRegistered(normalized);
            } catch (Throwable e) {
                LOG.warn("Mount listener failed on register: {}", key, e);
            }
        }
        return normalized;
    }

    /**
     * 按别名移除挂载，并通知监听器。
     *
     * @param alias 挂载别名
     * @return 被移除的挂载；不存在时返回 null
     */
    public Mount remove(String alias) {
        String key = normalizeAlias(alias);
        Mount removed;
        synchronized (this) {
            removed = sourceMountMap.remove(key);
        }

        if (removed != null) {
            LOG.debug("Mount has been removed.: {}", key);
            for (MountListener listener : listeners) {
                try {
                    listener.onMountRemoved(removed);
                } catch (Throwable e) {
                    LOG.warn("Mount listener failed on remove: {}", key, e);
                }
            }
        }
        return removed;
    }

    /**
     * 将工作区或本地挂载的逻辑路径解析为受挂载范围约束的物理路径。
     *
     * @param workDir 工作区路径
     * @param pStr 逻辑路径
     * @return 本地物理路径
     */
    public Path resolve(Path workDir, String pStr) {
        if (pStr == null || pStr.isEmpty() || ".".equals(pStr)) return workDir;

        if (pStr.startsWith(WORKSPACE_ALIAS) && isWorkspacePath(pStr)) {
            return localWorkspacePath(workDir, pStr.length() == 10 ? "" : pStr.substring(11));
        }
        if (pStr.startsWith("@")) {
            int slash = firstSeparator(pStr);
            String alias = slash > 0 ? pStr.substring(0, slash) : pStr;
            Mount mount = sourceMountMap.get(alias);
            if (mount == null) {
                throw new SecurityException("权限拒绝：未知的挂载点 " + alias);
            }
            if (!mount.isEnabled()) {
                throw new SecurityException("权限拒绝：挂载点已禁用 " + alias);
            }
            String sub = slash < 0 ? "" : pStr.substring(slash + 1);
            if (!(mount.getSource() instanceof FileMountSource)) {
                throw new SecurityException("挂载点不支持本地路径: " + alias);
            }
            FileMountSource source = (FileMountSource) mount.getSource();
            String normalized = source.normalize(sub);
            Path local = source.getLocalPath(normalized)
                    .orElseThrow(() -> new SecurityException("挂载点不支持本地路径: " + alias));
            return checkedLocalPath(source, local);
        }

        String cleanPath = pStr.startsWith("./") ? pStr.substring(2) : pStr;
        return localWorkspacePath(workDir, cleanPath);
    }

    /**
     * 统一解析工作区和挂载来源；非 bash 文件工具应优先使用此 API。
     *
     * @param path 逻辑路径
     * @return 对应来源及来源内路径
     */
    public ResolvedResource resolveResource(String path) {
        if (path == null || path.isEmpty() || ".".equals(path)) {
            return new ResolvedResource(path == null ? "" : path, WORKSPACE_ALIAS, null,
                    workspaceSource, "");
        }
        if (path.startsWith(WORKSPACE_ALIAS) && isWorkspacePath(path)) {
            String sourcePath = path.length() == 10 ? "" : path.substring(11);
            return new ResolvedResource(path, WORKSPACE_ALIAS, null, workspaceSource,
                    workspaceSource.normalize(sourcePath));
        }
        if (path.startsWith("@")) {
            int slash = firstSeparator(path);
            String alias = slash < 0 ? path : path.substring(0, slash);
            Mount mount = sourceMountMap.get(alias);

            if (mount == null) {
                throw new SecurityException("权限拒绝：未知的挂载点 " + alias);
            }

            if (!mount.isEnabled()) {
                throw new SecurityException("权限拒绝：挂载点已禁用 " + alias);
            }

            String sourcePath = slash < 0 ? "" : path.substring(slash + 1);
            return new ResolvedResource(path, alias, mount, mount.getSource(),
                    mount.getSource().normalize(sourcePath));
        }
        String sourcePath = path.startsWith("./") ? path.substring(2) : path;
        sourcePath = workspaceSource.normalize(sourcePath);
        return new ResolvedResource(path, WORKSPACE_ALIAS, null, workspaceSource, sourcePath);
    }

    /** 判断路径是否指向工作区别名或其子路径。 */
    private static boolean isWorkspacePath(String path) {
        return path.length() == 10 || path.charAt(10) == '/' || path.charAt(10) == '\\';
    }

    /** 解析工作区内的本地路径并检查其边界。 */
    private static Path localWorkspacePath(Path workDir, String path) {
        if (path.startsWith("/") || path.startsWith("\\") || Paths.get(path).isAbsolute()) {
            throw new SecurityException("工作区不允许绝对路径: " + path);
        }
        FileMountSource source = FileMountSource.of(workDir);
        Path local = source.getLocalPath(source.normalize(path))
                .orElseThrow(() -> new SecurityException("工作区路径不可访问: " + path));
        return checkedLocalPath(source, local);
    }

    /** 检查本地路径及已有路径的符号链接是否位于来源范围内。 */
    private static Path checkedLocalPath(FileMountSource source, Path local) {
        Path root = source.getRootPath();
        if (!local.startsWith(root)) throw new SecurityException("路径超出挂载范围: " + local);
        if (source.isFollowExternalLinks()) {
            // 来源显式声明跟随外部链接（如用户自有的技能目录经 Junction 放到其他盘）：
            // 语法级越界已在 source.normalize / resolveChecked 中拒绝，此处不再按真实路径二次拦截，
            // 否则会出现"发现阶段可列出、显式访问却拒绝"的不一致。
            return local;
        }
        try {
            if (Files.exists(root)) {
                Path realRoot = root.toRealPath();
                Path existing = local;
                while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                    existing = existing.getParent();
                }
                if (existing != null && !existing.toRealPath().startsWith(realRoot)) {
                    throw new SecurityException("符号链接超出挂载范围: " + local);
                }
            }
        } catch (IOException e) {
            throw new SecurityException("本地路径不可访问: " + local, e);
        }
        return local;
    }

    /** 获取路径中首个正斜杠或反斜杠的位置。 */
    private static int firstSeparator(String path) {
        int slash = path.indexOf('/');
        int backslash = path.indexOf('\\');
        if (slash < 0) return backslash;
        if (backslash < 0) return slash;
        return Math.min(slash, backslash);
    }

    /** 校验挂载别名并补齐起始的 {@code @}。 */
    private static String normalizeAlias(String alias) {
        if (alias == null) throw new IllegalArgumentException("alias must not be null");
        String value = alias.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("alias must not be empty");
        if (!value.startsWith("@")) value = "@" + value;
        if ("@".equals(value) || WORKSPACE_ALIAS.equals(value) || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                || value.matches(".*\\s+.*")) {
            throw new IllegalArgumentException("Invalid mount alias: " + alias);
        }
        return value;
    }

    /**
     * 按别名获取挂载。
     *
     * @param alias 挂载别名
     * @return 对应挂载；不存在时返回 null
     */
    public synchronized Mount getMount(String alias) {
        return sourceMountMap.get(normalizeAlias(alias));
    }

    /** 判断指定别名的挂载是否存在。 */
    public synchronized boolean hasMount(String alias) {
        String key = normalizeAlias(alias);
        return sourceMountMap.containsKey(key);
    }

    /** 按注册顺序获取不可修改的挂载快照。 */
    public synchronized Collection<Mount> getMounts() {
        return Collections.unmodifiableList(new ArrayList<>(sourceMountMap.values()));
    }

    /** 按注册顺序获取不可修改的挂载别名快照。 */
    public synchronized Set<String> getMountKeySet() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(sourceMountMap.keySet()));
    }
}
