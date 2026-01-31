package demo.ai.skills;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.skills.file.FileReadWriteSkill;
import org.noear.solon.ai.skills.file.ZipSkill;
import org.noear.solon.ai.skills.pdf.PdfSkill;
import org.noear.solon.ai.skills.mail.MailSkill;
import org.noear.solon.ai.skills.sys.*;
import org.noear.solon.ai.skills.crawler.WebCrawlerSkill;
import org.noear.solon.ai.skills.search.WebSearchSkill;
import org.noear.solon.ai.skills.generation.ImageGenerationSkill;
import org.noear.solon.ai.skills.generation.VideoGenerationSkill;

import java.io.File;

/**
 * 高级 AI 技能联动示例（2026 增强版）
 */
public class AdvancedDemo {

    // 场景 1：金融数据周报员（增加 PDF 导出）
    public void financeReportTask() throws Exception {
        String workDir = "./finance_work";

        SystemClockSkill clock = new SystemClockSkill();
        WebSearchSkill search = new WebSearchSkill(WebSearchSkill.SERPER, "serper_key");
        PythonSkill python = new PythonSkill(workDir);

        // 1. 引入 PdfSkill，支持中文报告
        PdfSkill pdf = new PdfSkill(workDir);

        // 2. 使用重构后的 MailSkill (SMTP 模式)
        MailSkill mail = new MailSkill(workDir, "smtp.exmail.qq.com", 465, "ai@company.com", "pass");

        ChatModel agent = ChatModel.of("...")
                .defaultSkillAdd(clock, search, python, pdf, mail)
                .build();

        // AI 流程：获取时间 -> 搜索指数 -> Python 分析 -> Pdf 生成报告 -> Mail 发送附件
        agent.prompt("获取当前时间。搜索本周标普 500 指数的每日收盘价。" +
                        "请用 Python 进行趋势预测，并将分析结果生成为一份名为 'Report.pdf' 的 PDF 报告。" +
                        "最后将该 PDF 发送给 boss@example.com")
                .call();
    }

    // 场景 2：技术调研归档助手（增加 HTML 到 PDF 的转换）
    public void techResearchTask() throws Exception {
        String workDir = "./research_docs";

        WebSearchSkill search = new WebSearchSkill(WebSearchSkill.BING, "bing_key");
        WebCrawlerSkill crawler = new WebCrawlerSkill(WebCrawlerSkill.JINA, "jina_key");
        PdfSkill pdf = new PdfSkill(workDir); // 默认字体模式
        ZipSkill zip = new ZipSkill(workDir);

        ChatModel agent = ChatModel.of("...")
                .defaultSkillAdd(search, crawler, pdf, zip)
                .build();

        // AI 会将抓取的网页内容先转为 PDF 保持格式，再打包
        agent.prompt("搜索 2026 年 Java 行业的三大热门趋势，抓取这些内容并生成精美的 PDF 文档。" +
                        "完成后，将文档打包成 'Java_Trend_2026.zip'。")
                .call();
    }

    // 场景 3：自动化运维与异常分析
    public void devOpsTask() throws Exception {
        String logDir = "./server_logs";

        ShellSkill shell = new ShellSkill(logDir);
        NodejsSkill nodejs = new NodejsSkill(logDir);
        // 使用 SMTP 邮件告警
        MailSkill mail = new MailSkill(logDir, "smtp.office365.com", 587, "admin@corp.com", "key");

        ChatModel agent = ChatModel.of("...")
                .defaultSkillAdd(shell, nodejs, mail)
                .build();

        agent.prompt("用 Shell 读取 /var/log/app.json.log 最后 50 行。" +
                        "若是 JSON 格式，用 Nodejs 提取 'error' 字段。" +
                        "将整理后的报告发邮件给运维组 admin@example.com，标题注明【紧急告警】。")
                .call();
    }

    // 综合场景：多模态 + 自动化链路
    public void runComplexTask() throws Exception {
        String workDir = "./ai_workspace";

        SystemClockSkill clock = new SystemClockSkill();
        WebSearchSkill search = new WebSearchSkill(WebSearchSkill.SERPER, "key");
        ImageGenerationSkill image = new ImageGenerationSkill(ImageGenerationSkill.DALL_E, "key", workDir);
        PdfSkill pdf = new PdfSkill(workDir);
        MailSkill mail = new MailSkill(workDir, "smtp.gmail.com", 587, "ai@gmail.com", "app_password");

        ChatModel agent = ChatModel.of("...")
                .defaultSkillAdd(clock, search, image, pdf, mail)
                .build();

        String userGoal = "获取当前日期。搜索今天关于 'SpaceX' 的最新进展，" +
                "根据进展描述画一张航天火箭的图 'rocket.png'。" +
                "最后将新闻内容和图片说明整理成一份 PDF 报告，发给 xxx@example.com";

        agent.prompt(userGoal).call();
    }
}