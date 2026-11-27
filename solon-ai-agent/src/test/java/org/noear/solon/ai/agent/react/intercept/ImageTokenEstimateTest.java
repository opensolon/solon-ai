package org.noear.solon.ai.agent.react.intercept;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.content.ImageBlock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 图片按视觉 token 估算，与 base64 长度无关
 */
public class ImageTokenEstimateTest {
    private static String base64Of(int chars) {
        StringBuilder buf = new StringBuilder(chars);
        for (int i = 0; i < chars; i++) {
            buf.append('A');
        }
        return buf.toString();
    }

    @Test
    void sizedImageUsesPatchFormula() {
        // 约 300KB 原图的 base64：按字符换算会估成约 10 万 token
        ImageBlock image = ImageBlock.ofBase64(base64Of(400_000), "image/jpeg")
                .metaAdd("width", 1456).metaAdd("height", 819);
        assertEquals(1560, ContextCompressionInterceptor.estimateImageTokens(image));
    }

    @Test
    void unsizedImageUsesDefault() {
        ImageBlock image = ImageBlock.ofBase64(base64Of(400_000), "image/png");
        assertEquals(ContextCompressionInterceptor.IMAGE_TOKEN_DEFAULT, ContextCompressionInterceptor.estimateImageTokens(image));
    }

    @Test
    void hugeImageIsCapped() {
        ImageBlock image = ImageBlock.ofBase64("AAAA", "image/png").metaAdd("width", "8000").metaAdd("height", "8000");
        assertEquals(ContextCompressionInterceptor.IMAGE_TOKEN_MAX, ContextCompressionInterceptor.estimateImageTokens(image));
    }
}
