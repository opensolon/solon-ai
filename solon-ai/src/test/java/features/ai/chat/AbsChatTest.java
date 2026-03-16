package features.ai.chat;

import features.ai.chat.tool.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.interceptor.*;
import org.noear.solon.ai.chat.message.SystemMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.skill.Skill;
import org.noear.solon.ai.chat.skill.SkillDesc;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.rx.SimpleSubscriber;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
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
                .defaultToolAdd(new Tools())
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
                .defaultToolAdd(new Tools())
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
                .defaultToolAdd(new Tools())
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
                .defaultToolAdd(new Tools())
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
                .defaultToolAdd(new Tools())
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
                .defaultToolAdd(new Tools())
                .build();

        ChatResponse resp = chatModel
                .prompt("solon 框架的作者是谁（个人或公司）？")
                .call();

        //打印消息
        log.info("{}", resp.getMessage());
    }

    @Test
    public void case3_www2_call() throws IOException {
        ChatModel chatModel = getChatModelBuilder()
                .build();

        ChatResponse resp = chatModel
                .prompt(ChatMessage.ofUserAugment("solon 框架的作者是谁（个人或公司）？", new Document()
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

        //流返回(sse)
        Publisher<ChatResponse> publisher = chatModel
                .prompt("今天杭州的天气情况？")
                .session(chatSession)
                .options(o -> o.toolAdd(new Tools()))
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

        //流返回(sse)
        Publisher<ChatResponse> publisher = chatModel
                .prompt("今天杭州的天气情况？")
                .session(chatSession)
                .options(o -> o.toolAdd(new Tools()))
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


        //流返回(sse)
        publisher = chatModel
                .prompt("搜索网络： solon 框架的作者是谁（个人或公司）？")
                .session(chatSession)
                .options(o -> o.toolAdd(new Tools()))
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
                .defaultToolAdd(new ReturnTools())
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
                .defaultToolAdd(new ReturnTools())
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
                .defaultToolAdd(new ReturnTools())
                .build();

        AtomicReference<ChatResponse> respHolder = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        ChatSession chatSession = InMemoryChatSession.builder().build();

        //测试与 reactor 的兼容性
        Flux.from(chatModel.prompt("今天杭州的天气情况？")
                        .session(chatSession)
                        .stream())
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
                .defaultToolAdd(new ReturnTools())
                .build();

        AtomicReference<ChatResponse> respHolder = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        ChatSession chatSession = InMemoryChatSession.builder().build();

        //测试与 reactor 的兼容性
        Flux.from(chatModel.prompt("杭州天气和北京降雨量如何？")
                        .session(chatSession)
                        .stream())
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
                .defaultToolAdd(new Case8Tools())
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
                .defaultToolAdd(new Case10Tools())
                .build();

        String response = chatModel.prompt("杭州的假日景点介绍。要求用 tool 查")
                .call()
                .getMessage()
                .getContent();

        log.warn("{}", response);
        assert Utils.isNotEmpty(response);
        assert response.contains("西湖");
    }

    @Test
    public void case11_skill_call() throws IOException {
        // 1. 定义一个简单的技能
        Skill timeSkill = SkillDesc.builder("time")
                .instruction("当前时间是 2026-01-19，请基于此日期回答。")
                .isSupported(prompt -> {
                    // 只有 prompt 中有 "use_time_skill" 属性时才支持
                    return "true".equals(prompt.attr("use_time_skill"));
                })
                .onAttach(prompt -> {
                    // 挂载时注入一个标识
                    prompt.attrs().put("skill_attached", "time_v1");
                })
                .build();

        ChatModel chatModel = getChatModelBuilder().build();
        ChatSession chatSession = InMemoryChatSession.builder().build();

        // 设置支持条件
        Prompt prompt = Prompt.of("今天几号？")
                .attrPut("use_time_skill", "true");

        // 执行调用
        ChatResponse resp = chatModel.prompt(prompt)
                .session(chatSession)
                .options(o -> o.skillAdd(timeSkill))
                .call();

        log.info("case11 response: {}", resp.getMessage().getContent());

        // 验证：1. 属性是否成功注入 2. 系统消息是否自动添加（1个User + 1个Skill生成的System + 1个Assistant）
        Assertions.assertEquals("time_v1", prompt.attr("skill_attached"));
        Assertions.assertTrue(resp.getMessage().getContent().contains("2026"));
    }

    @Test
    public void case12_skill_stream() throws Exception {
        // 1. 定义一个带工具的技能
        ToolProvider toolProvider = new MethodToolProvider(new Tools());
        Skill weatherSkill = SkillDesc.builder("weather")
                .instruction("你是一个气象专家。")
                .toolAdd(toolProvider)
                .build();

        ChatModel chatModel = getChatModelBuilder().build();
        ChatSession chatSession = InMemoryChatSession.builder().build();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ChatResponse> lastResp = new AtomicReference<>();

        // 流式调用
        chatModel.prompt("杭州天气？")
                .session(chatSession)
                .options(o -> o.skillAdd(weatherSkill))
                .stream()
                .subscribe(new SimpleSubscriber<ChatResponse>()
                        .doOnNext(resp -> {
                            if (resp.isFinished()) {
                                lastResp.set(resp);
                            }
                        })
                        .doOnComplete(latch::countDown)
                        .doOnError(err -> {
                            err.printStackTrace();
                            latch.countDown();
                        }));

        latch.await();

        log.info("case12 final content: {}", lastResp.get().getAggregationMessage().getContent());

        // 验证：1. 消息数量是否包含（User + System + ToolCall + ToolResult + Assistant）
        // 自动工具调用通常会产生至少 5 条消息
        Assertions.assertTrue(chatSession.getMessages().size() >= 4);
        Assertions.assertTrue(lastResp.get().getAggregationMessage().getContent().contains("晴"));
    }

    @Test
    public void case13_time_call() throws Throwable {
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolAdd(new TimeTool())
                .build();

        String time = chatModel.prompt("当前系统时间是几点？")
                .call()
                .getContent();

        System.out.println(time);

        String hour = LocalDateTime.now().getHour() + "";
        assert time.contains(hour);
    }

    @Test
    public void case14_time_stream() throws Throwable {
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolAdd(new TimeTool())
                .build();

        ChatResponse resp = chatModel.prompt("当前系统时间是几点？")
                .stream()
                .blockLast();

        AssistantMessage msg = resp.getAggregationMessage();

        System.out.println(msg.getContent());

        String hour = LocalDateTime.now().getHour() + "";
        assert msg.getContent().contains(hour);
    }

    @Test
    public void case15_tool_error_call() throws Throwable {
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolAdd(new ErrorTool())
                .build();

        Throwable lastThrow = null;

        try {
            chatModel.prompt("当前系统时间是几点？")
                    .call()
                    .getContent();
        } catch (Exception e) {
            lastThrow = e;
            e.printStackTrace();
        }

        assert lastThrow != null;
    }

    @Test
    public void case16_tool_error_stream() throws Throwable {
        AtomicReference<Throwable> lastThrow = new AtomicReference<>();

        ChatModel chatModel = getChatModelBuilder()
                .defaultToolAdd(new ErrorTool())
                .defaultInterceptorAdd(new ChatInterceptor() {
                    @Override
                    public Flux<ChatResponse> interceptStream(ChatRequest req, StreamChain chain) {
                        return chain.doIntercept(req)
                                .doOnError(e -> {
                                    lastThrow.set(e);
                                    e.printStackTrace();
                                });
                    }

                    @Override
                    public ToolResult interceptTool(ToolRequest req, ToolChain chain) throws Throwable {
                        throw new IOException("不支持工具调用");
                    }
                })
                .build();

        ChatResponse resp = chatModel.prompt("当前系统时间是几点？")
                .stream()
                .doOnError(e -> {
                    lastThrow.set(e);
                    e.printStackTrace();
                })
                .blockLast();

        assert lastThrow.get() != null;
    }

    // 1. 定义我们期望输出的数据结构（POJO）
    public static class ResumeInfo {
        public String name;
        public int age;
        public String email;
        public String[] capabilities;
    }

    @Test
    public void case17_outputSchema() throws Throwable {
        ChatModel chatModel = getChatModelBuilder()
                .role("专业的人事助理，擅长简历信息提取")
                .instruction("请从用户提供的文本中提取关键信息")
                .outputSchema(ResumeInfo.class)
                .modelOptions(o -> o.temperature(0.1F))
                .build();


        // 3. 准备业务输入
        String userInput = "你好，我是张三，今年 28 岁。我的邮箱是 zhangsan@example.com。我精通 Java, Solon 和 AI 开发。";

        // 4. 创建会话（用于承载 FlowContext）
        ChatSession session = new InMemoryChatSession("demo");

        // 5. 执行调用
        System.out.println("--- 正在提取信息 ---");
        AssistantMessage message = chatModel.prompt(Prompt.of(userInput)).session(session).call().getMessage();

        // 6. 获取结果
        // 方式 A：从返回值获取
        System.out.println("模型直接返回1: " + message.getContent());
        System.out.println("模型直接返回2: " + message.getResultContent());

        ONode oNodeRef = ONode.ofJson("{\n" +
                "  \"name\": \"张三\",\n" +
                "  \"age\": 28,\n" +
                "  \"email\": \"zhangsan@example.com\",\n" +
                "  \"capabilities\": [\"Java\", \"Solon\", \"AI开发\"]\n" +
                "}");

        ONode oNodeDat = ONode.ofJson(message.getResultContent());

        Assertions.assertEquals(oNodeRef.get("name").getString(),
                oNodeDat.get("name").getString());

        Assertions.assertEquals(oNodeRef.get("age").getString(),
                oNodeDat.get("age").getString());
    }
}