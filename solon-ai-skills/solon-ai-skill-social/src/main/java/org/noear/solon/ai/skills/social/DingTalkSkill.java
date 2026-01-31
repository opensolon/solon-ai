package org.noear.solon.ai.skills.social;

import org.noear.snack4.ONode;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.net.URLEncoder;

/**
 * 钉钉专家技能：支持文本、Markdown 以及 @ 提醒
 */
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