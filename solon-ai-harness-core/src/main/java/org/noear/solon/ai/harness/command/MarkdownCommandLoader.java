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
package org.noear.solon.ai.harness.command;

import org.noear.solon.ai.mount.source.FindOptions;
import org.noear.solon.ai.mount.source.MountEntry;
import org.noear.solon.ai.mount.source.MountSource;
import org.noear.solon.ai.util.Markdown;
import org.noear.solon.ai.util.MarkdownUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Arrays;

/**
 * 从挂载来源的命令目录加载 Markdown 自定义命令（本地目录用 {@code FileMountSource.of(dir)}）
 *
 * @author noear
 * @since 4.2.0
 */
public class MarkdownCommandLoader {
    private static final Logger LOG = LoggerFactory.getLogger(MarkdownCommandLoader.class);

    /**
     * 从挂载来源的目录（含子目录）加载 .md 命令：{@code deploy/staging.md → deploy:staging}。
     *
     * <p>来源可以是本地目录、db 或 http；单个文件读取失败只跳过该文件。</p>
     *
     * @param source   挂载来源
     * @param dir      来源内的命令目录（空串表示来源根）
     * @param registry 注册表
     */
    public static void loadFrom(MountSource source, String dir, CommandRegistry registry) {
        if (source == null) {
            return;
        }
        String base;
        List<MountEntry> entries;
        try {
            base = source.normalize(dir);
            if (!source.isDirectory(base)) {
                return;
            }
            entries = source.find(base, FindOptions.builder().glob("**.md").filesOnly(true)
                    .maxEntries(Integer.MAX_VALUE).build());
        } catch (IOException | RuntimeException e) {
            LOG.warn("Failed to load commands from {}:{}: {}", source.getLocation(), dir, e.getMessage());
            return;
        }

        for (MountEntry entry : entries) {
            String relative = base.isEmpty() ? entry.getPath() : entry.getPath().substring(base.length() + 1);
            try {
                String text = new String(source.readBytes(entry.getPath()), StandardCharsets.UTF_8);
                Markdown md = MarkdownUtil.resolve(Arrays.asList(text.split("\r?\n", -1)));

                String name = relative.substring(0, relative.length() - 3).replace('/', ':');
                registry.register(new MarkdownCommand(name, md.getContent(), md.getDescription(),
                        md.getMeta("model").getString()));
            } catch (IOException | RuntimeException e) {
                LOG.warn("Failed to read command file {}: {}", entry.getPath(), e.getMessage());
            }
        }
    }
}
