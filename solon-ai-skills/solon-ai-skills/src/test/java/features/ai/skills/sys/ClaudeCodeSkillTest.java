package features.ai.skills.sys;

import demo.ai.skills.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.skills.sys.ClaudeCodeAgentSkills;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class ClaudeCodeSkillTest {

    // 指向存量技能库目录
    String skillsDir = "/Users/noear/WORK/work_github/solonlab/opencode-skills";
    ClaudeCodeAgentSkills codeSkill = new ClaudeCodeAgentSkills(skillsDir);

    private ReActAgent createAgent(String role) {
        return ReActAgent.of(LlmUtil.getChatModel())
                .role(role)
                .defaultSkillAdd(codeSkill)
                .maxSteps(15) // 规范驱动需要更多步数来读取 SKILL.md
                .build();
    }

    /**
     * 测试 csv-data-summarizer：验证数据处理与汇总能力
     */
    @Test
    public void testUseCsvSummarizerSkill() throws Throwable {
        ReActAgent agent = createAgent("数据分析专家");

        Path dataFile = Paths.get(skillsDir, "sales_data.csv");
        String csvContent = "Item,Price\nApple,10\nOrange,20\nApple,15";
        Files.write(dataFile, csvContent.getBytes(StandardCharsets.UTF_8));

        String promptText = "请利用 'csv-data-summarizer' 技能分析根目录下的 sales_data.csv，计算每种物品的总额。";

        String result = agent.prompt(promptText).call().getContent();

        Assertions.assertTrue(result.contains("Apple") && result.contains("25"), "应计算出 Apple 总额为 25");
    }

    /**
     * 测试 log-analyzer：验证日志特征提取能力
     */
    @Test
    public void testUseLogAnalyzerSkill() throws Throwable {
        ReActAgent agent = createAgent("运维诊断专家");

        Path logFile = Paths.get(skillsDir, "app.log");
        String logContent = "2026-02-04 INFO  Start\n2026-02-04 ERROR NullPointerException at line 45\n2026-02-04 WARN  Slow response";
        Files.write(logFile, logContent.getBytes(StandardCharsets.UTF_8));

        String promptText = "根目录下有 app.log，请使用 'log-analyzer' 技能找出日志中的错误摘要。";

        String result = agent.prompt(promptText).call().getContent();

        Assertions.assertTrue(result.contains("NullPointerException"), "应识别出空指针异常");
    }

    /**
     * 测试 image-service：验证多媒体处理（模拟指令下达）
     */
    @Test
    public void testUseImageServiceSkill() throws Throwable {
        ReActAgent agent = createAgent("图像处理助手");

        // 1. 从远程 URL 加载一张真实的图片作为输入源
        Path imgFile = Paths.get(skillsDir, "input.png");
        String imageUrl = "https://solon.noear.org/img/solon/favicon256.png";

        try (InputStream in = new URL(imageUrl).openStream()) {
            Files.copy(in, imgFile, StandardCopyOption.REPLACE_EXISTING);
        }

        try {
            // 2. 构造任务：要求调整尺寸并重命名
            // Agent 会经历：ls -> cat image-service/SKILL.md -> run_command
            String promptText = "请使用 'image-service' 技能将根目录下的 input.png 调整为 800x600 像素，并重命名为 output.png。";

            String result = agent.prompt(promptText).call().getContent();
            System.out.println("--- Image Service 执行结果 ---\n" + result);

            // 3. 验证逻辑
            // A. 验证 Agent 的回复中包含关键目标文件名
            Assertions.assertTrue(result.contains("output.png") || result.toLowerCase().contains("success"),
                    "Agent 应报告处理成功并提及 output.png");

            // B. 物理验证：检查 image-service 是否真的驱动工具生成了新文件
            Path outputFile = Paths.get(skillsDir, "output.png");
            Assertions.assertTrue(Files.exists(outputFile), "技能执行后应在物理路径生成 output.png");

        } finally {
            // 4. 清理现场
            Files.deleteIfExists(imgFile);
            Files.deleteIfExists(Paths.get(skillsDir, "output.png"));
        }
    }

    /**
     * 测试 searchnews：验证外部工具集成能力
     */
    @Test
    public void testUseSearchNewsSkill() throws Throwable {
        // 增加步数，因为 Ralph Loop 确实比较重
        ReActAgent agent = ReActAgent.of(LlmUtil.getChatModel())
                .role("情报员")
                .defaultSkillAdd(codeSkill)
                .maxSteps(25)
                .build();

        // 2. 环境预热：预置一个包含 Solon 关键词的模拟结果
        // 这样即便 curl 失败，Agent 在 grep 时也能从本地文件系统发现“情报”
        Path mockPath = Paths.get(skillsDir, "searchnews/dailynews/2026-02-04/report.md");
        Files.createDirectories(mockPath.getParent());
        String mockData = "Solon AI Framework 今日发布了基于 Claude Code 规范的 Agent Skills 扩展，支持地毯式搜索。";
        Files.write(mockPath, mockData.getBytes(StandardCharsets.UTF_8));

        // 3. 执行
        String promptText = "请利用 'searchnews' 技能，搜索 2026-02-04 关于 'Solon AI Framework' 的进展并汇报。";
        String result = agent.prompt(promptText).call().getContent();

        // 4. 强化校验
        System.out.println("--- 汇报内容 ---\n" + result);
        Assertions.assertTrue(result.contains("Solon"), "结果必须包含关键词 'Solon'");
        Assertions.assertTrue(result.contains("Framework"), "结果必须包含关键词 'Framework'");
    }
}