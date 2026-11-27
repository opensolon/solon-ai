package org.noear.solon.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.chat.content.ContentBlock;
import org.noear.solon.ai.chat.content.ImageBlock;
import org.noear.solon.ai.chat.content.ImageInputs;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.mount.MountManager;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Random;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

/**
 * read 读图片：按文件头分流、缩放、转码、说明文本与错误分支
 */
public class TerminalReadImageTest {
    @TempDir
    Path work;

    private TerminalTalent talent() {
        return new TerminalTalent(new MountManager(work.toString()));
    }

    private void writeImage(String name, int width, int height, String format) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.ORANGE);
            g.fillRect(0, 0, width, height);
            g.setColor(Color.BLUE);
            g.fillRect(width / 4, height / 4, width / 2, height / 2);
        } finally {
            g.dispose();
        }
        Path file = work.resolve(name);
        Files.createDirectories(file.getParent());
        assertTrue(ImageIO.write(image, format, file.toFile()));
    }

    private static ImageBlock imageOf(ToolResult result) {
        ImageBlock found = null;
        for (ContentBlock block : result.getBlocks()) {
            if (block instanceof ImageBlock) {
                assertNull(found, "只应有一张图");
                found = (ImageBlock) block;
            }
        }
        assertNotNull(found, result.toString());
        return found;
    }

    private static BufferedImage decode(ImageBlock block) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(block.getData())));
    }

    @Test
    void smallPngIsSentAsIs() throws Exception {
        writeImage("docs/a.png", 40, 30, "png");
        byte[] original = Files.readAllBytes(work.resolve("docs/a.png"));

        ToolResult result = talent().read("docs/a.png", null, null, work.toString(), null);
        assertFalse(result.isError(), result.getContent());

        ImageBlock image = imageOf(result);
        assertEquals("image/png", image.getMimeType());
        assertEquals("docs/a.png", image.getFilePath());
        assertArrayEquals(original, Base64.getDecoder().decode(image.getData()));
        assertEquals(40, image.metas().get("width"));
        assertEquals(30, image.metas().get("height"));

        String text = result.getContent();
        assertTrue(text.contains("<path>docs/a.png</path>"), text);
        assertTrue(text.contains("image/png, 40x30 px"), text);
        assertFalse(text.contains("Downscaled"), text);
    }

    @Test
    void largeImageIsDownscaledWithCoordinateFactor() throws Exception {
        writeImage("big.png", 3000, 2000, "png");

        ToolResult result = talent().read("big.png", null, null, work.toString(), null);
        assertFalse(result.isError(), result.getContent());

        ImageBlock image = imageOf(result);
        BufferedImage sent = decode(image);
        assertTrue(sent.getWidth() <= ImageInputs.MAX_EDGE, "width=" + sent.getWidth());
        assertTrue(ImageInputs.tokensOf(sent.getWidth(), sent.getHeight()) <= ImageInputs.MAX_TOKENS);
        assertEquals(sent.getWidth(), image.metas().get("width"));

        String text = result.getContent();
        assertTrue(text.contains("Downscaled from 3000x2000 px"), text);
        String factor = String.format(Locale.ROOT, "%.4f", 3000.0 / sent.getWidth());
        assertTrue(text.contains("multiply coordinates in this image by " + factor), text);
    }

    @Test
    void gifIsConvertedToPng() throws Exception {
        writeImage("anim.gif", 20, 20, "gif");

        ToolResult result = talent().read("anim.gif", null, null, work.toString(), null);
        assertFalse(result.isError(), result.getContent());
        ImageBlock image = imageOf(result);
        assertEquals("image/png", image.getMimeType());
        assertNotNull(decode(image));
    }

    /** 随机噪声图：PNG 压不小，用来模拟大截图 */
    private byte[] noisePng(int width, int height, boolean alpha) throws Exception {
        BufferedImage image = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = random.nextInt(0x1000000);
                image.setRGB(x, y, alpha ? (((x + y) % 2 == 0 ? 0x10 : 0xFF) << 24) | rgb : rgb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", out));
        return out.toByteArray();
    }

    @Test
    void largeOpaquePngIsSentAsJpegWhenSmaller() throws Exception {
        byte[] png = noisePng(600, 600, false);
        assertTrue(png.length > ImageInputs.PNG_PASSTHROUGH_MAX_BYTES, "png=" + png.length);
        Files.write(work.resolve("shot.png"), png);

        ImageBlock image = imageOf(talent().read("shot.png", null, null, work.toString(), null));
        assertEquals("image/jpeg", image.getMimeType());
        assertTrue(Base64.getDecoder().decode(image.getData()).length < png.length);
        assertEquals(600, image.metas().get("width"));
    }

    @Test
    void largeTransparentPngStaysPng() throws Exception {
        byte[] png = noisePng(600, 600, true);
        assertTrue(png.length > ImageInputs.PNG_PASSTHROUGH_MAX_BYTES, "png=" + png.length);
        Files.write(work.resolve("alpha.png"), png);

        ImageBlock image = imageOf(talent().read("alpha.png", null, null, work.toString(), null));
        assertEquals("image/png", image.getMimeType());
        assertArrayEquals(png, Base64.getDecoder().decode(image.getData()));
    }

    @Test
    void imageWithoutExtensionIsReadByHeader() throws Exception {
        writeImage("snapshot", 20, 10, "png");
        writeImage("photo.bin", 20, 10, "jpeg");

        assertEquals("image/png", imageOf(talent().read("snapshot", null, null, work.toString(), null)).getMimeType());
        assertEquals("image/jpeg", imageOf(talent().read("photo.bin", null, null, work.toString(), null)).getMimeType());
    }

    @Test
    void textIsStillReadAsTextEvenWithImageExtension() throws Exception {
        Files.write(work.resolve("notes.txt"), "one\ntwo\n".getBytes(StandardCharsets.UTF_8));
        Files.write(work.resolve("fake.png"), "not an image".getBytes(StandardCharsets.UTF_8));

        ToolResult text = talent().read("notes.txt", null, null, work.toString(), null);
        assertFalse(text.isError(), text.getContent());
        assertTrue(text.getContent().contains("     1 | one"), text.getContent());
        assertTrue(text.getBlocks().stream().noneMatch(b -> b instanceof ImageBlock));

        // 后缀是 .png、内容不是图片：不按图片读
        ToolResult fake = talent().read("fake.png", null, null, work.toString(), null);
        assertTrue(fake.getBlocks().stream().noneMatch(b -> b instanceof ImageBlock));
    }

    @Test
    void imageIgnoresOffsetAndLimit() throws Exception {
        writeImage("a.png", 10, 10, "png");

        ToolResult result = talent().read("a.png", 5, 1, work.toString(), null);
        assertFalse(result.isError(), result.getContent());
        assertEquals("image/png", imageOf(result).getMimeType());
    }

    @Test
    void errorBranches() throws Exception {
        TerminalTalent t = talent();
        String cwd = work.toString();

        // 文件头是 PNG 但内容损坏：解不出来
        Files.write(work.resolve("broken.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3});

        ToolResult missing = t.read("nope.png", null, null, cwd, null);
        assertTrue(missing.isError());

        ToolResult broken = t.read("broken.png", null, null, cwd, "en");
        assertTrue(broken.isError());
        assertTrue(broken.getContent().contains("cannot be decoded"), broken.getContent());

        for (ToolResult r : new ToolResult[]{missing, broken}) {
            for (ContentBlock block : r.getBlocks()) {
                assertFalse(block instanceof ImageBlock, "出错时不带图片");
            }
        }
    }

    /**
     * 只有文件头与 IHDR 的 PNG（几十字节），声明任意宽高：用来模拟「文件很小、像素极多」的图
     */
    static byte[] pngHeader(int width, int height) {
        ByteBuffer ihdr = ByteBuffer.allocate(17);
        ihdr.put("IHDR".getBytes(StandardCharsets.US_ASCII)).putInt(width).putInt(height)
                .put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        CRC32 crc = new CRC32();
        crc.update(ihdr.array());

        ByteBuffer png = ByteBuffer.allocate(8 + 4 + 17 + 4);
        png.put(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        png.putInt(13).put(ihdr.array()).putInt((int) crc.getValue());
        return png.array();
    }

    @Test
    void tooManyPixelsIsRejectedBeforeDecoding() throws Exception {
        // 20000 x 20000 = 4 亿像素：解码要约 1.2GB 内存，文件却只有 33 字节
        Files.write(work.resolve("wide.png"), pngHeader(20000, 20000));

        ToolResult result = talent().read("wide.png", null, null, work.toString(), "en");
        assertTrue(result.isError());
        assertTrue(result.getContent().contains("20000x20000 px"), result.getContent());
        assertTrue(result.getContent().contains("50-megapixel"), result.getContent());

        // 上限以内的只读文件头不会误判为超限（走到解码，因缺 IDAT 而失败）
        Files.write(work.resolve("small.png"), pngHeader(7000, 7000));
        ToolResult small = talent().read("small.png", null, null, work.toString(), "en");
        assertTrue(small.isError());
        assertTrue(small.getContent().contains("cannot be decoded"), small.getContent());
    }

    @Test
    void readImageIsNotASeparateTool() {
        for (FunctionTool tool : talent().getTools(null)) {
            assertNotEquals("read_image", tool.name());
        }
    }
}
