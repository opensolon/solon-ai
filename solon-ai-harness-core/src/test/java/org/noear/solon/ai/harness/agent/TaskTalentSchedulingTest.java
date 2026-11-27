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

import features.ai.harness.i18n.HarnessFixtures;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.harness.HarnessEngine;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台子任务的调度契约：父会话队列的并发上限跨多次 task_start 生效、不漏唤醒、执行器不可用时不让人永远等；
 * task_wait 被中断立即返回；坏名字只让那一次 task_start 失败。
 *
 * <p>这些问题的共同特点是「错了也不报错」——上限失效、永久等待、整轮被一个幻觉名字带崩，
 * 都只会表现为难排查的怪现象，因此单独锁定。</p>
 *
 * @author bai
 * @since 4.2
 */
public class TaskTalentSchedulingTest {

    private static SubTaskQueue.Job job(String id, Supplier<String> body) {
        return new SubTaskQueue.Job(id) {
            @Override
            String run() {
                return body.get();
            }

            @Override
            String skipped() {
                return "skipped";
            }
        };
    }

    private static HarnessEngine engine(Path workspace) {
        Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
        return HarnessEngine.of(workspace.toString(), null)
                .sessionProvider((id, parentId, c) -> c ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id))
                .build();
    }

    // ---------- 父会话队列 ----------

    /** 上限按父会话生效：每次只交一个任务，连交多次也不超过上限，超出的排队，完成一个补一个 */
    @Test
    public void capHoldsAcrossSubmits() throws Exception {
        SubTaskQueue<SubTaskQueue.Job> queue = SubTaskQueue.of(InMemoryAgentSession.of("p"), true);
        ExecutorService executor = Executors.newCachedThreadPool();
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        try {
            List<SubTaskQueue.Job> jobs = new ArrayList<>();
            List<Boolean> started = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                SubTaskQueue.Job job = job("t" + i, () -> {
                    peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    active.decrementAndGet();
                    return "ok";
                });
                jobs.add(job);
                started.add(queue.submit(job, 2, executor));
            }

            assertEquals("[true, true, false, false, false]", started.toString());
            Thread.sleep(200);
            assertEquals(2, active.get(), "同时在跑的不超过上限");

            release.countDown();
            for (SubTaskQueue.Job job : jobs) {
                assertEquals("ok", job.result.get(5, TimeUnit.SECONDS));
            }
            assertEquals(2, peak.get());
        } finally {
            executor.shutdownNow();
        }
    }

    /** worker 取到空就退出：退出与新任务入队交错时不能漏掉任务（判空退出与补 worker 在同一把锁里） */
    @Test
    public void noLostWakeupWhenWorkerExits() throws Exception {
        SubTaskQueue<SubTaskQueue.Job> queue = SubTaskQueue.of(InMemoryAgentSession.of("p"), true);
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            for (int i = 0; i < 2000; i++) {
                SubTaskQueue.Job job = job("t" + i, () -> "ok");
                queue.submit(job, 1, executor);
                assertEquals("ok", job.result.get(5, TimeUnit.SECONDS), "第 " + i + " 个任务没人执行");
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /** 执行器不可用：一个 worker 都起不来时，排队的按「未启动」结束，不让 task_wait 永远等 */
    @Test
    public void rejectedExecutorSkipsQueued() throws Exception {
        SubTaskQueue<SubTaskQueue.Job> queue = SubTaskQueue.of(InMemoryAgentSession.of("p"), true);
        SubTaskQueue.Job job = job("t1", () -> "ran");

        assertFalse(queue.submit(job, 2, r -> {
            throw new RejectedExecutionException("closed");
        }));
        assertEquals("skipped", job.result.get(1, TimeUnit.SECONDS));
    }

    /** 轮次结束：排队的不再启动，队列清空；task_stop：只有还没被取走的才能移出 */
    @Test
    public void drainAndUnqueue() throws Exception {
        SubTaskQueue<SubTaskQueue.Job> queue = SubTaskQueue.of(InMemoryAgentSession.of("p"), true);
        List<Runnable> held = new ArrayList<>();
        SubTaskQueue.Job a = job("a", () -> "a");
        SubTaskQueue.Job b = job("b", () -> "b");
        SubTaskQueue.Job c = job("c", () -> "c");
        // 执行器先不跑：worker 已计数但还没取走任何任务
        queue.submit(a, 1, held::add);
        queue.submit(b, 1, held::add);
        queue.submit(c, 1, held::add);
        assertEquals(1, held.size(), "上限 1 只起一个 worker");

        assertTrue(queue.unqueue(b));
        assertEquals("skipped", b.result.getNow(null));
        assertFalse(queue.unqueue(b), "已移出的不能再移");
        assertTrue(queue.isActive("a") && queue.isActive("c"));

        List<SubTaskQueue.Job> all = queue.drain();
        assertEquals(3, all.size(), "本轮全部任务（含已结束的）都交给收尾");
        assertEquals("skipped", a.result.getNow(null));
        assertEquals("skipped", c.result.getNow(null));
        assertTrue(queue.uncollected().isEmpty() && queue.get("a") == null, "收尾后队列清空");

        // 迟到的 worker 拿不到任何任务
        held.get(0).run();
    }

    // ---------- task_wait / task_start ----------

    /**
     * task_wait 被中断必须立即返回。用「永不完成」的任务锁住前提：无法靠任务自己结束逃出等待
     * （中断标志复位后不跳出，下一轮 get 会立即再抛 → 零间隔死循环）。
     */
    @Test
    public void taskWaitReturnsWhenInterrupted(@org.junit.jupiter.api.io.TempDir Path workspace) throws Exception {
        // 执行器先被占住：worker 已提交但永远轮不到，任务一直不结束
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch hold = new CountDownLatch(1);
        executor.execute(() -> {
            try {
                hold.await();
            } catch (InterruptedException ignored) {
            }
        });

        try {
            Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
            // 模型只用来通过预解析，任务不会真的跑
            ChatConfig model = new ChatConfig();
            model.setName("m");
            model.setModel("m");
            model.setApiUrl("http://127.0.0.1:1/v1/chat/completions");
            HarnessEngine engine = HarnessFixtures.builder(workspace)
                    .modelAdd(model)
                    .defaultModel("m")
                    .sessionProvider((id, parentId, c) -> c ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id))
                    .taskExecutor(executor)
                    .build();
            HarnessFixtures.bindTrace(engine.getSession("s1"), HarnessFixtures.trace(null, false));
            TaskTalent.SingleTaskOp op = new TaskTalent.SingleTaskOp();
            op.agent_name = "explore";
            op.description = "d";
            op.prompt = "p";
            ToolResult started = engine.getTaskTalent().taskStart(op, workspace.toString(), "s1", null);
            assertFalse(started.isError(), started.getContent());

            String out = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                Thread.currentThread().interrupt();
                try {
                    return engine.getTaskTalent().taskWait(null, null, "s1", null).getContent();
                } finally {
                    Thread.interrupted();
                }
            });

            assertTrue(out.contains("已中断"), "应说明是中断导致放弃等待: " + out);
            engine.getTaskTalent().endRound(engine.getSession("s1"));
        } finally {
            hold.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * 未知子代理只让这一次 task_start 失败、不入队。
     *
     * <p>回归：{@code AgentManager#getAgent} 对未知名称是<b>抛异常</b>（不是返回 null），
     * 直接调用会让模型幻觉一个 agent 名时抛 IllegalArgumentException。走真实链路，锁住 preparePlan 的判定。</p>
     */
    @Test
    public void unknownAgentOnlyFailsThatStart(@org.junit.jupiter.api.io.TempDir Path workspace) throws Exception {
        HarnessEngine engine = engine(workspace);
        HarnessFixtures.bindTrace(engine.getSession("session-x"), HarnessFixtures.trace(null, false));
        TaskTalent.SingleTaskOp op = new TaskTalent.SingleTaskOp();
        op.agent_name = "no-such-agent-a";
        op.description = "d";
        op.prompt = "p";

        ToolResult r = engine.getTaskTalent().taskStart(op, workspace.toString(), "session-x", null);

        assertTrue(r.isError(), r.getContent());
        assertTrue(r.getContent().contains("未知的子代理类型 'no-such-agent-a'"), "应保留可诊断原因: " + r.getContent());
        SubTaskQueue<SubTaskQueue.Job> queue = SubTaskQueue.of(engine.getSession("session-x"), false);
        assertTrue(queue == null || queue.uncollected().isEmpty(), "失败的不入队");
    }

    // ---------- 开关 ----------

    /**
     * 后台子任务工具跟着子代理走、默认就有：它们是并行的唯一入口，
     * 不能挂在默认关闭的 bash_start 开关上（否则默认安装就没有并行子代理了）
     */
    @Test
    public void backgroundToolsAvailableByDefault(@org.junit.jupiter.api.io.TempDir Path workspace) {
        HarnessEngine engine = engine(workspace);
        TaskTalent talent = engine.getTaskTalent();

        assertFalse(engine.isBashAsyncEnabled(), "前提：bash_start 默认关闭");
        assertEquals("[task, task_start, task_stop, task_wait]", names(talent));
        assertTrue(talent.getInstruction(null).contains("task_start"));
    }

    private static String names(TaskTalent talent) {
        List<String> names = new ArrayList<>();
        talent.getTools(null).forEach(t -> names.add(t.name()));
        java.util.Collections.sort(names);
        return names.toString();
    }

    // ---------- 提示词分条 ----------

    /** 第 1 条结尾缺换行会把后续条目吞进同一段落，直接影响每轮系统提示词 */
    @Test
    public void instructionSeparatesListItems(@org.junit.jupiter.api.io.TempDir Path workspace) {
        HarnessEngine engine = engine(workspace);
        String instruction = engine.getTaskTalent().getInstruction(null);

        assertTrue(instruction.contains("\n1. **优先并行**"), "第 1 条应独占一行");
        assertTrue(instruction.contains("\n2. **原子性**"), "第 2 条应另起一行");
        assertTrue(instruction.contains("\n3. **上下文感知**"), "第 3 条应另起一行");
        assertTrue(instruction.contains("\n5. **后台任务**"), "第 5 条应另起一行");
        assertFalse(instruction.contains("multitask"), "multitask 已下线，提示词不得再提");
    }

    /** 提示词已不再出现「建议 N 个 / 分批」，避免与真实限流语义冲突 */
    @Test
    public void instructionNoLongerMentionsBatchSplit(@org.junit.jupiter.api.io.TempDir Path workspace) {
        String instruction = engine(workspace).getTaskTalent().getInstruction(null);

        assertFalse(instruction.contains("分批"), "已改为排队，不应再要求分批");
        assertFalse(instruction.contains("单次建议"), "不应再给出与实际限流无关的数量建议");
    }

    /** 负数必须被拦截：静默接受会让「配错了」表现为静默降级 */
    @Test
    public void negativeConcurrencyIsRejected(@org.junit.jupiter.api.io.TempDir Path workspace) {
        HarnessEngine engine = engine(workspace);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> engine.setSubagentDefaultConcurrency(-1));
    }
}
