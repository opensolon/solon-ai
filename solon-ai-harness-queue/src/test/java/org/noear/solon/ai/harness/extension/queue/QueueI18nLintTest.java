package org.noear.solon.ai.harness.extension.queue;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.extension.queue.i18n.QueueI18n;
import org.noear.solon.ai.harness.extension.queue.i18n.QueueI18nKeys;
import org.noear.solon.ai.i18n.testkit.I18nLint;

/**
 * queue 文案资源的格式与一致性检查（规则见 {@link I18nLint}）
 */
public class QueueI18nLintTest {
    @Test
    public void testLint() throws Exception {
        new I18nLint(QueueI18n.messages(), QueueI18nKeys.class, "QueueI18n")
                .minCalls(7, 8)
                .verify();
    }
}
