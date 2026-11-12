package org.noear.solon.ai.harness.extension.loop;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.harness.HarnessResult;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 宿主侧 API 契约：宿主只经 LoopExtension + LoopSpec/LoopPatch/LoopOutcome 交互，
 * 请求参数可原样传入（null = 未设置/保持），LoopTask 对宿主完全只读。
 */
class LoopHostApiTest {

    private static LoopExtension ext() {
        return LoopExtension.builder().jobManager(null).defaultExecutor(false).build();
    }

    // ==================== 公开面守卫 ====================

    @Test
    void publicSurfaceIsSmall() throws Exception {
        Set<String> publicTypes = new HashSet<>();
        Path root = Paths.get("src/main/java/org/noear/solon/ai/harness/extension/loop");
        List<Path> files;
        try (Stream<Path> s = Files.list(root)) {
            files = s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
        assertTrue(files.size() >= 8, "守卫必须真的扫描到源码，实际 " + files.size());
        for (Path f : files) {
            String name = f.getFileName().toString().replace(".java", "");
            Class<?> c = Class.forName("org.noear.solon.ai.harness.extension.loop." + name);
            if (Modifier.isPublic(c.getModifiers())) {
                publicTypes.add(name);
            }
        }
        assertEquals(new HashSet<>(Arrays.asList(
                "LoopExtension", "LoopTask", "LoopExecutor", "LoopOutcome", "LoopSpec", "LoopPatch", "LoopTalent",
                "LoopArgumentException")),
                publicTypes, "宿主可见类型应只有门面、只读任务、执行器三件套与规格/补丁（LoopTalent 仅为反射保持 public）"
                        + "，以及规格异常（按语言渲染）；文案门面/key 在 i18n 子包里");
    }

    @Test
    void loopTaskIsReadOnlyForHost() {
        for (Constructor<?> c : LoopTask.class.getDeclaredConstructors()) {
            assertFalse(Modifier.isPublic(c.getModifiers()), "宿主新建任务只能走 LoopSpec：" + c);
        }
        List<String> mutators = new ArrayList<>();
        for (Method m : LoopTask.class.getDeclaredMethods()) {
            String n = m.getName();
            if (Modifier.isPublic(m.getModifiers())
                    && (n.startsWith("set") || n.startsWith("put") || n.startsWith("copy") || n.equals("cancel"))) {
                mutators.add(n);
            }
        }
        assertTrue(mutators.isEmpty(), "LoopTask 对宿主必须只读（修改走 LoopPatch）：" + mutators);
    }

    // ==================== LoopSpec ====================

    @Test
    void specAcceptsRawRequestParams() {
        LoopExtension loop = ext();
        // 宿主把请求参数原样传入：null 即未设置
        LoopTask t = loop.schedule("s1", LoopSpec.of("p").everyMinutes(null).cron(null).runNow(null).oneShot(null)).getValue();
        assertEquals(LoopSpec.DEFAULT_INTERVAL_MINUTES, t.getIntervalMinutes());
        assertFalse(t.isCronMode());
        assertFalse(t.isRunNow());

        LoopTask c = loop.schedule("s1", LoopSpec.of("p").everyMinutes(10).cron("  ")).getValue();
        assertFalse(c.isCronMode(), "空白 cron 视为未设置");
        assertEquals(10, c.getIntervalMinutes());
    }

    @Test
    void specValidatesInsteadOfSilentlyClamping() {
        LoopExtension loop = ext();
        assertEquals(HarnessResult.Code.INVALID, loop.schedule("s1", LoopSpec.of(" ")).getCode());
        assertEquals(HarnessResult.Code.INVALID, loop.schedule("s1", LoopSpec.of("p").everyMinutes(0)).getCode());
        assertEquals(HarnessResult.Code.INVALID, loop.schedule("s1", LoopSpec.of("p").everyMinutes(1441)).getCode());
        assertEquals(HarnessResult.Code.INVALID, loop.schedule("s1", LoopSpec.of("p").oneShot(true)).getCode(),
                "一次性必须有 at 或 cron");
        assertEquals(HarnessResult.Code.INVALID, loop.schedule("s1", LoopSpec.of("p").cron("0 0 18 * * ?").oneShot(true).runNow(true)).getCode());
        assertEquals(HarnessResult.Code.INVALID, loop.schedule("s1", LoopSpec.of("p").at(LocalDateTime.now().minusMinutes(1))).getCode());
        assertEquals(HarnessResult.Code.INVALID, loop.schedule("s1", LoopSpec.of("p").cron("every day")).getCode());
        assertThrows(IllegalArgumentException.class, () -> LoopSpec.of("p").extra("prompt", new ONode()));
        assertTrue(loop.list("s1").isEmpty(), "校验失败不得留下半个任务");
    }

    @Test
    void specAtCreatesOneShotCron() {
        LocalDateTime at = LocalDateTime.now().plusDays(1).withSecond(0).withNano(0);
        LoopTask t = ext().schedule("s1", LoopSpec.of("remind").at(at)).getValue();
        assertTrue(t.isOneShot(), "宿主也能建一次性任务（此前只有模型工具能建）");
        assertEquals(String.format("0 %d %d %d %d ? %d", at.getMinute(), at.getHour(),
                at.getDayOfMonth(), at.getMonthValue(), at.getYear()), t.getCron());
    }

    @Test
    void specExtrasArePersistedVerbatim() {
        LoopTask t = ext().schedule("s1", LoopSpec.of("p").extra("automation", new ONode().set("id", "a1"))).getValue();
        assertEquals("a1", t.getExtra("automation").get("id").getString());
        assertEquals("a1", t.toONode().get("automation").get("id").getString());
    }

    // ==================== LoopPatch ====================

    @Test
    void patchKeepsUnsetFields() {
        LoopExtension loop = ext();
        LoopTask t = loop.schedule("s1", LoopSpec.of("v1").everyMinutes(7).runNow(true)
                .extra("automation", new ONode().set("id", "a1"))).getValue();
        loop.pause("s1", t.getId());

        LoopTask u = loop.update("s1", t.getId(), LoopPatch.create()
                .prompt(null).everyMinutes(null).cron(null).runNow(null)).getValue();
        assertEquals("v1", u.getPrompt());
        assertEquals(7, u.getIntervalMinutes());
        assertTrue(u.isRunNow());
        assertFalse(u.isEnabled(), "启停状态必须保留");
        assertEquals("a1", u.getExtra("automation").get("id").getString(), "扩展字段必须保留");

        LoopTask v = loop.update("s1", t.getId(), LoopPatch.create().prompt("  ").everyMinutes(30)).getValue();
        assertEquals("v1", v.getPrompt(), "空白 prompt = 保持");
        assertEquals(30, v.getIntervalMinutes());
    }

    @Test
    void patchEmptyCronClearsCron() {
        LoopExtension loop = ext();
        LoopTask t = loop.schedule("s1", LoopSpec.of("p").cron("0 0 18 * * ?")).getValue();
        LoopTask u = loop.update("s1", t.getId(), LoopPatch.create().cron("")).getValue();
        assertFalse(u.isCronMode(), "空串 cron = 回到固定间隔");
    }

    @Test
    void invalidPatchLeavesTaskUntouched() {
        LoopExtension loop = ext();
        LoopTask t = loop.schedule("s1", LoopSpec.of("p").everyMinutes(5)).getValue();
        assertEquals(HarnessResult.Code.INVALID, loop.update("s1", t.getId(), LoopPatch.create().prompt("x").everyMinutes(0)).getCode());
        assertEquals(HarnessResult.Code.INVALID, loop.update("s1", t.getId(), LoopPatch.create().prompt("x").cron("0 0 18 5 9 ? 2020")).getCode());
        assertSame(t, loop.get("s1", t.getId()), "非法补丁不得替换当前版本");
        assertEquals("p", loop.get("s1", t.getId()).getPrompt());
    }

    @Test
    void patchUpdatesAndRemovesExtras() {
        LoopExtension loop = ext();
        LoopTask t = loop.schedule("s1", LoopSpec.of("p").extra("a", new ONode().set("v", 1)).extra("b", new ONode().set("v", 2))).getValue();
        LoopTask u = loop.update("s1", t.getId(), LoopPatch.create().extra("a", new ONode().set("v", 9)).extra("b", null)).getValue();
        assertEquals(9, u.getExtra("a").get("v").getInt());
        assertNull(u.getExtra("b"));
    }

    @Test
    void updateMissingReturnsNull() {
        assertEquals(HarnessResult.Code.NOT_FOUND, ext().update("s1", "nope", LoopPatch.create().prompt("x")).getCode());
    }

    // ==================== 启停 / 活跃 / 视图 ====================

    @Test
    void pauseResumeAreIdempotent() {
        LoopExtension loop = ext();
        LoopTask t = loop.schedule("s1", LoopSpec.of("p")).getValue();
        assertTrue(loop.pause("s1", t.getId()).isOk());
        assertTrue(loop.pause("s1", t.getId()).isOk());
        assertFalse(t.isEnabled(), "重复暂停不得反向切换");
        assertTrue(loop.resume("s1", t.getId()).isOk());
        assertTrue(loop.resume("s1", t.getId()).isOk());
        assertTrue(t.isEnabled());
        assertFalse(loop.pause("s1", "nope").isOk());
    }

    @Test
    void pausedTasksAreNotActive() {
        LoopExtension loop = ext();
        assertFalse(loop.hasActiveTasks());
        LoopTask t = loop.schedule("s1", LoopSpec.of("p")).getValue();
        assertTrue(loop.hasActiveTasks());
        loop.pause("s1", t.getId());
        assertFalse(loop.hasActiveTasks(), "只剩暂停任务的工作区不应阻止闲置回收");
    }

    @Test
    void toMapIsStandardView() {
        LoopTask t = ext().schedule("s1", LoopSpec.of("p").cron("0 0 18 * * ?")
                .extra("automation", new ONode().set("id", "a1"))).getValue();
        Map<String, Object> m = t.toMap();
        assertEquals(t.getId(), m.get("id"));
        assertEquals("p", m.get("prompt"));
        assertEquals("0 0 18 * * ?", m.get("cron"));
        for (String k : new String[]{"intervalMinutes", "oneShot", "runNow", "enabled", "cancelled", "running", "currentIteration"}) {
            assertTrue(m.containsKey(k), "缺字段 " + k);
        }
        assertFalse(m.containsKey("automation"), "视图不含宿主扩展字段");
        assertFalse(m.containsKey("lastResult"));
    }
}
