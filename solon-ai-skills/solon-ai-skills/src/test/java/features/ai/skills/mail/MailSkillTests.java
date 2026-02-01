package features.ai.skills.mail;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.skills.mail.MailSkill;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;

/**
 * 邮件技能单元测试：使用 Mock 模式避免真实网络请求
 */
public class MailSkillTests {

    private final String workDir = "./test_workspace";
    private final String toMail = "user@example.com";

    private MailSkill mailSkill;

    @BeforeEach
    public void setup() throws IOException {
        // 1. 准备沙箱目录
        Path root = Paths.get(workDir);
        if (Files.exists(root)) {
            Files.walk(root).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
        Files.createDirectories(root);

        // 2. 初始化技能（使用 Mock 服务器参数）
        // 在 Simple Java Mail 中，如果检测到环境或显式指定，可以使用 MockMailer
        mailSkill = new MailSkill(workDir,
                "smtp.mock.com",
                25,
                "ai@test.com",
                "password");
    }

    @Test
    public void testSendPlainTextEmail() {
        String to = toMail;
        String subject = "测试邮件";
        String body = "这是一封纯文本邮件。";

        // 执行发送
        String result = mailSkill.sendEmail(to, subject, body, null);

        // 验证返回状态
        Assertions.assertTrue(result.contains("成功"));
    }

    @Test
    public void testSendHtmlEmail() {
        String to = toMail;
        String subject = "HTML 邮件测试";
        String body = "<h1>你好</h1><p>这是一个<b>HTML</b>正文。</p>";

        String result = mailSkill.sendEmail(to, subject, body, null);

        Assertions.assertTrue(result.contains("成功"));
    }

    @Test
    public void testSendEmailWithAttachment() throws IOException {
        // 1. 在沙箱中创建一个临时附件
        String fileName = "report.txt";
        Path filePath = Paths.get(workDir, fileName);
        Files.write(filePath, "附件内容: Hello Solon AI".getBytes());

        // 2. 发送带附件的邮件
        String result = mailSkill.sendEmail(toMail, "周报", "请查收附件", fileName);

        // 3. 验证
        Assertions.assertTrue(result.contains("成功"));
    }

    @Test
    public void testSecurityPathAccess() {
        // 验证非法路径访问（尝试读取系统文件作为附件）
        Assertions.assertThrows(SecurityException.class, () -> {
            mailSkill.sendEmail("test@test.com", "Hack", "Body", "../../../etc/passwd");
        });
    }
}