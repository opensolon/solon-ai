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
package org.noear.solon.ai.harness.command;

import org.noear.solon.ai.util.Markdown;
import org.noear.solon.ai.util.MarkdownUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * 从 .soloncode/commands/ 目录加载 Markdown 自定义命令
 *
 * @author noear
 * @since 2026.4.28
 */
public class MarkdownCommandLoader {
    private static final Logger LOG = LoggerFactory.getLogger(MarkdownCommandLoader.class);

    /**
     * 扫描目录（含子目录），注册 .md 文件为命令
     *
     * @param baseDir  命令目录根路径
     * @param registry 注册表
     */
    public static void loadFromDirectory(Path baseDir, CommandRegistry registry) {
        if (!Files.isDirectory(baseDir)) {
            return;
        }

        // 递归扫描子目录，支持 deploy/staging.md → deploy:staging
        try (Stream<Path> files = Files.walk(baseDir)) {
            files.filter(p -> p.toString().endsWith(".md"))
                 .filter(p -> Files.isRegularFile(p))
                    .forEach(p -> registerMarkdownCommand(p, baseDir, registry));
        } catch (IOException e) {
            LOG.warn("Failed to load commands from {}: {}", baseDir, e.getMessage());
        }
    }

    private static String buildCommandName(Path mdFile, Path baseDir) {
        Path relative = baseDir.relativize(mdFile);

        // 去掉 .md 后缀
        String relativeStr = relative.toString();
        if (relativeStr.endsWith(".md")) {
            relativeStr = relativeStr.substring(0, relativeStr.length() - 3);
        }

        // 将路径分隔符替换为冒号（命名空间分隔符）
        return relativeStr.replace('/', ':').replace('\\', ':');
    }

    /**
     * 注册单个 Markdown 命令
     *
     * @param mdFile   文件路径
     * @param registry 注册表
     */
    private static void registerMarkdownCommand(Path mdFile, Path baseDir, CommandRegistry registry) {
        // 1. 计算命令名（含命名空间）

        try {
            // 2. 读取文件所有行
            List<String> lines = Files.readAllLines(mdFile, StandardCharsets.UTF_8);

            // 3. 使用 MarkdownUtil 解析（自动处理 YAML Frontmatter）
            Markdown md = MarkdownUtil.resolve(lines);

            // 4. 提取元数据
            String name = buildCommandName(mdFile, baseDir);

            String description = md.getDescription();
            String argumentHint = md.getMeta("argument-hint").getString();
            List<String> allowedTools = parseAllowedTools(md.getMeta("allowed-tools").getString());
            String body = md.getContent();

            // 5. 如果没有 Frontmatter 的 description，尝试从 HTML 注释提取（向后兼容）
            if (description == null && !lines.isEmpty()) {
                String firstLine = lines.get(0).trim();
                if (firstLine.startsWith("<!--") && firstLine.endsWith("-->")) {
                    description = firstLine.substring(4, firstLine.length() - 3).trim();
                }
            }

            // 6. 注册命令
            registry.register(new MarkdownCommand(name, description, argumentHint, body, allowedTools));

        } catch (IOException e) {
            LOG.warn("Failed to read command file {}: {}", mdFile, e.getMessage());
        }
    }

    /**
     * 解析 allowed-tools 字段值
     * <p>
     * 格式："Bash(git add:*), Bash(git status:*), FileRead(*)"
     * → ["Bash(git add:*)", "Bash(git status:*)", "FileRead(*)"]
     */
    static List<String> parseAllowedTools(String value) {
        if (value == null || value.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> tools = new ArrayList<>();
        // 按逗号分割，但要处理括号内的逗号（如 Bash(a,b) 不应被分割）
        int depth = 0;
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                String tool = value.substring(start, i).trim();
                if (!tool.isEmpty()) {
                    tools.add(tool);
                }
                start = i + 1;
            }
        }
        // 最后一个
        String last = value.substring(start).trim();
        if (!last.isEmpty()) {
            tools.add(last);
        }

        return tools;
    }
}