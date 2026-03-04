package org.noear.solon.ai.skills.generation;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.UUID;

/**
 * 绘图生成技能：为 AI 提供多模态视觉创作能力。
 *
 * <p>该技能集成了多种绘图驱动（如 DALL-E, Midjourney 等），允许 Agent 根据自然语言描述生成图像并持久化到本地。
 * 核心特性：
 * <ul>
 * <li><b>智能命名</b>：支持 AI 建议文件名，并自动处理物理文件名冲突。</li>
 * <li><b>安全沙箱</b>：强制生成物存储在指定的 WorkDir，防止路径逃逸。</li>
 * <li><b>链式友好</b>：显式返回保存后的物理路径，方便后续技能（如文件打包、邮件发送）直接引用。</li>
 * </ul>
 * </p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class ImageGenerationSkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(ImageGenerationSkill.class);

    private final ImageDriver driver;
    private final String apiKey;
    private final Path rootPath;

    public ImageGenerationSkill(ImageDriver driver, String apiKey, String workDir) {
        this.driver = driver;
        this.apiKey = apiKey;
        this.rootPath = Paths.get(workDir).toAbsolutePath().normalize();
        ensureDir();
    }

    @Override
    public String name() { return "image_generator"; }

    @Override
    public String description() {
        return "绘图专家：能够根据文字描述生成精美的图片。尺寸建议：头像 512x512，标准 1024x1024，海报 1024x1792。";
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        String content = prompt.getUserContent().toLowerCase();
        // 意图识别：捕获绘画相关的动词和名词
        return content.contains("画") || content.contains("生成图片") ||
                content.contains("image") || content.contains("draw") || content.contains("pic");
    }

    /**
     * 执行图片生成
     *
     * @param prompt   提示词（描述图片内容）
     * @param size     尺寸（如 1024x1024）
     * @param fileName 建议的文件名（可选，如 design.png）
     */
    @ToolMapping(name = "generate_image", description = "根据提示词生成图片并保存到本地。你可以指定图片尺寸，完成后请告知用户文件名。")
    public String generate(@Param("prompt") String prompt,
                           @Param("size") String size,
                           @Param("fileName") String fileName) {
        try {
            // 1. 文件名安全与自动生成
            String finalName = sanitizeFileName(fileName);
            Path targetPath = resolvePath(finalName);

            // 2. 自动处理重名（防止覆盖旧图）
            targetPath = getUniquePath(targetPath);

            // 3. 调用驱动生成逻辑
            LOG.info("AI Generating image -> Size: {}, Prompt: {}", (size == null ? "1024x1024" : size), prompt);
            String result = driver.generate(prompt, (size == null ? "1024x1024" : size), targetPath, apiKey);

            // 4. 返回明确的文件名，确保 AI 在后续链式操作（如打包或发邮件）中能精准找到该文件
            return result + " [Saved as: " + targetPath.getFileName().toString() + "]";
        } catch (SecurityException se) {
            return "权限安全限制: " + se.getMessage();
        } catch (Exception e) {
            LOG.error("Image generation failed: {}", prompt, e);
            return "生成图片失败: " + e.getMessage();
        }
    }

    private String sanitizeFileName(String name) {
        if (Assert.isBlank(name)) {
            return "img_" + UUID.randomUUID().toString().substring(0, 8) + ".png";
        }
        // 简单处理路径字符，防止非法注入
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return name.toLowerCase().contains(".") ? name : name + ".png";
    }

    private Path getUniquePath(Path path) {
        if (!Files.exists(path)) return path;

        String fileName = path.getFileName().toString();
        int lastDot = fileName.lastIndexOf('.');
        String base = (lastDot == -1) ? fileName : fileName.substring(0, lastDot);
        String ext = (lastDot == -1) ? "" : fileName.substring(lastDot);

        int count = 1;
        while (Files.exists(path)) {
            path = path.getParent().resolve(base + "_" + (count++) + ext);
        }
        return path;
    }

    private void ensureDir() {
        try {
            if (!Files.exists(rootPath)) Files.createDirectories(rootPath);
        } catch (IOException ignored) {}
    }

    private Path resolvePath(String fileName) {
        Path p = rootPath.resolve(fileName).normalize();
        if (!p.startsWith(rootPath)) {
            throw new SecurityException("非法的文件名访问路径: " + fileName);
        }
        return p;
    }

    // --- 驱动接口 ---

    public interface ImageDriver {
        /**
         * @param prompt   提示词
         * @param size     尺寸
         * @param savePath 本地保存路径
         * @param apiKey   API密钥
         */
        String generate(String prompt, String size, Path savePath, String apiKey) throws Exception;
    }

    // --- 静态辅助工具 ---

    /**
     * 驱动可直接使用的通用下载方法
     */
    public static void downloadImage(String imageUrl, Path savePath) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(imageUrl).openConnection();
        conn.setConnectTimeout(10000); // 10s
        conn.setReadTimeout(60000);    // 60s
        conn.setRequestProperty("User-Agent", "Solon-AI-Agent/1.0");

        try (BufferedInputStream in = new BufferedInputStream(conn.getInputStream())) {
            Files.copy(in, savePath, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            conn.disconnect();
        }
    }

    // --- 典型驱动示例 ---

    /**
     * DALL-E 驱动模拟
     */
    public static final ImageDriver DALL_E = (prompt, size, savePath, key) -> {
        // 1. 发起请求获取图片 URL (此处为模拟)
        // 2. downloadImage(url, savePath);
        return "图片已成功由 DALL-E 生成";
    };
}