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
            return new RerankingResponse(model, new RerankingException(oResp.get("error").getString()), null, null);
        } else {
            List<Reranking> results = new ArrayList<>();

            for(ONode n1 : oResp.get("results").getArray()){
                Reranking r1 = new Reranking(
                        n1.get("index").getInt(),
                        n1.get("document").get("text").getString(),
                        n1.get("relevance_score").getFloat());

                results.add(r1);
            }

            AiUsage usage = null;

            if (oResp.hasKey("usage")) {
                ONode oUsage = oResp.get("usage");
                usage = new AiUsage(
                        oUsage.get("prompt_tokens").getInt(),
                        0,
                        oUsage.get("total_tokens").getInt()
                );
            }

            return new RerankingResponse(model, null, results, usage);
        }
    }
}
