package features.ai.chat;

import features.ai.chat.interceptor.ChatInterceptorTest;
import features.ai.chat.tool.Case10Tools;
import features.ai.chat.tool.Case8Tools;
import features.ai.chat.tool.ReturnTools;
import features.ai.chat.tool.Tools;
import org.junit.jupiter.api.Assertions;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class OpenRouterR1Test extends AbsThinkTest{
    private static final String apiUrl = "https://openrouter.ai/api/v1/chat/completions";
    private static final String apiKey = "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";
    private static final String model = "tngtech/deepseek-r1t2-chimera:free"; //deepseek/deepseek-r1-0528 //deepseek/deepseek-chat-v3-0324 //tngtech/deepseek-r1t2-chimera:free

    protected ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of(apiUrl)
                .apiKey(apiKey)
                .model(model)
                .timeout(Duration.ofSeconds(160))
                .defaultInterceptorAdd(new ChatInterceptorTest());
    }
}