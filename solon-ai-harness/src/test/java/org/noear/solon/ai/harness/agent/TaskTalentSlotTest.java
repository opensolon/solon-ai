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
import org.noear.solon.ai.chat.ChatConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * multitask 并行槽位与执行器契约测试
 *
 * <p>锁定三个最容易静默失效的点：</p>
 * <ol>
 *   <li>默认值回退：模型未配置时按默认值限流，模型配了就以模型为准（不能反过来）。</li>
 *   <li>上限判定不能从 availablePermits 反推：运行中许可被占时可用数必然小于上限。</li>
 *   <li>执行器必须真并发：core=0 + 无界队列会退化为「并发恒为 1」而不报任何错。</li>
 * </ol>
 *
 * @author bai
 * @since 4.2
 */
public class TaskTalentSlotTest {

    private static TaskTalent talent() {
        // semaphoreOf / acquireAll / taskExecutor 均不触达 engine，传 null 即可
        return new TaskTalent(null);
    }

    private static Semaphore semaphoreOf(TaskTalent t, ChatConfig config) throws Exception {
        Method m = TaskTalent.class.getDeclaredMethod("semaphoreOf", ChatConfig.class);
        m.setAccessible(true);
        return (Semaphore) m.invoke(t, config);
    }

    private static boolean acquirePolling(TaskTalent t, Semaphore sem) throws Exception {
        Method m = TaskTalent.class.getDeclaredMethod("acquirePolling", Semaphore.class,
                Class.forName("reactor.core.publisher.FluxSink"));
        m.setAccessible(true);
        return (Boolean) m.invoke(t, sem, null);
    }

    private static ExecutorService executor(TaskTalent t) throws Exception {
        Field f = TaskTalent.class.getDeclaredField("taskExecutor");
        f.setAccessible(true);
        return (ExecutorService) f.get(t);
    }

    // ---------- semaphoreOf ----------

    @Test
    public void nullConfigMeansUnlimited() throws Exception {
        assertNull(semaphoreOf(talent(), null));
    }

    @Test
    public void unsetModelFallsBackToDefaultConcurrency() throws Exception {
        // 模型未配置（0 = 未设置）→ 用默认值 5 建信号量，这才是护栏所在
        ChatConfig config = new ChatConfig();
        Semaphore sem = semaphoreOf(talent(), config);
        assertNotNull(sem, "模型未配置时应按默认值限流");
        assertEquals(5, sem.availablePermits());
    }

    @Test
    public void defaultZeroMeansUnlimited() throws Exception {
        TaskTalent t = talent();
        t.setDefaultConcurrency(0);

        ChatConfig config = new ChatConfig();
        assertNull(semaphoreOf(t, config), "默认值 0 表示整体不限，不应建信号量");
    }

    @Test
    public void modelConfigOverridesDefaultConcurrency() throws Exception {
        // 核心回归：默认 5 不能把配了 8 的模型卡住（旧"两级取小"的静默降级）
        ChatConfig config = new ChatConfig();
        config.setConcurrency(8);

        Semaphore sem = semaphoreOf(talent(), config);
        assertEquals(8, sem.availablePermits(), "模型配置必须优先于默认值");
    }

    @Test
    public void sameConfigReusesSameSemaphore() throws Exception {
        TaskTalent t = talent();
        ChatConfig config = new ChatConfig();
        config.setConcurrency(3);

        Semaphore first = semaphoreOf(t, config);
        assertSame(first, semaphoreOf(t, config), "上限未变时必须复用同一信号量");
        assertEquals(3, first.availablePermits());
    }

    @Test
    public void semaphoreNotRecreatedWhilePermitsInUse() throws Exception {
        TaskTalent t = talent();
        ChatConfig config = new ChatConfig();
        config.setConcurrency(2);

        Semaphore sem = semaphoreOf(t, config);
        sem.acquire();

        // 关键回归：若用 availablePermits() 反推上限，此刻可用数为 1 != 2，
        // 会被误判为「上限变了」而重建，限流直接失效
        Semaphore again = semaphoreOf(t, config);
        assertSame(sem, again, "运行中许可被占不应触发重建");
        assertEquals(1, again.availablePermits());
    }

    @Test
    public void capChangeReplacesSemaphore() throws Exception {
        TaskTalent t = talent();
        ChatConfig config = new ChatConfig();
        config.setConcurrency(2);

        Semaphore first = semaphoreOf(t, config);
        config.setConcurrency(4);
        Semaphore second = semaphoreOf(t, config);

        assertFalse(first == second, "上限变更应换新信号量");
        assertEquals(4, second.availablePermits());
    }

    // ---------- acquirePolling / releaseQuietly ----------

    @Test
    public void interruptExitsWaitWithoutHoldingPermit() throws Exception {
        TaskTalent t = talent();

        Semaphore sem = new Semaphore(1);
        sem.acquire(); // 占满，必然进入等待

        Thread worker = new Thread(() -> {
            try {
                acquirePolling(t, sem);
            } catch (Exception ignored) {
            }
        });
        worker.start();

        Thread.sleep(300);
        worker.interrupt();
        worker.join(2000);

        assertFalse(worker.isAlive(), "被中断后应退出等待");
        assertEquals(0, sem.availablePermits(), "中断退出不应凭空多出许可");
    }

    @Test
    public void acquireThenReleaseRestoresPermit() throws Exception {
        TaskTalent t = talent();

        Semaphore sem = new Semaphore(1);

        assertTrue(acquirePolling(t, sem));
        assertEquals(0, sem.availablePermits());

        Method m = TaskTalent.class.getDeclaredMethod("releaseQuietly", Semaphore.class);
        m.setAccessible(true);
        m.invoke(null, sem);

        assertEquals(1, sem.availablePermits());
    }

    @Test
    public void releaseQuietlyToleratesNull() throws Exception {
        // 不限并发时槽位为 null，释放路径必须安全（否则每个不限模型都 NPE）
        Method m = TaskTalent.class.getDeclaredMethod("releaseQuietly", Semaphore.class);
        m.setAccessible(true);
        m.invoke(null, new Object[]{null});
    }

    // ---------- 执行器：必须真并发 ----------

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
            assertTrue(started.await(3, TimeUnit.SECONDS),
                    "两个任务应同时开始执行（并发度为 2）");
        } finally {
            release.countDown();
            t.shutdown();
        }
    }

    @Test
    public void shutdownRejectsFurtherSubmits() throws Exception {
        TaskTalent t = talent();
        ExecutorService exec = executor(t);

        t.shutdown();

        boolean rejected = false;
        try {
            exec.execute(() -> {
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            rejected = true;
        }

        assertTrue(rejected, "关闭后应拒绝新任务，由调用方归还许可并给出占位结果");
    }
}
