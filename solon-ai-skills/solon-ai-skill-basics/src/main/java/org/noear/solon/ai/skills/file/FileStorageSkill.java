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
package org.noear.solon.ai.skills.file;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 文件存储技能：为 AI 提供本地文件系统的操作能力
 *
 * @author noear
 * @since 3.9.1
 */
public class FileStorageSkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(FileStorageSkill.class);

    private final Path rootPath;

    /**
     * @param workDir 工作目录（建议使用相对路径或专用的 temp 目录）
     */
    public FileStorageSkill(String workDir) {
        this.rootPath = Paths.get(workDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.rootPath);
        } catch (IOException e) {
            LOG.error("Failed to initialize work directory: {}", workDir, e);
        }
    }

    @Override
    public String name() { return "file_storage"; }

    @Override
    public String description() { return "磁盘管家：支持文件的创建、读取、列表查询和删除操作，用于数据持久化。"; }

    @ToolMapping(name = "write_file", description = "创建或覆盖一个文件，并写入文本内容")
    public String writeFile(@Param("fileName") String fileName, @Param("content") String content) {
        try {
            Path target = resolvePath(fileName);
            Files.write(target, content.getBytes(StandardCharsets.UTF_8));
            return "文件保存成功: " + fileName;
        } catch (Exception e) {
            return "写入失败: " + e.getMessage();
        }
    }

    @ToolMapping(name = "read_file", description = "读取指定文件的文本内容")
    public String readFile(@Param("fileName") String fileName) {
        try {
            Path target = resolvePath(fileName);
            if (!Files.exists(target)) return "错误：文件不存在。";
            return new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "读取失败: " + e.getMessage();
        }
    }

    @ToolMapping(name = "list_files", description = "列出当前工作目录下的所有文件")
    public String listFiles() {
        try (Stream<Path> stream = Files.list(rootPath)) {
            String files = stream.map(p -> p.getFileName().toString())
                    .collect(Collectors.joining(", "));
            return files.isEmpty() ? "目录为空。" : "当前文件: " + files;
        } catch (IOException e) {
            return "查询列表失败: " + e.getMessage();
        }
    }

    @ToolMapping(name = "delete_file", description = "删除指定的文件")
    public String deleteFile(@Param("fileName") String fileName) {
        try {
            Path target = resolvePath(fileName);
            return Files.deleteIfExists(target) ? "文件已删除: " + fileName : "文件不存在。";
        } catch (Exception e) {
            return "删除失败: " + e.getMessage();
        }
    }

    // 安全检查：防止路径穿越攻击（例如文件名传 "../../etc/passwd"）
    private Path resolvePath(String fileName) {
        Path p = rootPath.resolve(fileName).normalize();
        if (!p.startsWith(rootPath)) {
            throw new SecurityException("非法路径访问！");
        }
        return p;
    }
}