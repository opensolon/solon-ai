package org.noear.solon.ai.harness.extension.goal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.command.builtin.Fakes;
import org.noear.solon.ai.i18n.AiI18n;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * goal 国际化的行为场景：执行意图关键词跨语言识别；不同出口各自的语言来源
 */
public class GoalI18nScenarioTest {
    @TempDir
    Path workspace;

    /**
     * 执行型目标的关键词合并所有语言：与目标用什么语言写无关，也不随默认语言变化
     */
    @Test
    public void actionKeywordsMatchEveryLanguage() {
        Locale saved = AiI18n.getDefaultLocale();
        try {
            for (Locale l : new Locale[]{Locale.CHINESE, Locale.ENGLISH}) {
                AiI18n.setDefaultLocale(l);
                assertTrue(GoalRuntime.requiresActionEvidence("修复登录缺陷"), l.toString());
                assertTrue(GoalRuntime.requiresActionEvidence("新建一个配置"), l.toString());
                assertTrue(GoalRuntime.requiresActionEvidence("Fix the login bug"), l.toString());
                assertTrue(GoalRuntime.requiresActionEvidence("DEPLOY to staging"), l.toString());
                assertFalse(GoalRuntime.requiresActionEvidence("了解一下项目结构"), l.toString());
                assertFalse(GoalRuntime.requiresActionEvidence("Explain the architecture"), l.toString());
                assertFalse(GoalRuntime.requiresActionEvidence("  "), l.toString());
            }
        } finally {
            AiI18n.setDefaultLocale(saved);
        }
    }

    /**
     * 门面的冲突说明跟随引擎语言；/goal 命令的冲突提示跟随命令上下文的语言
     */
    @Test
    public void conflictFollowsEngineForFacadeAndContextForCommand() throws Exception {
        Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
        GoalExtension ext = GoalExtension.builder().jobManager(null).defaultValidators(false).build();
        HarnessEngine engine = HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .sessionProvider((id, c) -> c ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id))
                .locale(Locale.ENGLISH)
                .extensionAdd(ext)
                .build();

        sessions.computeIfAbsent("s1", InMemoryAgentSession::of);
        ext.create("s1", GoalSpec.of("first").runNow(false));

        assertEquals("This session already has a goal in progress; clear it before creating a new one",
                ext.create("s1", GoalSpec.of("second").runNow(false)).getDetail());

        Fakes.StubContext zh = new Fakes.StubContext(sessions.get("s1"), Arrays.asList("another", "goal")).locale(Locale.CHINA);
        new GoalCommand(ext).execute(engine, zh.in(null), zh);
        assertTrue(zh.output().contains("WARN: 当前会话已有进行中的 Goal，请先清除后再创建"), zh.output());
    }
}
