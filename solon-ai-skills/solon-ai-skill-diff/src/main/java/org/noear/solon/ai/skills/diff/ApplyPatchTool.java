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
package org.noear.solon.ai.skills.diff;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import org.noear.solon.ai.chat.tool.AbsTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class ApplyPatchTool extends AbsTool {
    private static final Logger LOG = LoggerFactory.getLogger(ApplyPatchTool.class);
    private final String workDir;

    public ApplyPatchTool(String workDir) {
        this.workDir = workDir;

        addParam("patchText", String.class, true, "The full patch text that describes all changes to be made");
    }

    protected Path getWorkPath(String __cwd) {
        String path = (__cwd != null) ? __cwd : workDir;
        if (path == null) throw new IllegalStateException("Working directory is not set.");
        return Paths.get(path).toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "apply_patch";
    }

    @Override
    public String description() {
        return "使用 `apply_patch` 工具来编辑文件。补丁语言是一种简化的、面向文件的 diff 格式，设计目的是易于解析且安全。你可以将其视为一个高级封装：\n" +
                "\n" +
                "*** Begin Patch\n" +
                "[ 一个或多个文件区块 ]\n" +
                "*** End Patch\n" +
                "\n" +
                "在此封装内，是一系列文件操作序列。\n" +
                "你必须包含一个头部（Header）来指定你要执行的操作。\n" +
                "每个操作以以下三种头部之一开始：\n" +
                "\n" +
                "*** Add File: <path> - 创建新文件。后续每一行都必须是以 + 开头的行（初始内容）。\n" +
                "*** Delete File: <path> - 删除现有文件。下方无需跟任何内容。\n" +
                "*** Update File: <path> - 就地修补现有文件（可选重命名操作）。\n" +
                "\n" +
                "补丁示例：\n" +
                "\n" +
                "```\n" +
                "*** Begin Patch\n" +
                "*** Add File: hello.txt\n" +
                "+Hello world\n" +
                "*** Update File: src/app.py\n" +
                "*** Move to: src/main.py\n" +
                "@@ def greet():\n" +
                "-print(\"Hi\")\n" +
                "+print(\"Hello, world!\")\n" +
                "*** Delete File: obsolete.txt\n" +
                "*** End Patch\n" +
                "```\n" +
                "\n" +
                "请务必记住：\n" +
                "\n" +
                "- 你必须包含一个指明意图的操作头部（Add/Delete/Update）。\n" +
                "- 即使在创建新文件时，新行也必须以 `+` 为前缀。";
    }

    @Override
    public Object handle(Map<String, Object> args) throws Throwable {
        String patchText = (String) args.get("patchText");
        String __cwd = (String) args.get("__cwd"); //由 toolContext 传递

        // 严格对齐: if (!params.patchText) throw new Error("patchText is required")
        if (patchText == null || patchText.trim().length() == 0) {
            throw new RuntimeException("patchText is required");
        }

        // 1. Parse result 对齐
        List<PatchHunk> hunks;
        try {
            hunks = parsePatchText(patchText);
        } catch (Throwable e) {
            throw new RuntimeException("apply_patch verification failed: " + e.getMessage());
        }

        // 2. 空补丁校验对齐
        if (hunks.isEmpty()) {
            String normalized = patchText.replace("\r\n", "\n").replace("\r", "\n").trim();
            if (normalized.equals("*** Begin Patch\n*** End Patch")) {
                throw new RuntimeException("patch rejected: empty patch");
            }
            throw new RuntimeException("apply_patch verification failed: no hunks found");
        }

        Path workPath = getWorkPath(__cwd);

        List<FileChange> fileChanges = new ArrayList<>();
        StringBuilder totalDiff = new StringBuilder();

        // 3. 循环准备变更
        for (PatchHunk hunk : hunks) {
            Path filePath = workPath.resolve(hunk.path).normalize();
            assertExternalDirectory(workPath, filePath);

            FileChange change = new FileChange();
            change.filePath = filePath;

            switch (hunk.type) {
                case "add":
                    change.type = "add";
                    change.oldContent = "";
                    change.newContent = (hunk.contents.length() == 0 || hunk.contents.endsWith("\n"))
                            ? hunk.contents : hunk.contents + "\n";
                    calculateDiffAndStats(change, workPath);
                    break;

                case "update":
                    // 对齐 stats 校验
                    if (!Files.exists(filePath) || Files.isDirectory(filePath)) {
                        throw new RuntimeException("apply_patch verification failed: Failed to read file to update: " + filePath);
                    }
                    change.oldContent = readFile(filePath);
                    try {
                        // 对应 deriveNewContentsFromChunks
                        change.newContent = applyHunks(change.oldContent, hunk.lines);
                    } catch (Throwable e) {
                        throw new RuntimeException("apply_patch verification failed: " + e.getMessage());
                    }

                    if (hunk.move_path != null) {
                        change.movePath = workPath.resolve(hunk.move_path).normalize();
                        assertExternalDirectory(workPath, change.movePath);
                        change.type = "move";
                    } else {
                        change.type = "update";
                    }
                    calculateDiffAndStats(change, workPath);
                    break;

                case "delete":
                    change.oldContent = readFile(filePath); // readFile 已处理 NoSuchFileException
                    change.type = "delete";
                    change.newContent = "";
                    calculateDiffAndStats(change, workPath);
                    break;
            }
            fileChanges.add(change);
            totalDiff.append(change.diff).append("\n");
        }

        // 4. 执行物理变更 (Apply the changes)
        for (FileChange change : fileChanges) {
            applyToDisk(change);
        }

        // 5. 生成摘要对齐
        List<String> summaryLines = new ArrayList<>();
        for (FileChange c : fileChanges) {
            Path base = c.movePath != null ? c.movePath : c.filePath;
            String rel = workPath.relativize(base).toString().replace("\\", "/");
            if (c.type.equals("add")) summaryLines.add("A " + rel);
            else if (c.type.equals("delete")) summaryLines.add("D " + rel);
            else summaryLines.add("M " + rel);
        }
        String output = "Success. Updated the following files:\n" + String.join("\n", summaryLines);

        // 6. 返回结构 100% 对齐 (包含 title, metadata, output)
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("diff", totalDiff.toString());
        metadata.put("files", buildFileMetadata(workPath, fileChanges));
        metadata.put("diagnostics", new HashMap<>()); // 保持结构完整性

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", output);
        result.put("metadata", metadata);
        result.put("output", output);

        return result;
    }

    private void calculateDiffAndStats(FileChange change, Path worktree) {
        List<String> oldLines = Arrays.asList(change.oldContent.split("\\r?\\n", -1));
        List<String> newLines = Arrays.asList(change.newContent.split("\\r?\\n", -1));

        if (change.type.equals("delete")) {
            // 对齐 delete 逻辑: contentToDelete.split("\n").length
            change.deletions = change.oldContent.length() == 0 ? 0 : change.oldContent.split("\n", -1).length;
            change.additions = 0;
        }

        Patch<String> patch = DiffUtils.diff(oldLines, newLines);
        if (!change.type.equals("delete")) {
            for (AbstractDelta<String> delta : patch.getDeltas()) {
                switch (delta.getType()) {
                    case INSERT: change.additions += delta.getTarget().size(); break;
                    case DELETE: change.deletions += delta.getSource().size(); break;
                    case CHANGE:
                        change.additions += delta.getTarget().size();
                        change.deletions += delta.getSource().size();
                        break;
                }
            }
        }

        // trimDiff 逻辑对齐
        String fileName = change.filePath.toString().replace("\\", "/");
        List<String> unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(fileName, fileName, oldLines, patch, 0);
        if (unifiedDiff.size() > 2) {
            StringBuilder sb = new StringBuilder();
            for (int i = 2; i < unifiedDiff.size(); i++) {
                sb.append(unifiedDiff.get(i)).append("\n");
            }
            change.diff = sb.toString();
        }
    }

    private void applyToDisk(FileChange change) throws IOException {
        Path target = (change.movePath != null) ? change.movePath : change.filePath;
        if ("delete".equals(change.type)) {
            Files.deleteIfExists(change.filePath);
        } else {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.write(target, change.newContent.getBytes(StandardCharsets.UTF_8));
            if ("move".equals(change.type)) {
                Files.deleteIfExists(change.filePath);
            }
        }
    }

    private List<PatchHunk> parsePatchText(String text) {
        List<PatchHunk> hunks = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");
        PatchHunk current = null;
        for (String line : lines) {
            if (line.startsWith("*** Add File:")) {
                current = new PatchHunk("add", line.substring(13).trim());
                hunks.add(current);
            } else if (line.startsWith("*** Update File:")) {
                current = new PatchHunk("update", line.substring(16).trim());
                hunks.add(current);
            } else if (line.startsWith("*** Delete File:")) {
                current = new PatchHunk("delete", line.substring(16).trim());
                hunks.add(current);
            } else if (line.startsWith("*** Move to:") && current != null) {
                // 对齐 OpenCode 的 move_path 映射
                current.move_path = line.substring(12).trim();
            } else if (current != null) {
                if ("add".equals(current.type)) {
                    current.contents += (line.startsWith("+") ? line.substring(1) : line) + "\n";
                } else if (!line.startsWith("@@") && !line.equals("*** End Patch") && !line.equals("*** Begin Patch")) {
                    current.lines.add(line);
                }
            }
        }
        return hunks;
    }

    private String applyHunks(String oldContent, List<String> diffLines) {
        List<String> lines = new ArrayList<>(Arrays.asList(oldContent.split("\\r?\\n", -1)));
        for (String d : diffLines) {
            if (d.startsWith("-")) lines.remove(d.substring(1));
            else if (d.startsWith("+")) lines.add(d.substring(1));
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append(lines.get(i));
            if (i < lines.size() - 1) sb.append("\n");
        }
        return sb.toString();
    }

    private List<Map<String, Object>> buildFileMetadata(Path worktree, List<FileChange> changes) {
        return changes.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("filePath", c.filePath.toString());
            // relativePath 对齐：path.relative(Instance.worktree, change.movePath ?? change.filePath)
            Path target = c.movePath != null ? c.movePath : c.filePath;
            m.put("relativePath", worktree.relativize(target).toString().replace("\\", "/"));
            m.put("type", c.type);
            m.put("diff", c.diff);
            m.put("before", c.oldContent);
            m.put("after", c.newContent);
            m.put("additions", c.additions);
            m.put("deletions", c.deletions);
            m.put("movePath", c.movePath != null ? c.movePath.toString() : null);
            return m;
        }).collect(Collectors.toList());
    }

    private void assertExternalDirectory(Path worktree, Path path) {
        if (path != null && !path.normalize().startsWith(worktree)) {
            throw new RuntimeException("apply_patch verification failed: Access denied to " + path);
        }
    }

    private String readFile(Path path) throws IOException {
        try {
            byte[] encoded = Files.readAllBytes(path);
            return new String(encoded, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            // 对齐 TS 的报错文案：catch(error) => throw Error(...)
            throw new IOException(e.toString());
        }
    }

    private static class PatchHunk {
        String type, path, move_path, contents = "";
        List<String> lines = new ArrayList<>();
        PatchHunk(String t, String p) { this.type = t; this.path = p; }
    }

    private static class FileChange {
        Path filePath, movePath;
        String oldContent = "", newContent = "", type, diff = "";
        int additions = 0, deletions = 0;
    }
}