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

import org.noear.solon.ai.util.Markdown;
import org.noear.solon.ai.util.MarkdownUtil;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 挂载管理器
 *
 * @author noear
 * @since 3.9.5
 */
public class MountManager {
    private static final Logger LOG = LoggerFactory.getLogger(MountManager.class);

    private static final String USER_HOME = System.getProperty("user.home"); //对应 `～/`
    private final String workDir; //对应 `./`

    // 逻辑路径前缀 -> 挂载目录信息 (如 "@shared" -> MountDir)
    private final Map<String, MountDir> mountMap = new ConcurrentHashMap<>();

    // 逻辑全路径 -> 技能目录信息 (如 "video-creator" -> SkillDir)
    private volatile Map<String, SkillDir> skillMap = new ConcurrentHashMap<>();

    public MountManager(String workDir){
        this.workDir = workDir;
    }

    public String getWorkDir() {
        return workDir;
    }

    /**
     * 注册挂载（并扫描）
     */
    public synchronized MountDir register(String alias, MountType type, String path) {
        return register(new MountDir(alias, type,  path, false));
    }

    public synchronized MountDir register(String alias, MountType type, String path, boolean primary) {
        return register(new MountDir(alias, type,  path, primary));
    }

    /**
     * 注册挂载（并扫描）
     */
    public synchronized MountDir register(MountDir mountDir) {
        String key = mountDir.getAlias();
        Path realPath = parseRealPath(mountDir.getPath()).toAbsolutePath().normalize();
        mountDir.setRealPath(realPath);

        mountMap.put(key, mountDir);

        if (mountDir.isEnabled() && mountDir.getType() == MountType.SKILLS) {
            if (Files.exists(realPath) && Files.isDirectory(realPath)) {
                scanSkillAndCache(mountDir, skillMap);
                LOG.debug("Mount has been loaded.: {} -> {}", key, realPath);
            } else {
                String reason = !Files.exists(realPath) ? "The path does not exist." : "Not an effective directory";
                LOG.debug("Mount loading skip：{} (alias: {}, path: {})", reason, key, mountDir.getPath());
            }
        }

        return mountDir;
    }

    /**
     * 移除挂载（及其关联技能）
     */
    public synchronized MountDir remove(String alias) {
        String key = alias.startsWith("@") ? alias : "@" + alias;
        MountDir removed = mountMap.remove(key);
        if (removed != null) {
            skillMap.entrySet().removeIf(e -> key.equals(e.getValue().getMountAlias()));
            LOG.debug("Mount has been removed.: {}", key);
        }
        return removed;
    }

    /**
     * 刷新指定挂载（增量更新）
     */
    public synchronized void refresh(String alias) {
        if (Assert.isEmpty(alias)) {
            refresh();
        } else {
            String key = alias.startsWith("@") ? alias : "@" + alias;
            MountDir mountDir = mountMap.get(key);

            if (mountDir == null || mountDir.getType() != MountType.SKILLS){
                return;
            }

            // 1. 扫描该挂载
            Map<String, SkillDir> tmp = new LinkedHashMap<>();
            scanSkillAndCache(mountDir, tmp);

            // 2. 移除该挂载下的旧技能
            skillMap.entrySet().removeIf(e -> key.equals(e.getValue().getMountAlias()));
            skillMap.putAll(tmp);
        }
    }

    /**
     * 刷新所有挂载（重新扫描）
     */
    public synchronized void refresh() {
        Map<String, SkillDir> tmp = new ConcurrentHashMap<>();
        for (Map.Entry<String, MountDir> entry : mountMap.entrySet()) {
            scanSkillAndCache(entry.getValue(), tmp);
        }

        skillMap = tmp;
    }

    /**
     * 将逻辑路径解析为物理路径
     */
    public Path resolve(Path workDir, String pStr) {
        if (pStr == null || pStr.isEmpty() || ".".equals(pStr)) return workDir;

        if (pStr.startsWith("@")) {
            for (Map.Entry<String, MountDir> e : mountMap.entrySet()) {
                if (pStr.startsWith(e.getKey())) {
                    String sub = pStr.substring(e.getKey().length()).replaceFirst("^[/\\\\]", "");
                    return e.getValue().getRealPath().resolve(sub).normalize();
                }
            }
        }

        String cleanPath = pStr.startsWith("./") ? pStr.substring(2) : pStr;
        return workDir.resolve(cleanPath).normalize();
    }

    /**
     * 获取单个挂载
     */
    public MountDir getMount(String alias) {
        String key = alias.startsWith("@") ? alias : "@" + alias;
        return mountMap.get(key);
    }

    public boolean hasMount(String alias) {
        String key = alias.startsWith("@") ? alias : "@" + alias;
        return mountMap.containsKey(key);
    }

    /**
     * 获取所有挂载
     */
    public Collection<MountDir> getMounts() {
        return Collections.unmodifiableCollection(mountMap.values());
    }

    public Set<String> getMountKeySet() {
        return mountMap.keySet();
    }

    /**
     * 获取某挂载下的所有技能
     */
    public List<SkillDir> getSkillsByMount(String alias) {
        String key = alias.startsWith("@") ? alias : "@" + alias;
        return skillMap.values().stream()
                .filter(s -> s.getAliasPath().startsWith(key + "/") || s.getAliasPath().equals(key))
                .collect(Collectors.toList());
    }

    public Collection<SkillDir> getSkills() {
        return skillMap.values();
    }

    public int getSkillCount() {
        return skillMap.size();
    }

    public SkillDir getSkill(String name) {
        return skillMap.get(name);
    }


    private static void scanSkillAndCache(MountDir mountDir, Map<String, SkillDir> map) {
        if (mountDir.isEnabled() == false || mountDir.getType() != MountType.SKILLS) {
            return;
        }

        try {
            Files.walkFileTree(mountDir.getRealPath(), EnumSet.noneOf(FileVisitOption.class), 3, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (isSkillDir(dir)) {
                        String name = mountDir.getRealPath().relativize(dir).toString().replace("\\", "/");
                        String aliasPath = mountDir.getAlias() + (name.isEmpty() ? "" : "/" + name);

                        map.put(name, new SkillDir(name, mountDir.getAlias(), aliasPath, dir, parseDescription(dir)));
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (dir.getFileName().toString().startsWith(".")) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.error("Scan skill mount failed: {}", mountDir.getRealPath(), e);
        }
    }


    private static boolean isSkillDir(Path p) {
        return Files.exists(p.resolve("SKILL.md")) || Files.exists(p.resolve("skill.md"));
    }

    private static String parseDescription(Path dir) {
        Path md = dir.resolve("SKILL.md");
        if (!Files.exists(md)) {
            md = dir.resolve("skill.md");
        }

        try {
            List<String> lines = Files.readAllLines(md, StandardCharsets.UTF_8);
            Markdown markdown = MarkdownUtil.resolve(lines, true);

            String desc = markdown.getDescription();

            if (Assert.isEmpty(desc)) {
                return "技能规约。";
            } else {
                // 增加长度到 150，确保 LLM 能看到足够的语义信息
                return desc.length() > 150 ? desc.substring(0, 147) + "..." : desc;
            }
        } catch (Throwable e) {
            return "技能规约。";
        }
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