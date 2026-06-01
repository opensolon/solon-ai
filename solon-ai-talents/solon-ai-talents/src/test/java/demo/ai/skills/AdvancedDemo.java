package demo.ai.skills;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.talents.file.ZipTalent;
import org.noear.solon.ai.talents.pdf.PdfTalent;
import org.noear.solon.ai.talents.mail.MailTalent;
import org.noear.solon.ai.talents.sys.*;
import org.noear.solon.ai.talents.crawler.WebCrawlerDriverTalent;
import org.noear.solon.ai.talents.search.WebSearchDriverTalent;
import org.noear.solon.ai.talents.generation.ImageGenerationTalent;

/**
 * 高级 AI 工具包联动示例（2026 增强版）
 */
public class AdvancedDemo {

    // 场景 1：金融数据周报员（增加 PDF 导出）
    public void financeReportTask() throws Exception {
        String workDir = "./finance_work";

        SystemClockTalent clock = new SystemClockTalent();
        WebSearchDriverTalent search = new WebSearchDriverTalent(WebSearchDriverTalent.SERPER, "serper_key");
        PythonTalent python = new PythonTalent(workDir);

        // 1. 引入 PdfTalent，支持中文报告
        PdfTalent pdf = new PdfTalent(workDir);

        // 2. 使用重构后的 MailTalent (SMTP 模式)
        MailTalent mail = new MailTalent(workDir, "smtp.exmail.qq.com", 465, "ai@company.com", "pass");

        ChatModel agent = ChatModel.of("...")
                .defaultTalentAdd(clock, search, python, pdf, mail)
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

        WebSearchDriverTalent search = new WebSearchDriverTalent(WebSearchDriverTalent.BING, "bing_key");
        WebCrawlerDriverTalent crawler = new WebCrawlerDriverTalent(WebCrawlerDriverTalent.JINA, "jina_key");
        PdfTalent pdf = new PdfTalent(workDir); // 默认字体模式
        ZipTalent zip = new ZipTalent(workDir);

        ChatModel agent = ChatModel.of("...")
                .defaultTalentAdd(search, crawler, pdf, zip)
                .build();

        // AI 会将抓取的网页内容先转为 PDF 保持格式，再打包
        agent.prompt("搜索 2026 年 Java 行业的三大热门趋势，抓取这些内容并生成精美的 PDF 文档。" +
                        "完成后，将文档打包成 'Java_Trend_2026.zip'。")
                .call();
    }

    // 场景 3：自动化运维与异常分析
    public void devOpsTask() throws Exception {
        String logDir = "./server_logs";

        ShellTalent shell = new ShellTalent(logDir);
        NodejsTalent nodejs = new NodejsTalent(logDir);
        // 使用 SMTP 邮件告警
        MailTalent mail = new MailTalent(logDir, "smtp.office365.com", 587, "admin@corp.com", "key");

        ChatModel agent = ChatModel.of("...")
                .defaultTalentAdd(shell, nodejs, mail)
                .build();

        agent.prompt("用 Shell 读取 /var/log/app.json.log 最后 50 行。" +
                        "若是 JSON 格式，用 Nodejs 提取 'error' 字段。" +
                        "将整理后的报告发邮件给运维组 admin@example.com，标题注明【紧急告警】。")
                .call();
    }

    // 综合场景：多模态 + 自动化链路
    public void runComplexTask() throws Exception {
        String workDir = "./ai_workspace";

        SystemClockTalent clock = new SystemClockTalent();
        WebSearchDriverTalent search = new WebSearchDriverTalent(WebSearchDriverTalent.SERPER, "key");
        ImageGenerationTalent image = new ImageGenerationTalent(ImageGenerationTalent.DALL_E, "key", workDir);
        PdfTalent pdf = new PdfTalent(workDir);
        MailTalent mail = new MailTalent(workDir, "smtp.gmail.com", 587, "ai@gmail.com", "app_password");

        ChatModel agent = ChatModel.of("...")
                .defaultTalentAdd(clock, search, image, pdf, mail)
                .build();

        String userGoal = "获取当前日期。搜索今天关于 'SpaceX' 的最新进展，" +
                "根据进展描述画一张航天火箭的图 'rocket.png'。" +
                "最后将新闻内容和图片说明整理成一份 PDF 报告，发给 xxx@example.com";

        agent.prompt(userGoal).call();
    }
}