package features.ai.config;

import org.junit.jupiter.api.Test;
import org.noear.solon.Solon;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.test.SolonTest;

/**
 * @author noear 2025/5/16 created
 */
@SolonTest
public class ConfigTest {
    @Test
    public void proxyConfigTest() {
        ChatConfig config = Solon.cfg().toBean("solon.ai.chat.proxy1", ChatConfig.class);
        assert config != null;
        assert config.getProxy() != null;
    }
}
