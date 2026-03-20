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
package org.noear.solon.ai.agent.util;

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
        Markdown markdown = new Markdown();

        if (Assert.isEmpty(markdownLines)) {
            return markdown;
        }

        // 检查是否有 Frontmatter 标记
        if (markdownLines.size() > 2 && "---".equals(markdownLines.get(0).trim())) {
            int endSeparatorIndex = -1;
            for (int i = 1; i < markdownLines.size(); i++) {
                if ("---".equals(markdownLines.get(i).trim())) {
                    endSeparatorIndex = i;
                    break;
                }
            }

            if (endSeparatorIndex > 0) {
                List<String> metaLines = markdownLines.subList(1, endSeparatorIndex);
                String metadataStr = String.join("\n", metaLines);

                try {
                    Yaml yaml = new Yaml();
                    Object loaded = yaml.load(metadataStr);
                    if (loaded != null) {
                        // 将 Map 转换为 ONode
                        markdown.metadata.fill(loaded);
                    }
                } catch (Exception e) {
                    // 建议记录日志，防止 YAML 格式错误导致解析崩溃
                }

                // 修正索引：获取第二个 --- 之后的所有内容
                if (endSeparatorIndex + 1 < markdownLines.size()) {
                    List<String> contentLines = markdownLines.subList(endSeparatorIndex + 1, markdownLines.size());
                    markdown.content = String.join("\n", contentLines).trim();
                }

                return markdown;
            }
        }

        // 处理没有元数据的情况
        markdown.content = String.join("\n", markdownLines);
        return markdown;
    }
}