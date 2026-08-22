/*
 * Copyright 2017-2025 noear.org and authors
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
package org.noear.solon.ai.talents.cli;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * PowerShell CLIXML 过滤器：在字节层剥离 {@code powershell.exe} 写入 stderr 的 CLIXML 序列化块。
 *
 * <p><b>问题</b>：{@code powershell.exe} 的 stderr 一旦被重定向到管道（而非控制台），引擎就会把
 * 所有非 stdout 流（error / warning / verbose / debug / progress）序列化成 CLIXML 写出：
 * <pre>
 * #&lt; CLIXML
 * &lt;Objs Version="1.1.0.1" xmlns="..."&gt;&lt;Obj S="progress"&gt;...&lt;/Obj&gt;&lt;S S="Error"&gt;...&lt;/S&gt;&lt;/Objs&gt;
 * </pre>
 * 该行为无法用参数或首选项关闭（实测 {@code $ProgressPreference='SilentlyContinue'}、
 * {@code $global:} 作用域、{@code -OutputFormat Text}、预加载模块均无效——模块自动加载的
 * progress 记录始终会被序列化），只能由调用方过滤。</p>
 *
 * <p><b>两个后果</b>（都必须在字节层解决）：
 * <ul>
 *   <li><b>噪声</b>：模型每次执行命令都会收到一大段无意义的 XML（仅「正在准备首次使用模块」之类
 *       progress 记录），挤占上下文。</li>
 *   <li><b>乱码</b>：CLIXML 由引擎启动期就建好的 stderr writer 写出，用的是原始 ANSI/OEM 代码页；
 *       而 stdout 已被启动前置脚本切成 UTF-8。两者经 {@code redirectErrorStream} 合并到同一管道后，
 *       一条字节流里同时存在两种编码，{@link OutputDecoder} 只能锁定其中一种，另一半必然乱码
 *       （典型表现：正文中文变「鏈缃」而 XML 里的中文反倒正常）。因此必须在<b>解码之前</b>把
 *       CLIXML 段摘出来单独解码，剩下的主流才是编码统一的。</li>
 * </ul>
 *
 * <p><b>不丢信息</b>：CLIXML 里的 {@code <S S="Error">} / {@code <S S="warning">} 等文本流记录会被
 * 还原成纯文本（去 XML 转义、还原 {@code _x000D_} 之类的字符转义），由调用方追加到输出末尾；
 * 只有 {@code <Obj>}（progress 等结构化记录）被丢弃。</p>
 *
 * <p><b>流式安全</b>：{@link #accept(byte[], int)} 可被反复调用（仅限单线程），标记与结束符跨读取
 * 分块也不会漏判——尾部若是标记的前缀则暂存到下次。全部读完后调用 {@link #flush()}。</p>
 *
 * <p><b>误命中不吞数据</b>：{@code <Objs Version="} 这个起始标记理论上也可能出现在命令自己的输出里。
 * 为此载荷开头 {@value #VALIDATE_WINDOW} 字节内必须出现 PowerShell 序列化特征（固定 xmlns 或
 * {@code <Obj }/{@code <S S="} 记录标签），否则判定为误命中并把已缓冲字节原样回吐；EOF 时仍未闭合
 * 且不具备特征的载荷同样原样回吐。因此任何非 CLIXML 内容最多被延迟 {@value #VALIDATE_WINDOW} 字节，
 * 不会丢失。</p>
 *
 * @author noear
 * @since 4.0.4
 */
final class CliXmlFilter {
    /** CLIXML 块头标记（powershell.exe 在 stderr 首次写出时固定输出该行） */
    private static final byte[] MARK = "#< CLIXML".getBytes(StandardCharsets.US_ASCII);
    /** CLIXML 载荷起始（固定的 Objs 根元素；只在见过头标记后才据此判定，避免误伤正常输出） */
    private static final byte[] START = "<Objs Version=\"".getBytes(StandardCharsets.US_ASCII);
    /** CLIXML 载荷结束 */
    private static final byte[] END = "</Objs>".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EMPTY = new byte[0];

