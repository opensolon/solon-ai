package org.noear.solon.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.mount.MountManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * grep 读文件方式改造（一次读入字节、内存二进制探测、字面量字节预筛）的内容级差分测试：
 * 各类边角内容下，新实现与 {@link LegacySearch#grepReplacing}（优化前逐字副本，含原文本判定，
 * 只把解码换成替换模式）输出逐字一致。
 */
class TerminalGrepContentTest {

    @TempDir
    Path tmp;

    private static final List<String> PATTERNS = Arrays.asList(
            "token", "tok", "en", "中文令牌", "令", "\uD83D\uDE00", "a.b", "token$", "^token", "[unclosed", "[未闭合",
            "\uD83D", "[\uD83D", "\uFEFFtoken", "\r", "", "x", "TOKEN", "n\u0001", "\uFFFD", "two\n", "\uFFFD\ntoken");

    private static TerminalTalent talent(Path ws, boolean sandbox) {
        TerminalTalent t = new TerminalTalent(new MountManager(ws.toString()));
        t.setSandboxEnabled(sandbox);
        return t;
    }

    private static void writeBytes(Path file, byte[]... parts) throws IOException {
        Files.createDirectories(file.getParent());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p);
        }
        Files.write(file, out.toByteArray());
    }

    private static byte[] u(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] b(int... values) {
        byte[] r = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            r[i] = (byte) values[i];
        }
        return r;
    }

    private static byte[] repeat(String line, int totalBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] l = u(line);
        while (out.size() + l.length <= totalBytes) {
            out.write(l, 0, l.length);
        }
        while (out.size() < totalBytes) {
            out.write('x');
        }
        return out.toByteArray();
    }

    private static String compare(TerminalTalent t, Path ws, String pattern, String include) throws Exception {
        String cwd = ws.toString();
        String expected = TerminalSearchEquivalenceTest.outcome(() -> LegacySearch.grepReplacing(t, pattern, ".", include, cwd));
        String actual = TerminalSearchEquivalenceTest.outcome(() -> t.grep(pattern, ".", include, cwd, null).getContent());
        assertEquals(expected, actual, "pattern=" + escape(pattern) + " include=" + include + " ws=" + ws);
        return actual;
    }

    private static void compareAll(Path ws, Charset charset) throws Exception {
        for (boolean sandbox : new boolean[]{true, false}) {
            TerminalTalent t = talent(ws, sandbox);
            if (charset != null) {
                t.fileCharset = charset;
            }
            for (String p : PATTERNS) {
                compare(t, ws, p, null);
            }
        }
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append(c < 0x20 || Character.isSurrogate(c) || c == '\uFEFF' ? String.format("\\u%04x", (int) c) : String.valueOf(c));
        }
        return sb.toString();
    }

    /**
     * 坏 UTF-8 字节：替换成 U+FFFD 后继续搜，坏字节前后的命中都输出（原先小文件整文件无输出、
     * 大文件只输出坏字节前已解码的部分）。
     */
    @Test
    void malformedUtf8IsReplacedAndSearchContinues() throws Exception {
        Path ws = tmp.resolve("ws");
        writeBytes(ws.resolve("small.txt"), u("token one\ntoken two\n"), b(0xFF), u("\ntoken three\n"));
        writeBytes(ws.resolve("large.txt"), repeat("token line\n", 30000), b(0xC3), u("\ntoken tail\n"));
        writeBytes(ws.resolve("edge8k.txt"), repeat("token z\n", 8191), b(0xE4, 0xB8), u("\ntoken after\n"));
        writeBytes(ws.resolve("surrogate.txt"), u("token a\n"), b(0xED, 0xA0, 0x80), u("\ntoken b\n"));
        writeBytes(ws.resolve("inline.txt"), u("x "), b(0xD6, 0xD0), u(" token mid\n"));
        writeBytes(ws.resolve("gbk.java"), "// 中文注释\nint token = 1;\n".getBytes(Charset.forName("GBK")));
        writeBytes(ws.resolve("ok.txt"), u("token ok\n"));
        compareAll(ws, null);

        TerminalTalent t = talent(ws, true);
        t.setMaxCharacterLimit(1 << 22);
        String out = compare(t, ws, "token", null);
        assertTrue(out.contains("large.txt:1: token line"), out.substring(0, Math.min(200, out.length())));
        assertTrue(out.contains("large.txt:2729: token tail"), out.substring(Math.max(0, out.indexOf("large.txt:272"))));
        assertTrue(out.contains("small.txt:1: token one"));
        assertTrue(out.contains("small.txt:4: token three"));
        assertTrue(out.contains("edge8k.txt:1025: token after"));
        assertTrue(out.contains("surrogate.txt:3: token b"));
        assertTrue(out.contains("inline.txt:1: x \uFFFD"), out);
        assertTrue(out.contains("gbk.java:2: int token = 1;"));
        assertTrue(out.contains("ok.txt:1: token ok"));

        // 确实是行为变化：原口径下这些命中都会丢
        String legacy = LegacySearch.grep(t, "token", ".", null, ws.toString());
        assertFalse(legacy.contains("token tail"));
        assertFalse(legacy.contains("small.txt"));
        assertFalse(legacy.contains("gbk.java"));

        // 按 U+FFFD 搜：字节里没有 EF BF BD，但解码后有，不能被预筛跳过
        String fffd = compare(t, ws, "\uFFFD", null);
        assertTrue(fffd.contains("small.txt:3:"), fffd);
        assertTrue(fffd.contains("gbk.java:1:"), fffd);
    }

    /** 换行：\n、\r\n、孤立 \r、末尾 \r、连续空行，行号必须一致。 */
    @Test
    void lineSeparatorsKeepLegacyLineNumbers() throws Exception {
        Path ws = tmp.resolve("ws");
        writeBytes(ws.resolve("mixed.txt"), u("a\r\ntoken 2\rtoken 3\n\n\r\rtoken 7\r\n\r\ntoken 9\r"));
        writeBytes(ws.resolve("only-cr.txt"), u("x\rx\rtoken 3"));
        writeBytes(ws.resolve("tail-crlf.txt"), u("token 1\r\n"));
        compareAll(ws, null);

        String out = compare(talent(ws, true), ws, "token", null);
        assertTrue(out.contains("mixed.txt:7: token 7"), out);
        assertTrue(out.contains("only-cr.txt:3: token 3"), out);
    }

    /** BOM：只剥首行；锚定行首的正则在首行仍能命中；只有 BOM 的文件；非首行的 U+FEFF 保留。 */
    @Test
    void bomHandlingMatchesLegacy() throws Exception {
        Path ws = tmp.resolve("ws");
        writeBytes(ws.resolve("bom.txt"), b(0xEF, 0xBB, 0xBF), u("token first\n\uFEFFtoken second\n"));
        writeBytes(ws.resolve("bom-only.txt"), b(0xEF, 0xBB, 0xBF));
        writeBytes(ws.resolve("bom-bad.txt"), b(0xEF, 0xBB), u("token\n"));
        compareAll(ws, null);

        String out = compare(talent(ws, true), ws, "^token", null);
        assertTrue(out.contains("bom.txt:1: token first"), out);
    }

    /** 二进制探测：采样只看开头 4096 字节；NUL 在 4095 / 4096；控制字符占比；空文件；无后缀二进制。 */
    @Test
    void binarySniffUsesFirst4096Bytes() throws Exception {
        Path ws = tmp.resolve("ws");
        writeBytes(ws.resolve("empty.txt"));
        writeBytes(ws.resolve("nul-4095.txt"), repeat("token\n", 4095), b(0x00), u("\ntoken\n"));
        writeBytes(ws.resolve("nul-4096.txt"), repeat("token\n", 4096), b(0x00), u("\ntoken\n"));
        writeBytes(ws.resolve("exact-4096.txt"), repeat("token\n", 4096));
        byte[] ctrl = new byte[40];
        Arrays.fill(ctrl, (byte) 0x01);
        writeBytes(ws.resolve("ctrl-heavy.txt"), u("token\n"), ctrl);
        byte[] ctrlLight = new byte[10];
        Arrays.fill(ctrlLight, (byte) 0x02);
        writeBytes(ws.resolve("ctrl-light.txt"), u("token token token token token token\n"), ctrlLight);
        writeBytes(ws.resolve("noext"), b(0x7F, 'E', 'L', 'F', 0x00), u("token"));
        writeBytes(ws.resolve("image.png"), u("token in png\n"));
        writeBytes(ws.resolve("App.class"), u("token in class\n"));
        compareAll(ws, null);

        String out = compare(talent(ws, true), ws, "token", null);
        assertTrue(out.contains("nul-4096.txt"), out);
        assertFalse(out.contains("nul-4095.txt"), out);
        assertTrue(out.contains("exact-4096.txt"), out);
        assertTrue(out.contains("ctrl-light.txt"), out);
        assertFalse(out.contains("ctrl-heavy.txt"), out);
        assertFalse(out.contains("noext"), out);
        assertFalse(out.contains("image.png"), out);
    }

    /** 中文、emoji、孤立代理字符模式、非法正则回退 contains、含元字符的模式、空模式。 */
    @Test
    void multiByteAndSpecialPatternsMatchLegacy() throws Exception {
        Path ws = tmp.resolve("ws");
        writeBytes(ws.resolve("zh.md"), u("这里有中文令牌\n只有令\n[未闭合 的括号\n"));
        writeBytes(ws.resolve("emoji.txt"), u("smile \uD83D\uDE00 token\n[\uD83D\uDE00 bracket\n"));
        writeBytes(ws.resolve("meta.txt"), u("a.b\naxb\n[unclosed bracket\ntoken\n"));
        compareAll(ws, null);

        TerminalTalent t = talent(ws, true);
        assertTrue(compare(t, ws, "中文令牌", null).contains("zh.md:1:"));
        assertTrue(compare(t, ws, "[未闭合", null).contains("zh.md:3:"));
        assertTrue(compare(t, ws, "\uD83D\uDE00", null).contains("emoji.txt:1:"));
        assertTrue(compare(t, ws, "a.b", null).contains("meta.txt:2: axb"));
        // 非法正则回退 contains 按 char 比较：孤立高代理能命中代理对的前半，所以含孤立代理的模式不能预筛
        assertTrue(compare(t, ws, "[\uD83D", null).contains("emoji.txt:2:"));
    }

    /** 非 UTF-8 字符集（不走预筛）：GBK、ISO-8859-1，含 GBK 下非法的字节。 */
    @Test
    void nonUtf8CharsetMatchesLegacy() throws Exception {
        Path ws = tmp.resolve("ws");
        Charset gbk = Charset.forName("GBK");
        writeBytes(ws.resolve("gbk.txt"), "中文令牌 token\n第二行令\n".getBytes(gbk));
        writeBytes(ws.resolve("gbk-bad.txt"), "token\n".getBytes(gbk), b(0x81, 0x0A), "token 3\n".getBytes(gbk));
        writeBytes(ws.resolve("utf8.txt"), u("中文令牌 utf8\n"));
        compareAll(ws, gbk);
        compareAll(ws, StandardCharsets.ISO_8859_1);

        TerminalTalent t = talent(ws, true);
        t.fileCharset = gbk;
        assertTrue(compare(t, ws, "中文令牌", null).contains("gbk.txt:1:"));
        assertNull(TerminalSupport.grepLiteralBytes("token", true, gbk));
    }

    /** 输出截断：预筛跳过的文件不影响截断位置。 */
    @Test
    void truncationMatchesLegacy() throws Exception {
        Path ws = tmp.resolve("ws");
        for (int i = 0; i < 30; i++) {
            writeBytes(ws.resolve("f" + i + ".txt"), u(i % 3 == 0 ? "nothing here\n" : "token line " + i + "\ntoken again\n"));
        }
        for (int limit : new int[]{50, 200, 1000}) {
            TerminalTalent t = talent(ws, true);
            t.setMaxCharacterLimit(limit);
            compare(t, ws, "token", null);
            compare(t, ws, "token", "*.txt");
        }
    }

    /** 超过 10 MB 上限：直接的大文件跳过；指向大文件的符号链接（属性是链接本身的）退回流式读取，结果仍一致。 */
    @Test
    void oversizedContentFallsBackToStreaming() throws Exception {
        Path ws = tmp.resolve("ws");
        Path outside = tmp.resolve("outside");
        byte[] big = repeat("filler line\n", TerminalSupport.GREP_MAX_FILE_BYTES + 100);
        // 尾部前夹一个坏字节：流式回退路径同样按替换模式解码，坏字节之后的命中仍要输出
        writeBytes(outside.resolve("big.txt"), big, b(0xFF), u("\ntoken at end\n"));
        writeBytes(ws.resolve("big-in-ws.txt"), big, u("\ntoken at end\n"));
        boolean linked;
        try {
            Files.createSymbolicLink(ws.resolve("link-big.txt"), outside.resolve("big.txt"));
            linked = true;
        } catch (IOException | UnsupportedOperationException e) {
            linked = false;
        }
        TerminalTalent t = talent(ws, false);
        String out = compare(t, ws, "token", null);
        assertFalse(out.contains("big-in-ws.txt"), out);
        if (linked) {
            assertTrue(out.contains("link-big.txt:"), out);
            assertTrue(out.contains(": token at end"), out);
        }
    }

    /** 读不了的文件：两边都跳过。 */
    @Test
    void unreadableFileIsSkipped() throws Exception {
        if (SearchFixture.WINDOWS) {
            return;
        }
        Path ws = tmp.resolve("ws");
        writeBytes(ws.resolve("locked.txt"), u("token locked\n"));
        writeBytes(ws.resolve("open.txt"), u("token open\n"));
        Files.setPosixFilePermissions(ws.resolve("locked.txt"), PosixFilePermissions.fromString("---------"));
        try {
            String out = compare(talent(ws, true), ws, "token", null);
            if (!Files.isReadable(ws.resolve("locked.txt"))) {
                assertFalse(out.contains("locked.txt"), out);
            }
            assertTrue(out.contains("open.txt"), out);
        } finally {
            Files.setPosixFilePermissions(ws.resolve("locked.txt"), PosixFilePermissions.fromString("rw-------"));
        }
    }

    /** 随机内容：字面量、换行、BOM、坏字节、控制字符、中文、emoji 随机拼接，大小覆盖 4096 / 8192 边界。 */
    @Test
    void randomContentMatchesLegacy() throws Exception {
        Path ws = tmp.resolve("ws");
        Random r = new Random(20261119L);
        byte[][] pieces = {
                u("token"), u("tok"), u("en"), u("中文令牌"), u("令"), u("\uD83D\uDE00"), u("a.b"), u("axb"),
                u("\n"), u("\r\n"), u("\r"), u(" "), u("[unclosed"), u("[未闭合"), u("TOKEN"), u("x"),
                b(0xFF), b(0xC3), b(0xE4, 0xB8), b(0xED, 0xA0, 0x80), b(0x01), b(0x02, 0x03), b(0xEF, 0xBB, 0xBF)
        };
        int[] sizes = {0, 1, 10, 100, 4090, 4096, 4100, 8180, 8192, 8200, 20000};
        for (int i = 0; i < 160; i++) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (r.nextInt(5) == 0) {
                out.write(b(0xEF, 0xBB, 0xBF));
            }
            int size = sizes[r.nextInt(sizes.length)];
            boolean clean = r.nextInt(3) == 0;
            while (out.size() < size) {
                byte[] p = pieces[clean ? r.nextInt(16) : r.nextInt(pieces.length)];
                out.write(p);
            }
            if (r.nextInt(8) == 0) {
                out.write(0);
            }
            writeBytes(ws.resolve("d" + (i % 7)).resolve("r" + i + (i % 4 == 0 ? ".md" : ".txt")), out.toByteArray());
        }
        for (boolean sandbox : new boolean[]{true, false}) {
            TerminalTalent t = talent(ws, sandbox);
            t.setMaxCharacterLimit(1 << 22);
            for (String p : PATTERNS) {
                compare(t, ws, p, null);
                compare(t, ws, p, "*.md");
            }
        }
        // 确认随机内容真的触发了命中（否则比对没有意义）
        TerminalTalent t = talent(ws, true);
        t.setMaxCharacterLimit(1 << 22);
        assertTrue(compare(t, ws, "token", null).split("\n").length > 50);
    }

    // ---- 支撑方法 ----

    @Test
    void containsBytesMatchesNaiveSearch() {
        Random r = new Random(7);
        for (int round = 0; round < 20000; round++) {
            byte[] data = new byte[r.nextInt(40)];
            byte[] needle = new byte[r.nextInt(5)];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) r.nextInt(3);
            }
            for (int i = 0; i < needle.length; i++) {
                needle[i] = (byte) r.nextInt(3);
            }
            assertEquals(naiveContains(data, needle), TerminalSupport.containsBytes(data, needle),
                    Arrays.toString(data) + " / " + Arrays.toString(needle));
        }
    }

    private static boolean naiveContains(byte[] data, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= data.length; i++) {
            for (int k = 0; k < needle.length; k++) {
                if (data[i + k] != needle[k]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    @Test
    void readUpToHandlesWrongSizeHintsAndLimit() throws Exception {
        Path f = tmp.resolve("f.bin");
        byte[] content = new byte[30000];
        new Random(3).nextBytes(content);
        Files.write(f, content);
        for (long hint : new long[]{0, 1, 100, 29999, 30000, 30001, 1 << 20, -1}) {
            assertArrayEquals(content, TerminalSupport.readUpTo(f, hint, 1 << 20), "hint=" + hint);
            // 恰好等于上限：读得到；上限小 1：返回 null
            assertArrayEquals(content, TerminalSupport.readUpTo(f, hint, 30000), "hint=" + hint);
            assertNull(TerminalSupport.readUpTo(f, hint, 29999), "hint=" + hint);
        }
        Path empty = tmp.resolve("empty.bin");
        Files.write(empty, new byte[0]);
        assertEquals(0, TerminalSupport.readUpTo(empty, 0, 10).length);
        assertEquals(0, TerminalSupport.readUpTo(empty, 5, 10).length);
    }

    @Test
    void literalBytesOnlyForUtf8AndMetaFreePatterns() {
        Charset utf8 = StandardCharsets.UTF_8;
        assertArrayEquals(u("token"), TerminalSupport.grepLiteralBytes("token", true, utf8));
        assertArrayEquals(u("中文 令牌-#/:=<>!&,'\"@%~`"), TerminalSupport.grepLiteralBytes("中文 令牌-#/:=<>!&,'\"@%~`", true, utf8));
        for (String meta : Arrays.asList("a.b", "^a", "a$", "a|b", "a?", "a*", "a+", "(a)", "[a]", "a{1}", "a\\d", "}")) {
            assertNull(TerminalSupport.grepLiteralBytes(meta, true, utf8), meta);
        }
        // 非法正则回退 contains：含元字符也按字面预筛
        assertArrayEquals(u("[unclosed"), TerminalSupport.grepLiteralBytes("[unclosed", false, utf8));
        assertNull(TerminalSupport.grepLiteralBytes("", true, utf8));
        assertNull(TerminalSupport.grepLiteralBytes("\uD83D", true, utf8));
        // 含 U+FFFD：解码时坏字节会变成它，字节里未必有对应编码
        assertNull(TerminalSupport.grepLiteralBytes("\uFFFD", true, utf8));
        assertNull(TerminalSupport.grepLiteralBytes("a\uFFFDb", false, utf8));
        assertNull(TerminalSupport.grepLiteralBytes("token", true, StandardCharsets.ISO_8859_1));
        assertNotNull(TerminalSupport.grepLiteralBytes("\uD83D\uDE00", true, utf8));
    }

    @Test
    void sniffHelpersAgreeWithFileBasedDetection() throws Exception {
        Random r = new Random(11);
        TerminalSupport support = LegacySearch.support(talent(tmp, true));
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            byte[] data = new byte[r.nextInt(6000)];
            int ctrlPct = r.nextInt(60);
            for (int k = 0; k < data.length; k++) {
                data[k] = (byte) (r.nextInt(100) < ctrlPct ? 1 + r.nextInt(31) : r.nextInt(10) == 0 ? 0x80 + r.nextInt(128) : 'a' + r.nextInt(26));
            }
            if (data.length > 0 && r.nextInt(5) == 0) {
                data[r.nextInt(data.length)] = 0;
            }
            Path f = tmp.resolve("s" + i + (i % 10 == 0 ? ".png" : ".txt"));
            Files.write(f, data);
            files.add(f);
        }
        int binary = 0;
        for (Path f : files) {
            boolean legacy = LegacySearch.isNotTextFile(f);
            assertEquals(legacy, support.isNotTextFile(f), f.toString());
            byte[] bytes = Files.readAllBytes(f);
            boolean split = support.isNotTextFileByName(f)
                    || TerminalSupport.isLikelyBinary(bytes, Math.min(bytes.length, TerminalSupport.BINARY_SNIFF_BYTES));
            assertEquals(legacy, split, f.toString());
            if (legacy) {
                binary++;
            }
        }
        assertTrue(binary > 30 && binary < 270, "binary=" + binary);
    }
}
