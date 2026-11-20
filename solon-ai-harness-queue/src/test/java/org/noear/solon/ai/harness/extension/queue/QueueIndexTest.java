package org.noear.solon.ai.harness.extension.queue;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.InterruptMode;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.session.SessionOwnership;
import org.noear.solon.ai.mount.source.FileMountSource;
import org.noear.solon.ai.mount.source.FindOptions;
import org.noear.solon.ai.mount.source.MountCapabilities;
import org.noear.solon.ai.mount.source.MountEntry;
import org.noear.solon.ai.mount.source.MountSource;
import org.noear.solon.ai.mount.source.MoveOptions;
import org.noear.solon.ai.mount.source.WriteOptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 积压索引的一致性与规模契约。
 *
 * <ol>
 *   <li>会话换节点后再入队，标记必须重新写（不能被进程内的旧判断跳过）；</li>
 *   <li>「判定队列已空」与「删标记」之间插不进入队（同一把会话锁）；</li>
 *   <li>每轮结束都会走的注销只删单条：读目录失败不会被放大；同一会话对象确认注销后不再碰磁盘；</li>
 *   <li>死标记（会话已不在、队列早空、删除失败）会收敛：下次观察或恢复时清掉；</li>
 *   <li>先写标记再入队：入队保存快照时标记已在（两步之间崩溃不会留下「有积压、没标记」）；</li>
 *   <li>引擎关闭后不再领取、不重新认领租约；积压不挡引擎回收；</li>
 *   <li>一个实例只服务一个运行中的引擎。</li>
 * </ol>
 */
class QueueIndexTest {
    @TempDir
    Path tmp;

    private final Map<String, AgentSession> store = new ConcurrentHashMap<>();

    private AgentSession sessionOf(String id, boolean create) {
        return create ? store.computeIfAbsent(id, InMemoryAgentSession::of) : store.get(id);
    }

    private HarnessEngine engine(MountSource state) throws IOException {
        return engine(state, this::sessionOf, new QueueExtension());
    }

    private HarnessEngine engine(MountSource state, AgentSessionProvider sessions, QueueExtension queue) throws IOException {
        return engine(state, sessions, queue, null);
    }

    private HarnessEngine engine(MountSource state, AgentSessionProvider sessions, QueueExtension queue,
                                 SessionOwnership ownership) throws IOException {
        // 假模型（不可达地址）：只走队列门面与中断，不开流
        ChatConfig model = new ChatConfig();
        model.setName("fake");
        model.setModel("fake-model");
        model.setApiUrl("http://localhost:1/v1");
        model.setApiKey("test");
        HarnessEngine.Builder builder = HarnessEngine.of(Files.createDirectories(tmp.resolve("ws")).toString(), tmp.resolve("home").toString())
                .stateSource(state)
                .sessionProvider(sessions)
                .modelAdd(model)
                .defaultModel("fake")
                .extensionAdd(queue);
        if (ownership != null) {
            builder.sessionOwnership(ownership);
        }
        return builder.build();
    }

    /** 积压索引里的会话（直接列举索引目录） */
    private static List<String> indexed(MountSource state) throws IOException {
        return new QueueIndex(state, QueueExtension.INDEX_PATH).list();
    }

    private FileMountSource stateDir() throws IOException {
        return FileMountSource.of(Files.createDirectories(tmp.resolve("state")));
    }

    private static QueueExtension queue(HarnessEngine engine) {
        return engine.getExtension(QueueExtension.class).get();
    }

    /** 标记文件路径（测试用的 sessionId 都是不需要编码的安全字符） */
    private static String marker(String sessionId) {
        return QueueExtension.INDEX_PATH + "/" + sessionId;
    }

    // ---------- ① 会话换节点 ----------

