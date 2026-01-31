package demo.ai.skills;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.skills.file.FileReadWriteSkill;
import org.noear.solon.ai.skills.sys.SystemClockSkill;
import org.noear.solon.ai.skills.sys.NodejsSkill;
import org.noear.solon.ai.skills.sys.PythonSkill;
import org.noear.solon.ai.skills.sys.ShellSkill;
import org.noear.solon.ai.skills.file.ZipSkill;
import org.noear.solon.ai.skills.generation.ImageGenerationSkill;
import org.noear.solon.ai.skills.generation.VideoGenerationSkill;
import org.noear.solon.ai.skills.social.MailSkill;
import org.noear.solon.ai.skills.crawler.WebCrawlerSkill;
import org.noear.solon.ai.skills.search.WebSearchSkill;

public class AdvancedDemo {
    // 场景 1：金融数据周报员
    public void financeReportTask() throws Exception {
        // 1. 初始化技能
        SystemClockSkill clock = new SystemClockSkill();
        WebSearchSkill search = new WebSearchSkill(WebSearchSkill.SERPER, "serper_key");
        // Python 用于趋势计算
        PythonSkill python = new PythonSkill("./finance_work");
        // 邮件发送
        MailSkill mail = new MailSkill(MailSkill.RESEND, "resend_key", "./finance_work");

        // 2. 构建 Agent
        ChatModel agent = ChatModel.of("...")
                .defaultSkillAdd(clock, search, python, mail)
                .build();

        // 3. 执行任务（AI 会自动先取时间，再搜索，再用 Python 计算，最后发邮件）
        agent.prompt("获取当前时间。搜索本周标普 500 指数的每日收盘价。" +
                        "请编写 Python 脚本进行线性回归分析预测下周趋势。" +
                        "最后将分析报告发给 boss@example.com")
                .call();
    }

    // 场景 2：技术调研归档助手
    public void techResearchTask() throws Exception {
        WebSearchSkill search = new WebSearchSkill(WebSearchSkill.BING, "bing_key");
        WebCrawlerSkill crawler = new WebCrawlerSkill(WebCrawlerSkill.JINA, "jina_key");
        FileReadWriteSkill storage = new FileReadWriteSkill("./research_docs");
        ZipSkill zip = new ZipSkill("./research_docs");

        ChatModel agent = ChatModel.of("...")
                .defaultSkillAdd(search, crawler, storage, zip)
                .build();

        // AI 会循环调用 crawler 和 write_file，最后调用一次 zip_directory
        agent.prompt("搜索 2026 年 Java 行业的三大热门趋势，抓取这些文章的正文并保存为 .md 文件。" +
                        "完成后，将整个目录打包成 'Trend_Report_2026.zip'。")
                .call();
    }

    // 场景 3：自动化运维与异常分析
    public void devOpsTask() throws Exception {
        // Shell 负责读取系统状态
        ShellSkill shell = new ShellSkill("./server_logs");
        // Nodejs 负责解析复杂的日志 JSON
        NodejsSkill nodejs = new NodejsSkill("./server_logs");
        FileReadWriteSkill storage = new FileReadWriteSkill("./server_logs");
        MailSkill mail = new MailSkill(MailSkill.RESEND, "resend_key", "./server_logs");

        ChatModel agent = ChatModel.of("...")
                .defaultSkillAdd(shell, nodejs, storage, mail)
                .build();

        // 逻辑：Shell 读文件 -> AI 发现是 JSON -> 调用 Nodejs 处理 -> 保存报告 -> 邮件报警
        agent.prompt("使用 Shell 读取 /var/log/app.json.log 的最后 50 行。" +
                        "如果是 JSON 格式，请使用 Nodejs 提取其中的 'error_message' 和 'stack_trace' 字段。" +
                        "将整理后的异常报告保存为 'panic.txt' 并发邮件给 admin@example.com")
                .call();
    }

