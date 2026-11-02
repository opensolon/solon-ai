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
package org.noear.solon.ai.harness.input;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 视野选择入口的语义锁定：未指定 / 传空 / 去重保序。
 */
class HarnessInputSelectionTest {
    @Test
    void notSpecifiedMeansUnrestricted() {
        HarnessInput input = HarnessInput.of(null, "hi");

        assertFalse(input.hasSkillSelection());
        assertFalse(input.hasMcpSelection());
        assertTrue(input.getSelectedSkills().isEmpty());
        assertTrue(input.getSelectedMcps().isEmpty());
    }

    @Test
    void explicitEmptyIsEquivalentToNotSpecified() {
        HarnessInput input = HarnessInput.of(null, "hi")
                .selectedSkill()
                .selectedMcp(new String[0]);

        // 空数组 == 不限制：避免宿主把"没勾选"序列化成空列表时把资源全关掉
        assertFalse(input.hasSkillSelection());
        assertFalse(input.hasMcpSelection());
    }

    @Test
    void selectionIsTrimmedDedupedAndOrdered() {
        HarnessInput input = HarnessInput.of(null, "hi")
                .selectedSkill(" @user-skills/a ", "@user-skills/a", "skills/b");

        assertTrue(input.hasSkillSelection());
        assertEquals(Arrays.asList("@user-skills/a", "skills/b"), input.getSelectedSkills());

        // 不可变：Talent 侧是多请求共享读取，调用方不应能反手改掉
        try {
            input.getSelectedSkills().add("x");
            throw new AssertionError("应不可变");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }

    @Test
    void nullArrayResetsSelection() {
        HarnessInput input = HarnessInput.of(null, "hi")
                .selectedMcp("weather")
                .selectedMcp((String[]) null);

        assertFalse(input.hasMcpSelection());
    }

    @Test
    void skillAndMcpSelectionsAreIndependent() {
        HarnessInput input = HarnessInput.of(null, "hi")
                .selectedSkill("@user-skills/a")
                .selectedMcp("weather");

        assertEquals(Arrays.asList("@user-skills/a"), input.getSelectedSkills());
        assertEquals(Arrays.asList("weather"), input.getSelectedMcps());
    }
}
