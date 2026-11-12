package org.noear.solon.ai.harness.extension.loop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.harness.AbsHarnessExtension;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessResult;
import org.noear.solon.ai.harness.command.builtin.Fakes;
import org.noear.solon.ai.harness.extension.loop.i18n.LoopI18nKeys;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.mount.source.FileMountSource;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 定时任务的语言：创建时记下（/loop 取命令上下文的语言，loop_add 取工具参数 {@code __locale}，宿主经 {@link LoopSpec#locale}），
 * 随任务持久化；每一轮交给执行器（进而写进本轮输入）的语言都取它，没有时用引擎语言。
 *
 * <p>回归：Web 界面选英文后创建定时任务，引擎未配置语言（缺省中文），后台轮次的协议指令与工具文案全是中文。</p>
 */
public class LoopLocaleTest {
    @TempDir
    Path workspace;

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();

    private HarnessEngine engine(Locale engineLocale, AbsHarnessExtension... extensions) {
        HarnessEngine.Builder b = HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider((id, c) -> c ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id))
                .locale(engineLocale);
        for (AbsHarnessExtension e : extensions) {
            b.extensionAdd(e);
        }
        return b.build();
    }

    private static LoopExtension loopExt() {
        return LoopExtension.builder().jobManager(null).build();
    }

    private AgentSession session(String id) {
        return sessions.computeIfAbsent(id, InMemoryAgentSession::of);
    }

    @Test
    public void commandRecordsTheContextLocale() throws Exception {
        LoopExtension ext = loopExt();
        HarnessEngine engine = engine(null, ext);

        Fakes.StubContext en = new Fakes.StubContext(session("s1"),
                Arrays.asList("--every", "10m", "check", "the", "build")).locale(Locale.ENGLISH);
        ext.command().execute(engine, en.in(null), en);
        assertEquals(Locale.ENGLISH, ext.list("s1").get(0).getLocale(), en.output());

        Fakes.StubContext none = new Fakes.StubContext(session("s2"), Arrays.asList("检查构建")).locale(null);
        ext.command().execute(engine, none.in(null), none);
        assertNull(ext.list("s2").get(0).getLocale(), "命令上下文没有语言：不冻结，跟随引擎");
    }

    @Test
    public void loopAddRecordsTheToolLocale() throws Throwable {
        LoopExtension ext = loopExt();
        engine(null, ext);
        FunctionTool add = null;
        for (FunctionTool t : new LoopTalent(ext.scheduler()).getTools(Prompt.of("hi"))) {
            if ("loop_add".equals(t.name())) {
                add = t;
            }
        }

        Map<String, Object> args = new HashMap<>();
        args.put("prompt", "check the build");
        args.put("__sessionId", "s1");
        args.put(AiI18n.ATTR_LOCALE, "en");
        add.handle(args);
        assertEquals(Locale.ENGLISH, ext.list("s1").get(0).getLocale());
    }

    @Test
    public void executorReceivesTheTaskLocale() {
        LoopExtension ext = loopExt();
        engine(null, ext); // 引擎未配置语言：缺省中文
        List<Locale> locales = new CopyOnWriteArrayList<>();
        ext.addExecutor("t", null, (sid, prompt, locale) -> {
            locales.add(locale);
            return LoopOutcome.done("ok");
        });

        session("s1");
        LoopTask task = ext.schedule("s1", LoopSpec.of("check the build").locale(Locale.ENGLISH)).getValue();
        ext.scheduler().doTrigger("s1", task, false);
        assertEquals(Arrays.asList(Locale.ENGLISH), locales, "执行器拿到任务的语言");
        assertEquals(1, ext.get("s1", task.getId()).getCurrentIteration());
    }

    @Test
    public void taskWithoutLocaleFollowsTheEngine() {
        LoopExtension ext = loopExt();
        engine(Locale.ENGLISH, ext);
        List<Locale> locales = new CopyOnWriteArrayList<>();
        ext.addExecutor("t", null, (sid, prompt, locale) -> {
            locales.add(locale);
            return LoopOutcome.done("ok");
        });

        session("s1");
        LoopTask task = ext.schedule("s1", LoopSpec.of("explain the architecture")).getValue();
        assertNull(task.getLocale(), "未指定：不冻结引擎语言");
        ext.scheduler().doTrigger("s1", task, false);
        assertEquals(Arrays.asList(Locale.ENGLISH), locales);
    }

    @Test
    public void defaultExecutorPutsTheLocaleOnTheInput() {
        List<Locale> seen = new CopyOnWriteArrayList<>();
        AbsHarnessExtension probe = new AbsHarnessExtension() {
            @Override
            public void onInput(HarnessEngine engine, HarnessInput input) {
                if (input.isProgram()) {
                    seen.add(input.getLocale());
                    throw new IllegalStateException("probe: stop before the model call");
                }
            }
        };
        LoopExtension ext = loopExt();
        engine(null, probe, ext);

        session("s1");
        LoopTask task = ext.schedule("s1", LoopSpec.of("check the build").locale(Locale.ENGLISH)).getValue();
        ext.scheduler().doTrigger("s1", task, false);
        assertEquals(Arrays.asList(Locale.ENGLISH), seen, "缺省执行器把语言写进本轮输入");
    }

    @Test
    public void facadeMessagesUseTheTaskLocale() {
        LoopExtension ext = loopExt();
        engine(null, ext); // 引擎缺省中文

        HarnessResult<LoopTask> bad = ext.schedule("s1", LoopSpec.of(" ").locale(Locale.ENGLISH));
        assertEquals(new LoopArgumentException(LoopI18nKeys.LOOP_ERROR_PROMPT_EMPTY).getMessage(Locale.ENGLISH),
                bad.getDetail(), "新建：校验说明按规格的语言");

        LoopTask task = ext.schedule("s1", LoopSpec.of("x").locale(Locale.ENGLISH)).getValue();
        HarnessResult<LoopTask> badPatch = ext.update("s1", task.getId(), LoopPatch.create().everyMinutes(0));
        assertEquals(new LoopArgumentException(LoopI18nKeys.LOOP_ERROR_INTERVAL_RANGE,
                        "min", LoopTask.MIN_INTERVAL, "max", LoopTask.MAX_INTERVAL, "value", 0).getMessage(Locale.ENGLISH),
                badPatch.getDetail(), "修改：校验说明按任务的语言");
    }

    @Test
    public void localeSurvivesUpdateAndReload() {
        FileMountSource source = FileMountSource.of(workspace.resolve("state").toString(), true);
        LoopExtension ext = LoopExtension.builder().jobManager(null).store(source, "loop").build();
        engine(null, ext);

        LoopTask task = ext.schedule("s1", LoopSpec.of("check the build").locale(Locale.ENGLISH)).getValue();
        LoopTask updated = ext.update("s1", task.getId(), LoopPatch.create().prompt("check the tests")).getValue();
        assertEquals(Locale.ENGLISH, updated.getLocale(), "补丁没指定语言：保持任务的语言");

        LoopExtension reloaded = LoopExtension.builder().jobManager(null).store(source, "loop").build();
        engine(null, reloaded);
        assertEquals(Locale.ENGLISH, reloaded.get("s1", task.getId()).getLocale(), "重启后语言还在");
    }

    @Test
    public void patchChangesTheTaskLocale() {
        LoopExtension ext = loopExt();
        engine(Locale.ENGLISH, ext);

        LoopTask task = ext.schedule("s1", LoopSpec.of("check the build").locale(Locale.CHINA)).getValue();
        LoopTask updated = ext.update("s1", task.getId(), LoopPatch.create().locale(Locale.ENGLISH)).getValue();

        assertEquals(Locale.ENGLISH, updated.getLocale(), "补丁指定语言：改成它");
        assertEquals(Locale.ENGLISH, ext.get("s1", task.getId()).getLocale());
    }

    @Test
    public void patchWithNullLocaleKeepsTheTaskLocale() {
        LoopExtension ext = loopExt();
        engine(Locale.ENGLISH, ext);

        LoopTask task = ext.schedule("s1", LoopSpec.of("check the build").locale(Locale.CHINA)).getValue();
        // locale(null) 与「不调」同义：保持，不清除
        LoopTask kept = ext.update("s1", task.getId(), LoopPatch.create().locale(null)).getValue();

        assertEquals(Locale.CHINA, kept.getLocale(), "传 null 也是保持任务的语言");
        assertEquals("zh-CN", kept.toMap().get("locale"));
    }

    @Test
    public void patchLocaleDrivesTheNextRound() {
        LoopExtension ext = loopExt();
        engine(null, ext);

        LoopTask task = ext.schedule("s1", LoopSpec.of("check the build").locale(Locale.CHINA)).getValue();
        // 每轮执行前都取一次「任务的语言 > 引擎语言」：换语言后下一轮就是新的
        assertEquals(Locale.CHINA, ext.scheduler().localeOf(ext.get("s1", task.getId())));

        ext.update("s1", task.getId(), LoopPatch.create().locale(Locale.ENGLISH));
        assertEquals(Locale.ENGLISH, ext.scheduler().localeOf(ext.get("s1", task.getId())));
    }

    @Test
    public void localeIsExposedInTheDisplayView() {
        LoopTask task = new LoopTask("p", 5, null, false, Locale.ENGLISH);
        assertEquals("en", task.toMap().get("locale"), "展示视图带上语言，宿主可显示");
        assertNull(new LoopTask("p", 5, null, false).toMap().get("locale"), "未指定不写键");
    }

    @Test
    public void localeIsSerialized() {
        LoopTask task = new LoopTask("p", 5, null, false, Locale.CHINA);
        ONode node = task.toONode();
        assertEquals("zh-CN", node.get("locale").getString());
        assertEquals(Locale.CHINA, LoopTask.fromONode(node).getLocale());
        assertNull(LoopTask.fromONode(node).getExtra("locale"), "locale 是核心字段，不进扩展字段");

        // 升级前保存的任务没有这个字段：跟随引擎
        ONode legacy = new LoopTask("p", 5, null, false).toONode();
        assertNull(legacy.getOrNull("locale"), "未指定语言不写键");
        assertNull(LoopTask.fromONode(legacy).getLocale());

        // 下划线写法也认
        legacy.set("locale", "en_US");
        assertEquals(Locale.US, LoopTask.fromONode(legacy).getLocale());
    }
}
