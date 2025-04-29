package features.ai.chat;

import features.ai.chat.tool.ReturnTools;
import features.ai.chat.tool.Tools;
import org.junit.jupiter.api.Test;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class GiteeaiTest {
    private static final Logger log = LoggerFactory.getLogger(GiteeaiTest.class);
    private static final String apiUrl = "https://ai.gitee.com/v1/chat/completions";
    private static final String apiKey = "PE6JVMP7UQI81GY6AZ0J8WEWWLFHWHROG15XUP18";
    private static final String model = "Qwen2.5-72B-Instruct";//"QwQ-32B";//"DeepSeek-V3"; //deepseek-reasoner//deepseek-chat

    private ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of(apiUrl)
                .apiKey(apiKey)
                .model(model);
    }

    @Test
    public void case1() throws IOException {
        ChatModel chatModel = getChatModelBuilder().build();

        //一次性返回
        ChatResponse resp = chatModel.prompt("hello").call();

        //打印消息
        log.info("{}", resp.getMessage());
    }

    @Test
    public void case2() throws Exception {
        ChatModel chatModel = getChatModelBuilder().build();

        //流返回
        Publisher<ChatResponse> publisher = chatModel.prompt("hello").stream();

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
        assert resp.getMessage() != null;
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
    public void case3_www() throws IOException {
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolsAdd(new Tools())
                .build();

        ChatResponse resp = chatModel
                .prompt("solon 框架的作者是谁？")
                .call();

        //打印消息
        log.info("{}", resp.getMessage());
    }

    @Test
    public void case3_www_2() throws IOException {
        ChatModel chatModel = getChatModelBuilder()
                .build();

        ChatResponse resp = chatModel
                .prompt(ChatMessage.augment("solon 框架的作者是谁？", new Document()
                        .title("概述")
                        .summary("Solon 框架由杭州无耳科技有限公司（下属 Noear 团队）开发并开源。是新一代，Java 企业级应用开发框架。从零开始构建，有自主的标准规范与开放生态。近16万行代码。")
                        .url("https://solon.noear.org/article/about")))
                .call();

        //打印消息
        log.info("{}", resp.getMessage());
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

        chatSession.addMessage(ChatMessage.ofUser("solon 框架的作者是谁？"));

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
}