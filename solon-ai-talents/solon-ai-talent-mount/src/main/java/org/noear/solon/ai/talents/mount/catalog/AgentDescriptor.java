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
package org.noear.solon.ai.talents.mount.catalog;

import org.noear.solon.ai.talents.mount.source.MountSource;
import org.noear.solon.ai.util.Markdown;
import org.noear.solon.ai.util.MarkdownUtil;
import org.noear.solon.core.util.IoUtil;
import org.noear.solon.lang.Preview;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

/**
 * 已解析的 Agent 来源描述，名称和描述来自 Markdown frontmatter，并缓存解析结果。
 *
 * @author noear 
 * @since 4.1.1
 */
@Preview("4.1.1")
public class AgentDescriptor {
    private final String name;
    private final String description;
    private final String mountAlias;
    private final MountSource source;
    private final String sourcePath;
    private final Markdown markdown;

    /**
     * 根据已解析的 Markdown 创建 Agent 描述并规范化来源路径。
     *
     * @param mountAlias 挂载别名
     * @param source 来源
     * @param sourcePath 来源路径
     * @param markdown 已解析的 Markdown
     */
    private AgentDescriptor(String mountAlias, MountSource source, String sourcePath, Markdown markdown) {
        this.name = markdown.getName();
        this.description = markdown.getDescription() == null ? "" : markdown.getDescription();
        this.mountAlias = mountAlias;
        this.source = source;
        this.sourcePath = source.normalize(sourcePath);
        this.markdown = markdown;
    }

    /**
     * 读取并解析来源中的 Markdown，校验 frontmatter 中的名称。
     *
     * @param mountAlias 挂载别名
     * @param source 来源
     * @param sourcePath Markdown 来源路径
     * @return 解析后的 Agent 描述
     * @throws IOException 读取失败或名称缺失、为空时抛出
     */
    public static AgentDescriptor parse(String mountAlias, MountSource source, String sourcePath) throws IOException {
        Markdown markdown;
        try (InputStream input = source.openRead(sourcePath)) {
            String str = IoUtil.transferToString(input);
            markdown = MarkdownUtil.resolve(Arrays.asList(str.split("\\R", -1)));
        }

        String name = markdown.getName();
        if (name == null || name.trim().isEmpty()) {
            throw new IOException("Agent Markdown must define frontmatter name: " + sourcePath);
        }
        return new AgentDescriptor(mountAlias, source, sourcePath, markdown);
    }

    /**
     * 获取 Markdown frontmatter 中的 Agent 名称。
     *
     * @return Agent 名称
     */
    public String getName() { return name; }
    /**
     * 获取 Agent 描述。
     *
     * @return 描述；未定义时返回空字符串
     */
    public String getDescription() { return description; }
    /**
     * 获取缓存的 Markdown 解析结果。
     *
     * @return Markdown 解析结果
     */
    public Markdown getMarkdown() { return markdown; }
    /**
     * 获取所属挂载的别名。
     *
     * @return 挂载别名
     */
    public String getMountAlias() { return mountAlias; }
    /**
     * 获取 Agent 的来源。
     *
     * @return 挂载来源
     */
    public MountSource getSource() { return source; }
    /**
     * 获取规范化后的来源路径。
     *
     * @return 来源路径
     */
    public String getSourcePath() { return sourcePath; }

    /**
     * 打开 Agent 来源文件的输入流。
     *
     * @return 来源文件的输入流，由调用方关闭
     * @throws IOException 打开来源文件失败时抛出
     */
    public InputStream open() throws IOException {
        return source.openRead(sourcePath);
    }

    /**
     * 返回来源对应的本地文件路径。
     *
     * @return 本地来源路径；来源没有对应的本地文件时返回 {@code null}
     */
    public Path getFilePath() {
        Optional<Path> path = source.getLocalPath(sourcePath);
        return path.orElse(null);
    }
}
