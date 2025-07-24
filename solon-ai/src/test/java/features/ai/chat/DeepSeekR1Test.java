package features.ai.chat;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.ChatSessionDefault;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.rx.SimpleSubscriber;
import org.noear.solon.test.SolonTest;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class DeepSeekR1Test extends AbsThinkTest{
    //JQC6M0GTNPGSCEXZOBUGUX0HVHCOLDIMN6XOSSSA
    private static final Logger log = LoggerFactory.getLogger(DeepSeekR1Test.class);
    private static final String apiUrl = "https://api.deepseek.com/v1/chat/completions";
    private static final String apiKey = "sk-9f4415ddc570496581897c22e3d41a54";
    private static final String model = "deepseek-reasoner"; //deepseek-reasoner//deepseek-chat

    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of(apiUrl)
                .apiKey(apiKey)
                .model(model);
    }
}