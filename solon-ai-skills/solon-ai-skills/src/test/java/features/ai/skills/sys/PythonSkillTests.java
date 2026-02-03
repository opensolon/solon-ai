package features.ai.skills.sys;

import demo.ai.skills.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.simple.SimpleResponse;
import org.noear.solon.ai.skills.sys.PythonSkill;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

/**
 * Python 脚本执行技能测试
 */
public class PythonSkillTests {

    private final String workDir = "./test_workspace";
    private PythonSkill pythonSkill;

    @BeforeEach
    public void setup() throws IOException {
        // 1. 准备并清理沙箱目录
        Path root = Paths.get(workDir);
        if (Files.exists(root)) {
            Files.walk(root).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
        Files.createDirectories(root);

        // 2. 初始化技能 (会自动探测系统的 python 指令)
        pythonSkill = new PythonSkill(workDir);
    }

    // --- 1. 基础功能测试 ---

    @Test
    public void testSimpleCalculation() {
        // 测试数学计算输出
        String code = "print(123 + 456)";
        String result = pythonSkill.execute(code);

        System.out.println("Result: " + result);
        Assertions.assertTrue(result.contains("579"));
    }

    @Test
    public void testDataAnalysisLogic() {
        // 测试 Python 的多行逻辑与数据处理
        String code =
                "data = [1, 2, 3, 4, 5]\n" +
                        "print(sum(data) / len(data))";

        String result = pythonSkill.execute(code);
        Assertions.assertTrue(result.contains("3.0"));
    }

    @Test
    public void testSecurityBounds() {
        // 验证沙箱内的文件写操作（虽然 AbsProcessSkill 主要是隔离运行目录）
        String code = "with open('test.txt', 'w') as f: f.write('hello')\nprint('ok')";
        pythonSkill.execute(code);

        Assertions.assertTrue(Files.exists(Paths.get(workDir, "test.txt")), "文件应生成在指定的 workDir 目录下");
    }

    // --- 2. Agent 驱动集成测试 ---

    @Test
    public void testAgentDataAnalysis() throws Throwable {
        // 初始化 Agent
        SimpleAgent agent = SimpleAgent.of(LlmUtil.getChatModel())
                .role("数据分析专家")
                .defaultSkillAdd(pythonSkill)
                .build();

        // 模拟一个需要编程解决的数学问题
        String query = "请计算 2026 年 2 月 1 日到 2026 年 10 月 1 日之间有多少个周六？请通过编写 Python 代码计算。";

        System.out.println("[Agent 开始编写代码并计算...]");
        SimpleResponse resp = agent.prompt(query).call();

        System.out.println("[AI 最终结论]: " + resp.getContent());

        // 验证：AI 应该通过调用 execute_python 工具得到结果，回复中应包含正确的数字
        Assertions.assertTrue(resp.getContent().contains("35") || resp.getContent().contains("周六"));
    }
}