package org.noear.solon.ai.skills.social;

import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.annotation.Param;

public class DingTalkSkill extends AbsWebhookSkill {
    public DingTalkSkill(String webhookUrl) { super(webhookUrl); }
    @Override public String name() { return "dingtalk_sender"; }

    @Override
    public boolean isSupported(Prompt prompt) {
        String content = prompt.getUserContent().toLowerCase();
        return content.contains("钉钉") || content.contains("ding");
    }

    @ToolMapping(name = "send_dingtalk", description = "发送钉钉群机器人消息。支持纯文本。")
    public String send(@Param("content") String content) {
        try {
            // 使用 ONode 安全构建 JSON，自动处理字符转义
            ONode data = new ONode();
            data.set("msgtype", "text");
            data.getOrNew("text").set("content", content);

            String res = postJson(data.toJson());
            return res.contains("\"errcode\":0") ? "发送成功" : "发送失败: " + res;
        } catch (Exception e) { return "发送异常: " + e.getMessage(); }
    }
}