/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.chat.content;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;

/**
 * 发给模型的图片准备：按文件头识别类型、按模型的视觉输入上限缩放、必要时转码。
 * 读文件工具（read）与 MCP 工具结果共用同一套规则。
 *
 * <p>缩放目标取 Anthropic 的参考算法（长边不超过 {@link #MAX_EDGE}，且
 * {@code ⌈w/28⌉ × ⌈h/28⌉} 不超过 {@link #MAX_TOKENS}），这样 Claude 不会再二次缩放，
 * 说明文本里给出的坐标换算倍数是准确的；其他厂商的上限都不比它严。
 * 体积上限 {@link #MAX_SEND_BYTES} 取 Bedrock 与智谱（单张 base64 不超过 5MB）中最严的。</p>
 *
 * <p>只用 JDK 自带的 ImageIO：能解 PNG / JPEG / GIF（取第一帧），解不了 WebP——WebP 在上限内就原样发送。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public final class ImageInputs {
    /** 读入的原图上限（字节） */
    public static final int MAX_SOURCE_BYTES = 20 * 1024 * 1024;
    /**
     * 原图像素上限（5000 万）：解码前先读宽高。压缩率高的 PNG 几 MB 就能声明几万乘几万像素，
     * 解码要几 GB 内存；超过时不解码。
     */
    public static final long MAX_SOURCE_PIXELS = 50_000_000L;
    /** 发送给模型的单张图片上限（原始字节，base64 后约 5MB） */
    public static final int MAX_SEND_BYTES = 3_750_000;
    /**
     * 超过这个大小的不透明 PNG 试转 JPEG，更小才换（原始字节，base64 后约 384KiB，同 opencode）：
     * 截图类 PNG 动辄几 MB，几张就占满请求的媒体总量
     */
    public static final int PNG_PASSTHROUGH_MAX_BYTES = 288 * 1024;
    /** 长边上限（像素） */
    public static final int MAX_EDGE = 1568;
    /** 视觉 token 上限（按 28px 一格计） */
    public static final int MAX_TOKENS = 1568;
    /** 一格视觉 token 对应的边长（像素） */
    public static final int TOKEN_PATCH = 28;

    public static final String PNG = "image/png";
    public static final String JPEG = "image/jpeg";
    public static final String GIF = "image/gif";
    public static final String WEBP = "image/webp";

    private ImageInputs() {
    }

    /** 识别类型要读的文件头字节数（WebP 要看到第 12 个字节） */
    public static final int SNIFF_BYTES = 12;

    /** 按文件头识别图片类型；不是支持的图片返回 null */
    public static String sniffMime(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        if (startsWith(bytes, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return PNG;
        }
        if (startsWith(bytes, 0, 0xFF, 0xD8, 0xFF)) {
            return JPEG;
        }
        if (startsWith(bytes, 0, 'G', 'I', 'F', '8')) {
            return GIF;
        }
        if (startsWith(bytes, 0, 'R', 'I', 'F', 'F') && startsWith(bytes, 8, 'W', 'E', 'B', 'P')) {
            return WEBP;
        }
        return null;
    }

    private static boolean startsWith(byte[] bytes, int offset, int... sig) {
        if (bytes.length < offset + sig.length) {
            return false;
        }
        for (int i = 0; i < sig.length; i++) {
            if ((bytes[offset + i] & 0xFF) != sig[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 按上限算出的目标尺寸（不放大）：长边不超过 {@link #MAX_EDGE}、视觉 token 不超过 {@link #MAX_TOKENS}，保持宽高比
     *
     * @return {宽, 高}
     */
    public static int[] targetSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return new int[]{width, height};
        }
        if (fits(width, height)) {
            return new int[]{width, height};
        }

        // 二分查找最大的长边，使缩放后满足两个上限
        boolean wide = width >= height;
        int longEdge = wide ? width : height;
        int lo = 1;
        int hi = Math.min(longEdge, MAX_EDGE);
        int best = 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int[] size = scaleLongEdge(width, height, mid);
            if (fits(size[0], size[1])) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return scaleLongEdge(width, height, best);
    }

    private static int[] scaleLongEdge(int width, int height, int longEdge) {
        if (width >= height) {
            return new int[]{longEdge, Math.max(1, (int) Math.round((double) height * longEdge / width))};
        } else {
            return new int[]{Math.max(1, (int) Math.round((double) width * longEdge / height)), longEdge};
        }
    }

    private static boolean fits(int width, int height) {
        return width <= MAX_EDGE && height <= MAX_EDGE && tokensOf(width, height) <= MAX_TOKENS;
    }

    /** 视觉 token 估算：{@code ⌈w/28⌉ × ⌈h/28⌉} */
    public static long tokensOf(int width, int height) {
        return (long) ((width + TOKEN_PATCH - 1) / TOKEN_PATCH) * ((height + TOKEN_PATCH - 1) / TOKEN_PATCH);
    }

    /**
     * 规范化工具结果里的 base64 图片（同 opencode 的 normalizeImages）：与 read 同一套缩放、转码规则。
     * 解不了或缩不到上限以内的，换成一行说明文本，免得这张图留在历史里让之后每一轮请求都被拒；
     * 运行环境缺图像组件时原样返回。
     *
     * @param base64 图片数据（不带 data: 前缀）
     * @param mime   声明的类型（仅用于说明文本，实际类型按文件头识别）
     * @return {@link ImageBlock}（有尺寸时带 width/height 元数据）或 {@link TextBlock}
     */
    public static ContentBlock normalizeBase64(String base64, String mime) {
        // 原图上限按 base64 长度先判，不白解码
        if (base64.length() > MAX_SOURCE_BYTES / 3 * 4 + 4) {
            return TextBlock.of(OMITTED_SIZE);
        }

        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            return TextBlock.of(omittedDecode(mime));
        }

        String sniffed = sniffMime(bytes);
        if (sniffed == null) {
            return TextBlock.of(omittedDecode(mime));
        }

        Prepared image;
        try {
            image = prepare(bytes, sniffed);
        } catch (PixelLimitException e) {
            return TextBlock.of(OMITTED_SIZE);
        } catch (IOException e) {
            return TextBlock.of(omittedDecode(mime));
        } catch (RuntimeException | LinkageError e) {
            // 缺图像组件（如裁剪过的运行时）：保留原图，交给方言层的单张上限兜底
            return ImageBlock.ofBase64(base64, mime);
        }
        if (image == null) {
            return TextBlock.of(OMITTED_SIZE);
        }

        String data = image.getBytes() == bytes ? base64 : Base64.getEncoder().encodeToString(image.getBytes());
        ImageBlock block = ImageBlock.ofBase64(data, image.getMime());
        if (image.hasSize()) {
            block.metaAdd("width", image.getWidth()).metaAdd("height", image.getHeight());
        }
        return block;
    }

    private static final String OMITTED_SIZE = "[1 image omitted: could not be resized below the image size limit.]";

    private static String omittedDecode(String mime) {
        return "[1 image omitted: could not be decoded" + (mime == null || mime.isEmpty() ? "" : " (" + mime + ")") + ".]";
    }

    /**
     * 准备要发送的图片
     *
     * @param bytes 原图字节
     * @param mime  按文件头识别出的类型
     * @return 准备好的图片；解不了（WebP 超上限、文件损坏）或缩不到上限以内时返回 null
     * @throws PixelLimitException 原图像素超过 {@link #MAX_SOURCE_PIXELS}
     */
    public static Prepared prepare(byte[] bytes, String mime) throws IOException {
        BufferedImage image = WEBP.equals(mime) ? null : decode(bytes);

        if (image == null) {
            // ImageIO 解不了（WebP，或者文件损坏）：在上限内就原样发送，尺寸未知
            if (WEBP.equals(mime) && bytes.length <= MAX_SEND_BYTES) {
                return new Prepared(bytes, mime, -1, -1, -1, -1);
            }
            return null;
        }

        int width = image.getWidth();
        int height = image.getHeight();
        int[] target = targetSize(width, height);
        boolean resize = target[0] != width || target[1] != height;

        // 不缩放、不必转码（GIF 一律转 PNG：Gemini 的函数响应与智谱都不收 GIF，Anthropic 也只看第一帧）且在体积上限内：原样发送
        boolean passthrough = resize == false && GIF.equals(mime) == false && bytes.length <= MAX_SEND_BYTES;
        if (passthrough && (PNG.equals(mime) == false || bytes.length <= PNG_PASSTHROUGH_MAX_BYTES)) {
            return new Prepared(bytes, mime, width, height, width, height);
        }

        BufferedImage scaled = resize ? scale(image, target[0], target[1]) : image;
        String outMime = JPEG.equals(mime) ? JPEG : PNG;
        byte[] out = passthrough ? bytes : (JPEG.equals(outMime) ? encodeJpeg(scaled) : encodePng(scaled));

        // 大的不透明 PNG（截图）：试转 JPEG，更小才换
        if (PNG.equals(outMime) && out.length > PNG_PASSTHROUGH_MAX_BYTES && isTransparent(scaled) == false) {
            byte[] jpeg = encodeJpeg(scaled);
            if (jpeg.length < out.length) {
                out = jpeg;
                outMime = JPEG;
            }
        }

        // PNG 仍超过上限（透明的大图）：退到 JPEG
        if (out.length > MAX_SEND_BYTES && PNG.equals(outMime)) {
            out = encodeJpeg(scaled);
            outMime = JPEG;
        }

        if (out.length > MAX_SEND_BYTES) {
            return null;
        }

        return new Prepared(out, outMime, width, height, target[0], target[1]);
    }

    /**
     * 解码（GIF 取第一帧）：先只读宽高，超过 {@link #MAX_SOURCE_PIXELS} 时不解码。无法识别时返回 null
     *
     * @throws PixelLimitException 像素超过上限
     */
    private static BufferedImage decode(byte[] bytes) throws IOException {
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (readers.hasNext() == false) {
                return null;
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if ((long) width * height > MAX_SOURCE_PIXELS) {
                    throw new PixelLimitException(width, height);
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    /** 原图像素超过 {@link #MAX_SOURCE_PIXELS} */
    public static final class PixelLimitException extends IOException {
        private final int width;
        private final int height;

        PixelLimitException(int width, int height) {
            super("Image too large: " + width + "x" + height + " px");
            this.width = width;
            this.height = height;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }
    }

    /**
     * 是否有明显的透明区域（同 opencode：64×64 网格采样，alpha &lt; 250 的点超过 5%）。
     * 带透明通道但全不透明的截图算不透明
     */
    private static boolean isTransparent(BufferedImage image) {
        if (image.getColorModel().hasAlpha() == false) {
            return false;
        }
        int grid = 64;
        int samples = 0;
        int transparent = 0;
        for (int gy = 0; gy < grid; gy++) {
            int y = (int) ((long) gy * image.getHeight() / grid);
            for (int gx = 0; gx < grid; gx++) {
                int x = (int) ((long) gx * image.getWidth() / grid);
                samples++;
                if ((image.getRGB(x, y) >>> 24) < 250) {
                    transparent++;
                }
            }
        }
        return transparent * 20 > samples;
    }

    private static BufferedImage scale(BufferedImage src, int width, int height) {
        boolean alpha = src.getColorModel().hasAlpha();
        BufferedImage dst = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (ImageIO.write(image, "png", out) == false) {
            throw new IOException("No PNG writer available");
        }
        return out.toByteArray();
    }

    private static byte[] encodeJpeg(BufferedImage image) throws IOException {
        // JPEG 不支持透明通道：先铺白底
        BufferedImage rgb = image;
        if (image.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, image.getWidth(), image.getHeight());
                g.drawImage(image, 0, 0, null);
            } finally {
                g.dispose();
            }
        }

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (writers.hasNext() == false) {
            throw new IOException("No JPEG writer available");
        }

        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.85f);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** 准备好的图片；尺寸未知时为 -1 */
    public static final class Prepared {
        private final byte[] bytes;
        private final String mime;
        private final int sourceWidth;
        private final int sourceHeight;
        private final int width;
        private final int height;

        Prepared(byte[] bytes, String mime, int sourceWidth, int sourceHeight, int width, int height) {
            this.bytes = bytes;
            this.mime = mime;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.width = width;
            this.height = height;
        }

        /** 要发送的字节（未改动时就是原图字节） */
        public byte[] getBytes() {
            return bytes;
        }

        public String getMime() {
            return mime;
        }

        public int getSourceWidth() {
            return sourceWidth;
        }

        public int getSourceHeight() {
            return sourceHeight;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public boolean isResized() {
            return width > 0 && (width != sourceWidth || height != sourceHeight);
        }

        public boolean hasSize() {
            return width > 0 && height > 0;
        }
    }
}
