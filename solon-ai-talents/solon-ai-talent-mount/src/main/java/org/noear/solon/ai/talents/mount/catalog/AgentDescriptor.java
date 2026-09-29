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

    private AgentDescriptor(String mountAlias, MountSource source, String sourcePath, Markdown markdown) {
        this.name = markdown.getName();
        this.description = markdown.getDescription() == null ? "" : markdown.getDescription();
        this.mountAlias = mountAlias;
        this.source = source;
        this.sourcePath = source.normalize(sourcePath);
        this.markdown = markdown;
    }

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

    public String getName() { return name; }
    public String getDescription() { return description; }
    public Markdown getMarkdown() { return markdown; }
    public String getMountAlias() { return mountAlias; }
    public MountSource getSource() { return source; }
    public String getSourcePath() { return sourcePath; }

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
