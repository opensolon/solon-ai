package features.ai.skills.sys;

import demo.ai.skills.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.skills.sys.ClaudeCodeAgentSkills;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * ClaudeCodeAgentSkills 综合集成测试
 * * <p>本测试覆盖了 opencode-skills 库中所有技能的自动化发现与驱动逻辑。</p>
 *
 * @author noear
 * @since 3.9.1
 */
public class ClaudeCodeSkillTest {

    // 技能库根目录
    private final String workDir = "/Users/noear/WORK/work_github/solonlab/opencode-skills";

    /**
     * 构建标准 ClaudeCode 智能体
     * @param role 业务角色定义
     */
    private ReActAgent createAgent(String role) {
        return ReActAgent.of(LlmUtil.getChatModel())
                .name("ClaudeCodeAgent")
                .role(role + "。你将严格遵守挂载技能中的【规范协议】执行任务。")
                .defaultSkillAdd(new ClaudeCodeAgentSkills(workDir))
                .maxSteps(30) // 生产环境建议 30 步，以支持复杂的链式思考
                .build();
    }

    /**
     * [csv-data-summarizer] 验证数据流式处理与逻辑汇总能力
     */
    @Test
    public void testUseCsvSummarizerSkill() throws Throwable {
        ReActAgent agent = createAgent("数据分析专家");

        Path dataFile = Paths.get(workDir, "sales_data.csv");
        String csvContent = "Item,Price\nApple,10\nOrange,20\nApple,15";
        Files.write(dataFile, csvContent.getBytes(StandardCharsets.UTF_8));

        try {
            String promptText = "利用 'csv-data-summarizer' 分析 sales_data.csv，计算每种物品的总额并输出结果。";
            String result = agent.prompt(promptText).call().getContent();

            Assertions.assertTrue(result.contains("Apple") && result.contains("25"), "计算逻辑错误或未发现技能");
        } finally {
            Files.deleteIfExists(dataFile);
        }
    }

    /**
     * [log-analyzer] 验证日志模式识别与异常提取能力
     */
    @Test
    public void testUseLogAnalyzerSkill() throws Throwable {
        ReActAgent agent = createAgent("运维诊断专家");

        Path logFile = Paths.get(workDir, "app.log");
        String logContent = "2026-02-04 INFO  Start\n2026-02-04 ERROR NullPointerException at line 45";
        Files.write(logFile, logContent.getBytes(StandardCharsets.UTF_8));

        try {
            String promptText = "分析 app.log，利用 'log-analyzer' 提取错误摘要。";
            String result = agent.prompt(promptText).call().getContent();

            Assertions.assertTrue(result.contains("NullPointerException"), "未能正确识别日志异常");
        } finally {
            Files.deleteIfExists(logFile);
        }
    }

    /**
     * [image-service] 验证多媒体文件操作与物理路径生成
     */
    @Test
    public void testUseImageServiceSkill() throws Throwable {
        ReActAgent agent = createAgent("图像处理助手");

        Path imgFile = Paths.get(workDir, "input.png");
        String imageUrl = "https://solon.noear.org/img/solon/favicon256.png";

        try (InputStream in = new URL(imageUrl).openStream()) {
            Files.copy(in, imgFile, StandardCopyOption.REPLACE_EXISTING);
        }

        try {
            String promptText = "调用 'image-service' 将 input.png 调整为 800x600 并重命名为 output.png。";
            agent.prompt(promptText).call();

            Path outputFile = Paths.get(workDir, "output.png");
            Assertions.assertTrue(Files.exists(outputFile), "物理文件 output.png 未生成");
        } finally {
            Files.deleteIfExists(imgFile);
            Files.deleteIfExists(Paths.get(workDir, "output.png"));
        }
    }

