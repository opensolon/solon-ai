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
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;

/**
 * 压缩技能：支持混合打包文件或目录
 *
 * @author noear
 * @since 3.9.1
 */
public class ZipSkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(ZipSkill.class);
    private final Path rootPath;
    private final Charset charset;

    public ZipSkill(String workDir) {
        this(workDir, StandardCharsets.UTF_8);
    }

    public ZipSkill(String workDir, Charset charset) {
        this.rootPath = Paths.get(workDir).toAbsolutePath().normalize();
        this.charset = (charset == null ? StandardCharsets.UTF_8 : charset);
    }

    @Override
    public String name() { return "zip_tool"; }

    @Override
    public String description() {
        return "压缩专家：可以将指定的一个或多个路径（文件或目录）打包为 ZIP。";
    }

    @Override
    public boolean isSupported(Prompt prompt) { return true; }

    @ToolMapping(name = "zip", description = "将指定的路径列表（支持文件和目录混合）打包到 ZIP 文件中。")
    public String zip(@Param("zipFileName") String zipFileName, @Param("paths") String[] paths) {
        Path zipPath = resolvePath(zipFileName);
        ensureParentDir(zipPath);

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");
        env.put("encoding", charset.name());

        try (FileSystem zipfs = FileSystems.newFileSystem(URI.create("jar:" + zipPath.toUri()), env)) {
            for (String sourceName : paths) {
                Path sourcePath = resolvePath(sourceName);
                if (!Files.exists(sourcePath)) {
                    continue;
                }

                if (Files.isDirectory(sourcePath)) {
                    // 如果是目录，递归添加
                    addToZipRecursive(sourcePath, zipfs, zipPath);
                } else {
                    // 如果是文件，直接添加
                    addToZip(sourcePath, zipfs);
                }
            }
            return "打包成功，保存至: " + zipFileName;
        } catch (IOException e) {
            LOG.error("Zip failed: {}, charset: {}", zipFileName, charset, e);
            return "打包失败: " + e.getMessage();
        }
    }

    /**
     * 递归添加目录到 ZIP
     */
    private void addToZipRecursive(Path sourceDir, FileSystem zipfs, Path zipPath) throws IOException {
        Files.walkFileTree(sourceDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                // 在 ZIP 中创建对应的目录结构
                String relativePath = rootPath.relativize(dir).toString().replace("\\", "/");
                if (!relativePath.isEmpty()) {
                    Path pathInZip = zipfs.getPath(relativePath + "/");
                    if (Files.notExists(pathInZip)) {
                        Files.createDirectories(pathInZip);
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                // 防止把正在生成的 zip 文件自己又压进去
                if (Files.isSameFile(file, zipPath)) {
                    return FileVisitResult.CONTINUE;
                }
                addToZip(file, zipfs);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 单个文件添加
     */
    private void addToZip(Path file, FileSystem zipfs) throws IOException {
        String relativePath = rootPath.relativize(file).toString().replace("\\", "/");
        Path pathInZip = zipfs.getPath(relativePath);

        if (pathInZip.getParent() != null && Files.notExists(pathInZip.getParent())) {
            Files.createDirectories(pathInZip.getParent());
        }
        Files.copy(file, pathInZip, StandardCopyOption.REPLACE_EXISTING);
    }

    private void ensureParentDir(Path path) {
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
        } catch (IOException ignored) {}
    }

    private Path resolvePath(String fileName) {
        Path p = rootPath.resolve(fileName).normalize();
        if (!p.startsWith(rootPath)) {
            throw new SecurityException("非法路径访问：" + fileName);
        }
        return p;
    }
}