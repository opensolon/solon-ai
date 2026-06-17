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
package org.noear.solon.ai.talents.memory;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * MemoryTalent：基于自演进心智模型的长期记忆才能
 *
 * 遵从 MemoryTalent 论文核心：Extract (提取), Consolidate (整合), Prune (修剪), Search (检索)
 *
 * @author noear
 * @since 3.9.4
 */
@Preview("3.9.4")
public class MemoryTalent extends AbsTalent {
    private static final Logger LOG = LoggerFactory.getLogger(MemoryTalent.class);

    /** 列出全部时的最大返回条数，避免上下文膨胀 */
    private static final int LIST_ALL_LIMIT = 100;
    /** 列目录摘要长度，控制单条占用 */
    private static final int BRIEF_LEN = 50;

    /** 画像注入：相关性检索条数 */
    private static final int INJECT_RELEVANT = 5;
    /** 画像注入：热记忆兜底条数 */
    private static final int INJECT_HOT = 3;
    /** 默认搜索返回条数 */
    private static final int SEARCH_TOPK_DEFAULT = 5;
    /** 近似 Key 探测：相似条目提示阈值 */
    private static final int NEAR_KEY_PROBE = 3;
    /** 碎片密度检测：同类低分(Imp<5)碎片数超此值时提示整合 */
    private static final int FRAGMENT_HINT_THRESHOLD = 5;

    private final MemorySolutionProvider solutionProvider;
    private boolean sessionIsolation = false; // 默认会话不隔离
    private boolean relevanceInjection = true; // 默认按“相关性+热度”混合注入画像

    public MemoryTalent(MemorySolutionProvider solutionProvider) {
        this.solutionProvider = solutionProvider;
    }

    /**
     * 设置是否启用会话隔离
     */
    public MemoryTalent sessionIsolation(boolean sessionIsolation) {
        this.sessionIsolation = sessionIsolation;
        return this;
    }

    /**
     * 设置画像注入策略。
     *
     * <p>true（默认）：按当前用户输入做语义检索 + 热记忆兜底混合注入，注入内容与当前对话强相关；
     * false：仅注入 Top 热记忆（旧行为，记忆量极大或检索有较高延迟时可用）。
     */
    public MemoryTalent relevanceInjection(boolean relevanceInjection) {
        this.relevanceInjection = relevanceInjection;
        return this;
    }

    private String getUserId(String __sessionId) {
        if (sessionIsolation) {
            return __sessionId == null ? "tmp" : __sessionId;
        } else {
            return "shared";
        }
    }

    private String getNow() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @Override
    public String description() {
        return "长期记忆专家：负责用户心智模型的提取、演进、冲突消解与深度检索。";
    }

