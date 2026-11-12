/*
 * Copyright 2017-2026 noear.org and authors
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
package org.noear.solon.ai.agent.react.intercept.compress;

import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.i18n.AgentI18n;
import org.noear.solon.ai.agent.i18n.AgentI18nKeys;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.CompressionStrategy;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 向量检索增强压缩策略 (RAG-based Memory Strategy)
 * 核心逻辑：将裁减的消息持久化到向量库，并提取关键词或最新压缩结果作为上下文索引。
 *
 * @author noear
 * @since 3.9.4
 */
public class VectorStoreCompressionStrategy extends AbsTalent implements CompressionStrategy {
    private static final Logger log = LoggerFactory.getLogger(VectorStoreCompressionStrategy.class);

    private final RepositoryStorable vectorRepository;
    private final Map<Locale, Collection<FunctionTool>> localizedTools = new ConcurrentHashMap<>();

    public VectorStoreCompressionStrategy(RepositoryStorable vectorRepository) {
        this.vectorRepository = vectorRepository;

        //默认语言的工具同时登记到内部工具表（兼容 getToolAry()/getToolMap()）
        Locale defLocale = AgentI18n.getDefaultLocale();
        Collection<FunctionTool> defTools = buildTools(defLocale);
        defTools.forEach(this::internalAddTool);
        localizedTools.put(defLocale, defTools);
    }

    /**
     * 获取归档通知文本（压缩产物）
     *
     * @param locale 语言（null 时使用默认语言）
     * @since 4.2
     */
    public static String getArchivedNotice(Locale locale) {
        return AgentI18n.fmt(locale, AgentI18nKeys.COMPRESS_VECTOR_ARCHIVED_NOTICE, "archivedTag", AgentI18n.get(locale, AgentI18nKeys.COMPRESS_VECTOR_ARCHIVED_TAG));
    }

