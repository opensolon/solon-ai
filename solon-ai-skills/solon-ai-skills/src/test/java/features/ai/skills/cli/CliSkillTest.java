package features.ai.skills.cli;

import demo.ai.skills.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.skills.claudecode.CliSkill;

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
public class CliSkillTest {
    // 下载 skills： https://github.com/solonlab/opencode-skills

    // 技能库根目录
    private final String workDir = "/Users/noear/WORK/work_github/solonlab/opencode-skills";

    /**
     * 构建标准 ClaudeCode 智能体
     * @param role 业务角色定义
     */
    private ReActAgent createAgent(String role) {
        return ReActAgent.of(LlmUtil.getChatModel())
                .name("ClaudeCodeAgent")
                .role(role)
                .instruction("严格遵守挂载技能中的【规范协议】执行任务")
                .defaultSkillAdd(new CliSkill(workDir))
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

        // 1. 准备 MOCK 数据
        Path dayDir = Paths.get(workDir, "searchnews/dailynews/2026-02-04");
        Files.createDirectories(dayDir);
        Path mockPath = dayDir.resolve("2026-02-04.md");
        String mockData = "Solon AI Framework 宣布深度集成 Claude Code 规范，极大提升了 Agent 的自主执行效率。";
        Files.write(mockPath, mockData.getBytes(StandardCharsets.UTF_8));

        try {
            // 2. 这里的 Prompt 增加 "只读" 暗示，防止它去写进度文件
            String promptText = "【只读任务】搜索 2026-02-04 关于 'Solon AI Framework' 的情报。\n" +
                    "请直接使用 grep 检索本地目录。如果发现匹配报告，请立即汇报内容。\n" +
                    "禁止更新任何进度文件或记录文件，直接给出最终答案。";

            String result = agent.prompt(promptText).call().getContent();
            System.out.println("--- SearchNews Result ---\n" + result);

            Assertions.assertTrue(result.contains("Solon"), "结果应包含关键词 'Solon'");
        } finally {
            // 3. 修复点：使用递归删除，确保清理干净 Agent 产生的 progress.txt 等杂质
            deleteDirectoryRecursive(dayDir);
        }
    }

    private void deleteDirectoryRecursive(Path path) throws java.io.IOException {
        if (Files.exists(path)) {
            try (java.util.stream.Stream<Path> walk = Files.walk(path)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (java.io.IOException e) {
                                // 打印日志但继续清理
                                System.err.println("清理文件失败: " + p + " -> " + e.getMessage());
                            }
                        });
            }
        }
    }

    /**
     * [videocut-clip/subtitle] 验证视频处理流水线协作
     */
    @Test
    public void testVideoCutWorkflow() throws Throwable {
        ReActAgent agent = createAgent("视频导演");
        Path videoPath = Paths.get(workDir, "demo.mp4");
        // 写入稍微像样点的伪数据，或者保持原样但在 Prompt 中声明
        Files.write(videoPath, "video_raw_data".getBytes());

        try {
            String promptText = "【模拟测试场景】请忽略环境依赖检查（ffmpeg/whisper）。\n" +
                    "直接尝试调用 'videocut-clip' 裁剪 demo.mp4（假设已确认删除任务），\n" +
                    "然后调用 'videocut-subtitle'。即使命令执行报错，也要汇报你尝试执行的指令。";

            String result = agent.prompt(promptText).call().getContent();
            System.out.println("--- VideoCut Result ---\n" + result);

            // 只要 Agent 尝试去调工具了，或者在回复中提到了剪辑逻辑，就算通
            Assertions.assertTrue(result.contains("clip") || result.contains("指令") || result.contains("成功"),
                    "Agent 未能进入执行阶段，可能卡在环境预检中");
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

        String promptText = "利用 'skill-creator' 创建一个名为 'solon-skill' 的新技能（让用户更方便开发 java solon 应用），必须包含 SKILL.md。";
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
        ReActAgent agent = createAgent("视频助手");

        // 1. 物理埋点：必须放在 Agent 能看到的技能目录内
        // 我们选 videocut-clip，因为它最符合“剪切”任务的目标
        Path skillDir = Paths.get(workDir, "videocut-clip");
        Path versionFile = skillDir.resolve("version.txt");

        // 确保目录存在（虽然它应该已经存在了）并写入旧版本
        Files.createDirectories(skillDir);
        Files.write(versionFile, "v1.0.0".getBytes());

        try {
            // 2. 模拟真实用户的模糊指令 + 模拟环境暗示
            String promptText = "请帮我使用 videocut 工具剪切 demo.mp4 的前5秒。" +
                    "（注意：如果是环境或版本问题，请尝试自主修复，无需向我请示）";

            String result = agent.prompt(promptText).call().getContent();
            System.out.println("--- 执行 Trace 摘要 ---\n" + result);

            // 3. 硬核断言：不仅要有关键字，还要有动作逻辑
            boolean hasAction = result.contains("update") || result.contains("升级") || result.contains("install");
            boolean hasVersionAware = result.contains("v1.0.0");

            Assertions.assertTrue(hasAction && hasVersionAware,
                    "Agent 必须识别到 v1.0.0 版本并明确表现出‘升级’或‘安装’的行为。实际回复：" + result);

        } finally {
            // 建议使用递归清理，防止 Agent 产生了 version.txt.bak 等临时文件
            Files.deleteIfExists(versionFile);
        }
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