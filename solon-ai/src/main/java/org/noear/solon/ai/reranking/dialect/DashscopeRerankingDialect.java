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

import org.noear.snack.ONode;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.reranking.*;

import java.util.ArrayList;
import java.util.List;

/**
 * DashScope 重排模型方言（阿里云产品）
 *
 * @author noear
 * @since 3.1
 */

public class DashscopeRerankingDialect extends AbstractRerankingDialect {
    //https://help.aliyun.com/zh/model-studio/developer-reference

    private static DashscopeRerankingDialect instance = new DashscopeRerankingDialect();

    public static DashscopeRerankingDialect getInstance() {
        return instance;
    }

    /**
     * 匹配检测
     *
     * @param config 聊天配置
     */
    @Override
    public boolean matched(RerankingConfig config) {
        return "dashscope".equals(config.getProvider());

    }

    @Override
    public RerankingResponse parseResponseJson(RerankingConfig config, String respJson) {
        ONode oResp = ONode.load(respJson);

        String model = oResp.get("model").getString();

        if (oResp.contains("error")) {
            return new RerankingResponse(model, new RerankingException(oResp.get("error").getString()), null, null);
        } else {
            List<Reranking> results = new ArrayList<>();

            for (ONode n1 : oResp.get("output").get("results").ary()) {
                Reranking r1 = new Reranking(
                        n1.get("index").getInt(),
                        n1.get("document").get("text").getString(),
                        n1.get("relevance_score").getFloat());

                results.add(r1);
            }

            AiUsage usage = null;

            if (oResp.contains("usage")) {
                ONode oUsage = oResp.get("usage");
                int total_tokens = oUsage.get("total_tokens").getInt();
                usage = new AiUsage(
                        total_tokens,
                        0,
                        total_tokens
                );
            }

            return new RerankingResponse(model, null, results, usage);
        }
    }
}