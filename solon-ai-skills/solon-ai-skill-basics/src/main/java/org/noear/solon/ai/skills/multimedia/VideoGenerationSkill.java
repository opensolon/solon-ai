package org.noear.solon.ai.skills.multimedia;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

/**
 * 视频生成技能插件
 *
 * @author noear
 * @since 3.9.1
 */
public class VideoGenerationSkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(VideoGenerationSkill.class);

    private final VideoDriver driver;
    private final String apiKey;
    private final Path rootPath;

    public VideoGenerationSkill(VideoDriver driver, String apiKey, String workDir) {
        this.driver = driver;
        this.apiKey = apiKey;
        this.rootPath = Paths.get(workDir).toAbsolutePath().normalize();
        ensureDir();
    }

    @Override
    public String name() {
        return "video_generator";
    }

    @Override
    public String description() {
        return "视频专家：能够根据文字描述生成短视频。支持指定时长（seconds）和横竖屏比例（aspectRatio）。";
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        String content = prompt.getUserContent().toLowerCase();
        // 意图识别优化：确保只有在涉及媒体创作场景时才触发昂贵的视频 API
        return content.contains("视频") || content.contains("video") ||
                content.contains("短片") || content.contains("制作") || content.contains("mp4");
    }

    /**
     * 执行视频生成
     *
     * @param prompt      视频内容描述
     * @param seconds     期望时长（秒），可选，默认 5 秒
     * @param aspectRatio 视频比例（如 16:9, 9:16），可选，默认 16:9
     * @param fileName    建议的文件名（如 promo.mp4）
     */
    @ToolMapping(name = "generate_video", description = "根据文字提示词生成一段短视频并保存。建议指定比例：横屏 16:9，竖屏 9:16。")
    public String generate(@Param("prompt") String prompt,
                           @Param("seconds") Integer seconds,
                           @Param("aspectRatio") String aspectRatio,
                           @Param("fileName") String fileName) {
        try {
            // 1. 文件名与路径安全处理
            String finalName = sanitizeFileName(fileName);
            Path targetPath = resolvePath(finalName);

            // 2. 自动递增处理重名文件
            targetPath = getUniquePath(targetPath);

            // 3. 参数容错
            int finalSeconds = (seconds == null || seconds <= 0) ? 5 : seconds;
            String finalRatio = Assert.isBlank(aspectRatio) ? "16:9" : aspectRatio;

            // 4. 调用驱动（驱动内负责异步轮询与下载）
            LOG.info("AI Generating video -> Prompt: {}, Target: {}, Config: {}s/{}",
                    prompt, targetPath.getFileName(), finalSeconds, finalRatio);

            String result = driver.generate(prompt, finalSeconds, finalRatio, targetPath, apiKey);

            // 5. 返回结构化描述，方便 AI 在上下文里准确引用此文件（如后续发邮件）
            return result + " [File: " + targetPath.getFileName() + ", Duration: " + finalSeconds + "s, Ratio: " + finalRatio + "]";
        } catch (SecurityException se) {
            return "权限错误: " + se.getMessage();
        } catch (Exception e) {
            LOG.error("Video generation failed: {}", prompt, e);
            return "生成视频失败: " + e.getMessage();
        }
    }

    private String sanitizeFileName(String name) {
        if (Assert.isBlank(name)) {
            return "v_" + UUID.randomUUID().toString().substring(0, 8) + ".mp4";
        }
        // 移除潜在的路径穿越字符
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return name.toLowerCase().endsWith(".mp4") ? name : name + ".mp4";
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
            throw new SecurityException("非法的文件路径访问: " + fileName);
        }
        return p;
    }

    /**
     * 视频生成驱动接口
     */
    public interface VideoDriver {
        /**
         * @param prompt      提示词
         * @param seconds     时长
         * @param aspectRatio 比例
         * @param savePath    本地保存路径
         * @param apiKey      API 密钥
         */
        String generate(String prompt, int seconds, String aspectRatio, Path savePath, String apiKey) throws Exception;
    }

    // --- 驱动示例 ---

    /**
     * 模拟 Sora 驱动实现
     */
    public static final VideoDriver SORA = (prompt, seconds, ratio, savePath, key) -> {
        LOG.info("Sora driver processing... (Mock)");
        // 模拟下载动作（实际需调用驱动工具类下载 URL）
        // ImageGenerationSkill.downloadImage("https://example.com/video.mp4", savePath);
        return "视频生成任务已圆满完成";
    };
}