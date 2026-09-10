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
import org.noear.solon.Utils;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.generate.GenerateContent;
import org.noear.solon.ai.generate.dialect.AbstractGenerateDialect;
import org.noear.solon.ai.generate.GenerateConfig;
import org.noear.solon.ai.generate.GenerateException;
import org.noear.solon.ai.generate.GenerateResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OpenAI 生成方言
 *
 * @author noear
 * @since 3.5
 */
public class OpenaiGenerateDialect extends AbstractGenerateDialect {
    private static final OpenaiGenerateDialect instance = new OpenaiGenerateDialect();

    public static OpenaiGenerateDialect getInstance() {
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
    public boolean matched(GenerateConfig config) {
        return false;
    }

    @Override
    public GenerateResponse parseResponseJson(GenerateConfig config, String respJson) {
        ONode oResp = ONode.ofJson(respJson);

        String model = oResp.get("model").getString();
        if (Utils.isEmpty(model)) {
            // OpenAI ImagesResponse 不返回 model，使用请求配置补位
            model = config.getModel();
        }

        ONode errorNode = oResp.getOrNull("error");
        if (errorNode != null && !errorNode.isNull()) {
            return new GenerateResponse(model,
                    new GenerateException(OpenaiDialectSupport.extractErrorMessage(errorNode)), null, null);
        }

        List<GenerateContent> data = parseData(config, oResp);
        AiUsage usage = parseUsage(oResp.getOrNull("usage"));
        return new GenerateResponse(model, null, data, usage);
    }

    private List<GenerateContent> parseData(GenerateConfig config, ONode oResp) {
        String taskId = oResp.get("task_id").getString();
        if (Utils.isNotEmpty(taskId) && Utils.isNotEmpty(config.getTaskUrl())) {
            // 兼容异步生成端点：只返回任务 id
            return Collections.singletonList(GenerateContent.builder()
                    .url(config.getTaskUrlAndId(taskId))
                    .build());
        }

        ONode dataNode = oResp.getOrNull("data");
        if (dataNode == null || !dataNode.isArray()) {
            return null;
        }

        String defaultMimeType = toImageMimeType(oResp.get("output_format").getString());
        List<GenerateContent> contents = new ArrayList<>(dataNode.getArray().size());
        for (ONode item : dataNode.getArray()) {
            if (item == null || !item.isObject()) {
                continue;
            }

            // OpenAI Images API 使用 b64_json/revised_prompt；同时保留兼容端点的 data/text/mimeType。
            String base64 = item.get("b64_json").getString();
            if (Utils.isEmpty(base64)) {
                base64 = item.get("data").getString();
            }
            String text = item.get("revised_prompt").getString();
            if (Utils.isEmpty(text)) {
                text = item.get("text").getString();
            }
            String mimeType = item.get("mimeType").getString();
            if (Utils.isEmpty(mimeType)) {
                mimeType = defaultMimeType;
            }

            contents.add(GenerateContent.builder()
                    .text(text)
                    .data(base64)
                    .url(item.get("url").getString())
                    .mimeType(mimeType)
                    .build());
        }
        return contents;
    }

    private AiUsage parseUsage(ONode oUsage) {
        if (oUsage == null || !oUsage.isObject()) {
            return null;
        }

        // OpenAI Images API 使用 input/output_tokens；兼容旧端点的 prompt/completion_tokens。
        long inputTokens = oUsage.hasKey("input_tokens")
                ? oUsage.get("input_tokens").getLong() : oUsage.get("prompt_tokens").getLong();
        long outputTokens = oUsage.hasKey("output_tokens")
                ? oUsage.get("output_tokens").getLong() : oUsage.get("completion_tokens").getLong();
        long totalTokens = oUsage.hasKey("total_tokens")
                ? oUsage.get("total_tokens").getLong() : inputTokens + outputTokens;

        return new AiUsage(inputTokens, 0L, outputTokens, totalTokens, oUsage);
    }

    private String toImageMimeType(String outputFormat) {
        if (Utils.isEmpty(outputFormat)) {
            return null;
        }
        if ("jpg".equalsIgnoreCase(outputFormat)) {
            outputFormat = "jpeg";
        }
        return "image/" + outputFormat.toLowerCase(java.util.Locale.ROOT);
    }
}
