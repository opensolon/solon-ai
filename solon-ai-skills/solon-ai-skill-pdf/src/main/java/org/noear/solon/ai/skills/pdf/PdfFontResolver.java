package org.noear.solon.ai.skills.pdf;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.function.Supplier;

/**
 * PDF 字体解析器。
 * <p>
 * 用于为 PDF 渲染过程发现可用的中文/CJK 字体。优先读取用户显式配置的字体路径，
 * 未配置时再按常见操作系统字体路径查找；若没有发现可用字体，则返回 {@code null}，
 * 由 {@link PdfSkill} 继续使用 OpenHTMLToPDF 的默认字体回退机制。
 *
 * @author noear
 * @since 3.10.4
 */
public final class PdfFontResolver {
    /**
     * JVM 系统属性字体路径配置项。
     * <p>
     * 示例：{@code -Dsolon.ai.pdf.font.path=/path/to/font.ttf}
     */
    static final String FONT_PATH_PROPERTY = "solon.ai.pdf.font.path";

    /**
     * 环境变量字体路径配置项。
     * <p>
     * 示例：{@code SOLON_AI_PDF_FONT_PATH=/path/to/font.ttf}
     */
    static final String FONT_PATH_ENV = "SOLON_AI_PDF_FONT_PATH";

    /**
     * 常见平台的中文/CJK 字体候选路径。
     * <p>
     * 仅引用系统字体路径，不随组件打包字体文件，避免引入额外授权风险。
     */
    private static final String[] DEFAULT_FONT_PATHS = new String[]{
            "C:\\Windows\\Fonts\\msyh.ttc",
            "C:\\Windows\\Fonts\\msyh.ttf",
            "C:\\Windows\\Fonts\\simhei.ttf",
            "/System/Library/Fonts/PingFang.ttc",
            "/Library/Fonts/Arial Unicode.ttf",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttf",
            "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
            "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttf"
    };

    private PdfFontResolver() {
    }

    /**
     * 解析默认字体流供应器。
     * <p>
     * 解析顺序：JVM 系统属性、环境变量、常见系统字体路径。
     *
     * @return 字体流供应器；未发现可用字体时返回 {@code null}
     */
    static Supplier<InputStream> resolveSupplier() {
        String configuredPath = System.getProperty(FONT_PATH_PROPERTY);
        if (isBlank(configuredPath)) {
            configuredPath = System.getenv(FONT_PATH_ENV);
        }

        return resolveSupplier(configuredPath, DEFAULT_FONT_PATHS);
    }

    /**
     * 根据指定路径解析字体流供应器。
     * <p>
     * 每次调用 {@link Supplier#get()} 都会重新打开字体文件流，避免复用已关闭的流。
     *
     * @param configuredPath 用户显式配置的字体路径，可为空
     * @param fontPaths      默认字体候选路径数组
     * @return 字体流供应器；未发现可用字体时返回 {@code null}
     */
    static Supplier<InputStream> resolveSupplier(String configuredPath, String[] fontPaths) {
        final File fontFile = resolveFontFile(configuredPath, fontPaths);
        if (fontFile == null) {
            return null;
        }

        return () -> {
            try {
                return Files.newInputStream(fontFile.toPath());
            } catch (Exception e) {
                return null;
            }
        };
    }

    /**
     * 解析实际可用的字体文件。
     * <p>
     * 用户显式配置优先级高于默认候选路径；不存在或不是文件的路径会被忽略。
     *
     * @param configuredPath 用户显式配置的字体路径，可为空
     * @param fontPaths      默认字体候选路径数组
     * @return 可用字体文件；未发现时返回 {@code null}
     */
    static File resolveFontFile(String configuredPath, String[] fontPaths) {
        if (!isBlank(configuredPath)) {
            File file = new File(configuredPath.trim());
            if (file.isFile()) {
                return file;
            }
        }

        for (String path : fontPaths) {
            if (isBlank(path)) {
                continue;
            }

            File file = new File(path);
            if (file.isFile()) {
                return file;
            }
        }

        return null;
    }

    /**
     * 判断字符串是否为空白。
     *
     * @param text 待判断字符串
     * @return 为空或仅包含空白字符时返回 {@code true}
     */
    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }
}
