package org.noear.solon.ai.talents.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编码判断：只认 UTF-8 与 BOM，不猜其他编码；UTF-8 扫描与 JDK 解码器同口径；保字节往返。
 */
class TextEncodingsTest {
    private static final Charset UTF8 = StandardCharsets.UTF_8;
    private static final Charset GBK = Charset.forName("GBK");

    static final String CN_CODE = "/**\n * 用户服务：负责登录、注册与权限校验。\n * 注意：缓存失效后需要重新加载配置。\n */\n"
            + "public class UserService {\n    // 默认超时时间（毫秒）\n    private int timeout = 3000;\n}\n";

    private static TextEncodings.Decoded detect(byte[] bytes) {
        return TextEncodings.detect(bytes, bytes.length, false, UTF8);
    }

    @Test
    void utf8AndBomSamples() {
        for (String s : Arrays.asList("hello\n", CN_CODE, "emoji \uD83D\uDE00\n")) {
            TextEncodings.Decoded d = detect(s.getBytes(UTF8));
            assertEquals(TextEncodings.Source.PRIMARY, d.source());
            assertEquals("UTF-8", d.label());
            assertEquals(s, d.displayText());
            assertTrue(d.allowsNonAscii());
        }

        byte[] bom8 = cat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, "中文\n".getBytes(UTF8));
        TextEncodings.Decoded d = detect(bom8);
        assertEquals(TextEncodings.Source.BOM, d.source());
        assertEquals("UTF-8 with BOM", d.label());
        assertEquals("中文\n", d.displayText());
        assertEquals(3, d.bomLength());

