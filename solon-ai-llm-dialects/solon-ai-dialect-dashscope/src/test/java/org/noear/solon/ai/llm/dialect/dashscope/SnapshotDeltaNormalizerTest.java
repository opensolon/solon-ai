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
package org.noear.solon.ai.llm.dialect.dashscope;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「累计快照 → 流式增量」归一器的判定规则
 *
 * <p>核心累积器按流式协议逐帧追加，因此本类的契约是：把「每帧全量快照」截成真正的新增后缀，
 * 同时对普通增量流零改写（宁可少截、不可错截——错截会永久吞掉正文）。</p>
 *
 * @author noear
 */
public class SnapshotDeltaNormalizerTest {
    /**
     * 判定阈值内的基准文本（正好 {@link SnapshotDeltaNormalizer#MIN_MATCH_LENGTH} 个字符）
     */
    private static final String BASE = "杭州今天天气晴朗";

    @Test
    public void minMatchLengthContract() {
        assertEquals(8, SnapshotDeltaNormalizer.MIN_MATCH_LENGTH,
                "阈值变化会改变判定期长度，需同步 openai 方言的同名类");
        assertEquals(SnapshotDeltaNormalizer.MIN_MATCH_LENGTH, BASE.length());
    }

    /**
     * 空帧：原样返回且不计入累积（否则会污染后续前缀判定）
     */
    @Test
    public void emptyFrameIsPassedThroughWithoutAccumulating() {
        SnapshotDeltaNormalizer normalizer = new SnapshotDeltaNormalizer();

        assertNull(normalizer.normalize(null), "null 帧原样返回");
        assertEquals("", normalizer.normalize(""), "空串帧原样返回");

        assertEquals(0, normalizer.length(), "空帧不得计入累积基准");
        assertFalse(normalizer.isSnapshotMode());
    }

    /**
     * 首帧无基准可比：一定原样透传
     */
    @Test
    public void firstFrameIsAlwaysVerbatim() {
        SnapshotDeltaNormalizer normalizer = new SnapshotDeltaNormalizer();

        assertEquals(BASE, normalizer.normalize(BASE));
        assertEquals(BASE.length(), normalizer.length());
        assertFalse(normalizer.isSnapshotMode(), "单帧不足以判定快照流");
    }

    /**
     * 短增量（累积长度未达阈值）：即使偶然构成前缀关系也按普通增量处理
     */
    @Test
    public void shortDeltasBelowThresholdStayVerbatim() {
        SnapshotDeltaNormalizer normalizer = new SnapshotDeltaNormalizer();

        assertEquals("好", normalizer.normalize("好"));
        //"好的" 是 "好" 的前缀延伸，但累积长度 1 < 8，不得截断
        assertEquals("好的", normalizer.normalize("好的"));

        assertFalse(normalizer.isSnapshotMode());
        assertEquals(3, normalizer.length());
    }

    /**
     * 达到阈值且本帧严格更长的前缀延伸：判定为快照流，只交付新增后缀
     */
    @Test
    public void snapshotDetectedAfterThresholdDeliversSuffixOnly() {
        SnapshotDeltaNormalizer normalizer = new SnapshotDeltaNormalizer();

        assertEquals(BASE, normalizer.normalize(BASE));
        assertEquals("，气温25度", normalizer.normalize(BASE + "，气温25度"));

        assertTrue(normalizer.isSnapshotMode(), "已判定为累计快照流");
        assertEquals(BASE.length() + 6, normalizer.length(), "累积基准 = 已交付文本");
    }

    /**
     * 判定期的完全相等帧：不接受（不截），避免把普通重复增量误判为快照
     */
    @Test
    public void equalFrameBeforeDetectionIsNotTreatedAsSnapshot() {
        SnapshotDeltaNormalizer normalizer = new SnapshotDeltaNormalizer();

        normalizer.normalize(BASE);
        assertEquals(BASE, normalizer.normalize(BASE), "判定期要求严格更长，完全相等按普通增量");
        assertFalse(normalizer.isSnapshotMode());
        assertEquals(BASE.length() * 2, normalizer.length());
    }

