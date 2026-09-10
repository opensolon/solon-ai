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
import org.noear.solon.ai.embedding.Embedding;
import org.noear.solon.ai.embedding.EmbeddingConfig;
import org.noear.solon.ai.embedding.EmbeddingException;
import org.noear.solon.ai.embedding.EmbeddingResponse;
import org.noear.solon.ai.embedding.dialect.AbstractEmbeddingDialect;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * OpenAi 嵌入模型方言
 *
 * @author noear
 * @since 3.1
 */
public class OpenaiEmbeddingDialect extends AbstractEmbeddingDialect {
    private static final OpenaiEmbeddingDialect instance = new OpenaiEmbeddingDialect();

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

        ONode errorNode = oResp.getOrNull("error");
        if (errorNode != null && !errorNode.isNull()) {
            return new EmbeddingResponse(model,
                    new EmbeddingException(OpenaiDialectSupport.extractErrorMessage(errorNode)), null, null);
        }

        List<Embedding> data = parseData(oResp.getOrNull("data"));
        AiUsage usage = null;

        ONode oUsage = oResp.getOrNull("usage");
        if (oUsage != null && oUsage.isObject()) {
            long promptTokens = oUsage.get("prompt_tokens").getLong();
            // OpenAI Embeddings 没有 completion_tokens；保留兼容端点返回值
            long completionTokens = oUsage.get("completion_tokens").getLong();
            long totalTokens = oUsage.hasKey("total_tokens")
                    ? oUsage.get("total_tokens").getLong() : promptTokens + completionTokens;

            usage = new AiUsage(promptTokens, 0L, completionTokens, totalTokens, oUsage);
        }

        return new EmbeddingResponse(model, null, data, usage);
    }

    private List<Embedding> parseData(ONode dataNode) {
        if (dataNode == null || !dataNode.isArray()) {
            return null;
        }

        List<Embedding> data = new ArrayList<>(dataNode.getArray().size());
        for (ONode item : dataNode.getArray()) {
            if (item == null || !item.isObject()) {
                continue;
            }

            ONode embeddingNode = item.getOrNull("embedding");
            if (embeddingNode == null || embeddingNode.isNull()) {
                continue;
            }

            float[] vector;
            if (embeddingNode.isArray()) {
                vector = embeddingNode.toBean(float[].class);
            } else {
                vector = decodeBase64Embedding(embeddingNode.getString());
            }
            data.add(new Embedding(item.get("index").getInt(), vector));
        }
        return data;
    }

    /**
     * OpenAI base64 embedding 是 little-endian IEEE-754 float32 序列。
     */
    private float[] decodeBase64Embedding(String value) {
        byte[] bytes = Base64.getDecoder().decode(value);
        if (bytes.length % Float.BYTES != 0) {
            throw new IllegalArgumentException("Invalid OpenAI base64 embedding byte length: " + bytes.length);
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }
}
