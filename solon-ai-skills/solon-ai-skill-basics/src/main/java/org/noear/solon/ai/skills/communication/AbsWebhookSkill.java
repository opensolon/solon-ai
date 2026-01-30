package org.noear.solon.ai.skills.communication;

import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.net.http.HttpUtils;

public abstract class AbsWebhookSkill extends AbsSkill {
    protected final String webhookUrl;
    public AbsWebhookSkill(String webhookUrl) { this.webhookUrl = webhookUrl; }

    protected String postJson(String json) throws Exception {
        return HttpUtils.http(webhookUrl)
                .header("Content-Type", "application/json;charset=utf-8")
                .timeout(10, 30, 30)
                .bodyJson(json)
                .post();
    }
}