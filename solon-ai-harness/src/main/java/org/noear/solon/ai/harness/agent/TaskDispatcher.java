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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * 子任务队列调度：一批任务进 FIFO 队列，起「窗口大小」个 worker 自取，跑完一个立刻取下一个。
 *
 * <p>这就是「首发 N 个、完成一个补一个」的全部实现——worker 自己 {@code poll} 下一个，
 * 不需要完成回调，也不需要计数器递减。串行（窗口 1）与并行不再是两个分支，只是一个参数。</p>
 *
 * <p><b>结果必须全量完成</b>：这批任务的 future 由调用方按下标聚合等待，任何一个不完成都会让
 * 调用方永久挂住。因此正常路径、取消路径、提交失败路径、worker 异常退出路径都会把剩余项补成
 * 占位结果。</p>
 *
 * @author bai
 * @since 4.2
 */
final class TaskDispatcher {

    /** 任务体：返回该下标的执行结果。实现应自行兜住异常，不要抛出。 */
    interface TaskBody {
        String run(int index);
    }

    private TaskDispatcher() {
    }

    /**
     * 提交一批任务并驱动执行。
     *
     * @param size        任务总数
     * @param results     定长结果位，按 index 写入
     * @param cap         并发窗口（不足 1 时按 1 处理）
     * @param executor    执行器
     * @param cancelled   取消判定；为 true 后不再开新任务
     * @param body        任务体
     * @param placeholder 未执行/未完成任务的占位结果
     * @return false 表示执行器已关闭，全部任务按占位结束、一个都没跑
     */
    static boolean submit(int size, List<CompletableFuture<String>> results, int cap,
                          ExecutorService executor, BooleanSupplier cancelled,
                          TaskBody body, Function<Integer, String> placeholder) {
        final ConcurrentLinkedQueue<Integer> queue = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < size; i++) {
            if (cancelled.getAsBoolean()) {
                results.get(i).complete(placeholder.apply(i));
            } else {
                queue.add(i);
            }
        }

        if (queue.isEmpty()) {
            return true;
        }

        if (executor.isShutdown()) {
            drain(queue, results, placeholder);
            return false;
        }

        final int workers = Math.min(Math.max(1, cap), queue.size());
        final AtomicInteger alive = new AtomicInteger(workers);

        final Runnable worker = () -> {
            try {
                for (Integer i; (i = queue.poll()) != null; ) {
                    if (cancelled.getAsBoolean()) {
                        break;
                    }

                    results.get(i).complete(body.run(i));
                }
            } catch (Throwable e) {
                // body 约定不抛；真抛了也不能让本 worker 静默退出把队列丢下
            } finally {
                if (alive.decrementAndGet() == 0) {
                    drain(queue, results, placeholder);
                }
            }
        };

        for (int n = 0; n < workers; n++) {
            try {
                executor.execute(worker);
            } catch (Throwable e) {
                // 建线程时可能抛 OOM（Error）：少一个 worker，由计数归零时的补偿收尾
                if (alive.decrementAndGet() == 0) {
                    drain(queue, results, placeholder);
                }
            }
        }

        return true;
    }

    /**
     * 队列里剩下的任务全部按占位结束，保证调用方不会永久等待
     */
    private static void drain(ConcurrentLinkedQueue<Integer> queue,
                              List<CompletableFuture<String>> results,
                              Function<Integer, String> placeholder) {
        for (Integer i; (i = queue.poll()) != null; ) {
            results.get(i).complete(placeholder.apply(i));
        }
    }
}
