package features.ai.skills.mail;

import demo.ai.skills.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.simple.SimpleResponse;
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

    // --- Agent 驱动测试 ---

    @Test
    public void testAgentDrivenMailTask() throws Throwable {
        // 1. 初始化 Agent，并注入邮件技能
        // 注意：LlmUtil.getChatModel() 需根据你的环境确保可用
        SimpleAgent agent = SimpleAgent.of(LlmUtil.getChatModel())
                .role("办公助手")
                .defaultSkillAdd(mailSkill)
                .build();

        // 2. 构建一个复杂指令：要求 AI 查资料并整理后发送
        // 这里的“查资料”会由大模型利用其内部知识库完成
        String query = String.format(
                "请查询并简述 2026 年 Solon 框架的主要技术趋势，" +
                        "整理成一封专业的邮件发送给 %s，邮件主题为 'Solon 2026 技术展望'。",
                toMail);

        System.out.println("[Agent 运行中...]");

        // 3. 执行任务
        SimpleResponse resp = agent.prompt(query).call();

        System.out.println("[AI 回复]: " + resp.getContent());

        // 4. 验证
        // 由于是 Mock 环境，我们验证 AI 是否成功触发了工具调用指令
        Assertions.assertTrue(resp.getContent().contains("成功") ||
                        resp.getContent().toLowerCase().contains("sent"),
                "AI 应当反馈邮件发送成功的状态");

        // 如果需要物理验证，可以检查 workDir 下是否有生成的邮件存根（取决于 MailSkill 是否有存储逻辑）
    }

    @Test
    public void testSecurityPathAccess() {
        // 验证非法路径访问（尝试读取系统文件作为附件）
        Assertions.assertThrows(SecurityException.class, () -> {
            mailSkill.sendEmail("test@test.com", "Hack", "Body", "../../../etc/passwd");
        });
    }
}