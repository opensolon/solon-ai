package org.noear.solon.ai.harness.extension.goal;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.extension.goal.i18n.GoalI18n;
import org.noear.solon.ai.harness.extension.goal.i18n.GoalI18nKeys;
import org.noear.solon.ai.i18n.testkit.I18nLint;

/**
 * goal 文案资源的格式与一致性检查（规则见 {@link I18nLint}）
 */
public class GoalI18nLintTest {
    @Test
    public void testLint() throws Exception {
        new I18nLint(GoalI18n.messages(), GoalI18nKeys.class, "GoalI18n")
                .minCalls(20, 30)
                .verify();
    }
}
