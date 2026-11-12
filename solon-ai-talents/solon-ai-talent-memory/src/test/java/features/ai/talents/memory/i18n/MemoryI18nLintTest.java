package features.ai.talents.memory.i18n;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.i18n.testkit.I18nLint;
import org.noear.solon.ai.talents.memory.i18n.MemoryI18n;
import org.noear.solon.ai.talents.memory.i18n.MemoryI18nKeys;

/**
 * memory 文案资源的格式与一致性检查（规则见 {@link I18nLint}）
 */
public class MemoryI18nLintTest {
    @Test
    public void testLint() throws Exception {
        new I18nLint(MemoryI18n.messages(), MemoryI18nKeys.class, "MemoryI18n")
                .minCalls(15, 20)
                .verify();
    }
}
