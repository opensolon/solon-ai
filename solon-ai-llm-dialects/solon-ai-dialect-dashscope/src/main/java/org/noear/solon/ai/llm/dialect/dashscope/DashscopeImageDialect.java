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
package org.noear.solon.ai.llm.dialect.dashscope;

import org.noear.snack4.ONode;
import org.noear.snack4.codec.TypeRef;
import org.noear.solon.Utils;
import org.noear.solon.ai.image.*;
import org.noear.solon.ai.image.dialect.AbstractImageDialect;
import org.noear.solon.ai.media.Image;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * DashScope 图型模型方言（阿里云产品）
 *
 * @author noear
 * @since 3.1
 */

public class DashscopeImageDialect extends AbstractImageDialect {
    //https://help.aliyun.com/zh/model-studio/developer-reference

    private static final String URL_PREFIX = "https://dashscope.aliyuncs.com/api/v1/services/";

    private static DashscopeImageDialect instance = new DashscopeImageDialect();

    public static DashscopeImageDialect getInstance() {
        return instance;
    }

    /**
     * 匹配检测
     *
     * @param config 聊天配置
     */
    @Override
    public boolean matched(ImageConfig config) {
        if ("dashscope".equals(config.getProvider())) {
            return true;
        } else if (config.getApiUrl().startsWith(URL_PREFIX)) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public String buildRequestJson(ImageConfig config, ImageOptions options, String promptStr, Map promptMap) {
        return new ONode().then(n -> {
            if (Utils.isNotEmpty(config.getModel())) {
                n.set("model", config.getModel());
            }

            if (Utils.isNotEmpty(promptStr)) {
                n.getOrNew("input").set("prompt", promptStr);
            } else if (Utils.isNotEmpty(promptMap)) {
                n.set("input", ONode.ofBean(promptMap));
            }

            n.getOrNew("parameters").then(n1 -> {
                for (Map.Entry<String, Object> kv : options.options().entrySet()) {
                    n1.set(kv.getKey(), kv.getValue());
                }
            });
        }).toJson();
    }

    @Override
    public ImageResponse parseResponseJson(ImageConfig config, String respJson) {
        ONode oResp = ONode.ofJson(respJson);

        String model = oResp.get("model").getString();


        //https://dashscope.aliyuncs.com/api/v1/tasks/

        if (oResp.hasKey("code") && !Utils.isEmpty(oResp.get("code").getString())) {
            return new ImageResponse(model, new ImageException(oResp.get("code").getString() + ": " + oResp.get("message").getString()), null, null);
        } else {
            List<Image> data = null;
            ONode oOutput = oResp.get("output");

            if (oOutput.hasKey("results")) {
                //同步模式直接有结果
                data = oOutput.get("results").toBean(new TypeRef<List<Image>>() { });
            } else {
                //异步模式只返回任务 id
                String url = "https://dashscope.aliyuncs.com/api/v1/tasks/" + oOutput.get("task_id").getString();
                data = Arrays.asList(Image.ofUrl(url));
            }

            return new ImageResponse(model, null, data, null);
        }
    }
}