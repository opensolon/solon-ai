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

import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

/**
 * 飞书助手技能：支持文本、富文本卡片以及 @ 提醒
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class FeishuSkill extends AbsWebhookSkill {
    private final String secret;

    public FeishuSkill(String webhookUrl) {
        this(webhookUrl, null);
    }

    public FeishuSkill(String webhookUrl, String secret) {
        super(webhookUrl);
        this.secret = secret;
    }

    @Override
    public String name() { return "lark_tool"; }

    @Override
    public String description() {
        return "飞书助手：支持向飞书群聊发送文本消息或交互式卡片报告。";
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        String content = prompt.getUserContent().toLowerCase();
        return content.contains("飞书") || content.contains("lark") || content.contains("feishu");
    }

    @ToolMapping(name = "send_lark", description = "发送飞书消息。title 可选（填了则发送卡片消息），text 支持飞书特有的 Markdown 语法。")
    public String send(@Param("title") String title,
                       @Param("text") String text) {
        try {
            ONode data = new ONode();

            // 1. 处理签名逻辑（飞书签名放在 JSON Body 中）
            if (Assert.isNotEmpty(secret)) {
                long timestamp = System.currentTimeMillis() / 1000;
                data.set("timestamp", String.valueOf(timestamp));
                data.set("sign", genSign(secret, timestamp));
            }

            // 2. 根据是否有 title 决定发送普通文本还是交互式卡片
            if (Assert.isNotEmpty(title)) {
                data.set("msg_type", "interactive");
                ONode card = data.getOrNew("card");

                // 卡片标题栏
                card.getOrNew("header").getOrNew("title").set("tag", "plain_text").set("content", title);
                card.getOrNew("header").set("template", "blue"); // 标题栏颜色

                // 卡片正文（使用 lark_md 类型支持更多格式）
                ONode element = card.getOrNew("elements").addNew();
                element.set("tag", "div");
                element.getOrNew("text").set("tag", "lark_md").set("content", text);
            } else {
                data.set("msg_type", "text");
                data.getOrNew("content").set("text", text);
            }

            // 3. 执行发送
            String res = postJson(webhookUrl, data.toJson());

            // 飞书成功返回的是 {"StatusCode":0, ...} 或 {"code":0, ...}
            ONode resNode = ONode.ofJson(res);
            if (resNode.get("code").getLong() == 0 || resNode.get("StatusCode").getLong() == 0) {
                return "发送成功";
            } else {
                return "发送失败: " + res;
            }
        } catch (Exception e) {
            return "发送异常: " + e.getMessage();
        }
    }

    /**
     * 飞书签名算法：把 timestamp + "\n" + secret 做 HMAC-SHA256
     */
    private String genSign(String secret, long timestamp) throws Exception {
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signData);
    }
}