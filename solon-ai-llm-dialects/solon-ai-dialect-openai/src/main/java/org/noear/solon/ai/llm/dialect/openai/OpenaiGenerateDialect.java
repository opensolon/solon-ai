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

import org.noear.snack.ONode;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.generate.dialect.AbstractGenerateDialect;
import org.noear.solon.ai.generate.GenerateConfig;
import org.noear.solon.ai.generate.GenerateException;
import org.noear.solon.ai.generate.GenerateResponse;
import org.noear.solon.ai.media.Image;

import java.util.Arrays;
import java.util.List;

/**
 * @author noear
 * @since 3.1
 */
public class OpenaiGenerateDialect extends AbstractGenerateDialect {
    private static OpenaiGenerateDialect instance = new OpenaiGenerateDialect();

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
        ONode oResp = ONode.load(respJson);

        String model = oResp.get("model").getString();

        if (oResp.contains("error")) {
            return new GenerateResponse(model, new GenerateException(oResp.get("error").getString()), null, null);
        } else {
            List<Image> data = null;

            if (oResp.contains("task_id")) {
                //异步模式只返回任务 id
                String url = config.getTaskUrlAndId(oResp.get("task_id").getString());
                data = Arrays.asList(Image.ofUrl(url));
            } else if (oResp.contains("data")) {
                //同步模式直接有结果
                data = oResp.get("data").toObjectList(Image.class);
            }

            AiUsage usage = null;
            if (oResp.contains("usage")) {
                ONode oUsage = oResp.get("usage");
                usage = new AiUsage(
                        oUsage.get("prompt_tokens").getInt(),
                        0,
                        oUsage.get("total_tokens").getInt()
                );
            }

            return new GenerateResponse(model, null, data, usage);
        }
    }
}
