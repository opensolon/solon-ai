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
 * 文件读写技能
 * @author noear
 * @since 3.9.1
 */
public class FileReadWriteSkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(FileReadWriteSkill.class);
    private final Path rootPath;
    private final Charset charset;
    private static final int MAX_READ_SIZE = 1024 * 24;

    public FileReadWriteSkill(String workDir) {
        this(workDir, StandardCharsets.UTF_8);
    }

    public FileReadWriteSkill(String workDir, Charset charset) {
        this.rootPath = Paths.get(workDir).toAbsolutePath().normalize();
        this.charset = (charset == null ? StandardCharsets.UTF_8 : charset);
        ensureDir(this.rootPath);
    }

    @Override
    public String name() { return "file_manager"; }

    @Override
    public String description() {
        return "文件专家：支持本地文件的读写、列表查看（识别目录/文件）、检索及删除。支持深度目录访问。";
    }

    @Override
    public boolean isSupported(Prompt prompt) { return true; }

    @ToolMapping(name = "file_write", description = "写入文本到文件。会自动创建不存在的目录。")
    public String write(@Param("fileName") String fileName, @Param("content") String content) {
        try {
            Path target = resolvePath(fileName);
            Path parent = target.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            byte[] bytes = (content == null ? "" : content).getBytes(charset);
            Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return "文件保存成功: " + fileName;
        } catch (Exception e) {
            LOG.error("File write error: {}", fileName, e);
            return "写入失败: " + e.getMessage();
        }
    }

    @ToolMapping(name = "file_read", description = "读取文本文件内容。")
    public String read(@Param("fileName") String fileName) {
        try {
            Path target = resolvePath(fileName);
            if (!Files.exists(target)) return "文件不存在: " + fileName;
            if (Files.isDirectory(target)) return "读取失败：'" + fileName + "' 是一个目录。请使用 file_list 查看其内容。";

            long size = Files.size(target);
            if (size > MAX_READ_SIZE) {
                byte[] buffer = new byte[MAX_READ_SIZE];
                try (InputStream is = Files.newInputStream(target)) {
                    int readLen = is.read(buffer);
                    return new String(buffer, 0, readLen, charset) + "\n...(内容过长，仅截取前 " + (MAX_READ_SIZE / 1024) + "KB)...";
                }
            } else {
                return new String(Files.readAllBytes(target), charset);
            }
        } catch (Exception e) {
            LOG.error("File read error: {}", fileName, e);
            return "读取失败: " + e.getMessage();
        }
    }

    @ToolMapping(name = "file_list", description = "列出指定目录下的文件和子目录。如果不指定目录，则列出根目录。")
    public String list(@Param(value = "dirName", required = false) String dirName) {
        Path targetDir = (dirName == null || dirName.isEmpty()) ? rootPath : resolvePath(dirName);

        if (!Files.exists(targetDir)) return "目录不存在: " + (dirName == null ? "/" : dirName);
        if (!Files.isDirectory(targetDir)) return "路径不是目录: " + dirName;

        try (Stream<Path> stream = Files.walk(targetDir, 1)) {
            String info = stream.filter(p -> !p.equals(targetDir))
                    .map(p -> {
                        String name = p.getFileName().toString();
                        if (Files.isDirectory(p)) {
                            return "[Dir] " + name;
                        } else {
                            try {
                                return String.format("[File] %s (%d bytes)", name, Files.size(p));
                            } catch (IOException e) {
                                return "[File] " + name;
                            }
                        }
                    })
                    .collect(Collectors.joining("\n"));

            String header = "目录内容 (" + (dirName == null ? "根目录" : dirName) + "):\n";
            return info.isEmpty() ? header + "空" : header + info;
        } catch (IOException e) {
            return "获取列表失败: " + e.getMessage();
        }
    }

    @ToolMapping(name = "file_delete", description = "删除指定文件或空目录。")
    public String delete(@Param("fileName") String fileName) {
        try {
            Path target = resolvePath(fileName);
            return Files.deleteIfExists(target) ? "删除成功: " + fileName : "文件不存在。";
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
        } catch (IOException ignored) { }
    }
}