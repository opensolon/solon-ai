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
import java.net.URLEncoder;

/**
 * 钉钉社交技能：为 AI 提供即时通讯工具的推送与触达能力。
 *
 * <p>该技能基于钉钉自定义机器人 Webhook 实现，具备以下特性：
 * <ul>
 * <li><b>智能消息路由</b>：根据参数自动切换 {@code text}（纯文本）或 {@code markdown}（富文本）模式。</li>
 * <li><b>增强安全性</b>：内置 HmacSHA256 签名算法，支持钉钉机器人的“加签”安全设置。</li>
 * <li><b>精准提醒</b>：集成 {@code atMobiles} 逻辑，支持 AI 在群聊中通过手机号精确 @ 目标用户。</li>
 * <li><b>异步汇报场景</b>：非常适合作为 Agent 完成长时任务（如报表生成、代码审查）后的最后一步通知。</li>
 * </ul>
 * </p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class DingTalkSkill extends AbsWebhookSkill {
    private final String secret;

    public DingTalkSkill(String webhookUrl) {
        this(webhookUrl, null);
    }

    public DingTalkSkill(String webhookUrl, String secret) {
        super(webhookUrl);
        this.secret = secret;
    }

    @Override
    public String name() { return "dingtalk_tool"; }

    @Override
    public String description() {
        return "钉钉助手：支持向指定群聊发送文本消息或 Markdown 格式的报告。";
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        String content = prompt.getUserContent().toLowerCase();
        return content.contains("钉钉") || content.contains("ding");
    }

    @ToolMapping(name = "send_dingtalk", description = "发送钉钉消息。text 支持 Markdown 语法，atMobiles 为可选手机号列表（逗号分隔）。")
    public String send(@Param("title") String title,
                       @Param("text") String text,
                       @Param("atMobiles") String atMobiles) {
        try {
            ONode data = new ONode();

            // 1. 根据 title 是否存在智能判断消息类型（钉钉 Markdown 必须有 title）
            if (Assert.isNotEmpty(title)) {
                data.set("msgtype", "markdown");
                data.getOrNew("markdown").set("title", title).set("text", text);
            } else {
                data.set("msgtype", "text");
                data.getOrNew("text").set("content", text);
            }

            // 2. 处理 @ 提醒逻辑
            if (Assert.isNotEmpty(atMobiles)) {
                ONode atNode = data.getOrNew("at");
                for (String mobile : atMobiles.split(",")) {
                    atNode.getOrNew("atMobiles").add(mobile.trim());
                }
            }

            // 3. 执行发送（使用计算后的签名 URL）
            String res = postJson(getSignUrl(), data.toJson());
            return res.contains("\"errcode\":0") ? "发送成功" : "发送失败: " + res;
        } catch (Exception e) {
            return "发送异常: " + e.getMessage();
        }
    }

    private String getSignUrl() throws Exception {
        if (Assert.isEmpty(secret)) return webhookUrl;

        long timestamp = System.currentTimeMillis();
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"));
        byte[] signData = mac.doFinal(stringToSign.getBytes("UTF-8"));
        String sign = URLEncoder.encode(Base64.getEncoder().encodeToString(signData), "UTF-8");

        return webhookUrl + "&timestamp=" + timestamp + "&sign=" + sign;
    }
}