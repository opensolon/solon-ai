package org.noear.solon.ai.harness.extension.loop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.tool.ToolResult;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * loop_* 工具的成败标记：失败返回 {@link ToolResult#error(String)}（文本仍带 {@code ERROR: } 前缀给模型看），
 * 成功（含查询为空）不标记失败。文本与默认语言旧签名逐字一致。
 */
class LoopTalentErrorFlagTest {
    private static final DateTimeFormatter AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private LoopScheduler ops;
    private LoopTalent talent;

    @BeforeEach
    void setUp() {
        ops = LoopExtension.builder().jobManager(null).defaultExecutor(false)
                .maxTasks(LoopExtension.DEFAULT_MAX_TASKS).build().scheduler();
        talent = new LoopTalent(ops);
    }

    private static void assertError(ToolResult r) {
        assertTrue(r.isError(), r.getContent());
        assertTrue(r.getContent().startsWith("ERROR: "), r.getContent());
    }

    private static void assertOk(ToolResult r) {
        assertFalse(r.isError(), r.getContent());
        assertFalse(r.getContent().startsWith("ERROR"), r.getContent());
    }

    @Test
    void addFailuresAreErrors() {
        String past = LocalDateTime.now().minusDays(1).format(AT_FORMAT);

        assertError(talent.loopAdd("p", null, null, null, null, null, null, null));
        assertError(talent.loopAdd("p", past, null, null, null, null, "s1", null));
        assertError(talent.loopAdd("p", null, null, "every day at 6", null, null, "s1", null));
        assertError(talent.loopAdd("p", null, 10, null, true, null, "s1", null));
    }

    @Test
    void addSuccessIsNotError() {
        ToolResult r = talent.loopAdd("p", null, 10, null, null, null, "s1", null);

        assertOk(r);
        assertTrue(r.getContent().contains("taskId"));
    }

    @Test
    void emptyListIsNotError() {
        ToolResult r = talent.loopList("no-such-session", null);

        assertOk(r);
        assertEquals(0, ONode.ofJson(r.getContent()).get("total").getInt());
        assertError(talent.loopList("", null));
    }

    @Test
    void controlFailuresAreErrors() {
        assertError(talent.loopControl("restart", "any", "s1", null));
        assertError(talent.loopControl("stop", "missing", "s1", null));
        assertError(talent.loopControl("pause", "missing", "s1", null));
        assertError(talent.loopControl("stop", "x", null, null));

        talent.loopAdd("p", null, 10, null, null, null, "s1", null);
        String taskId = ops.list("s1").get(0).getId();
        assertOk(talent.loopControl("pause", taskId, "s1", null));
        // 已暂停的任务不能 trigger
        assertError(talent.loopControl("trigger", taskId, "s1", null));
        assertOk(talent.loopControl("resume", taskId, "s1", null));
        assertOk(talent.loopControl("trigger", taskId, "s1", null));
        assertOk(talent.loopControl("stop", taskId, "s1", null));
    }

    @Test
    void legacySignatureKeepsSameText() {
        ToolResult r = talent.loopControl("restart", "any", "s1", null);

        assertEquals(r.getContent(), talent.loopControl("restart", "any", "s1"));
    }
}
