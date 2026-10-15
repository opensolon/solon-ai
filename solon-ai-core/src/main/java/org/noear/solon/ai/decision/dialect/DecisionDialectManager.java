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
package org.noear.solon.ai.decision.dialect;

import org.noear.solon.ai.decision.DecisionConfig;
import org.noear.solon.core.util.ClassUtil;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 决策模型方言管理
 *
 * @author devlau
 * @since 4.1.1
 */
@Preview("4.1.1")
public class DecisionDialectManager {
    private static final Logger log = LoggerFactory.getLogger(DecisionDialectManager.class);
    private static final List<RankEntity<DecisionDialect>> dialects = new ArrayList<>();
    private static DecisionDialect defaultDialect;

    static {
        register(ClassUtil.tryInstance("org.noear.solon.ai.llm.dialect.ollama.OllamaDecisionDialect"));
        register(ClassUtil.tryInstance("org.noear.solon.ai.llm.dialect.openai.OpenaiDecisionDialect"));
    }

    /**
     * 按优先级选择方言，未匹配时使用默认方言；未注册方言时为 null
     */
    @Nullable
    public static DecisionDialect select(DecisionConfig config) {
        for (RankEntity<DecisionDialect> dialect : dialects) {
            if (dialect.target.matched(config)) {
                return dialect.target;
            }
        }
        return defaultDialect;
    }

    /**
     * 注册方言
     */
    public static void register(DecisionDialect dialect) {
        register(dialect, 0);
    }

    /**
     * 按优先级注册方言
     */
    public static void register(DecisionDialect dialect, int index) {
        if (dialect != null) {
            dialects.add(new RankEntity<>(dialect, index));
            Collections.sort(dialects);
            if (defaultDialect == null || dialect.isDefault()) {
                defaultDialect = dialect;
            }
            log.debug("Register decision dialect: {}", dialect.getClass());
        }
    }

    /**
     * 注销方言，同时清理默认方言引用
     */
    public static void unregister(DecisionDialect dialect) {
        dialects.removeIf(entity -> entity.target == dialect);
        if (defaultDialect == dialect) {
            defaultDialect = null;
            for (RankEntity<DecisionDialect> entity : dialects) {
                if (defaultDialect == null || entity.target.isDefault()) {
                    defaultDialect = entity.target;
                }
            }
        }
    }
}
