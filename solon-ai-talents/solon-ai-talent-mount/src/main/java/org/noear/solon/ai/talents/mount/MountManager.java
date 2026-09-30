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
package org.noear.solon.ai.talents.mount;

import org.noear.solon.ai.talents.mount.catalog.AgentCatalog;
import org.noear.solon.ai.talents.mount.catalog.DefaultAgentCatalog;
import org.noear.solon.ai.talents.mount.catalog.DefaultSkillCatalog;
import org.noear.solon.ai.talents.mount.catalog.SkillCatalog;
import org.noear.solon.ai.talents.mount.source.FileMountSource;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 挂载管理器
 *
 * @author noear
 * @since 3.9.5
 */
public class MountManager {
    private static final Logger LOG = LoggerFactory.getLogger(MountManager.class);
    private static final String WORKSPACE_ALIAS = "@workspace";

    private static final String USER_HOME = System.getProperty("user.home"); //对应 `～/`
    private final String workDir; //对应 `./`
    private final FileMountSource workspaceSource;

    private final Map<String, Mount> sourceMountMap = new LinkedHashMap<>();
    private final Set<String> disallowSkills = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private final AgentCatalog agentCatalog;
    private final SkillCatalog skillCatalog;

    /**
     * 创建挂载管理器。
     *
     * @param workDir 工作区目录
     */
    public MountManager(String workDir) {
        this.workDir = workDir;
        this.workspaceSource = FileMountSource.of(workDir);

        this.agentCatalog = new DefaultAgentCatalog(this);
        this.skillCatalog = new DefaultSkillCatalog(this);
    }

    /** 获取子代理目录。 */
    public AgentCatalog getAgentCatalog() {
        return agentCatalog;
    }

    /** 获取技能目录。 */
    public SkillCatalog getSkillCatalog() {
        return skillCatalog;
    }

    /** 工作区来源；工作区与普通本地挂载统一使用 FileMountSource。 */
    public FileMountSource getWorkspaceSource() {
        return workspaceSource;
    }

    /** 获取禁用技能的别名路径集合。 */
    public Set<String> getDisallowSkills() {
        return disallowSkills;
    }

    /**
     * 按别名路径禁用技能；空路径不作处理。
     *
     * @param aliasPath 技能别名路径
     */
    public void disallowSkill(String aliasPath) {
        if (Assert.isEmpty(aliasPath) == false) {
            disallowSkills.add(aliasPath);
        }
    }

    /**
     * 按别名路径允许技能；空路径不作处理。
     *
     * @param aliasPath 技能别名路径
     */
    public void allowSkill(String aliasPath) {
        if (Assert.isEmpty(aliasPath) == false) {
            disallowSkills.remove(aliasPath);
        }
    }

    /**
     * 替换禁用技能的别名路径集合；传入 null 时清空。
     *
     * @param aliasPaths 技能别名路径集合
     */
    public void setDisallowSkills(Collection<String> aliasPaths) {
        disallowSkills.clear();
        if (aliasPaths != null) {
            disallowSkills.addAll(aliasPaths);
        }
    }

    /**
     * 判断技能是否被禁用。
     *
     * @param aliasPath 技能别名路径
     * @return 是否被禁用
     */
    public boolean isSkillDisallowed(String aliasPath) {
        return disallowSkills.contains(aliasPath);
    }

    /** 获取工作区目录。 */
    public String getWorkDir() {
        return workDir;
    }

    /**
     * 注册挂载并刷新对应目录；同别名挂载会被替换。
     *
     * @param mount 待注册挂载
     * @return 别名规范化后的挂载
     */
    public synchronized Mount register(Mount mount) {
        if (mount == null || mount.getSource() == null) {
            throw new IllegalArgumentException("mount/source must not be null");
        }

        String key = normalizeAlias(mount.getAlias());
        Mount normalized = Mount.builder()
                .alias(key)
                .description(mount.getDescription())
                .type(mount.getType())
                .primary(mount.isPrimary())
                .enabled(mount.isEnabled())
                .writeable(mount.isWriteable())
                .visible(mount.isVisible())
                .source(mount.getSource())
                .build();

        sourceMountMap.put(key, normalized);

        skillCatalog.refreshByMount(key);
        agentCatalog.refreshByMount(key);

        LOG.debug("MountSource has been registered: {} -> {}", key, mount.getSource().getScheme());
        return normalized;
    }

    /** 获取按注册顺序排列的挂载快照。 */
    public synchronized Collection<Mount> getSourceMounts() {
        return getMounts();
    }

    /**
     * 按别名移除挂载，并刷新对应目录。
     *
     * @param alias 挂载别名
     * @return 被移除的挂载；不存在时返回 null
     */
    public synchronized Mount remove(String alias) {
        String key = normalizeAlias(alias);
        Mount removed = sourceMountMap.remove(key);

        if (removed != null) {
            skillCatalog.refreshByMount(key);
            agentCatalog.refreshByMount(key);

            LOG.debug("Mount has been removed.: {}", key);
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

    /**
     * 解析配置路径，支持 {@code ~/}、{@code ./} 及其反斜杠形式。
     *
     * @param rawPath 配置路径；为空时使用工作区目录
     * @return 规范化的绝对路径
     */
    public Path parseRealPath(String rawPath) {
        if (Assert.isEmpty(rawPath)) {
            return Paths.get(workDir);
        }

        String processedPath = rawPath;
        // 1. 处理 Unix 式或 Windows 兼容的家目录路径 (例如 ~/skills 或 ~)
        if (rawPath.startsWith("~" + File.separator) || rawPath.equals("~")) {
            processedPath = rawPath.replaceFirst("^~", USER_HOME);
        } else if (rawPath.startsWith("~/") || rawPath.startsWith("~\\")) {
            processedPath = USER_HOME + rawPath.substring(1);
        }
        // 2. 处理工作区相对路径 (例如 ./my-work)
        else if (rawPath.startsWith("." + File.separator) || rawPath.equals(".")) {
            processedPath = rawPath.replaceFirst("^\\.", workDir);
        } else if (rawPath.startsWith("./") || rawPath.startsWith(".\\")) {
            processedPath = workDir + rawPath.substring(1);
        }

        return Paths.get(processedPath).toAbsolutePath().normalize();
    }
}