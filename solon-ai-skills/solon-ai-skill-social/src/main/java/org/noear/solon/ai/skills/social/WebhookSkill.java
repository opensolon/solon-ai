package org.noear.solon.ai.skills.social;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.annotation.Param;
import org.noear.solon.lang.Preview;

/**
 * 通用 Webhook 技能：用于触发 Jenkins、极狐 GitLab 或其他自定义 API
 */
@Preview("3.9.1")
public class WebhookSkill extends AbsWebhookSkill {
    public WebhookSkill(String webhookUrl) { super(webhookUrl); }

    @Override public String name() { return "webhook_executor"; }

    @Override public boolean isSupported(Prompt prompt) { return true; }

    @ToolMapping(name = "execute_webhook", description = "触发自定义 Webhook 回调（通常用于触发 CI/CD 流或外部自动化系统）。")
    public String execute(@Param("payload") String payload) {
        try {
            String res = postJson(payload);
            return "外部响应: " + res;
        } catch (Exception e) {
            return "触发失败: " + e.getMessage();
        }
    }
}