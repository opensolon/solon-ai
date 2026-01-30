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
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;

/**
 * 压缩技能：支持将文件或目录打包为 ZIP 格式 (优化版)
 *
 * @author noear
 * @since 3.9.1
 */
public class ZipSkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(ZipSkill.class);
    private final Path rootPath;

    public ZipSkill(String workDir) {
        this.rootPath = Paths.get(workDir).toAbsolutePath().normalize();
    }

    @Override
    public String name() { return "zip_tool"; }

    @Override
    public String description() { return "压缩专家：可以将指定文件或目录打包为 ZIP。"; }

    @Override
    public boolean isSupported(Prompt prompt) {
        String content = prompt.getUserContent();
        // 只有当涉及到文件操作意图时，才加载文件和压缩工具
        return content.matches(".*(保存|文件|下载|打包|压缩|ZIP|zip|导出).*");
    }

    @ToolMapping(name = "zip_files", description = "将指定文件列表打包到 ZIP 中")
    public String zipFiles(@Param("zipFileName") String zipFileName, @Param("fileNames") String[] fileNames) {
        Path zipPath = resolvePath(zipFileName);
        ensureParentDir(zipPath);

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");
        env.put("encoding", "UTF-8");

        try (FileSystem zipfs = FileSystems.newFileSystem(URI.create("jar:" + zipPath.toUri()), env)) {
            for (String name : fileNames) {
                Path source = resolvePath(name);
                if (Files.isRegularFile(source)) {
                    // 优化点：计算相对路径，保留子目录层级
                    String relativePath = rootPath.relativize(source).toString().replace("\\", "/");
                    Path pathInZip = zipfs.getPath("/" + relativePath);

                    if (pathInZip.getParent() != null) {
                        Files.createDirectories(pathInZip.getParent());
                    }
                    Files.copy(source, pathInZip, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return "文件已打包至: " + zipFileName;
        } catch (IOException e) {
            LOG.error("Zip files failed: {}", zipFileName, e);
            return "打包失败: " + e.getMessage();
        }
    }

    @ToolMapping(name = "zip_directory", description = "将整个工作目录递归打包成 ZIP")
    public String zipDirectory(@Param("zipFileName") String zipFileName) {
        Path zipPath = resolvePath(zipFileName);
        ensureParentDir(zipPath);

        Map<String, String> env = new HashMap<>();
        env.put("create", "true");
        env.put("encoding", "UTF-8");

        try (FileSystem zipfs = FileSystems.newFileSystem(URI.create("jar:" + zipPath.toUri()), env)) {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    // 处理空目录：在压缩包内创建对应文件夹
                    if (!dir.equals(rootPath)) {
                        String relativePath = rootPath.relativize(dir).toString().replace("\\", "/");
                        Files.createDirectories(zipfs.getPath(relativePath + "/"));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    // 关键优化：跳过压缩包本身
                    if (Files.exists(zipPath) && Files.isSameFile(file, zipPath)) {
                        return FileVisitResult.CONTINUE;
                    }

                    String relativePath = rootPath.relativize(file).toString().replace("\\", "/");
                    Path pathInZip = zipfs.getPath(relativePath);

                    // 拷贝文件（如果父目录没创建则创建，覆盖已存在的）
                    if (pathInZip.getParent() != null) {
                        Files.createDirectories(pathInZip.getParent());
                    }
                    Files.copy(file, pathInZip, StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });
            return "整个目录已成功压缩至: " + zipFileName;
        } catch (IOException e) {
            LOG.error("Zip directory failed: {}", zipFileName, e);
            return "目录打包失败: " + e.getMessage();
        }
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