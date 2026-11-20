/*
 * Copyright 2017-2026 noear.org and authors
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

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * 文件编码识别（read / edit / write / grep 共用，保证同一份内容四个工具判断一致）。
 *
 * <p>判断顺序（命中即停），详见 docs/file-encoding-plan.md：</p>
 * <ol>
 *   <li>BOM：UTF-8 / UTF-16LE / UTF-16BE；</li>
 *   <li>路径规则（{@link FileEncodingPolicy#ruleFor}）：用户明确指定，优先于自动识别；</li>
 *   <li>整份严格合法的主编码（默认 UTF-8，末尾不容许残缺；抽样时容许）；</li>
 *   <li>大体是 UTF-8、只夹少量坏字节：仍按 UTF-8，坏字节原样保留（{@link Source#MIXED}）；</li>
 *   <li>候选编码：解码无错 + 往返逐字节相同 + 内容像正常文本；</li>
 *   <li>都不满足：按主编码显示，坏字节原样保留（{@link Source#UNKNOWN}）。</li>
 * </ol>
 *
 * @author noear
 * @since 4.2
 */
final class TextEncodings {
    private TextEncodings() {
    }

    /** 编码的来源（决定可信程度与写入规则）。 */
    enum Source {
        /** 整份严格合法的主编码（默认 UTF-8，含纯 ASCII） */
        PRIMARY,
        /** 由 BOM 确定 */
        BOM,
        /** 路径规则指定 */
        RULE,
        /** 候选编码自动识别 */
        DETECTED,
        /** 大体是 UTF-8，夹少量坏字节 */
        MIXED,
        /** 无法确定 */
        UNKNOWN
    }

    /** 超大文件只检测开头这么多字节。 */
    static final int SAMPLE_BYTES = 1024 * 1024;

    /** 大体是 UTF-8 的判定：合法多字节字符数至少是坏字节段数的这么多倍。 */
    static final int MIXED_RATIO = 4;

    /** GB 系候选的内容检查：双字节字符里一级汉字与常用符号的最低占比（%）。 */
    static final int GB_GOOD_PERCENT = 90;
    /** GB 系候选的内容检查：汉字达到这个数量后才检查常用字覆盖率。 */
    static final int GB_COMMON_MIN_HANZI = 10;
    /** GB 系候选的内容检查：常用字覆盖率下限（%）。 */
    static final int GB_COMMON_PERCENT = 50;
    /**
     * GB 系候选的内容检查：汉字达到 10 个时，首字节在 C9–D7 的汉字至少占这么多（%）。
     * GB2312 一级汉字按拼音排列，是/中/这/在/我/有/一/说/上等高频字都在 C9–D7；
     * EUC-KR 的韩文只占 B0–C8，按 GBK 解码时这一项为 0。
     */
    static final int GB_HIGH_ROW_PERCENT = 10;
    /** 其他候选的内容检查：非 ASCII 字符里像正常文字的最低占比（%）。 */
    static final int GENERIC_GOOD_PERCENT = 90;

    private static final Charset UTF_16LE = StandardCharsets.UTF_16LE;
    private static final Charset UTF_16BE = StandardCharsets.UTF_16BE;

