package features.ai.harness.all;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.DeployMode;
import org.noear.solon.ai.harness.extension.goal.GoalExtension;
import org.noear.solon.ai.harness.extension.loop.LoopExtension;
import org.noear.solon.ai.harness.extension.queue.QueueExtension;
import org.noear.solon.ai.harness.extension.steer.SteerExtension;
import org.noear.solon.ai.harness.scope.InputScope;
import org.noear.solon.ai.harness.scope.InputScopeResolver;
import org.noear.solon.ai.mount.source.FileMountSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SAAS.md §二「最小可运行骨架」的装配写法必须能在 SERVER 模式下原样启动。
 */
class SaasSampleBootTest {

    @TempDir
    Path tmp;

    private static ChatConfig model() {
        ChatConfig model = new ChatConfig();
        model.setName("fake");
        model.setModel("fake-model");
        model.setApiUrl("http://localhost:1/v1");
        model.setApiKey("test");
        return model;
    }

    /** SAAS.md §二 buildEngine 的装配写法（改动文档示例时同步此处） */
    @Test
    void documentedSampleBootsUnderServer() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path state = Files.createDirectories(tmp.resolve("state"));

        FileMountSource stateSrc = FileMountSource.of(state);
        GoalExtension goal = GoalExtension.builder().roundConcurrency(2).build();

        HarnessEngine engine = HarnessEngine.of(ws.toString(), ".solon/")
                .deployMode(DeployMode.SERVER)
                .stateSource(stateSrc)
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .modelAdd(model())
                .defaultModel("fake")
                .scopeResolver(InputScopeResolver.byPrincipal(p -> InputScope.builder(p).build()))
                .extensionAdd(QueueExtension.builder().maxSize(20).build())
                .extensionAdd(new SteerExtension())
                .extensionAdd(goal)
                .extensionAdd(LoopExtension.builder()
                        .store(stateSrc, "loop")
                        .maxTasks(200).quota(sid -> 20)
                        .build())
                .build();
        try {
            assertEquals(0, goal.recover(), "空状态目录下没有可恢复的 goal");

            assertTrue(engine.getExtension(QueueExtension.class).isPresent());
            assertTrue(engine.getExtension(SteerExtension.class).isPresent());
            assertSame(goal, engine.getExtension(GoalExtension.class).orElse(null));
            assertTrue(engine.getExtension(LoopExtension.class).isPresent());

            // 插件在封口前完成命令注册
            assertTrue(engine.getCommandRegistry().isSealed());
            assertNotNull(engine.getCommandRegistry().get("goal"));
            assertNotNull(engine.getCommandRegistry().get("loop"));
        } finally {
            engine.shutdown();
        }
    }

    /** 旧写法：build 之后再 addExtensionIfAbsent（SERVER 下引擎全局配置已锁定） */
    @Test
    void postBuildAttachIsLockedUnderServer() throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path state = Files.createDirectories(tmp.resolve("state"));

        HarnessEngine engine = HarnessEngine.of(ws.toString(), ".solon/")
                .deployMode(DeployMode.SERVER)
                .sessionProvider((id, c) -> InMemoryAgentSession.of(id))
                .stateSource(FileMountSource.of(state))
                .modelAdd(model())
                .scopeResolver(InputScopeResolver.byPrincipal(p -> InputScope.builder(p).build()))
                .build();
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> engine.addExtensionIfAbsent(QueueExtension.builder().maxSize(20).build()));
            assertTrue(e.getMessage().contains("extensionAdd"), "报错要指向正确写法：" + e.getMessage());
        } finally {
            engine.shutdown();
        }
    }
}
