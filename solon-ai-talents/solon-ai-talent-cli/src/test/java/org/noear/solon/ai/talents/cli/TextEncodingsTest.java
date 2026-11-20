package org.noear.solon.ai.talents.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编码识别语料：每类样本的识别结果、内容检查的阈值依据、UTF-8 扫描与 JDK 解码器同口径、保字节往返。
 */
class TextEncodingsTest {
    private static final Charset UTF8 = StandardCharsets.UTF_8;
    private static final Charset GBK = Charset.forName("GBK");
    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final Charset BIG5 = Charset.forName("Big5");
    private static final Charset SJIS = Charset.forName("Shift_JIS");
    private static final Charset EUC_JP = Charset.forName("EUC-JP");
    private static final Charset EUC_KR = Charset.forName("EUC-KR");
    private static final Charset CP1252 = Charset.forName("windows-1252");

    /** 只有 GBK 候选（不受运行机器的系统编码影响）。 */
    private static final FileEncodingPolicy GBK_ONLY = FileEncodingPolicy.builder().noCandidates().candidate(GBK).build();

    static final String CN_CODE = "/**\n * 用户服务：负责登录、注册与权限校验。\n * 注意：缓存失效后需要重新加载配置。\n */\n"
            + "public class UserService {\n    // 默认超时时间（毫秒）\n    private int timeout = 3000;\n\n"
            + "    /** 根据用户名查询用户，找不到时返回空 */\n    public User find(String name) {\n"
            + "        if (name == null) {\n            throw new IllegalArgumentException(\"用户名不能为空\");\n        }\n"
            + "        return repo.get(name); // 先查缓存，再查数据库\n    }\n}\n";
    static final String CN_PROSE = "今天天气很好，我们一起去公园散步。路上遇到了很多朋友，大家都很高兴。"
            + "晚上回家以后，我写了一篇日记，记录这一天发生的事情。";
    static final String CN_TABLE = "// ┌──────┬──────┐\n// │ 名称 │ 说明 │\n// ├──────┼──────┤\n// │ 端口 │ 监听 │\n// └──────┴──────┘\n";
    static final String TW = "繁體中文測試：這是一段使用正體字書寫的說明文件，內容包含設定與資料庫連線。\n";
    static final String JA = "これは日本語のテストです。設定ファイルを読み込んでから、サーバーを起動します。\n";
    static final String KO = "이것은 한국어 테스트입니다. 설정 파일을 읽은 후 서버를 시작합니다. 사용자 이름을 확인하세요.\n";

    private static TextEncodings.Decoded detect(byte[] bytes) {
        return TextEncodings.detect(bytes, bytes.length, false, UTF8, GBK_ONLY, "a.txt");
    }

    private static void assertDetected(TextEncodings.Source source, String charset, byte[] bytes) {
        TextEncodings.Decoded d = detect(bytes);
        assertEquals(source, d.source(), d.label());
        assertEquals(charset, d.charset().name(), d.label());
    }

    @Test
    void unicodeAndBomSamples() {
        assertDetected(TextEncodings.Source.PRIMARY, "UTF-8", "hello\n".getBytes(UTF8));
        assertDetected(TextEncodings.Source.PRIMARY, "UTF-8", CN_CODE.getBytes(UTF8));
        assertDetected(TextEncodings.Source.PRIMARY, "UTF-8", "emoji \uD83D\uDE00\n".getBytes(UTF8));

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
        byte[] be = cat(new byte[]{(byte) 0xFE, (byte) 0xFF}, "ab中\n".getBytes(StandardCharsets.UTF_16BE));
        assertEquals("UTF-16BE with BOM", detect(be).label());
        // UTF-32LE 的 BOM 不当作 UTF-16
        assertNull(TextEncodings.bomCharset(new byte[]{(byte) 0xFF, (byte) 0xFE, 0, 0}, 4));
    }

