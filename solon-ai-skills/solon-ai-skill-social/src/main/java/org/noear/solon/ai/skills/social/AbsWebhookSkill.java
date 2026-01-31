package org.noear.solon.ai.skills.social;

import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.lang.Preview;
import org.noear.solon.net.http.HttpUtils;

/**
 * Webhook 技能基类
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
                .bodyJson(json)
                .post();
    }
}