    @Test
    void markerIsRewrittenAfterTheSessionMovesToAnotherNode() throws Exception {
        FileMountSource state = stateDir();
        HarnessEngine nodeA = engine(state);
        HarnessEngine nodeB = engine(state);
        try {
            QueueExtension qa = queue(nodeA);
            QueueExtension qb = queue(nodeB);

            // 会话先在 B 上排过队：B 写过这个标记
            AgentSession onB = InMemoryAgentSession.of("s1");
            assertTrue(qb.enqueue(onB, "first").isOk());

            // 会话换到 A（A 加载到另一个会话对象）：A 把积压清掉，标记注销
            AgentSession onA = InMemoryAgentSession.of("s1");
            assertTrue(qa.enqueue(onA, "first").isOk());
            assertEquals(1, qa.clearPending(onA).getValue().intValue());
            assertFalse(state.exists(marker("s1")), "A 清空后注销标记");

            // 会话又回到 B（新加载的会话对象），又来一条
            AgentSession onB2 = InMemoryAgentSession.of("s1");
            assertTrue(qb.enqueue(onB2, "second").isOk());
            assertTrue(state.exists(marker("s1")),
                    "B 必须重新写标记（不能拿「以前写过」跳过）：否则 B 宕机或引擎被回收后，这条积压没人续发");
        } finally {
            nodeA.shutdown();
            nodeB.shutdown();
        }
    }

    // ---------- ② 判定与删除同锁 ----------

    @Test
    void stopRacingWithAnOfferNeverDeletesTheFreshMarker() throws Exception {
        RecordingSource state = new RecordingSource(stateDir());
        HarnessEngine engine = engine(state);
        try {
            QueueExtension q = queue(engine);
            AgentSession session = InMemoryAgentSession.of("s1");
            assertTrue(q.enqueue(session, "old").isOk());

            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch proceed = new CountDownLatch(1);
            state.deleteProceed = proceed;
            state.deleteEntered = entered;

            Thread stopper = new Thread(() -> engine.interrupt(session, InterruptMode.INTERRUPT_ALL));
            stopper.start();
            assertTrue(entered.await(10, TimeUnit.SECONDS), "停止已判定队列为空，正在删标记");

            Thread offerer = new Thread(() -> q.offer(session, HarnessInput.of(session, "new")));
            offerer.start();
            // 同一把会话锁：入队要等删标记结束。不同锁时入队与写标记会抢在删除之前完成，随后被删
            awaitBlockedOrDone(offerer);
            proceed.countDown();
            stopper.join(10_000);
            offerer.join(10_000);

            assertEquals(1, QueueStore.pendingSize(session), "新消息已入队");
            assertEquals(Collections.singletonList("s1"), indexed(state), "删标记不能吃掉入队刚写的标记");
        } finally {
            engine.shutdown();
        }
    }

    // ---------- ③ 每轮结束的路径不放大 I/O ----------

