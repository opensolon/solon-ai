package org.noear.solon.ai.harness.extension.goal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.extension.goal.validate.ValidationResult;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * goal 工具的成败标记：
 * <ul>
 *   <li>{@code goal_update} 没改成状态返回 {@link ToolResult#error(String)}，文本仍是错误码 JSON；</li>
 *   <li>{@code goal_get} 查不到目标是查询的正常状态，不标记失败（文本仍是 NO_GOAL JSON）。</li>
 * </ul>
 */
class GoalTalentErrorFlagTest {
    @TempDir
    Path workspace;

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();

    private GoalExtension newExtension() {
        GoalExtension ext = GoalExtension.builder().jobManager(null).build();
        HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider((id, c) -> c ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id))
                .extensionAdd(ext)
                .build();
        return ext;
    }

    private String sid(String id) {
        sessions.computeIfAbsent(id, InMemoryAgentSession::of);
        return id;
    }

    private static void assertError(ToolResult r, String code) {
        assertTrue(r.isError(), r.getContent());
        assertTrue(r.getContent().contains("\"code\":\"" + code + "\""), r.getContent());
    }

    @Test
    void updateFailuresAreErrors() {
        GoalExtension ext = newExtension();
        GoalTalent talent = ext.getGoalTalent();

        assertError(talent.goal_update(null, "s1", null, null), "INVALID_PARAMETER");
        assertError(talent.goal_update("complete", null, null, null), "NO_SESSION");
        assertError(talent.goal_update("complete", sid("s1"), null, null), "NO_GOAL");

        ext.create(sid("s2"), GoalSpec.of("目标").runNow(false));
        assertError(talent.goal_update("unknown", "s2", null, null), "INVALID_PARAMETER");
    }

    @Test
    void validationFailedIsError() {
        GoalExtension ext = newExtension();
        ext.registerValidator(c -> c.contains("lint"), (c, dir) -> ValidationResult.failed("lint broken"));
        ext.create(sid("s1"), GoalSpec.of("lint 一下").runNow(false));

        ToolResult r = ext.getGoalTalent().goal_update("complete", "s1", workspace.toString(), null);

        assertError(r, "VALIDATION_FAILED");
    }

    @Test
    void completeAndBlockedAreNotErrors() {
        GoalExtension ext = newExtension();
        ext.create(sid("s1"), GoalSpec.of("目标").runNow(false));
        ext.create(sid("s2"), GoalSpec.of("目标").runNow(false));

        ToolResult done = ext.getGoalTalent().goal_update("complete", "s1", workspace.toString(), null);
        assertFalse(done.isError(), done.getContent());
        assertTrue(done.getContent().contains("achieved"), done.getContent());

        ToolResult blocked = ext.getGoalTalent().goal_update("blocked", "s2", workspace.toString(), null);
        assertFalse(blocked.isError(), blocked.getContent());
        assertTrue(blocked.getContent().contains("blocked"), blocked.getContent());
    }

    /**
     * 经工具注册路径：goal_update 失败标记能传出；goal_get 查不到目标不算失败。
     */
    @Test
    void registeredToolFlags() throws Throwable {
        GoalExtension ext = newExtension();
        sid("s1");

        FunctionTool update = ext.getGoalTalent().getToolMap().get("goal_update");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("status", "complete");
        args.put("__sessionId", "s1");
        assertError(update.call(args), "NO_GOAL");

        FunctionTool get = ext.getGoalTalent().getToolMap().get("goal_get");
        Map<String, Object> getArgs = new LinkedHashMap<>();
        getArgs.put("__sessionId", "s1");
        ToolResult r = get.call(getArgs);
        assertFalse(r.isError(), r.getContent());
        assertTrue(r.getContent().contains("NO_GOAL"), r.getContent());
    }
}
