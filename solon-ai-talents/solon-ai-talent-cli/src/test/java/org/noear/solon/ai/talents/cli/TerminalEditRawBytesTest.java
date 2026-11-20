package org.noear.solon.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.mount.Mount;
import org.noear.solon.ai.mount.MountManager;
import org.noear.solon.ai.mount.MountType;
import org.noear.solon.ai.mount.source.InMemoryMountSource;
import org.noear.solon.ai.mount.source.WriteOptions;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件里有按字符集解不了的字节（GBK 等其他编码、混入的零星坏字节）时，edit 不改坏用户没要求改的内容：
 * 未编辑的部分逐字节不变；改不了时明确报错且文件不动；read 能读出内容（坏字节显示为 U+FFFD）。
 */
class TerminalEditRawBytesTest {

    @TempDir
    Path tmp;

    private static final Charset GBK = Charset.forName("GBK");

    /** 本类测“原字节原样保留”的机制；GBK 文件不是 UTF-8，按“编码无法确定”处理（BOM 等其他编码行为见 TerminalFileEncodingTest）。 */
    private static TerminalTalent talent(Path ws) {
        return new TerminalTalent(new MountManager(ws.toString()));
    }

    private static TerminalTalent.EditOp op(String oldStr, String newStr, Integer line, Boolean all) {
        TerminalTalent.EditOp op = new TerminalTalent.EditOp();
        op.oldStr = oldStr;
        op.newStr = newStr;
        op.oldStrStartLine = line;
        op.replaceAll = all;
        return op;
    }

