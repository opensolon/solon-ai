package features.ai.chat;

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

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class OllamaTest {
    private static final Logger log = LoggerFactory.getLogger(OllamaTest.class);
    private static final String apiUrl = "http://127.0.0.1:11434/api/chat";
    private static final String provider = "ollama";
    private static final String model = "qwen2.5:1.5b"; //"llama3.2";//deepseek-r1:1.5b;

    private ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of(apiUrl)
                .provider(provider) //需要指定供应商，用于识别接口风格（也称为方言）
                .model(model);
    }

    @Test
    public void case1() throws IOException {
        ChatModel chatModel = getChatModelBuilder()
                .build();

        //一次性返回
        ChatResponse resp = chatModel.prompt("hello").call();

        //打印消息
        log.info("{}", resp.getMessage());
    }

    @Test
    public void case2() throws Exception {
        ChatModel chatModel = getChatModelBuilder()
                .build();

        ChatSession chatSession = new ChatSessionDefault();
        chatSession.addMessage(ChatMessage.ofUser("hello"));

        //流返回
        Publisher<ChatResponse> publisher = chatModel.prompt(chatSession).stream();

        CountDownLatch doneLatch = new CountDownLatch(1);
        publisher.subscribe(new SimpleSubscriber<ChatResponse>()
                .doOnNext(resp -> {
                    log.info("{}", resp.getMessage());
                }).doOnComplete(() -> {
                    log.debug("::完成!");
                    doneLatch.countDown();
                }).doOnError(err -> {
                    err.printStackTrace();
                    doneLatch.countDown();
                }));

        doneLatch.await();

        //序列化测试
        String ndjson1 = chatSession.toNdjson();
        System.out.println(ndjson1);

        chatSession.clear();
        chatSession.loadNdjson(ndjson1);
        String ndjson2 = chatSession.toNdjson();
        System.out.println(ndjson2);
        assert ndjson1.equals(ndjson2);
    }

    @Test
    public void case3_wather() throws IOException {
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolsAdd(new Tools())
                .build();

        ChatResponse resp = chatModel
                .prompt("今天杭州的天气情况？")
                .call();

        //打印消息
        log.info("{}", resp.getMessage());
    }

    @Test
    public void case3_wather_rainfall() throws IOException {
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolsAdd(new Tools())
                .build();

        ChatResponse resp = chatModel
                .prompt("杭州天气和北京降雨量如何？")
                .call();

        //打印消息
        log.info("{}", resp.getMessage());
        assert resp.getMessage() != null;
    }

    @Test
    public void case3_wather_rainfall_stream() throws Exception {
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolsAdd(new Tools())
                .build();

        AtomicReference<ChatResponse> respHolder = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        chatModel.prompt("杭州天气和北京降雨量如何？")
                .stream()
                .subscribe(new SimpleSubscriber<ChatResponse>()
                        .doOnNext(resp -> {
                            respHolder.set(resp);
                        })
                        .doOnComplete(() -> {
                            latch.countDown();
                        }));

        latch.await();

        //打印消息
        log.info("{}", respHolder.get().getAggregationMessage());

        assert respHolder.get().getAggregationMessage() != null;
        assert respHolder.get().getAggregationMessage().getContent().contains("北京");
    }

    @Test
    public void case4() throws Throwable {
        ChatModel chatModel = getChatModelBuilder()
                .build();

        ChatSession chatSession = new ChatSessionDefault();
        chatSession.addMessage(ChatMessage.ofUser("今天杭州的天气情况？"));

        //流返回(sse)
        Publisher<ChatResponse> publisher = chatModel
                .prompt(chatSession)
                .options(o -> o.toolsAdd(new Tools()))
                .stream();

        AtomicReference<AssistantMessage> msgHolder = new AtomicReference<>();
        CountDownLatch doneLatch = new CountDownLatch(1);
        publisher.subscribe(new SimpleSubscriber<ChatResponse>()
                .doOnNext(resp -> {
                    msgHolder.set(resp.getAggregationMessage());
                }).doOnComplete(() -> {
                    log.debug("::完成!");
                    doneLatch.countDown();
                }).doOnError(err -> {
                    err.printStackTrace();
                    msgHolder.set(null);
                    doneLatch.countDown();
                }));

        doneLatch.await();
        assert msgHolder.get() != null;
        System.out.println(msgHolder.get());

        System.out.println("-----------------------------------");

        System.out.println(chatSession.toNdjson());

        assert chatSession.getMessages().size() == 4;
    }

    @Test
    public void case5() throws Throwable {
        ChatModel chatModel = getChatModelBuilder()
                .build();

        ChatSession chatSession = new ChatSessionDefault();
        chatSession.addMessage(ChatMessage.ofUser("今天杭州的天气情况？"));

        //流返回(sse)
        Publisher<ChatResponse> publisher = chatModel
                .prompt(chatSession)
                .options(o -> o.toolsAdd(new Tools()))
                .stream();

        AtomicReference<AssistantMessage> msgHolder = new AtomicReference<>();
        CountDownLatch doneLatch = new CountDownLatch(1);
        publisher.subscribe(new SimpleSubscriber<ChatResponse>()
                .doOnNext(resp -> {
                    msgHolder.set(resp.getAggregationMessage());
                }).doOnComplete(() -> {
                    log.debug("::完成!");
                    doneLatch.countDown();
                }).doOnError(err -> {
                    msgHolder.set(null);
                    err.printStackTrace();
                    doneLatch.countDown();
                }));

        doneLatch.await();
        assert msgHolder.get() != null;
        System.out.println(msgHolder.get());

        System.out.println("-----------------------------------");

        chatSession.addMessage(ChatMessage.ofUser("搜索网络： solon 框架的作者是谁？"));

        //流返回(sse)
        publisher = chatModel
                .prompt(chatSession)
                .options(o -> o.toolsAdd(new Tools()))
                .stream();

        AtomicReference<AssistantMessage> msgHolder2 = new AtomicReference<>();
        CountDownLatch doneLatch2 = new CountDownLatch(1);
        publisher.subscribe(new SimpleSubscriber<ChatResponse>()
                .doOnNext(resp -> {
                    msgHolder2.set(resp.getAggregationMessage());
                }).doOnComplete(() -> {
                    log.debug("::完成!");
                    doneLatch2.countDown();
                }).doOnError(err -> {
                    msgHolder2.set(null);
                    err.printStackTrace();
                    doneLatch2.countDown();
                }));

        doneLatch2.await();
        assert msgHolder2.get() != null;
        System.out.println(msgHolder2.get());

        System.out.println("-----------------------------------");

        System.out.println(chatSession.toNdjson());

        assert chatSession.getMessages().size() == 8;
    }

    @Test
    public void case6_wather_return() throws IOException {
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolsAdd(new ReturnTools())
                .build();

        ChatResponse resp = chatModel
                .prompt("今天杭州的天气情况？")
                .call();

        //打印消息
        log.info("{}", resp.getMessage());
        assert "晴，24度".equals(resp.getMessage().getContent());
    }

    @Test
    public void case6_wather_rainfall_return() throws IOException {
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolsAdd(new ReturnTools())
                .build();

        ChatResponse resp = chatModel
                .prompt("杭州天气和北京降雨量如何？")
                .call();

        //打印消息
        log.info("{}", resp.getMessage());
        assert "晴，24度\n555毫米".equals(resp.getMessage().getContent());
    }

    @Test
    public void case6_wather_return_stream() throws Exception {
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolsAdd(new ReturnTools())
                .build();

        AtomicReference<ChatResponse> respHolder = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        ChatSession chatSession = new ChatSessionDefault();
        chatSession.addMessage(ChatMessage.ofUser("今天杭州的天气情况？"));

        chatModel.prompt(chatSession)
                .stream()
                .subscribe(new SimpleSubscriber<ChatResponse>()
                        .doOnNext(resp -> {
                            respHolder.set(resp);
                        })
                        .doOnComplete(() -> {
                            latch.countDown();
                        }));

        latch.await();

        //打印消息
        log.info("{}", chatSession.toNdjson());
        log.info("{}", respHolder.get().getAggregationMessage());

        assert chatSession.getMessages().size() == 4;

        assert respHolder.get().getAggregationMessage() != null;
        assert respHolder.get().getAggregationMessage().getContent().equals("晴，24度");
    }


    @Test
    public void case6_wather_rainfall_return_stream() throws Exception {
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolsAdd(new ReturnTools())
                .build();

        AtomicReference<ChatResponse> respHolder = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        ChatSession chatSession = new ChatSessionDefault();
        chatSession.addMessage(ChatMessage.ofUser("杭州天气和北京降雨量如何？"));

        chatModel.prompt(chatSession)
                .stream()
                .subscribe(new SimpleSubscriber<ChatResponse>()
                        .doOnNext(resp -> {
                            respHolder.set(resp);
                        })
                        .doOnComplete(() -> {
                            latch.countDown();
                        }));

        latch.await();

        //打印消息
        log.info("{}", chatSession.toNdjson());
        log.info("{}", respHolder.get().getAggregationMessage());

        assert chatSession.getMessages().size() == 5;

        assert respHolder.get().getAggregationMessage() != null;
        assert respHolder.get().getAggregationMessage().getContent().equals("晴，24度\n" +
                "555毫米");
    }

    @Test
    public void case6_user_return() throws IOException {
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolsAdd(new ReturnTools())
                .build();

        ChatResponse resp = chatModel
                .prompt("查询用户 112 的信息？")
                .call();

        //打印消息
        log.info("{}", resp.getMessage());
        assert "{\"userId\":112,\"name\":\"a1\",\"age\":12}".equals(resp.getMessage().getContent());
    }
}