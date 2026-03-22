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
package org.noear.solon.ai.skills.memory;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.skill.AbsSkill;
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
 * MemSkill：基于自演进心智模型的长期记忆技能
 *
 * 遵从 MemSkill 论文核心：Extract (提取), Consolidate (整合), Prune (修剪), Search (检索)
 * 通过 Designer 指令引导 Agent 实现认知的自我更新。
 *
 * @author noear
 * @since 3.9.4
 */
@Preview("3.9.4")
public class MemSkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(MemSkill.class);
    private static final String BASE_PREFIX = "ai:memskill:";

    private final MemStoreProvider storeProvider;
    //private final RedisClient redis;
    private final MemSearchProvider searchProvider;
    private boolean sessionIsolation = true; // 默认开启会话隔离

    public MemSkill(MemStoreProvider storeProvider, MemSearchProvider searchProvider) {
        //this.redis = redis;
        this.searchProvider = searchProvider;
        this.storeProvider = storeProvider;
    }

    /**
     * 设置是否启用会话隔离
     */
    public MemSkill sessionIsolation(boolean sessionIsolation) {
        this.sessionIsolation = sessionIsolation;
        return this;
    }

    private String getEffectiveSessionId(String __sessionId) {
        if (sessionIsolation) {
            return __sessionId == null ? "tmp" : __sessionId;
        } else {
            return "shared";
        }
    }

    private String getSessoinId(Prompt prompt) {
        if (sessionIsolation) {
            return prompt.attrOrDefault(ChatSession.ATTR_SESSIONID, "tmp");
        } else {
            return "shared";
        }
    }

    private String getFinalKey(String __sessionId, String key) {
        return BASE_PREFIX + __sessionId + ":" + key;
    }

    private String getNow() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @Override
    public String name() {
        return "mem_skill";
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
        String mentalModel = "";
        if (searchProvider != null) {
            String sessionId = getSessoinId(prompt);
            List<MemSearchResult> hot = searchProvider.getHotMemories(sessionId, 8);
            if (!hot.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (MemSearchResult r : hot) {
                    sb.append(String.format("- [%s] %s: %s (Imp: %d)\n",
                            r.getTime(), r.getKey(), r.getContent(), r.getImportance()));
                }
                mentalModel = sb.toString();
            }
        }

        String scope = sessionIsolation ? "用户私人空间" : "全局公共知识库";

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
                "- **发现冲突时**：若新事实与“核心认知预览”冲突，必须调用 `mem_extract` 更新，并根据返回的 `[认知对比]` 向用户确认或在回复中体现认知的修正。\n" +
                "- **碎片过多时**：当你发现检索到多个关于同一主题的低分记录（Imp < 5），应主动调用 `mem_consolidate` 将其升维为一条高分偏好（Imp >= 7）。\n" +
                "- **时效性原则**：永远以时间戳（Time）最近的认知记录为准。";
    }


    /**
     * EXTRACT & UPDATE: 提取与覆盖
     * 解决了记忆冲突与反思逻辑
     */
    @ToolMapping(name = "mem_extract",
            description = "将事实、偏好或进度存入用户心智模型。若存在同名 Key，系统将返回旧记录以供你对比反思。")
    public String extract(@Param("key") String key,
                          @Param("fact") String fact,
                          @Param(value = "importance", description = "权重(1-10)：1-3琐碎事实, 4-6偏好习惯, 7-9核心规约, 10重大身份定论") int importance,
                          String __sessionId) {
        __sessionId = getEffectiveSessionId(__sessionId);

        try {
            String finalKey = getFinalKey(__sessionId, key);
            String oldJson = storeProvider.get(finalKey); //redis.getBucket().get(finalKey);
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

            storeProvider.put(finalKey, ONode.serialize(data), ttl);
            //redis.getBucket().store(finalKey, ONode.serialize(data), ttl);

            if (searchProvider != null) {
                searchProvider.updateIndex(__sessionId, key, fact, importance, now);
            }

            return feedback.toString();
        } catch (Exception e) {
            LOG.error("MemSkill extract error", e);
            return "存储异常。";
        }
    }

    /**
     * SEARCH: 语义搜索
     */
    @ToolMapping(name = "mem_search",
            description = "语义检索：通过自然语言描述在心智模型中寻找相关的记忆碎片，辅助找回背景信息。")
    public String search(@Param("query") String query, String __sessionId) {
        if (searchProvider == null) {
            return "搜索适配器未配置。";
        }

        __sessionId = getEffectiveSessionId(__sessionId);

        List<MemSearchResult> results = searchProvider.search(__sessionId, query, 3);
        if (results.isEmpty()) {
            return "未发现相关认知片段。";
        }

        StringBuilder sb = new StringBuilder("匹配到以下认知参考（建议优先参考时间戳较近的记录）：\n");
        for (MemSearchResult res : results) {
            sb.append(String.format("- [%s] (Key: %s): %s\n",
                    Utils.isNotEmpty(res.getTime()) ? res.getTime() : "未知时间", res.getKey(), res.getContent()));
        }
        return sb.toString();
    }

    /**
     * RECALL: 精确召回
     */
    @ToolMapping(name = "mem_recall", description = "精确召回：通过 Key 获取该认知条目的完整细节。")
    public String recall(@Param("key") String key, String __sessionId) {
        __sessionId = getEffectiveSessionId(__sessionId);

        try {
            String val = storeProvider.get(getFinalKey(__sessionId, key));
            //redis.getBucket().get(getFinalKey(__sessionId, key));
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
     * 对齐 MemSkill 的“压缩”思想，将事实进化为经验
     */
    @ToolMapping(name = "mem_consolidate",
            description = "认知升维：将多个低层事实碎片整合为高层偏好模型，并清理冗余碎片。")
    public String consolidate(@Param("keys_to_merge") List<String> oldKeys,
                              @Param("new_key") String newKey,
                              @Param("evolved_insight") String insight,
                              String __sessionId) {
        __sessionId = getEffectiveSessionId(__sessionId);

        // 原论文精神：通过整合减少上下文占用，提高信噪比
        String fact = "[Evolved Insight] " + insight;
        extract(newKey, fact, 10, __sessionId); // 核心洞察赋予最高重要度

        for (String k : oldKeys) {
            prune(k, __sessionId); // 彻底清理旧碎片，防止语义干扰
        }

        return "【心智进化成功】已将碎片认知升维为核心洞察，删除了冗余记录。";
    }

    /**
     * PRUNE: 记忆修剪
     */
    @ToolMapping(name = "mem_prune", description = "认知修正：删除错误、重复或过时的认知。")
    public String prune(@Param("key") String key, String __sessionId) {
        __sessionId = getEffectiveSessionId(__sessionId);

        storeProvider.remove(getFinalKey(__sessionId, key));
        //redis.getBucket().remove(getFinalKey(__sessionId, key));
        if (searchProvider != null) {
            searchProvider.removeIndex(__sessionId, key);
        }

        return "已从模型中清理 Key: " + key;
    }
}