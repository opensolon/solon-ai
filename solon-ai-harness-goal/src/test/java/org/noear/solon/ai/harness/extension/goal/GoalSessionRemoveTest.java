package org.noear.solon.ai.harness.extension.goal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.extension.goal.validate.GoalValidator;
import org.noear.solon.scheduling.scheduled.JobHandler;
import org.noear.solon.scheduling.scheduled.manager.IJobManager;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 会话删除契约：
 * <ol>
 *   <li>{@code engine.removeSession} → {@code onSessionRemove} 注销该会话的 goal job；</li>
 *   <li>会话已不存在（宿主没走 removeSession）时，job 触发即自行注销，且不会把会话重建出来。</li>
 * </ol>
 */
class GoalSessionRemoveTest {
    @TempDir
    Path workspace;

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, JobHandler> jobs = new ConcurrentHashMap<>();

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
                        case "isStarted":
                        case "hasJobInterceptor":
                            return false;
                        case "hashCode":
                            return System.identityHashCode(p);
                        case "equals":
                            return p == args[0];
                        case "toString":
                            return "GoalFakeJobManager";
                        default:
                            return null;
                    }
                });
    }

    private HarnessEngine engine(GoalExtension ext) {
        return HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider((id, c) -> c ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id))
                .extensionAdd(ext)
                .build();
    }

    @Test
    void removeSessionUnschedulesGoalJob() {
        GoalExtension ext = GoalExtension.builder().jobManager(jobManager()).build();
        HarnessEngine engine = engine(ext);

        ext.create("s1", GoalSpec.of("目标").runNow(false));
        assertEquals(1, jobs.size(), "创建 goal 后应注册 job");
        assertTrue(ext.hasActiveGoals());

        engine.removeSession("s1");

        assertTrue(jobs.isEmpty(), "onSessionRemove 必须注销该会话的 job");
        assertFalse(ext.hasActiveGoals());

        engine.removeSession("s1"); // 幂等
    }

    @Test
    void jobUnschedulesItselfWhenSessionIsGoneAndDoesNotRecreateIt() throws Throwable {
        GoalExtension ext = GoalExtension.builder().jobManager(jobManager()).build();
        engine(ext);

        ext.create("s1", GoalSpec.of("目标").runNow(true)); // 下一拍即到期
        JobHandler job = jobs.values().iterator().next();

        sessions.remove("s1"); // 宿主绕过 removeSession 直接删掉了会话

        job.handle(null);

        assertTrue(jobs.isEmpty(), "会话不存在时 job 必须自行注销");
        assertFalse(ext.hasActiveGoals());
        assertFalse(sessions.containsKey("s1"), "调度轮次不得把已删除的会话重建出来");
    }

    @Test
    void twoInstancesNeverShareJobNames() {
        // 两个扩展实例共用同一个 JobManager，对同一个 sessionId 调度（如重建引擎时旧实例未卸干净）
        IJobManager jm = jobManager();
        GoalExtension a = GoalExtension.builder().jobManager(jm).build();
        GoalExtension b = GoalExtension.builder().jobManager(jm).build();
        HarnessEngine engineA = engine(a);
        // 另一个引擎用独立的会话存储（goal 状态在会话属性里，共用会触发单槽限制）
        Map<String, AgentSession> otherSessions = new ConcurrentHashMap<>();
        HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider((id, c) -> c ? otherSessions.computeIfAbsent(id, InMemoryAgentSession::of) : otherSessions.get(id))
                .extensionAdd(b)
                .build();

        a.create("s1", GoalSpec.of("A").runNow(false));
        b.create("s1", GoalSpec.of("B").runNow(false));
        String nameA = a.getGoalScheduler().tickJobName();
        String nameB = b.getGoalScheduler().tickJobName();
        assertNotEquals(nameA, nameB, "不同实例同一会话的 job 不得撞名");
        assertEquals(2, jobs.size(), "两个实例的节拍 job 都必须注册上（撞名时 JobManager 会静默沿用旧 job）");

        engineA.removeSession("s1");
        assertFalse(jobs.containsKey(nameA));
        assertTrue(jobs.containsKey(nameB), "一个实例注销不得波及另一个实例的 job");
    }

    /**
     * 验证器必须在本轮会话工作目录下执行。历史缺陷：验证器在构造期绑定 engine.getWorkspace()，
     * 桌面端 goal 把会话目录设为目标项目后，mvn test 仍在启动目录里跑。
     */
    @Test
    void goalUpdateValidatesInRoundCwdNotEngineWorkspace() throws Exception {
        GoalExtension ext = GoalExtension.builder().jobManager(null).build();
        HarnessEngine engine = engine(ext);
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        ext.registerValidator(c -> c.contains("lint"), (c, dir) -> {
            seen.add(dir);
            return GoalValidator.ValidationResult.passed();
        });
        Path project = java.nio.file.Files.createDirectories(workspace.resolve("other-project"));

        // ① 本轮工具上下文带 __cwd：以它为准
        ext.create("s1", GoalSpec.of("lint 一下").runNow(false));
        String json = ext.getGoalTalent().goal_update("complete", "s1", project.toString());
        assertTrue(json.contains("achieved"), json);
        assertEquals(Collections.singletonList(project.toString()), seen);

        // ② 上下文没有 __cwd：回落到会话工作目录，而不是引擎工作区
        seen.clear();
        engine.setSessionCwd(engine.getSession("s2"), project.toString());
        ext.create("s2", GoalSpec.of("lint 一下").runNow(false));
        json = ext.getGoalTalent().goal_update("complete", "s2", null);
        assertTrue(json.contains("achieved"), json);
        assertEquals(Collections.singletonList(project.toString()), seen);
        assertNotEquals(engine.getWorkspace(), seen.get(0));
    }
}
