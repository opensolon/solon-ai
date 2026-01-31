package org.noear.solon.ai.skills.file;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * PDF 解析技能（Java 8 兼容）
 * @author noear
 * @since 3.9.1
 */
public class PdfParserSkill extends AbsSkill {
    private static final Logger log = LoggerFactory.getLogger(PdfParserSkill.class);
    private final Path rootPath;
    // 限制提取文本长度，避免撑爆模型上下文
    private static final int MAX_TEXT_LEN = 1024 * 30;

    public PdfParserSkill(String workDir) {
        this.rootPath = Paths.get(workDir).toAbsolutePath().normalize();
    }

    @Override
    public String name() { return "pdf_parser"; }

    @Override
    public String description() {
        return "PDF 专家：能够解析并提取本地 PDF 文件的文字内容。";
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        String content = prompt.getUserContent().toLowerCase();
        return content.contains("pdf") || content.contains("解析") || content.contains("读取文档");
    }

    @ToolMapping(name = "parse_pdf", description = "提取指定 PDF 文件的内容。完成后请向用户总结其要点。")
    public String parse(@Param("fileName") String fileName) {
        File file = resolvePath(fileName).toFile();
        if (!file.exists()) {
            return "文件不存在: " + fileName;
        }

        // 使用 try-with-resources 确保资源释放（Java 7/8 特性）
        try (PDDocument document = PDDocument.load(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true); // 保证阅读顺序

            String text = stripper.getText(document);

            if (text == null || text.trim().isEmpty()) {
                return "未能从 PDF 中提取出有效文字（可能是扫描件或受损文件）。";
            }

            if (text.length() > MAX_TEXT_LEN) {
                return text.substring(0, MAX_TEXT_LEN) + "\n...(内容较长，已截断展示)...";
            }
            return text;
        } catch (Exception e) {
            log.error("PDF parse error: {}", fileName, e);
            return "解析 PDF 失败: " + e.getMessage();
        }
    }

    private Path resolvePath(String name) {
        Path p = rootPath.resolve(name).normalize();
        if (!p.startsWith(rootPath)) {
            throw new SecurityException("非法的文件访问路径");
        }
        return p;
    }
}