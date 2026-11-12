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
package org.noear.solon.ai.harness.agent;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.harness.HarnessEngine;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * multitask 调度契约测试：取消后聚合不挂住、坏条目不拖垮整批。
 *
 * <p>这两点的共同特点是「错了也不报错」——永久等待、整批被一个幻觉名字带崩，
 * 都只会表现为难排查的怪现象，因此单独锁定。队列调度的语义见 {@link SubtaskSchedulerTest}。</p>
 *
 * @author bai
 * @since 4.2
 */
public class TaskTalentSchedulingTest {

    /** TaskPlan / MultitaskContext 都是私有内部结构，用反射构造，不重复生产逻辑 */
    private static Object context(int total) throws Exception {
        Class<?> ctxClass = Class.forName("org.noear.solon.ai.harness.agent.TaskTalent$MultitaskContext");
        Constructor<?> ctor = ctxClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);

        return ctor.newInstance(null, null, null, null, total,
                new AtomicBoolean(), new AtomicBoolean(), new AtomicBoolean());
    }

    /** agentDefinition 为 null 时 runTask 走「未知子代理」分支，不触达引擎 */
    private static TaskTalent.TaskPlan plan(TaskTalent.MultiTaskOp task) {
        return new TaskTalent.TaskPlan(task, null, null);
    }

    private static TaskTalent.MultiTaskOp task(String agent, int index) {
        TaskTalent.MultiTaskOp op = new TaskTalent.MultiTaskOp();
        op.agent_name = agent;
        op.description = "d";
        op.prompt = "p";
        op.index = index;
        return op;
    }

    // ---------- 取消 ----------

    /**
     * 中断后 aggregate 必须立即返回。
     *
     * <p>回归：原先 InterruptedException 分支只重置了中断标志、没有再跳出，
     * 而复位后的标志会让下一轮 {@code get} 立即再抛 → 零间隔死循环
     * （CPU 打满、任务永不结束）。这里用「永不完成」的 future 锁住前提：
     * 无法靠任务自己结束来逃出循环。</p>
     */
    @Test
    public void aggregateReturnsWhenInterrupted() throws Exception {
        TaskTalent talent = new TaskTalent(null);

        List<CompletableFuture<String>> futures = new ArrayList<>();
        futures.add(new CompletableFuture<>()); // 永不完成

        Object ctx = context(1);

        Method m = TaskTalent.class.getDeclaredMethod("aggregate", List.class,
                Class.forName("org.noear.solon.ai.harness.agent.TaskTalent$MultitaskContext"));
        m.setAccessible(true);

        // preemptive：若退回死循环，用例会在 2 秒后超时失败（而不是挂住整个构建）
        String out = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(2), () -> {
                    Thread.currentThread().interrupt();
                    return ((ToolResult) m.invoke(talent, futures, ctx)).getContent();
                });

        assertTrue(out.contains("已中断"), "应说明是中断导致放弃等待: " + out);
    }

    /**
     * 未知子代理只废这一条：整批不能被一个坏名字带崩。
     *
     * <p>回归：{@code AgentManager#getAgent} 对未知名称是<b>抛异常</b>（不是返回 null），
     * 直接调用会让模型幻觉一个 agent 名时整个 multitask 抛 IllegalArgumentException。
     * 本用例走真实链路（不伪造 TaskPlan），因此能真正锁住 preparePlan 的判定。</p>
     */
    @Test
    public void unknownAgentOnlyFailsItsOwnTask(@org.junit.jupiter.api.io.TempDir Path workspace) {
        HarnessEngine engine = HarnessEngine.of(workspace.toString(), null)
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .build();

        List<TaskTalent.MultiTaskOp> tasks = new ArrayList<>();
        tasks.add(task("no-such-agent-a", 1));
        tasks.add(task("no-such-agent-b", 2));

        String out = engine.getTaskTalent().multitask(tasks, workspace.toString(), "session-x");

        assertTrue(out.contains("<index>1</index>"), "第 1 条应保留在外壳里: " + out);
        assertTrue(out.contains("<index>2</index>"), "第 2 条应保留在外壳里: " + out);
        assertTrue(out.contains("未知的子代理类型 'no-such-agent-a'"), "应保留可诊断原因: " + out);
        assertTrue(out.contains("未知的子代理类型 'no-such-agent-b'"), "两条都要各自失败: " + out);
    }

    // ---------- 提示词分条 ----------

    /** 第 1 条结尾缺换行会把后续条目吞进同一段落，直接影响每轮系统提示词 */
    @Test
    public void instructionSeparatesListItems(@org.junit.jupiter.api.io.TempDir Path workspace) {
        HarnessEngine engine = HarnessEngine.of(workspace.toString(), null)
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .build();
        String instruction = engine.getTaskTalent().getInstruction(null);

        assertTrue(instruction.contains("\n1. **优先并行**"), "第 1 条应独占一行");
        assertTrue(instruction.contains("\n2. **原子性**"), "第 2 条应另起一行");
        assertTrue(instruction.contains("\n3. **上下文感知**"), "第 3 条应另起一行");
        assertTrue(!instruction.contains("节省时间。2."), "第 1 条结尾不得吞掉第 2 条");
    }

    /** 提示词已不再出现「建议 N 个 / 分批」，避免与真实限流语义冲突 */
    @Test
    public void instructionNoLongerMentionsBatchSplit(@org.junit.jupiter.api.io.TempDir Path workspace) {
        HarnessEngine engine = HarnessEngine.of(workspace.toString(), null)
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .build();
        String instruction = engine.getTaskTalent().getInstruction(null);

        assertTrue(!instruction.contains("分批"), "已改为排队，不应再要求分批");
        assertTrue(!instruction.contains("单次建议"), "不应再给出与实际限流无关的数量建议");
    }

    // ---------- 已发布旧名的桥接 ----------


    /** 负数必须被拦截：静默接受会让「配错了」表现为静默降级 */
    @Test
    public void negativeConcurrencyIsRejected(@org.junit.jupiter.api.io.TempDir Path workspace) {
        HarnessEngine engine = HarnessEngine.of(workspace.toString(), null)
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .build();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> engine.setSubagentDefaultConcurrency(-1));
    }
}
