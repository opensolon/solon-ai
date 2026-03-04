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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 资源池管理（主要是技能池）
 *
 * @author noear
 * @since 3.9.5
 */
public class PoolManager {
    private static final Logger LOG = LoggerFactory.getLogger(PoolManager.class);

    // 逻辑路径前缀 -> 物理根路径 (如 "@shared" -> "/path/to/shared")
    private final Map<String, Path> poolMap = new ConcurrentHashMap<>();

    // 逻辑全路径 -> 技能目录信息 (如 "@shared/video-creator" -> SkillDir)
    private final Map<String, SkillDir> skillMap = new ConcurrentHashMap<>();

    /**
     * 注册池（并扫描）
     */
    public PoolManager register(String alias, Path dir) {
        String key = alias.startsWith("@") ? alias : "@" + alias;
        Path rootPath = dir.toAbsolutePath().normalize();
        poolMap.put(key, rootPath);
        scanAndCache(key, rootPath);
        return this;
    }

    /**
     * 注册池（并扫描）
     */
    public PoolManager register(String alias, String dir) {
        return register(alias, Paths.get(dir));
    }

    /**
     * 刷新池（重新扫描所有池）
     */
    public void refresh() {
        skillMap.clear();
        poolMap.forEach(this::scanAndCache);
    }

    /**
     * 将逻辑路径解析为物理路径
     */
    public Path resolve(Path workDir, String pStr) {
        if (pStr == null || pStr.isEmpty() || ".".equals(pStr)) return workDir;

        if (pStr.startsWith("@")) {
            for (Map.Entry<String, Path> e : poolMap.entrySet()) {
                if (pStr.startsWith(e.getKey())) {
                    String sub = pStr.substring(e.getKey().length()).replaceFirst("^[/\\\\]", "");
                    return e.getValue().resolve(sub).normalize();
                }
            }
        }

        String cleanPath = pStr.startsWith("./") ? pStr.substring(2) : pStr;
        return workDir.resolve(cleanPath).normalize();
    }

    public Map<String, SkillDir> getSkillMap() {
        return skillMap;
    }

    public Map<String, Path> getPoolMap() {
        return poolMap;
    }

    public static class SkillDir {
        public final String aliasPath;
        public final Path realPath;
        public final String description;

        SkillDir(String aliasPath, Path realPath, String description) {
            this.aliasPath = aliasPath;
            this.realPath = realPath;
            this.description = description;
        }

        public String getAliasPath() {
            return aliasPath;
        }

        public Path getRealPath() {
            return realPath;
        }

        public String getDescription() {
            return description;
        }
    }

    private boolean isSkillDir(Path p) {
        return Files.exists(p.resolve("SKILL.md")) || Files.exists(p.resolve("skill.md"));
    }


    private void scanAndCache(String alias, Path root) {
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 3, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (isSkillDir(dir)) {
                        String rel = root.relativize(dir).toString().replace("\\", "/");
                        String aliasPath = alias + (rel.isEmpty() ? "" : "/" + rel);
                        skillMap.put(aliasPath, new SkillDir(aliasPath, dir, parseDescription(dir)));
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (dir.getFileName().toString().startsWith(".")) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.error("Scan skill pool failed: {}", root, e);
        }
    }

    private String parseDescription(Path dir) {
        Path md = dir.resolve("SKILL.md");
        if (!Files.exists(md)) md = dir.resolve("skill.md");

        try (Stream<String> lines = Files.lines(md, StandardCharsets.UTF_8)) {
            List<String> allLines = lines.map(String::trim).collect(Collectors.toList());

            if (allLines.isEmpty()) return "专家技能规约。";

            // 检查是否包含 YAML Front Matter (以 --- 开头)
            if (allLines.get(0).equals("---")) {
                for (int i = 1; i < allLines.size(); i++) {
                    String line = allLines.get(i);
                    if (line.equals("---")) break; // YAML 结束

                    // 寻找 description 字段
                    if (line.toLowerCase().startsWith("description:")) {
                        String desc = line.substring(line.indexOf(":") + 1).trim();
                        // 去掉两端的引号
                        if (desc.startsWith("\"") && desc.endsWith("\"")) {
                            desc = desc.substring(1, desc.length() - 1);
                        }
                        return formatDesc(desc);
                    }
                }
            }

            // 策略 2：如果没有 YAML 或没找到 description，寻找第一个一级标题 (#)
            // 然后取其后的第一个非空、非引用、非代码块的段落
            boolean foundMainTitle = false;
            for (String line : allLines) {
                if (line.startsWith("# ")) {
                    foundMainTitle = true;
                    continue;
                }
                if (foundMainTitle && !line.isEmpty() && !line.startsWith("#")
                        && !line.startsWith("`") && !line.startsWith(">")) {
                    return formatDesc(line);
                }
            }

            // 策略 3：兜底逻辑
            return allLines.stream()
                    .filter(l -> !l.isEmpty() && !l.startsWith("-") && !l.startsWith("#"))
                    .findFirst()
                    .map(this::formatDesc)
                    .orElse("专业领域专家规约。");

        } catch (Exception e) {
            return "专家技能规约。";
        }
    }

    private String formatDesc(String desc) {
        // 增加长度到 150，确保 LLM 能看到足够的语义信息
        return desc.length() > 150 ? desc.substring(0, 147) + "..." : desc;
    }
}