    /**
     * 充当 Designer 引导逻辑，动态加载心智模型
     */
    @Override
    public String getInstruction(Prompt prompt) {
        String __cwd = prompt.attrAs("__cwd");

        MemorySearcher searchProvider = solutionProvider.get(__cwd).getSearcher();

        String mentalModel = "";

        if (searchProvider != null) {
            String __sessionId = prompt.attrAs(ChatSession.ATTR_SESSIONID);
            String userId = getUserId(__sessionId);

            // 混合注入：先按当前用户输入取语义相关记忆，再用热记忆兜底，按 Key 去重
            // （相关性优先，避免高重要度但与当前话题无关的记忆挤占上下文）
            Map<String, MemorySearchResult> merged = new LinkedHashMap<>();
            try {
                if (relevanceInjection) {
                    String userContent = prompt.getUserContent();
                    if (Utils.isNotEmpty(userContent)) {
                        // 当前用户输入仅作为检索 query，不拼接进任何可执行上下文
                        for (MemorySearchResult r : searchProvider.search(userId, userContent, INJECT_RELEVANT)) {
                            merged.putIfAbsent(r.getKey(), r);
                        }
                    }
                }
                for (MemorySearchResult r : searchProvider.getHotMemories(userId, INJECT_HOT)) {
                    merged.putIfAbsent(r.getKey(), r);
                }
            } catch (Exception e) {
                LOG.warn("MemoryTalent getInstruction inject error", e);
            }

            if (!merged.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (MemorySearchResult r : merged.values()) {
                    sb.append(String.format("- [%s] %s: %s (Imp: %.2f)\n",
                            r.getTime(), r.getKey(), r.getContent(), r.getImportance()));
                }
                mentalModel = sb.toString();
            }
        }

        String scope = sessionIsolation ? "当前会话私有空间" : "全局公共知识库";

        return "## 长期记忆与心智演进指南 (存储域: " + scope + ")\n" +
                "你拥有自主维护用户心智模型的能力。请实时提取对话中的价值点，并维持认知的一致性。\n\n" +
                "### 1. 当前核心认知预览：\n" +
                (Assert.isEmpty(mentalModel) ? "- (暂无核心认知，请通过交流逐步构建用户画像)" : mentalModel) +
                "\n\n### 2. 记忆提取评分标准 (importance)：\n" +
                "- **1-3 (琐碎事实)**：临时的任务细节、单次提及的背景（如：当前处理的文件名）。\n" +
                "- **4-6 (行为偏好)**：用户展现出的习惯、常用的工具偏好、反复提及的关注点。\n" +
                "- **7-9 (核心规约)**：项目的架构定义、长期的技术选型、用户明确要求的行为准则。\n" +
                "- **10 (生命周期关键点)**：足以改变后续所有对话逻辑的重大发现或用户身份定论。\n\n" +
                "### 3. 认知维护指令：\n" +
                "- **发现冲突时**：若新事实与“核心认知预览”冲突，必须调用 `memory_extract` 更新，并根据返回的 `[认知对比]` 向用户确认或在回复中体现认知的修正。\n" +
                "- **碎片过多时**：当你发现检索到多个关于同一主题的低分记录（Imp < 5），应主动调用 `memory_consolidate` 将其升维为一条高分偏好（Imp >= 7）。\n" +
                "- **列出全部时**：当用户问“记住了哪些/有哪些记忆”时，调用 `memory_search('*')` 获取全部条目索引（Key + 摘要），需要细节再用 `memory_recall` 按 Key 召回。\n" +
                "- **时效性原则**：永远以时间戳（Time）最近的认知记录为准。";
    }


    /**
     * EXTRACT & UPDATE: 提取与覆盖
     * 解决了记忆冲突与反思逻辑
     */
    @ToolMapping(name = "memory_extract",
            description = "将事实、偏好或进度存入用户心智模型（或者用户要求记住时）。若存在同名 Key，系统将返回旧记录以供你对比反思。")
    public String extract(@Param("key") String key,
                          @Param("fact") String fact,
                          @Param(value = "importance", description = "权重(1-10)：1-3琐碎事实, 4-6偏好习惯, 7-9核心规约, 10重大身份定论") int importance,
                          String __cwd,
                          String __sessionId) {
        String userId = getUserId(__sessionId);

        MemorySolution memorySolution = solutionProvider.get(__cwd);
        MemoryStorer storeProvider = memorySolution.getStorer();
        MemorySearcher searchProvider = memorySolution.getSearcher();

        try {
            String oldJson = storeProvider.get(userId, key);
            String now = getNow();

            StringBuilder feedback = new StringBuilder("【操作成功】心智模型已更新。");
            if (Utils.isNotEmpty(oldJson)) {
                ONode old = ONode.ofJson(oldJson);
                feedback.append("\n[认知对比] 发现历史记录：")
                        .append("\n- 旧内容: ").append(old.get("content").getString())
                        .append("\n- 旧时间: ").append(old.get("time").getString())
                        .append("\n请对比新旧信息差异。若发生改变，请在后续对话中体现出你的认知进化。");
            }

            Map<String, Object> data = new HashMap<>();
            data.put("content", fact);
            data.put("time", now);
            data.put("importance", importance);

            // 动态 TTL：重要信息（>=5）保留 30 天，核心总结（>=10）永久或极长，普通信息 7 天
            int ttl;
            if (importance >= 10) ttl = -1; // 核心经验永不过期
            else if (importance >= 5) ttl = 2592000;
            else ttl = 604800;

            storeProvider.put(userId, key, ONode.serialize(data), ttl);

            if (searchProvider != null) {
                searchProvider.updateIndex(userId, key, fact, importance, now);

                // M3.1 近似 Key 探测：用 fact 检索是否已存在同主题但不同 Key 的条目，抑制碎片化
                // （只在新建 Key 时提示；同名覆盖已由上方认知对比处理）
                if (Utils.isEmpty(oldJson)) {
                    appendNearKeyHint(feedback, searchProvider, userId, key, fact);
                }

                // M4.1 碎片密度检测：低分碎片过多时提示整合
                appendFragmentHint(feedback, searchProvider, userId);
            }

            return feedback.toString();
        } catch (Exception e) {
            LOG.error("MemoryTalent extract error", e);
            return "存储异常。";
        }
    }