    /**
     * [searchnews] 验证外部信息检索与报告解析
     */
    @Test
    public void testUseSearchNewsSkill() throws Throwable {
        ReActAgent agent = createAgent("情报分析员");

        Path mockPath = Paths.get(workDir, "searchnews/dailynews/2026-02-04/report.md");
        Files.createDirectories(mockPath.getParent());
        Files.write(mockPath, "Solon AI Framework 进展汇报。".getBytes(StandardCharsets.UTF_8));

        String promptText = "搜索 2026-02-04 关于 'Solon AI Framework' 的情报。";
        String result = agent.prompt(promptText).call().getContent();

        Assertions.assertTrue(result.contains("Solon"), "未能从本地技能缓存中提取情报");
    }

    /**
     * [videocut-clip/subtitle] 验证视频处理流水线协作
     */
    @Test
    public void testVideoCutWorkflow() throws Throwable {
        ReActAgent agent = createAgent("视频导演");
        Path videoPath = Paths.get(workDir, "demo.mp4");
        Files.write(videoPath, "video_raw_data".getBytes());

        try {
            String promptText = "使用 'videocut-clip' 裁剪 demo.mp4 后，再用 'videocut-subtitle' 加字幕。";
            String result = agent.prompt(promptText).call().getContent();

            Assertions.assertTrue(result.contains("clip") || result.contains("成功"), "视频流处理失败");
        } finally {
            Files.deleteIfExists(videoPath);
        }
    }

    /**
     * [deep-research/smart-query] 验证长链条调研与信息精简
     */
    @Test
    public void testResearchWorkflow() throws Throwable {
        ReActAgent agent = createAgent("资深研究员");
        Files.createDirectories(Paths.get(workDir, "deep-research/reports"));

        String promptText = "启动 'deep-research' 调查 AI 趋势，并用 'smart-query' 总结核心点。";
        String result = agent.prompt(promptText).call().getContent();

        Assertions.assertNotNull(result, "调研总结不应为空");
    }

    /**
     * [skill-creator/mcp-builder] 验证 Agent 对技能库的自我演化
     */
    @Test
    public void testSkillSelfEvolution() throws Throwable {
        ReActAgent agent = createAgent("系统架构师");

        String promptText = "利用 'skill-creator' 创建一个名为 'temp-skill' 的新技能，必须包含 SKILL.md。";
        agent.prompt(promptText).call();

        Path newSkillPath = Paths.get(workDir, "temp-skill/SKILL.md");
        Assertions.assertTrue(Files.exists(newSkillPath), "Agent 未能自主演化出新技能");
    }

    /**
     * [story-to-scenes/video-creator] 验证从文本到视频的创意链路
     */
    @Test
    public void testCreativeWorkflow() throws Throwable {
        ReActAgent agent = createAgent("创意导演");

        String promptText = "根据文本 '星际旅行'，用 'story-to-scenes' 生成分镜并用 'video-creator' 预演。";
        String result = agent.prompt(promptText).call().getContent();

        Assertions.assertTrue(result.contains("分镜") || result.contains("scene"), "创意链路中断");
    }

    /**
     * [videocut-self-update/install] 验证环境自愈与工具维护
     */
    @Test
    public void testMaintenanceSkills() throws Throwable {
        ReActAgent agent = createAgent("运维专家");

        String promptText = "检查 videocut 环境，若缺失则执行 'videocut-install'，若过旧则执行 'videocut-self-update'。";
        String result = agent.prompt(promptText).call().getContent();

        Assertions.assertTrue(result.contains("检查") || result.contains("更新"), "维护指令未能识别");
    }

    /**
     * [uni-agent/mcp-builder] 验证通用代理调度与连接器构建
     */
    @Test
    public void testAgentIntegrationSkills() throws Throwable {
        ReActAgent agent = createAgent("集成专家");

        String promptText = "通过 'uni-agent' 调度子任务，并尝试用 'mcp-builder' 生成连接器配置。";
        String result = agent.prompt(promptText).call().getContent();

        Assertions.assertTrue(result.contains("代理") || result.contains("mcp"), "集成类技能调用失败");
    }
}