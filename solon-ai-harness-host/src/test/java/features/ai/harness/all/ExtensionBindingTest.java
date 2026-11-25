package features.ai.harness.all;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.AbsHarnessExtension;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.harness.extension.goal.GoalExtension;
import org.noear.solon.ai.harness.extension.loop.LoopExtension;
import org.noear.solon.ai.harness.extension.loop.LoopSpec;
import org.noear.solon.ai.harness.extension.queue.QueueExtension;
import org.noear.solon.ai.harness.extension.steer.SteerExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 引擎绑定规则（{@link AbsHarnessExtension#bindEngine}）：一个持有引擎状态的扩展实例同一时刻只服务一个运行中的引擎。
 *
 * <p>守护的问题：四个插件原来各管各的——goal 第二次挂接直接返回，一直绑着第一个引擎；
 * loop / steer 后挂的覆盖先挂的。命令回调、调度器、索引都按最后挂接的那个引擎走，会把 A 引擎会话的标记写进 B 引擎的状态来源。</p>
 */
class ExtensionBindingTest {

    @TempDir
    Path tmp;

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();

    private HarnessEngine engine(HarnessExtension... extensions) throws Exception {
        ChatConfig model = new ChatConfig();
        model.setName("fake");
        model.setModel("fake-model");
        model.setApiUrl("http://localhost:1/v1");
        model.setApiKey("test");
        HarnessEngine.Builder builder = HarnessEngine.of(Files.createDirectories(tmp.resolve("ws")).toString(), tmp.resolve("home").toString())
                .sessionProvider((id, parentId, c) -> c ? sessions.computeIfAbsent(id, InMemoryAgentSession::of) : sessions.get(id))
                .modelAdd(model)
                .defaultModel("fake");
        for (HarnessExtension extension : extensions) {
            builder.extensionAdd(extension);
        }
        return builder.build();
    }

    private static LoopExtension loop() {
        return LoopExtension.builder().jobManager(null).defaultExecutor(false).build();
    }

    private void assertOneEnginePerInstance(String name, HarnessExtension ext, String command) throws Exception {
        HarnessEngine first = engine(ext);
        assertNotNull(first.getCommandRegistry().find(command), name + " 挂接后注册命令");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> engine(ext),
                name + "：挂在运行中的引擎上时不能再挂到另一个");
        assertTrue(e.getMessage().contains("one instance per engine"), e.getMessage());

        // 前一个引擎已关闭（如引擎池回收后重建）：可以换绑，命令在新引擎上重新注册
        first.shutdown();
        HarnessEngine second = engine(ext);
        try {
            assertNotNull(second.getCommandRegistry().find(command), name + "：换绑后在新引擎上注册命令");
        } finally {
            second.shutdown();
        }
    }

    @Test
    void queueServesOneRunningEngine() throws Exception {
        assertOneEnginePerInstance("queue", QueueExtension.builder().build(), "queue");
    }

    @Test
    void goalServesOneRunningEngine() throws Exception {
        assertOneEnginePerInstance("goal", GoalExtension.builder().jobManager(null).build(), "goal");
    }

    @Test
    void loopServesOneRunningEngine() throws Exception {
        assertOneEnginePerInstance("loop", LoopExtension.builder().build(), "loop");
    }

    @Test
    void steerServesOneRunningEngine() throws Exception {
        assertOneEnginePerInstance("steer", new SteerExtension(), "steer");
    }

    /**
     * 构建半途失败（后面的扩展挂不上）：前面已挂接的实例随半成品引擎一起解绑，能挂到重建的引擎上。
     * 否则它们绑在一个永远没人关闭的引擎上，同一个实例从此再也挂不上新引擎。
     */
    @Test
    void failedBuildReleasesTheExtensionsAttachedBeforeTheFailure() throws Exception {
        LoopExtension busy = loop();
        HarnessEngine running = engine(busy);
        QueueExtension queue = QueueExtension.builder().build();
        try {
            // queue 先挂上，busy 挂不上（还绑在运行中的引擎上）：构建失败
            assertThrows(IllegalStateException.class, () -> engine(queue, busy));

            HarnessEngine rebuilt = engine(queue);
            try {
                assertSame(queue, rebuilt.getExtension(QueueExtension.class).orElse(null), "重建的引擎能挂上同一个实例");
            } finally {
                rebuilt.shutdown();
            }
        } finally {
            running.shutdown();
        }
    }

    /** addExtension 挂不上：不留在引擎的扩展表里；这个引擎关闭时也不会去卸别的引擎上的调度 */
    @Test
    void failedAddExtensionLeavesNoTrace() throws Exception {
        LoopExtension loop = loop();
        HarnessEngine owner = engine(loop);
        HarnessEngine other = engine();
        try {
            owner.getSession("s1");
            assertTrue(loop.schedule("s1", LoopSpec.of("p").everyMinutes(10)).isOk());
            assertTrue(loop.hasActiveTasks());

            assertThrows(IllegalStateException.class, () -> other.addExtension(loop));
            assertFalse(other.getExtension(LoopExtension.class).isPresent(), "挂不上的扩展不留在引擎上");

            other.shutdown();
            assertTrue(loop.hasActiveTasks(), "别的引擎关闭不影响本实例在自己引擎上的任务");
        } finally {
            other.shutdown();
            owner.shutdown();
        }
    }

    /**
     * 从运行中的引擎上摘除不算解绑：那个引擎的命令表里还留着本实例的命令（命令表不能注销），
     * 换绑后它们会改按新引擎维护状态（如把旧引擎会话的积压标记写进新引擎的状态来源）。原引擎关闭后才能换绑。
     */
    @Test
    void removedFromARunningEngineCannotMoveToAnother() throws Exception {
        QueueExtension queue = QueueExtension.builder().build();
        HarnessEngine first = engine(queue);
        try {
            first.removeExtension(queue);
            assertNotNull(first.getCommandRegistry().find("queue"), "摘除后命令仍在原引擎上");
            assertThrows(IllegalStateException.class, () -> engine(queue), "原引擎关闭之前不能换绑");
        } finally {
            first.shutdown();
        }
        engine(queue).shutdown();
    }

    /** 换绑之后，旧引擎上迟到的 removeExtension 不卸新引擎的调度（onDetach 只认绑定的那个引擎） */
    @Test
    void lateDetachFromTheOldEngineDoesNotStopTheNewOne() throws Exception {
        LoopExtension loop = loop();
        HarnessEngine old = engine(loop);
        old.shutdown();
        HarnessEngine current = engine(loop);
        try {
            current.getSession("s1");
            assertTrue(loop.schedule("s1", LoopSpec.of("p").everyMinutes(10)).isOk());
            old.removeExtension(loop);
            assertTrue(loop.hasActiveTasks(), "旧引擎的回调不碰新引擎上的任务");
        } finally {
            current.shutdown();
        }
    }
}
