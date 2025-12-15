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

import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.net.http.HttpUtils;

import java.util.Map;

/**
 * Openai 聊天模型方言
 *
 * @author noear
 * @since 3.1
 */
public class GeminiChatDialect extends OpenaiChatDialect {
    /**
     * 是否为默认
     */
    @Override
    public boolean isDefault() {
        return false;
    }

    /**
     * 匹配检测
     *
     * @param config 聊天配置
     */
    @Override
    public boolean matched(ChatConfig config) {
        return "gemini".equals(config.getProvider());
    }

    @Override
    public HttpUtils createHttpUtils(ChatConfig config) {
        HttpUtils httpUtils = HttpUtils
                .http(config.getApiUrl())
                .timeout((int) config.getTimeout().getSeconds());

        if (config.getProxy() != null) {
            httpUtils.proxy(config.getProxy());
        }

        if (Utils.isNotEmpty(config.getApiKey())) {
            //它的 token 与别人传得不同
            httpUtils.header("x-goog-api-key", config.getApiKey());
        }

        httpUtils.headers(config.getHeaders());

        return httpUtils;
    }
}