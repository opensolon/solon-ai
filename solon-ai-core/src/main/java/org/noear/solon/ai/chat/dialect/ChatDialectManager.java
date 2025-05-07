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
package org.noear.solon.ai.chat.dialect;

import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.core.util.ClassUtil;
import org.noear.solon.core.util.RankEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 聊天模型方言管理
 *
 * @author noear
 */
public class ChatDialectManager {
    static final Logger log = LoggerFactory.getLogger(ChatDialectManager.class);

    private static List<RankEntity<ChatDialect>> dialects = new ArrayList<>();
    private static ChatDialect defaultDialect;

    static {
        register(ClassUtil.tryInstance("org.noear.solon.ai.llm.dialect.dashscope.DashscopeChatDialect"));
        register(ClassUtil.tryInstance("org.noear.solon.ai.llm.dialect.ollama.OllamaChatDialect"));
        register(ClassUtil.tryInstance("org.noear.solon.ai.llm.dialect.openai.OpenaiChatDialect"));
    }

    /**
     * 选择聊天方言
     *
     * @param config 聊天配置
     */
    public static ChatDialect select(ChatConfig config) {
        for (RankEntity<ChatDialect> d : dialects) {
            if (d.target.matched(config)) {
                return d.target;
            }
        }

        return defaultDialect;
    }

    /**
     * 注册聊天方言
     *
     * @param dialect 聊天方言
     */
    public static void register(ChatDialect dialect) {
        register(dialect, 0);
    }

    /**
     * 注册方言
     *
     * @param dialect 聊天方言
     * @param index   顺序位（匹配执行顺序）
     */
    public static void register(ChatDialect dialect, int index) {
        if (dialect != null) {
            dialects.add(new RankEntity<>(dialect, index));
            Collections.sort(dialects);

            if (defaultDialect == null || dialect.isDefault()) {
                defaultDialect = dialect;
            }

            log.debug("Register chat dialect: {}", dialect.getClass());
        }
    }

    /**
     * 注销方言
     *
     * @param dialect 聊天方言
     */
    public static void unregister(ChatDialect dialect) {
        dialects.removeIf(rankEntity -> rankEntity.target == dialect);
    }
}