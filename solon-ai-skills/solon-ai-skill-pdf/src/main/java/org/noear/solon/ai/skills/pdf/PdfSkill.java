package org.noear.solon.ai.skills.pdf;

import com.openhtmltopdf.extend.FSSupplier;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Supplier;

/**
 * PDF 专家技能：提供 PDF 文档的结构化读取与精美排版生成能力。
 *
 * <p>核心职责：
 * <ul>
 * <li><b>双向转换</b>：支持将 PDF 提取为纯文本（Parse），以及将 Markdown/HTML 渲染为 PDF（Create）。</li>
 * <li><b>字体引擎解耦</b>：通过 {@code Supplier<InputStream>} 支持灵活的字体注入，完美解决中文字体渲染及跨环境（如容器）部署问题。</li>
 * <li><b>排版支持</b>：内置基础 CSS 样式，确保生成的文档具备良好的可读性（支持表格、代码块及行高优化）。</li>
 * </ul>
 * </p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class PdfSkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(PdfSkill.class);
    private final Path rootPath;
    private final Supplier<InputStream> fontSupplier; // 使用 Supplier 延迟加载流
    private static final int MAX_READ_LEN = 1024 * 30;

    /**
     * 基础构造：不强制要求字体（默认西文）
     */
    public PdfSkill(String workDir) {
        this(workDir, null);
    }

    /**
     * 高级构造：允许传入自定义字体流（如来自 resources 或外部文件）
     */
    public PdfSkill(String workDir, Supplier<InputStream> fontSupplier) {
        this.rootPath = Paths.get(workDir).toAbsolutePath().normalize();
        this.fontSupplier = fontSupplier;
    }

    @Override
    public String name() { return "pdf_tool"; }

    @Override
    public String description() {
        return "PDF 专家：解析 PDF 内容，或将 Markdown/HTML 转换成 PDF。支持富文本和基础排版。";
    }

    @Override
    public boolean isSupported(Prompt prompt) { return true; }

    @ToolMapping(name = "pdf_create", description = "生成 PDF。format: 'markdown', 'html', 'text'。")
    public String create(@Param("fileName") String fileName,
                         @Param("content") String content,
                         @Param("format") String format) {
        try {
            Path target = resolvePath(fileName.toLowerCase().endsWith(".pdf") ? fileName : fileName + ".pdf");
            String html = convertToHtml(content, format);

            try (OutputStream os = new FileOutputStream(target.toFile())) {
                PdfRendererBuilder builder = new PdfRendererBuilder();
                builder.useFastMode();

                // 核心改动：不再寻找具体路径，而是通过流加载
                if (fontSupplier != null) {
                    FSSupplier<InputStream> supplier = () -> fontSupplier.get();
                    builder.useFont(supplier, "BaseFont");
                }

                builder.withHtmlContent(html, "/");
                builder.toStream(os);
                builder.run();
            }
            return "PDF 生成成功: " + target.getFileName();
        } catch (Exception e) {
            LOG.error("Create PDF error", e);
            return "生成失败: " + e.getMessage();
        }
    }

    /**
     * 解析功能（保持不变）
     */
    @ToolMapping(name = "pdf_parse", description = "读取本地 PDF 文件的文本。")
    public String parse(@Param("fileName") String fileName) {
        File file = resolvePath(fileName).toFile();
        if (!file.exists()) return "错误：文件不存在。";
        try (PDDocument document = PDDocument.load(file)) {
            return new PDFTextStripper().getText(document);
        } catch (Exception e) {
            return "解析失败: " + e.getMessage();
        }
    }

    private String convertToHtml(String content, String format) {
        String body;
        if ("markdown".equalsIgnoreCase(format)) {
            body = HtmlRenderer.builder().build().render(Parser.builder().build().parse(content));
        } else if ("text".equalsIgnoreCase(format)) {
            body = "<pre style='white-space: pre-wrap;'>" + content + "</pre>";
        } else {
            body = content;
        }

        String fontCss = (fontSupplier != null) ? "font-family: 'BaseFont', sans-serif;" : "font-family: sans-serif;";

        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\" /><style>" +
                "body { " + fontCss + " line-height: 1.6; padding: 20px; }" +
                "table { border-collapse: collapse; width: 100%; }" +
                "th, td { border: 1px solid #ccc; padding: 8px; }" +
                "</style></head><body>" + body + "</body></html>";
    }

    private Path resolvePath(String name) {
        Path p = rootPath.resolve(name).normalize();
        if (!p.startsWith(rootPath)) throw new SecurityException("非法路径访问");
        return p;
    }
}