    /**
     * 是否包含归档标记（识别所有内置语言，兼容会话中切换语言）
     *
     * <p>标记文本与指令中提到的标记来自同一 key（compress.vector.archived_tag）。</p>
     *
     * @param content 消息文本
     * @since 4.2
     */
    public static boolean isArchivedNotice(String content) {
        if (content == null) {
            return false;
        }
        for (String tag : CompressionUtil.textsOfAllLocales(null, AgentI18nKeys.COMPRESS_VECTOR_ARCHIVED_TAG)) {
            if (content.contains("--- [" + tag)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String description(Prompt prompt) {
        return AgentI18n.get(AgentI18n.localeOf(prompt), AgentI18nKeys.COMPRESS_VECTOR_DESCRIPTION);
    }

    @Override
    public String getInstruction(Prompt prompt) {
        Locale l = AgentI18n.localeOf(prompt);
        return AgentI18n.fmt(l, AgentI18nKeys.COMPRESS_VECTOR_INSTRUCTION, "archivedTag", AgentI18n.get(l, AgentI18nKeys.COMPRESS_VECTOR_ARCHIVED_TAG));
    }

    /**
     * 工具按请求语言构建并缓存：描述、参数描述与工具返回文案都绑定同一语言。
     *
     * <p>策略实例会被多个请求共用，故不在字段里保存语言，而是每种语言一套工具（处理器闭包捕获语言）。</p>
     */
    @Override
    public Collection<FunctionTool> getTools(Prompt prompt) {
        Locale l = AgentI18n.resolve(AgentI18n.localeOf(prompt));
        return localizedTools.computeIfAbsent(l, this::buildTools);
    }

    private Collection<FunctionTool> buildTools(Locale l) {
        FunctionTool tool = new FunctionToolDesc("recall_history")
                .title("")
                .returnType(String.class)
                .description(AgentI18n.get(l, AgentI18nKeys.COMPRESS_VECTOR_TOOL_DESCRIPTION))
                .paramAdd("query", String.class, AgentI18n.get(l, AgentI18nKeys.COMPRESS_VECTOR_TOOL_PARAM_QUERY))
                .paramAdd("limit", int.class, true, AgentI18n.get(l, AgentI18nKeys.COMPRESS_VECTOR_TOOL_PARAM_LIMIT), "3")
                .doHandle(args -> recall(l,
                        args.get("query") == null ? null : String.valueOf(args.get("query")),
                        toLimit(args.get("limit")),
                        args.get(ChatSession.ATTR_SESSIONID) == null ? null : String.valueOf(args.get(ChatSession.ATTR_SESSIONID))));

        return Collections.singletonList(tool);
    }

    private static int toLimit(Object val) {
        if (val instanceof Number) {
            return Math.max(1, ((Number) val).intValue());
        }
        if (val != null) {
            try {
                return Math.max(1, (int) Double.parseDouble(String.valueOf(val).trim()));
            } catch (NumberFormatException ignore) {
                //非法值按默认数量处理
            }
        }
        return 3;
    }

    // --- Tool 实现（负责"取"） ---

    /**
     * 回溯历史（返回文案使用默认语言）
     */
    public String recall(String query, int limit, String __sessionId) {
        return recall(null, query, limit, __sessionId);
    }

    /**
     * 回溯历史（返回文案使用指定语言）
     *
     * @since 4.2
     */
    public String recall(Locale locale, String query, int limit, String __sessionId) {
        if (Assert.isEmpty(query)) {
            return AgentI18n.get(locale, AgentI18nKeys.COMPRESS_VECTOR_RECALL_EMPTY_QUERY);
        }

        // sessionId 防护：白名单校验优于转义。不同向量库的 filterExpression 方言对元字符
        // （\ ; # $ {} 等）处理不一，仅转义单引号不够鲁棒。系统生成的 sessionId
        // 本就仅包含字母、数字、-_。不符合白名单则拒绝查询，从根本上消除表达式注入面。
        if (__sessionId == null || !isSafeSessionId(__sessionId)) {
            log.warn("Recall history rejected: invalid sessionId format: {}", __sessionId);
            return AgentI18n.get(locale, AgentI18nKeys.COMPRESS_VECTOR_RECALL_INVALID_SESSION);
        }

        QueryCondition condition = new QueryCondition(query)
                .filterExpression("sessionId == '" + __sessionId + "'")
                .limit(limit);

        try {
            List<Document> docs = vectorRepository.search(condition);

            if (docs.isEmpty()) {
                return AgentI18n.fmt(locale, AgentI18nKeys.COMPRESS_VECTOR_RECALL_NOT_FOUND, "query", query);
            }

            return docs.stream()
                    .map(d -> {
                        String time = d.getMetadataAs("timestamp");
                        return (time != null ? "[" + time + "] " : "") + d.getContent();
                    })
                    .collect(Collectors.joining("\n\n---\n\n"));

        } catch (Throwable e) {
            log.error("Recall history failed. SessionId: {}, Query: {}", __sessionId, query, e);
            return AgentI18n.get(locale, AgentI18nKeys.COMPRESS_VECTOR_RECALL_FAILED);
        }
    }

    /**
     * 校验 sessionId 是否为安全白名单格式（仅允许字母、数字、连字符、下划线、点、冒号）。
     * <p>系统生成的会话标识（如 UUID / 雪花算法 ID）均落在此集合内；任何含引号、
     * 反斜杠、分号、{@code #$\{}} 等表达式元字符的输入均被拒绝，从根本上消除注入面。
     */
    private boolean isSafeSessionId(String sessionId) {
        if (Assert.isEmpty(sessionId) || sessionId.length() > 128) {
            return false;
        }
        for (int i = 0; i < sessionId.length(); i++) {
            char c = sessionId.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == ':';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ChatMessage compress(ChatModel chatModel, int maxRetries, ReActTrace trace, List<ChatMessage> messagesToCompress) {
        if (messagesToCompress == null || messagesToCompress.isEmpty()) {
            return null;
        }

        // 归档按会话存：跟随本轮（trace）的语言
        Locale msgLocale = CompressionUtil.localeOf(trace);

        // 使用 CompressionUtil 统一格式化消息（超长内容自动截断）
        String archivedContent = messagesToCompress.stream()
                .filter(m -> !m.hasMetadata(AgentTrace.META_FIRST))
                .map(m -> CompressionUtil.formatMessageForCompression(m,
                        CompressionUtil.DEFAULT_MAX_TOOL_RESULT_LENGTH, msgLocale))
                .collect(Collectors.joining("\n"));

        if (Assert.isEmpty(archivedContent.trim())) return null;

        try {
            // 封装为高质量 Document
            Document doc = new Document(archivedContent);
            doc.metadata("sessionId", trace.getSession().getSessionId());
            doc.metadata("timestamp", System.currentTimeMillis());
            doc.metadata("type", "execution_log");

            // 同步保存到向量库
            vectorRepository.save(doc);

            // 返回一个紧凑的系统通知
            return ChatMessage.ofUser(getArchivedNotice(msgLocale));

        } catch (Throwable e) {
            log.error("Failed to archive to vector store", e);
            return null;
        }
    }
}
