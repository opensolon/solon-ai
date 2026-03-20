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
package org.noear.solon.ai.util;

import org.noear.solon.core.util.Assert;
import org.yaml.snakeyaml.Yaml;

import java.util.List;

/**
 * 有元数据（YamlFrontmatter）的 markdown 文档工具
 *
 * @author noear
 * @since 3.9.6
 */
public class MarkdownUtil {

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
                    // 静默处理或记录日志
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
}