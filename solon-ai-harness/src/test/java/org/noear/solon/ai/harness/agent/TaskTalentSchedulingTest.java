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
import org.noear.solon.ai.harness.HarnessEngine;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * multitask 调度契约测试：结果定长对齐、槽位归还、谁有空配额就提交谁、坏条目不拖垮整批。
 *
 * <p>这些点的共同特点是「错了也不报错」——结果错位、许可漏还、按顺序阻塞、整批被一个
 * 幻觉名字带崩，都只会表现为难排查的怪现象，因此单独锁定。</p>
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
    private static TaskTalent.TaskPlan plan(TaskTalent.MultiTaskOp task, Semaphore slot, int cap) {
        return new TaskTalent.TaskPlan(task, null, null, slot, cap, null);
    }

    private static void submitTask(TaskTalent talent, int index, TaskTalent.TaskPlan plan,
                                   List<CompletableFuture<String>> futures,
                                   Object ctx) throws Exception {
        Method m = TaskTalent.class.getDeclaredMethod("submitTask", int.class, TaskTalent.TaskPlan.class,
                List.class, Class.forName("org.noear.solon.ai.harness.agent.TaskTalent$MultitaskContext"));
        m.setAccessible(true);
        m.invoke(talent, index, plan, futures, ctx);
    }

    private static TaskTalent.MultiTaskOp task(String agent, int index) {
        TaskTalent.MultiTaskOp op = new TaskTalent.MultiTaskOp();
        op.agent_name = agent;
        op.description = "d";
        op.prompt = "p";
        op.index = index;
        return op;
    }

    // ---------- 结果定长对齐 ----------

    /**
     * 核心回归：结果必须按下标 set 而不是顺序 add。
     * 若退回 add，混合模型场景下 &lt;index&gt; 会与 &lt;task_result&gt; 错位。
     */
    @Test
    public void submitTaskWritesResultAtGivenIndex() throws Exception {
        TaskTalent talent = new TaskTalent(null);
        talent.shutdown(); // 执行器已关闭 → 走提交失败分支，不会真的跑任务

        List<CompletableFuture<String>> futures = new ArrayList<>(Collections.nCopies(2, null));

        submitTask(talent, 1, plan(task("general", 2), new Semaphore(1), 1), futures, context(2));

        assertNull(futures.get(0), "下标 0 未被提交，必须保持 null（证明是按下标写）");
        assertNotNull(futures.get(1), "下标 1 应被写入");
        assertTrue(futures.get(1).join().contains("任务提交失败"),
                "执行器已关闭应返回提交失败: " + futures.get(1).join());
        assertTrue(futures.get(1).join().contains("<index>2</index>"), "结果必须携带任务自身 index");
    }

    /** 提交失败时许可必须归还，否则该模型后续全部卡死 */
    @Test
    public void submitFailureReleasesSlot() throws Exception {
        TaskTalent talent = new TaskTalent(null);
        talent.shutdown();

        Semaphore slot = new Semaphore(1);
        slot.acquire(); // 模拟已取到槽位（可用数 0）

        List<CompletableFuture<String>> futures = new ArrayList<>(Collections.nCopies(1, null));

        submitTask(talent, 0, plan(task("general", 1), slot, 1), futures, context(1));

        assertEquals(1, slot.availablePermits(), "提交失败必须归还许可");
    }

    // ---------- 轮询分派：谁有空配额就提交谁 ----------

    /**
     * 核心回归：不能按下标顺序阻塞。
     *
     * <p>构造 waiting = [0(模型A 已满), 1(模型B 空闲)]。若按下标阻塞，
     * 必须先等 A 有许可才能提交 1；轮询方式下 1 应立即被挑出。</p>
     */
    @Test
    public void dispatchPicksWhateverHasCapacity() throws Exception {
        Semaphore busy = new Semaphore(1);
        busy.acquire();                       // 模型 A 已满
        Semaphore idle = new Semaphore(1);    // 模型 B 空闲

        List<TaskTalent.TaskPlan> plans = new ArrayList<>();
        plans.add(plan(task("a", 1), busy, 1));
        plans.add(plan(task("b", 2), idle, 1));
        plans.add(plan(task("c", 3), null, 0)); // 无槽位（模型不可解析）：立即可用

        List<Integer> waiting = new ArrayList<>();
        waiting.add(0);
        waiting.add(1);
        waiting.add(2);

        List<Integer> ready = TaskTalent.tryAcquireReady(waiting, plans);

        assertEquals(java.util.Arrays.asList(1, 2), ready,
                "有空配额的与无槽位的都应被挑出，不能被下标 0 挡住");
        assertEquals(Collections.singletonList(0), waiting, "未取到槽位的任务应留在待分派队列");
        assertEquals(0, idle.availablePermits(), "挑出即已取槽");
        assertEquals(0, busy.availablePermits(), "未取到的槽位不得被动过");
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

        List<TaskTalent.TaskPlan> plans = new ArrayList<>();
        plans.add(plan(task("general", 1), null, 1));

        List<CompletableFuture<String>> futures = new ArrayList<>();
        futures.add(new CompletableFuture<>()); // 永不完成

        Object ctx = context(1);

        Method m = TaskTalent.class.getDeclaredMethod("aggregate", List.class, List.class,
                Class.forName("org.noear.solon.ai.harness.agent.TaskTalent$MultitaskContext"));
        m.setAccessible(true);

        // preemptive：若退回死循环，用例会在 2 秒后超时失败（而不是挂住整个构建）
        String out = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(2), () -> {
                    Thread.currentThread().interrupt();
                    return (String) m.invoke(talent, plans, futures, ctx);
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
                .sessionProvider(InMemoryAgentSession::of)
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
                .sessionProvider(InMemoryAgentSession::of)
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
                .sessionProvider(InMemoryAgentSession::of)
                .build();
        String instruction = engine.getTaskTalent().getInstruction(null);

        assertTrue(!instruction.contains("分批"), "已改为排队，不应再要求分批");
        assertTrue(!instruction.contains("单次建议"), "不应再给出与实际限流无关的数量建议");
    }

    // ---------- 已发布旧名的桥接 ----------

    /** 旧名曾随 v4.0.5 起发布，必须保留桥接并写入同一字段 */
    @SuppressWarnings("deprecation")
    @Test
    public void deprecatedEngineSettersBridgeToSubagentDefaultConcurrency(
            @org.junit.jupiter.api.io.TempDir Path workspace) {
        HarnessEngine engine = HarnessEngine.of(workspace.toString(), null)
                .sessionProvider(InMemoryAgentSession::of)
                .build();

        engine.setMultitaskMaxTasks(3);
        assertEquals(3, engine.getSubagentDefaultConcurrency(), "旧 setter 必须写入新字段");
        assertEquals(3, engine.getMultitaskMaxTasks(), "旧 getter 必须读到新字段");
        assertEquals(3, engine.getTaskTalent().getDefaultConcurrency(),
                "桥接必须同步到执行侧，否则限流仍用旧值");

        engine.setMultitaskMaxConcurrency(4);
        assertEquals(4, engine.getSubagentDefaultConcurrency(), "过渡名必须与新名同效");
        assertEquals(4, engine.getTaskTalent().getDefaultConcurrency());
    }

    /** 负数必须被拦截：静默接受会让「配错了」表现为静默降级 */
    @Test
    public void negativeConcurrencyIsRejected(@org.junit.jupiter.api.io.TempDir Path workspace) {
        HarnessEngine engine = HarnessEngine.of(workspace.toString(), null)
                .sessionProvider(InMemoryAgentSession::of)
                .build();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> engine.setSubagentDefaultConcurrency(-1));
    }
}
