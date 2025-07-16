package features.ai.chat;

import features.ai.chat.tool.*;
import org.junit.jupiter.api.Assertions;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
    public void case2() throws Exception {
        ChatModel chatModel = getChatModelBuilder().build();

        ChatSession chatSession = new ChatSessionDefault();
        chatSession.addMessage(ChatMessage.ofUser("hello"));

        //流返回
        Publisher<ChatResponse> publisher = chatModel.prompt(chatSession).stream();
        List<AssistantMessage> assistantMessageList = new ArrayList<>();

        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicBoolean done = new AtomicBoolean(false);
        publisher.subscribe(new SimpleSubscriber<ChatResponse>()
                .doOnNext(resp -> {
                    log.info("{} - {}", resp.isFinished(), resp.getMessage());
                    assistantMessageList.add(resp.getMessage());
                    done.set(resp.isFinished());
                }).doOnComplete(() -> {
                    log.debug("::完成!");
                    doneLatch.countDown();
                }).doOnError(err -> {
                    doneLatch.countDown();
                    err.printStackTrace();
                }));

        doneLatch.await();
        assert done.get();


        //序列化测试
        String ndjson1 = chatSession.toNdjson();
        System.out.println(ndjson1);

        chatSession.clear();
        chatSession.loadNdjson(ndjson1);
        String ndjson2 = chatSession.toNdjson();
        System.out.println(ndjson2);
        assert ndjson1.equals(ndjson2);

        //有思考的，也有非思考的
        assert assistantMessageList.stream().filter(m -> m.isThinking()).count() > 0;
        assert assistantMessageList.stream().filter(m -> m.isThinking() == false).count() > 0;
    }

    @Test
    public void case_trink() throws Exception {
        ChatModel chatModel = getChatModelBuilder()
                .build();

        ChatSession chatSession = new ChatSessionDefault();
        chatSession.addMessage("如何保证睡眠质量？");

        //流返回
        Publisher<ChatResponse> publisher = chatModel.prompt(chatSession).stream();

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

        log.warn(chatSession.toNdjson());

        Assertions.assertEquals(1, list.stream().filter(s -> s.equals("<think>")).count(), "<think> 数量");
        Assertions.assertEquals(1, list.stream().filter(s -> s.equals("</think>")).count(), "</think> 数量");
    }
}