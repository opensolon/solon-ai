/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.llm.dialect.openai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「累计快照 → 流式增量」归一器的判定边界
 *
 * <p>协议语义：官方 delta 是新增文本，少数兼容网关下发累计快照。改写只允许在证据充分时发生，
 * 否则会静默篡改正常输出。</p>
 */
public class SnapshotDeltaNormalizerTest {

    @Test
    public void emptyValue_returnedAsIsWithoutStateChange() {
        SnapshotDeltaNormalizer n = new SnapshotDeltaNormalizer();

        assertNull(n.normalize(null));
        assertEquals("", n.normalize(""));
        assertEquals(0, n.length(), "空值不得进入累积");
        assertFalse(n.isSnapshotMode());
    }

    @Test
    public void firstFrame_alwaysKeptAsDelta() {
        SnapshotDeltaNormalizer n = new SnapshotDeltaNormalizer();

        assertEquals("abcdefgh", n.normalize("abcdefgh"));
        assertEquals(8, n.length());
        assertFalse(n.isSnapshotMode(), "首帧不足以判定快照流");
    }

    @Test
    public void shortPrefixDeltas_notTreatedAsSnapshot() {
        SnapshotDeltaNormalizer n = new SnapshotDeltaNormalizer();

        // 累积长度未达 MIN_MATCH_LENGTH：即使构成前缀关系也按普通增量处理
        assertEquals("好", n.normalize("好"));
        assertEquals("好的", n.normalize("好的"));
        assertFalse(n.isSnapshotMode());
        assertEquals(3, n.length(), "普通增量应原样累积");
    }

    @Test
    public void equalLengthFrame_notEnoughToEnterSnapshotMode() {
        SnapshotDeltaNormalizer n = new SnapshotDeltaNormalizer();

        n.normalize("12345678");
        // 首次判定要求本帧严格长于累积（真的有新增后缀），完全相等不算证据
        assertEquals("12345678", n.normalize("12345678"));
        assertFalse(n.isSnapshotMode());
        assertEquals(16, n.length());
    }

    @Test
    public void longPrefixFrame_entersSnapshotModeAndTruncates() {
        SnapshotDeltaNormalizer n = new SnapshotDeltaNormalizer();

        assertEquals("累计快照的判定门槛", n.normalize("累计快照的判定门槛"));
        assertEquals("，再决定是否截断", n.normalize("累计快照的判定门槛，再决定是否截断"));
        assertTrue(n.isSnapshotMode(), "达到门槛且严格增长应进入粘滞快照模式");
        assertEquals("累计快照的判定门槛，再决定是否截断".length(), n.length());
    }

    @Test
    public void snapshotMode_duplicateFrameBecomesEmpty() {
        SnapshotDeltaNormalizer n = new SnapshotDeltaNormalizer();

        n.normalize("12345678");
        n.normalize("123456789");
        assertTrue(n.isSnapshotMode());

        // 粘滞模式下完全相等的重复帧才允许被吃掉
        assertEquals("", n.normalize("123456789"));
        assertEquals(9, n.length(), "重复帧不得再次累积");
    }

    @Test
    public void snapshotMode_nonPrefixFrameKeptWhole() {
        SnapshotDeltaNormalizer n = new SnapshotDeltaNormalizer();

        n.normalize("12345678");
        n.normalize("123456789");

        // 快照流中途改口（非前缀）：只能整帧当增量，不能凭空截断
        assertEquals("XYZ", n.normalize("XYZ"));
        assertEquals(12, n.length());

        // 比累积更短的帧同样无法构成前缀
        assertEquals("12", n.normalize("12"));
    }

    @Test
    public void notPrefix_charMismatchKeepsFullValue() {
        SnapshotDeltaNormalizer n = new SnapshotDeltaNormalizer();

        n.normalize("第一段较长的输出内容");
        // 等长且不同：连长度证据都不成立，原样保留
        assertEquals("第二段完全不同的内容", n.normalize("第二段完全不同的内容"));
        assertFalse(n.isSnapshotMode());
    }

    @Test
    public void longerButNotPrefix_isNotSnapshot() {
        SnapshotDeltaNormalizer n = new SnapshotDeltaNormalizer();

        n.normalize("第一段较长的输出内容");
        // 长度达门槛且严格更长，但逐字符比较在首字即失配：仍是普通增量，不得截断
        assertEquals("完全不同的另一段更长的输出内容", n.normalize("完全不同的另一段更长的输出内容"));
        assertFalse(n.isSnapshotMode());
        assertEquals("第一段较长的输出内容完全不同的另一段更长的输出内容".length(), n.length());
    }

    @Test
    public void append_recordsBaselineWithoutJudging() {
        SnapshotDeltaNormalizer n = new SnapshotDeltaNormalizer();

        n.append(null);
        n.append("");
        assertEquals(0, n.length(), "空值不得进入累积");

        n.append("抱歉，我不能协助该请求。");
        assertEquals("抱歉，我不能协助该请求。".length(), n.length());
        assertFalse(n.isSnapshotMode(), "append 不做快照判定");

        // append 写入的基准同样参与后续判定（官方独有字段与正文共用一个通道）
        assertEquals("可以换个问法", n.normalize("抱歉，我不能协助该请求。可以换个问法"));
        assertTrue(n.isSnapshotMode());
    }

    @Test
    public void minMatchLengthContract() {
        assertEquals(8, SnapshotDeltaNormalizer.MIN_MATCH_LENGTH,
                "门槛值是判定误伤与漏判的平衡点，变更需同步测试");
    }
}
