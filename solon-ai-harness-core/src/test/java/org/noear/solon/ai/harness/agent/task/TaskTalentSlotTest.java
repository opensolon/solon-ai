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

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子任务执行器契约测试
 *
 * <p>队列已改为「每次调用起 cap 个 worker」，这里只锁住执行器本身的两个坑：</p>
 * <ol>
 *   <li>必须真并发：core=0 + 无界队列会退化为「并发恒为 1」而不报任何错。</li>
 *   <li>关闭后拒绝提交：worker 不会凭空冒出来，调用方据 {@code submit} 的返回值给占位结果。</li>
 * </ol>
 *
 * @author bai
 * @since 4.2
 */
public class TaskTalentSlotTest {

    /** taskExecutor 不触达 engine，传 null 即可 */
    private static TaskTalent talent() {
        return new TaskTalent(null);
    }

    private static ExecutorService executor(TaskTalent t) throws Exception {
        Field f = TaskTalent.class.getDeclaredField("taskExecutor");
        f.setAccessible(true);
        return (ExecutorService) f.get(t);
    }

    @Test
    public void aboveMaxClampsToMax() {
        TaskTalent t = talent();
        t.setDefaultConcurrency(TaskTalent.MAX_CONCURRENCY + 5);

        assertEquals(TaskTalent.MAX_CONCURRENCY, t.getDefaultConcurrency());
    }

    @Test
    public void executorRunsTasksConcurrently() throws Exception {
        TaskTalent t = talent();
        ExecutorService exec = executor(t);
        assertNotNull(exec);

        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        Runnable body = () -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        try {
            exec.execute(body);
            exec.execute(body);

            // 若队列把第二个任务排队而非新建线程，这里会等待超时
            assertTrue(started.await(3, TimeUnit.SECONDS), "两个 worker 应同时开始执行（cap=2）");
        } finally {
            release.countDown();
            t.shutdown();
        }
    }

    @Test
    public void shutdownRejectsFurtherSubmits() {
        TaskTalent t = talent();

        t.shutdown();

        boolean rejected;
        try {
            executor(t).execute(() -> {
            });
            rejected = false;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            rejected = true;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        assertTrue(rejected, "关闭后应拒绝新任务，由调用方给出占位结果");
    }
}
