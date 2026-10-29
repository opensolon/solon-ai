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
package org.noear.solon.ai.harness.command.builtin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /goal 命令元数据测试。
 *
 * <p>Goal 已是独立产品能力，不再自称 "/loop goal 的快捷别名"，
 * 因此断言其描述与示例中均不出现 loop 引用（防止耦合回流）。</p>
 */
class GoalCommandTest {
    @Test
    void shouldExposeGoalMetadata() {
        GoalCommand command = new GoalCommand(null, null);

        assertEquals("goal", command.name());
        assertEquals("/goal <objective>", command.examples()[0]);
    }

    @Test
    void descriptionShouldNotReferenceLoop() {
        GoalCommand command = new GoalCommand(null, null);

        assertFalse(command.description().toLowerCase().contains("loop"),
                "goal 是独立能力，描述不应再引用 loop");
        for (String example : command.examples()) {
            assertFalse(example.contains("/loop"),
                    "示例不应再引用 /loop: " + example);
        }
    }

    @Test
    void examplesShouldCoverControlSubcommands() {
        GoalCommand command = new GoalCommand(null, null);

        String joined = String.join(" ", command.examples());
        assertTrue(joined.contains("pause"), "应提供 pause 用法");
        assertTrue(joined.contains("clear"), "应提供 clear 用法");
    }
}
