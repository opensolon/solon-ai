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

    public MountManager(String workDir) {
        this.workDir = workDir;
        this.workspaceSource = FileMountSource.of(workDir);

        this.agentCatalog = new DefaultAgentCatalog(this);
        this.skillCatalog = new DefaultSkillCatalog(this);
    }

    public AgentCatalog getAgentCatalog() {
        return agentCatalog;
    }

    public SkillCatalog getSkillCatalog() {
        return skillCatalog;
    }

    /** 工作区来源；工作区与普通本地挂载统一使用 FileMountSource。 */
    public FileMountSource getWorkspaceSource() {
        return workspaceSource;
    }

    public Set<String> getDisallowSkills() {
        return disallowSkills;
    }

    /**
     * 禁用技能（按 aliasPath）
     */
    public void disallowSkill(String aliasPath) {
        if (Assert.isEmpty(aliasPath) == false) {
            disallowSkills.add(aliasPath);
        }
    }

    /**
     * 允许技能（按 aliasPath）
     */
    public void allowSkill(String aliasPath) {
        if (Assert.isEmpty(aliasPath) == false) {
            disallowSkills.remove(aliasPath);
        }
    }

    /**
     * 批量设置禁用技能（按 aliasPath）
     */
    public void setDisallowSkills(Collection<String> aliasPaths) {
        disallowSkills.clear();
        if (aliasPaths != null) {
            disallowSkills.addAll(aliasPaths);
        }
    }

    /**
     * 技能是否被禁用（按 aliasPath）
     */
    public boolean isSkillDisallowed(String aliasPath) {
        return disallowSkills.contains(aliasPath);
    }

    public String getWorkDir() {
        return workDir;
    }

    /** 注册挂载来源。 */
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
                .source(mount.getSource())
                .build();

        sourceMountMap.put(key, normalized);

        skillCatalog.refreshByMount(key);
        agentCatalog.refreshByMount(key);

        LOG.debug("MountSource has been registered: {} -> {}", key, mount.getSource().getScheme());
        return normalized;
    }

    public synchronized Collection<Mount> getSourceMounts() {
        return getMounts();
    }

    /** 移除挂载。 */
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
     * 将逻辑路径解析为物理路径
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

    /** 统一解析工作区和挂载来源；非 bash 文件工具应优先使用此 API。 */
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

    private static boolean isWorkspacePath(String path) {
        return path.length() == 10 || path.charAt(10) == '/' || path.charAt(10) == '\\';
    }

    private static Path localWorkspacePath(Path workDir, String path) {
        if (path.startsWith("/") || path.startsWith("\\") || Paths.get(path).isAbsolute()) {
            throw new SecurityException("工作区不允许绝对路径: " + path);
        }
        FileMountSource source = FileMountSource.of(workDir);
        Path local = source.getLocalPath(source.normalize(path))
                .orElseThrow(() -> new SecurityException("工作区路径不可访问: " + path));
        return checkedLocalPath(source, local);
    }

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

    private static int firstSeparator(String path) {
        int slash = path.indexOf('/');
        int backslash = path.indexOf('\\');
        if (slash < 0) return backslash;
        if (backslash < 0) return slash;
        return Math.min(slash, backslash);
    }

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

    /** 获取单个挂载。 */
    public synchronized Mount getMount(String alias) {
        return sourceMountMap.get(normalizeAlias(alias));
    }

    public synchronized boolean hasMount(String alias) {
        String key = normalizeAlias(alias);
        return sourceMountMap.containsKey(key);
    }

    /** 按注册顺序获取挂载快照。 */
    public synchronized Collection<Mount> getMounts() {
        return Collections.unmodifiableList(new ArrayList<>(sourceMountMap.values()));
    }

    public synchronized Set<String> getMountKeySet() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(sourceMountMap.keySet()));
    }

    /**
     * 内部辅助方法：解析配置路径并支持 "~/" 和 "./" 语法
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