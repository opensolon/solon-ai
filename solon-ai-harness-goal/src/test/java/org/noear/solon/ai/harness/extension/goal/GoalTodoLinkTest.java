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
 * Todo/Goal 完成判定联动单元测试（阶段一）
 *
 * <p>验证 {@link GoalEngine#countUnfinishedCheckboxes(String)} 的 checkbox 解析：
 * 该方法为 goal_update(complete) 的联动门禁提供未完成项统计，若返回 &gt; 0 则拒绝完成。
 *
 * @since 3.9.3
 */
class GoalTodoLinkTest {

    @Test
    void nullContentReturnsZero() {
        assertEquals(0, GoalEngine.countUnfinishedCheckboxes(null));
    }

    @Test
    void emptyContentReturnsZero() {
        assertEquals(0, GoalEngine.countUnfinishedCheckboxes(""));
    }

    @Test
    void noCheckboxLinesReturnsZero() {
        String content = "# 标题\n普通文本\n- 无状态列表项\n";
        assertEquals(0, GoalEngine.countUnfinishedCheckboxes(content));
    }

    @Test
    void allDoneReturnsZero() {
        String content = "- [x] 任务一\n- [x] 任务二\n- [X] 任务三（大写）\n";
        assertEquals(0, GoalEngine.countUnfinishedCheckboxes(content));
    }

    @Test
    void pendingCounted() {
        String content = "- [ ] 待办一\n- [ ] 待办二\n";
        assertEquals(2, GoalEngine.countUnfinishedCheckboxes(content));
    }

    @Test
    void inProgressCounted() {
        String content = "- [/] 进行中\n";
        assertEquals(1, GoalEngine.countUnfinishedCheckboxes(content));
    }

    @Test
    void mixedStatesCountsOnlyUnfinished() {
        String content = "## 分组 A\n"
                + "- [x] 已完成\n"
                + "- [/] 进行中\n"
                + "- [ ] 待办\n"
                + "## 分组 B\n"
                + "- [x] 已完成2\n"
                + "- [ ] 待办2\n";
        // 未完成 = 1 个 [/] + 2 个 [ ] = 3
        assertEquals(3, GoalEngine.countUnfinishedCheckboxes(content));
    }

    @Test
    void indentedCheckboxesCounted() {
        // 缩进的 checkbox（子任务）也应被识别（trim 后判断）
        String content = "- [x] 父任务\n    - [ ] 子任务一\n    - [/] 子任务二\n";
        assertEquals(2, GoalEngine.countUnfinishedCheckboxes(content));
    }

    @Test
    void malformedCheckboxIgnored() {
        // 缺右括号、格式不符的行不计入
        String content = "- [ 未闭合\n- [] 空括号\n-[ ] 缺空格\n- [ ] 合法待办\n";
        assertEquals(1, GoalEngine.countUnfinishedCheckboxes(content));
    }
}
