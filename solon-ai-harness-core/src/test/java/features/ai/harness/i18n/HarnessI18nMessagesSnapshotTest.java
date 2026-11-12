package features.ai.harness.i18n;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.i18n.HarnessI18n;
import org.noear.solon.ai.i18n.testkit.FrozenMessages;

import java.util.Locale;

/**
 * 内置文案的逐字快照（审校基线；场景用例的英文期望值取自这里）
 */
public class HarnessI18nMessagesSnapshotTest {
    @Test
    public void testZh() throws Exception {
        FrozenMessages.verifySnapshot(HarnessI18n.messages(), Locale.CHINESE);
    }

    @Test
    public void testEn() throws Exception {
        FrozenMessages.verifySnapshot(HarnessI18n.messages(), Locale.ENGLISH);
    }
}