    /** PowerShell 序列化命名空间：CLIXML 的最强特征 */
    private static final byte[] NS =
            "xmlns=\"http://schemas.microsoft.com/powershell/2004/04\"".getBytes(StandardCharsets.US_ASCII);
    /** 记录标签：命名空间万一变化时的兜底特征 */
    private static final byte[] OBJ_TAG = "<Obj ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] STR_TAG = "<S S=\"".getBytes(StandardCharsets.US_ASCII);
    /** 文本记录结束标签（收割边界） */
    private static final byte[] STR_END = "</S>".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OBJ_END = "</Obj>".getBytes(StandardCharsets.US_ASCII);

    /**
     * 载荷特征校验窗口：真实 CLIXML 的 xmlns 距载荷起点不足 70 字节，128 字节足够留出版本号变长的余量。
     * 窗口内未命中特征即判定误命中并原样回吐，从而把误命中的最大吞吐量限制在该窗口内。
     */
    static final int VALIDATE_WINDOW = 128;

    /**
     * XML 段缓冲上限：纯内存保护。已确认的 CLIXML 超限时按记录边界收割并复位缓冲（见
     * {@link #compact()}），不会退化成把真 CLIXML 当正文吐回去。
     */
    private static final int MAX_XML_BUFFER = 256 * 1024;

    private final StringBuilder messages = new StringBuilder();
    /** 未处理完的尾部字节（标记的可能前缀）；xml 非空时表示属于 XML 段的待定尾部 */
    private byte[] pending = EMPTY;
    /** 非 null 表示正处于 CLIXML 载荷内 */
    private ByteArrayOutputStream xml;
    /** 当前载荷是否已确认为 CLIXML（命中特征）：未确认前只允许缓冲 VALIDATE_WINDOW 字节 */
    private boolean xmlConfirmed;
    /** 是否已出现过 {@code #< CLIXML} 头：只有见过它才认 Objs 载荷 */
    private boolean sawMark;
    /** 头标记后待吞掉的换行字节数（{@code \r\n} 可能跨分块） */
    private int eolSkip;

    /**
     * 该字节流是否需要过滤（仅 Windows 下的 PowerShell 会产生 CLIXML）。
     */
    static boolean isNeeded() {
        return EnvironmentResolver.isWindows();
    }

