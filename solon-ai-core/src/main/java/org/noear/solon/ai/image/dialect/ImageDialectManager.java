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
package org.noear.solon.ai.image.dialect;

import org.noear.solon.ai.image.ImageConfig;
import org.noear.solon.core.util.ClassUtil;
import org.noear.solon.core.util.RankEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 图像模型方言管理
 *
 * @author noear
 */
public class ImageDialectManager {
    static final Logger log = LoggerFactory.getLogger(ImageDialectManager.class);

    private static List<RankEntity<ImageDialect>> dialects = new ArrayList<>();
    private static ImageDialect defaultDialect;

    static {
        register(ClassUtil.tryInstance("org.noear.solon.ai.llm.dialect.dashscope.DashscopeImageDialect"));
        register(ClassUtil.tryInstance("org.noear.solon.ai.llm.dialect.ollama.OllamaImageDialect"));
        register(ClassUtil.tryInstance("org.noear.solon.ai.llm.dialect.openai.OpenaiImageDialect"));
    }

    /**
     * 选择方言
     */
    public static ImageDialect select(ImageConfig config) {
        for (RankEntity<ImageDialect> d : dialects) {
            if (d.target.matched(config)) {
                return d.target;
            }
        }

        return defaultDialect;
    }

    /**
     * 注册方言
     */
    public static void register(ImageDialect dialect) {
        register(dialect, 0);
    }

    /**
     * 注册方言
     */
    public static void register(ImageDialect dialect, int index) {
        if (dialect != null) {
            dialects.add(new RankEntity<>(dialect, index));
            Collections.sort(dialects);

            if (defaultDialect == null || dialect.isDefault()) {
                defaultDialect = dialect;
            }

            log.info("Register image dialect: {}", dialect.getClass());
        }
    }

    /**
     * 注销方言
     */
    public static void unregister(ImageDialect dialect) {
        dialects.removeIf(rankEntity -> rankEntity.target == dialect);
    }
}