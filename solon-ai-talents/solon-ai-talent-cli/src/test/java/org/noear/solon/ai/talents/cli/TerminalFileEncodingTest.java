package org.noear.solon.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.mount.Mount;
import org.noear.solon.ai.mount.MountManager;
import org.noear.solon.ai.mount.MountType;
import org.noear.solon.ai.mount.source.InMemoryMountSource;
import org.noear.solon.ai.mount.source.WriteOptions;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件编码接入 read / edit / write 后的行为：只认 UTF-8 与 BOM（UTF-8 / UTF-16LE / UTF-16BE），不猜其他编码。
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

        // GBK 不猜：标明不是 UTF-8，中文显示为 �，ASCII 照常
        String g = read(t, ws, "g.java", null, null);
        assertTrue(header(g).matches(".*Encoding: not UTF-8, \\d+ undecodable bytes shown as U\\+FFFD\\) ---"), g);
        assertTrue(g.contains("     1 | // \uFFFD") && g.contains("     3 |     int x = 1;\n"), g);

        String bom = read(t, ws, "bom.txt", null, null);
        assertTrue(header(bom).endsWith("Encoding: UTF-8 with BOM) ---"), bom);
        assertTrue(bom.contains("     1 | // 中文注释"), bom);

        // UTF-16 带 BOM：以前因 NUL 字节被当成二进制拒绝
        String u16 = read(t, ws, "u16.txt", null, null);
        assertTrue(header(u16).endsWith("Encoding: UTF-16LE with BOM) ---"), u16);
        assertTrue(u16.contains("     1 | // 中文注释：用户服务\n") && u16.contains("     3 |     int x = 1;\n"), u16);

        assertTrue(header(read(t, ws, "bad.txt", null, null)).endsWith("Encoding: not UTF-8, 1 undecodable byte shown as U+FFFD) ---"));
        assertTrue(header(read(t, ws, "mixed.txt", null, null)).endsWith("Encoding: UTF-8, 2 undecodable bytes shown as U+FFFD) ---")); // “粘”的 GBK 编码 D5 B3 恰好是合法 UTF-8
    }

    /** 编码按整份文件判断：前面全是英文、最后才有坏字节的文件，每一页的 Encoding 都一样。 */
    @Test
    void encodingIsJudgedOnWholeFileNotPage() throws Exception {
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
        assertTrue(header(first).contains("Encoding: not UTF-8, "), header(first));
        assertEquals(header(first).replaceAll("Lines: \\d+ - \\d+", ""), header(last).replaceAll("Lines: \\d+ - \\d+", ""));
    }

    /** 超过 10 MB：只抽样开头判断、流式读取，标签注明 sampled。 */
    @Test
    void hugeFileIsSampled() throws Exception {
        Path ws = ws();
        byte[] line = u("// 中文注释：很长的文件\n");
        byte[] big = new byte[TerminalSupport.GREP_MAX_FILE_BYTES + line.length * 2];
        for (int i = 0; i + line.length <= big.length; i += line.length) {
            System.arraycopy(line, 0, big, i, line.length);
        }
        Files.write(ws.resolve("big.txt"), big);
        String out = read(talent(ws), ws, "big.txt", 1, 2);
        assertTrue(header(out).endsWith("Encoding: UTF-8, sampled first 1 MB) ---"), header(out));
        assertTrue(out.contains("     1 | // 中文注释：很长的文件\n"), out);

        String text = new String(big, StandardCharsets.UTF_8);
        Files.write(ws.resolve("big16.txt"), cat(BOM16LE, (text + text).getBytes(StandardCharsets.UTF_16LE)));
        out = read(talent(ws), ws, "big16.txt", 2, 1);
        assertTrue(header(out).endsWith("Encoding: UTF-16LE with BOM, sampled first 1 MB) ---"), header(out));
        assertTrue(out.contains("     2 | // 中文注释：很长的文件\n"), out);
    }

    private static final String[] PIECES = {"a", "bc", " ", "\t", "中文", "注释", "：", "é", "😀", "\n", "\r\n", "\r", "\n\n", "x=1;"};

    /** 各种形态的文件：UTF-8、带 BOM、GBK、夹坏字节、UTF-16LE 带 BOM、换行混用。 */
    private static byte[] randomFile(Random r) {
        StringBuilder sb = new StringBuilder();
        int n = 20 + r.nextInt(200);
        for (int i = 0; i < n; i++) {
            sb.append(PIECES[r.nextInt(PIECES.length)]);
        }
        String text = sb.toString();
        switch (r.nextInt(6)) {
            case 1:
                return cat(BOM8, u(text));
            case 2:
                return text.replace("😀", "用户").replace("é", "e").getBytes(GBK);
            case 3: {
                byte[] b = u(text);
                for (int k = 0; k < 3 && b.length > 0; k++) {
                    b[r.nextInt(b.length)] = (byte) (0x80 + r.nextInt(0x80));
                }
                return b;
            }
            case 4:
                return cat(BOM16LE, text.getBytes(StandardCharsets.UTF_16LE));
            default:
                return u(text);
        }
    }

    private static List<String> bodyLines(String out) {
        List<String> lines = new ArrayList<>();
        for (String line : out.split("\n", -1)) {
            if (line.matches("^ *\\d+ \\| .*")) {
                lines.add(line);
            }
        }
        return lines;
    }

    /** 任意页与“按判断出的编码整份替换解码、再逐行切分”的结果逐字相同，翻页时首行不变。 */
    @Test
    void readPagesMatchFullDecode() throws Exception {
        Path ws = ws();
        TerminalTalent t = talent(ws);
        Random r = new Random(20261120);
        for (int round = 0; round < 300; round++) {
            byte[] data = randomFile(r);
            Path f = ws.resolve("f" + round + ".txt");
            Files.write(f, data);
            TextEncodings.Decoded d = TextEncodings.detect(data, data.length, false, StandardCharsets.UTF_8);
            List<String> expected = new BufferedReader(new StringReader(d.displayText())).lines().collect(Collectors.toList());
            if (expected.isEmpty()) {
                continue;
            }
            String full = read(t, ws, f.getFileName().toString(), null, null);
            List<String> all = bodyLines(full);
            assertEquals(expected.size(), all.size(), full);
            for (int i = 0; i < expected.size(); i++) {
                assertEquals(String.format("%6d | %s", i + 1, expected.get(i)), all.get(i));
            }
            for (int k = 0; k < 4; k++) {
                int offset = 2 + r.nextInt(expected.size() + 1);
                int limit = 1 + r.nextInt(5);
                String page = read(t, ws, f.getFileName().toString(), offset, limit);
                if (offset > expected.size()) {
                    assertTrue(bodyLines(page).isEmpty(), page);
                    continue;
                }
                assertEquals(header(full).replaceAll("Lines: \\d+ - \\d+", ""), header(page).replaceAll("Lines: \\d+ - \\d+", ""));
                assertEquals(all.subList(offset - 1, Math.min(all.size(), offset - 1 + limit)), bodyLines(page), page);
            }
        }
    }

    // ------------------------------------------------------------------ edit

    /** 不是 UTF-8 的文件（GBK）：只改 ASCII 可以，中文原字节逐字节保留；写入非 ASCII 拒绝，文件不动。 */
    @Test
    void editNotUtf8File() throws Exception {
        Path ws = ws();
        Path f = ws.resolve("A.java");
        Files.write(f, CN.getBytes(GBK));
        TerminalTalent t = talent(ws);

        ToolResult r = edit(t, ws, "A.java", op("int x = 1;", "int x = 2;"));
        assertEquals("文件 A.java 成功完成 1 处修改。", r.getContent());
        assertArrayEquals(CN.replace("int x = 1;", "int x = 2;").getBytes(GBK), Files.readAllBytes(f));

        byte[] before = Files.readAllBytes(f);
        r = edit(t, ws, "A.java", op("int x = 2;", "int x = 3; // 新增"));
        assertTrue(r.isError(), r.getContent());
        assertTrue(r.getContent().contains("该文件不是 UTF-8（有 ") && r.getContent().contains("请告诉用户该文件的编码无法自动识别"), r.getContent());
        assertFalse(r.getContent().contains("配置") || r.getContent().contains("转成") || r.getContent().contains("重写"), r.getContent());
        assertArrayEquals(before, Files.readAllBytes(f));
        r = t.edit("A.java", Arrays.asList(op("int x = 2;", "int x = 3; // 新增")), ws.toString(), "en");
        assertTrue(r.getContent().contains("this file is not UTF-8 (bytes that cannot be decoded as UTF-8: "), r.getContent());
        assertFalse(r.getContent().contains("configure"), r.getContent());
    }

    @Test
    void editKeepsBomAndUtf16() throws Exception {
        Path ws = ws();
        TerminalTalent t = talent(ws);
        Path u16 = ws.resolve("u16.txt");
        Files.write(u16, cat(BOM16LE, "第一行\r\nkey=1\r\n".getBytes(StandardCharsets.UTF_16LE)));
        ToolResult r = edit(t, ws, "u16.txt", op("key=1", "键=2"));
        assertEquals("文件 u16.txt 成功完成 1 处修改。", r.getContent());
        assertArrayEquals(cat(BOM16LE, "第一行\r\n键=2\r\n".getBytes(StandardCharsets.UTF_16LE)), Files.readAllBytes(u16));

        Path be = ws.resolve("be.txt");
        Files.write(be, cat(new byte[]{(byte) 0xFE, (byte) 0xFF}, "a\n".getBytes(StandardCharsets.UTF_16BE)));
        assertFalse(edit(t, ws, "be.txt", op("a", "中")).isError());
        assertArrayEquals(cat(new byte[]{(byte) 0xFE, (byte) 0xFF}, "中\n".getBytes(StandardCharsets.UTF_16BE)), Files.readAllBytes(be));

        Path bom = ws.resolve("bom.txt");
        Files.write(bom, cat(BOM8, u("a\n")));
        assertEquals("文件 bom.txt 成功完成 1 处修改。", edit(t, ws, "bom.txt", op("a", "中")).getContent());
        assertArrayEquals(cat(BOM8, u("中\n")), Files.readAllBytes(bom));
    }

    /** 大体是 UTF-8、夹少量坏字节：可以写中文，坏字节原样保留。 */
    @Test
    void editMixedFile() throws Exception {
        Path ws = ws();
        TerminalTalent t = talent(ws);
        byte[] mixed = cat(u(CN + CN), "// 粘贴\n".getBytes(GBK));
        Files.write(ws.resolve("m.txt"), mixed);
        ToolResult r = edit(t, ws, "m.txt", op("int x = 1;\n}\n// 中文", "int x = 1;\n}\n// 汉字"));
        assertEquals("文件 m.txt 成功完成 1 处修改。", r.getContent());
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
    }

    @Test
    void writeOverwritesKeepingBom() throws Exception {
        Path ws = ws();
        TerminalTalent t = talent(ws);
        // UTF-8 BOM：保留，内容自带 BOM 时不写两个
        Path bom = ws.resolve("bom.txt");
        Files.write(bom, cat(BOM8, u("old\n")));
        assertEquals("文件成功写入: bom.txt", t.write("bom.txt", "new\n", ws.toString(), null).getContent());
        assertArrayEquals(cat(BOM8, u("new\n")), Files.readAllBytes(bom));
        t.write("bom.txt", "\uFEFFnew2\n", ws.toString(), null);
        assertArrayEquals(cat(BOM8, u("new2\n")), Files.readAllBytes(bom));

        // UTF-16LE BOM：按原字节序写
        Path u16 = ws.resolve("u16.txt");
        Files.write(u16, cat(BOM16LE, "a\n".getBytes(StandardCharsets.UTF_16LE)));
        assertEquals("文件成功写入: u16.txt", t.write("u16.txt", "中\n", ws.toString(), null).getContent());
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
        Path g = ws.resolve("g.java");
        byte[] gbk = CN.getBytes(GBK);
        Files.write(g, gbk);

        // 照抄 read 显示（含 �）整份覆盖：会永久丢掉原字节
        ToolResult r = t.write("g.java", "// \uFFFD\uFFFD\nclass A {}\n", ws.toString(), null);
        assertTrue(r.isError(), r.getContent());
        assertTrue(r.getContent().contains("个无法解码的字节") && r.getContent().contains("edit"), r.getContent());
        assertFalse(r.getContent().contains("转成 UTF-8"), r.getContent());
        assertArrayEquals(gbk, Files.readAllBytes(g));
        // 非 ASCII：文件会悄悄换成另一种编码
        r = t.write("g.java", "// 全部重写\n", ws.toString(), null);
        assertTrue(r.isError() && r.getContent().contains("原文件不是 UTF-8") && !r.getContent().contains("配置"), r.getContent());
        assertArrayEquals(gbk, Files.readAllBytes(g));
        // 纯 ASCII 可以
        assertEquals("文件成功写入: g.java", t.write("g.java", "class A {}\n", ws.toString(), null).getContent());
        assertArrayEquals(u("class A {}\n"), Files.readAllBytes(g));

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
        Files.write(g, gbk);
        r = t.write("g.java", "\uFFFD", ws.toString(), "en");
        assertTrue(r.getContent().startsWith("Error: the existing file has undecodable bytes (count: "), r.getContent());
        r = t.write("g.java", "中", ws.toString(), "en");
        assertTrue(r.getContent().startsWith("Error: the existing file is not UTF-8 (bytes that cannot be decoded as UTF-8: "), r.getContent());
    }

    /** 超过 10 MB 的文件：坏字节只在 1 MB 之后也要拦住（覆盖前扫完整份，不像 read 只抽样开头）。 */
    @Test
    void writeChecksWholeHugeFile() throws Exception {
        Path ws = ws();
        TerminalTalent t = talent(ws);
        byte[] ascii = new byte[TerminalSupport.GREP_MAX_FILE_BYTES + 1000];
        Arrays.fill(ascii, (byte) 'a');
        for (int i = 79; i < ascii.length; i += 80) {
            ascii[i] = '\n';
        }

        // 尾部是 GBK：含 � 的内容、非 ASCII 内容都拒绝，文件不动；纯 ASCII 可以
        Path g = ws.resolve("huge.txt");
        byte[] gbk = cat(ascii, "// 中文注释\n".getBytes(GBK));
        Files.write(g, gbk);
        ToolResult r = t.write("huge.txt", "// \uFFFD\uFFFD\n", ws.toString(), null);
        // 整份统计（“注”的 GBK 编码 D7 A2 恰好是合法 UTF-8，其余 6 个字节解不了）；只看开头 1 MB 会是 0 个
        assertTrue(r.isError() && r.getContent().contains("原文件有 6 个无法解码的字节"), r.getContent());
        r = t.write("huge.txt", "// 中\n", ws.toString(), null);
        assertTrue(r.isError() && r.getContent().contains("原文件不是 UTF-8"), r.getContent());
        assertArrayEquals(gbk, Files.readAllBytes(g));
        assertFalse(t.write("huge.txt", "ascii\n", ws.toString(), null).isError());
        assertArrayEquals(u("ascii\n"), Files.readAllBytes(g));

        // 尾部是合法 UTF-8 中文：照常写中文
        Path ok = ws.resolve("huge-ok.txt");
        Files.write(ok, cat(ascii, u("// 中文\n")));
        assertEquals("文件成功写入: huge-ok.txt", t.write("huge-ok.txt", "中文\n", ws.toString(), null).getContent());

        // UTF-16LE BOM，尾部有孤立代理：含 � 的内容拒绝；其余沿用 BOM
        Path u16 = ws.resolve("huge16.txt");
        byte[] body = new String(ascii, StandardCharsets.US_ASCII).getBytes(StandardCharsets.UTF_16LE);
        byte[] u16Bytes = cat(BOM16LE, body, new byte[]{0x3D, (byte) 0xD8}, "\n".getBytes(StandardCharsets.UTF_16LE));
        Files.write(u16, u16Bytes);
        assertTrue(t.write("huge16.txt", "\uFFFD\n", ws.toString(), null).isError());
        assertArrayEquals(u16Bytes, Files.readAllBytes(u16));
        assertFalse(t.write("huge16.txt", "中\n", ws.toString(), null).isError());
        assertArrayEquals(cat(BOM16LE, "中\n".getBytes(StandardCharsets.UTF_16LE)), Files.readAllBytes(u16));

        // 没有已知后缀的二进制大文件：开头探测到就不保护
        Path bin = ws.resolve("huge.raw");
        byte[] binBytes = gbk.clone();
        binBytes[0] = 0;
        Files.write(bin, binBytes);
        assertFalse(t.write("huge.raw", "文本\n", ws.toString(), null).isError());
    }

    /** read 先读开头探测二进制：探测长度上下的文本文件完整读出，开头是二进制的拒绝。 */
    @Test
    void readSniffsHeadBeforeReadingWhole() throws Exception {
        Path ws = ws();
        TerminalTalent t = talent(ws);
        int sniff = TerminalSupport.BINARY_SNIFF_BYTES;
        for (int size : new int[]{sniff - 1, sniff, sniff + 1, sniff * 3}) {
            byte[] data = new byte[size];
            Arrays.fill(data, (byte) 'x');
            data[size - 1] = '\n';
            byte[] tail = u("中\n");
            System.arraycopy(tail, 0, data, size - tail.length, tail.length);
            Files.write(ws.resolve("s" + size + ".txt"), data);
            String out = read(t, ws, "s" + size + ".txt", null, null);
            assertTrue(header(out).endsWith("Encoding: UTF-8) ---"), out);
            assertTrue(out.contains("     1 | " + new String(data, 0, size - 1, StandardCharsets.UTF_8) + "\n"), size + "");
        }
        byte[] bin = new byte[sniff * 3];
        Arrays.fill(bin, (byte) 'x');
        bin[10] = 0;
        Files.write(ws.resolve("b.raw"), bin);
        assertEquals("错误：该文件是二进制格式，无法作为文本读取。", t.read("b.raw", null, null, ws.toString(), null).getContent());
    }

    // ------------------------------------------------------------------ 虚拟来源

    @Test
    void virtualSourcesFollowTheSameRules() throws Exception {
        Path ws = ws();
        InMemoryMountSource source = new InMemoryMountSource();
        put(source, "g.java", CN.getBytes(GBK));
        put(source, "u16.txt", cat(BOM16LE, CN.getBytes(StandardCharsets.UTF_16LE)));
        MountManager manager = new MountManager(ws.toString());
        manager.register(Mount.builder().alias("@mem").type(MountType.FILES).source(source).writable(true).build());
        TerminalTalent t = new TerminalTalent(manager);

        String out = t.read("@mem/g.java", null, null, ws.toString(), null).getContent();
        assertTrue(header(out).contains("Encoding: not UTF-8, ") && out.contains("     3 |     int x = 1;\n"), out);
        out = t.read("@mem/u16.txt", null, null, ws.toString(), null).getContent();
        assertTrue(header(out).endsWith("Encoding: UTF-16LE with BOM) ---") && out.contains("     1 | // 中文注释：用户服务\n"), out);

        assertEquals("文件 @mem/g.java 成功完成 1 处修改。", t.edit("@mem/g.java", Arrays.asList(op("x = 1", "x = 2")), ws.toString(), null).getContent());
        assertArrayEquals(CN.replace("x = 1", "x = 2").getBytes(GBK), get(source, "g.java"));
        byte[] before = get(source, "g.java");
        assertTrue(t.edit("@mem/g.java", Arrays.asList(op("x = 2", "x = 中")), ws.toString(), null).isError());
        assertTrue(t.write("@mem/g.java", "// \uFFFD\n", ws.toString(), null).isError());
        assertTrue(t.write("@mem/g.java", "// 中\n", ws.toString(), null).isError());
        assertArrayEquals(before, get(source, "g.java"));

        assertEquals("文件 @mem/u16.txt 成功完成 1 处修改。", t.edit("@mem/u16.txt", Arrays.asList(op("用户", "订单")), ws.toString(), null).getContent());
        assertArrayEquals(cat(BOM16LE, CN.replace("用户", "订单").getBytes(StandardCharsets.UTF_16LE)), get(source, "u16.txt"));
        assertEquals("文件成功写入: @mem/u16.txt", t.write("@mem/u16.txt", "// 重写\n", ws.toString(), null).getContent());
        assertArrayEquals(cat(BOM16LE, "// 重写\n".getBytes(StandardCharsets.UTF_16LE)), get(source, "u16.txt"));

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
