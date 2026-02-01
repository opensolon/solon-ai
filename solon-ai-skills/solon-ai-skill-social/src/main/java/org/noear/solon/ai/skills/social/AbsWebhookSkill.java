/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.skills.social;

import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.lang.Preview;
import org.noear.solon.net.http.HttpUtils;

/**
 * Webhook 技能基类
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public abstract class AbsWebhookSkill extends AbsSkill {
    protected final String webhookUrl;

    public AbsWebhookSkill(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    /**
     * 发送 JSON 数据到默认 Webhook 地址
     */
    protected String postJson(String json) throws Exception {
        return postJson(this.webhookUrl, json);
    }

    /**
     * 发送 JSON 数据到指定 URL（用于处理带签名的动态 URL）
     */
    protected String postJson(String url, String json) throws Exception {
        return HttpUtils.http(url)
                .header("Content-Type", "application/json;charset=utf-8")
                .timeout(10, 30, 30)
                .bodyOfJson(json)
                .post();
    }
}