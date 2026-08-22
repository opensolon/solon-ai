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

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 子进程输出解码器：期望字符集（通常 UTF-8）优先，Windows 遗留代码页（GBK 等）兜底。
 *
 * <p><b>字符集判定</b>：用严格模式（{@link CodingErrorAction#REPORT}）校验字节流是否为合法的
 * 期望字符集编码——非法即判定为遗留代码页。不采用「统计 U+FFFD 数量」的启发式：那样只要输出里
 * 合法地出现一个替换符（工具自身打印、或混入少量坏字节），就会误判并把正确的 UTF-8 整段重解成
 * 乱码；而 GBK 解码器几乎不产生替换符，比较数量的方向性并不可靠。</p>
 *
 * <p><b>流式使用</b>：{@link #decode(byte[], int)} 可被反复调用（仅限单线程，如输出读取线程），
 * 内部持有 {@link CharsetDecoder} 与残字节，因此多字节字符跨读取分块也不会被截断；
 * 全部读完后调用 {@link #flush()} 输出尾部残字节。整体为 O(n)，不做全量重解码。</p>
 *
 * <p><b>一次性使用</b>：{@link #decodeAll(byte[], Charset)} 用于已拿到完整字节的同步执行路径。</p>
 *
 * <p><b>两条路径的判定差异</b>：{@code decodeAll} 能看到完整字节，做全量严格校验；流式路径只能
 * 「首次拿到足够样本时锁定」（此后不再改判，否则已回调出去的文本无法撤回）。同一次执行若同时用到
 * 两条路径，应通过 {@link #selectedCharset()} 让最终解码复用流式已锁定的字符集，避免实时输出与
 * 最终返回值不一致。</p>
 *
 * @author noear
 * @since 4.0.4
 */
final class OutputDecoder {
    /** ASCII 快速通道的编码：字节全 &lt; 0x80 时任意 ASCII 兼容字符集结果一致 */
    private static final Charset ASCII_FAST = StandardCharsets.ISO_8859_1;
    private static final byte[] EMPTY = new byte[0];
    /** 判定字符集所需的最小非 ASCII 样本字节数（UTF-8 单字符最长 4 字节） */
    private static final int MIN_PROBE_BYTES = 4;

    private final Charset primary;
    private final Charset fallback;

    private CharsetDecoder decoder; // 判定后固定；仍为纯 ASCII 时为 null（走快速通道）
    private Charset selected;       // 已锁定的字符集；未锁定时为 null
    private byte[] pending = EMPTY; // 上次未消费的字节（不完整多字节序列，或样本不足未判定的尾部）

    OutputDecoder(Charset primary) {
        this(primary, legacyCharset(primary));
    }

    OutputDecoder(Charset primary, Charset fallback) {
        this.primary = primary == null ? StandardCharsets.UTF_8 : primary;
        this.fallback = (fallback == null || fallback.equals(this.primary)) ? null : fallback;
    }

    /**
     * 流式路径已锁定的字符集；尚未出现非 ASCII 字节（未锁定）时返回 {@code null}。
     */
    Charset selectedCharset() {
        return selected;
    }

    /**
     * 增量解码一段输出。非线程安全：只能由单一读取线程调用。
     */
    String decode(byte[] buf, int len) {
        if (buf == null || len <= 0) {
            return "";
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

        if (decoder == null) {
            int probe = firstNonAscii(data, dataLen);
            if (probe < 0) {
                // 纯 ASCII：两种候选字符集结果一致，直接输出且不产生残字节，暂不锁定字符集
                return new String(data, 0, dataLen, ASCII_FAST);
            }
            if (dataLen - probe < MIN_PROBE_BYTES) {
                // 非 ASCII 样本不足（多字节序列可能正好被读取分块切断）：此时判定极易误锁——
                // 单个 0xC4 在「未到结尾」的严格校验下对 UTF-8 合法，会把实际的 GBK 输出锁成 UTF-8。
                // 先输出确定无歧义的 ASCII 前缀，尾部留到下一段凑够样本再判定。
                pending = copyOf(data, probe, dataLen);
                return new String(data, 0, probe, ASCII_FAST);
            }
            lock(select(data, probe, dataLen - probe, primary, fallback));
        }

        ByteBuffer in = ByteBuffer.wrap(data, 0, dataLen);
        CharBuffer out = CharBuffer.allocate((int) (in.remaining() * decoder.maxCharsPerByte()) + 1);
        decoder.decode(in, out, false);

        // 尾部不完整的多字节序列留到下一段（避免跨块截断产生替换符）
        pending = in.hasRemaining() ? copyRemaining(in) : EMPTY;

        out.flip();
        return out.toString();
    }

    /**
     * 输出结束：把尾部残字节按替换符收尾。
     */
    String flush() {
        if (pending.length == 0) {
            return "";
        }
        if (decoder == null) {
            // 样本不足而延迟判定的尾部：到此已是全部输入，按现有字节判定（不能直接丢弃，否则丢输出）
            lock(select(pending, 0, pending.length, primary, fallback));
        }
        ByteBuffer in = ByteBuffer.wrap(pending);
        CharBuffer out = CharBuffer.allocate((int) (in.remaining() * decoder.maxCharsPerByte()) + 1);
        decoder.decode(in, out, true);
        decoder.flush(out);
        pending = EMPTY;
        out.flip();
        return out.toString();
    }

    private void lock(Charset charset) {
        selected = charset;
        decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
    }

    /**
     * 一次性解码完整字节输出：合法则按 primary，非法则按平台遗留代码页。
     */
    static String decodeAll(byte[] raw, Charset primary) {
        if (raw == null || raw.length == 0) {
            return "";
        }
        Charset expect = primary == null ? StandardCharsets.UTF_8 : primary;
        Charset selected = select(raw, 0, raw.length, expect, legacyCharset(expect));
        return new String(raw, selected);
    }

    /**
     * 严格校验 {@code [off, off+len)} 是否为合法的 {@code primary} 编码；否则返回 {@code fallback}。
     *
     * <p>按「未到输入结尾」校验（{@code endOfInput=false}），因此尾部被截断的不完整多字节序列
     * 不算错误——输出可能因上限截断或分块读取而正好切在字符中间。</p>
     */
    static Charset select(byte[] bytes, int off, int len, Charset primary, Charset fallback) {
        if (fallback == null || fallback.equals(primary)) {
            return primary;
        }
        CharsetDecoder strict = primary.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(bytes, off, len);
        CharBuffer out = CharBuffer.allocate(8192);
        while (in.hasRemaining()) {
            CoderResult result = strict.decode(in, out, false);
            if (result.isMalformed() || result.isUnmappable()) {
                return fallback;
            }
            if (result.isOverflow()) {
                out.clear(); // 只做校验，解码结果丢弃
                continue;
            }
            break; // UNDERFLOW：尾部不完整序列，视为合法
        }
        return primary;
    }

    /**
     * 平台遗留代码页：仅 Windows 有意义（中文系统为 GBK）。
     *
     * <p>非 Windows 返回 null（不做兜底）：类 Unix 的 {@code sun.jnu.encoding} 常为 UTF-8 或
     * POSIX locale 下的 ASCII，用它兜底只会把非法字节解得更糟。</p>
     */
    static Charset legacyCharset(Charset primary) {
        if (!EnvironmentResolver.isWindows()) {
            return null;
        }
        return ansiCharset(primary);
    }

    /**
     * 系统 ANSI 代码页字符集（不判平台，供脚本写入等场景使用）：
     * 优先 {@code sun.jnu.encoding}，其次 GBK；与 {@code primary} 相同则返回 null。
     */
    static Charset ansiCharset(Charset primary) {
        String jnu = System.getProperty("sun.jnu.encoding");
        if (jnu != null && !jnu.isEmpty()) {
            try {
                Charset c = Charset.forName(jnu);
                return c.equals(primary) ? null : c;
            } catch (Throwable ignored) {
                // 无法识别则继续兜底
            }
        }
        try {
            Charset c = Charset.forName("GBK");
            return c.equals(primary) ? null : c;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 首个非 ASCII 字节下标；全为 ASCII 时返回 -1。
     */
    private static int firstNonAscii(byte[] buf, int len) {
        for (int i = 0; i < len; i++) {
            if ((buf[i] & 0x80) != 0) {
                return i;
            }
        }
        return -1;
    }

    private static byte[] copyOf(byte[] buf, int from, int to) {
        byte[] rest = new byte[to - from];
        System.arraycopy(buf, from, rest, 0, rest.length);
        return rest;
    }

    private static byte[] copyRemaining(ByteBuffer in) {
        byte[] rest = new byte[in.remaining()];
        in.get(rest);
        return rest;
    }
}
