package features.ai.skills.pdf;

import demo.ai.skills.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.simple.SimpleResponse;
import org.noear.solon.ai.skills.pdf.PdfSkill;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

/**
 * PDF 技能综合测试（macOS 环境优化）
 */
public class PdfSkillTests {

    private final String workDir = "./test_workspace";
    private PdfSkill pdfSkill;

    @BeforeEach
    public void setup() throws IOException {
        // 1. 清理沙箱环境
        Path root = Paths.get(workDir);
        if (Files.exists(root)) {
            Files.walk(root).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
        Files.createDirectories(root);

        // 2. 针对 macOS 优化：尝试加载系统内置中文字体
        // Mac 常见路径：/System/Library/Fonts/ 或 /Library/Fonts/
        File macFont = new File("/System/Library/Fonts/PingFang.ttc");
        if (!macFont.exists()) {
            macFont = new File("/Library/Fonts/Arial Unicode.ttf");
        }

        final File finalFont = macFont;
        pdfSkill = new PdfSkill(workDir, () -> {
            try {
                return finalFont.exists() ? new FileInputStream(finalFont) : null;
            } catch (Exception e) {
                return null;
            }
        });
    }

    // --- 1. 核心渲染能力测试 ---

    @Test
    public void testCreateChineseMarkdown() throws IOException {
        String fileName = "mac_chinese_test.pdf";
        // 测试内容包含：中文、表格、加粗
        String mdContent = "# Solon AI 报告\n" +
                "\n" +
                "| 特性 | 状态 |\n" +
                "| :--- | :--- |\n" +
                "| 中文支持 | **通过** |\n" +
                "| 自动换行 | **通过** |\n" +
                "\n" +
                "这是在 macOS 上生成的测试文档。";

        String result = pdfSkill.create(fileName, mdContent, "markdown");

        Path pdfPath = Paths.get(workDir, fileName);
        Assertions.assertTrue(result.contains("成功"));
        Assertions.assertTrue(Files.exists(pdfPath));

        // 验证解析：生成的中文 PDF 应该能被自己解析回来
        String parsedText = pdfSkill.parse(fileName);
        Assertions.assertTrue(parsedText.contains("Solon AI"), "解析内容应匹配");
    }

    @Test
    public void testHtmlWithStyle() throws IOException {
        String fileName = "styled.pdf";
        String html = "<h1 style='color:blue;'>Blue Title</h1><p style='background:#eee;'>Background Test</p>";

        pdfSkill.create(fileName, html, "html");
        Assertions.assertTrue(Files.exists(Paths.get(workDir, fileName)));
    }

    @Test
    public void testSecurityBounds() {
        // 验证路径穿越防御
        Assertions.assertThrows(SecurityException.class, () -> {
            pdfSkill.parse("../../../etc/passwd");
        });
    }

    // --- 2. Agent 深度集成测试 ---

    @Test
    public void testAgentComplexTask() throws Throwable {
        SimpleAgent agent = SimpleAgent.of(LlmUtil.getChatModel())
                .role("财务助手")
                .defaultSkillAdd(pdfSkill)
                .build();

        // 模拟一个需要 AI 思考、排版并生成文件的任务
        String query = "帮我生成一个简单的 2026 年 1 月份开支预算表，保存为 'budget.pdf'。请使用 Markdown 格式绘制表格。";

        System.out.println("[Agent 运行中...]");
        SimpleResponse resp = agent.prompt(query).call();
        System.out.println("[AI 回复]: " + resp.getContent());

        Path targetPdf = Paths.get(workDir, "budget.pdf");
        Assertions.assertTrue(Files.exists(targetPdf), "AI 必须成功调用工具生成 budget.pdf");

        // 进一步校验：读取生成的 PDF 看看 AI 写的对不对
        String content = pdfSkill.parse("budget.pdf");
        System.out.println("[PDF 内容快照]: " + content);
        Assertions.assertTrue(content.length() > 0);
    }
}