    /**
     * 粘滞模式下的重复帧（结束帧常重发整段快照）：吃掉，交付空串
     */
    @Test
    public void duplicatedFrameInSnapshotModeIsDropped() {
        SnapshotDeltaNormalizer normalizer = new SnapshotDeltaNormalizer();

        normalizer.normalize(BASE);
        String full = BASE + "，气温25度";
        assertEquals("，气温25度", normalizer.normalize(full));

        int lengthBefore = normalizer.length();
        assertEquals("", normalizer.normalize(full), "快照模式下的完全重复帧不得再交付正文");
        assertEquals(lengthBefore, normalizer.length(), "重复帧不得增长累积基准");
    }

    /**
     * 粘滞模式下的回退帧（本帧比已交付文本更短，前缀关系不成立）：原样透传，不吞内容
     */
    @Test
    public void shorterFrameInSnapshotModeIsKeptVerbatim() {
        SnapshotDeltaNormalizer normalizer = new SnapshotDeltaNormalizer();

        normalizer.normalize(BASE);
        normalizer.normalize(BASE + "，气温25度");

        assertEquals("短", normalizer.normalize("短"), "比累积更短 → 不是快照延伸，原样交付");
    }

    /**
     * 粘滞模式下的分叉帧（等长或更长但首字符就不同）：原样透传
     */
    @Test
    public void divergentFrameInSnapshotModeIsKeptVerbatim() {
        SnapshotDeltaNormalizer normalizer = new SnapshotDeltaNormalizer();

        normalizer.normalize(BASE);
        normalizer.normalize(BASE + "，气温25度");

        String divergent = "另起一段的完整正文内容";
        assertTrue(divergent.length() > BASE.length());
        assertEquals(divergent, normalizer.normalize(divergent), "非前缀延伸帧不得截断");
    }

    /**
     * 真增量流（incremental_output 已生效）：累积达阈值后仍不得改写任何一帧
     */
    @Test
    public void realDeltaStreamIsNeverRewritten() {
        SnapshotDeltaNormalizer normalizer = new SnapshotDeltaNormalizer();
        StringBuilder delivered = new StringBuilder();

        String[] frames = {BASE, "接下来是更长的一段新增内容", "，收尾"};
        for (String frame : frames) {
            String delta = normalizer.normalize(frame);
            assertEquals(frame, delta, "普通增量帧必须原样交付");
            delivered.append(delta);
        }

        assertFalse(normalizer.isSnapshotMode());
        assertEquals(BASE + "接下来是更长的一段新增内容，收尾", delivered.toString());
    }

    /**
     * append：不做判定地补基准（用于无快照实现的字段），随后仍参与快照判定
     */
    @Test
    public void appendSeedsBaselineWithoutJudgement() {
        SnapshotDeltaNormalizer normalizer = new SnapshotDeltaNormalizer();

        normalizer.append(null);
        normalizer.append("");
        assertEquals(0, normalizer.length(), "空值不得计入累积");

        normalizer.append(BASE);
        assertEquals(BASE.length(), normalizer.length());
        assertFalse(normalizer.isSnapshotMode(), "append 本身不做判定");

        assertEquals("，气温25度", normalizer.normalize(BASE + "，气温25度"),
                "append 的文本必须作为后续快照判定的基准");
        assertTrue(normalizer.isSnapshotMode());
    }

    /**
     * 完整快照流：逐帧交付拼起来 = 最后一帧全量（既不重复也不丢字）
     */
    @Test
    public void snapshotStreamRebuildsExactlyOnce() {
        SnapshotDeltaNormalizer normalizer = new SnapshotDeltaNormalizer();
        StringBuilder delivered = new StringBuilder();

        String[] snapshots = {
                BASE,
                BASE + "，气温25度",
                BASE + "，气温25度，体感舒适",
                BASE + "，气温25度，体感舒适"
        };
        for (String snapshot : snapshots) {
            delivered.append(normalizer.normalize(snapshot));
        }

        assertEquals(snapshots[snapshots.length - 1], delivered.toString());
        assertEquals(delivered.length(), normalizer.length());
    }
}
