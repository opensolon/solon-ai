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
        try {
            List<String> lines = Files.readAllLines(mdFile, StandardCharsets.UTF_8);

            Markdown md = MarkdownUtil.resolve(lines);

            String name = buildCommandName(mdFile, baseDir);
            String template = md.getContent();
            String description = md.getDescription();
            String model = md.getMeta("model").getString();

            registry.register(new MarkdownCommand(name, template, description, model));

        } catch (IOException e) {
            LOG.warn("Failed to read command file {}: {}", mdFile, e.getMessage());
        }
    }
}