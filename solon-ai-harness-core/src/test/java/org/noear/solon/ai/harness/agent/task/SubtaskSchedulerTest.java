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
package org.noear.solon.ai.harness.agent.task;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 队列调度契约测试
 *
 * <p>锁定四件「错了也不报错」的事：窗口大小真实生效、完成即补位（不必等满一个节拍）、
 * 任何退出路径都不留下未完成的 future（否则调用方永久挂住）、执行器关闭时一个任务都不跑。</p>
 *
 * @author bai
 * @since 4.2
 */
public class SubtaskSchedulerTest {

    private static List<CompletableFuture<String>> results(int size) {
        List<CompletableFuture<String>> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(new CompletableFuture<String>());
        }
        return list;
    }

    private static String placeholder(int i) {
        return "未启动-" + i;
    }

    @Test
    public void windowIsRespectedAndRefillsOnCompletion() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        List<CompletableFuture<String>> results = results(3);

        CountDownLatch[] started = {new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1)};
        CountDownLatch release = new CountDownLatch(1);

        try {
            SubTaskScheduler.submit(3, results, 2, pool, () -> false, i -> {
                started[i].countDown();
                await(release); // 前两个都卡住，窗口被占满
                return "r" + i;
            }, SubtaskSchedulerTest::placeholder);

            assertTrue(started[0].await(2, TimeUnit.SECONDS), "窗口 2：第 1 个应立刻开跑");
            assertTrue(started[1].await(2, TimeUnit.SECONDS), "窗口 2：第 2 个应同时开跑");
            assertFalse(started[2].await(200, TimeUnit.MILLISECONDS), "窗口占满时第 3 个必须排队");

            release.countDown();

            // 完成即补位：不等任何节拍，第 3 个应马上被本 worker 取走
            assertTrue(started[2].await(1, TimeUnit.SECONDS), "有任务完成后应立刻补上下一个");
            assertEquals("r2", results.get(2).get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    public void capOneRunsSerially() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        List<CompletableFuture<String>> results = results(3);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();

        try {
            SubTaskScheduler.submit(3, results, 1, pool, () -> false, i -> {
                maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                sleep(50);
                running.decrementAndGet();
                return "r" + i;
            }, SubtaskSchedulerTest::placeholder);

            for (int i = 0; i < 3; i++) {
                assertEquals("r" + i, results.get(i).get(3, TimeUnit.SECONDS));
            }
            assertEquals(1, maxRunning.get(), "cap=1 必须串行，不能有两个任务同时在跑");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void canceledBatchCompletesEveryFuture() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        List<CompletableFuture<String>> results = results(2);
        AtomicBoolean cancelled = new AtomicBoolean(true);

        try {
            SubTaskScheduler.submit(2, results, 2, pool, cancelled::get,
                    i -> "不该被执行", SubtaskSchedulerTest::placeholder);

            // 关键：聚合方按下标等待，任何一项不完成都会让主流程永久挂住
            assertEquals("未启动-0", results.get(0).get(1, TimeUnit.SECONDS));
            assertEquals("未启动-1", results.get(1).get(1, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void canceledMidwayCompletesRemainingFutures() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        List<CompletableFuture<String>> results = results(4);
        AtomicBoolean cancelled = new AtomicBoolean(false);

        try {
            SubTaskScheduler.submit(4, results, 1, pool, cancelled::get, i -> {
                cancelled.set(true); // 第一个任务跑完后主流程被取消
                return "r" + i;
            }, SubtaskSchedulerTest::placeholder);

            assertEquals("r0", results.get(0).get(2, TimeUnit.SECONDS));
            // 取消后剩余任务必须立刻补占位，而不是留在队列里等
            assertEquals("未启动-3", results.get(3).get(1, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void shuttingDownExecutorFailsWithoutRunningAnything() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        pool.shutdown();

        List<CompletableFuture<String>> results = results(2);

        boolean submitted = SubTaskScheduler.submit(2, results, 2, pool, () -> false,
                i -> "不该被执行", SubtaskSchedulerTest::placeholder);

        assertFalse(submitted, "执行器已关闭时应如实返回 false");
        assertEquals("未启动-0", results.get(0).get(1, TimeUnit.SECONDS));
        assertEquals("未启动-1", results.get(1).get(1, TimeUnit.SECONDS));
    }

    @Test
    public void throwingBodyDoesNotStrandRemainingTasks() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        List<CompletableFuture<String>> results = results(3);

        try {
            SubTaskScheduler.submit(3, results, 1, pool, () -> false, i -> {
                if (i == 0) {
                    throw new IllegalStateException("boom");
                }
                return "r" + i;
            }, SubtaskSchedulerTest::placeholder);

            // worker 因异常退出后由补偿收尾：剩余任务补占位，聚合方不会挂住
            assertEquals("未启动-1", results.get(1).get(2, TimeUnit.SECONDS));
            assertEquals("未启动-2", results.get(2).get(2, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
