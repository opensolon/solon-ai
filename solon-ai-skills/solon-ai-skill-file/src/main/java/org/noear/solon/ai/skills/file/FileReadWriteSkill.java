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
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 文件读写技能（增强版：带沙箱保护、溢出控制与管理功能）
 * @author noear
 * @since 3.9.1
 */
public class FileReadWriteSkill extends AbsSkill {
    private static final Logger log = LoggerFactory.getLogger(FileReadWriteSkill.class);
    private final Path rootPath;
    // 限制读取 24KB，防止过大内容塞爆大模型上下文
    private static final int MAX_READ_SIZE = 1024 * 24;

    public FileReadWriteSkill(String workDir) {
        this.rootPath = Paths.get(workDir).toAbsolutePath().normalize();
        ensureDir(this.rootPath);
    }

    @Override
    public String name() { return "file_manager"; }

    @Override
    public String description() {
        return "文件专家：支持本地文件的存储、检索、查看列表及删除。适用于管理生成的报告或记录。";
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        String content = prompt.getUserContent().toLowerCase();
        // 涵盖了读、写、查、删的所有意图
        return content.matches(".*(文件|file|写下|读取|保存|列表|目录|删除|delete).*");
    }

    @ToolMapping(name = "file_write", description = "写入文本到文件。会自动创建不存在的目录。")
    public String write(@Param("fileName") String fileName, @Param("content") String content) {
        try {
            Path target = resolvePath(fileName);
            Path parent = target.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
            Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            log.info("AI File Saved: {}", target);
            return "文件保存成功: " + fileName;
        } catch (Exception e) {
            return "写入失败: " + e.getMessage();
        }
    }

    @ToolMapping(name = "file_read", description = "读取文本文件内容。")
    public String read(@Param("fileName") String fileName) {
        try {
            Path target = resolvePath(fileName);
            if (!Files.exists(target)) return "文件不存在: " + fileName;

            long size = Files.size(target);
            if (size > MAX_READ_SIZE) {
                byte[] buffer = new byte[MAX_READ_SIZE];
                try (InputStream is = Files.newInputStream(target)) {
                    int readLen = is.read(buffer);
                    return new String(buffer, 0, readLen, StandardCharsets.UTF_8) +
                            "\n...(内容过长，仅展示前 " + (MAX_READ_SIZE / 1024) + "KB)...";
                }
            } else {
                return new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return "读取失败: " + e.getMessage();
        }
    }

    // --- 吸收自 FileStorageSkill 的功能 ---

    @ToolMapping(name = "file_list", description = "列出当前目录下的所有文件。")
    public String list() {
        try (Stream<Path> stream = Files.walk(rootPath, 1)) {
            String files = stream.filter(p -> !p.equals(rootPath))
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.joining(", "));
            return files.isEmpty() ? "目录为空。" : "当前文件列表: " + files;
        } catch (IOException e) {
            return "获取列表失败: " + e.getMessage();
        }
    }

    @ToolMapping(name = "file_delete", description = "删除指定文件。")
    public String delete(@Param("fileName") String fileName) {
        try {
            Path target = resolvePath(fileName);
            return Files.deleteIfExists(target) ? "文件已删除: " + fileName : "文件不存在。";
        } catch (Exception e) {
            return "删除失败: " + e.getMessage();
        }
    }

    private Path resolvePath(String name) {
        Path p = rootPath.resolve(name).normalize();
        if (!p.startsWith(rootPath)) {
            throw new SecurityException("禁止越权访问沙箱外部");
        }
        return p;
    }

    private void ensureDir(Path path) {
        try {
            if (!Files.exists(path)) Files.createDirectories(path);
        } catch (IOException ignored) {}
    }
}