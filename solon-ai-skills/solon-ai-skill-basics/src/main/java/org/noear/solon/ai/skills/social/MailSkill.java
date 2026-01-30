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
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;
import org.noear.solon.net.http.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

/**
 * 邮件发送技能：基于 Web API 驱动，不依赖 Jakarta/JavaMail 接口
 * 兼容 Java 8 ~ Java 25
 *
 * @author noear
 * @since 3.9.1
 */
public class MailSkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(MailSkill.class);

    private final MailDriver driver;
    private final String apiKey;
    private final Path rootPath;

    public MailSkill(MailDriver driver, String apiKey, String workDir) {
        this.driver = driver;
        this.apiKey = apiKey;
        this.rootPath = Paths.get(workDir).toAbsolutePath().normalize();
    }

    @Override
    public String name() { return "mail_tool"; }

    @Override
    public String description() { return "邮件专家：通过云服务发送邮件，支持添加本地附件。"; }

    @Override
    public boolean isSupported(Prompt prompt) {
        String content = prompt.getUserContent();
        // 仅在明确需要发送或交付时激活
        return content.matches(".*(发邮件|发送|邮箱|email|mail).*");
    }

    @ToolMapping(name = "send_email", description = "发送邮件给指定收件人")
    public String sendEmail(@Param("to") String to,
                            @Param("subject") String subject,
                            @Param("content") String content,
                            @Param("attachmentFileName") String attachmentFileName) {
        if (to == null || to.isEmpty()) return "错误：收件人不能为空";

        try {
            Path attachment = null;
            if (attachmentFileName != null && !attachmentFileName.isEmpty()) {
                attachment = resolvePath(attachmentFileName);
            }

            return driver.send(to, (subject == null ? "No Subject" : subject), content, attachment, apiKey);
        } catch (Exception e) {
            LOG.error("Mail send failed: {}", to, e);
            return "发送失败: " + e.getMessage();
        }
    }

    private Path resolvePath(String fileName) {
        Path p = rootPath.resolve(fileName).normalize();
        if (!p.startsWith(rootPath)) {
            throw new SecurityException("非法路径访问：" + fileName);
        }
        return p;
    }

    // --- 驱动接口定义 ---
    public interface MailDriver {
        String send(String to, String subject, String content, Path attachment, String apiKey) throws Exception;
    }

    // --- 驱动实现：QQ 邮件（中转模式） ---
    public static final MailDriver QQ = (to, subject, content, attachment, key) -> {
        String[] auth = key.split(":");
        if (auth.length < 2) throw new IllegalArgumentException("API Key 格式应为 'sender:token'");

        ONode body = new ONode().set("from", auth[0]).set("to", to).set("subject", subject).set("text", content);

        if (attachment != null && Files.exists(attachment)) {
            String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(attachment));
            body.getOrNew("attachment").set("name", attachment.getFileName().toString()).set("data", base64);
        }

        return HttpUtils.http("https://api.your-mail-gateway.com/send")
                .header("Authorization", "Bearer " + auth[1])
                .bodyJson(body.toJson()).post();
    };

    // --- 驱动实现：Resend ---
    public static final MailDriver RESEND = (to, subject, content, attachment, key) -> {
        ONode body = new ONode().set("from", "AI-Assistant <onboarding@resend.dev>")
                .set("subject", subject).set("text", content);
        body.getOrNew("to").add(to);

        if (attachment != null && Files.exists(attachment)) {
            String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(attachment));
            body.getOrNew("attachments").add(new ONode()
                    .set("filename", attachment.getFileName().toString())
                    .set("content", base64));
        }

        String json = HttpUtils.http("https://api.resend.com/emails")
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .bodyJson(body.toJson()).post();

        ONode res = ONode.ofJson(json);
        return res.hasKey("id") ? "邮件已通过 Resend 发送: " + res.get("id").getString() : "发送异常: " + json;
    };
}