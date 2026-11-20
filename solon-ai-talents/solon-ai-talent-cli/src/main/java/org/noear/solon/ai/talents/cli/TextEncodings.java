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

import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 文件编码判断（read / edit / write / grep 共用，保证同一份内容四个工具判断一致）。
 *
 * <p>只认 UTF-8，不猜其他编码：编码只由文件内容决定，同一份文件在任何机器上结果相同。判断顺序（命中即停）：</p>
 * <ol>
 *   <li>BOM：UTF-8 / UTF-16LE / UTF-16BE（编码是确定的，不需要猜）；</li>
 *   <li>整份严格合法的 UTF-8（{@link Source#PRIMARY}）；</li>
 *   <li>大体是 UTF-8、只夹少量坏字节：仍按 UTF-8，坏字节原样保留（{@link Source#MIXED}）；</li>
 *   <li>其余都算“不是 UTF-8”：按 UTF-8 显示，坏字节原样保留，新写入的内容不能含非 ASCII 字符（{@link Source#UNKNOWN}）。</li>
 * </ol>
 *
 * <p>主编码（{@code TerminalTalent.fileCharset}）不是 UTF-8 时，第 2 步改为“按主编码解码没有坏字节”，没有第 3 步。</p>
 *
 * @author noear
 * @since 4.2
 */
final class TextEncodings {
    private TextEncodings() {
    }

    /** 编码的来源（决定显示与写入规则）。 */
    enum Source {
        /** 整份严格合法的主编码（默认 UTF-8，含纯 ASCII） */
        PRIMARY,
        /** 由 BOM 确定 */
        BOM,
        /** 大体是 UTF-8，夹少量坏字节 */
        MIXED,
        /** 不是主编码（无法确定实际编码） */
        UNKNOWN
    }

    /** 超大文件只检测开头这么多字节。 */
    static final int SAMPLE_BYTES = 1024 * 1024;

    /** 大体是 UTF-8 的判定：合法多字节字符数至少是坏字节段数的这么多倍。 */
    static final int MIXED_RATIO = 4;

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
            return StandardCharsets.UTF_16LE;
        }
        if (len >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return StandardCharsets.UTF_16BE;
        }
        return null;
    }

    /** 带 UTF-16 BOM 时返回对应编码，否则 null（这类文件含大量 NUL，不能按二进制探测，也不能按 UTF-8 读）。 */
    static Charset utf16BomCharset(byte[] b, int len) {
        Charset cs = bomCharset(b, len);
        return cs == StandardCharsets.UTF_8 ? null : cs;
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

    // ---------------------------------------------------------------- 判断

    /**
     * 判断编码。
     *
     * @param bytes   文件内容（抽样时为开头部分）
     * @param length  有效长度
     * @param sampled 是否只是开头的抽样（超大文件）
     * @param primary 主编码（{@code TerminalTalent.fileCharset}，默认 UTF-8）
     */
    static Decoded detect(byte[] bytes, int length, boolean sampled, Charset primary) {
        Charset bom = bomCharset(bytes, length);
        if (bom != null) {
            int start = bomLength(bom);
            return new Decoded(bytes, start, length, bom, Source.BOM, sampled, countRaw(bytes, start, length, bom, sampled));
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

        if (!StandardCharsets.UTF_8.equals(primary)) {
            int raw = countRaw(bytes, 0, end, primary, sampled);
            return new Decoded(bytes, 0, length, primary, raw == 0 ? Source.PRIMARY : Source.UNKNOWN, sampled, raw);
        }

        Utf8Scan scan = scanUtf8(bytes, 0, end, sampled);
        if (scan.valid) {
            return new Decoded(bytes, 0, length, primary, Source.PRIMARY, sampled, 0);
        }
        Source source = scan.multiByte > 0 && scan.multiByte >= MIXED_RATIO * scan.badSegments ? Source.MIXED : Source.UNKNOWN;
        return new Decoded(bytes, 0, length, primary, source, sampled, scan.badBytes);
    }

    /** 按保字节解码统计解不了的字节数。 */
    private static int countRaw(byte[] bytes, int off, int end, Charset cs, boolean sampled) {
        if (StandardCharsets.UTF_8.equals(cs)) {
            return scanUtf8(bytes, off, end, sampled).badBytes;
        }
        String text = TerminalSupport.decodeKeepingRawBytes(Arrays.copyOfRange(bytes, off, end), cs);
        return TerminalSupport.countRawBytes(text);
    }

    // ---------------------------------------------------------------- 编码校验

    /**
     * 第一个无法按 {@code cs} 编码的字符：返回 {1 起的字符序号, 码点}；都能编码返回 null。
     * 孤立代理（含原字节映射字符）一律算无法编码。
     */
    static int[] firstUnencodable(String text, Charset cs) {
        CharsetEncoder enc = cs.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
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

    /** 判断结果。 */
    static final class Decoded {
        private final byte[] bytes;
        private final int bodyStart;
        private final int end;
        private final Charset charset;
        private final Source source;
        private final boolean sampled;
        private final int rawBytes;
        private String text;

        Decoded(byte[] bytes, int bodyStart, int end, Charset charset, Source source, boolean sampled, int rawBytes) {
            this.bytes = bytes;
            this.bodyStart = bodyStart;
            this.end = end;
            this.charset = charset;
            this.source = source;
            this.sampled = sampled;
            this.rawBytes = rawBytes;
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

        /** 新写入的内容能否含非 ASCII 字符（不是 UTF-8 的文件不能，否则文件会混入另一种编码）。 */
        boolean allowsNonAscii() {
            return source != Source.UNKNOWN;
        }

        /**
         * 正文（不含 BOM）的保字节解码：解不了的字节映射为 U+DC00+b，供 edit 编辑后原样写回。
         * 没有解不了的字节时与普通解码结果相同。
         */
        String text() {
            if (text == null) {
                text = rawBytes == 0 && !sampled
                        ? new String(bytes, bodyStart, end - bodyStart, charset)
                        : TerminalSupport.decodeKeepingRawBytes(Arrays.copyOfRange(bytes, bodyStart, end), charset);
            }
            return text;
        }

        /** 正文（不含 BOM）的展示解码：解不了的字节显示为 U+FFFD（与按替换模式读取一致）。 */
        String displayText() {
            return rawBytes == 0 && !sampled ? text() : new String(bytes, bodyStart, end - bodyStart, charset);
        }

        /**
         * 编辑后的正文能否原样写回（未编辑时编码结果与原字节逐字节相同）。
         * UTF-8 与按 BOM 确定的 UTF-16 在没有坏字节时总能；其余情况（坏字节、其他主编码）实际编码一遍核对。
         */
        boolean writesBackUnchanged() {
            if (sampled || (rawBytes == 0 && (source == Source.BOM || StandardCharsets.UTF_8.equals(charset)))) {
                return true;
            }
            return TerminalSupport.writesBackUnchanged(text(), Arrays.copyOfRange(bytes, bodyStart, end), charset);
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

        /**
         * read 首行的 Encoding 字段（固定英文）：{@code UTF-8}、{@code UTF-8 with BOM}、{@code UTF-16LE with BOM}、
         * {@code UTF-8, 2 undecodable bytes shown as U+FFFD}、{@code not UTF-8, 120 undecodable bytes shown as U+FFFD}，
         * 超大文件追加 {@code , sampled first 1 MB}。
         */
        String label() {
            StringBuilder sb = new StringBuilder();
            if (source == Source.UNKNOWN) {
                sb.append("not ");
            }
            sb.append(charset.name());
            if (source == Source.BOM) {
                sb.append(" with BOM");
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
}
