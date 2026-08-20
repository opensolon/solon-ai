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
package org.noear.solon.ai.llm.dialect.openai;

import org.noear.snack4.ONode;
import org.noear.snack4.codec.TypeRef;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.embedding.Embedding;
import org.noear.solon.ai.embedding.EmbeddingConfig;
import org.noear.solon.ai.embedding.EmbeddingException;
import org.noear.solon.ai.embedding.EmbeddingResponse;
import org.noear.solon.ai.embedding.dialect.AbstractEmbeddingDialect;

import java.util.List;

/**
 * OpenAi 嵌入模型方言
 *
 * @author noear
 * @since 3.1
 */
public class OpenaiEmbeddingDialect extends AbstractEmbeddingDialect {
    private static OpenaiEmbeddingDialect instance = new OpenaiEmbeddingDialect();

    public static OpenaiEmbeddingDialect getInstance() {
        return instance;
    }

    /**
     * 是否为默认
     */
    @Override
    public boolean isDefault() {
        return true;
    }

    @Override
    public boolean matched(EmbeddingConfig config) {
        return false;
    }

    @Override
    public EmbeddingResponse parseResponseJson(EmbeddingConfig config, String respJson) {
        ONode oResp = ONode.ofJson(respJson);

        String model = oResp.get("model").getString();

        if (oResp.hasKey("error")) {
            return new EmbeddingResponse(model, new EmbeddingException(OpenaiDialectSupport.extractErrorMessage(oResp.get("error"))), null, null);
        } else {
            // 防御：部分兼容端点异常形态可能缺失 data 数组
            ONode dataNode = oResp.getOrNull("data");
            List<Embedding> data = (dataNode != null && dataNode.isArray())
                    ? dataNode.toBean(new TypeRef<List<Embedding>>() {
                    })
                    : null;
            AiUsage usage = null;

            if (oResp.hasKey("usage")) {
                ONode oUsage = oResp.get("usage");
                long prompt_tokens = oUsage.get("prompt_tokens").getLong();
                long completion_tokens = oUsage.get("completion_tokens").getLong();
                // 官方 SDK 中 total_tokens 为 optional，缺省时用输入+输出兜底
                long total_tokens = oUsage.hasKey("total_tokens")
                        ? oUsage.get("total_tokens").getLong() : (prompt_tokens + completion_tokens);

                usage = new AiUsage(prompt_tokens, 0L, completion_tokens, total_tokens, oUsage);
            }

            return new EmbeddingResponse(model, null, data, usage);
        }
    }
}
