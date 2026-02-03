package features.ai.skills.sys;

import demo.ai.skills.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.simple.SimpleResponse;
import org.noear.solon.ai.skills.sys.ShellSkill;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

/**
 * Shell 脚本执行技能测试
 */
public class ShellSkillTests {

    private final String workDir = "./test_workspace";
    private ShellSkill shellSkill;

    @BeforeEach
    public void setup() throws IOException {
        // 1. 准备并清理沙箱目录
        Path root = Paths.get(workDir);
        if (Files.exists(root)) {
            Files.walk(root).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
        Files.createDirectories(root);

        // 2. 初始化技能（默认使用 /bin/sh，macOS 完美支持）
        shellSkill = new ShellSkill(workDir);
    }

    // --- 1. 基础系统指令测试 ---

    @Test
    public void testBasicCommand() throws Exception{
        // 测试标准输出捕获
        String code = "echo 'Hello Solon AI' && pwd";
        String result = shellSkill.execute(code);

        System.out.println("Shell Output: " + result);
        Assertions.assertTrue(result.contains("Hello Solon AI"));

        // 获取 workDir 的物理绝对路径进行对比
        String absolutePath = new File(workDir).getCanonicalPath();
        Assertions.assertTrue(result.contains(absolutePath), "路径应包含: " + absolutePath);
    }

    @Test
    public void testMultiLineScript() {
        // 测试多行逻辑与变量赋值
        String code =
                "COUNT=10\n" +
                        "if [ $COUNT -gt 5 ]; then\n" +
                        "  echo 'Count is large'\n" +
                        "fi";

        String result = shellSkill.execute(code);
        Assertions.assertTrue(result.contains("Count is large"));
    }

    // --- 2. Agent 驱动集成测试 ---

    @Test
    public void testAgentSysTask() throws Throwable {
        SimpleAgent agent = SimpleAgent.of(LlmUtil.getChatModel())
                .role("系统运维专家")
                .defaultSkillAdd(shellSkill)
                .build();

        // 重点：在 query 中明确要求“执行”
        String query = "请【执行】Shell命令：在当前目录下创建一个名为 info.txt 的文件，" +
                "写入内容 'solon-ai-test'。执行完成后，统计文件数并回复。";

        System.out.println("[Agent 运行中...]");
        SimpleResponse resp = agent.prompt(query).call();

        // 检查 AI 是否真的触发了动作（通过控制台日志看是否有 "Executing shell code"）
        Path infoFile = Paths.get(workDir, "info.txt");

        if (!Files.exists(infoFile)) {
            System.err.println("AI 耍赖了，它只写了代码没调工具。回复内容：\n" + resp.getContent());
        }

        Assertions.assertTrue(Files.exists(infoFile), "AI 必须实际调用 execute_shell 创建文件");
        Assertions.assertTrue(resp.getContent().contains("1"), "AI 应当反馈统计结果");
    }
}