package features.ai.harness.all;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.harness.extension.goal.GoalExtension;
import org.noear.solon.ai.harness.extension.loop.LoopExtension;
import org.noear.solon.ai.harness.extension.queue.QueueExtension;
import org.noear.solon.ai.harness.extension.steer.SteerExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聚合包守卫：引入 solon-ai-harness-all 即可拿到核心与全部会话插件。
 *
 * <p>本测试以编译期引用为主——任一插件从 pom 漏掉，这里直接编译失败；
 * 运行期再确认四个插件都实现了核心扩展契约（插件只经 {@link HarnessExtension} 与核心协作）。
 */
class HarnessAllDependencyTest {

    @Test
    void aggregatesCoreAndAllSessionPlugins() {
        assertTrue(HarnessEngine.class.getName().startsWith("org.noear.solon.ai.harness."));

        Class<?>[] plugins = {
                GoalExtension.class,
                QueueExtension.class,
                SteerExtension.class,
                LoopExtension.class
        };

        for (Class<?> plugin : plugins) {
            assertTrue(HarnessExtension.class.isAssignableFrom(plugin),
                    plugin.getSimpleName() + " 应实现 HarnessExtension");
        }
    }
}
