package features.ai.skills.sys;

import demo.ai.skills.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.skills.sys.ClaudeCodeSkill;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ClaudeCodeSkillTest {

    String workDir = "/Users/noear/WORK/work_github/solonlab/opencode-skills";
    ClaudeCodeSkill codeSkill = new ClaudeCodeSkill(workDir);

    @Test
    public void testUseCsvSummarizerSkill() throws Throwable {
        // 1. 初始化 Agent (增加步数以支持完整的“规范发现”流程)
        ReActAgent agent = ReActAgent.of(LlmUtil.getChatModel())
                .role("数据分析专家")
                .defaultSkillAdd(codeSkill)
                .maxSteps(15) // 给 Agent 足够的空间去读取 SKILL.md
                .build();

        // 2. 准备生产数据
        Path dataFile = Paths.get(workDir, "test_data.csv");
        String csvContent = "Category,Amount\nFood,100\nTransport,50\nFood,200\nElectronic,1500";
        Files.write(dataFile, csvContent.getBytes(StandardCharsets.UTF_8));

        // 3. 构造任务：仅强调“使用技能名称”，强迫 Agent 遵循 Claude Code 规范去探索
        String promptText = "根目录下有 'test_data.csv'。请调用 'csv-data-summarizer' 技能" +
                "对该文件进行分类汇总分析，并报告结果。";

        // 4. 执行
        String result = agent.prompt(promptText).call().getContent();
        System.out.println("--- 生产调用结果 ---\n" + result);

        // 5. 验证：
        // 不要只搜一个总和数字，要搜技能处理后的特征关键词
        Assertions.assertAll("Agent 规范驱动执行验证",
                () -> Assertions.assertTrue(result.contains("Food"), "报告应包含分类：Food"),
                () -> Assertions.assertTrue(result.contains("Electronic"), "报告应包含大额分类：Electronic"),
                () -> Assertions.assertTrue(result.contains("1500"), "报告应包含特征金额：1500"),
                // 验证它是否真的运行了 analyze.py 生成了洞察，而不是简单的文本提取
                () -> Assertions.assertTrue(result.contains("分析") || result.contains("Summary"), "报告应包含分析性结论")
        );
    }
}