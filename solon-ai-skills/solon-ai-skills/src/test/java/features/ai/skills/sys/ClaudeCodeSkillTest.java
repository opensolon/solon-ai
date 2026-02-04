package features.ai.skills.sys;

import demo.ai.skills.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.skills.sys.ClaudeCodeSkill;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ClaudeCodeSkillTest {

    @Test
    public void testProjectMaintenance() throws Throwable {
        // 1. 初始化 Skill，指向你的 opencode-skills 目录
        String workDir = "/Users/noear/WORK/work_github/solonlab/opencode-skills";
        ClaudeCodeSkill codeSkill = new ClaudeCodeSkill(workDir);

        // 2. 模拟一个 Agent
        // 注意：这里需要配置你实际可用的 ChatModel (如 OpenAI, DashScope 等)
        SimpleAgent agent = SimpleAgent.of(LlmUtil.getChatModel())
                .role("高级架构师")
                .defaultSkillAdd(codeSkill)
                .build();

        // 3. 构造测试场景：
        // 使用 Paths.get 代替 Path.of
        Path projectPath = Paths.get(workDir, "csv-data-summarizer");
        Path testFile = projectPath.resolve("Main.java");
        Path skillMd = projectPath.resolve("skill.md");

        // 确保目录存在
        if (!Files.exists(projectPath)) {
            Files.createDirectories(projectPath);
        }

        // 使用 Files.write 代替 Files.writeString
        Files.write(testFile, "public class Main { \n // TODO: Add logging \n }".getBytes(StandardCharsets.UTF_8));
        Files.write(skillMd, "规范：所有修改必须包含 'Modified by AI' 注释。".getBytes(StandardCharsets.UTF_8));

        try {
            // 4. 执行 Agent 任务
            String promptText = "请进入 csv-data-summarizer 目录，查看 Main.java，" +
                    "根据 skill.md 的规范，将 TODO 替换为日志打印。";

            String result = agent.prompt(promptText).call().getContent();
            System.out.println("Agent 执行结果: \n" + result);

            // 5. 验证结果
            String updatedContent = new String(Files.readAllBytes(testFile), StandardCharsets.UTF_8);
            System.out.println("文件更新后内容: \n" + updatedContent);

            Assertions.assertTrue(updatedContent.contains("Modified by AI"), "应该包含规范要求的注释");
            Assertions.assertFalse(updatedContent.contains("TODO"), "TODO 应该被替换了");

        } finally {
            // 清理测试现场（可选）
            // Files.deleteIfExists(testFile);
            // Files.deleteIfExists(skillMd);
        }
    }
}