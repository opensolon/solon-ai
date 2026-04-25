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

    /**
     * PDF 文件读写根目录。
     * <p>
     * 所有传入文件名都会基于该目录解析，并通过路径归一化防止越权访问。
     */
    private final Path rootPath;

    /**
     * PDF 渲染字体流供应器。
     * <p>
     * 通过 {@link Supplier} 延迟打开字体流，确保每次渲染都能获得新的输入流。
     * 当值为 {@code null} 时，使用 OpenHTMLToPDF 的默认字体回退机制。
     */
    private final Supplier<InputStream> fontSupplier;

    /**
     * 单次读取文本内容的最大字符数。
     */
    private static final int MAX_READ_LEN = 1024 * 30;

    /**
     * 基础构造。
     * <p>
     * 会自动尝试发现系统中的中文/CJK 字体；若未发现可用字体，则回退到默认字体。
     *
     * @param workDir PDF 文件读写根目录
     */
    public PdfSkill(String workDir) {
        this(workDir, PdfFontResolver.resolveSupplier());
    }

    /**
     * 高级构造。
     * <p>
     * 允许传入自定义字体流供应器，例如来自 classpath resources、外部字体文件或业务配置。
     *
     * @param workDir      PDF 文件读写根目录
     * @param fontSupplier 字体流供应器；为 {@code null} 时使用默认字体回退
     */
    public PdfSkill(String workDir, Supplier<InputStream> fontSupplier) {
        this.rootPath = Paths.get(workDir).toAbsolutePath().normalize();
        this.fontSupplier = fontSupplier;
    }

    @Override
    public String name() {
        return "pdf_tool";
    }

    @Override
    public String description() {
        return "PDF 专家：解析 PDF 内容，或将 Markdown/HTML 转换成 PDF。支持富文本和基础排版。";
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        return true;
    }

    /**
     * 生成 PDF 文件。
     *
     * @param fileName PDF 文件名，未包含 {@code .pdf} 后缀时会自动追加
     * @param content  待写入内容
     * @param format   内容格式，支持 {@code markdown}、{@code html}、{@code text}
     * @return 生成结果描述
     */
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
     * 解析 PDF 文件文本内容。
     *
     * @param fileName PDF 文件名
     * @return 解析出的文本内容，或错误描述
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

    /**
     * 将输入内容转换为可渲染的 HTML 文档。
     *
     * @param content 输入内容
     * @param format  输入格式
     * @return 完整 HTML 文档字符串
     */
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

    /**
     * 将文件名解析为工作目录内的安全路径。
     *
     * @param name 文件名或相对路径
     * @return 归一化后的目标路径
     * @throws SecurityException 当路径越过工作目录边界时抛出
     */
    private Path resolvePath(String name) {
        Path p = rootPath.resolve(name).normalize();
        if (!p.startsWith(rootPath)) throw new SecurityException("非法路径访问");
        return p;
    }
}
