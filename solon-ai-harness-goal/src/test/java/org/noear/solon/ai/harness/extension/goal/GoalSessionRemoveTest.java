package org.noear.solon.ai.harness.extension.goal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.scheduling.scheduled.JobHandler;
import org.noear.solon.scheduling.scheduled.manager.IJobManager;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
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
        GoalExtension ext = GoalExtension.builder("ws-rm").jobManager(jobManager()).build();
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
        GoalExtension ext = GoalExtension.builder("ws-gone").jobManager(jobManager()).build();
        engine(ext);

        ext.create("s1", GoalSpec.of("目标").runNow(false));
        JobHandler job = jobs.values().iterator().next();

        sessions.remove("s1"); // 宿主绕过 removeSession 直接删掉了会话

        job.handle(null);

        assertTrue(jobs.isEmpty(), "会话不存在时 job 必须自行注销");
        assertFalse(ext.hasActiveGoals());
        assertFalse(sessions.containsKey("s1"), "调度轮次不得把已删除的会话重建出来");
    }
}
