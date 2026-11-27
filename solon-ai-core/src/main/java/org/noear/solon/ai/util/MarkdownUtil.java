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
package org.noear.solon.ai.util;

import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 有元数据（YamlFrontmatter）的 markdown 文档工具
 *
 * @author noear
 * @since 3.9.6
 */
public class MarkdownUtil {
    private static final Logger LOG = LoggerFactory.getLogger(MarkdownUtil.class);

    /**
     * 分析有元数据（YamlFrontmatter）的 markdown 文档
     */
    public static Markdown resolve(List<String> markdownLines) {
        return resolve(markdownLines, false);
    }

    /**
     * 分析有元数据（YamlFrontmatter）的 markdown 文档
     *
     * @param onlyFrontmatter 只解析原数据
     */
    public static Markdown resolve(List<String> markdownLines, boolean onlyFrontmatter) {
        Markdown markdown = new Markdown();
        if (Assert.isEmpty(markdownLines)) {
            return markdown;
        }

        int endSeparatorIndex = -1;

        // 1. 尝试寻找 Frontmatter 边界
        // 必须以 --- 开头，且至少有 3 行（---, 内容, ---）
        if (markdownLines.size() > 2 && "---".equals(markdownLines.get(0).trim())) {
            for (int i = 1; i < markdownLines.size(); i++) {
                if ("---".equals(markdownLines.get(i).trim())) {
                    endSeparatorIndex = i;
                    break;
                }
            }
        }

        // 2. 如果找到了元数据边界，解析它
        if (endSeparatorIndex > 0) {
            // 只有在 endSeparatorIndex > 0 时才说明有有效的 Yaml 块
            List<String> metaLines = markdownLines.subList(1, endSeparatorIndex);
            if (!metaLines.isEmpty()) {
                String metadataStr = String.join("\n", metaLines);
                try {
                    Object loaded = new Yaml().load(metadataStr);
                    if (loaded != null) {
                        markdown.metadata.fill(loaded);
                    }
                } catch (Exception e) {
                    // 元数据整段丢弃（正文照常解析）：告警，否则配置写错（如未加引号的 *）会无声失效
                    LOG.warn("Markdown frontmatter is not valid YAML and was ignored: {}", e.getMessage());
                }
            }
        }

        // 3. 处理正文 (Content)
        if (!onlyFrontmatter) {
            int contentStartIndex = (endSeparatorIndex > 0) ? endSeparatorIndex + 1 : 0;
            if (contentStartIndex < markdownLines.size()) {
                List<String> contentLines = markdownLines.subList(contentStartIndex, markdownLines.size());
                // 使用 trim() 去掉首尾多余换行
                markdown.content = String.join("\n", contentLines).trim();
            }
        }

        return markdown;
    }

    /**
     * 生成有元数据（YamlFrontmatter）的 markdown 文档
     *
     * <p>标量写成一行（字符串加双引号并转义）；列表与 Map 按 YAML 写出（标量列表为一行 {@code [a, b]}，
     * 嵌套的列表 / Map 分行），由 {@link #resolve(List)} 读回后与原值一致。值为 null 的键不写。</p>
     *
     * @param metadata 元数据（值为标量、List、Map 及其嵌套）
     * @param content  正文（null 表示无正文）
     */
    public static String toMarkdownWithMetadata(Map<String, Object> metadata, String content) {
        StringBuilder buf = new StringBuilder();
        buf.append("---\n");
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }

            if (value instanceof Collection || value instanceof Map) {
                buf.append(new Yaml().dump(Collections.singletonMap(entry.getKey(), value)));
            } else {
                buf.append(entry.getKey()).append(": ").append(toYamlValue(value)).append("\n");
            }
        }
        buf.append("---\n\n");
        if (content != null) {
            buf.append(content);
        }
        return buf.toString();
    }

    private static String toYamlValue(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }

        String str = String.valueOf(value);
        return "\"" + str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n") + "\"";
    }
}