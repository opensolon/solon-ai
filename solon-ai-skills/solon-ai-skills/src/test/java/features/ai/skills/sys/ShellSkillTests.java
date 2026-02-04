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
    public void testBasicCommand() throws Exception {
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

    // --- 2. 新增能力测试：环境探测与文件感知 ---

    @Test
    public void testDiscoveryAndPerception() throws IOException {
        // A. 测试命令探测 (探测系统必有的命令)
        String cmdToTest = System.getProperty("os.name").toLowerCase().contains("win") ? "cmd" : "sh";
        boolean hasCmd = shellSkill.existsCmd(cmdToTest);
        Assertions.assertTrue(hasCmd, "系统应当能探测到基础 Shell 环境: " + cmdToTest);

        // B. 测试文件列表感知
        Files.write(Paths.get(workDir, "perception.test"), "data".getBytes());
        String listResult = shellSkill.listFiles();
        System.out.println("List Files Output:\n" + listResult);
        Assertions.assertTrue(listResult.contains("perception.test"));
    }

    // --- 3. Agent 驱动集成测试 (增加复杂逻辑) ---

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

    @Test
    public void testAgentIntelligentDiscovery() throws Throwable {
        SimpleAgent agent = SimpleAgent.of(LlmUtil.getChatModel())
                .role("全栈开发专家")
                .defaultSkillAdd(shellSkill)
                .build();

        // 新增测试点：探测 Python 并在 Shell 中执行多语言逻辑
        String query = "请帮我检查当前环境是否支持 python。如果支持，请打印 python 的版本号；" +
                "如果不支持，请告诉我就好。最后请列出当前目录下的所有文件。";

        System.out.println("[Agent 智能探测中...]");
        SimpleResponse resp = agent.prompt(query).call();

        System.out.println("Agent Response: " + resp.getContent());

        // 验证 AI 是否调用了正确的工具链
        // 期望观察：AI 调用了 exists_cmd("python"), 如果支持则 execute_shell("python --version"), 最后 list_files()
        Assertions.assertNotNull(resp.getContent());
    }

    @Test
    public void testInvalidCommand() {
        // 执行一个肯定不存在的命令
        String code = "non_existent_command_12345";
        String result = shellSkill.execute(code);

        System.out.println("Invalid Command Output: " + result);
        // 应该是包含 command not found 或类似的系统错误提示
        Assertions.assertNotNull(result);
    }

    @Test
    public void testTimeoutProtection() {
        // Linux/macOS 用 sleep 60; Windows 用 timeout 60
        String code = System.getProperty("os.name").toLowerCase().contains("win")
                ? "timeout /t 60" : "sleep 60";

        long start = System.currentTimeMillis();
        String result = shellSkill.execute(code);
        long end = System.currentTimeMillis();

        System.out.println("Timeout Result: " + result);
        // 耗时应该接近 30s（AbsProcessSkill 的默认限制），而不是 60s
        Assertions.assertTrue((end - start) < 40000, "执行时间过长，超时保护失效");
    }

    @Test
    public void testDeepDirectoryPerception() throws IOException {
        // 创建深层目录
        Path deepPath = Paths.get(workDir, "a", "b", "c");
        Files.createDirectories(deepPath);
        Files.write(deepPath.resolve("deep.txt"), "found me".getBytes());

        // 测试 list_files 能否反映出目录结构
        String result = shellSkill.listFiles();
        Assertions.assertTrue(result.contains("[DIR] a"));

        // 测试 AI 能否进入深层目录操作
        String cdCode = System.getProperty("os.name").toLowerCase().contains("win")
                ? "cd a\\b\\c && type deep.txt"
                : "cd a/b/c && cat deep.txt";
        String output = shellSkill.execute(cdCode);
        Assertions.assertTrue(output.contains("found me"));
    }

    @Test
    public void testAgentSelfHealing() throws Throwable {
        SimpleAgent agent = SimpleAgent.of(LlmUtil.getChatModel())
                .role("高级运维专家")
                .defaultSkillAdd(shellSkill)
                .build();

        // 预设一个文件
        Files.write(Paths.get(workDir, "data.log"), "line1\nline2\nline3".getBytes());

        // 任务：要求它【不准使用 wc 命令】来统计行数（强迫它思考替代方案）
        String query = "请统计当前目录下 data.log 的行数。注意：不要直接使用 'wc' 命令，" +
                "请先检查环境，选择一种你认为最稳妥的脚本方式（如 awk, python 或读取文件内容）来实现。";

        System.out.println("[Agent 正在寻找替代方案...]");
        SimpleResponse resp = agent.prompt(query).call();

        System.out.println("Agent 方案反馈: " + resp.getContent());
        Assertions.assertTrue(resp.getContent().contains("3"), "AI 应当通过替代方案算出正确行数");
    }

    @Test
    public void testAgentComplexDataPipeline() throws Throwable {
        SimpleAgent agent = SimpleAgent.of(LlmUtil.getChatModel())
                .role("数据工程师")
                .defaultSkillAdd(shellSkill)
                .build();

        // 复杂任务：生成数据 -> 验证 -> 修改 -> 再验证
        String query = "请在当前目录下生成一个名为 config.json 的文件，内容包含版本号 1.0.0。" +
                "然后，使用 shell 命令将版本号改为 1.0.1，并最后确认文件内容是否修改成功。";

        System.out.println("[Agent 执行数据流水线...]");
        SimpleResponse resp = agent.prompt(query).call();

        Path configFile = Paths.get(workDir, "config.json");
        String content = new String(Files.readAllBytes(configFile));

        System.out.println("最终文件内容: " + content);
        Assertions.assertTrue(content.contains("1.0.1"), "AI 应当成功完成了读取、修改、回写的闭环操作");
    }
}