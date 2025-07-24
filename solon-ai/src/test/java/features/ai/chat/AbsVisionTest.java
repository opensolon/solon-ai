package features.ai.chat;

import org.noear.solon.ai.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author noear 2025/7/24 created
 */
public abstract class AbsVisionTest {
    private static final Logger log = LoggerFactory.getLogger(AbsVisionTest.class);

    protected abstract ChatModel.Builder getChatModelBuilder();
}
