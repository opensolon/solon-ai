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
package org.noear.solon.ai.skills.diff;

import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import com.github.difflib.patch.PatchFailedException;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 基于 Unified Diff 的代码编辑技能 (Precise Code Editing)
 * 解决大文件修改时全量替换容易出错且消耗 Token 的问题。
 *
 * @author noear
 * @since 3.9.1
 */
public class DiffSkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(DiffSkill.class);

    private final Path rootPath;

    public DiffSkill(String workDir) {
        this.rootPath = Paths.get(workDir).toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "diff_editor";
    }

    @Override
    public String description() {
        return "通过 Unified Diff 补丁精准修改代码。支持局部多块(Hunks)修改，具有上下文感知和冲突检测能力。";
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        return true;
    }

    @Override
    public String getInstruction(Prompt prompt) {
        return "#### 编辑协议 (Editing Protocol)\n" +
                "- `apply_diff`: 传入相对路径和标准的 Unified Diff。补丁需包含上下文行以确保定位精度。\n" +
                "- 格式规范: 必须符合 `@@ -l,s +l,s @@` 块定义。即便行号略微偏移，算法也会尝试基于上下文进行模糊匹配。\n";
    }

    /**
     * 应用补丁到指定文件
     *
     * @param path 文件相对路径
     * @param diff 内容 (Unified Diff 格式)
     */
    @ToolMapping(name = "apply_diff", description = "应用 Unified Diff 补丁来修改文件。")
    public String applyDiff(@Param(value = "path", description = "相对于工作目录的文件路径") String path,
                            @Param(value = "diff", description = "Unified Diff 格式的补丁内容") String diff) {
        Path targetPath = rootPath.resolve(path).normalize();

        // 安全检查：防止路径穿越
        if (!targetPath.startsWith(rootPath)) {
            return "错误: 非法的路径访问。";
        }

        if (!Files.exists(targetPath)) {
            return "错误: 文件不存在: " + path;
        }

        try {
            // 1. 读取原始文件
            List<String> originalLines = Files.readAllLines(targetPath, StandardCharsets.UTF_8);

            // 2. 预处理补丁行
            List<String> diffLines = prepareDiffLines(path, diff);

            // 3. 解析并应用补丁
            Patch<String> patch = UnifiedDiffUtils.parseUnifiedDiff(diffLines);
            List<String> patchedLines = patch.applyTo(originalLines);

            // 4. 写回文件
            Files.write(targetPath, patchedLines, StandardCharsets.UTF_8);

            LOG.info("Diff applied successfully to: {}", path);
            return "成功: 补丁已应用到 " + path;

        } catch (PatchFailedException e) {
            LOG.warn("Patch collision or context mismatch for: {}", path);
            return "应用补丁失败: 上下文不匹配（冲突）。请重新读取文件获取最新版本再生成 Diff。";
        } catch (IOException e) {
            LOG.error("IO error applying diff to: {}", path, e);
            return "应用补丁失败: 文件读写错误: " + e.getMessage();
        } catch (Exception e) {
            LOG.error("Unexpected error applying diff", e);
            return "应用补丁失败: 内部错误: " + e.getMessage();
        }
    }

    private List<String> prepareDiffLines(String path, String diff) {
        // 清洗 Markdown 标记
        String cleanDiff = diff.trim();
        if (cleanDiff.startsWith("```")) {
            cleanDiff = cleanDiff.replaceAll("^```[a-zA-Z]*\\s+", "").replaceAll("\\s+```$", "");
        }

        String[] lines = cleanDiff.split("\\R");
        List<String> result = new ArrayList<>();

        // 检查头信息
        boolean hasHeader = false;
        for (String line : lines) {
            if (line.startsWith("---")) {
                hasHeader = true;
                break;
            }
        }

        if (!hasHeader) {
            result.add("--- " + path);
            result.add("+++ " + path);
        }

        for (String line : lines) {
            String trimmed = line.trim();
            // 过滤无效行：Markdown 标识、Git 辅助说明行、完全无意义的空行
            if (trimmed.startsWith("```") || line.startsWith("\\")) {
                continue;
            }
            // 补丁行通常以 ' ', '+', '-', '@' 开头，保护性处理
            result.add(line);
        }
        return result;
    }
}