    // 场景 4：竞品舆情简报
    public void competitorAnalysisTask() throws Exception {
        SystemClockSkill clock = new SystemClockSkill();
        WebSearchSkill search = new WebSearchSkill(WebSearchSkill.BAIDU, "baidu_key");
        WebCrawlerSkill crawler = new WebCrawlerSkill(WebCrawlerSkill.FIRECRAWL, "firecrawl_key");
        FileReadWriteSkill storage = new FileReadWriteSkill("./competitor_news");

        ChatModel agent = ChatModel.of("...")
                .defaultSkillAdd(clock, search, crawler, storage)
                .build();

        agent.prompt("今天是哪天？请搜索过去 24 小时内关于 '华为' 和 '苹果' 的竞品动态。" +
                        "抓取深度分析文章，对比两者的最新动作，撰写一份 500 字的对比简报，" +
                        "以当天日期命名保存为 Markdown 文件。")
                .call();
    }

    // 综合
    public void runComplexTask() throws Exception {
        // 初始化所有超能力
        SystemClockSkill clock = new SystemClockSkill();
        WebSearchSkill search = new WebSearchSkill(WebSearchSkill.SERPER, "key");
        WebCrawlerSkill crawler = new WebCrawlerSkill(WebCrawlerSkill.JINA, "key");
        PythonSkill python = new PythonSkill("./ai_workspace");
        NodejsSkill nodejs = new NodejsSkill("./ai_workspace");
        FileReadWriteSkill disk = new FileReadWriteSkill("./ai_workspace");
        ZipSkill zip = new ZipSkill("./ai_workspace");
        MailSkill mail = new MailSkill(MailSkill.RESEND, "key", "./ai_workspace");

        ChatModel agent = ChatModel.of("...")
                .defaultSkillAdd(clock, search, crawler, python, nodejs, disk, zip, mail)
                .build();

        // 这是一个典型的跨技能链式任务
        String userGoal = "获取当前时间，搜索今天关于 AI 的 3 条热点新闻，" +
                "抓取内容后通过 Python 提取关键词，生成一份报告保存，" +
                "最后把报告打包发给 xxx@example.com";

        agent.prompt(userGoal).call();
    }

    public void testVideoAgent() throws Exception {
        // 初始化视频生成（使用 Sora 驱动）
        VideoGenerationSkill videoSkill = new VideoGenerationSkill(VideoGenerationSkill.SORA, "sk-xxx", "./ai_media");
        // 辅助：文件打包
        ZipSkill zip = new ZipSkill("./ai_media");

        ChatModel agent = ChatModel.of("...")
                .defaultSkillAdd(videoSkill, zip)
                .build();

        // AI 会先调用 generate_video，等待几分钟完成后，再调用 zip_directory
        agent.prompt("帮我生成一段‘赛博朋克风格的北京街道’短视频，文件名叫 bj2077.mp4。" +
                        "完成后把视频文件压缩成 zip 包。")
                .call();
    }

    // 模拟多模态联动测试代码
    public void testMultiModalWorkflow() throws Throwable {
        // 1. 初始化存储路径
        String workDir = "./ai_output";

        // 2. 准备 Skill 实例 (假设你已经有了对应的 Driver 实现)
        ImageGenerationSkill imageSkill = new ImageGenerationSkill(ImageGenerationSkill.DALL_E, "sk-xxx", workDir);
        VideoGenerationSkill videoSkill = new VideoGenerationSkill(VideoGenerationSkill.SORA, "sk-xxx", workDir);

        // 3. 构建 Agent
        ChatModel agent = ChatModel.of("gpt-4-turbo") // 或者使用 Solon 适配的其他模型
                .defaultSkillAdd(imageSkill, videoSkill)
                .build();

        // 4. 发起跨模态指令
        // 这个 Prompt 会强制 AI 顺序调用两个工具，并利用上一步生成的“文件名”
        String prompt = "请帮我画一张『未来科幻风格的深圳南山街道』的图片，起名叫 sz_future.png。" +
                "等图片生成后，请基于这张图片的描述，再帮我制作一个 5 秒钟的 16:9 短视频，视频文件名叫 sz_movie.mp4。";

        //LOG.info("Starting multi-modal task...");
        String response = agent.prompt(prompt).call().getContent();

        //LOG.info("Agent Response: \n{}", response);
    }
}