    @Test
    void unreadableIndexIsNeverListedOnTheRunEndPath() throws Exception {
        RecordingSource state = new RecordingSource(stateDir());
        HarnessEngine engine = engine(state);
        try {
            QueueExtension q = queue(engine);
            q.markPending("someone-else");   // 目录存在
            state.failLists = true;

            for (int i = 0; i < 20; i++) {
                // 每轮结束都会走的那条路：续发领取落空 → 注销
                assertEquals(QueueClaimStatus.NO_TASK, q.drainLoop(InMemoryAgentSession.of("s" + i), null, claim -> true));
            }
            assertTrue(engine.isQuiescent(), "静默判定不读索引目录");

            assertEquals(0, state.lists.get(), "注销只删单条：列举失败不会在每轮结束时重复发生");
            assertEquals(20, state.deletes.get(), "每个新加载的会话对象第一次真删一次");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void confirmedUnmarkSkipsTheDiskUntilTheNextEnqueue() throws Exception {
        RecordingSource state = new RecordingSource(stateDir());
        HarnessEngine engine = engine(state);
        try {
            QueueExtension q = queue(engine);
            AgentSession s = InMemoryAgentSession.of("s1");
            for (int i = 0; i < 10; i++) {
                q.drainLoop(s, null, claim -> true);
            }
            assertEquals(1, state.deletes.get(), "同一会话对象只真删一次：之后每轮结束不再碰磁盘");

            assertTrue(q.enqueue(s, "x").isOk());
            assertEquals(1, state.writes.get());
            assertEquals(1, q.clearPending(s).getValue().intValue());
            assertEquals(2, state.deletes.get(), "入队后清空：再真删一次");

            q.drainLoop(s, null, claim -> true);
            assertEquals(2, state.deletes.get());
        } finally {
            engine.shutdown();
        }
    }

    // ---------- ④ 死标记收敛 ----------

    @Test
    void failedUnmarkIsRetriedOnTheNextObservation() throws Exception {
        RecordingSource state = new RecordingSource(stateDir());
        HarnessEngine engine = engine(state);
        try {
            QueueExtension q = queue(engine);
            AgentSession s = InMemoryAgentSession.of("s1");
            assertTrue(q.enqueue(s, "x").isOk());

            state.failDeletes.set(1);
            q.clearPending(s);
            // 直接查文件：不经 pendingSessions（列目录会影响被测的「下次还删不删」）
            assertTrue(state.exists(marker("s1")), "删失败：标记还在（超集，不丢积压）");

            q.drainLoop(s, null, claim -> true);
            assertFalse(state.exists(marker("s1")), "删失败没记成已注销：下次观察到队空时再删");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void drainFindingNothingLeftUnmarksAfterAnAsyncRun() throws Exception {
        FileMountSource state = stateDir();
        HarnessEngine engine = engine(state);
        try {
            QueueExtension q = queue(engine);
            AgentSession s = InMemoryAgentSession.of("s1");
            assertTrue(q.enqueue(s, "only").isOk());

            QueueClaim[] held = new QueueClaim[1];
            assertEquals(QueueClaimStatus.STARTED, q.drainLoop(s, null, claim -> {
                held[0] = claim;
                return false;   // 异步启动：收口交给流终态
            }));
            assertTrue(state.exists(marker("s1")), "运行中那条未收口：标记保留");

            assertTrue(held[0].settle(true));   // 流终态（RunTicket.settle）收口：收口本身不碰索引
            // L0 每轮结束都会续发：领取落空即注销（L1 再次 claimNext 落空同理，都没发生时有恢复兜底）
            assertEquals(QueueClaimStatus.NO_TASK, q.drainLoop(s, null, claim -> true));
            assertFalse(state.exists(marker("s1")));
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void recoverDrainsLiveBacklogAndCleansDeadMarkers() throws Exception {
        FileMountSource state = stateDir();
        HarnessEngine engine = engine(state);
        try {
            QueueExtension q = queue(engine);
            q.markPending("gone");                              // 会话被存储层清理（没走 removeSession）
            store.put("empty", InMemoryAgentSession.of("empty"));
            q.markPending("empty");                             // 会话还在，队列早空了
            assertTrue(q.enqueue(engine.getSession("live"), "todo").isOk());
            store.put("web-other", InMemoryAgentSession.of("web-other"));
            q.markPending("web-other");                         // 别的入口负责的会话

            List<String> drained = new ArrayList<>();
            int n = q.recover(sid -> sid.startsWith("web-") == false, session -> {
                drained.add(session.getSessionId());
                q.drainLoop(session, null, claim -> true);
            });

            assertEquals(2, n);
            assertEquals(Arrays.asList("empty", "live"), drained, "按 sessionId 顺序逐个续发");
            assertEquals(Collections.singletonList("web-other"), indexed(state),
                    "死标记（gone、empty）清掉，live 跑完注销，过滤外的原样保留给负责它的入口");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void recoverReportsAnUnreadableIndexInsteadOfNoBacklog() throws Exception {
        RecordingSource state = new RecordingSource(stateDir());
        HarnessEngine engine = engine(state);
        try {
            QueueExtension q = queue(engine);
            q.markPending("s1");
            state.failLists = true;

            assertThrows(IOException.class, () -> q.recover(null, s -> fail("读不到索引时不应续发")),
                    "读失败要让宿主知道（好重试），不能当成「没有积压」");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void recoverNormalizesUncheckedIndexFailures() throws Exception {
        RecordingSource state = new RecordingSource(stateDir());
        HarnessEngine engine = engine(state);
        try {
            QueueExtension q = queue(engine);
            q.markPending("s1");
            // 状态来源（远程实现尤其）可能抛非受检异常：不能原样冒给宿主，否则宿主的 IOException 重试认不出「这次没读到」
            state.uncheckedLists = new IllegalStateException("remote state source went away");

            assertThrows(IOException.class, () -> q.recover(null, s -> fail("读不到索引时不应续发")),
                    "非受检的列举失败要归一为 IOException");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void listingSkipsAtomicWriteLeftovers() throws Exception {
        FileMountSource state = stateDir();
        HarnessEngine engine = engine(state);
        try {
            QueueExtension q = queue(engine);
            q.markPending(".hidden");                             // 前导点编码为 %2E
            state.writeString(QueueExtension.INDEX_PATH + "/.s1.0123abcd.tmp", "");   // 原子写中断留下的临时文件

            assertEquals(Collections.singletonList(".hidden"), indexed(state));
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void listingSkipsBrokenAndEscapingLinks() throws Exception {
        // 索引目录里有一个断链或越界链接时，恢复不能永远失败：只跳过坏条目
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"), "依赖符号链接");
        FileMountSource state = stateDir();
        HarnessEngine engine = engine(state);
        try {
            QueueExtension q = queue(engine);
            q.markPending("s1");
            Path dir = tmp.resolve("state").resolve(QueueExtension.INDEX_PATH);
            Path outside = Files.createDirectories(tmp.resolve("outside"));
            Files.write(outside.resolve("x"), new byte[0]);
            Files.createSymbolicLink(dir.resolve("broken"), tmp.resolve("missing"));
            Files.createSymbolicLink(dir.resolve("escaped"), outside.resolve("x"));

            assertEquals(Collections.singletonList("s1"), indexed(state));
        } finally {
            engine.shutdown();
        }
    }

    // ---------- ⑤ 先写标记再入队 ----------

    @Test
    void markerIsWrittenBeforeTheSnapshotIsSaved() throws Exception {
        FileMountSource state = stateDir();
        HarnessEngine engine = engine(state);
        try {
            QueueExtension q = queue(engine);
            AtomicInteger queueSaves = new AtomicInteger();
            AtomicBoolean markerAtSave = new AtomicBoolean(true);
            AgentSession s = new InMemoryAgentSession("s1") {
                private int lastSize;

                @Override
                public void updateSnapshot() {
                    // 入队（队列变长）保存快照的一刻，标记必须已在：两步之间崩溃时，重启恢复仍碰得到这条积压。
                    // 只看队列变长的那几次保存：引擎别处保存快照时队列不变，不在判定范围
                    Object rows = getContext().data().get(QueueAttrs.QUEUE_ITEMS);
                    int size = rows instanceof List ? ((List<?>) rows).size() : 0;
                    if (size > lastSize) {
                        queueSaves.incrementAndGet();
                        try {
                            markerAtSave.compareAndSet(true, state.exists(marker("s1")));
                        } catch (IOException e) {
                            markerAtSave.set(false);
                        }
                    }
                    lastSize = size;
                    super.updateSnapshot();
                }
            };

            assertTrue(q.enqueue(s, "facade").isOk());
            // 每次入队前抿掉标记（好比别的节点删了它）：三个入口各自都得先写标记
            state.deleteIfExists(marker("s1"));
            assertTrue(q.offer(s, HarnessInput.of(s, "busy offer")) > 0);
            state.deleteIfExists(marker("s1"));
            engine.handle(HarnessInput.of(s, "/queue by command"));

            assertEquals(3, QueueStore.pendingSize(s), "门面、忙时积压、/queue 命令各入队一条");
            assertEquals(3, queueSaves.get());
            assertTrue(markerAtSave.get(), "三个入口都先写标记、再存快照");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void failedEnqueueIntoAnEmptyQueueLeavesNoMarker() throws Exception {
        FileMountSource state = stateDir();
        HarnessEngine engine = engine(state, this::sessionOf, QueueExtension.builder().maxTextLength(5).build());
        try {
            QueueExtension q = queue(engine);
            AgentSession s = InMemoryAgentSession.of("s1");
            assertFalse(q.enqueue(s, "longer than five").isOk());
            assertFalse(state.exists(marker("s1")), "没入成且之前队列为空：先写的标记删掉");

            assertTrue(q.enqueue(s, "ok").isOk());
            assertFalse(q.enqueue(s, "longer than five").isOk());
            assertTrue(state.exists(marker("s1")), "队列里还有一条：标记保留");
        } finally {
            engine.shutdown();
        }
    }

    // ---------- ⑥ 引擎关闭、回收判定 ----------

    @Test
    void closedEngineNeitherClaimsNorReacquiresOwnership() throws Exception {
        CountingOwnership ownership = new CountingOwnership();
        HarnessEngine engine = engine(stateDir(), this::sessionOf, new QueueExtension(), ownership);
        QueueExtension q = queue(engine);
        AgentSession s = engine.getSession("s1");
        assertTrue(q.enqueue(s, "a").isOk());
        engine.shutdown();

        int acquires = ownership.acquires.get();
        assertEquals(QueueClaimStatus.NO_TASK, q.drainLoop(s, null, claim -> fail("引擎已关闭：不应领取")));
        assertEquals(QueueClaimStatus.NO_TASK, q.claimNext(s, null, claim -> fail("引擎已关闭：不应领取")));
        assertEquals(0, q.recover(null, x -> fail("引擎已关闭：不应恢复")));
        assertEquals(acquires, ownership.acquires.get(), "关闭后不再认领租约（shutdown 刚释放过）");
        assertEquals(1, q.pendingSize(s), "队列原样保留，由新引擎的恢复续发");
    }

    @Test
    void stuckBacklogDoesNotHoldTheEngineFromBeingReclaimed() throws Exception {
        HarnessEngine engine = engine(stateDir());
        try {
            QueueExtension q = queue(engine);
            AgentSession s = InMemoryAgentSession.of("s1");
            assertTrue(q.enqueue(s, "x").isOk());
            assertTrue(engine.isQuiescent(), "积压在会话快照与索引里，回收不会丢：重建时的恢复续发");

            assertEquals(QueueClaimStatus.START_FAILED, q.drainLoop(s, null, claim -> {
                throw new IllegalStateException("start failed");
            }));
            assertEquals(1, q.pendingSize(s), "启动失败：回到队首");
            assertTrue(engine.isQuiescent(), "启动失败卡住的积压不挡回收");

            q.setEnabled(false);
            assertTrue(engine.isQuiescent(), "停用后不再续发的积压同样不挡回收");
        } finally {
            engine.shutdown();
        }
    }

    // ---------- ⑤ 一个实例一个引擎 ----------

    @Test
    void oneInstanceServesOneRunningEngine() throws Exception {
        QueueExtension shared = new QueueExtension();
        HarnessEngine first = engine(stateDir(), this::sessionOf, shared);
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> engine(stateDir(), this::sessionOf, shared));
            assertTrue(e.getMessage().contains("one instance per engine"));
        } finally {
            first.shutdown();
        }

        // 前一个引擎已关闭（如引擎池回收后重建）：可以再挂
        HarnessEngine second = engine(stateDir(), this::sessionOf, shared);
        second.shutdown();
    }

    /** 记录认领次数的租约（总是本节点持有） */
    static final class CountingOwnership implements SessionOwnership {
        final AtomicInteger acquires = new AtomicInteger();

        @Override
        public boolean tryAcquire(String sessionId) {
            acquires.incrementAndGet();
            return true;
        }

        @Override
        public String ownerOf(String sessionId) {
            return "self";
        }

        @Override
        public void release(String sessionId) {
        }
    }

    // ---------- 工具 ----------

    private static void awaitBlockedOrDone(Thread t) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Thread.State s = t.getState();
            if (s == Thread.State.BLOCKED || s == Thread.State.TERMINATED) {
                return;
            }
            Thread.sleep(10);
        }
        fail("thread neither blocked nor finished: " + t.getState());
    }

    /** 委托式状态来源：只对积压索引目录计数，并可注入列举失败、删除失败与删除阻塞 */
    static final class RecordingSource implements MountSource {
        final MountSource d;
        final AtomicInteger lists = new AtomicInteger();
        final AtomicInteger deletes = new AtomicInteger();
        final AtomicInteger writes = new AtomicInteger();
        final AtomicInteger failDeletes = new AtomicInteger();
        volatile boolean failLists;
        /** 非受检的列举失败（远程状态来源的常见形态）：验证 {@link QueueIndex#list()} 会归一为 IOException */
        volatile RuntimeException uncheckedLists;
        volatile CountDownLatch deleteEntered;
        volatile CountDownLatch deleteProceed;

        RecordingSource(MountSource d) {
            this.d = d;
        }

        private static boolean indexed(String path) {
            return path != null && path.startsWith(QueueExtension.INDEX_PATH);
        }

        @Override
        public List<MountEntry> list(String path) throws IOException {
            if (indexed(path)) {
                lists.incrementAndGet();
                if (failLists) {
                    throw new IOException("index unavailable");
                }
                if (uncheckedLists != null) {
                    throw uncheckedLists;
                }
            }
            return d.list(path);
        }

        @Override
        public boolean deleteIfExists(String path) throws IOException {
            if (indexed(path)) {
                deletes.incrementAndGet();
                CountDownLatch entered = deleteEntered;
                CountDownLatch proceed = deleteProceed;
                if (entered != null && proceed != null) {
                    deleteEntered = null;   // 只拦一次
                    entered.countDown();
                    try {
                        proceed.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (failDeletes.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                    throw new IOException("delete failed");
                }
            }
            return d.deleteIfExists(path);
        }

        @Override
        public void writeString(String path, String content) throws IOException {
            if (indexed(path)) {
                writes.incrementAndGet();
            }
            d.writeString(path, content);
        }

        // ---- 其余全部委托 ----

        @Override
        public String getLocation() {
            return d.getLocation();
        }

        @Override
        public String getScheme() {
            return d.getScheme();
        }

        @Override
        public String normalize(String path) {
            return d.normalize(path);
        }

        @Override
        public MountEntry stat(String path) throws IOException {
            return d.stat(path);
        }

        @Override
        public List<MountEntry> find(String path, FindOptions options) throws IOException {
            return d.find(path, options);
        }

        @Override
        public InputStream openRead(String path) throws IOException {
            return d.openRead(path);
        }

        @Override
        public OutputStream openWrite(String path, WriteOptions options) throws IOException {
            return d.openWrite(path, options);
        }

        @Override
        public void delete(String path) throws IOException {
            d.delete(path);
        }

        @Override
        public void move(String source, String target, MoveOptions options) throws IOException {
            d.move(source, target, options);
        }

        @Override
        public MountCapabilities capabilities() {
            return d.capabilities();
        }

        @Override
        public Optional<Path> getLocalRoot() {
            return d.getLocalRoot();
        }

        @Override
        public Optional<Path> getLocalPath(String path) {
            return d.getLocalPath(path);
        }
    }
}
