package org.noear.solon.ai.talents.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * PowerShell CLIXML 过滤：剥离 stderr 上被强制序列化的 CLIXML 块，并还原其中的文本流记录。
 *
 * <p>覆盖两个真实症状：
 * <ul>
 *   <li>输出里混进大段 {@code #< CLIXML ... </Objs>}（多为「正在准备首次使用模块」的 progress 记录）；</li>
 *   <li>CLIXML 用 ANSI 代码页写、stdout 用 UTF-8 写，合并到一条管道后字符集判定必然锁错一半 → 正文乱码。</li>
 * </ul>
 */
public class CliXmlFilterTest {
    private static final Charset GBK = Charset.forName("GBK");

    private static final String PROGRESS_XML =
            "<Objs Version=\"1.1.0.1\" xmlns=\"http://schemas.microsoft.com/powershell/2004/04\">"
                    + "<Obj S=\"progress\" RefId=\"0\"><TN RefId=\"0\"><T>System.Management.Automation.PSCustomObject</T>"
                    + "</TN><MS><I64 N=\"SourceId\">1</I64><PR N=\"Record\"><AV>正在准备首次使用模块。</AV>"
                    + "<AI>0</AI><Nil /><T>Completed</T></PR></MS></Obj></Objs>";

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            buf.write(p, 0, p.length);
        }
        return buf.toByteArray();
    }

    /** 全量过滤：progress 块整段消失，正文原样保留 */
    @Test
    public void stripsProgressBlock() {
        byte[] raw = concat(
                "#< CLIXML\r\n".getBytes(StandardCharsets.UTF_8),
                "line-1\r\nline-2\r\n".getBytes(StandardCharsets.UTF_8),
                ("\r\n" + PROGRESS_XML).getBytes(StandardCharsets.UTF_8));

        StringBuilder messages = new StringBuilder();
        String text = new String(CliXmlFilter.filterAll(raw, messages), StandardCharsets.UTF_8);

        assertEquals("line-1\r\nline-2\r\n\r\n", text);
        assertFalse(text.contains("CLIXML"));
        assertFalse(text.contains("<Objs"));
        assertEquals("", messages.toString()); // progress 是结构化记录，不还原为文本
    }

    /**
     * 关键回归：CLIXML 段是 GBK、正文是 UTF-8（PowerShell 实际行为）。
     * 过滤后剩下的字节必须是纯 UTF-8，从而让字符集判定不再锁错，正文中文不再变「鏈缃」。
     *
     * <p>注意：这里显式传入 GBK 作为 fallback 来断言判定结果，而不是走 {@code decodeAll}
     * 的环境默认——{@code legacyCharset} 仅在 Windows 上返回 ANSI 代码页，非 Windows 上为 null，
     * 「未过滤时会锁错字符集」这个前提在 macOS/Linux 上根本不成立，据此断言会平台性失败。
     */
    @Test
    public void mixedEncodingNoLongerBreaksDetection() {
        byte[] raw = concat(
                "#< CLIXML\r\n".getBytes(StandardCharsets.US_ASCII),
                "MAVEN_HOME 未设置\r\n".getBytes(StandardCharsets.UTF_8),
                ("\r\n" + PROGRESS_XML).getBytes(GBK)); // stderr 侧用 ANSI 代码页

        // 未过滤时：整条流不是合法 UTF-8，判定回退到 GBK，正文中文被解成乱码
        assertEquals(GBK, OutputDecoder.select(raw, 0, raw.length, StandardCharsets.UTF_8, GBK));
        assertFalse(new String(raw, GBK).contains("MAVEN_HOME 未设置"));

        // 过滤后：剩余字节是纯 UTF-8，判定不再回退，正文正确
        byte[] filtered = CliXmlFilter.filterAll(raw, null);
        assertEquals(StandardCharsets.UTF_8,
                OutputDecoder.select(filtered, 0, filtered.length, StandardCharsets.UTF_8, GBK));
        assertEquals("MAVEN_HOME 未设置\r\n\r\n", OutputDecoder.decodeAll(filtered, StandardCharsets.UTF_8));
    }

    /** error / warning 文本流记录要还原成纯文本（含 _x000D__x000A_ 与 XML 实体） */
    @Test
    public void restoresErrorAndWarningRecords() {
        String xml = "<Objs Version=\"1.1.0.1\" xmlns=\"http://schemas.microsoft.com/powershell/2004/04\">"
                + "<Obj S=\"progress\" RefId=\"0\"><AV>忽略我</AV></Obj>"
                + "<S S=\"Error\">出错了 a &lt; b_x000D__x000A_</S>"
                + "<S S=\"warning\">小心</S></Objs>";
        byte[] raw = concat(
                "#< CLIXML\r\n".getBytes(StandardCharsets.UTF_8),
                xml.getBytes(StandardCharsets.UTF_8));

        StringBuilder messages = new StringBuilder();
        byte[] filtered = CliXmlFilter.filterAll(raw, messages);

        assertEquals(0, filtered.length);
        assertEquals("出错了 a < b\r\n小心", messages.toString());
    }

    /** 流式：逐字节喂入也不能漏判标记（标记与结束符会跨读取分块） */
    @Test
    public void streamingAcrossChunkBoundaries() {
        byte[] raw = concat(
                "head\r\n".getBytes(StandardCharsets.UTF_8),
                "#< CLIXML\r\n".getBytes(StandardCharsets.UTF_8),
                PROGRESS_XML.getBytes(StandardCharsets.UTF_8),
                "tail 中文\r\n".getBytes(StandardCharsets.UTF_8));

        CliXmlFilter filter = new CliXmlFilter();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] one = new byte[1];
        for (byte b : raw) {
            one[0] = b;
            byte[] piece = filter.accept(one, 1);
            out.write(piece, 0, piece.length);
        }
        byte[] rest = filter.flush();
        out.write(rest, 0, rest.length);

        assertEquals("head\r\ntail 中文\r\n", new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    /** 普通输出不得被改动（不含标记时零影响） */
    @Test
    public void plainOutputUntouched() {
        byte[] raw = "BUILD SUCCESS 构建成功\r\n".getBytes(StandardCharsets.UTF_8);
        assertEquals("BUILD SUCCESS 构建成功\r\n",
                new String(CliXmlFilter.filterAll(raw, null), StandardCharsets.UTF_8));
    }

    /** 未闭合的 CLIXML（进程被强杀）：不得把后续输出一路吞掉，文本记录尽力还原 */
    @Test
    public void unterminatedBlockIsHarvestedOnFlush() {
        byte[] raw = concat(
                "before\r\n".getBytes(StandardCharsets.UTF_8),
                "#< CLIXML\r\n<Objs Version=\"1.1.0.1\"><S S=\"Error\">断了</S>".getBytes(StandardCharsets.UTF_8));

        CliXmlFilter filter = new CliXmlFilter();
        byte[] head = filter.accept(raw, raw.length);
        byte[] tail = filter.flush();

        assertEquals("before\r\n", new String(head, StandardCharsets.UTF_8));
        assertEquals(0, tail.length);
        assertTrue(filter.drainMessages().contains("断了"));
    }

    /**
     * 误命中保护（未闭合）：{@code <Objs Version="} 也可能出现在命令自己的输出里。
     * 若校验窗口内不含 PowerShell 序列化特征，必须立刻原样回吐——不能等到 EOF，更不能静默丢弃。
     */
    @Test
    public void falsePositiveIsSpilledWithinValidateWindow() {
        StringBuilder body = new StringBuilder("<Objs Version=\"9\">");
        while (body.length() < CliXmlFilter.VALIDATE_WINDOW * 2) {
            body.append("<row>业务数据</row>");
        }
        byte[] first = concat(
                "#< CLIXML\r\n".getBytes(StandardCharsets.UTF_8),
                body.toString().getBytes(StandardCharsets.UTF_8));
        byte[] second = "after\r\n".getBytes(StandardCharsets.UTF_8);

        CliXmlFilter filter = new CliXmlFilter();
        String head = new String(filter.accept(first, first.length), StandardCharsets.UTF_8);
        // 关键：还没到 EOF 就已经吐出来了，说明吞吐窗口被限制在 VALIDATE_WINDOW 内
        assertTrue(head.contains("<row>业务数据</row>"), "误命中的正文被吞掉了: " + head);

        String rest = new String(filter.accept(second, second.length), StandardCharsets.UTF_8)
                + new String(filter.flush(), StandardCharsets.UTF_8);
        assertEquals(body.toString() + "after\r\n", head + rest);
    }

    /** 误命中保护（已闭合）：闭合了但不具备序列化特征的 XML 同样必须原样保留，含 {@code </Objs>} */
    @Test
    public void falsePositiveTerminatedBlockIsKept() {
        String body = "<Objs Version=\"9\"><row>业务数据</row></Objs>rest\r\n";
        byte[] raw = concat(
                "#< CLIXML\r\n".getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8));

        assertEquals(body, new String(CliXmlFilter.filterAll(raw, null), StandardCharsets.UTF_8));
    }

    /**
     * 超大真 CLIXML（progress 长期刷屏可达数百 KB）：必须仍被完整剥离，不能因为超过缓冲上限就
     * 退化成把 XML 当正文吐回去；跨越收割边界的 error 文本也不能丢。
     */
    @Test
    public void hugeCliXmlBlockIsStillStripped() {
        StringBuilder xml = new StringBuilder("<Objs Version=\"1.1.0.1\" ")
                .append("xmlns=\"http://schemas.microsoft.com/powershell/2004/04\">")
                .append("<S S=\"Error\">开头出错</S>");
        while (xml.length() < 400 * 1024) {
            xml.append("<Obj S=\"progress\" RefId=\"0\"><MS><AV>正在准备首次使用模块。</AV></MS></Obj>");
        }
        xml.append("<S S=\"Error\">结尾出错</S></Objs>");

        byte[] raw = concat(
                "#< CLIXML\r\n".getBytes(StandardCharsets.UTF_8),
                xml.toString().getBytes(StandardCharsets.UTF_8),
                "tail\r\n".getBytes(StandardCharsets.UTF_8));

        // 按真实读取分块喂入，才会走到缓冲复位（compact）路径
        CliXmlFilter filter = new CliXmlFilter();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int off = 0; off < raw.length; off += 4096) {
            byte[] chunk = new byte[Math.min(4096, raw.length - off)];
            System.arraycopy(raw, off, chunk, 0, chunk.length);
            byte[] piece = filter.accept(chunk, chunk.length);
            out.write(piece, 0, piece.length);
        }
        byte[] rest = filter.flush();
        out.write(rest, 0, rest.length);

        assertEquals("tail\r\n", new String(out.toByteArray(), StandardCharsets.UTF_8));
        String messages = filter.drainMessages();
        assertTrue(messages.contains("开头出错"), "收割前的 error 记录丢失: " + messages);
        assertTrue(messages.contains("结尾出错"), "收割后的 error 记录丢失: " + messages);
    }
}
