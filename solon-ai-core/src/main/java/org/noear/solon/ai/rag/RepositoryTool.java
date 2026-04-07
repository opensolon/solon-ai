/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.rag;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.tool.AbsToolProvider;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.ai.reranking.RerankingModel;
import org.noear.solon.annotation.Param;
import org.noear.solon.lang.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * 知识库检索工具（协助实现 Agent RAG，从 “被动检索” 到 “主动寻知”）
 *
 * @author noear
 * @since 3.10.1
 */
public class RepositoryTool extends AbsToolProvider {
    private final Repository repository;
    private final @Nullable RerankingModel rerankingModel;

    public RepositoryTool(Repository repository) {
        this(repository, null);
    }

    public RepositoryTool(Repository repository, @Nullable RerankingModel rerankingModel) {
        Objects.requireNonNull(repository, "repository");

        this.repository = repository;
        this.rerankingModel = rerankingModel;
    }

    @ToolMapping(name = "repository_query", description = "知识库检索工具。当需要查询特定领域知识、文档或背景资料时调用。")
    public String query(
            @Param(value = "queries", description = "查询词列表（1个或多个，最多不要超过5个）") List<String> queries,
            @Param(value = "topK", defaultValue = "3", description = "返回结果的数") int topK
    ) throws IOException {
        if (queries == null || queries.isEmpty()) {
            return "提示：未提供有效的查询关键词。";
        }

        // 限制单次查询词数量，防止模型一次性搜太多导致上下文溢出
        int maxQueries = Math.min(queries.size(), 5);

        StringBuilder sb = new StringBuilder("### 多重检索结果汇总\n\n");
        for (int i = 0; i < maxQueries; i++) {
            String q = queries.get(i);
            if (q == null || q.trim().isEmpty()) {
                continue;
            }

            QueryCondition condition = new QueryCondition(q).limit(topK > 0 ? topK : 3);

            List<Document> result = repository.search(condition);
            if (rerankingModel != null) {
                result = rerankingModel.rerank(q, result);
            }

            sb.append("#### 针对关键词 [").append(q).append("] 的结果：\n");
            sb.append(formatResults(result));
            sb.append("\n---\n");
        }
        return sb.toString();
    }

    private String formatResults(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return "  > 未找到相关的知识库内容。\n";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);

            // 使用二级列表项 (Markdown 缩进)，清晰划分不同来源
            sb.append("  - **来源 [").append(i + 1).append("]**\n");

            // 1. 标题 (如果有)
            String title = doc.getTitle();
            if (title != null && !title.isEmpty()) {
                sb.append("    - 标题: ").append(title).append("\n");
            }

            // 2. 相关度评分 (保留2位小数)
            sb.append(String.format("    - 相关度: %.2f\n", doc.getScore()));

            // 3. 内容摘要 (增加长度截断保护)
            String content = doc.getContent();
            if (content != null) {
                content = content.trim();
                // 建议截断长度设为 2000 左右，既能覆盖大部分场景，又不至于挤占过多上下文
                if (content.length() > 2000) {
                    content = content.substring(0, 2000) + "... (内容过长已截断)";
                }
                sb.append("    - 内容: ").append(content).append("\n");
            }

            // 4. 引用链接
            String url = doc.getUrl();
            if (url != null && !url.isEmpty()) {
                sb.append("    - 引用地址: ").append(url).append("\n");
            }

            sb.append("\n"); // 来源条目间的间距
        }

        return sb.toString();
    }
}