    static Charset charsetOrNull(String name) {
        try {
            return name == null ? null : Charset.forName(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** ASCII 字符按单字节原样编码（UTF-16/32 不是）。 */
    static boolean isAsciiCompatible(Charset cs) {
        if (cs == null || !cs.canEncode()) {
            return false;
        }
        String probe = "\t\n\r !\"#$%&'()*+,-./09:;<=>?@AZ[\\]^_`az{|}~";
        try {
            CharsetEncoder enc = cs.newEncoder();
            ByteBuffer bb = enc.encode(CharBuffer.wrap(probe));
            byte[] out = new byte[bb.remaining()];
            bb.get(out);
            return Arrays.equals(out, probe.getBytes(StandardCharsets.US_ASCII));
        } catch (CharacterCodingException | RuntimeException e) {
            return false;
        }
    }

    static boolean isAscii(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 0x80) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- BOM

    /** BOM 对应的编码；没有 BOM 返回 null。UTF-32 不支持（FF FE 00 00 不算 UTF-16 BOM）。 */
    static Charset bomCharset(byte[] b, int len) {
        if (len >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (len >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            if (len >= 4 && b[2] == 0 && b[3] == 0) {
                return null; // UTF-32LE
            }
            return UTF_16LE;
        }
        if (len >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return UTF_16BE;
        }
        return null;
    }

    /** 是否以 UTF-16 BOM 开头（这类文件含大量 NUL，不能按二进制探测）。 */
    static boolean hasUtf16Bom(byte[] b, int len) {
        Charset cs = bomCharset(b, len);
        return cs != null && cs != StandardCharsets.UTF_8;
    }

    private static int bomLength(Charset cs) {
        return cs == StandardCharsets.UTF_8 ? 3 : 2;
    }

    // ---------------------------------------------------------------- UTF-8 扫描

    /** UTF-8 扫描结果。 */
    static final class Utf8Scan {
        /** 整段严格合法 */
        boolean valid = true;
        /** 合法多字节字符数 */
        int multiByte;
        /** 坏字节段数（连续坏字节算一段） */
        int badSegments;
        /** 坏字节数（与保字节解码得到的原字节数一致） */
        int badBytes;
    }

    /**
     * 按 RFC 3629 扫描 UTF-8（与 JDK 解码器同口径：拒绝超长编码、代理区、超过 U+10FFFF）。
     *
     * @param tolerateTail 末尾残缺的多字节序列不算坏字节（抽样检测时用）
     */
    static Utf8Scan scanUtf8(byte[] b, int off, int end, boolean tolerateTail) {
        Utf8Scan s = new Utf8Scan();
        boolean inBad = false;
        int i = off;
        while (i < end) {
            if (b[i] >= 0) {
                // ASCII 连续段（源码文件的绝大部分）：只判断符号位
                do {
                    i++;
                } while (i < end && b[i] >= 0);
                inBad = false;
                continue;
            }
            int c = b[i] & 0xFF;
            int need;
            int lo = 0x80;
            int hi = 0xBF;
            if (c >= 0xC2 && c <= 0xDF) {
                need = 1;
            } else if (c >= 0xE0 && c <= 0xEF) {
                need = 2;
                if (c == 0xE0) {
                    lo = 0xA0;
                } else if (c == 0xED) {
                    hi = 0x9F;
                }
            } else if (c >= 0xF0 && c <= 0xF4) {
                need = 3;
                if (c == 0xF0) {
                    lo = 0x90;
                } else if (c == 0xF4) {
                    hi = 0x8F;
                }
            } else {
                need = -1;
            }
            int ok = 0;
            if (need > 0) {
                // 第二个字节有专门的范围，其余续字节都是 80..BF
                for (int k = 1; k <= need && i + k < end; k++) {
                    int t = b[i + k] & 0xFF;
                    if (k == 1 ? (t < lo || t > hi) : (t < 0x80 || t > 0xBF)) {
                        break;
                    }
                    ok++;
                }
                if (ok == need) {
                    s.multiByte++;
                    i += need + 1;
                    inBad = false;
                    continue;
                }
                if (tolerateTail && i + 1 + ok == end) {
                    break; // 抽样边界切断的字符
                }
            }
            // 首字节与已匹配的续字节都算坏字节（与 JDK 解码器的出错长度一致）
            int bad = need > 0 ? 1 + ok : 1;
            s.valid = false;
            s.badBytes += bad;
            if (!inBad) {
                s.badSegments++;
                inBad = true;
            }
            i += bad;
        }
        return s;
    }

    // ---------------------------------------------------------------- 识别

    /**
     * 识别编码。
     *
     * @param bytes   文件内容（抽样时为开头部分）
     * @param length  有效长度
     * @param sampled 是否只是开头的抽样（超大文件）
     * @param primary 主编码（{@code TerminalTalent.fileCharset}，默认 UTF-8）
     * @param policy  候选编码与路径规则；null 视为不自动识别、无规则
     * @param path    用于匹配路径规则的相对路径；null 不匹配
     */
    static Decoded detect(byte[] bytes, int length, boolean sampled, Charset primary, FileEncodingPolicy policy, String path) {
        Charset bom = bomCharset(bytes, length);
        if (bom != null) {
            int start = bomLength(bom);
            return new Decoded(bytes, start, length, bom, Source.BOM, sampled,
                    countRaw(bytes, start, length, bom, sampled));
        }

        Charset rule = policy == null ? null : policy.ruleFor(path);
        if (rule != null) {
            return new Decoded(bytes, 0, length, rule, Source.RULE, sampled, countRaw(bytes, 0, length, rule, sampled));
        }

        // 抽样时在最后一个换行处截断，避免把切断的多字节字符当成坏字节
        int end = length;
        if (sampled) {
            for (int i = length - 1; i > 0; i--) {
                if (bytes[i] == '\n') {
                    end = i + 1;
                    break;
                }
            }
        }

        boolean utf8 = StandardCharsets.UTF_8.equals(primary);
        Utf8Scan scan = null;
        int primaryRaw;
        if (utf8) {
            scan = scanUtf8(bytes, 0, end, sampled);
            if (scan.valid) {
                return new Decoded(bytes, 0, length, primary, Source.PRIMARY, sampled, 0);
            }
            if (scan.multiByte > 0 && scan.multiByte >= MIXED_RATIO * scan.badSegments) {
                return new Decoded(bytes, 0, length, primary, Source.MIXED, sampled, scan.badBytes);
            }
            primaryRaw = scan.badBytes;
        } else {
            primaryRaw = countRaw(bytes, 0, end, primary, sampled);
            if (primaryRaw == 0 && roundTrips(bytes, 0, end, primary)) {
                return new Decoded(bytes, 0, length, primary, Source.PRIMARY, sampled, 0);
            }
        }

        if (policy != null) {
            for (Charset candidate : policy.getCandidates()) {
                if (candidate.equals(primary)) {
                    continue;
                }
                CharBuffer decoded = decodeCandidate(bytes, 0, end, candidate);
                if (decoded != null) {
                    // 整份识别时保留解码结果：edit / grep 直接复用，不必再解码一遍
                    return new Decoded(bytes, 0, length, candidate, Source.DETECTED, sampled, 0, sampled ? null : decoded);
                }
            }
        }

        return new Decoded(bytes, 0, length, primary, Source.UNKNOWN, sampled, primaryRaw);
    }

    /** 按保字节解码统计解不了的字节数。 */
    private static int countRaw(byte[] bytes, int off, int end, Charset cs, boolean sampled) {
        if (StandardCharsets.UTF_8.equals(cs)) {
            return scanUtf8(bytes, off, end, sampled).badBytes;
        }
        String text = TerminalSupport.decodeKeepingRawBytes(Arrays.copyOfRange(bytes, off, end), cs);
        return TerminalSupport.countRawBytes(text);
    }

    private static boolean roundTrips(byte[] bytes, int off, int end, Charset cs) {
        try {
            CharBuffer chars = strictDecoder(cs).decode(ByteBuffer.wrap(bytes, off, end - off));
            return sameBytes(strictEncoder(cs).encode(chars), bytes, off, end);
        } catch (CharacterCodingException | RuntimeException e) {
            return false;
        }
    }

    private static boolean sameBytes(ByteBuffer bb, byte[] bytes, int off, int end) {
        if (bb.remaining() != end - off) {
            return false;
        }
        for (int i = off; i < end; i++) {
            if (bb.get() != bytes[i]) {
                return false;
            }
        }
        return true;
    }

    /** 整份严格解码；有任何解不了的字节返回 null。 */
    static CharBuffer decodeStrictOrNull(byte[] bytes, Charset cs) {
        try {
            return strictDecoder(cs).decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException | RuntimeException e) {
            return null;
        }
    }

    private static CharsetDecoder strictDecoder(Charset cs) {
        return cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    private static CharsetEncoder strictEncoder(Charset cs) {
        return cs.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    /** 候选编码三项检查：解码无错、往返逐字节相同、内容像正常文本。 */
    static boolean acceptCandidate(byte[] bytes, int off, int end, Charset cs) {
        return decodeCandidate(bytes, off, end, cs) != null;
    }

    /**
     * 候选编码三项检查，通过时返回严格解码的结果（不通过返回 null）。
     * GB 系的内容检查只看原字节、开销最小，放在最前面：不像中文的文件不必解码。
     */
    static CharBuffer decodeCandidate(byte[] bytes, int off, int end, Charset cs) {
        if (!cs.canEncode()) {
            return null;
        }
        boolean gb = isGbFamily(cs);
        if (gb && !gbLooksLikeChinese(bytes, off, end)) {
            return null;
        }
        CharBuffer chars;
        try {
            chars = strictDecoder(cs).decode(ByteBuffer.wrap(bytes, off, end - off));
            if (!encodesBackTo(chars, cs, bytes, off, end)) {
                return null;
            }
        } catch (CharacterCodingException | RuntimeException e) {
            return null;
        }
        if (!gb && !looksLikeText(chars, cs.newEncoder().maxBytesPerChar() <= 1.0f)) {
            return null;
        }
        return chars;
    }

    /** 往返检查：{@code chars} 严格编码后与原字节逐字节相同（分块编码、边编边比，不另分配整份输出）。 */
    private static boolean encodesBackTo(CharBuffer chars, Charset cs, byte[] bytes, int off, int end) {
        CharsetEncoder enc = strictEncoder(cs);
        CharBuffer in = chars.duplicate();
        ByteBuffer out = ByteBuffer.allocate(8192);
        int pos = off;
        CoderResult cr;
        do {
            cr = enc.encode(in, out, true);
            if (cr.isError() || (pos = compareDrain(out, bytes, pos, end)) < 0) {
                return false;
            }
        } while (cr.isOverflow());
        do {
            cr = enc.flush(out);
            if (cr.isError() || (pos = compareDrain(out, bytes, pos, end)) < 0) {
                return false;
            }
        } while (cr.isOverflow());
        return pos == end;
    }

    /** 把编码缓冲里的字节与原字节 {@code [pos, end)} 比较并清空缓冲；返回新位置，不同时返回 -1。 */
    private static int compareDrain(ByteBuffer out, byte[] bytes, int pos, int end) {
        out.flip();
        int n = out.remaining();
        if (pos + n > end) {
            return -1;
        }
        byte[] a = out.array();
        int base = out.arrayOffset() + out.position();
        for (int i = 0; i < n; i++) {
            if (a[base + i] != bytes[pos + i]) {
                return -1;
            }
        }
        out.clear();
        return pos + n;
    }

    static boolean isGbFamily(Charset cs) {
        String n = cs.name().toUpperCase(Locale.ROOT);
        return n.equals("GBK") || n.equals("GB18030") || n.equals("GB2312") || n.equals("X-MSWIN-936") || n.equals("X-EUC-CN");
    }

    /**
     * GB 系内容检查（在原字节上做）：
     * (a) 双字节字符里 GB2312 一级汉字（B0A1–D7F9）与常用符号行（A1–A3 标点/全角，A9 制表符）占比 ≥ 90%，
     * 日文假名所在的 A4/A5 行不算——挡住 Big5、Shift_JIS、EUC-JP；
     * (b) 汉字达到 10 个时，常用字覆盖率 ≥ 50%——挡住 EUC-KR（韩文按 GBK 解码全落在一级汉字区，但都是生僻组合）。
     */
    static boolean gbLooksLikeChinese(byte[] bytes, int off, int end) {
        int[] s = gbStats(bytes, off, end);
        int total = s[0];
        int good = s[1];
        int hanzi = s[2];
        int common = s[3];
        int high = s[4];
        if (total == 0 || good * 100L < total * (long) GB_GOOD_PERCENT) {
            return false;
        }
        return hanzi < GB_COMMON_MIN_HANZI
                || (common * 100L >= hanzi * (long) GB_COMMON_PERCENT && high * 100L >= hanzi * (long) GB_HIGH_ROW_PERCENT);
    }

    /**
     * GB 系内容统计：{双字节字符数, 一级汉字与常用符号数, 一级汉字数, 其中常用字数, 其中首字节在 C9–D7 的数}。
     */
    static int[] gbStats(byte[] bytes, int off, int end) {
        int total = 0;
        int good = 0;
        int hanzi = 0;
        int common = 0;
        int high = 0;
        int i = off;
        while (i < end) {
            int b = bytes[i] & 0xFF;
            if (b < 0x80) {
                i++;
                continue;
            }
            if (b == 0x80 || b == 0xFF || i + 1 >= end) {
                total++;
                i++;
                continue;
            }
            int t = bytes[i + 1] & 0xFF;
            if (t >= 0x30 && t <= 0x39) {
                // GB18030 四字节字符（扩展汉字、emoji 等）：只在配置了 GB18030 时才可能解码成功，不计入占比
                i += 4;
                continue;
            }
            total++;
            if (b >= 0xB0 && b <= 0xD7 && t >= 0xA1 && t <= 0xFE && !(b == 0xD7 && t > 0xF9)) {
                good++;
                hanzi++;
                if (COMMON_GB[(b - 0xA1) * 94 + (t - 0xA1)]) {
                    common++;
                }
                if (b >= 0xC9) {
                    high++;
                }
            } else if ((b <= 0xA3 || b == 0xA9) && b >= 0xA1 && t >= 0xA1 && t <= 0xFE) {
                good++;
            }
            i += 2;
        }
        return new int[]{total, good, hanzi, common, high};
    }

    /** 其他候选的内容检查：非 ASCII 字符里像正常文字（中日韩文字、假名、谚文、全角/常用符号；单字节编码看字母与符号）的占比。 */
    static boolean looksLikeText(CharBuffer chars, boolean singleByte) {
        int nonAscii = 0;
        int good = 0;
        for (int i = 0; i < chars.length(); ) {
            int cp = Character.codePointAt(chars, i);
            i += Character.charCount(cp);
            if (cp < 0x80) {
                continue;
            }
            nonAscii++;
            if (singleByte ? isLatinText(cp) : isCjkText(cp)) {
                good++;
            }
        }
        return nonAscii > 0 && good * 100L >= nonAscii * (long) GENERIC_GOOD_PERCENT;
    }

    private static boolean isLatinText(int cp) {
        if (cp < 0xA0 || cp == 0xFFFD || Character.isISOControl(cp)) {
            return false;
        }
        int type = Character.getType(cp);
        return type != Character.PRIVATE_USE && type != Character.UNASSIGNED && type != Character.SURROGATE;
    }

    private static boolean isCjkText(int cp) {
        return (cp >= 0x3000 && cp <= 0x30FF)      // CJK 标点、平假名、片假名
                || (cp >= 0x3400 && cp <= 0x4DBF)  // 扩展 A
                || (cp >= 0x4E00 && cp <= 0x9FFF)  // 基本汉字
                || (cp >= 0xAC00 && cp <= 0xD7AF)  // 谚文音节
                || (cp >= 0x1100 && cp <= 0x11FF) || (cp >= 0x3130 && cp <= 0x318F)
                || (cp >= 0xF900 && cp <= 0xFAFF)  // 兼容汉字
                || (cp >= 0xFF00 && cp <= 0xFFEF)  // 全角/半角
                || (cp >= 0x2000 && cp <= 0x206F)  // 常用标点
                || (cp >= 0x2100 && cp <= 0x22FF)  // 字母式符号、箭头、数学符号
                || (cp >= 0x2460 && cp <= 0x27BF)  // 带圈数字、制表符、几何图形
                || (cp >= 0x0391 && cp <= 0x03C9) || (cp >= 0x0401 && cp <= 0x0451)
                || cp == 0xA7 || cp == 0xA8 || cp == 0xB0 || cp == 0xB1 || cp == 0xB4 || cp == 0xB6
                || cp == 0xD7 || cp == 0xF7
                || (cp >= 0x20000 && cp <= 0x2FA1F);
    }

    // ---------------------------------------------------------------- 编码校验

    /**
     * 第一个无法按 {@code cs} 编码的字符：返回 {1 起的字符序号, 码点}；都能编码返回 null。
     * 孤立代理（含原字节映射字符）一律算无法编码。
     */
    static int[] firstUnencodable(String text, Charset cs) {
        CharsetEncoder enc = strictEncoder(cs);
        int index = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int n = Character.charCount(cp);
            index++;
            if (Character.isSurrogate(text.charAt(i)) && n == 1) {
                return new int[]{index, cp};
            }
            if (cp >= 0x80 && !enc.canEncode(text.substring(i, i + n))) {
                return new int[]{index, cp};
            }
            i += n;
        }
        return null;
    }

    /** 展示字符：可见字符 {@code 'x' (U+0078)}，孤立代理只给码位。 */
    static String describeChar(int cp) {
        String code = String.format("U+%04X", cp);
        if (cp >= 0xD800 && cp <= 0xDFFF) {
            return code;
        }
        return "'" + new String(Character.toChars(cp)) + "' (" + code + ")";
    }

    // ---------------------------------------------------------------- 结果

    /** 识别结果。 */
    static final class Decoded {
        private final byte[] bytes;
        private final int bodyStart;
        private final int end;
        private final Charset charset;
        private final Source source;
        private final boolean sampled;
        private final int rawBytes;
        /** 识别时已严格解码的正文（候选编码），没有为 null */
        private CharBuffer decoded;
        private String text;

        Decoded(byte[] bytes, int bodyStart, int end, Charset charset, Source source, boolean sampled, int rawBytes) {
            this(bytes, bodyStart, end, charset, source, sampled, rawBytes, null);
        }

        Decoded(byte[] bytes, int bodyStart, int end, Charset charset, Source source, boolean sampled, int rawBytes,
                CharBuffer decoded) {
            this.bytes = bytes;
            this.bodyStart = bodyStart;
            this.end = end;
            this.charset = charset;
            this.source = source;
            this.sampled = sampled;
            this.rawBytes = rawBytes;
            this.decoded = decoded;
        }

        Charset charset() {
            return charset;
        }

        Source source() {
            return source;
        }

        /** BOM 字节数（0 表示没有 BOM）。 */
        int bomLength() {
            return source == Source.BOM ? bodyStart : 0;
        }

        byte[] bom() {
            return Arrays.copyOf(bytes, bomLength());
        }

        /** 解不了、原样保留的字节数。 */
        int rawBytes() {
            return rawBytes;
        }

        boolean sampled() {
            return sampled;
        }

        /**
         * 能否在原字节上按 {@code \n}、{@code \r} 定位行（read 翻页时不必解码前面的行）。
         *
         * <p>UTF-8（含夹坏字节、无法确定时按 UTF-8 替换解码）：多字节字符与坏字节段都不含 ASCII 字节；
         * 识别出的 GB 系编码：严格合法，双字节字符的第二字节不小于 0x30。两种情况下 0x0A / 0x0D 字节就是换行字符。</p>
         */
        boolean lineBreaksAreSingleBytes() {
            return StandardCharsets.UTF_8.equals(charset) || (source == Source.DETECTED && isGbFamily(charset));
        }

        /** 新写入的内容能否含非 ASCII 字符（编码无法确定时不能，否则文件会混入另一种编码）。 */
        boolean allowsNonAscii() {
            return source != Source.UNKNOWN;
        }

        /** 是否就是普通的 UTF-8（无 BOM、无坏字节）：写入提示里不必说明保存编码。 */
        boolean isPlainUtf8() {
            return StandardCharsets.UTF_8.equals(charset) && bomLength() == 0 && rawBytes == 0;
        }

        /**
         * 正文（不含 BOM）的保字节解码：解不了的字节映射为 U+DC00+b，供 edit 编辑后原样写回。
         *
         * <p>没有解不了的字节时，保字节解码与普通解码结果相同：复用识别时的解码结果，或直接按字符集整体解码。</p>
         */
        String text() {
            if (text == null) {
                if (decoded != null) {
                    text = decoded.toString();
                    decoded = null;
                } else if (rawBytes == 0 && !sampled) {
                    text = new String(bytes, bodyStart, end - bodyStart, charset);
                } else {
                    text = TerminalSupport.decodeKeepingRawBytes(Arrays.copyOfRange(bytes, bodyStart, end), charset);
                }
            }
            return text;
        }

        /** 正文（不含 BOM）的展示解码：解不了的字节显示为 U+FFFD（与原来按替换模式读取一致）。 */
        String displayText() {
            if (rawBytes == 0 && !sampled) {
                // 没有解不了的字节：展示解码与保字节解码相同，共用一份
                return text();
            }
            return new String(bytes, bodyStart, end - bodyStart, charset);
        }

        /** 编辑后的正文能否原样写回（未编辑时编码结果与原字节逐字节相同）。 */
        boolean writesBackUnchanged() {
            if (sampled || (StandardCharsets.UTF_8.equals(charset) && rawBytes == 0)) {
                return true;
            }
            if (rawBytes == 0 && (source == Source.PRIMARY || source == Source.DETECTED)) {
                // 识别时已做过严格解码与逐字节往返检查
                return true;
            }
            try {
                byte[] encoded = encode(text());
                return encoded.length == end && sameBytes(ByteBuffer.wrap(encoded), bytes, 0, end);
            } catch (CharacterCodingException e) {
                return false;
            }
        }

        /** 按本编码写回：BOM + 正文（原字节映射字符还原为原字节，其余严格编码）。 */
        byte[] encode(String body) throws CharacterCodingException {
            byte[] encoded = TerminalSupport.encodeKeepingRawBytes(body, charset);
            int bom = bomLength();
            if (bom == 0) {
                return encoded;
            }
            byte[] out = Arrays.copyOf(bytes, bom + encoded.length);
            System.arraycopy(encoded, 0, out, bom, encoded.length);
            return out;
        }

        /** 编码名（写入提示用），如 {@code GBK}、{@code UTF-8 with BOM}。 */
        String name() {
            return bomLength() > 0 ? charset.name() + " with BOM" : charset.name();
        }

        /** read 首行的 Encoding 字段。 */
        String label() {
            StringBuilder sb = new StringBuilder();
            switch (source) {
                case BOM:
                    sb.append(charset.name()).append(" with BOM");
                    break;
                case RULE:
                    sb.append(charset.name()).append(" configured");
                    break;
                case DETECTED:
                    sb.append(charset.name()).append(" detected");
                    break;
                case UNKNOWN:
                    sb.append("unknown");
                    break;
                default:
                    sb.append(charset.name());
            }
            if (rawBytes > 0) {
                sb.append(", ").append(rawBytes).append(rawBytes == 1 ? " undecodable byte" : " undecodable bytes")
                        .append(" shown as U+FFFD");
            }
            if (sampled) {
                sb.append(", sampled first ").append(SAMPLE_BYTES / (1024 * 1024)).append(" MB");
            }
            return sb.toString();
        }
    }

    // ---------------------------------------------------------------- 常用字

    /**
     * 现代汉语常用字（按字频排在前列的约一千字），用于区分真正的中文与按 GBK 误解出的“乱码汉字”。
     * 只需大体准确：真实中文的覆盖率通常在七成以上，误解码的韩文等约在三成以下。
     */
    private static final String COMMON_HANZI =
            "的一是不了在人有我他这个们中来上大为和国地到以说时要就出会可也你对生能而子那得于着下自之年过发后作里用道行所然家种事成方多经么去法学如都同现当没动面起看定天分还进好小部其些主样理心她本前开但因只从想实日军者意无力它与长把机十民第公此已工使情明性知全三又关点正业外将两高间由问很最重并物手应战向头文体政美相见被利什二等产或新己制身果加西斯月话合回特代内信表化老给世位次度门任常先海通教儿原东声提立及比员解水名真论处走义各入几口认条平系气题活尔更别打女变四神总何电数安少报才结反受目太量再感建务做接必场件计管期市直德资命山金指克许统区保至队形社便空决治展马科司五基眼书非则听白却界达光放强即像难且权思王象完设式色路记南品住告类求据程北边死张该交规万取拉格望觉术领共确传师观清今切院让识候带导争运笑飞风步改收根干造言联持组每济车亲极林服快办议往元英士证近失转夫令准布始怎呢存未远叫台单影具罗字爱击流备兵连调深商算质团集百需价花党华城石级整府离况亚请技际约示复病息究线似官火断精满支视消越器容照须九增研写称企八功吗包片史委乎查轻易早曾除农找装广显吧阿李标谈吃图念六引历首医局突专费号尽另周较注语仅考落青随选列武红响虽推势参希古众构房半节土投某案黑维革划敌致陈律足态护七兴派孩验责营星够章音跟志底站严巴例防族供效续施留讲型料终答紧黄绝奇察母京段依批群项故按河米围江织害斗双境客纪采举杀攻父苏密低朝友诉止细愿千值仍男钱破网热助倒育属坐帝限船脸职速刻乐否刚威毛状率甚独球般普怕弹校苦创假久错承印晚兰试股拿脑预谁益阳若哪微尼继送急血惊伤素药适波夜省初喜卫源食险待述陆习置居劳财环排福纳欢雷警获模充负云停木游龙树疑层冷洲冲射略范竟句室异激汉村哈策演简卡罪判担州静退既衣您宗积余痛检差富灵协角占配征修皮挥胜降阶审沉坚善妈刘读啊超免压银买皇养伊怀执副乱抗犯追帮宣佛岁航优怪香著田铁控税左右份穿艺背阵草脚概恶块顿敢守酒岛托央户烈洋哥索胡款靠评版宝座释景顾弟登货互付伯慢欧换闻危忙核暗姐介坏讨丽良序升监临亮露永呼味野架域沙掉括舰鱼杂误湾吉减编楚肯测败屋跑梦散温困剑渐封救贵枪缺楼县尚毫移娘朋画班智亦耳恩短掌恐遗固席松秘谢鲁遇康虑幸均销钟诗藏赶剧票损忽巨炮旧端探湖录叶春乡附吸予礼港雨呀板庭妇归睛饭额含顺输摇招婚脱补谓督毒油疗旅泽材灭逐莫笔亡鲜词圣择寻厂睡博勒烟授诺伦岸奥唐卖俄炸载洛健堂旁宫喝借君禁阴园谋宋避抓荣姑孙逃牙束跳顶玉镇雪午练迫爷篇肉嘴馆遍凡础洞卷坦牛宁纸诸训私庄祖丝翻暴森塔默握戏隐熟骨访弱蒙歌店鬼软典欲萨伙遭盘爸扩盖弄雄稳忘亿刺拥徒姆杨齐赛趣曲刀床迎冰虚玩析窗醒妻透购替塞努休虎扬途侵刑绿兄迅套贸毕唯谷轮库迹尤竞街促延震弃甲伟麻川申缓潜闪售灯针哲络抵朱埃抱鼓植纯夏忍页杰筑折郑贝尊吴秀混臣雅振染盛怒舞圆搞狂措姓残秋培迷诚宽宇猛摆梅毁伸摩盟末乃悲拍丁赵硬麦蒋操耶阻订彩抽赞魔纷沿喊违妹浪汇币丰蓝殊献桌啦瓦援译夺汽烧距裁偏符勇触课敬哭懂墙袭召罚侠厅拜巧侧韩冒债曼融惯享戴童犹乘挂奖绍厚纵障讯涉彻刊丈爆乌役描洗玛患妙镜唱烦签仙彼弗症仿倾牌陷鸟轰咱菜闭奋庆撤泪茶疾缘播朗杜奶季丹狗尾仪偷奔珠虫驻孔宜艾桥淡翼恨繁寒伴叹旦愈潮粮缩罢聚径恰挑袋灰捕徐珍幕映裂泰隔启尖忠累炎暂估泛荒偿横拒瑞忆孤鼻闹羊呆厉衡胞零穷舍码赫婆魂灾洪腿胆津俗辩胸晓劲贫仁偶辑邦恢赖圈摸仰润堆碰艇稍迟辆废净凶署壁御奉旋冬矿抬蛋晨伏吹鸡倍糊秦盾杯租骑乏隆诊奴摄丧污渡旗甘耐凭扎抢绪粗肩梁幻菲皆碎宙叔岩荡综爬荷悉蒂返井壮薄悄扫敏碍殖详迪矛霍允幅撒剩凯颗骂赏液番箱贴漫酸郎腰舒眉忧浮辛恋餐吓挺励辞艘键伍峰尺昨黎辈贯侦滑券崇扰宪绕趋慈乔阅汗枝拖墨胁插箭腊粉泥氏彭拔骗凤慧媒佩愤扑龄驱惜豪掩兼跃尸肃帕驶堡届欣惠册储飘桑闲惨洁踪勃宾频仇磨递邪撞拟滚奏巡颜剂绩贡疯坡瞧截燃焦殿伪柳锁逼颇昏劝呈搜勤戒驾漂饮曹朵仔柔俩孟腐幼践籍牧凉牲佳娜浓芳稿竹腹跌逻垂遵脉貌柏狱猜怜惑陶兽帐饰贷昌叙躺钢沟寄扶铺邓寿惧询汤盗肥尝匆辉奈扣廷澳嘛董迁凝慰厌脏腾幽怨鞋丢埋泉涌辖躲晋紫艰魏吾慌祝邮吐狠鉴曰械咬邻赤挤弯椅陪割揭韦悟聪雾锋梯猫祥阔誉筹丛牵鸣沈阁穆屈旨袖猎臂蛇贺柱抛鼠瑟戈牢逊迈欺吨琴衰瓶恼燕仲诱狼池疼卢仗冠粒遥吕玄尘冯抚浅敦纠钻晶岂峡苍喷耗凌敲菌赔涂粹扁亏寂煤熊恭湿循暖糖赋抑秩帽哀宿踏烂袁侯抖夹昆肝擦猪炼恒慎搬纽纹玻渔磁铜齿跨押怖漠疲叛遣兹祭醉拳弥斜档稀捷肤疫肿豆削岗晃吞宏癌肚隶履涨耀扭坛拨沃绘伐堪仆郭牺歼墓雇廉契拼惩捉覆刷劫嫌瓜歇雕闷乳串娃缴唤赢莲霸桃妥瘦搭赴岳嘉舱俊址庞耕锐缝悔邀玲惟斥宅添挖呵讼氧浩羽斤酷掠妖祸侍乙妨贪挣汪尿莉悬唇翰仓轨枚盐览傅帅庙芬屏寺胖璃愚滴疏萧姿颤丑劣柯寸扔盯辱匹俱辨饿蜂哦腔郁溃谨糟葛苗肠忌溜鸿爵鹏鹰笼丘桂滋聊挡纲肌茨壳痕碗穴膀卓贤卧膜毅锦欠哩函茫昂薛皱夸豫胃舌剥傲拾窝睁携陵哼棉晴铃填饲渴吻扮逆脆喘罩卜炉柴愉绳胎蓄眠竭喂傻慕浑奸扇柜悦拦诞饱乾泡贼亭夕爹酬儒姻卵氛泄杆挨僧蜜吟猩遂狭肖甜霞驳裕顽於摘矮秒卿畜咽披辅勾盆疆赌塑畏吵囊嗯泊肺骤缠冈羞瞪吊贾漏斑涛悠鹿俘锡卑葬铭滩嫁催璇翅盒蛮矣潘歧赐鲍锅廊拆灌勉盲宰佐啥胀扯禧辽抹筒棋裤唉朴咐孕誓喉妄拘链驰栏逝窃艳臭纤玑棵趁匠盈翁愁瞬婴孝颈倘浙谅蔽畅赠妮莎尉冻跪闯葡厨鸭颠遮谊圳吁仑辟瘤嫂陀框谭亨钦庸歉芝吼甫衫摊宴嘱衷娇陕矩浦讶耸裸碧摧薪淋耻胶屠鹅饥盼脖虹翠崩账萍逢赚撑翔倡绵猴枯巫昭怔渊凑溪蠢禅阐旺寓藤匪伞碑挪琼脂谎慨菩萄狮掘抄岭晕逮砍挫僵匀泳寡葱咸"
                    // 编程注释常用字（技术词汇在通用字频表里排名偏后）
                    + "数据库表字段参数返回值调用方法函数类型对象接口实现配置缓存线程异常错误请求响应文件路径目录读取写入删除修改创建更新查询列索引默认空判断循环遍历初始化加载解析校验转换格式编码属性集合映射键节点树堆栈队链版本测试日志打印输出输入用户登录密码权限角色菜单页面按钮选择框提交注释说明示例临时变量常量静态私有公共继承重载覆盖回调事件监听启停止并发锁同步异步超时重试连接断开服务端客户端网络协议端口地址消息通知订阅发布任务调度执行结果状态成功失败处理逻辑业务模块组件插件框架依赖";

    /** GB2312 区位表（94×94）上的常用字标记。 */
    private static final boolean[] COMMON_GB = buildCommonGb();

    private static boolean[] buildCommonGb() {
        boolean[] table = new boolean[94 * 94];
        Charset gbk = charsetOrNull("GBK");
        if (gbk == null) {
            return table;
        }
        for (int i = 0; i < COMMON_HANZI.length(); i++) {
            byte[] b = String.valueOf(COMMON_HANZI.charAt(i)).getBytes(gbk);
            if (b.length == 2) {
                int hi = (b[0] & 0xFF) - 0xA1;
                int lo = (b[1] & 0xFF) - 0xA1;
                if (hi >= 0 && hi < 94 && lo >= 0 && lo < 94) {
                    table[hi * 94 + lo] = true;
                }
            }
        }
        return table;
    }
}