        byte[] le = cat(new byte[]{(byte) 0xFF, (byte) 0xFE}, "ab中\n".getBytes(StandardCharsets.UTF_16LE));
        d = detect(le);
        assertEquals("UTF-16LE with BOM", d.label());
        assertEquals("ab中\n", d.displayText());
        assertTrue(d.writesBackUnchanged());
        byte[] be = cat(new byte[]{(byte) 0xFE, (byte) 0xFF}, "ab中\n".getBytes(StandardCharsets.UTF_16BE));
        assertEquals("UTF-16BE with BOM", detect(be).label());
        assertEquals(StandardCharsets.UTF_16BE, TextEncodings.utf16BomCharset(be, be.length));
        assertNull(TextEncodings.utf16BomCharset(bom8, bom8.length));
        // UTF-32LE 的 BOM 不当作 UTF-16
        assertNull(TextEncodings.bomCharset(new byte[]{(byte) 0xFF, (byte) 0xFE, 0, 0}, 4));
        // UTF-16 带 BOM、末尾多一个孤字节：按 BOM 判断，孤字节计数并原样保留
        byte[] odd = cat(le, new byte[]{0x41});
        d = detect(odd);
        assertEquals("UTF-16LE with BOM, 1 undecodable byte shown as U+FFFD", d.label());
        assertTrue(d.writesBackUnchanged());
    }

    /** 不猜其他编码：GBK、Big5、Shift_JIS、EUC-KR、windows-1252 一律是“不是 UTF-8”，与运行机器无关。 */
    @Test
    void otherEncodingsAreNotGuessed() throws Exception {
        String[][] samples = {
                {"GBK", CN_CODE},
                {"Big5", "繁體中文測試：這是一段使用正體字書寫的說明文件。\n"},
                {"Shift_JIS", "これは日本語のテストです。設定ファイルを読み込みます。\n"},
                {"EUC-KR", "이것은 한국어 테스트입니다. 설정 파일을 읽습니다.\n"},
                {"windows-1252", "Déjà vu: naïve façade, über café.\n"},
        };
        for (String[] s : samples) {
            byte[] b = s[1].getBytes(Charset.forName(s[0]));
            TextEncodings.Decoded d = detect(b);
            assertEquals(TextEncodings.Source.UNKNOWN, d.source(), s[0] + " -> " + d.label());
            assertEquals(UTF8, d.charset());
            assertTrue(d.label().startsWith("not UTF-8, ") && d.label().endsWith(" undecodable bytes shown as U+FFFD"), d.label());
            assertFalse(d.allowsNonAscii());
            // 坏字节原样保留：解码再编码逐字节相同
            assertTrue(d.writesBackUnchanged());
            assertArrayEquals(b, d.encode(d.text()));
        }
    }

    /** 已知限制：字很少且恰好是合法 UTF-8 的 GBK 文件（“注” = D7 A2 = U+05E2）会被当成 UTF-8；字节仍原样保留。 */
    @Test
    void tinyGbkThatIsValidUtf8IsTakenAsUtf8() throws Exception {
        byte[] b = "// 注\n".getBytes(GBK);
        TextEncodings.Decoded d = detect(b);
        assertEquals(TextEncodings.Source.PRIMARY, d.source());
        assertArrayEquals(b, d.encode(d.text()));
    }

    /** 大体是 UTF-8 的比例边界：合法多字节字符数 ≥ 4 × 坏字节段数。 */
    @Test
    void mixedRatioBoundary() {
        byte[] bad = {(byte) 0xFF};
        assertEquals(TextEncodings.Source.UNKNOWN, detect(cat("一二三".getBytes(UTF8), bad)).source());
        assertEquals(TextEncodings.Source.MIXED, detect(cat("一二三四".getBytes(UTF8), bad)).source());
        assertEquals(TextEncodings.Source.UNKNOWN, detect(cat("一二三四".getBytes(UTF8), bad, "x".getBytes(UTF8), bad)).source());
        // 纯 ASCII 夹一个坏字节：没有合法多字节字符，无从判断
        assertEquals(TextEncodings.Source.UNKNOWN, detect(cat("hello ".getBytes(UTF8), bad)).source());
    }

    @Test
    void mostlyUtf8WithPastedGbkIsMixed() throws Exception {
        byte[] b = cat(CN_CODE.getBytes(UTF8), "// 粘贴进来的注释\n".getBytes(GBK));
        TextEncodings.Decoded d = detect(b);
        assertEquals(TextEncodings.Source.MIXED, d.source(), d.label());
        assertTrue(d.label().startsWith("UTF-8, ") && d.label().endsWith(" undecodable bytes shown as U+FFFD"), d.label());
        assertTrue(d.allowsNonAscii());
        assertArrayEquals(b, d.encode(d.text()));

        d = detect(cat("hello ".getBytes(UTF8), new byte[]{(byte) 0xFF}, " world\n".getBytes(UTF8)));
        assertEquals("not UTF-8, 1 undecodable byte shown as U+FFFD", d.label());
        assertFalse(d.allowsNonAscii());
    }

    /** 抽样：在最后一个换行处截断，切断的多字节字符不算坏字节；标签带 sampled。 */
    @Test
    void sampledDetection() {
        byte[] utf8 = CN_CODE.getBytes(UTF8);
        int cut = -1;
        for (int i = 1; i < utf8.length; i++) {
            if ((utf8[i] & 0xC0) == 0x80) {
                cut = i; // 第一个续字节：从中间切断一个中文字符
                break;
            }
        }
        TextEncodings.Decoded d = TextEncodings.detect(utf8, cut, true, UTF8);
        assertEquals("UTF-8, sampled first 1 MB", d.label());
        byte[] gbk = (CN_CODE + CN_CODE).getBytes(GBK);
        d = TextEncodings.detect(gbk, gbk.length - 1, true, UTF8);
        assertTrue(d.label().startsWith("not UTF-8, ") && d.label().endsWith(", sampled first 1 MB"), d.label());
    }

    /** 主编码不是 UTF-8 时（fileCharset 改成别的）：按它解码没有坏字节即按它，否则“不是它”；没有 MIXED。 */
    @Test
    void nonUtf8Primary() {
        byte[] b = "中文 hello\n".getBytes(GBK);
        TextEncodings.Decoded d = TextEncodings.detect(b, b.length, false, GBK);
        assertEquals(TextEncodings.Source.PRIMARY, d.source());
        assertEquals("GBK", d.label());
        assertTrue(d.writesBackUnchanged());
        byte[] bad = cat("中文".getBytes(GBK), new byte[]{(byte) 0xFF});
        assertEquals("not GBK, 1 undecodable byte shown as U+FFFD", TextEncodings.detect(bad, bad.length, false, GBK).label());
        // UTF-16（无 BOM）做主编码：编码时会加 BOM，不能原样写回
        byte[] u = "ab".getBytes(StandardCharsets.UTF_16BE);
        d = TextEncodings.detect(u, u.length, false, StandardCharsets.UTF_16);
        assertFalse(d.writesBackUnchanged());
    }

    /** 自实现的 UTF-8 扫描与 JDK 解码器同口径：合法性一致，坏字节数等于保字节解码的原字节数。 */
    @Test
    void utf8ScanMatchesJdk() {
        Random r = new Random(11);
        for (int round = 0; round < 50000; round++) {
            byte[] data = randomBytes(r, r.nextInt(16));
            TextEncodings.Utf8Scan s = TextEncodings.scanUtf8(data, 0, data.length, false);
            assertEquals(strictUtf8(data), s.valid, Arrays.toString(data));
            String keep = TerminalSupport.decodeKeepingRawBytes(data, UTF8);
            assertEquals(TerminalSupport.countRawBytes(keep), s.badBytes, Arrays.toString(data));
        }
    }

    /** 任意字节：能原样写回，保字节解码与展示解码与 JDK 一致，合法时没有坏字节。 */
    @Test
    void arbitraryBytesRoundTrip() throws Exception {
        Random r = new Random(7);
        for (int round = 0; round < 20000; round++) {
            byte[] data = randomBytes(r, r.nextInt(32));
            TextEncodings.Decoded d = detect(data);
            byte[] body = Arrays.copyOfRange(data, d.bomLength(), data.length);
            // UTF-8（含带 BOM）总能原样写回；偶然以 UTF-16 BOM 开头的随机字节由 writesBackUnchanged 判定
            if (d.charset() == UTF8) {
                assertTrue(d.writesBackUnchanged(), Arrays.toString(data));
            }
            if (d.writesBackUnchanged()) {
                assertArrayEquals(data, d.encode(d.text()), Arrays.toString(data));
            }
            assertEquals(TerminalSupport.decodeKeepingRawBytes(body, d.charset()), d.text());
            assertEquals(new String(body, d.charset()), d.displayText());
            if (d.source() == TextEncodings.Source.PRIMARY) {
                assertEquals(0, d.rawBytes());
            }
        }
    }

    /**
     * 超大文件的整份流式判断与整份读入后判断结果相同：任意开头长度、任意分块大小（多字节字符、连续坏字节被块边界切断），
     * 无 BOM / UTF-8 BOM / UTF-16 BOM、主编码不是 UTF-8 的情况都覆盖。
     */
    @Test
    void detectWholeMatchesWholeDetect() throws Exception {
        Random r = new Random(23);
        byte[][] prefixes = {{}, {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, {(byte) 0xFF, (byte) 0xFE}, {(byte) 0xFE, (byte) 0xFF}};
        for (int round = 0; round < 30000; round++) {
            byte[] prefix = prefixes[r.nextInt(prefixes.length)];
            byte[] data = cat(prefix, randomBytes(r, r.nextInt(120)));
            Charset primary = prefix.length == 0 && r.nextInt(5) == 0 ? GBK : UTF8;
            int headLen = Math.min(data.length, 4 + r.nextInt(Math.max(1, data.length)));
            byte[] head = Arrays.copyOf(data, headLen);
            int chunk = 8 + r.nextInt(24);
            TextEncodings.Decoded whole = TextEncodings.detectWhole(head,
                    new ByteArrayInputStream(data, headLen, data.length - headLen), primary, chunk);
            TextEncodings.Decoded expected = TextEncodings.detect(data, data.length, false, primary);
            String msg = Arrays.toString(data) + " head=" + headLen + " chunk=" + chunk + " " + primary;
            assertEquals(expected.source(), whole.source(), msg);
            assertEquals(expected.rawBytes(), whole.rawBytes(), msg);
            assertEquals(expected.charset(), whole.charset(), msg);
            assertEquals(expected.bomLength(), whole.bomLength(), msg);
            assertTrue(whole.sampled(), msg);
        }
    }

    /** 连续坏字节被块边界切开仍算一段：4 个中文 + 1 段坏字节是“大体是 UTF-8”，算成两段就会变成“不是 UTF-8”。 */
    @Test
    void detectWholeKeepsBadRunAcrossChunks() throws Exception {
        byte[] data = cat("一二三四".getBytes(UTF8), new byte[]{(byte) 0xFF, (byte) 0xFF});
        assertEquals(TextEncodings.Source.MIXED, detect(data).source());
        TextEncodings.Decoded whole = TextEncodings.detectWhole(data, new ByteArrayInputStream(new byte[0]), UTF8, 13);
        assertEquals(TextEncodings.Source.MIXED, whole.source());
        assertEquals(2, whole.rawBytes());
    }

    /** 坏字节只在抽样范围之后：抽样判断看不到，整份流式判断能看到。 */
    @Test
    void detectWholeSeesBytesBeyondSample() throws Exception {
        byte[] ascii = new byte[TextEncodings.SAMPLE_BYTES + 100];
        Arrays.fill(ascii, (byte) 'a');
        for (int i = 79; i < ascii.length; i += 80) {
            ascii[i] = '\n';
        }
        byte[] data = cat(ascii, "// 中文\n".getBytes(GBK));
        byte[] head = Arrays.copyOf(data, TextEncodings.SAMPLE_BYTES);
        assertEquals(0, TextEncodings.detect(head, head.length, true, UTF8).rawBytes());
        TextEncodings.Decoded whole = TextEncodings.detectWhole(head,
                new ByteArrayInputStream(data, head.length, data.length - head.length), UTF8);
        assertEquals(TextEncodings.Source.UNKNOWN, whole.source());
        assertEquals(4, whole.rawBytes());
        assertFalse(whole.allowsNonAscii());
    }

    @Test
    void unencodableCharacters() throws Exception {
        assertNull(TextEncodings.firstUnencodable("中文 ok", GBK));
        assertArrayEquals(new int[]{4, 0x1F600}, TextEncodings.firstUnencodable("中文 \uD83D\uDE00", GBK));
        assertArrayEquals(new int[]{2, 0xD83D}, TextEncodings.firstUnencodable("x\uD83D", UTF8));
        assertArrayEquals(new int[]{1, 0xDCFF}, TextEncodings.firstUnencodable("\uDCFF", UTF8));
        assertNull(TextEncodings.firstUnencodable("\uD83D\uDE00", UTF8));
        assertEquals("'\uD83D\uDE00' (U+1F600)", TextEncodings.describeChar(0x1F600));
        assertEquals("U+D83D", TextEncodings.describeChar(0xD83D));
        // 保字节编码是严格编码：孤立高代理、表示不了的字符照样报错，不会写成 '?'
        assertThrows(CharacterCodingException.class, () -> TerminalSupport.encodeKeepingRawBytes("a\uD83D", UTF8));
        assertThrows(CharacterCodingException.class, () -> TerminalSupport.encodeKeepingRawBytes("a\uD83D\uDE00", GBK));
        assertArrayEquals("中文".getBytes(GBK), TerminalSupport.encodeKeepingRawBytes("中文", GBK));
    }

    private static byte[] randomBytes(Random r, int n) {
        byte[] data = new byte[n];
        for (int i = 0; i < n; i++) {
            int kind = r.nextInt(5);
            data[i] = (byte) (kind == 0 ? r.nextInt(256) : kind == 1 ? 0x80 + r.nextInt(64)
                    : kind == 2 ? 0xC0 + r.nextInt(64) : kind == 3 ? 0xA1 + r.nextInt(94) : 'a');
        }
        return data;
    }

    private static boolean strictUtf8(byte[] data) {
        try {
            UTF8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }
}
