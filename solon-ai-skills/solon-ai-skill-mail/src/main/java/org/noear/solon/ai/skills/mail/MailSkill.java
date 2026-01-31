package org.noear.solon.ai.skills.mail;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.mailer.MailerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 邮件专家技能：基于 Simple Java Mail 实现
 */
public class MailSkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(MailSkill.class);
    private final Path rootPath;
    private final Mailer mailer;
    private final String fromEmail;

    public MailSkill(String workDir, String host, int port, String username, String password) {
        this.rootPath = Paths.get(workDir).toAbsolutePath().normalize();
        this.fromEmail = username;

        // 这里的配置适配了现代安全协议，支持 Java 25 的强加密要求
        this.mailer = MailerBuilder
                .withSMTPServer(host, port, username, password)
                .withTransportStrategy(port == 465 ? TransportStrategy.SMTPS : TransportStrategy.SMTP_TLS)
                .withSessionTimeout(15 * 1000)
                .withConnectionPoolCoreSize(2) // 保持少量长连接，提升 AI 响应速度
                .buildMailer();
    }

    @Override
    public String name() { return "mail_tool"; }

    @Override
    public String description() {
        return "邮件专家：发送带有格式的正式邮件，支持 HTML 正文及本地附件挂载。";
    }

    @Override
    public boolean isSupported(Prompt prompt) { return true; }

    @ToolMapping(name = "send_email", description = "发送邮件。body 支持文本或 HTML。")
    public String sendEmail(@Param("to") String to,
                            @Param("subject") String subject,
                            @Param("body") String body,
                            @Param("attachmentFileName") String attachmentFileName) {
        try {
            EmailPopulatingBuilder builder = EmailBuilder.startingBlank()
                    .from("AI Assistant", fromEmail)
                    .to(to)
                    .withSubject(subject == null ? "No Subject" : subject);

            // 正文处理
            if (body != null && (body.contains("<") && body.contains(">"))) {
                builder.withHTMLText(body);
            } else {
                builder.withPlainText(body);
            }

            // --- 修复后的附件逻辑 ---
            if (attachmentFileName != null && !attachmentFileName.trim().isEmpty()) {
                Path path = resolvePath(attachmentFileName);
                if (Files.exists(path)) {
                    // 兼容性最好的写法
                    byte[] content = Files.readAllBytes(path);
                    builder.withAttachment(path.getFileName().toString(), content, null);
                }
            }

            mailer.sendMail(builder.buildEmail());
            return "邮件发送成功: " + to;
        } catch (Exception e) {
            LOG.error("Mail send error", e);
            return "发送异常: " + e.getMessage();
        }
    }

    private Path resolvePath(String name) {
        Path p = rootPath.resolve(name).normalize();
        if (!p.startsWith(rootPath)) throw new SecurityException("非法路径访问安全拦截");
        return p;
    }
}