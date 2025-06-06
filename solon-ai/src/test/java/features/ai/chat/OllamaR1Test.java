package features.ai.chat;

import features.ai.chat.tool.*;
import org.junit.jupiter.api.Test;
import org.noear.solon.Utils;
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

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class OllamaR1Test {
    private static final Logger log = LoggerFactory.getLogger(OllamaR1Test.class);
    private static final String apiUrl = "http://127.0.0.1:11434/api/chat";
    private static final String provider = "ollama";
    private static final String model = "deepseek-r1:1.5b"; //"llama3.2";//deepseek-r1:1.5b;

    private ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of(apiUrl)
                .provider(provider) //需要指定供应商，用于识别接口风格（也称为方言）
                .model(model);
    }

    @Test
    public void case_trink() throws Exception {
        ChatModel chatModel = getChatModelBuilder()
                .build();

        //流返回
        Publisher<ChatResponse> publisher = chatModel.prompt("如何保证睡眠质量？").stream();

        List<String> list = new ArrayList<>();
        CountDownLatch doneLatch = new CountDownLatch(1);
        publisher.subscribe(new SimpleSubscriber<ChatResponse>()
                .doOnNext(resp -> {
                    list.add(resp.getMessage().getContent());
                }).doOnComplete(() -> {
                    log.debug("::完成!");
                    doneLatch.countDown();
                }).doOnError(err -> {
                    err.printStackTrace();
                    doneLatch.countDown();
                }));

        doneLatch.await();

        assert list.stream().filter(s -> s.equals("<think>")).count() == 1;
        assert list.stream().filter(s -> s.equals("</think>")).count() == 1;
    }
}