    /**
     * 一次性过滤完整字节输出。
     *
     * @return 剥离 CLIXML 后的字节
     */
    static byte[] filterAll(byte[] raw, StringBuilder messagesOut) {
        if (raw == null || raw.length == 0) {
            return raw;
        }
        CliXmlFilter filter = new CliXmlFilter();
        byte[] head = filter.accept(raw, raw.length);
        byte[] tail = filter.flush();
        if (messagesOut != null) {
            messagesOut.append(filter.drainMessages());
        }
        if (tail.length == 0) {
            return head;
        }
        byte[] all = new byte[head.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }

    /**
     * 过滤一段输出。非线程安全：只能由单一读取线程调用。
     *
     * <p><b>为什么头标记与载荷要分开处理</b>：{@code #< CLIXML} 是 PowerShell 首次写 stderr 时立刻
     * 输出的，而 {@code <Objs>} 载荷在进程退出前才写出。两者之间夹的是整段正常 stdout——若把
     * 「头标记到 {@code </Objs>}」整体当成 XML 段丢弃，会把命令的全部输出一起吞掉。</p>
     *
     * @return 可安全下发的字节（不含 CLIXML 内容，也不含可能被切断的标记前缀）
     */
    byte[] accept(byte[] buf, int len) {
        if (buf == null || len <= 0) {
            return EMPTY;
        }

        byte[] data;
        int dataLen;
        if (pending.length == 0) {
            data = buf;
            dataLen = len;
        } else {
            data = new byte[pending.length + len];
            System.arraycopy(pending, 0, data, 0, pending.length);
            System.arraycopy(buf, 0, data, pending.length, len);
            dataLen = data.length;
            pending = EMPTY;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(dataLen);
        int i = 0;
        while (true) {
            // 头标记后的换行（可能跨分块）
            while (eolSkip > 0 && i < dataLen && (data[i] == '\r' || data[i] == '\n')) {
                eolSkip--;
                i++;
            }
            if (i >= dataLen) {
                break;
            }

            if (xml == null) {
                int markIdx = indexOf(data, dataLen, MARK, i);
                int startIdx = sawMark ? indexOf(data, dataLen, START, i) : -1;
                boolean markFirst = markIdx >= 0 && (startIdx < 0 || markIdx < startIdx);

                if (markFirst) {
                    out.write(data, i, markIdx - i);
                    i = markIdx + MARK.length;
                    sawMark = true;
                    eolSkip = 2; // 吞掉紧随其后的 \r\n
                    continue;
                }
                if (startIdx >= 0) {
                    out.write(data, i, startIdx - i);
                    i = startIdx + START.length;
                    xml = new ByteArrayOutputStream();
                    xmlConfirmed = false;
                    continue;
                }
                // 尾部可能是某个标记被切断的前缀：暂存，等下一段拼接后再判定
                int hold = Math.max(
                        suffixPrefixLen(data, i, dataLen, MARK),
                        sawMark ? suffixPrefixLen(data, i, dataLen, START) : 0);
                out.write(data, i, dataLen - i - hold);
                pending = copy(data, dataLen - hold, dataLen);
                break;
            }

            int endIdx = indexOf(data, dataLen, END, i);
            if (endIdx >= 0) {
                xml.write(data, i, endIdx - i);
                byte[] payload = xml.toByteArray();
                xml = null;
                if (xmlConfirmed || looksLikeCliXml(payload, payload.length)) {
                    i = endIdx + END.length; // 结束符一并丢弃
                    harvest(payload);
                } else {
                    // 闭合了但不具备任何序列化特征 → 判定误命中，原样回吐（含被吃掉的起始标记），
                    // 结束符交回正常路径输出
                    out.write(START, 0, START.length);
                    out.write(payload, 0, payload.length);
                    i = endIdx;
                }
                xmlConfirmed = false;
                continue;
            }
            int hold = suffixPrefixLen(data, i, dataLen, END);
            xml.write(data, i, dataLen - i - hold);
            pending = copy(data, dataLen - hold, dataLen);
            if (!xmlConfirmed) {
                byte[] head = xml.toByteArray();
                if (looksLikeCliXml(head, head.length)) {
                    xmlConfirmed = true;
                } else if (head.length >= VALIDATE_WINDOW) {
                    // 校验窗口内没有任何 PowerShell 序列化特征：判定误命中，立即原样回吐。
                    // 这条路径把「误把正文当 XML 吞掉」的最大延迟限制在一个窗口内。
                    out.write(START, 0, START.length);
                    out.write(head, 0, head.length);
                    xml = null;
                    break;
                }
            }
            if (xml != null && xml.size() > MAX_XML_BUFFER) {
                compact();
            }
            break;
        }
        return out.toByteArray();
    }

    /**
     * 已确认的 CLIXML 载荷过大时按记录边界收割并复位缓冲：内存有界，文本记录不丢。
     *
     * <p>切点取最后一个 {@code </S>} / {@code </Obj>} 之后，既保证不会把一条记录劈成两半，也保证切点
     * 落在 ASCII 上，不会切断多字节字符。</p>
     */
    private void compact() {
        byte[] payload = xml.toByteArray();
        int cut = Math.max(lastEndOf(payload, STR_END), lastEndOf(payload, OBJ_END));
        xml.reset();
        if (cut <= 0) {
            // 整段都没有完整记录（异常巨大的单条记录）：直接收割，避免内存无界
            harvest(payload);
            return;
        }
        harvest(copy(payload, 0, cut));
        xml.write(payload, cut, payload.length - cut);
    }

    /**
     * 是否具备 PowerShell 序列化特征：固定 xmlns，或 {@code <Obj }/{@code <S S="} 记录标签。
     */
    private static boolean looksLikeCliXml(byte[] data, int len) {
        return indexOf(data, len, NS, 0) >= 0
                || indexOf(data, len, OBJ_TAG, 0) >= 0
                || indexOf(data, len, STR_TAG, 0) >= 0;
    }

    /**
     * 输出结束：交出仍在暂存的尾部字节。
     *
     * <p>未闭合的 CLIXML 段（进程被强杀等场景）里的文本记录尽力还原、结构化残片丢弃；但若该段压根不
     * 具备序列化特征，则视为误命中并原样回吐——宁可多输出一段 XML，也不能静默丢掉用户数据。</p>
     */
    byte[] flush() {
        byte[] tail = EMPTY;
        if (xml != null) {
            if (pending.length > 0) {
                xml.write(pending, 0, pending.length);
                pending = EMPTY;
            }
            byte[] payload = xml.toByteArray();
            xml = null;
            if (xmlConfirmed || looksLikeCliXml(payload, payload.length)) {
                harvest(payload);
            } else {
                tail = concat(START, payload);
            }
        } else {
            tail = pending;
        }
        pending = EMPTY;
        xmlConfirmed = false;
        return tail;
    }

    /**
     * 取出并清空已还原的文本流记录（error / warning / verbose / debug 等）。
     */
    String drainMessages() {
        String text = messages.toString();
        messages.setLength(0);
        return text;
    }

    /**
     * 从 CLIXML 段中还原文本流记录：只取 {@code <S S="...">...</S>}，丢弃 {@code <Obj>} 结构化记录。
     *
     * <p>该段字节可能与主流不同编码，故单独按 UTF-8 优先 + ANSI 兜底解码。</p>
     */
    private void harvest(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return;
        }
        String text = OutputDecoder.decodeAll(payload, StandardCharsets.UTF_8);
        int from = 0;
        while (true) {
            int start = text.indexOf("<S ", from);
            if (start < 0) {
                break;
            }
            int open = text.indexOf('>', start);
            if (open < 0) {
                break;
            }
            int close = text.indexOf("</S>", open);
            if (close < 0) {
                break;
            }
            messages.append(unescape(text.substring(open + 1, close)));
            from = close + 4;
        }
    }

    /**
     * 还原 CLIXML 的字符转义：{@code _xHHHH_} 码点转义 + 常见 XML 实体。
     */
    static String unescape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder buf = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); ) {
            char c = value.charAt(i);
            if (c == '_' && i + 6 < value.length() && (value.charAt(i + 1) == 'x' || value.charAt(i + 1) == 'X')
                    && value.charAt(i + 6) == '_') {
                try {
                    buf.append((char) Integer.parseInt(value.substring(i + 2, i + 6), 16));
                    i += 7;
                    continue;
                } catch (NumberFormatException ignored) {
                    // 不是合法码点转义，按普通字符处理
                }
            }
            if (c == '&') {
                int semi = value.indexOf(';', i);
                if (semi > i && semi - i <= 10) {
                    String entity = value.substring(i + 1, semi);
                    String plain = entity(entity);
                    if (plain != null) {
                        buf.append(plain);
                        i = semi + 1;
                        continue;
                    }
                }
            }
            buf.append(c);
            i++;
        }
        return buf.toString();
    }

    private static String entity(String name) {
        if ("lt".equals(name)) return "<";
        if ("gt".equals(name)) return ">";
        if ("amp".equals(name)) return "&";
        if ("quot".equals(name)) return "\"";
        if ("apos".equals(name)) return "'";
        if (name.length() > 1 && name.charAt(0) == '#') {
            try {
                int cp = (name.charAt(1) == 'x' || name.charAt(1) == 'X')
                        ? Integer.parseInt(name.substring(2), 16)
                        : Integer.parseInt(name.substring(1));
                return new String(Character.toChars(cp));
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 在 {@code [from, len)} 中查找 {@code needle} 的首个完整出现位置。
     */
    private static int indexOf(byte[] data, int len, byte[] needle, int from) {
        int limit = len - needle.length;
        outer:
        for (int i = Math.max(0, from); i <= limit; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * {@code [from, len)} 的尾部与 {@code needle} 前缀的最长重叠长度（用于跨分块的标记保留）。
     */
    private static int suffixPrefixLen(byte[] data, int from, int len, byte[] needle) {
        int max = Math.min(needle.length - 1, len - from);
        for (int n = max; n > 0; n--) {
            boolean match = true;
            for (int j = 0; j < n; j++) {
                if (data[len - n + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return n;
            }
        }
        return 0;
    }

    /**
     * {@code needle} 最后一次出现的结尾位置（即最后一个匹配之后的下标）；未命中返回 -1。
     */
    private static int lastEndOf(byte[] data, byte[] needle) {
        outer:
        for (int i = data.length - needle.length; i >= 0; i--) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i + needle.length;
        }
        return -1;
    }

    private static byte[] concat(byte[] head, byte[] tail) {
        if (tail.length == 0) {
            return head;
        }
        byte[] all = new byte[head.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }

    private static byte[] copy(byte[] data, int from, int to) {
        if (to <= from) {
            return EMPTY;
        }
        byte[] rest = new byte[to - from];
        System.arraycopy(data, from, rest, 0, rest.length);
        return rest;
    }
}
