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
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.reranking.Reranking;
import org.noear.solon.ai.reranking.RerankingConfig;
import org.noear.solon.ai.reranking.RerankingException;
import org.noear.solon.ai.reranking.RerankingResponse;
import org.noear.solon.ai.reranking.dialect.AbstractRerankingDialect;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAi 重排模型方言
 *
 * @author noear
 * @since 3.1
 */
public class OpenaiRerankingDialect extends AbstractRerankingDialect {
    private static OpenaiRerankingDialect instance = new OpenaiRerankingDialect();

    public static OpenaiRerankingDialect getInstance() {
        return instance;
    }

    @Override
    public boolean isDefault() {
        return true;
    }

    @Override
    public boolean matched(RerankingConfig config) {
        return false;
    }

    @Override
    public RerankingResponse parseResponseJson(RerankingConfig config, String respJson) {
        ONode oResp = ONode.ofJson(respJson);

        String model = oResp.get("model").getString();

        if (oResp.hasKey("error")) {
            return new RerankingResponse(model, new RerankingException(OpenaiDialectSupport.extractErrorMessage(oResp.get("error"))), null, null);
        } else {
            List<Reranking> results = new ArrayList<>();

            // 防御：部分兼容端点异常形态可能缺失 results 数组，避免 NPE
            ONode resultsNode = oResp.getOrNull("results");
            if (resultsNode != null && resultsNode.isArray()) {
                for (ONode n1 : resultsNode.getArray()) {
                    // document 可能缺失或非对象形态（部分兼容端点），做空值防御
                    String documentText = null;
                    ONode document = n1.getOrNull("document");
                    if (document != null) {
                        if (document.isObject()) {
                            documentText = document.get("text").getString();
                        } else if (document.isValue()) {
                            documentText = document.getString();
                        }
                    }

                    Reranking r1 = new Reranking(
                            n1.get("index").getInt(),
                            documentText,
                            n1.get("relevance_score").getFloat());

                    results.add(r1);
                }
            }

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

            return new RerankingResponse(model, null, results, usage);
        }
    }
}
