package org.noear.solon.ai.harness.extension.goal;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.mount.source.FileMountSource;
import org.noear.solon.ai.mount.source.FindOptions;
import org.noear.solon.ai.mount.source.MountCapabilities;
import org.noear.solon.ai.mount.source.MountEntry;
import org.noear.solon.ai.mount.source.MountSource;
import org.noear.solon.ai.mount.source.MoveOptions;
import org.noear.solon.ai.mount.source.WriteOptions;
import org.noear.solon.scheduling.scheduled.JobHandler;
import org.noear.solon.scheduling.scheduled.manager.IJobManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 活跃索引读失败的上报口径：{@link GoalExtension#recover} 抛出而不是当成「没有活跃 goal」。
 *
 * <p>返回空会让宿主以为恢复完成：进程重启后的调度就此丢掉，直到该会话下次被打开。抛出让宿主退避重试
 * （L0 的 {@code HarnessHost} 与积压恢复同一套退避）。</p>
 */
class GoalIndexReadFailureTest {

    @TempDir
    Path workspace;

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, JobHandler> jobs = new ConcurrentHashMap<>();

    /** 调度器只在配了 jobManager 时创建（与生产装配一致） */
    private IJobManager jobManager() {
        return (IJobManager) Proxy.newProxyInstance(IJobManager.class.getClassLoader(),
                new Class<?>[]{IJobManager.class}, (p, method, args) -> {
                    switch (method.getName()) {
                        case "jobAdd":
                            jobs.put((String) args[0], (JobHandler) args[2]);
                            return null;
                        case "jobExists":
                            return jobs.containsKey((String) args[0]);
                        case "jobRemove":
                            jobs.remove((String) args[0]);
                            return null;
                        case "hashCode":
                            return System.identityHashCode(p);
                        case "equals":
                            return p == args[0];
                        case "toString":
                            return "FakeJobManager";
                        default:
                            return method.getReturnType() == boolean.class ? false : null;
                    }
                });
    }

    private HarnessEngine engine(MountSource state) throws IOException {
        GoalExtension ext = GoalExtension.builder().jobManager(jobManager()).build();
        return HarnessEngine.of(Files.createDirectories(workspace.resolve("ws")).toString(),
                        workspace.resolve("home").toString())
                .stateSource(state)
                .sessionProvider((id, c) -> c ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id))
                .extensionAdd(ext)
                .build();
    }

    @Test
    void recoverReportsAnUnreadableIndex() throws Exception {
        FileMountSource real = FileMountSource.of(Files.createDirectories(workspace.resolve("state")));
        FailingListSource state = new FailingListSource(real);

        HarnessEngine ext1 = engine(state);
        ext1.getExtension(GoalExtension.class).get().getGoalScheduler().schedule("s1", false);
        ext1.shutdown();

        state.failLists = true;
        HarnessEngine ext2 = engine(state);
        try {
            GoalExtension goal = ext2.getExtension(GoalExtension.class).get();
            assertThrows(IOException.class, goal::recover,
                    "索引读不了：不是「没有活跃 goal」，要让宿主退避重试");

            state.failLists = false;
            assertEquals(1, goal.recover(), "读得到之后照常恢复，且只登记一次");
        } finally {
            ext2.shutdown();
        }
    }

    @Test
    void recoverSkipsBrokenAndEscapingLinksInTheIndex() throws Exception {
        // 索引目录里的断链 / 越界链接只是坏条目：跳过即可，不能让每次恢复都失败
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"), "依赖符号链接");
        Path stateDir = Files.createDirectories(workspace.resolve("state"));
        FileMountSource state = FileMountSource.of(stateDir);

        HarnessEngine ext1 = engine(state);
        ext1.getExtension(GoalExtension.class).get().getGoalScheduler().schedule("s1", false);
        ext1.shutdown();

        Path index = stateDir.resolve(GoalExtension.INDEX_PATH);
        Path outside = Files.createDirectories(workspace.resolve("outside"));
        Files.write(outside.resolve("x"), new byte[0]);
        Files.createSymbolicLink(index.resolve("broken"), workspace.resolve("missing"));
        Files.createSymbolicLink(index.resolve("escaped"), outside.resolve("x"));

        HarnessEngine ext2 = engine(state);
        try {
            assertEquals(1, ext2.getExtension(GoalExtension.class).get().recover());
        } finally {
            ext2.shutdown();
        }
    }

    /** 委托式状态来源：只对活跃索引目录的列举注入失败 */
    private static final class FailingListSource implements MountSource {
        private final MountSource d;
        volatile boolean failLists;

        FailingListSource(MountSource d) {
            this.d = d;
        }

        @Override
        public List<MountEntry> list(String path) throws IOException {
            if (failLists && path != null && path.startsWith(GoalExtension.INDEX_PATH)) {
                throw new IOException("index unavailable");
            }
            return d.list(path);
        }

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
