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
package org.noear.solon.ai.reranking.dialect;

import org.noear.solon.ai.reranking.RerankingConfig;
import org.noear.solon.core.util.ClassUtil;
import org.noear.solon.core.util.RankEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 重排模型方言管理
 *
 * @author noear
 */
public class RerankingDialectManager {
    private static List<RankEntity<RerankingDialect>> dialects = new ArrayList<>();
    private static RerankingDialect defaultDialect;

    static {
        register(ClassUtil.tryInstance("org.noear.solon.ai.llm.dialect.dashscope.DashscopeRerankingDialect"));
        register(ClassUtil.tryInstance("org.noear.solon.ai.llm.dialect.openai.OpenaiRerankingDialect"));
    }

    /**
     * 选择方言
     */
    public static RerankingDialect select(RerankingConfig config) {
        for (RankEntity<RerankingDialect> d : dialects) {
            if (d.target.matched(config)) {
                return d.target;
            }
        }

        return defaultDialect;
    }

    /**
     * 注册方言
     */
    public static void register(RerankingDialect dialect) {
        register(dialect, 0);
    }

    /**
     * 注册方言
     */
    public static void register(RerankingDialect dialect, int index) {
        if (dialect != null) {
            dialects.add(new RankEntity<>(dialect, index));
            Collections.sort(dialects);

            if (defaultDialect == null || dialect.isDefault()) {
                defaultDialect = dialect;
            }
        }
    }

    /**
     * 注销方言
     */
    public static void unregister(RerankingDialect dialect) {
        dialects.removeIf(rankEntity -> rankEntity.target == dialect);
    }
}