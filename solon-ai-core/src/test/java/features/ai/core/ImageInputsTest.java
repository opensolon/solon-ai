package features.ai.core;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.ImageInputs;
import org.noear.solon.ai.chat.content.TextBlock;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 图片准备：尺寸算法、文件头识别，以及工具结果图片的规范化（MCP 截图走这里）
 */
public class ImageInputsTest {
    @Test
    void targetSizeFollowsAnthropicReference() {
        // 官方示例：1920x1080 在标准档缩到 1456x819（不是按长边 1568 缩到 1568x882）
        assertArrayEquals(new int[]{1456, 819}, ImageInputs.targetSize(1920, 1080));
        assertEquals(1560, ImageInputs.tokensOf(1456, 819));
        // 竖图与小图
        assertArrayEquals(new int[]{819, 1456}, ImageInputs.targetSize(1080, 1920));
        assertArrayEquals(new int[]{800, 600}, ImageInputs.targetSize(800, 600));
        // 长条图受长边约束
        int[] strip = ImageInputs.targetSize(5000, 100);
        assertTrue(strip[0] <= ImageInputs.MAX_EDGE);
        assertTrue(ImageInputs.tokensOf(strip[0], strip[1]) <= ImageInputs.MAX_TOKENS);
    }

    @Test
    void sniffByHeaderNotExtension() {
        assertEquals(ImageInputs.PNG, ImageInputs.sniffMime(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0}));
        assertEquals(ImageInputs.JPEG, ImageInputs.sniffMime(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}));
        assertEquals(ImageInputs.GIF, ImageInputs.sniffMime("GIF89a".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(ImageInputs.WEBP, ImageInputs.sniffMime("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.US_ASCII)));
        assertNull(ImageInputs.sniffMime("hello".getBytes(StandardCharsets.US_ASCII)));
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setColor(Color.BLUE);
            g.fillRect(width / 4, height / 4, width / 2, height / 2);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", out));
        return out.toByteArray();
    }

    @Test
    void tallScreenshotIsScaledWithinLimits() throws Exception {
        // 整页截图：体积不大，但高度超过 Anthropic 的 8000px 上限
        String base64 = Base64.getEncoder().encodeToString(png(1280, 9000));

        ContentBlock block = ImageInputs.normalizeBase64(base64, "image/png");

        ImageBlock image = assertInstanceOf(ImageBlock.class, block);
        BufferedImage sent = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(image.getData())));
        assertTrue(sent.getHeight() <= ImageInputs.MAX_EDGE, "height=" + sent.getHeight());
        assertTrue(ImageInputs.tokensOf(sent.getWidth(), sent.getHeight()) <= ImageInputs.MAX_TOKENS);
        assertEquals(sent.getWidth(), image.metas().get("width"));
        assertEquals(sent.getHeight(), image.metas().get("height"));
    }

    @Test
    void smallImageIsKeptAsIs() throws Exception {
        String base64 = Base64.getEncoder().encodeToString(png(40, 30));

        ImageBlock image = assertInstanceOf(ImageBlock.class, ImageInputs.normalizeBase64(base64, "image/png"));

        assertEquals(base64, image.getData());
        assertEquals("image/png", image.getMimeType());
    }

    @Test
    void mimeFollowsHeaderNotDeclaration() throws Exception {
        // 服务端把 PNG 声明成 image/jpeg：按文件头纠正
        String base64 = Base64.getEncoder().encodeToString(png(40, 30));

        ImageBlock image = assertInstanceOf(ImageBlock.class, ImageInputs.normalizeBase64(base64, "image/jpeg"));

        assertEquals("image/png", image.getMimeType());
    }

    @Test
    void undecodableImageBecomesText() {
        String notBase64 = "%%%not-base64%%%";
        String notImage = Base64.getEncoder().encodeToString("<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(StandardCharsets.UTF_8));
        // 文件头是 PNG，内容损坏
        byte[] broken = new byte[64];
        System.arraycopy(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 0, broken, 0, 8);
        String brokenPng = Base64.getEncoder().encodeToString(broken);

        for (String data : new String[]{notBase64, notImage, brokenPng}) {
            TextBlock text = assertInstanceOf(TextBlock.class, ImageInputs.normalizeBase64(data, "image/svg+xml"), data);
            assertEquals("[1 image omitted: could not be decoded (image/svg+xml).]", text.getContent());
        }
    }

    @Test
    void tooManyPixelsBecomesText() {
        // 只伪造文件头声明 10000x10000（1 亿像素），不解码就拒绝
        String base64 = Base64.getEncoder().encodeToString(pngHeader(10000, 10000));

        TextBlock text = assertInstanceOf(TextBlock.class, ImageInputs.normalizeBase64(base64, "image/png"));

        assertEquals("[1 image omitted: could not be resized below the image size limit.]", text.getContent());
    }

    @Test
    void oversizedSourceBecomesTextWithoutDecoding() {
        StringBuilder huge = new StringBuilder(ImageInputs.MAX_SOURCE_BYTES / 3 * 4 + 8);
        while (huge.length() < ImageInputs.MAX_SOURCE_BYTES / 3 * 4 + 8) {
            huge.append("AAAA");
        }

        TextBlock text = assertInstanceOf(TextBlock.class, ImageInputs.normalizeBase64(huge.toString(), "image/png"));

        assertTrue(text.getContent().contains("size limit"), text.getContent());
    }

    /** 只有签名与 IHDR 的 PNG：ImageIO 能读出宽高，读不出像素 */
    private static byte[] pngHeader(int width, int height) {
        ByteBuffer buf = ByteBuffer.allocate(8 + 25);
        buf.put(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        buf.putInt(13);
        ByteBuffer ihdr = ByteBuffer.allocate(17);
        ihdr.put("IHDR".getBytes(StandardCharsets.US_ASCII)).putInt(width).putInt(height)
                .put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        buf.put(ihdr.array());
        CRC32 crc = new CRC32();
        crc.update(ihdr.array());
        buf.putInt((int) crc.getValue());
        return buf.array();
    }
}