    /**
     * SEARCH: 语义搜索
     */
    @ToolMapping(name = "memory_search",
            description = "语义检索：通过自然语言描述在心智模型中寻找相关的记忆碎片，辅助找回背景信息。传入 '*' 可列出全部记忆条目的索引（Key + 摘要），用于回答“记住了哪些”。")
    public String search(@Param("query") String query,
                         @Param(value = "topK", required = false, defaultValue = "5", description = "返回条数上限（默认 5）。需要更全面的召回可适当调高。") Integer topK,
                         String __cwd,
                         String __sessionId) {
        String userId = getUserId(__sessionId);
        MemorySearcher searchProvider = solutionProvider.get(__cwd).getSearcher();

        if (searchProvider == null) {
            return "搜索适配器未配置。";
        }

        // 约定符 '*'：列出全部记忆条目索引（走 listAll，不做重要度过滤，低分碎片也能列出）
        if ("*".equals(query.trim())) {
            List<MemorySearchResult> all = searchProvider.listAll(userId, LIST_ALL_LIMIT);
            if (all.isEmpty()) {
                return "当前心智模型为空，尚未记录任何认知。";
            }

            StringBuilder sb = new StringBuilder("当前共记录以下认知条目（如需完整细节，请用 memory_recall 按 Key 召回）：\n");
            for (MemorySearchResult res : all) {
                sb.append(String.format("- [%s] (Key: %s) Imp:%.2f: %s\n",
                        Utils.isNotEmpty(res.getTime()) ? res.getTime() : "未知时间",
                        res.getKey(), res.getImportance(), briefOf(res.getContent())));
            }
            if (all.size() >= LIST_ALL_LIMIT) {
                sb.append("（仅展示前 ").append(LIST_ALL_LIMIT).append(" 条，更多请按主题检索）\n");
            }
            return sb.toString();
        }

        int limit = (topK == null || topK <= 0) ? SEARCH_TOPK_DEFAULT : topK;
        List<MemorySearchResult> results = searchProvider.search(userId, query, limit);
        if (results.isEmpty()) {
            return "未发现相关认知片段。";
        }

        StringBuilder sb = new StringBuilder("匹配到以下认知参考（建议优先参考时间戳较近的记录）：\n");
        for (MemorySearchResult res : results) {
            sb.append(String.format("- [%s] (Key: %s): %s\n",
                    Utils.isNotEmpty(res.getTime()) ? res.getTime() : "未知时间", res.getKey(), res.getContent()));
        }
        return sb.toString();
    }

    private static String briefOf(String content) {
        if (content == null) {
            return "";
        }
        String s = content.replace("\n", " ").trim();
        return s.length() > BRIEF_LEN ? s.substring(0, BRIEF_LEN) + "…" : s;
    }

    /**
     * M3.1：探测是否存在与新 fact 语义高度相似、但 Key 不同的已存条目，
     * 命中时在 feedback 附加“疑似已存 Key”提示，引导 LLM 复用 Key 而非新建。
     */
    private void appendNearKeyHint(StringBuilder feedback, MemorySearcher searchProvider,
                                   String userId, String currentKey, String fact) {
        try {
            List<MemorySearchResult> similar = searchProvider.search(userId, fact, NEAR_KEY_PROBE);
            List<String> related = new ArrayList<>();
            for (MemorySearchResult r : similar) {
                if (!currentKey.equals(r.getKey())) {
                    related.add(r.getKey());
                }
            }
            if (!related.isEmpty()) {
                feedback.append("\n[Key 治理] 检测到语义相近的已存条目：").append(related)
                        .append("。若属同一主题，建议更新已有 Key 而非新建，避免记忆碎片化。");
            }
        } catch (Exception e) {
            LOG.warn("MemoryTalent appendNearKeyHint error", e);
        }
    }

    /**
     * M4.1：统计低分（Imp<5）碎片数，超阈值时在 feedback 附加整合建议，把被动变半主动。
     */
    private void appendFragmentHint(StringBuilder feedback, MemorySearcher searchProvider, String userId) {
        try {
            List<MemorySearchResult> all = searchProvider.listAll(userId, LIST_ALL_LIMIT);
            int fragments = 0;
            for (MemorySearchResult r : all) {
                if (r.getImportance() < 5) {
                    fragments++;
                }
            }
            if (fragments >= FRAGMENT_HINT_THRESHOLD) {
                feedback.append("\n[维护建议] 当前累计 ").append(fragments)
                        .append(" 条低分碎片(Imp<5)，如存在同主题可调用 memory_consolidate 升维为高分偏好。");
            }
        } catch (Exception e) {
            LOG.warn("MemoryTalent appendFragmentHint error", e);
        }
    }