    private static ToolResult edit(TerminalTalent t, Path ws, String file, TerminalTalent.EditOp... ops) throws Exception {
        return t.edit(file, Arrays.asList(ops), ws.toString(), null);
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
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

    private static void assertNoLoneSurrogate(String text) {
        assertEquals(text, TerminalSupport.displayRawBytes(text), "输出给模型的文本不能含孤立代理");
    }

    /** GBK 文件改一行 ASCII：其余字节（含全部中文）原样保留。原先整份中文会被写成 EF BF BD（“锟斤拷”）。 */
    @Test
    void gbkFileKeepsUntouchedBytes() throws Exception {
        Path ws = tmp.resolve("ws");
        Files.createDirectories(ws);
        Path f = ws.resolve("A.java");
        String text = "// 中文注释\nclass A {\n    String s = \"你好\";\n    int x = 1;\n}\n";
        Files.write(f, text.getBytes(GBK));

        ToolResult r = edit(talent(ws), ws, "A.java", op("int x = 1;", "int x = 2;", null, null));
        assertFalse(r.isError(), r.getContent());
        assertEquals(text.replace("int x = 1;", "int x = 2;"), new String(Files.readAllBytes(f), GBK));
        assertArrayEquals(text.replace("int x = 1;", "int x = 2;").getBytes(GBK), Files.readAllBytes(f));

        // 带行号、宽松匹配（缩进不同）、全文替换也一样
        r = edit(talent(ws), ws, "A.java", op("int x = 2;", "int y = 2;", 4, null), op("class A {", "class B {", null, true));
        assertFalse(r.isError(), r.getContent());
        assertArrayEquals(text.replace("int x = 1;", "int y = 2;").replace("class A {", "class B {").getBytes(GBK), Files.readAllBytes(f));
    }

    /** 零星坏字节夹在各处（行首、行尾、紧贴被替换文本、文件末尾的不完整序列、编码过的代理），未编辑的字节都不变。 */
    @Test
    void strayBadBytesAroundEditedTextArePreserved() throws Exception {
        Path ws = tmp.resolve("ws");
        Files.createDirectories(ws);
        Path f = ws.resolve("mixed.txt");
        byte[] before = cat(b(0xFF), u("head\n中文 ok\nleft"), b(0xC3), u("TARGET"), b(0xE4, 0xB8), u("right\n"),
                b(0xED, 0xA0, 0x80), u("\ntail "), b(0xF0, 0x9F, 0x98));
        Files.write(f, before);

        // 坏字节多、合法多字节字符少：编码无法确定，写入非 ASCII 会混入另一种编码，拒绝且文件不动
        ToolResult r = edit(talent(ws), ws, "mixed.txt", op("TARGET", "替换后", null, null), op("head", "HEAD", null, null));
        assertTrue(r.isError(), r.getContent());
        assertTrue(r.getContent().contains("非 ASCII"), r.getContent());
        assertArrayEquals(before, Files.readAllBytes(f));

        r = edit(talent(ws), ws, "mixed.txt", op("TARGET", "REPLACED", null, null), op("head", "HEAD", null, null));
        assertFalse(r.isError(), r.getContent());
        assertEquals("文件 mixed.txt 成功完成 2 处修改。", r.getContent());
        byte[] expected = cat(b(0xFF), u("HEAD\n中文 ok\nleft"), b(0xC3), u("REPLACED"), b(0xE4, 0xB8), u("right\n"),
                b(0xED, 0xA0, 0x80), u("\ntail "), b(0xF0, 0x9F, 0x98));
        assertArrayEquals(expected, Files.readAllBytes(f));
    }

    /** BOM 与 CRLF：都按原样保留。 */
    @Test
    void bomAndCrlfArePreservedWithBadBytes() throws Exception {
        Path ws = tmp.resolve("ws");
        Files.createDirectories(ws);
        Path f = ws.resolve("bom.txt");
        Files.write(f, cat(b(0xEF, 0xBB, 0xBF), u("first\r\n"), b(0x80), u("\r\nthird line\r\n")));

        ToolResult r = edit(talent(ws), ws, "bom.txt", op("first\nsecond", "x", null, null));
        assertTrue(r.isError(), r.getContent());

        r = edit(talent(ws), ws, "bom.txt", op("third line\n", "3rd\nline\n", null, null));
        assertFalse(r.isError(), r.getContent());
        assertArrayEquals(cat(b(0xEF, 0xBB, 0xBF), u("first\r\n"), b(0x80), u("\r\n3rd\r\nline\r\n")), Files.readAllBytes(f));
    }

    /** old_str 带 �（照抄 read/grep 的输出）或带真实中文（GBK 文件）：匹配不上，提示原因，文件不动，输出不含孤立代理。 */
    @Test
    void mismatchOnUndecodableTextExplainsAndLeavesFileUntouched() throws Exception {
        Path ws = tmp.resolve("ws");
        Files.createDirectories(ws);
        Path f = ws.resolve("A.java");
        byte[] original = "// 中文注释\nint x = 1;\n".getBytes(GBK);
        Files.write(f, original);
        TerminalTalent t = talent(ws);

        String shown = t.read("A.java", null, null, ws.toString(), null).getContent();
        String firstLine = shown.split("\n")[2].substring(9); // "     1 | " 之后
        assertTrue(firstLine.startsWith("// ") && firstLine.indexOf('\uFFFD') > 0, shown);

        for (TerminalTalent.EditOp op : Arrays.asList(
                op(firstLine, "// x", null, null),
                op(firstLine, "// x", 1, null),
                op("// 中文注释", "// x", 1, null),
                op("// 中文注释", "// x", null, true))) {
            ToolResult r = edit(t, ws, "A.java", op);
            assertTrue(r.isError(), r.getContent());
            assertTrue(r.getContent().contains("UTF-8") && r.getContent().contains("Encoding"), r.getContent());
            assertNoLoneSurrogate(r.getContent());
            assertArrayEquals(original, Files.readAllBytes(f));
        }

        // 合法文件匹配失败时不带这段提示
        Files.write(ws.resolve("ok.txt"), u("hello\n"));
        ToolResult r = edit(t, ws, "ok.txt", op("zzz", "y", null, null));
        assertTrue(r.isError());
        assertFalse(r.getContent().contains("Encoding"), r.getContent());
    }

    /** new_str 无法按文件字符集保存：拒绝且文件不动（原先孤立代理、GBK 表示不了的字符会被无声写成 '?'）。 */
    @Test
    void unencodableNewStrIsRejected() throws Exception {
        Path ws = tmp.resolve("ws");
        Files.createDirectories(ws);
        Path ok = ws.resolve("ok.txt");
        Files.write(ok, u("hello world\n"));
        Path bad = ws.resolve("bad.txt");
        byte[] badBytes = cat(u("hello "), b(0xFF), u(" world\n"));
        Files.write(bad, badBytes);
        TerminalTalent t = talent(ws);

        // 孤立高代理；以及落在原字节映射区的孤立低代理（若放行，会被当成一个原字节写进文件）
        for (String newStr : Arrays.asList("x\uD83D", "\uDCFF", "\uDE00y")) {
            for (Path p : Arrays.asList(ok, bad)) {
                byte[] before = Files.readAllBytes(p);
                ToolResult r = edit(t, ws, p.getFileName().toString(), op("hello", newStr, null, null));
                assertTrue(r.isError(), r.getContent());
                assertTrue(r.getContent().contains("UTF-8") && r.getContent().contains("U+D"), r.getContent());
                assertArrayEquals(before, Files.readAllBytes(p));
            }
        }

        // 完整的代理对：普通 UTF-8 文件没问题；编码无法确定的文件不能写入非 ASCII
        assertFalse(edit(t, ws, "ok.txt", op("hello", "\uD83D\uDE00", null, null)).isError());
        assertArrayEquals(u("\uD83D\uDE00 world\n"), Files.readAllBytes(ok));
        assertTrue(edit(t, ws, "bad.txt", op("hello", "\uD83D\uDE00", null, null)).isError());
        assertArrayEquals(badBytes, Files.readAllBytes(bad));
        assertFalse(edit(t, ws, "bad.txt", op("hello", "hi", null, null)).isError());
        assertArrayEquals(cat(u("hi "), b(0xFF), u(" world\n")), Files.readAllBytes(bad));

        // GBK 字符集：emoji 表示不了
        t.fileCharset = GBK;
        Path g = ws.resolve("g.txt");
        Files.write(g, "中文 hello\n".getBytes(GBK));
        ToolResult r = edit(t, ws, "g.txt", op("hello", "\uD83D\uDE00", null, null));
        assertTrue(r.isError(), r.getContent());
        assertTrue(r.getContent().contains("GBK"), r.getContent());
        assertArrayEquals("中文 hello\n".getBytes(GBK), Files.readAllBytes(g));
        assertFalse(edit(t, ws, "g.txt", op("hello", "你好", null, null)).isError());
        assertArrayEquals("中文 你好\n".getBytes(GBK), Files.readAllBytes(g));
    }

    /**
     * 个别字符集无法保证原样写回（主编码设为会自动加 BOM 的 UTF-16，而文件没有 BOM）：拒绝且文件不动。
     * 带 BOM 的 UTF-16 小端文件末尾多一个孤字节：现在按 BOM 识别为 UTF-16LE，孤字节原样保留，可以编辑。
     */
    @Test
    void notPreservableCharsetIsRejected() throws Exception {
        Path ws = tmp.resolve("ws");
        Files.createDirectories(ws);
        Path f = ws.resolve("u16.txt");
        byte[] original = "ab\n".getBytes(StandardCharsets.UTF_16BE);
        Files.write(f, original);
        TerminalTalent t = talent(ws);
        t.fileCharset = StandardCharsets.UTF_16;
        ToolResult r = edit(t, ws, "u16.txt", op("ab", "cd", null, null));
        assertTrue(r.isError(), r.getContent());
        assertTrue(r.getContent().contains("UTF-16"), r.getContent());
        assertArrayEquals(original, Files.readAllBytes(f));

        Path le = ws.resolve("le.txt");
        Files.write(le, cat(b(0xFF, 0xFE), "ab\n".getBytes(StandardCharsets.UTF_16LE), b(0x41)));
        r = edit(talent(ws), ws, "le.txt", op("ab", "cd", null, null));
        assertFalse(r.isError(), r.getContent());
        assertArrayEquals(cat(b(0xFF, 0xFE), "cd\n".getBytes(StandardCharsets.UTF_16LE), b(0x41)), Files.readAllBytes(le));
    }

    /** 合法文件：结果与原实现（new String / getBytes）逐字节相同。 */
    @Test
    void validFilesBehaveAsBefore() throws Exception {
        Path ws = tmp.resolve("ws");
        Files.createDirectories(ws);
        Random r = new Random(20261120L);
        String[] pieces = {"a", "token", " ", "\n", "\r\n", "中文", "\uD83D\uDE00", "\uFFFD", "\t", "\uFEFF"};
        TerminalTalent t = talent(ws);
        for (int i = 0; i < 200; i++) {
            StringBuilder sb = new StringBuilder();
            int n = 1 + r.nextInt(40);
            for (int k = 0; k < n; k++) {
                sb.append(pieces[r.nextInt(pieces.length)]);
            }
            sb.append("UNIQUE").append(i);
            String text = sb.toString();
            Path f = ws.resolve("v" + i + ".txt");
            Files.write(f, u(text));
            // 替换文本不带换行：CRLF 文件里 new_str 的 \n 会被规范化，不属本用例关心的范围
            String[] replacements = {"", "a", "中文", "\uD83D\uDE00", "\uFFFD", "\t"};
            String replacement = replacements[r.nextInt(replacements.length)] + "R";
            ToolResult res = edit(t, ws, f.getFileName().toString(), op("UNIQUE" + i, replacement, null, null));
            assertFalse(res.isError(), res.getContent());
            assertArrayEquals(u(text.replace("UNIQUE" + i, replacement)), Files.readAllBytes(f), text);
        }
    }

    /** 编解码往返：任意字节按 UTF-8 保字节解码再编码，逐字节还原；合法内容与 new String 一致。 */
    @Test
    void rawByteCodecRoundTripsArbitraryBytes() throws Exception {
        Random r = new Random(5);
        for (int round = 0; round < 20000; round++) {
            byte[] data = new byte[r.nextInt(24)];
            for (int i = 0; i < data.length; i++) {
                int kind = r.nextInt(4);
                data[i] = (byte) (kind == 0 ? r.nextInt(256) : kind == 1 ? 0x80 + r.nextInt(64) : kind == 2 ? 0xC0 + r.nextInt(64) : 'a');
            }
            String text = TerminalSupport.decodeKeepingRawBytes(data, StandardCharsets.UTF_8);
            assertArrayEquals(data, TerminalSupport.encodeKeepingRawBytes(text, StandardCharsets.UTF_8), Arrays.toString(data));
            assertTrue(TerminalSupport.writesBackUnchanged(text, data, StandardCharsets.UTF_8));
            if (!TerminalSupport.hasRawBytes(text)) {
                assertEquals(new String(data, StandardCharsets.UTF_8), text);
            }
            // 展示版：原字节位置对应 U+FFFD，无孤立代理
            assertNoLoneSurrogate(TerminalSupport.displayRawBytes(text));
        }
        // 大于解码缓冲的内容
        byte[] big = new byte[50000];
        r.nextBytes(big);
        String text = TerminalSupport.decodeKeepingRawBytes(big, StandardCharsets.UTF_8);
        assertArrayEquals(big, TerminalSupport.encodeKeepingRawBytes(text, StandardCharsets.UTF_8));
        // 代理对的低半部分落在映射区（U+1F400 = D83D DC00）也不会被误认
        String rat = new String(u("\uD83D\uDC00"), StandardCharsets.UTF_8);
        assertFalse(TerminalSupport.hasRawBytes(rat));
        assertArrayEquals(u("\uD83D\uDC00"), TerminalSupport.encodeKeepingRawBytes(rat, StandardCharsets.UTF_8));
    }

    /** read：含坏字节的文件能读出来，坏字节显示为 U+FFFD（原先抛 MalformedInputException）。 */
    @Test
    void readShowsReplacementCharacters() throws Exception {
        Path ws = tmp.resolve("ws");
        Files.createDirectories(ws);
        Files.write(ws.resolve("A.java"), cat(u("line one\nbad "), b(0xFF), u(" here\nline three\n")));
        String out = talent(ws).read("A.java", null, null, ws.toString(), null).getContent();
        assertTrue(out.contains("     2 | bad \uFFFD here\n"), out);
        assertTrue(out.contains("     3 | line three\n"), out);
        String page = talent(ws).read("A.java", 2, 1, ws.toString(), null).getContent();
        assertTrue(page.contains("     2 | bad \uFFFD here\n"), page);
    }

    /** 虚拟来源上的 edit：同样原样保留坏字节、匹配失败带提示。 */
    @Test
    void virtualEditKeepsRawBytes() throws Exception {
        Path ws = tmp.resolve("ws");
        Files.createDirectories(ws);
        InMemoryMountSource source = new InMemoryMountSource();
        byte[] original = "// 中文\nint x = 1;\n".getBytes(GBK);
        try (OutputStream out = source.openWrite("A.java", WriteOptions.replace())) {
            out.write(original);
        }
        MountManager manager = new MountManager(ws.toString());
        manager.register(Mount.builder().alias("@mem").type(MountType.FILES).source(source).writable(true).build());
        TerminalTalent t = new TerminalTalent(manager);

        List<TerminalTalent.EditOp> ops = Arrays.asList(op("// 中文", "// x", null, null));
        ToolResult r = t.edit("@mem/A.java", ops, ws.toString(), null);
        assertTrue(r.isError(), r.getContent());
        assertTrue(r.getContent().contains("Encoding"), r.getContent());
        assertArrayEquals(original, readAll(source, "A.java"));

        r = t.edit("@mem/A.java", Arrays.asList(op("x = 1", "x = 2", null, null)), ws.toString(), null);
        assertFalse(r.isError(), r.getContent());
        assertArrayEquals("// 中文\nint x = 2;\n".getBytes(GBK), readAll(source, "A.java"));

        r = t.edit("@mem/A.java", Arrays.asList(op("x = 2", "\uDCFF", null, null)), ws.toString(), null);
        assertTrue(r.isError(), r.getContent());
        assertArrayEquals("// 中文\nint x = 2;\n".getBytes(GBK), readAll(source, "A.java"));
    }

    private static byte[] readAll(InMemoryMountSource source, String path) throws Exception {
        try (InputStream in = source.openRead(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
