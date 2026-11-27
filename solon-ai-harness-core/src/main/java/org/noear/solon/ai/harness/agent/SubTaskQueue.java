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
package org.noear.solon.ai.harness.agent;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.lang.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 后台子任务队列（{@code task_start} 用）：每个父会话一个，放在父会话的 {@code attrs()} 里。
 *
 * <p><b>为什么按父会话</b>：{@code task_start} 一次只交一个任务，模型连续调用多次时，这些任务要进同一个队列、
 * 共用同一个并发上限，否则上限形同虚设。{@code attrs()} 只在内存里、不进快照，正好与「任务跟着这一轮走」一致。</p>
 *
 * <p><b>调度形态</b>：FIFO 队列 + 不超过上限的 worker 自取，跑完一个立刻取下一个，
 * 取到空就退出；新任务入队时存活 worker 不足上限就补起一个。「判空退出（存活数减一）」与「入队后判断要不要补」
 * 在同一把锁（本对象）里，不会出现任务入队却没有 worker 的漏唤醒。</p>
 *
 * <p>锁只用本对象，<b>不用</b> {@code session.attrs()} 的监视器：那是 queue / steer / dispatch 等模块共用的会话锁。
 * 等待结果用各任务自己的 {@link CompletableFuture}，不在锁上等。</p>
 *
 * <p>本类只管排队与收结果；子代理怎么跑、怎么停、结果怎么定口径都在 {@link TaskTalent}。</p>
 *
 * @author noear
 * @since 4.2.0
 */
final class SubTaskQueue<J extends SubTaskQueue.Job> {
    /** 父会话属性键（包内私有约定，不对外） */
    private static final String ATTR = "_harness_subtask_queue_";

    /**
     * 本轮全部后台任务（含已收回的，轮次结束时统一定状态），按启动顺序。同一任务在本轮里可以被续跑多次
     * （每次一个 task_start 调用），每次都要定状态，所以按调用存，不能按 task_id
     */
    private final List<J> all = new ArrayList<>();
    /** task_id → 该任务本轮最近一次启动（task_wait / task_stop 按 task_id 找、续跑撞车检查） */
    private final Map<String, J> jobs = new LinkedHashMap<>();
    /** 排队中的任务 */
    private final ArrayDeque<J> pending = new ArrayDeque<>();
    /** 存活的 worker 数 */
    private int workers;

    private SubTaskQueue() {
    }

    /**
     * 取（或创建）父会话的后台任务队列。一个会话只有一种任务类型（TaskTalent 的后台任务），按调用方的类型取。
     *
     * @param create false 时没有就返回 null
     */
    @SuppressWarnings("unchecked")
    static <J extends Job> @Nullable SubTaskQueue<J> of(AgentSession session, boolean create) {
        if (session == null) {
            return null;
        }

        Object value = session.attrs().get(ATTR);
        if (value instanceof SubTaskQueue) {
            return (SubTaskQueue<J>) value;
        }

        if (create == false) {
            return null;
        }

        SubTaskQueue<J> created = new SubTaskQueue<>();
        Object old = session.attrs().putIfAbsent(ATTR, created);
        return old instanceof SubTaskQueue ? (SubTaskQueue<J>) old : created;
    }

    /** 一个后台任务：子类给出任务体与「没启动就被移出」时的结果 */
    abstract static class Job {
        final String taskId;
        final CompletableFuture<String> result = new CompletableFuture<>();
        /** 已被 worker 取走（队列锁内写） */
        boolean taken;
        /** 结果已被模型收回（task_wait / task_stop；队列锁内读写） */
        boolean collected;

        Job(String taskId) {
            this.taskId = taskId;
        }

        /** 任务体（worker 线程）：返回结果文本。约定不抛（抛了按异常完成） */
        abstract String run();

        /** 没启动就被移出队列时的结果（执行器不可用、task_stop、轮次结束） */
        abstract String skipped();
    }

    /**
     * 入队并按需补起 worker。
     *
     * @param cap 并发上限（不足 1 按 1）
     * @return true = 有空位、已开始执行；false = 在排队
     */
    synchronized boolean submit(J job, int cap, Executor executor) {
        all.add(job);
        jobs.put(job.taskId, job);
        pending.add(job);

        if (workers >= Math.max(1, cap)) {
            return false;
        }

        workers++;
        try {
            executor.execute(this::work);
            return true;
        } catch (Throwable e) {
            // 执行器已关闭、线程用尽或建线程 OOM：少一个 worker；一个都没有时排队的任务按「未启动」结束，不让人永远等
            workers--;
            if (workers == 0) {
                skipPending();
            }
            return false;
        }
    }

    private void work() {
        while (true) {
            final J job;
            synchronized (this) {
                job = pending.poll();
                if (job == null) {
                    workers--;
                    return;
                }
                job.taken = true;
            }

            try {
                job.result.complete(job.run());
            } catch (Throwable e) {
                // 任务体约定不抛；真抛了也要让等的人收到结果，worker 继续取下一个
                job.result.completeExceptionally(e);
            }
        }
    }

    /** 队列锁内调用：排队的全部按「未启动」结束 */
    private void skipPending() {
        for (J job; (job = pending.poll()) != null; ) {
            skip(job);
        }
    }

    private static void skip(Job job) {
        try {
            job.result.complete(job.skipped());
        } catch (Throwable e) {
            job.result.completeExceptionally(e);
        }
    }

    /** 本轮的任务；null = 不存在 */
    synchronized @Nullable J get(String taskId) {
        return taskId == null ? null : jobs.get(taskId);
    }

    /** 未结束的任务里是否有这个 ID（续跑撞车检查） */
    synchronized boolean isActive(String taskId) {
        J job = jobs.get(taskId);
        return job != null && job.result.isDone() == false;
    }

    /** 还没被收回的任务，按启动顺序 */
    synchronized List<J> uncollected() {
        List<J> list = new ArrayList<>();
        for (J job : all) {
            if (job.collected == false) {
                list.add(job);
            }
        }
        return list;
    }

    /** 标记已收回 */
    synchronized void collect(J job) {
        job.collected = true;
    }

    /** 是否已被 worker 取走（取走后由任务体自己判断还能不能启动） */
    synchronized boolean isTaken(J job) {
        return job.taken;
    }

    /**
     * 还在排队就移出并按 {@link Job#skipped()} 结束。
     *
     * @return true = 已移出（之后不会再启动）
     */
    synchronized boolean unqueue(J job) {
        if (job.taken || pending.remove(job) == false) {
            return false;
        }
        skip(job);
        return true;
    }

    /**
     * 轮次结束：摘下本轮全部任务（排队的不再启动，已按 {@link Job#skipped()} 结束），队列清空。
     *
     * @return 本轮全部任务（含已收回的），按启动顺序
     */
    synchronized List<J> drain() {
        skipPending();
        List<J> out = new ArrayList<>(all);
        all.clear();
        jobs.clear();
        return out;
    }
}
