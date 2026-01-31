package org.noear.solon.ai.skills.file;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 文件读写技能（增强版：带编码配置、沙箱保护、溢出控制与管理功能）
 * @author noear
 * @since 3.9.1
 */
public class FileReadWriteSkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(FileReadWriteSkill.class);
    private final Path rootPath;
    private final Charset charset;

    // 限制读取 24KB，防止过大内容塞爆大模型上下文
    private static final int MAX_READ_SIZE = 1024 * 24;

    /**
     * 默认构造函数（使用 UTF-8 编码）
     */
    public FileReadWriteSkill(String workDir) {
        this(workDir, StandardCharsets.UTF_8);
    }

    /**
     * 可选编码构造函数
     *
     * @param workDir 工作目录
     * @param charset 文本编码格式
     */
    public FileReadWriteSkill(String workDir, Charset charset) {
        this.rootPath = Paths.get(workDir).toAbsolutePath().normalize();
        this.charset = (charset == null ? StandardCharsets.UTF_8 : charset);
        ensureDir(this.rootPath);
    }

    @Override
    public String name() {
        return "file_manager";
    }

    @Override
    public String description() {
        return "文件专家：支持本地文件的存储、检索、查看列表及删除。适用于管理生成的报告或记录。";
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        return true;
    }

    @ToolMapping(name = "file_write", description = "写入文本到文件。会自动创建不存在的目录。")
    public String write(@Param("fileName") String fileName, @Param("content") String content) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("AI File Write ({}): {}", charset.name(), fileName);
        }

        try {
            Path target = resolvePath(fileName);
            Path parent = target.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            byte[] bytes = (content == null ? "" : content).getBytes(charset);
            Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            LOG.info("AI File Saved ({}): {}", charset.name(), target);
            return "文件保存成功: " + fileName;
        } catch (Exception e) {
            LOG.error("File write error: {}", fileName, e);
            return "写入失败: " + e.getMessage();
        }
    }

    @ToolMapping(name = "file_read", description = "读取文本文件内容。")
    public String read(@Param("fileName") String fileName) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("AI File Read ({}): {}", charset.name(), fileName);
        }

        try {
            Path target = resolvePath(fileName);
            if (!Files.exists(target)) return "文件不存在: " + fileName;

            long size = Files.size(target);
            if (size > MAX_READ_SIZE) {
                byte[] buffer = new byte[MAX_READ_SIZE];
                try (InputStream is = Files.newInputStream(target)) {
                    int readLen = is.read(buffer);
                    return new String(buffer, 0, readLen, charset) +
                            "\n...(内容过长，仅展示前 " + (MAX_READ_SIZE / 1024) + "KB)...";
                }
            } else {
                return new String(Files.readAllBytes(target), charset);
            }
        } catch (Exception e) {
            LOG.error("File read error: {}", fileName, e);
            return "读取失败: " + e.getMessage();
        }
    }

    @ToolMapping(name = "file_list", description = "列出当前目录下的文件及其基本信息。")
    public String list() {
        if (LOG.isTraceEnabled()) {
            LOG.trace("AI File List ({}): {}", charset.name(), rootPath);
        }

        try (Stream<Path> stream = Files.walk(rootPath, 1)) {
            String info = stream.filter(p -> !p.equals(rootPath))
                    .map(p -> {
                        try {
                            long size = Files.size(p);
                            return String.format("%s (%d bytes)", p.getFileName(), size);
                        } catch (IOException e) {
                            return p.getFileName().toString();
                        }
                    })
                    .collect(Collectors.joining(", "));
            return info.isEmpty() ? "目录为空。" : "文件列表: " + info;
        } catch (IOException e) {
            return "获取列表失败: " + e.getMessage();
        }
    }

    @ToolMapping(name = "file_delete", description = "删除指定文件。")
    public String delete(@Param("fileName") String fileName) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("AI File Delete ({}): {}", charset.name(), fileName);
        }

        try {
            Path target = resolvePath(fileName);
            return Files.deleteIfExists(target) ? "文件已删除: " + fileName : "文件不存在。";
        } catch (Exception e) {
            LOG.error("File delete error: {}", fileName, e);
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
        } catch (IOException ignored) {
        }
    }
}