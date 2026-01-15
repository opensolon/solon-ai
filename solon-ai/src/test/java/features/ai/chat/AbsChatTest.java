package features.ai.chat;

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
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.rx.SimpleSubscriber;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author noear 2025/7/24 created
 */
public abstract class AbsChatTest {
    private static final Logger log = LoggerFactory.getLogger(AbsChatTest.class);

    protected abstract ChatModel.Builder getChatModelBuilder();

    @Test
    public void case1_call() throws IOException {
        ChatModel chatModel = getChatModelBuilder()
                .timeout(Duration.ofMinutes(10))
                .build();

        //一次性返回
        ChatResponse resp = chatModel.prompt("hello").call();

        //打印消息
        log.info("{}", resp.getMessage());
    }

    @Test
    public void case2_stream() throws Exception {
        ChatModel chatModel = getChatModelBuilder()
                .build();

        //流返回
        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicBoolean done = new AtomicBoolean(false);
        chatModel.prompt("hello").stream()
                .subscribe(new SimpleSubscriber<ChatResponse>()
                        .doOnNext(resp -> {
                            log.info("{} - {}", resp.isFinished(), resp.getMessage());
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
    }

    @Test
    public void case3_wather_call() throws IOException {
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolsAdd(new Tools())
                .build();

        ChatResponse resp = chatModel
                .prompt("今天杭州的天气情况？")
                .call();

        //打印消息
        log.info("{}", resp.getMessage());
        assert resp.getMessage() != null;
        assert resp.getMessage().getContent().contains("晴");
    }

    @Test
    public void case3_wather_stream() throws Exception {
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolsAdd(new Tools())
                .build();

        AtomicReference<ChatResponse> respRef = new AtomicReference<>();
        CountDownLatch doneLatch = new CountDownLatch(1);

        Flux.from(chatModel.prompt("今天杭州的天气情况？").stream())
                .doOnNext(resp -> {
                    if (resp.isFinished()) {
                        respRef.set(resp);
                    }
                }).doOnComplete(() -> {
                    doneLatch.countDown();
                }).doOnError(err -> {
                    err.printStackTrace();
                    doneLatch.countDown();
                })
                .subscribe();

        doneLatch.await();
        assert respRef.get() != null;

        //打印消息
        log.info("{}", respRef.get().getAggregationMessage());
        assert respRef.get().getAggregationMessage() != null;
        assert respRef.get().getAggregationMessage().getContent().contains("晴");
    }

    @Test
    public void case3_wather_stream_finished() throws Exception {
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolsAdd(new Tools())
                .build();

        AtomicInteger atomicInteger = new AtomicInteger();
        CountDownLatch doneLatch = new CountDownLatch(1);
        chatModel.prompt("今天杭州的天气情况？")
                .stream().subscribe(new SimpleSubscriber<ChatResponse>()
                        .doOnComplete(() -> {
                            atomicInteger.incrementAndGet();
                            doneLatch.countDown();
                        }).doOnError(err -> {
                            err.printStackTrace();
                            doneLatch.countDown();
                        }));

        doneLatch.await();
        Thread.sleep(100);
        Assertions.assertEquals(1, atomicInteger.get(), "完成事件");
    }

    @Test
    public void case3_wather_rainfall_call() throws IOException {
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolsAdd(new Tools())
                .build();

        ChatResponse resp = chatModel
                .prompt("杭州天气和北京降雨量如何？")
                .call();

        //打印消息
        log.info("{}", resp.getMessage());
        assert resp.getMessage() != null;
        assert resp.getMessage().getContent().contains("晴");
        assert resp.getMessage().getContent().contains("555");
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
        assert respHolder.get().getAggregationMessage().getContent().contains("555");
    }


    @Test
    public void case3_www_call() throws IOException {
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
    public void case3_www2_call() throws IOException {
        ChatModel chatModel = getChatModelBuilder()
                .build();

        ChatResponse resp = chatModel
                .prompt(ChatMessage.ofUserAugment("solon 框架的作者是谁？", new Document()
                        .title("概述")
                        .url("https://solon.noear.org/article/about")))
                .call();

        //打印
        System.out.println(resp.getMessage());
        assert resp.hasContent();
        assert resp.getContent().contains("solon") || resp.getContent().contains("Solon");
    }

    @Test
    public void case4_tool_stream() throws Throwable {
        ChatModel chatModel = getChatModelBuilder()
                .build();

        ChatSession chatSession = InMemoryChatSession.builder().build();
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

        System.out.println("-----------------------------------");

        System.out.println(chatSession.getMessages().size());

        assert chatSession.getMessages().size() == 4;
    }

    @Test
    public void case5_tool_stream() throws Throwable {
        ChatModel chatModel = getChatModelBuilder()
                .build();

        ChatSession chatSession = InMemoryChatSession.builder().build();
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

        System.out.println("-----------------------------------");

        System.out.println(chatSession.getMessages().size());

        assert chatSession.getMessages().size() == 8;
    }


    @Test
    public void case6_wather_return_call() throws IOException {
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
    public void case6_wather_rainfall_return_call() throws IOException {
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
        ChatSession chatSession = InMemoryChatSession.builder().build();
        chatSession.addMessage(ChatMessage.ofUser("今天杭州的天气情况？"));

        //测试与 reactor 的兼容性
        Flux.from(chatModel.prompt(chatSession).stream())
                .doOnNext(resp -> {
                    respHolder.set(resp);
                })
                .doOnComplete(() -> {
                    latch.countDown();
                })
                .subscribe();

        latch.await();

        //打印消息
        log.info("{}", chatSession.toNdjson());
        log.info("{}", respHolder.get().getAggregationMessage());

        assert chatSession.getMessages().size() == 4;

        assert respHolder.get().getAggregationMessage() != null;
        assert respHolder.get().getAggregationMessage().getContent().contains("晴，24度");
    }


    @Test
    public void case6_wather_rainfall_return_stream() throws Exception {
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolsAdd(new ReturnTools())
                .build();

        AtomicReference<ChatResponse> respHolder = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        ChatSession chatSession = InMemoryChatSession.builder().build();
        chatSession.addMessage(ChatMessage.ofUser("杭州天气和北京降雨量如何？"));

        //测试与 reactor 的兼容性
        Flux.from(chatModel.prompt(chatSession).stream())
                .doOnNext(resp -> {
                    respHolder.set(resp);
                })
                .doOnComplete(() -> {
                    latch.countDown();
                }).subscribe();

        latch.await();

        //打印消息
        log.info("{}", chatSession.toNdjson());
        log.info("{}", respHolder.get().getAggregationMessage());

        assert chatSession.getMessages().size() == 5;

        assert respHolder.get().getAggregationMessage() != null;
        assert respHolder.get().getAggregationMessage().getContent().contains("晴，24度");
        assert respHolder.get().getAggregationMessage().getContent().contains("555毫米");
    }

    @Test
    public void case8_tool_stream() throws Exception {
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolsAdd(new Case8Tools())
                .timeout(Duration.ofSeconds(600))
                .build();

        Publisher<ChatResponse> publisher = chatModel
                .prompt(ChatMessage.ofUser("2025号3月20日，设备76-51的日用电量是多少"))
                .stream();

        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<Throwable> errHolder = new AtomicReference<>();

        //测试与 reactor 的兼容性
        Flux.from(publisher).doOnNext(resp -> {
                    if (resp.getMessage().getContent() != null) {
                        System.out.print(resp.getMessage().getContent());
                    }

                }).doOnComplete(() -> {
                    doneLatch.countDown();
                }).doOnError(err -> {
                    err.printStackTrace();

                    errHolder.set(err);
                    doneLatch.countDown();
                })
                .subscribe();

        doneLatch.await();

        System.out.println("完成");

        assert errHolder.get() == null;
    }

    @Test
    public void case10_tool_call() throws Exception {
        //没有参数的工具
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolsAdd(new Case10Tools())
                .build();

        String response = chatModel.prompt("杭州的假日景点介绍。要求用 tool 查")
                .call()
                .getMessage()
                .getContent();

        log.warn("{}", response);
        assert Utils.isNotEmpty(response);
        assert response.contains("西湖");
    }
}