    @Test
    void gbkSamplesAreDetected() {
        for (String s : Arrays.asList(CN_CODE, CN_PROSE, CN_TABLE, "注释\n", "// 中文\nint x = 1;\n", "版本：1.0（测试）\n")) {
            TextEncodings.Decoded d = detect(s.getBytes(GBK));
            assertEquals(TextEncodings.Source.DETECTED, d.source(), s);
            assertEquals("GBK detected", d.label());
            assertEquals(s, d.displayText());
            assertTrue(d.writesBackUnchanged());
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

    @Test
    void otherEncodingsAreNotMistakenForGbk() {
        Map<String, byte[]> samples = new LinkedHashMap<>();
        samples.put("big5", TW.getBytes(BIG5));
        samples.put("big5-short", "繁體中文測試\n".getBytes(BIG5));
        samples.put("sjis", JA.getBytes(SJIS));
        samples.put("euc-jp", JA.getBytes(EUC_JP));
        samples.put("euc-kr", KO.getBytes(EUC_KR));
        samples.put("cp1252", "café\n".getBytes(CP1252));
        samples.put("cp1252-long", "Déjà vu: naïve façade, über café — “quoted”.\n".getBytes(CP1252));
        samples.put("utf8-as-gbk", CN_CODE.substring(0, 60).getBytes(UTF8)); // 合法 UTF-8 不会走到候选，这里只为完整
        for (Map.Entry<String, byte[]> e : samples.entrySet()) {
            if (e.getKey().startsWith("utf8")) {
                continue;
            }
            TextEncodings.Decoded d = detect(e.getValue());
            assertEquals(TextEncodings.Source.UNKNOWN, d.source(), e.getKey() + " -> " + d.label());
            assertTrue(d.label().startsWith("unknown, "), d.label());
        }
    }

    /**
     * 阈值余量：真实中文的两项指标都要明显高于阈值，韩文按 GBK 解码的常用字覆盖率要明显低于阈值。
     * 调阈值时先看这里打印的数字。
     */
    @Test
    void gbThresholdMargins() {
        for (String s : Arrays.asList(CN_CODE, CN_PROSE, CN_TABLE)) {
            int[] st = TextEncodings.gbStats(s.getBytes(GBK), 0, s.getBytes(GBK).length);
            System.out.println("[gb] " + Arrays.toString(st) + " " + s.substring(0, 8).replace('\n', ' '));
            assertTrue(st[1] * 100 >= st[0] * 97, Arrays.toString(st));
            if (st[2] >= TextEncodings.GB_COMMON_MIN_HANZI) {
                assertTrue(st[3] * 100 >= st[2] * 70, Arrays.toString(st));
                assertTrue(st[4] * 100 >= st[2] * 25, "C9–D7 行汉字应远高于 10%：" + Arrays.toString(st));
            }
        }
        byte[] ko = (KO + KO.replace("설정", "사용자 정보")).getBytes(EUC_KR);
        int[] st = TextEncodings.gbStats(ko, 0, ko.length);
        System.out.println("[ko] " + Arrays.toString(st));
        assertTrue(st[1] * 100 >= st[0] * 90, "韩文确实能过 (a)：" + Arrays.toString(st));
        // 常用字覆盖率接近 50%，单靠 (b) 不稳；韩文只占 B0–C8，C9–D7 行为 0，靠它挡住
        assertEquals(0, st[4], Arrays.toString(st));
    }

    /**
     * C9–D7 行检查单独生效：全是常用字、但都落在 B0–C8 行（按 GBK 解码的韩文就是这种分布）——常用字覆盖率拦不住，靠它拒绝；
     * 加进几个 C9–D7 行的字就通过。真实中文里是/中/一/在/这/有等高频字都在 C9–D7。
     */
    @Test
    void highRowCheckRejectsCommonCharsOnlyInLowRows() {
        String low = "的不大到地出对发成分点定动等但得都并";
        byte[] b = low.getBytes(GBK);
        for (int i = 0; i < b.length; i += 2) {
            assertTrue((b[i] & 0xFF) >= 0xB0 && (b[i] & 0xFF) <= 0xC8, low.charAt(i / 2) + "");
        }
        int[] st = TextEncodings.gbStats(b, 0, b.length);
        assertTrue(st[3] * 100 >= st[2] * TextEncodings.GB_COMMON_PERCENT, Arrays.toString(st));
        assertEquals(0, st[4]);
        assertFalse(TextEncodings.gbLooksLikeChinese(b, 0, b.length));
        byte[] mixed = (low + "是中一在这有").getBytes(GBK);
        assertTrue(TextEncodings.gbLooksLikeChinese(mixed, 0, mixed.length));
    }

    /** 大体是 UTF-8 的比例边界：合法多字节字符数 ≥ 4 × 坏字节段数。 */
    @Test
    void mixedRatioBoundary() {
        byte[] bad = {(byte) 0xFF};
        assertEquals(TextEncodings.Source.UNKNOWN, detect(cat("一二三".getBytes(UTF8), bad)).source());
        assertEquals(TextEncodings.Source.MIXED, detect(cat("一二三四".getBytes(UTF8), bad)).source());
        assertEquals(TextEncodings.Source.UNKNOWN, detect(cat("一二三四".getBytes(UTF8), bad, "x".getBytes(UTF8), bad)).source());
    }

    /** GB18030 独有的四字节字符：默认不认；把 GB18030 加进候选后才认。 */
    @Test
    void gb18030OnlyWhenConfigured() {
        byte[] b = ("// 中文注释\n// " + new String(Character.toChars(0x20000)) + "\n").getBytes(GB18030);
        assertEquals(TextEncodings.Source.UNKNOWN, detect(b).source());
        FileEncodingPolicy p = FileEncodingPolicy.builder().noCandidates().candidate(GBK).candidate(GB18030).build();
        TextEncodings.Decoded d = TextEncodings.detect(b, b.length, false, UTF8, p, "a.txt");
        assertEquals("GB18030 detected", d.label());
    }

    @Test
    void mostlyUtf8WithPastedGbkIsMixed() throws Exception {
        byte[] b = cat(CN_CODE.getBytes(UTF8), "// 粘贴进来的注释\n".getBytes(GBK));
        TextEncodings.Decoded d = detect(b);
        assertEquals(TextEncodings.Source.MIXED, d.source(), d.label());
        assertTrue(d.label().startsWith("UTF-8, ") && d.label().endsWith(" undecodable bytes shown as U+FFFD"), d.label());
        assertTrue(d.allowsNonAscii());
        assertArrayEquals(b, d.encode(d.text()));

        // 几乎全是 ASCII、只有一个坏字节：无从判断
        d = detect(cat("hello ".getBytes(UTF8), new byte[]{(byte) 0xFF}, " world\n".getBytes(UTF8)));
        assertEquals("unknown, 1 undecodable byte shown as U+FFFD", d.label());
        assertFalse(d.allowsNonAscii());
    }

    @Test
    void pathRulesWin() {
        FileEncodingPolicy p = FileEncodingPolicy.builder().noCandidates().candidate(GBK)
                .rule("**/*.properties", StandardCharsets.ISO_8859_1).rule("legacy/**", GBK).build();
        byte[] latin = "name=caf\u00e9\n".getBytes(StandardCharsets.ISO_8859_1);
        for (String path : Arrays.asList("a.properties", "src/main/resources/a.properties", "./x/a.properties", "x\\a.properties")) {
            TextEncodings.Decoded d = TextEncodings.detect(latin, latin.length, false, UTF8, p, path);
            assertEquals("ISO-8859-1 configured", d.label(), path);
            assertEquals("name=café\n", d.displayText());
        }
        // 规则优先于 UTF-8 判断：合法 UTF-8 的 .properties 也按规则（用户明确指定）
        byte[] ascii = "a=b\n".getBytes(UTF8);
        assertEquals(TextEncodings.Source.RULE, TextEncodings.detect(ascii, ascii.length, false, UTF8, p, "a.properties").source());
        assertEquals(TextEncodings.Source.PRIMARY, TextEncodings.detect(ascii, ascii.length, false, UTF8, p, "a.txt").source());
        assertEquals("GBK configured", TextEncodings.detect(ascii, ascii.length, false, UTF8, p, "legacy/x/y.c").label());
        // BOM 优先于规则
        byte[] bom = cat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, ascii);
        assertEquals("UTF-8 with BOM", TextEncodings.detect(bom, bom.length, false, UTF8, p, "a.properties").label());
        // 规则编码解不了的字节：仍按规则，计数
        byte[] badGbk = cat("a=".getBytes(UTF8), new byte[]{(byte) 0xFF}, "\n".getBytes(UTF8));
        assertEquals("GBK configured, 1 undecodable byte shown as U+FFFD",
                TextEncodings.detect(badGbk, badGbk.length, false, UTF8, p, "legacy/a.c").label());
    }

    @Test
    void policyFactoryAndDefaults() {
        FileEncodingPolicy p = FileEncodingPolicy.of(Collections.singletonList("GB18030"), Collections.singletonMap("*.ini", "windows-1252"));
        assertEquals(Collections.singletonList(GB18030), p.getCandidates());
        assertEquals(CP1252, p.ruleFor("conf/a.ini"));
        assertNull(p.ruleFor("conf/a.txt"));
        assertNull(p.ruleFor(null));
        assertTrue(FileEncodingPolicy.of(Collections.emptyList(), null).getCandidates().isEmpty());
        assertEquals(GBK, FileEncodingPolicy.defaults().getCandidates().get(0));
        assertThrows(RuntimeException.class, () -> FileEncodingPolicy.of(Collections.singletonList("NO-SUCH-CS"), null));
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
        TextEncodings.Decoded d = TextEncodings.detect(utf8, cut, true, UTF8, GBK_ONLY, "a.txt");
        assertEquals("UTF-8, sampled first 1 MB", d.label());
        byte[] gbk = (CN_CODE + CN_CODE).getBytes(GBK);
        d = TextEncodings.detect(gbk, gbk.length - 1, true, UTF8, GBK_ONLY, "a.txt");
        assertEquals("GBK detected, sampled first 1 MB", d.label());
    }

    /** 主编码不是 UTF-8 时（fileCharset 改成别的）：严格合法即按它，不尝试与它相同的候选。 */
    @Test
    void nonUtf8Primary() {
        byte[] b = "中文 hello\n".getBytes(GBK);
        TextEncodings.Decoded d = TextEncodings.detect(b, b.length, false, GBK, GBK_ONLY, "a.txt");
        assertEquals(TextEncodings.Source.PRIMARY, d.source());
        assertEquals("GBK", d.label());
        // UTF-16（无 BOM）做主编码：编码时会加 BOM，往返不成立 → 无法确定，且不能原样写回
        byte[] u = "ab".getBytes(StandardCharsets.UTF_16BE);
        d = TextEncodings.detect(u, u.length, false, StandardCharsets.UTF_16, GBK_ONLY, "a.txt");
        assertEquals(TextEncodings.Source.UNKNOWN, d.source());
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

    /** 任意字节：识别结果能原样写回（能写回时），展示文本与按替换模式解码一致。 */
    @Test
    void arbitraryBytesRoundTrip() throws Exception {
        Random r = new Random(7);
        for (int round = 0; round < 20000; round++) {
            byte[] data = randomBytes(r, r.nextInt(32));
            TextEncodings.Decoded d = detect(data);
            if (d.writesBackUnchanged()) {
                assertArrayEquals(data, d.encode(d.text()), Arrays.toString(data));
            }
            assertEquals(new String(data, d.bomLength(), data.length - d.bomLength(), d.charset()), d.displayText());
            if (d.source() == TextEncodings.Source.PRIMARY || d.source() == TextEncodings.Source.DETECTED) {
                assertEquals(0, d.rawBytes());
            }
        }
    }

    @Test
    void unencodableCharacters() {
        assertNull(TextEncodings.firstUnencodable("中文 ok", GBK));
        assertArrayEquals(new int[]{4, 0x1F600}, TextEncodings.firstUnencodable("中文 \uD83D\uDE00", GBK));
        assertArrayEquals(new int[]{2, 0xD83D}, TextEncodings.firstUnencodable("x\uD83D", UTF8));
        assertArrayEquals(new int[]{1, 0xDCFF}, TextEncodings.firstUnencodable("\uDCFF", UTF8));
        assertNull(TextEncodings.firstUnencodable("\uD83D\uDE00", UTF8));
        assertEquals("'\uD83D\uDE00' (U+1F600)", TextEncodings.describeChar(0x1F600));
        assertEquals("U+D83D", TextEncodings.describeChar(0xD83D));
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
