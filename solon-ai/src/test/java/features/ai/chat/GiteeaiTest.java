package features.ai.chat;

import features.ai.chat.interceptor.ChatInterceptorTest;
import features.ai.chat.tool.Case10Tools;
import features.ai.chat.tool.ReturnTools;
import features.ai.chat.tool.Case8Tools;
import features.ai.chat.tool.Tools;
import org.junit.jupiter.api.Test;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.ChatSessionDefault;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.rx.SimpleSubscriber;
import org.noear.solon.test.SolonTest;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class GiteeaiTest extends AbsChatTest{
    private static final String apiUrl = "https://ai.gitee.com/v1/chat/completions";
    private static final String apiKey = "PE6JVMP7UQI81GY6AZ0J8WEWWLFHWHROG15XUP18";
    private static final String model = "Qwen2.5-72B-Instruct";//"Qwen3-32B";//"QwQ-32B";//"DeepSeek-V3"; //deepseek-reasoner//deepseek-chat

    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of(apiUrl)
                .apiKey(apiKey)
                .model(model)
                .defaultInterceptorAdd(new ChatInterceptorTest());
    }
}