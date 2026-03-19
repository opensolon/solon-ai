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
import org.noear.solon.ai.chat.tool.AbsTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 Unified Diff 的代码编辑技能 (Precise Code Editing)
 * 解决大文件修改时全量替换容易出错且消耗 Token 的问题。
 *
 * @author noear
 * @since 3.9.1
 */
public class ApplyDiffTool extends AbsTool {
    private static final Logger LOG = LoggerFactory.getLogger(ApplyDiffTool.class);
    private final String workDir;

    public ApplyDiffTool(String workDir) {
        this.workDir = workDir;

        addParam("path", String.class, true, "相对于工作目录的文件路径");
        addParam("diff", String.class, true, "Unified Diff 格式的补丁内容 (包含 @@ 块和上下文)");
    }

    protected Path getWorkPath(String __cwd) {
        String path = (__cwd != null) ? __cwd : workDir;
        if (path == null) throw new IllegalStateException("Working directory is not set.");
        return Paths.get(path).toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "apply_diff";
    }

    @Override
    public String description() {
        return "使用标准的 Unified Diff 补丁精准修改现有文件。当你需要对已有代码进行局部微调时，这是首选工具。\n" +
                "它具有以下特性：\n" +
                "1. 上下文感知：通过 @@ 块周围的行进行定位，即使行号微调也能成功。\n" +
                "2. 冲突检测：如果文件已被他人修改导致上下文不匹配，会报错提醒。\n" +
                "3. 节省 Token：无需发送整个文件，仅发送变化的部分。";
    }
    @Override
    public Object handle(Map<String, Object> args) throws Throwable {
        String path = (String) args.get("path");
        String diff = (String) args.get("diff");
        String __cwd = (String) args.get("__cwd"); //由 toolContext 传递

        if (__cwd == null) {
            return "错误: 未找到工作目录上下文。";
        }

        Path workPath = getWorkPath(__cwd);
        Path targetPath = workPath.resolve(path).normalize();

        // 安全检查：防止路径穿越
        if (!targetPath.startsWith(workPath)) {
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