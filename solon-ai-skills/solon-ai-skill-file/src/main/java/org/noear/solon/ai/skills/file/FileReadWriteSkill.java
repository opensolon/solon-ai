package org.noear.solon.ai.skills.file;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * 文件读写技能（Java 8 兼容版，带沙箱保护与溢出控制）
 * @author noear
 * @since 3.9.1
 */
public class FileReadWriteSkill extends AbsSkill {
    private static final Logger log = LoggerFactory.getLogger(FileReadWriteSkill.class);
    private final Path rootPath;
    // 限制读取 24KB，防止过大内容塞爆大模型上下文（Context Window）
    private static final int MAX_READ_SIZE = 1024 * 24;

    public FileReadWriteSkill(String workDir) {
        this.rootPath = Paths.get(workDir).toAbsolutePath().normalize();
        ensureDir(this.rootPath);
    }

    @Override
    public String name() {
        return "file_manager";
    }

    @Override
    public String description() {
        return "文件专家：支持本地文本文件的存储与检索。适用于保存生成的报告、记录笔记或分析日志。";
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        String content = prompt.getUserContent().toLowerCase();
        // 意图过滤，仅在涉及文件读写动作时触发
        return content.contains("文件") || content.contains("file") ||
                content.contains("写下") || content.contains("读取") || content.contains("保存");
    }

    @ToolMapping(name = "write_text_file", description = "写入文本到文件。会自动创建不存在的目录。建议使用 .txt, .md 或 .json 后缀。")
    public String write(@Param("fileName") String fileName, @Param("content") String content) {
        try {
            Path target = resolvePath(fileName);

            // Java 8 自动创建父级目录逻辑
            Path parent = target.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            // Java 8 写文件标准写法
            byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
            Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            log.info("AI File Saved: {}", target);
            return "文件保存成功: " + fileName;
        } catch (SecurityException se) {
            return "权限拒绝: " + se.getMessage();
        } catch (Exception e) {
            log.error("File write error: {}", fileName, e);
            return "写入失败: " + e.getMessage();
        }
    }

    @ToolMapping(name = "read_text_file", description = "从本地读取文本文件内容。")
    public String read(@Param("fileName") String fileName) {
        try {
            Path target = resolvePath(fileName);
            if (!Files.exists(target)) {
                return "文件不存在: " + fileName;
            }

            long size = Files.size(target);
            if (size > MAX_READ_SIZE) {
                // Java 8 保护性读取：只加载前 MAX_READ_SIZE 字节
                byte[] buffer = new byte[MAX_READ_SIZE];
                try (InputStream is = Files.newInputStream(target)) {
                    int readLen = is.read(buffer);
                    return new String(buffer, 0, readLen, StandardCharsets.UTF_8) +
                            "\n...(内容过长，仅展示前 " + (MAX_READ_SIZE / 1024) + "KB)...";
                }
            } else {
                // 小文件一次性读取
                byte[] bytes = Files.readAllBytes(target);
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.error("File read error: {}", fileName, e);
            return "读取失败: " + e.getMessage();
        }
    }

    private Path resolvePath(String name) {
        // 简单清理路径名
        Path p = rootPath.resolve(name).normalize();
        if (!p.startsWith(rootPath)) {
            throw new SecurityException("禁止越权访问沙箱外部目录");
        }
        return p;
    }

    private void ensureDir(Path path) {
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
        } catch (IOException ignored) {}
    }
}