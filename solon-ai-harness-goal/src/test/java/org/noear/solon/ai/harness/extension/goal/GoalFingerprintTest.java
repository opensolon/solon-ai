/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.extension.goal;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 指纹计算单元测试（Sprint 2: 无进展检测精度改进）
 *
 * @since 3.9.3
 */
class GoalFingerprintTest {

    // ===== 辅助：Java 8 兼容的 repeat =====

    private static String repeat(String s, int count) {
        StringBuilder sb = new StringBuilder(s.length() * count);
        for (int i = 0; i < count; i++) sb.append(s);
        return sb.toString();
    }

    // ===== countLines 单元测试 =====

    @Test
    void countLinesEmptyTextReturns1() {
        assertEquals(1, GoalRuntime.countLines(""));
    }

    @Test
    void countLinesSingleLineReturns1() {
        assertEquals(1, GoalRuntime.countLines("hello world"));
    }

    @Test
    void countLinesMultipleLines() {
        assertEquals(3, GoalRuntime.countLines("line1\nline2\nline3"));
    }

    @Test
    void countLinesTrailingNewline() {
        assertEquals(2, GoalRuntime.countLines("line1\n"));
    }

    // ===== computeFingerprint（静态方法，无需 GoalRuntime 实例） =====

    @Test
    void fingerprintNullResultReturnsNull() {
        assertEquals("null", GoalRuntime.computeFingerprint(null));
    }

    @Test
    void fingerprintEmptyTextReturnsEmpty() {
        GoalOutcome empty = TestOutcomes.ofText("");
        assertEquals("empty", GoalRuntime.computeFingerprint(empty));
    }

    @Test
    void fingerprintFormatHasThreeDimensions() {
        // 200 字以内 → lenBucket=0, 1 行 → lineBucket=0, 无工具调用 → "0"
        GoalOutcome shortText = TestOutcomes.ofText("short reply");
        String fp = GoalRuntime.computeFingerprint(shortText);
        assertTrue(fp.matches("\\d+:\\d+:\\d+"),
                "fingerprint should have 3 colon-separated dimensions, got: " + fp);
    }

    // ===== 指纹区分力验证 =====

    @Test
    void fingerprintDistinguishesToolCallPresence() {
        // 短文本（<200字），无工具调用
        GoalOutcome noTool = TestOutcomes.ofText("analysis result");
        // 长文本（>20字自动判为 hasToolCalls=true），有工具调用
        GoalOutcome withTool = TestOutcomes.ofText(
                repeat("x", 21) + "\ncommand output");

        String fpNoTool = GoalRuntime.computeFingerprint(noTool);
        String fpWithTool = GoalRuntime.computeFingerprint(withTool);

        // 工具维度不同（0 vs 1）
        assertNotEquals(fpNoTool.charAt(0), fpWithTool.charAt(0),
                "tool call dimension should differ");
    }

    @Test
    void fingerprintDistinguishesDifferentLengthBuckets() {
        // 199 字 → lenBucket=0
        GoalOutcome shortText = TestOutcomes.ofText(repeat("x", 199));
        // 201 字 → lenBucket=1（不同桶）
        GoalOutcome longerText = TestOutcomes.ofText(repeat("x", 201));

        String fpShort = GoalRuntime.computeFingerprint(shortText);
        String fpLonger = GoalRuntime.computeFingerprint(longerText);

        // 200 字/桶，199 和 201 在不同桶
        assertNotEquals(fpShort, fpLonger,
                "texts of 199 and 201 chars should be in different length buckets");
    }

    @Test
    void fingerprintDistinguishesDifferentLineBuckets() {
        // 9 行 + padding ≈ 200 字 → lineBucket=0
        String text9Lines = "line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9";
        text9Lines = text9Lines + repeat(" ", Math.max(0, 200 - text9Lines.length()));

        // 11 行 + padding ≈ 200 字 → lineBucket=1
        String text11Lines = "line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9\nline10\nline11";
        text11Lines = text11Lines + repeat(" ", Math.max(0, 200 - text11Lines.length()));

        GoalOutcome result9 = TestOutcomes.ofText(text9Lines);
        GoalOutcome result11 = TestOutcomes.ofText(text11Lines);

        String fp9 = GoalRuntime.computeFingerprint(result9);
        String fp11 = GoalRuntime.computeFingerprint(result11);

        assertNotEquals(fp9, fp11,
                "texts with 9 vs 11 lines should be in different line buckets");
    }

    @Test
    void fingerprintSameForSameContent() {
        String content = "same content\nwith tool calls\nand three lines";

        GoalOutcome r1 = TestOutcomes.ofText(content);
        GoalOutcome r2 = TestOutcomes.ofText(content);

        assertEquals(GoalRuntime.computeFingerprint(r1), GoalRuntime.computeFingerprint(r2),
                "same content should produce same fingerprint");
    }
}
