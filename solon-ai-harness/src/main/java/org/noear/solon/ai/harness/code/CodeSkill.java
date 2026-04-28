/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.code;

import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.code.impl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Code 规范对齐的专家技能
 *
 * @author noear
 * @since 3.9.4
 */
public class CodeSkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(CodeSkill.class);

    public final static String NAME_CODE_MD = "CODE.md";

    private final HarnessEngine engine;
    private final List<LanguageProvider> providers = new ArrayList<>();

    public CodeSkill(HarnessEngine engine) {
        this.engine = engine;

        providers.add(new MavenProvider());
        providers.add(new NodeProvider());
        providers.add(new GoProvider());
        providers.add(new PythonProvider());
        providers.add(new RustProvider());
        providers.add(new DotNetProvider());
    }

    public String HOME_CODE_MD() {
        return engine.getProps().getHarnessHome() + NAME_CODE_MD;
    }


    @Override
    public String description() {
        return "代码专家技能。支持项目初始化、技术栈自动识别以及 `" + HOME_CODE_MD() + "` 规约生成。";
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        String __cwd = prompt.attrAs(HarnessEngine.ATTR_CWD);
        Path rootPath = getRootPath(__cwd);

        if (rootExists(rootPath, HOME_CODE_MD())) {
            return true;
        }

        Set<String> markers = providers.stream()
                .flatMap(p -> Arrays.stream(p.markers()))
                .collect(Collectors.toSet());

        markers.addAll(Arrays.asList(".git", ".github", ".gitee", "src", "lib"));

        for (String marker : markers) {
            if (rootExists(rootPath, marker)) return true;
        }

        for (String marker : markers) {
            if (deepExists(rootPath, marker)) return true;
        }

        return false;
    }

    @Override
    public String getInstruction(Prompt prompt) {
        String __cwd = prompt.attrAs(HarnessEngine.ATTR_CWD);

        StringBuilder buf = new StringBuilder();

        String msg = init(__cwd);

        buf.append("\n## 核心工程规约 (Core Engineering Protocol)\n");
        buf.append("> 项目当前上下文: ").append(msg).append("\n\n");

        buf.append("为了确保工程质量，要严格执行以下操作：\n")
                .append("1. **动作前导**: 在开始任何任务前，先读 `" + HOME_CODE_MD() + "` 以获取构建和测试指令。\n")
                .append("2. **验证驱动**: 修改代码后，参考 `" + HOME_CODE_MD() + "` 中的指令运行测试，严禁未验证提交。\n");

        return buf.toString();
    }

    private Path getRootPath(String __cwd) {
        String path = (__cwd != null) ? __cwd : engine.getProps().getWorkspace();
        if (path == null) throw new IllegalStateException("Working directory is not set.");
        return Paths.get(path).toAbsolutePath().normalize();
    }

    public String init(String __cwd) {
        try {
            Path rootPath = getRootPath(__cwd);

            if (!Files.isWritable(rootPath)) return "错误：目录不可写。";

            StringBuilder newContent = new StringBuilder();
            newContent.append("## 构建与测试指令 (Build and Test Commands)\n\n");

            List<String> detectedStacks = new ArrayList<>();
            Set<LanguageProvider> rootMatched = new HashSet<>();

            for (LanguageProvider provider : providers) {
                if (provider.isMatch(rootPath)) {
                    rootMatched.add(provider);
                    detectedStacks.add(provider.id() + "(Root)");
                    provider.appendRootCommands(newContent);
                }
            }

            List<Path> allNodes = new ArrayList<>();
            try {
                Files.walkFileTree(rootPath, EnumSet.noneOf(FileVisitOption.class), 3, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (dir.equals(rootPath)) {
                            return FileVisitResult.CONTINUE;
                        }

                        if (isIgnored(dir)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }

                        allNodes.add(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                LOG.error("Scan sub-modules failed", e);
            }

            boolean hasSubModulesSection = false;

            // 存储已经处理过的模块路径，防止重复（比如父子目录都被识别）
            Set<String> processedPaths = new HashSet<>();

            for (Path dir : allNodes) {
                String relativePath = rootPath.relativize(dir).toString().replace("\\", "/");

                // 如果父目录已经作为模块处理过了，子目录就不再单独列出（Maven 惯例）
                if (processedPaths.stream().anyMatch(p -> relativePath.startsWith(p + "/"))) continue;

                for (LanguageProvider provider : providers) {
                    if (provider.isMatch(dir)) {
                        processedPaths.add(relativePath);

                        // 判断异构：如果当前 Provider 没在根目录出现过，则是异构
                        boolean isHeterogeneous = !rootMatched.contains(provider);

                        if (isHeterogeneous) {
                            detectedStacks.add(relativePath + "(" + provider.id() + ")");
                            provider.appendModuleCommands(newContent, relativePath);
                        } else {
                            if (!hasSubModulesSection) {
                                newContent.append("### 子模块与子项目 (Sub-modules & Sub-projects)\n");
                                hasSubModulesSection = true;
                            }
                            newContent.append("- ").append(relativePath).append(": ")
                                    .append(provider.typeName()).append("。受根项目指令统一控制。\n");
                        }
                        break; // 一个目录识别为一个主语言即可
                    }
                }
            }

            if (hasSubModulesSection) newContent.append("\n");


            appendGuidelines(newContent);

            Path targetPath = rootPath.resolve(HOME_CODE_MD());
            String finalContent = newContent.toString();
            boolean updated = true;
            if (Files.exists(targetPath)) {
                updated = !finalContent.equals(new String(Files.readAllBytes(targetPath), StandardCharsets.UTF_8));
            }
            if (updated) Files.write(targetPath, finalContent.getBytes(StandardCharsets.UTF_8));

            ensureInGitignore(rootPath, ".soloncode/");

            StringBuilder resultMsg = new StringBuilder();
            resultMsg.append(updated ? "已更新" : "已验证").append("项目工程规范");
            if (!detectedStacks.isEmpty()) {
                resultMsg.append(" (检测到技术栈: ").append(String.join(", ", detectedStacks)).append(")");
            } else {
                resultMsg.append(" (未检测到明确的技术栈)");
            }

            return resultMsg.toString();
        } catch (Throwable e) {
            LOG.error("Init failed", e);
            return "初始化失败: " + e.getMessage();
        }
    }

    private void appendGuidelines(StringBuilder buf) {
        buf.append("## 工程规约 (Guidelines)\n\n")
                .append("- **读前必改**: 在进行任何修改前，务必完整阅读相关文件内容。\n")
                .append("- **原子作业**: 每次仅实现一个功能或修复一个 Bug。\n")
                .append("- **验证驱动**: 任务完成前必须运行测试进行验证。\n")
                .append("- **路径规范**: 仅使用相对路径（例如：`src/main/java/App.java`，严禁使用 `./src/...`）。\n")
                .append("- **风格对齐**: 必须遵循代码库中已有的编码风格和设计模式。\n")
                .append("- **环境感知**: 利用你对各语言默认本地仓库路径（如 Maven、Node）的知识，协助排查依赖问题或进行源码分析。\n\n");

    }

    private void ensureInGitignore(Path rootPath, String fileName) {
        try {
            Path gitignore = rootPath.resolve(".gitignore");
            if (Files.exists(gitignore)) {
                List<String> lines = Files.readAllLines(gitignore, StandardCharsets.UTF_8);
                // 精确匹配行，或者检查是否有以该文件名开头的有效行
                boolean exists = lines.stream()
                        .map(String::trim)
                        .anyMatch(line -> line.equals(fileName) || line.startsWith(fileName + " "));

                if (!exists) {
                    String separator = (lines.isEmpty() || lines.get(lines.size() - 1).isEmpty()) ? "" : "\n";
                    Files.write(gitignore, (separator + fileName + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean rootExists(Path rootPath, String path) {
        return Files.exists(rootPath.resolve(path));
    }

    private boolean deepExists(Path rootPath, String path) {
        try {
            final boolean[] found = {false};

            Files.walkFileTree(rootPath, EnumSet.noneOf(FileVisitOption.class), 3, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (isIgnored(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    if (Files.exists(dir.resolve(path))) {
                        found[0] = true;
                        return FileVisitResult.TERMINATE;
                    }

                    return FileVisitResult.CONTINUE;
                }
            });

            return found[0];
        } catch (IOException e) {
            return false;
        }
    }

    private boolean isIgnored(Path path) {
        String pathName = path.getFileName().toString();

        // 1. 基础全局忽略（隐藏目录如 .git, .idea, .soloncode 等）
        if (pathName.startsWith(".")) {
            return true;
        }

        // 2. 业务无关的通用忽略
        if ("bin".equals(pathName) || "build".equals(pathName)) {
            return true;
        }

        // 3. 委派给各语言实现类
        return providers.stream().anyMatch(p -> p.isIgnored(pathName));
    }
}