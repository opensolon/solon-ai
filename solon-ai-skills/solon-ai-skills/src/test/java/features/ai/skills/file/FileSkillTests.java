package features.ai.skills.file;

import demo.ai.skills.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.simple.SimpleResponse;
import org.noear.solon.ai.skills.file.FileReadWriteSkill;
import org.noear.solon.ai.skills.file.ZipSkill;
import org.noear.solon.test.SolonTest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

/**
 * 文件与压缩技能综合测试
 */
@SolonTest
public class FileSkillTests {

    private String workDir = "./test_workspace";
    private FileReadWriteSkill fileSkill;
    private ZipSkill zipSkill;

    @BeforeEach
    public void setup() throws IOException {
        // 清理并初始化工作目录
        Path root = Paths.get(workDir);
        if (Files.exists(root)) {
            Files.walk(root).sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
        Files.createDirectories(root);

        fileSkill = new FileReadWriteSkill(workDir);
        zipSkill = new ZipSkill(workDir);
    }

    // --- 1. FileReadWriteSkill 基础与边界测试 ---

    @Test
    public void testFileBasicOps() {
        // 测试写入与自动创建子目录
        String subFile = "logs/test.log";
        String content = "hello solon ai";
        fileSkill.write(subFile, content);

        // 测试读取
        String readContent = fileSkill.read(subFile);
        Assertions.assertEquals(content, readContent);

        // 测试列表
        String list = fileSkill.list();
        Assertions.assertTrue(list.contains("logs"));

        // 测试删除
        fileSkill.delete(subFile);
        Assertions.assertTrue(fileSkill.read(subFile).contains("不存在"));
    }

    // --- 2. ZipSkill 逻辑测试 ---

    @Test
    public void testZipLogic() throws IOException {
        // 准备文件
        fileSkill.write("a.txt", "content a");
        fileSkill.write("b.txt", "content b");

        // 执行打包
        String zipName = "out.zip";
        zipSkill.zipFiles(zipName, new String[]{"a.txt", "b.txt"});

        // 验证文件存在
        Path zipPath = Paths.get(workDir, zipName);
        Assertions.assertTrue(Files.exists(zipPath));
        Assertions.assertTrue(Files.size(zipPath) > 0);
    }

    // --- 3. Agent 集成测试 (多技能联动) ---

    @Test
    public void testAgentFileAndZipLinkage() throws Throwable {
        SimpleAgent agent = SimpleAgent.of(LlmUtil.getChatModel())
                .role("文档管理员")
                .defaultSkillAdd(fileSkill)
                .defaultSkillAdd(zipSkill)
                .build();

        // 场景：让 AI 生成一份报告并将其压缩
        String query = "请帮我写一份名为 'report.md' 的报告，内容是关于 AI 发展的。然后把这个报告打包成 'result.zip'。";

        System.out.println("[Agent 联动测试]: " + query);
        SimpleResponse resp = agent.prompt(query).call();

        System.out.println("[AI 回复]: " + resp.getContent());

        // 物理验证
        Assertions.assertTrue(Files.exists(Paths.get(workDir, "report.md")), "report.md 应该被创建");
        Assertions.assertTrue(Files.exists(Paths.get(workDir, "result.zip")), "result.zip 应该被创建");
    }
}