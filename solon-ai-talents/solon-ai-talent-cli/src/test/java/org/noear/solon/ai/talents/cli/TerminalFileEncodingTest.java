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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件编码识别接入 read / edit / write 后的行为（docs/file-encoding-plan.md 第三节的三张表，每行至少一例）。
 */
class TerminalFileEncodingTest {

    @TempDir
    Path tmp;

    private static final Charset GBK = Charset.forName("GBK");
    private static final byte[] BOM8 = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] BOM16LE = {(byte) 0xFF, (byte) 0xFE};
    static final String CN = "// 中文注释：用户服务\nclass A {\n    int x = 1;\n}\n";

    private Path ws() throws Exception {
        return Files.createDirectories(tmp.resolve("ws"));
    }

    private static TerminalTalent talent(Path ws) {
        return new TerminalTalent(new MountManager(ws.toString()));
    }

    private static TerminalTalent.EditOp op(String oldStr, String newStr) {
        TerminalTalent.EditOp op = new TerminalTalent.EditOp();
        op.oldStr = oldStr;
        op.newStr = newStr;
        return op;
    }

    private static ToolResult edit(TerminalTalent t, Path ws, String file, TerminalTalent.EditOp... ops) throws Exception {
        return t.edit(file, Arrays.asList(ops), ws.toString(), null);
    }

    private static String read(TerminalTalent t, Path ws, String file, Integer offset, Integer limit) throws Exception {
        return t.read(file, offset, limit, ws.toString(), null).getContent();
    }

    private static String header(String readOutput) {
        return readOutput.substring(0, readOutput.indexOf('\n'));
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    private static byte[] u(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ read

    @Test
    void readShowsEncodingOnFirstLine() throws Exception {
        Path ws = ws();
        TerminalTalent t = talent(ws);
        Files.write(ws.resolve("u.txt"), u(CN));
        Files.write(ws.resolve("g.java"), CN.getBytes(GBK));
        Files.write(ws.resolve("bom.txt"), cat(BOM8, u(CN)));
        Files.write(ws.resolve("u16.txt"), cat(BOM16LE, CN.getBytes(StandardCharsets.UTF_16LE)));
        Files.write(ws.resolve("bad.txt"), cat(u("hello "), new byte[]{(byte) 0xFF}, u(" world\n")));
        Files.write(ws.resolve("mixed.txt"), cat(u(CN + CN), "// 粘贴\n".getBytes(GBK)));

        assertEquals("--- File: u.txt (Lines: 1 - 4, Size: 0.06 KB, Encoding: UTF-8) ---", header(read(t, ws, "u.txt", null, null)));

        String g = read(t, ws, "g.java", null, null);
        assertTrue(header(g).endsWith("Encoding: GBK detected) ---"), g);
        assertTrue(g.contains("     1 | // 中文注释：用户服务\n"), g);

        String bom = read(t, ws, "bom.txt", null, null);
        assertTrue(header(bom).endsWith("Encoding: UTF-8 with BOM) ---"), bom);
        assertTrue(bom.contains("     1 | // 中文注释"), bom);

        // UTF-16 带 BOM：以前因 NUL 字节被当成二进制拒绝
        String u16 = read(t, ws, "u16.txt", null, null);
        assertTrue(header(u16).endsWith("Encoding: UTF-16LE with BOM) ---"), u16);
        assertTrue(u16.contains("     1 | // 中文注释：用户服务\n") && u16.contains("     3 |     int x = 1;\n"), u16);

        assertTrue(header(read(t, ws, "bad.txt", null, null)).endsWith("Encoding: unknown, 1 undecodable byte shown as U+FFFD) ---"));
        assertTrue(header(read(t, ws, "mixed.txt", null, null)).endsWith("Encoding: UTF-8, 2 undecodable bytes shown as U+FFFD) ---")); // “粘”的 GBK 编码 D5 B3 恰好是合法 UTF-8

        // 路径规则
        Files.write(ws.resolve("a.properties"), "name=caf\u00e9\n".getBytes(StandardCharsets.ISO_8859_1));
        t.setFileEncodingPolicy(FileEncodingPolicy.builder().rule("*.properties", StandardCharsets.ISO_8859_1).build());
        String p = read(t, ws, "a.properties", null, null);
        assertTrue(header(p).endsWith("Encoding: ISO-8859-1 configured) ---") && p.contains("     1 | name=café\n"), p);
    }

    /** 编码按整份文件识别：前面全是英文、后面才有 GBK 的文件，每一页的 Encoding 都一样，中文页显示正确。 */
    @Test
    void encodingIsDetectedOnWholeFileNotPage() throws Exception {
        Path ws = ws();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2500; i++) {
            sb.append("line ").append(i).append('\n');
        }
        sb.append("// 中文注释：最后一页\n");
        Files.write(ws.resolve("long.txt"), sb.toString().getBytes(GBK));
        TerminalTalent t = talent(ws);
        String first = read(t, ws, "long.txt", 1, 100);
        String last = read(t, ws, "long.txt", 2501, 10);
        assertTrue(header(first).endsWith("Encoding: GBK detected) ---"), header(first));
        assertTrue(header(last).endsWith("Encoding: GBK detected) ---"), header(last));
        assertTrue(last.contains("  2501 | // 中文注释：最后一页\n"), last);
    }

    /** 超过 10 MB：只抽样开头识别、流式读取，标签注明 sampled。 */
    @Test
    void hugeFileIsSampled() throws Exception {
        Path ws = ws();
        byte[] line = "// 中文注释：很长的文件\n".getBytes(GBK);
        byte[] big = new byte[TerminalSupport.GREP_MAX_FILE_BYTES + line.length * 2];
        for (int i = 0; i + line.length <= big.length; i += line.length) {
            System.arraycopy(line, 0, big, i, line.length);
        }
        Files.write(ws.resolve("big.txt"), big);
        String out = read(talent(ws), ws, "big.txt", 1, 2);
        assertTrue(header(out).endsWith("Encoding: GBK detected, sampled first 1 MB) ---"), header(out));
        assertTrue(out.contains("     1 | // 中文注释：很长的文件\n"), out);
    }

    // ------------------------------------------------------------------ edit

    @Test
    void editGbkFileInItsOwnEncoding() throws Exception {
        Path ws = ws();
        Path f = ws.resolve("A.java");
        Files.write(f, CN.getBytes(GBK));
        TerminalTalent t = talent(ws);

        ToolResult r = edit(t, ws, "A.java", op("// 中文注释：用户服务", "// 中文注释：订单服务"), op("int x = 1;", "int x = 2; // 新增"));
        assertFalse(r.isError(), r.getContent());
        assertEquals("文件 A.java 成功完成 2 处修改。\n已按 GBK 编码保存。", r.getContent());
        assertArrayEquals(CN.replace("用户", "订单").replace("int x = 1;", "int x = 2; // 新增").getBytes(GBK), Files.readAllBytes(f));

        // GBK 表示不了的字符：拒绝，指出是哪个字符、第几个，文件不动
        byte[] before = Files.readAllBytes(f);
        r = edit(t, ws, "A.java", op("订单", "订单\uD83D\uDE00"));
        assertTrue(r.isError(), r.getContent());
        assertTrue(r.getContent().contains("'\uD83D\uDE00' (U+1F600)（第 3 个字符）") && r.getContent().contains("GBK"), r.getContent());
        assertArrayEquals(before, Files.readAllBytes(f));
    }

    @Test
    void editKeepsBomAndUtf16() throws Exception {
        Path ws = ws();
        TerminalTalent t = talent(ws);
        Path u16 = ws.resolve("u16.txt");
        Files.write(u16, cat(BOM16LE, "第一行\r\nkey=1\r\n".getBytes(StandardCharsets.UTF_16LE)));
        ToolResult r = edit(t, ws, "u16.txt", op("key=1", "键=2"));
        assertFalse(r.isError(), r.getContent());
        assertTrue(r.getContent().endsWith("已按 UTF-16LE with BOM 编码保存。"), r.getContent());
        assertArrayEquals(cat(BOM16LE, "第一行\r\n键=2\r\n".getBytes(StandardCharsets.UTF_16LE)), Files.readAllBytes(u16));

        Path bom = ws.resolve("bom.txt");
        Files.write(bom, cat(BOM8, u("a\n")));
        r = edit(t, ws, "bom.txt", op("a", "中"));
        assertTrue(r.getContent().endsWith("已按 UTF-8 with BOM 编码保存。"), r.getContent());
        assertArrayEquals(cat(BOM8, u("中\n")), Files.readAllBytes(bom));

        // 普通 UTF-8：提示与原来一字不差
        Files.write(ws.resolve("p.txt"), u("a\n"));
        assertEquals("文件 p.txt 成功完成 1 处修改。", edit(t, ws, "p.txt", op("a", "中")).getContent());
    }

    @Test
    void editUnknownAndMixedFiles() throws Exception {
        Path ws = ws();
        TerminalTalent t = talent(ws);
        byte[] unknown = cat(u("hello "), new byte[]{(byte) 0xFF}, u(" world\n"));
        Files.write(ws.resolve("x.txt"), unknown);
        ToolResult r = edit(t, ws, "x.txt", op("world", "世界"));
        assertTrue(r.isError(), r.getContent());
        assertTrue(r.getContent().contains("无法确定该文件的编码（有 1 个字节无法按 UTF-8 解码）"), r.getContent());
        assertArrayEquals(unknown, Files.readAllBytes(ws.resolve("x.txt")));
        r = t.edit("x.txt", Arrays.asList(op("world", "世界")), ws.toString(), "en");
        assertTrue(r.getContent().contains("the encoding of this file cannot be determined (bytes that cannot be decoded as UTF-8: 1)"), r.getContent());

        byte[] mixed = cat(u(CN + CN), "// 粘贴\n".getBytes(GBK));
        Files.write(ws.resolve("m.txt"), mixed);
        r = edit(t, ws, "m.txt", op("int x = 1;\n}\n// 中文", "int x = 1;\n}\n// 汉字"));
        assertFalse(r.isError(), r.getContent());
        assertTrue(r.getContent().endsWith("已按 UTF-8 编码保存，2 个无法解码的字节已原样保留。"), r.getContent());
        assertArrayEquals(cat(u(CN + CN.replaceFirst("// 中文", "// 汉字")), "// 粘贴\n".getBytes(GBK)), Files.readAllBytes(ws.resolve("m.txt")));
    }

    // ------------------------------------------------------------------ write

    @Test
    void writeNewFiles() throws Exception {
        Path ws = ws();
        TerminalTalent t = talent(ws);
        ToolResult r = t.write("n.txt", "中文\n", ws.toString(), null);
        assertEquals("文件成功写入: n.txt", r.getContent());
        assertArrayEquals(u("中文\n"), Files.readAllBytes(ws.resolve("n.txt")));

        // 孤立代理：原先被写成 '?'，现在拒绝且不创建文件
        r = t.write("s.txt", "x\uD83D", ws.toString(), null);
        assertTrue(r.isError(), r.getContent());
        assertTrue(r.getContent().contains("U+D83D（第 2 个字符）"), r.getContent());
        assertFalse(Files.exists(ws.resolve("s.txt")));

        t.setFileEncodingPolicy(FileEncodingPolicy.builder().rule("legacy/**", GBK).build());
        r = t.write("legacy/a.c", "// 中文\n", ws.toString(), null);
        assertEquals("文件成功写入: legacy/a.c\n已按 GBK 编码保存。", r.getContent());
        assertArrayEquals("// 中文\n".getBytes(GBK), Files.readAllBytes(ws.resolve("legacy/a.c")));
    }

    @Test
    void writeOverwritesInTheExistingEncoding() throws Exception {
        Path ws = ws();
        TerminalTalent t = talent(ws);
        Path g = ws.resolve("g.java");
        Files.write(g, CN.getBytes(GBK));
        ToolResult r = t.write("g.java", "// 全部重写\n", ws.toString(), null);
        assertEquals("文件成功写入: g.java\n已按 GBK 编码保存。", r.getContent());
        assertArrayEquals("// 全部重写\n".getBytes(GBK), Files.readAllBytes(g));
        r = t.write("g.java", "\uD83D\uDE00\n", ws.toString(), null);
        assertTrue(r.isError() && r.getContent().contains("GBK"), r.getContent());
        assertArrayEquals("// 全部重写\n".getBytes(GBK), Files.readAllBytes(g));

        // UTF-8 BOM：保留，内容自带 BOM 时不写两个
        Path bom = ws.resolve("bom.txt");
        Files.write(bom, cat(BOM8, u("old\n")));
        assertTrue(t.write("bom.txt", "new\n", ws.toString(), null).getContent().endsWith("已按 UTF-8 with BOM 编码保存。"));
        assertArrayEquals(cat(BOM8, u("new\n")), Files.readAllBytes(bom));
        t.write("bom.txt", "\uFEFFnew2\n", ws.toString(), null);
        assertArrayEquals(cat(BOM8, u("new2\n")), Files.readAllBytes(bom));

        // UTF-16LE BOM：按原字节序写
        Path u16 = ws.resolve("u16.txt");
        Files.write(u16, cat(BOM16LE, "a\n".getBytes(StandardCharsets.UTF_16LE)));
        t.write("u16.txt", "中\n", ws.toString(), null);
        assertArrayEquals(cat(BOM16LE, "中\n".getBytes(StandardCharsets.UTF_16LE)), Files.readAllBytes(u16));

        // 普通 UTF-8：与原来逐字节相同（内容自带 BOM 字符也照写）
        Path p = ws.resolve("p.txt");
        Files.write(p, u("old\n"));
        assertEquals("文件成功写入: p.txt", t.write("p.txt", "\uFEFFx\n", ws.toString(), null).getContent());
        assertArrayEquals(u("\uFEFFx\n"), Files.readAllBytes(p));
    }

    @Test
    void writeRefusesToDestroyUndecodableBytes() throws Exception {
        Path ws = ws();
        TerminalTalent t = talent(ws);
        Path x = ws.resolve("x.txt");
        byte[] unknown = cat(u("hello "), new byte[]{(byte) 0xFF}, u(" world\n"));
        Files.write(x, unknown);

        // 照抄 read 显示（含 �）整份覆盖：会永久丢掉原字节
        ToolResult r = t.write("x.txt", "hello \uFFFD world!\n", ws.toString(), null);
        assertTrue(r.isError(), r.getContent());
        assertTrue(r.getContent().contains("原文件有 1 个无法解码的字节") && r.getContent().contains("edit"), r.getContent());
        assertFalse(r.getContent().contains("转成 UTF-8"), r.getContent());
        assertArrayEquals(unknown, Files.readAllBytes(x));
        // 非 ASCII：会混入另一种编码
        r = t.write("x.txt", "你好\n", ws.toString(), null);
        assertTrue(r.isError() && r.getContent().contains("非 ASCII"), r.getContent());
        assertArrayEquals(unknown, Files.readAllBytes(x));
        // 纯 ASCII 可以
        assertFalse(t.write("x.txt", "hello\n", ws.toString(), null).isError());
        assertArrayEquals(u("hello\n"), Files.readAllBytes(x));

        // 大体是 UTF-8 的文件：不含 � 的内容按 UTF-8 写
        Path m = ws.resolve("m.txt");
        Files.write(m, cat(u(CN + CN), "// 粘贴\n".getBytes(GBK)));
        assertTrue(t.write("m.txt", "x \uFFFD\n", ws.toString(), null).isError());
        assertEquals("文件成功写入: m.txt", t.write("m.txt", "中文\n", ws.toString(), null).getContent());
        assertArrayEquals(u("中文\n"), Files.readAllBytes(m));

        // 二进制文件不受保护（覆盖二进制文件是用户的本意）
        Path bin = ws.resolve("b.dat");
        Files.write(bin, new byte[]{0, 1, 2, (byte) 0xFF});
        assertFalse(t.write("b.dat", "文本\n", ws.toString(), null).isError());
        assertArrayEquals(u("文本\n"), Files.readAllBytes(bin));

        // 英文
        Files.write(x, unknown);
        r = t.write("x.txt", "\uFFFD", ws.toString(), "en");
        assertTrue(r.getContent().startsWith("Error: the existing file has undecodable bytes (count: 1;"), r.getContent());
    }

    // ------------------------------------------------------------------ 虚拟来源

    @Test
    void virtualSourcesFollowTheSameRules() throws Exception {
        Path ws = ws();
        InMemoryMountSource source = new InMemoryMountSource();
        put(source, "g.java", CN.getBytes(GBK));
        put(source, "x.txt", cat(u("hello "), new byte[]{(byte) 0xFF}, u(" world\n")));
        MountManager manager = new MountManager(ws.toString());
        manager.register(Mount.builder().alias("@mem").type(MountType.FILES).source(source).writable(true).build());
        TerminalTalent t = new TerminalTalent(manager);

        String out = t.read("@mem/g.java", null, null, ws.toString(), null).getContent();
        assertTrue(header(out).endsWith("Encoding: GBK detected) ---") && out.contains("     1 | // 中文注释：用户服务\n"), out);

        ToolResult r = t.edit("@mem/g.java", Arrays.asList(op("用户", "订单")), ws.toString(), null);
        assertEquals("文件 @mem/g.java 成功完成 1 处修改。\n已按 GBK 编码保存。", r.getContent());
        assertArrayEquals(CN.replace("用户", "订单").getBytes(GBK), get(source, "g.java"));

        r = t.write("@mem/g.java", "// 重写\n", ws.toString(), null);
        assertEquals("文件成功写入: @mem/g.java\n已按 GBK 编码保存。", r.getContent());
        assertArrayEquals("// 重写\n".getBytes(GBK), get(source, "g.java"));

        byte[] before = get(source, "x.txt");
        assertTrue(t.write("@mem/x.txt", "\uFFFD", ws.toString(), null).isError());
        assertTrue(t.edit("@mem/x.txt", Arrays.asList(op("world", "世界")), ws.toString(), null).isError());
        assertArrayEquals(before, get(source, "x.txt"));

        assertEquals("文件成功写入: @mem/new.txt", t.write("@mem/new.txt", "中文", ws.toString(), null).getContent());
        assertArrayEquals(u("中文"), get(source, "new.txt"));
    }

    private static void put(InMemoryMountSource source, String path, byte[] data) throws Exception {
        try (OutputStream out = source.openWrite(path, WriteOptions.replace())) {
            out.write(data);
        }
    }

    private static byte[] get(InMemoryMountSource source, String path) throws Exception {
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
