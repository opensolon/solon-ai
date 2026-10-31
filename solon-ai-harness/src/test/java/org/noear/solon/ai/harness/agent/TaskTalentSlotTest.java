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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
 *   <li>槽位分组：同名模型共用配额、无名配置不得被误合并。</li>
 *   <li>执行器必须真并发：core=0 + 无界队列会退化为「并发恒为 1」而不报任何错。</li>
 * </ol>
 *
 * @author bai
 * @since 4.2
 */
public class TaskTalentSlotTest {

    /** semaphoreOf / acquirePolling / taskExecutor 均不触达 engine，传 null 即可 */
    private static TaskTalent talent() {
        return new TaskTalent(null);
    }

    private static Semaphore semaphoreOf(TaskTalent t, ChatConfig config) {
        return t.semaphoreOf(config, t.finalConcurrency(config));
    }

    private static ExecutorService executor(TaskTalent t) throws Exception {
        Field f = TaskTalent.class.getDeclaredField("taskExecutor");
        f.setAccessible(true);
        return (ExecutorService) f.get(t);
    }

    // ---------- 生效上限 ----------

    @Test
    public void nullConfigHasNoSlot() {
        assertNull(semaphoreOf(talent(), null), "模型不可解析 → 无槽位（任务会立即失败）");
    }

    @Test
    public void unsetModelFallsBackToDefaultConcurrency() {
        // 模型未配置（0 = 未设置）→ 用默认值 5 建信号量，这才是护栏所在
        Semaphore sem = semaphoreOf(talent(), new ChatConfig());
        assertNotNull(sem, "模型未配置时应按默认值限流");
        assertEquals(5, sem.availablePermits());
    }

    @Test
    public void defaultZeroFallsBackToBuiltInDefault() {
        TaskTalent t = talent();
        t.setDefaultConcurrency(0);

        Semaphore sem = semaphoreOf(t, new ChatConfig());
        assertNotNull(sem, "0 = 未设置，应归一为缺省值并建信号量（不存在「不限」）");
        assertEquals(TaskTalent.DEFAULT_CONCURRENCY, sem.availablePermits(),
                "0 必须归一为缺省值，而不是不建信号量");
    }

    @Test
    public void aboveMaxClampsToMax() {
        TaskTalent t = talent();
        t.setDefaultConcurrency(TaskTalent.MAX_CONCURRENCY + 5);

        assertEquals(TaskTalent.MAX_CONCURRENCY, t.getDefaultConcurrency());
        assertEquals(TaskTalent.MAX_CONCURRENCY, semaphoreOf(t, new ChatConfig()).availablePermits(),
                "默认值上界必须真实生效在信号量上");
    }

    @Test
    public void modelConfigOverridesDefaultConcurrency() {
        // 核心回归：默认 5 不能把配了 8 的模型卡住（旧"两级取小"的静默降级）
        ChatConfig config = new ChatConfig();
        config.setConcurrency(8);

        assertEquals(8, semaphoreOf(talent(), config).availablePermits(), "模型配置必须优先于默认值");
    }

    // ---------- 槽位复用与分组 ----------

    @Test
    public void sameConfigReusesSameSemaphore() {
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

        // 上限由 ModelSlot.limit 记录，不从 availablePermits() 反推：
        // 否则运行中可用数 1 != 2 会被误判为「上限变了」而重建，限流失效
        assertSame(sem, semaphoreOf(t, config), "运行中许可被占不应触发重建");
        assertEquals(1, sem.availablePermits());
    }

    @Test
    public void capChangeTakesEffect() {
        TaskTalent t = talent();
        ChatConfig config = new ChatConfig();
        config.setConcurrency(2);

        Semaphore first = semaphoreOf(t, config);
        assertEquals(2, first.availablePermits());

        config.setConcurrency(4);
        Semaphore second = semaphoreOf(t, config);

        // 上限变更换新信号量：代价是变更瞬间可能短暂超额，
        // 换来的是省掉一套「可调上限信号量 + 欠账」机制；正常运行期上限不会变
        assertNotSame(first, second, "上限变更后应换新信号量");
        assertEquals(4, second.availablePermits(), "新上限必须真实生效");
    }

    @Test
    public void modelSlotsKeyedByModelNameNotInstance() throws Exception {
        TaskTalent t = talent();

        ChatConfig first = new ChatConfig();
        first.setName("primary");
        first.setConcurrency(3);

        ChatConfig replaced = new ChatConfig();
        replaced.setName("primary");
        replaced.setConcurrency(3);

        // 核心回归：addModel 是以 getNameOrModel() 为键覆盖的，同名配置必然是同一个配额；
        // 若按实例分组，每次热更新都会遗留一个旧实例，modelSlots 无界增长
        assertSame(semaphoreOf(t, first), semaphoreOf(t, replaced), "同名模型必须共用同一槽位");

        Field f = TaskTalent.class.getDeclaredField("modelSlots");
        f.setAccessible(true);
        assertEquals(1, ((java.util.Map<?, ?>) f.get(t)).size(), "不应因替换配置而多出条目");
    }

    @Test
    public void unnamedConfigsAreNotMerged() {
        TaskTalent t = talent();

        // 既无 name 也无 model：无法归属到任何已注册模型，只能退回实例身份，
        // 否则所有无名配置会被误判成同一个配额
        assertNotSame(semaphoreOf(t, new ChatConfig()), semaphoreOf(t, new ChatConfig()),
                "无名配置不得被合并");
    }

    // ---------- acquirePolling / releaseQuietly ----------

    @Test
    public void interruptExitsWaitWithoutHoldingPermit() throws Exception {
        TaskTalent t = talent();

        Semaphore sem = new Semaphore(1);
        sem.acquire(); // 占满，必然进入等待

        Thread worker = new Thread(() -> t.acquirePolling(sem, null));
        worker.start();

        Thread.sleep(300);
        worker.interrupt();
        worker.join(2000);

        assertFalse(worker.isAlive(), "被中断后应退出等待");
        assertEquals(0, sem.availablePermits(), "中断退出不应凭空多出许可");
    }

    @Test
    public void acquireThenReleaseRestoresPermit() {
        TaskTalent t = talent();
        Semaphore sem = new Semaphore(1);

        assertTrue(t.acquirePolling(sem, null));
        assertEquals(0, sem.availablePermits());

        TaskTalent.releaseQuietly(sem);
        assertEquals(1, sem.availablePermits());
    }

    @Test
    public void releaseQuietlyToleratesNull() {
        // 无槽位（未知子代理/模型不可解析）时槽位为 null，释放路径必须安全
        TaskTalent.releaseQuietly(null);
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
            assertTrue(started.await(3, TimeUnit.SECONDS), "两个任务应同时开始执行（并发度为 2）");
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
