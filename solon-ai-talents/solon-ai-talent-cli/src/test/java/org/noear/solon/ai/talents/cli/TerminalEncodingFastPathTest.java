package org.noear.solon.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.mount.MountManager;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编码处理的提速路径与原做法结果相同（docs/file-encoding-plan.md「实施后的性能收紧」）：
 * read 只解码当前页、翻页时在原字节上跳行；识别结果复用；保字节编码的整体快路径；grep 先按各编码的字节预筛。
 */
class TerminalEncodingFastPathTest {

    @TempDir
    Path tmp;

    private static final Charset GBK = Charset.forName("GBK");
    private static final String[] PIECES = {"a", "bc", " ", "\t", "中文", "注释", "：", "é", "😀", "\n", "\r\n", "\r", "\n\n", "x=1;"};

    private static String randomText(Random r, int pieces, boolean gbkSafe) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pieces; i++) {
            String p = PIECES[r.nextInt(PIECES.length)];
            if (gbkSafe && (p.equals("😀") || p.equals("é"))) {
                p = "用户";
            }
            sb.append(p);
        }
        return sb.toString();
    }

    /** 各种形态的文件内容：UTF-8、带 BOM、GBK、夹坏字节、UTF-16LE 带 BOM、换行混用。 */
    private static byte[] randomFile(Random r) {
        int kind = r.nextInt(6);
        String text = randomText(r, 20 + r.nextInt(200), kind == 2);
        switch (kind) {
            case 1:
                return TerminalFileEncodingTest.cat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, text.getBytes(StandardCharsets.UTF_8));
            case 2:
                return (text + "这是中文的说明，用户在这里可以看到数据的处理结果。\n").getBytes(GBK);
            case 3: {
                byte[] u = text.getBytes(StandardCharsets.UTF_8);
                for (int k = 0; k < 3 && u.length > 0; k++) {
                    u[r.nextInt(u.length)] = (byte) (0x80 + r.nextInt(0x80));
                }
                return u;
            }
            case 4:
                return TerminalFileEncodingTest.cat(new byte[]{(byte) 0xFF, (byte) 0xFE}, text.getBytes(StandardCharsets.UTF_16LE));
            default:
                return text.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** read 输出的正文行（去掉首行元信息、分隔线与末尾提示）。 */
    private static List<String> bodyLines(String out) {
        List<String> lines = new ArrayList<>();
        for (String line : out.split("\n", -1)) {
            if (line.matches("^ *\\d+ \\| .*")) {
                lines.add(line);
            }
        }
        return lines;
    }

    @Test
    void readPagesMatchFullDecode() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        TerminalTalent t = new TerminalTalent(new MountManager(ws.toString()));
        Random r = new Random(20261120);
        int checkedSkips = 0;
        for (int round = 0; round < 300; round++) {
            byte[] data = randomFile(r);
            Path f = ws.resolve("f" + round + ".txt");
            Files.write(f, data);

            // 参照：按识别出的编码整份替换解码，再逐行切分（原实现的做法）
            TextEncodings.Decoded d = TextEncodings.detect(data, data.length, false, StandardCharsets.UTF_8, FileEncodingPolicy.defaults(), null);
            String text = d.displayText();
            List<String> expected = new BufferedReader(new StringReader(text)).lines().collect(Collectors.toList());
            if (expected.isEmpty()) {
                continue;
            }

            String full = t.read(f.getFileName().toString(), null, null, ws.toString(), null).getContent();
            List<String> all = bodyLines(full);
            assertEquals(expected.size(), all.size(), full);
            for (int i = 0; i < expected.size(); i++) {
                assertEquals(String.format("%6d | %s", i + 1, expected.get(i)), all.get(i));
            }

            for (int k = 0; k < 4; k++) {
                int offset = 2 + r.nextInt(expected.size() + 1);
                int limit = 1 + r.nextInt(5);
                String page = t.read(f.getFileName().toString(), offset, limit, ws.toString(), null).getContent();
                if (offset > expected.size()) {
                    assertTrue(page.contains(String.valueOf(offset)), page);
                    assertTrue(bodyLines(page).isEmpty(), page);
                    continue;
                }
                assertEquals(header(full).replaceAll("Lines: \\d+ - \\d+", ""), header(page).replaceAll("Lines: \\d+ - \\d+", ""));
                List<String> got = bodyLines(page);
                assertEquals(all.subList(offset - 1, Math.min(all.size(), offset - 1 + limit)), got, page);
                if (d.lineBreaksAreSingleBytes()) {
                    checkedSkips++;
                }
            }
        }
        assertTrue(checkedSkips > 500, "byte-level skips checked: " + checkedSkips);
    }

    private static String header(String out) {
        return out.substring(0, out.indexOf('\n'));
    }

    @Test
    void lineStartOffsetMatchesReaderLineBreaks() {
        byte[] b = "a\r\nb\rc\n\nd".getBytes(StandardCharsets.UTF_8);
        assertEquals(0, TerminalSupport.lineStartOffset(b, 0));
        assertEquals(3, TerminalSupport.lineStartOffset(b, 1));
        assertEquals(5, TerminalSupport.lineStartOffset(b, 2));
        assertEquals(7, TerminalSupport.lineStartOffset(b, 3));
        assertEquals(8, TerminalSupport.lineStartOffset(b, 4));
        assertEquals(b.length, TerminalSupport.lineStartOffset(b, 5));
        assertEquals(b.length, TerminalSupport.lineStartOffset(b, 99));
        // 文件以 \r 结尾：\r 后没有 \n 也算一行结束
        assertEquals(2, TerminalSupport.lineStartOffset("a\r".getBytes(StandardCharsets.UTF_8), 1));
    }

    @Test
    void reusedAndFastDecodingEqualKeepingRawDecode() throws Exception {
        Random r = new Random(7);
        for (int round = 0; round < 2000; round++) {
            byte[] data = randomFile(r);
            TextEncodings.Decoded d = TextEncodings.detect(data, data.length, false, StandardCharsets.UTF_8, FileEncodingPolicy.defaults(), null);
            byte[] body = java.util.Arrays.copyOfRange(data, d.bomLength(), data.length);
            assertEquals(TerminalSupport.decodeKeepingRawBytes(body, d.charset()), d.text());
            assertEquals(new String(body, d.charset()), d.displayText());
            if (d.writesBackUnchanged()) {
                assertArrayEquals(data, d.encode(d.text()));
            }
        }
    }

    /**
     * 候选编码必须往返逐字节相同（分块比较不能漏掉不同的字节）。JDK 的 GBK 没有往返不一致的双字节序列，
     * Big5 有：A2CC 解码为「十」（U+5341），再编码是 A451。
     */
    @Test
    void candidateMustRoundTripByteForByte() throws Exception {
        Charset big5 = Charset.forName("Big5");
        FileEncodingPolicy policy = FileEncodingPolicy.builder().noCandidates().candidate(big5).build();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            sb.append("繁體中文測試說明，這是一段正常的文字。\n");
        }
        byte[] ok = sb.toString().getBytes(big5);
        TextEncodings.Decoded d = TextEncodings.detect(ok, ok.length, false, StandardCharsets.UTF_8, policy, null);
        assertEquals(TextEncodings.Source.DETECTED, d.source());
        assertEquals(sb.toString(), d.text());

        // 不一致的字节放在分块边界之后的位置
        byte[] bad = TerminalFileEncodingTest.cat(ok, new byte[]{(byte) 0xA2, (byte) 0xCC, '\n'});
        d = TextEncodings.detect(bad, bad.length, false, StandardCharsets.UTF_8, policy, null);
        assertEquals(TextEncodings.Source.UNKNOWN, d.source());
    }

    @Test
    void encodeKeepingRawBytesFastPathIsStrict() throws Exception {
        Random r = new Random(11);
        for (int round = 0; round < 2000; round++) {
            String s = randomText(r, r.nextInt(50), false);
            assertArrayEquals(s.getBytes(StandardCharsets.UTF_8), TerminalSupport.encodeKeepingRawBytes(s, StandardCharsets.UTF_8));
        }
        // 没有原字节映射字符时也是严格编码：孤立高代理、GBK 表示不了的字符照样报错，不会写成 '?'
        assertThrows(CharacterCodingException.class, () -> TerminalSupport.encodeKeepingRawBytes("a\uD83D", StandardCharsets.UTF_8));
        assertThrows(CharacterCodingException.class, () -> TerminalSupport.encodeKeepingRawBytes("a😀", GBK));
        assertArrayEquals("中文".getBytes(GBK), TerminalSupport.encodeKeepingRawBytes("中文", GBK));
    }

    @Test
    void grepPrefiltersByEveryEncodingBeforeDetecting() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("g"));
        String gbkText = "// 这是中文注释：用户服务的数据处理\nclass A {}\n";
        Files.write(ws.resolve("gbk.java"), gbkText.getBytes(GBK));
        Files.write(ws.resolve("utf8.java"), "// 用户服务\n".getBytes(StandardCharsets.UTF_8));
        Files.write(ws.resolve("utf16.txt"), TerminalFileEncodingTest.cat(new byte[]{(byte) 0xFF, (byte) 0xFE}, "用户服务\n".getBytes(StandardCharsets.UTF_16LE)));
        Files.write(ws.resolve("a.properties"), "name=caf\u00e9 user\n".getBytes(StandardCharsets.ISO_8859_1));
        TerminalTalent t = new TerminalTalent(new MountManager(ws.toString()));
        t.setFileEncodingPolicy(FileEncodingPolicy.builder().rule("*.properties", StandardCharsets.ISO_8859_1).build());

        String out = t.grep("用户服务", ".", null, ws.toString(), null).getContent();
        assertTrue(out.contains("gbk.java:1: // 这是中文注释：用户服务的数据处理"), out);
        assertTrue(out.contains("utf8.java:1: // 用户服务"), out);
        assertTrue(out.contains("utf16.txt:1: 用户服务"), out);

        // 只有按路径规则的编码才找得到（UTF-8 与 GBK 的字节里都没有）
        out = t.grep("café", ".", null, ws.toString(), null).getContent();
        assertTrue(out.contains("a.properties:1: name=café user"), out);

        // 路径规则优先于「合法 UTF-8」：字节恰好是合法 UTF-8 的文件，规则指定 GBK 就按 GBK 解码（grep 的 UTF-8 快路径不能抢先）
        byte[] both = "中文内容\n".getBytes(StandardCharsets.UTF_8);
        String gbkView = new String(both, GBK).trim();
        assertTrue(gbkView.indexOf('\uFFFD') < 0 && !gbkView.isEmpty(), gbkView);
        Files.createDirectories(ws.resolve("legacy"));
        Files.write(ws.resolve("legacy/r.txt"), both);
        TerminalTalent ruled = new TerminalTalent(new MountManager(ws.toString()));
        ruled.setFileEncodingPolicy(FileEncodingPolicy.builder().rule("legacy/**", GBK).build());
        out = ruled.grep(gbkView, "legacy", null, ws.toString(), null).getContent();
        assertTrue(out.contains("r.txt:1: " + gbkView), out);

        // GBK 表示不了的字符：只按 UTF-8 的字节预筛，GBK 文件里不可能有
        Files.write(ws.resolve("emoji.java"), "// 😀 ok\n".getBytes(StandardCharsets.UTF_8));
        out = t.grep("😀 ok", ".", null, ws.toString(), null).getContent();
        assertTrue(out.contains("emoji.java:1: // 😀 ok"), out);
        assertEquals(1, out.split("\n").length, out);

        // 纯 ASCII 模式在 GBK 文件里照样命中
        out = t.grep("class A", ".", null, ws.toString(), null).getContent();
        assertTrue(out.contains("gbk.java:2: class A {}"), out);
    }
}
