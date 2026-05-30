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
 * 资源池管理（主要是技能池）
 *
 * @author noear
 * @since 3.9.5
 */
public class PoolManager {
    private static final Logger LOG = LoggerFactory.getLogger(PoolManager.class);

    // 逻辑路径前缀 -> 池目录信息 (如 "@shared" -> PoolDir)
    private final Map<String, PoolDir> poolMap = new ConcurrentHashMap<>();

    // 逻辑全路径 -> 技能目录信息 (如 "@shared/video-creator" -> SkillDir)
    private volatile Map<String, SkillDir> skillMap = new ConcurrentHashMap<>();

    /**
     * 注册池（并扫描）
     */
    public synchronized PoolManager register(String alias, String path) {
       return register(new PoolDir(alias, path));
    }

    /**
     * 注册池（并扫描）
     */
    public synchronized PoolManager register(String alias, String path, Path realPath) {
        return register(new PoolDir(alias, path, realPath));
    }

    /**
     * 注册池（并扫描）
     */
    public synchronized PoolManager register(PoolDir poolDir) {
        String key = poolDir.getAlias();
        Path realPath = poolDir.getRealPath();

        if (Files.exists(realPath) && Files.isDirectory(realPath)) {
            poolMap.put(key, poolDir);
            scanSkillAndCache(poolDir, skillMap);
            LOG.debug("Skill pool has been loaded.: {} -> {}", key, realPath);
        } else {
            String reason = !Files.exists(realPath) ? "The path does not exist." : "Not an effective directory";
            LOG.debug("Skill pool loading skip：{} (alias: {}, path: {})", reason, key, poolDir.getPath());
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
            skillMap.entrySet().removeIf(e ->
                    e.getKey().startsWith(key + "/") || e.getKey().equals(key));
            LOG.debug("Skill pool has been removed.: {}", key);
        }
        return removed;
    }

    /**
     * 刷新池（重新扫描所有池）
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

    public Set<String> getPoolKeySet(){
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

    public Map<String, SkillDir> getSkillMap() {
        return skillMap;
    }

    public SkillDir getSkill(String aliasPath) {
        return skillMap.get(aliasPath);
    }



    private static void scanSkillAndCache(PoolDir poolDir, Map<String, SkillDir> map) {
        try {
            Files.walkFileTree(poolDir.getRealPath(), EnumSet.noneOf(FileVisitOption.class), 3, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (isSkillDir(dir)) {
                        String name = poolDir.getRealPath().relativize(dir).toString().replace("\\", "/");
                        String aliasPath = poolDir.getAlias() + (name.isEmpty() ? "" : "/" + name);
                        map.put(aliasPath, new SkillDir(name, aliasPath, dir, parseDescription(dir)));
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
