/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package features.ai.harness;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.agent.TaskTalent;

import java.lang.reflect.Field;
import java.util.ArrayList;

/**
 * TaskTalent 防死锁守卫契约测试
 *
 * 1. 深度守卫：子代理线程内再调用 task/multitask 必须被拒绝（返回 ERROR 字符串，不抛异常、不触达引擎）
 * 2. 顶层不误伤：无守卫标记时应越过守卫触达引擎
 *
 * @author bai
 * @since 4.2
 */
public class TaskTalentGuardTest {

    /**
     * 通过反射在当前线程打上"子代理深度标记"，模拟子代理内再次委派的场景
     */
    private static void markSubagentThread() throws Exception {
        Field depthField = TaskTalent.class.getDeclaredField("TASK_DEPTH");
        depthField.setAccessible(true);
        ThreadLocal<Boolean> depth = (ThreadLocal<Boolean>) depthField.get(null);
        depth.set(Boolean.TRUE);
    }

    private static void clearSubagentThread() throws Exception {
        Field depthField = TaskTalent.class.getDeclaredField("TASK_DEPTH");
        depthField.setAccessible(true);
        ThreadLocal<Boolean> depth = (ThreadLocal<Boolean>) depthField.get(null);
        depth.remove();
    }

    @Test
    public void nestedTaskRejected() throws Exception {
        markSubagentThread();
        try {
            TaskTalent talent = new TaskTalent(null); // 守卫先于引擎触达，null 引擎可证明未触达
            TaskTalent.SingleTaskOp op = new TaskTalent.SingleTaskOp();
            op.agent_name = "general";
            op.prompt = "x";
            op.description = "x";

            String result = talent.task(op, "/tmp", "session-x");

            Assertions.assertNotNull(result);
            Assertions.assertTrue(result.startsWith("ERROR:"), "应返回 ERROR 字符串: " + result);
            Assertions.assertTrue(result.contains("不允许再委派"), "应说明嵌套被拒: " + result);
        } finally {
            clearSubagentThread();
        }
    }

    @Test
    public void nestedMultitaskRejected() throws Exception {
        markSubagentThread();
        try {
            TaskTalent talent = new TaskTalent(null);

            java.util.List<TaskTalent.MultiTaskOp> tasks = new ArrayList<>();
            TaskTalent.MultiTaskOp op = new TaskTalent.MultiTaskOp();
            op.agent_name = "general";
            op.prompt = "x";
            op.description = "x";
            tasks.add(op);

            String result = talent.multitask(tasks, "/tmp", "session-x");

            Assertions.assertNotNull(result);
            Assertions.assertTrue(result.startsWith("ERROR:"), "应返回 ERROR 字符串: " + result);
            Assertions.assertTrue(result.contains("不允许再委派"), "应说明嵌套被拒: " + result);
        } finally {
            clearSubagentThread();
        }
    }

    @Test
    public void topTaskNotBlockedByGuard() throws Exception {
        // 清空标记（模拟主代理线程），task() 不应被守卫拦截——但会因 null 引擎在引擎触达处抛 NPE
        // 抛 NPE 恰恰证明执行越过了守卫（守卫未误伤顶层调用）
        clearSubagentThread();
        TaskTalent talent = new TaskTalent(null);
        TaskTalent.SingleTaskOp op = new TaskTalent.SingleTaskOp();
        op.agent_name = "general";
        op.prompt = "x";
        op.description = "x";

        Assertions.assertThrows(NullPointerException.class, () -> talent.task(op, "/tmp", "session-x"),
                "无守卫标记时应越过守卫触达引擎（null 引擎抛 NPE 即为证据）");
    }
}
