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
package org.noear.solon.ai.skills.cli;

import org.noear.solon.ai.util.Markdown;
import org.noear.solon.ai.util.MarkdownUtil;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 挂载池管理
 *
 * @author noear
 * @since 3.9.5
 */
public class PoolManager {
    private static final Logger LOG = LoggerFactory.getLogger(PoolManager.class);

    // 逻辑路径前缀 -> 池目录信息 (如 "@shared" -> PoolDir)
    private final Map<String, PoolDir> poolMap = new ConcurrentHashMap<>();

    // 逻辑全路径 -> 技能目录信息 (如 "video-creator" -> SkillDir)
    private volatile Map<String, SkillDir> skillMap = new ConcurrentHashMap<>();

    /**
     * 注册池（并扫描）
     */
    public synchronized PoolManager register(String alias, PoolType type, String path) {
        return register(new PoolDir(alias, type, false, path));
    }

    /**
     * 注册池（并扫描）
     */
    public synchronized PoolManager register(String alias, PoolType type, String path, Path realPath) {
        return register(new PoolDir(alias, type, false, path, realPath));
    }

    /**
     * 注册池（并扫描）
     */
    public synchronized PoolManager register(PoolDir poolDir) {
        String key = poolDir.getAlias();
        Path realPath = poolDir.getRealPath();
        poolMap.put(key, poolDir);

        if (poolDir.isEnabled() && poolDir.getType() == PoolType.SKILLS) {
            if (Files.exists(realPath) && Files.isDirectory(realPath)) {
                scanSkillAndCache(poolDir, skillMap);
                LOG.debug("Mount pool has been loaded.: {} -> {}", key, realPath);
            } else {
                String reason = !Files.exists(realPath) ? "The path does not exist." : "Not an effective directory";
                LOG.debug("Mount pool loading skip：{} (alias: {}, path: {})", reason, key, poolDir.getPath());
            }
        }

        return this;
    }

    /**
     * 移除池（及其关联技能）
     */
    public synchronized PoolDir remove(String alias) {
        String key = alias.startsWith("@") ? alias : "@" + alias;
        PoolDir removed = poolMap.remove(key);
        if (removed != null) {
            skillMap.entrySet().removeIf(e -> key.equals(e.getValue().getPoolAlias()));
            LOG.debug("Mount pool has been removed.: {}", key);
        }
        return removed;
    }

    /**
     * 刷新指定挂载池（增量更新）
     */
    public synchronized void refresh(String alias) {
        if (Assert.isEmpty(alias)) {
            refresh();
        } else {
            String key = alias.startsWith("@") ? alias : "@" + alias;
            PoolDir poolDir = poolMap.get(key);

            if (poolDir == null || poolDir.getType() != PoolType.SKILLS){
                return;
            }

            // 1. 扫描该池
            Map<String, SkillDir> tmp = new LinkedHashMap<>();
            scanSkillAndCache(poolDir, tmp);

            // 2. 移除该池下的旧技能
            skillMap.entrySet().removeIf(e -> key.equals(e.getValue().getPoolAlias()));
            skillMap.putAll(tmp);
        }
    }

    /**
     * 刷新所有挂载池（重新扫描）
     */
    public synchronized void refresh() {
        Map<String, SkillDir> tmp = new ConcurrentHashMap<>();
        for (Map.Entry<String, PoolDir> entry : poolMap.entrySet()) {
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
            for (Map.Entry<String, PoolDir> e : poolMap.entrySet()) {
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
     * 获取单个池
     */
    public PoolDir getPool(String alias) {
        String key = alias.startsWith("@") ? alias : "@" + alias;
        return poolMap.get(key);
    }

    public boolean hasPool(String alias) {
        String key = alias.startsWith("@") ? alias : "@" + alias;
        return poolMap.containsKey(key);
    }

    /**
     * 获取所有池
     */
    public Collection<PoolDir> getPools() {
        return Collections.unmodifiableCollection(poolMap.values());
    }

    public Set<String> getPoolKeySet() {
        return poolMap.keySet();
    }

    /**
     * 获取某池下的所有技能
     */
    public List<SkillDir> getSkillsByPool(String alias) {
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


    private static void scanSkillAndCache(PoolDir poolDir, Map<String, SkillDir> map) {
        if (poolDir.isEnabled() == false || poolDir.getType() != PoolType.SKILLS) {
            return;
        }

        try {
            Files.walkFileTree(poolDir.getRealPath(), EnumSet.noneOf(FileVisitOption.class), 3, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (isSkillDir(dir)) {
                        String name = poolDir.getRealPath().relativize(dir).toString().replace("\\", "/");
                        String aliasPath = poolDir.getAlias() + (name.isEmpty() ? "" : "/" + name);

                        map.put(name, new SkillDir(name, poolDir.getAlias(), aliasPath, dir, parseDescription(dir)));
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (dir.getFileName().toString().startsWith(".")) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.error("Scan skill pool failed: {}", poolDir.getRealPath(), e);
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
}