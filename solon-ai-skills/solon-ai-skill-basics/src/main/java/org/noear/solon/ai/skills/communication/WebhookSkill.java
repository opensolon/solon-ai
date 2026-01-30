package org.noear.solon.ai.skills.communication;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

/** 通用 Webhook 技能 */
public class WebhookSkill extends AbsWebhookSkill {
    public WebhookSkill(String webhookUrl) { super(webhookUrl); }
    @Override public String name() { return "webhook_executor"; }

    @ToolMapping(name = "execute_webhook", description = "触发自定义 Webhook 回调（通常用于触发 Jenkins 或外部系统流）")
    public String execute(@Param("payload") String payload) {
        try { return "外部响应: " + postJson(payload); }
        catch (Exception e) { return "触发失败: " + e.getMessage(); }
    }
}