package org.noear.solon.ai.talents.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * 子进程输出解码：UTF-8 严格校验优先，遗留代码页（GBK 等）兜底；流式增量解码不截断多字节字符。
 *
 * <p>用显式 fallback 构造，避免依赖运行平台的 sun.jnu.encoding。</p>
 */
public class OutputDecoderTest {
    private static final Charset GBK = Charset.forName("GBK");

    private static String decodeAllWith(byte[] raw, Charset primary, Charset fallback) {
        return new String(raw, OutputDecoder.select(raw, 0, raw.length, primary, fallback));
    }

    @Test
    public void utf8BytesKeepPrimary() {
        byte[] raw = "你好 world".getBytes(StandardCharsets.UTF_8);
        assertEquals("你好 world", decodeAllWith(raw, StandardCharsets.UTF_8, GBK));
    }

    @Test
    public void gbkBytesFallBackToLegacy() {
        byte[] raw = "中文乱码测试".getBytes(GBK);
        assertEquals("中文乱码测试", decodeAllWith(raw, StandardCharsets.UTF_8, GBK));
    }

    @Test
    public void asciiUnaffected() {
        byte[] raw = "echo hello world".getBytes(StandardCharsets.UTF_8);
        assertEquals("echo hello world", decodeAllWith(raw, StandardCharsets.UTF_8, GBK));
    }

    /**
     * 关键回归：合法 UTF-8 里出现替换符（工具自身打印 U+FFFD）不得触发回退。
     * 旧实现按「替换符数量」比较，GBK 几乎不产生替换符，会把整段正确文本重解成乱码。
     */
    @Test
    public void legitReplacementCharDoesNotTriggerFallback() {
        String text = "正常输出\uFFFD继续中文";
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        assertSame(StandardCharsets.UTF_8, OutputDecoder.select(raw, 0, raw.length, StandardCharsets.UTF_8, GBK));
        assertEquals(text, decodeAllWith(raw, StandardCharsets.UTF_8, GBK));
    }

    /**
     * 输出被上限截断、正好切在多字节字符中间：尾部不完整序列不算非法，不得整段回退。
     */
    @Test
    public void truncatedTailDoesNotTriggerFallback() {
        byte[] full = "中文输出".getBytes(StandardCharsets.UTF_8);
        byte[] cut = Arrays.copyOf(full, full.length - 1);
        assertSame(StandardCharsets.UTF_8, OutputDecoder.select(cut, 0, cut.length, StandardCharsets.UTF_8, GBK));
    }

    @Test
    public void emptyInput() {
        assertEquals("", OutputDecoder.decodeAll(null, StandardCharsets.UTF_8));
        assertEquals("", OutputDecoder.decodeAll(new byte[0], StandardCharsets.UTF_8));
    }

    /**
     * 流式增量解码：多字节字符被分块边界切开也不产生替换符。
     */
    @Test
    public void streamingKeepsMultiByteCharAcrossChunks() {
        byte[] raw = "第一段中文第二段中文".getBytes(StandardCharsets.UTF_8);
        OutputDecoder decoder = new OutputDecoder(StandardCharsets.UTF_8, GBK);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length; i++) { // 逐字节喂入：最极端的切分
            sb.append(decoder.decode(new byte[]{raw[i]}, 1));
        }
        sb.append(decoder.flush());

        String result = sb.toString();
        assertEquals("第一段中文第二段中文", result);
        assertFalse(result.contains("\uFFFD"), "不应出现替换符: " + result);
    }

    @Test
    public void streamingAsciiThenChinese() {
        OutputDecoder decoder = new OutputDecoder(StandardCharsets.UTF_8, GBK);
        String head = decoder.decode("plain ascii\n".getBytes(StandardCharsets.UTF_8), 12);
        byte[] cn = "中文".getBytes(StandardCharsets.UTF_8);
        String tail = decoder.decode(cn, cn.length) + decoder.flush();
        assertEquals("plain ascii\n", head);
        assertEquals("中文", tail);
    }

    /**
     * 流式场景下的遗留代码页兜底：首个含非 ASCII 的分块决定字符集。
     */
    @Test
    public void streamingFallsBackForGbkOutput() {
        byte[] raw = "中文输出".getBytes(GBK);
        OutputDecoder decoder = new OutputDecoder(StandardCharsets.UTF_8, GBK);
        String result = decoder.decode(raw, raw.length) + decoder.flush();
        assertEquals("中文输出", result);
    }

    /**
     * 关键回归：分块正好切在「首个非 ASCII 字节」处时不得据此锁定字符集。
     *
     * <p>单个 GBK 前导字节（如 0xD6）在「未到结尾」的严格校验下对 UTF-8 合法，若立即锁定
     * UTF-8，后续整段 GBK 输出都会变成乱码。此时应先输出 ASCII 前缀、把尾部留到下一段。</p>
     */
    @Test
    public void streamingDefersDecisionWhenNonAsciiSampleTooShort() {
        byte[] cn = "中文输出".getBytes(GBK);
        OutputDecoder decoder = new OutputDecoder(StandardCharsets.UTF_8, GBK);

        // 第一段：ASCII 前缀 + GBK 首字节（样本不足，不能判定）
        byte[] first = {'o', 'k', ':', cn[0]};
        String head = decoder.decode(first, first.length);
        assertEquals("ok:", head, "应只输出无歧义的 ASCII 前缀");
        assertNull(decoder.selectedCharset(), "样本不足时不应锁定字符集");

        // 第二段：其余字节到齐后再判定
        byte[] rest = new byte[cn.length - 1];
        System.arraycopy(cn, 1, rest, 0, rest.length);
        String tail = decoder.decode(rest, rest.length) + decoder.flush();

        assertEquals(GBK, decoder.selectedCharset());
        assertEquals("ok:中文输出", head + tail);
    }

    /**
     * 延迟判定的尾部不能丢：流在样本凑够之前就结束时，flush 必须按现有字节判定并输出。
     */
    @Test
    public void flushEmitsDeferredTailWhenStreamEndsEarly() {
        byte[] cn = "中".getBytes(GBK); // 仅 2 字节，永远凑不满判定样本
        OutputDecoder decoder = new OutputDecoder(StandardCharsets.UTF_8, GBK);
        String head = decoder.decode(cn, cn.length);
        assertEquals("", head);
        assertEquals("中", decoder.flush(), "延迟判定的尾部必须在 flush 时输出，不能丢");
    }

    /**
     * 未出现非 ASCII 字节时不锁定字符集（供同步路径判断是否复用流式结论）。
     */
    @Test
    public void selectedCharsetNullWhileAllAscii() {
        OutputDecoder decoder = new OutputDecoder(StandardCharsets.UTF_8, GBK);
        assertEquals("hello", decoder.decode("hello".getBytes(StandardCharsets.UTF_8), 5));
        assertNull(decoder.selectedCharset());
        assertEquals("", decoder.flush());
    }
}
