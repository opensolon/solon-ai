package features.ai.skills.sys;

import demo.ai.skills.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.simple.SimpleResponse;
import org.noear.solon.ai.skills.sys.NodejsSkill;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

/**
 * Node.js 脚本执行技能测试
 */
public class NodejsSkillTests {

    private final String workDir = "./test_workspace";
    private NodejsSkill nodejsSkill;

    @BeforeEach
    public void setup() throws IOException {
        // 1. 准备并清理沙箱目录
        Path root = Paths.get(workDir);
        if (Files.exists(root)) {
            Files.walk(root).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
        Files.createDirectories(root);

        // 2. 初始化技能
        nodejsSkill = new NodejsSkill(workDir);
    }

    // --- 1. 基础功能测试 ---

    @Test
    public void testSimpleJsExecution() {
        // 测试基础打印和数学运算
        String code = "console.log(1024 * 2);";
        String result = nodejsSkill.execute(code);

        System.out.println("Node.js Output: " + result);
        Assertions.assertTrue(result.contains("2048"));
    }

    @Test
    public void testJsonHandling() {
        // 测试 Node.js 处理 JSON 的便利性
        String code =
                "const data = {name: 'Solon', version: '3.0'};\n" +
                        "console.log(JSON.stringify(data));";

        String result = nodejsSkill.execute(code);
        Assertions.assertTrue(result.contains("\"name\":\"Solon\""));
    }

    @Test
    public void testAsynchronousLogic() {
        // 测试简单的异步等待（Node.js 特色）
        String code =
                "setTimeout(() => {\n" +
                        "  console.log('Done');\n" +
                        "}, 100);";

        String result = nodejsSkill.execute(code);
        Assertions.assertTrue(result.contains("Done"));
    }

    // --- 2. Agent 驱动集成测试 ---

    @Test
    public void testAgentJsTask() throws Throwable {
        // 初始化 Agent，注入 Node.js 专家技能
        SimpleAgent agent = SimpleAgent.of(LlmUtil.getChatModel())
                .role("JavaScript 开发者")
                .defaultSkillAdd(nodejsSkill)
                .build();

        // 模拟一个文本处理任务
        String query = "请帮我写一段 JS 代码：将字符串 'hello_solon_ai' 转换为大驼峰格式（HelloSolonAi），并打印结果。";

        System.out.println("[Agent 正在思考并执行 JS...]");
        SimpleResponse resp = agent.prompt(query).call();

        System.out.println("[AI 最终结论]: " + resp.getContent());

        // 验证：AI 应当成功调用 execute_js 并输出转换后的结果
        Assertions.assertTrue(resp.getContent().contains("HelloSolonAi"));
    }
}