    /**
     * RECALL: 精确召回
     */
    @ToolMapping(name = "memory_recall", description = "精确召回：通过 Key 获取该认知条目的完整细节。")
    public String recall(@Param("key") String key,
                         String __cwd,
                         String __sessionId) {
        String userId = getUserId(__sessionId);
        MemoryStorer storeProvider = solutionProvider.get(__cwd).getStorer();

        try {
            String val = storeProvider.get(userId, key);

            if (Utils.isEmpty(val)) {
                return "未找到认知条目 [" + key + "]。";
            }

            ONode node = ONode.ofJson(val);
            return String.format("【认知详情】内容：%s | 记录时间：%s | 重要度：%s",
                    node.get("content").getString(), node.get("time").getString(), node.get("importance").getString());
        } catch (Exception e) {
            return "读取异常。";
        }
    }

    /**
     * CONSOLIDATE: 知识整合
     * 对齐 MemoryTalent 的“压缩”思想，将事实进化为经验
     */
    @ToolMapping(name = "memory_consolidate",
            description = "认知升维：将多个低层事实碎片整合为高层偏好模型，并清理冗余碎片。")
    public String consolidate(@Param("keys_to_merge") List<String> oldKeys,
                              @Param("new_key") String newKey,
                              @Param("evolved_insight") String insight,
                              String __cwd,
                              String __sessionId) {
        // 原论文精神：通过整合减少上下文占用，提高信噪比
        String userId = getUserId(__sessionId);
        String fact = "[Evolved Insight] " + insight;

        // 步骤1：写入新的合并洞察
        try {
            extract(newKey, fact, 10, __cwd, __sessionId); // 核心洞察赋予最高重要度
        } catch (Exception e) {
            LOG.error("MemoryTalent consolidate extract error, newKey={}", newKey, e);
            return "【合并异常】新洞察写入失败，旧碎片保留：" + e.getMessage();
        }

        // 步骤2：逐个清理旧碎片（即使某个失败也不影响其余）
        List<String> failedKeys = new ArrayList<>();
        for (String k : oldKeys) {
            try {
                prune(k, __cwd, __sessionId); // 彻底清理旧碎片，防止语义干扰
                LOG.info("MemoryTalent consolidate prune ok, userId={}, key={}", userId, k);
            } catch (Exception e) {
                LOG.error("MemoryTalent consolidate prune error, userId={}, key={}", userId, k, e);
                failedKeys.add(k);
            }
        }

        if (failedKeys.isEmpty()) {
            return "【心智进化成功】已将碎片认知升维为核心洞察，删除了" + oldKeys.size() + "条冗余记录。";
        } else {
            return "【心智进化部分成功】新洞察已写入，但以下碎片清理失败：" + failedKeys + "。可再次调用 memory_prune 清理。";
        }
    }

    /**
     * PRUNE: 记忆修剪
     */
    @ToolMapping(name = "memory_prune", description = "认知修正：删除错误、重复或过时的认知。")
    public String prune(@Param("key") String key,
                        String __cwd,
                        String __sessionId) {
        String userId = getUserId(__sessionId);
        MemoryStorer storeProvider = solutionProvider.get(__cwd).getStorer();
        MemorySearcher searchProvider = solutionProvider.get(__cwd).getSearcher();

        try {
            storeProvider.remove(userId, key);
        } catch (Exception e) {
            LOG.error("MemoryTalent prune remove error, userId={}, key={}", userId, key, e);
            return "清理失败 Key: " + key + "，原因：" + e.getMessage();
        }

        if (searchProvider != null) {
            try {
                searchProvider.removeIndex(userId, key);
            } catch (Exception e) {
                LOG.error("MemoryTalent prune removeIndex error, userId={}, key={}", userId, key, e);
            }
        }

        return "已从模型中清理 Key: " + key;
    }

    public static boolean isMemoryTool(String toolName) {
        if (toolName != null && toolName.startsWith("memory_")) {
            return true;
        }

        return false;
    }
}