package features.ai.harness.all;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.extension.goal.GoalExtension;
import org.noear.solon.ai.harness.extension.goal.GoalSpec;
import org.noear.solon.ai.harness.host.HarnessHost;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 骨架跑的 goal 后台轮次：本轮输入带上目标的语言（创建时记下的语言），
 * 智能体的协议指令、工具文案才会与提示词同一种语言。
 *
 * <p>回归：Web 界面选英文后提交 {@code /goal ...}，引擎未配置语言，后台轮次的输入不带语言，整轮回落成中文。</p>
 */
class HarnessHostGoalLocaleTest {

    @TempDir
    Path tmp;

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();

    @Test
    void goalRoundInputCarriesTheGoalLocale() throws Exception {
        ChatConfig model = new ChatConfig();
        model.setName("fake");
        model.setModel("fake-model");
        model.setApiUrl("http://localhost:1/v1");
        model.setApiKey("test");
        HarnessEngine engine = HarnessEngine.of(Files.createDirectories(tmp.resolve("ws")).toString(), tmp.resolve("home").toString())
                .sessionProvider((id, create) -> create ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id))
                .modelAdd(model)
                .defaultModel("fake")
                .extensionAdd(GoalExtension.builder().jobManager(null).defaultValidators(false).build())
                .build(); // 引擎不配置语言（缺省中文）

        List<Locale> seen = new CopyOnWriteArrayList<>();
        CountDownLatch decorated = new CountDownLatch(1);
        HarnessHost host = HarnessHost.builder()
                .engines(k -> engine)
                .programRuns(sid -> sid.startsWith("web-"))
                .input((tenant, in) -> {
                    if (in.isProgram()) {
                        seen.add(in.getLocale());
                        decorated.countDown();
                        throw new IllegalStateException("probe: stop before the model call");
                    }
                })
                .build();
        try {
            engine.getSession("web-1");
            host.interrupt("t", "web-1"); // 首次取到引擎：登记后台轮次执行器

            GoalExtension goal = engine.getExtension(GoalExtension.class).get();
            assertTrue(goal.create("web-1", GoalSpec.of("Prepare a business plan")
                    .locale(Locale.ENGLISH).runNow(false)).isOk());
            assertTrue(goal.trigger("web-1").isOk());

            assertTrue(decorated.await(10, TimeUnit.SECONDS), "后台轮次应经骨架的执行器");
            assertEquals(Arrays.asList(Locale.ENGLISH), seen);
        } finally {
            engine.shutdown();
        }
    }
}
