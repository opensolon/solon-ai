package features.ai.talents.todo.i18n;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.i18n.testkit.I18nLint;
import org.noear.solon.ai.talents.todo.i18n.TodoI18n;
import org.noear.solon.ai.talents.todo.i18n.TodoI18nKeys;

/**
 * todo 文案资源的格式与一致性检查（规则见 {@link I18nLint}）
 */
public class TodoI18nLintTest {
    @Test
    public void testLint() throws Exception {
        new I18nLint(TodoI18n.messages(), TodoI18nKeys.class, "TodoI18n")
                .minCalls(3, 3)
                .verify();
    }
}
