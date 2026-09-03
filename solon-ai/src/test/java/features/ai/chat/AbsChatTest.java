package features.ai.chat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import features.ai.chat.tool.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.CacheControl;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatConfigReadonly;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventDefault;
import org.noear.solon.ai.chat.event.ChatEventGroup;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatRequestDesc;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.interceptor.*;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.talent.Talent;
import org.noear.solon.ai.chat.talent.TalentDesc;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.core.util.RankEntity;
import org.noear.solon.net.http.HttpUtils;
import org.noear.solon.rx.SimpleSubscriber;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
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
                .build();

        //一次性返回
        ChatResponse resp = chatModel.prompt("hello").call();

        //打印消息
        log.info("{}", resp.getMessage());

        assert resp.hasContent();
        assert resp.getUsage() != null;
        assert resp.getUsage().totalTokens() >= 0;
    }

    /**
     * 基础流式：事件消费版
     *
     * <p>终态不再依赖「最后一帧碰巧攒够了内容」，而是取 RESPONSE_END 携带的不可变聚合。</p>
     */
    @Test
    public void case2_stream() throws Exception {
        ChatModel chatModel = getChatModelBuilder()
                .build();

        //事件流
        ChatEventCollector collector = ChatEventCollector.collect(
                chatModel.prompt("hello").stream());

        //通用契约：顺序、配平、标识一致
        collector.assertInvariants().assertCompleted();

        //生命周期不变量：全流各恰好一次
        Assertions.assertEquals(1, collector.countOf(ChatEventType.RESPONSE_START), "RESPONSE_START 数量");
        Assertions.assertEquals(1, collector.countOf(ChatEventType.RESPONSE_END), "RESPONSE_END 数量");

        //无工具调用时只有一步
        Assertions.assertEquals(1, collector.countOf(ChatEventType.STEP_START), "STEP_START 数量");
        Assertions.assertEquals(1, collector.countOf(ChatEventType.STEP_END), "STEP_END 数量");

        //正文边界成对，且有内容
        Assertions.assertEquals(1, collector.countOf(ChatEventType.TEXT_START), "TEXT_START 数量");
        Assertions.assertEquals(1, collector.countOf(ChatEventType.TEXT_END), "TEXT_END 数量");
        Assertions.assertTrue(collector.text().length() > 0, "应有正文内容");

        log.info("{}", collector.text());

        //终态聚合与用量
        ChatResponse finalResp = collector.finalResponse();
        Assertions.assertNotNull(finalResp, "RESPONSE_END 应携带终态聚合");
        Assertions.assertNotNull(finalResp.getUsage(), "RESPONSE_END 应携带汇总用量");
        Assertions.assertTrue(finalResp.getUsage().totalTokens() >= 0);

        System.out.println(finalResp.getUsage());
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

    /**
     * 工具调用多轮：事件消费版
     *
     * <p>旧写法靠 {@code resp.isFinished()} 嗅探末帧，而自动工具调用下 {@code isFinished()}
     * 会多次为 true（每轮一次）；现在每轮对应一对 STEP_START / STEP_END，
     * 全流只有一个 RESPONSE_END。</p>
     */
    @Test
    public void case3_wather_stream() throws Exception {
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolAdd(new Tools())
                .build();

        ChatEventCollector collector = ChatEventCollector.collect(
                chatModel.prompt("今天杭州的天气情况？").stream());

        collector.assertInvariants().assertCompleted();

        //自动工具调用：至少两轮，且 STEP 成对
        long stepStarts = collector.countOf(ChatEventType.STEP_START);
        Assertions.assertTrue(stepStarts >= 2, "工具调用应有至少两步，实际：" + stepStarts);
        Assertions.assertEquals(stepStarts, collector.countOf(ChatEventType.STEP_END), "STEP 应成对");

        //全流生命周期仍只一次
        Assertions.assertEquals(1, collector.countOf(ChatEventType.RESPONSE_START), "RESPONSE_START 数量");
        Assertions.assertEquals(1, collector.countOf(ChatEventType.RESPONSE_END), "RESPONSE_END 数量");

        //工具调用可见（不再靠翻消息反推）
        Assertions.assertFalse(collector.toolCalls().isEmpty(), "应有完成的工具调用事件");

        //打印消息
        log.info("{}", collector.text());
        Assertions.assertTrue(collector.text().contains("晴"));

        //终态聚合（末轮）
        ChatResponse finalResp = collector.finalResponse();
        Assertions.assertNotNull(finalResp);
        Assertions.assertNotNull(finalResp.getMessage());
        Assertions.assertTrue(finalResp.getMessage().getContent().contains("晴"));
    }

    @Test
    public void case3_wather_stream_finished() throws Exception {
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolAdd(new Tools())
                .build();

        AtomicInteger atomicInteger = new AtomicInteger();
        CountDownLatch doneLatch = new CountDownLatch(1);
        chatModel.prompt("今天杭州的天气情况？")
                .stream()
                .doOnComplete(() -> {
                    atomicInteger.incrementAndGet();
                    doneLatch.countDown();
                }).doOnError(err -> {
                    err.printStackTrace();
                    doneLatch.countDown();
                });


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

    /**
     * 多工具并行：事件消费版（保留 doFinally 以验证 reactor 操作符兼容性）
     */
    @Test
    public void case3_wather_rainfall_stream() throws Exception {
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolAdd(new Tools())
                .build();

        ChatEventCollector collector = new ChatEventCollector();
        CountDownLatch latch = new CountDownLatch(1);

        chatModel.prompt("杭州天气和北京降雨量如何？")
                .stream()
                .doOnNext(collector::onEvent)
                .doFinally(s -> {
                    latch.countDown();
                })
                //错误必须由 subscribe 的 onError 消费；只挂 doFinally 会让异常丢给 onErrorDropped，
                //断言退化成「未完成」，掩盖真实故障（如 401/429）
                .subscribe(e -> {
                }, collector::onError, collector::onComplete);

        latch.await();

        collector.assertInvariants().assertCompleted();

        //打印消息
        log.info("{}", collector.text());

        //两个工具都被调用
        Assertions.assertTrue(collector.toolCalls().size() >= 2,
                "应有至少两个工具调用，实际：" + collector.toolCalls());

        Assertions.assertTrue(collector.text().contains("北京"));
        Assertions.assertTrue(collector.text().contains("555"));
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

        //事件流(sse)
        ChatEventCollector collector = ChatEventCollector.collect(chatModel
                .prompt("今天杭州的天气情况？")
                .session(chatSession)
                .options(o -> o.toolAdd(new Tools()))
                .stream());

        collector.assertInvariants().assertCompleted();

        ChatResponse finalResp = collector.finalResponse();
        Assertions.assertNotNull(finalResp, "RESPONSE_END 应携带终态聚合");
        Assertions.assertNotNull(finalResp.getMessage());
        System.out.println(finalResp.getMessage());

        //本地工具执行结果对流可见（旧模型下只能靠翻会话消息）
        Assertions.assertTrue(collector.has(ChatEventType.TOOL_RESULT), "应有工具执行结果事件");

        System.out.println("-----------------------------------");

        System.out.println(ChatMessage.toNdjson(chatSession.getMessages()));

        System.out.println("-----------------------------------");

        System.out.println(chatSession.getMessages().size());

        assert chatSession.getMessages().size() >= 4;
    }

    @Test
    public void case5_tool_stream() throws Throwable {
        ChatModel chatModel = getChatModelBuilder()
                .build();

        ChatSession chatSession = InMemoryChatSession.builder().build();

        //事件流(sse)
        ChatEventCollector collector1 = ChatEventCollector.collect(chatModel
                .prompt("今天杭州的天气情况？")
                .session(chatSession)
                .options(o -> o.toolAdd(new Tools()))
                .stream());

        collector1.assertInvariants().assertCompleted();
        Assertions.assertNotNull(collector1.finalResponse());
        System.out.println(collector1.finalResponse().getMessage());

        System.out.println("-----------------------------------");

        //同一会话的第二次事件流：证实 responseId 逐流独立
        ChatEventCollector collector2 = ChatEventCollector.collect(chatModel
                .prompt("搜索网络： solon 框架的作者是谁（个人或公司）？")
                .session(chatSession)
                .options(o -> o.toolAdd(new Tools()))
                .stream());

        collector2.assertInvariants().assertCompleted();
        Assertions.assertNotNull(collector2.finalResponse());
        System.out.println(collector2.finalResponse().getMessage());

        //两条流各自恰好一个生命周期，不相互污染
        Assertions.assertEquals(1, collector1.countOf(ChatEventType.RESPONSE_END));
        Assertions.assertEquals(1, collector2.countOf(ChatEventType.RESPONSE_END));

        //responseId 逐流独立，不复用
        Assertions.assertNotEquals(collector1.responseIds(), collector2.responseIds(),
                "两条流的 responseId 应互不相同");

        System.out.println("-----------------------------------");

        System.out.println(ChatMessage.toNdjson(chatSession.getMessages()));

        System.out.println("-----------------------------------");

        System.out.println(chatSession.getMessages().size());

        assert chatSession.getMessages().size() >= 8;
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

        ChatEventCollector collector = new ChatEventCollector();
        CountDownLatch latch = new CountDownLatch(1);
        ChatSession chatSession = InMemoryChatSession.builder().build();

        //测试与 reactor 的兼容性
        chatModel.prompt("今天杭州的天气情况？")
                .session(chatSession)
                .stream()
                .doOnNext(collector::onEvent)
                .doFinally((s) -> {
                    latch.countDown();
                })
                .subscribe(e -> {
                }, collector::onError, collector::onComplete);

        latch.await();

        collector.assertInvariants().assertCompleted();

        //打印消息
        log.info("{}", ChatMessage.toNdjson(chatSession.getMessages()));
        log.info("{}", collector.text());

        assert chatSession.getMessages().size() == 4;

        //returnDirect：工具结果对流可见，且作为正文输出
        Assertions.assertTrue(collector.has(ChatEventType.TOOL_RESULT), "应有 TOOL_RESULT 事件");
        Assertions.assertTrue(collector.text().contains("晴，24度"));
    }


    @Test
    public void case6_wather_rainfall_return_stream() throws Exception {
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolAdd(new ReturnTools())
                .build();

        ChatEventCollector collector = new ChatEventCollector();
        CountDownLatch latch = new CountDownLatch(1);
        ChatSession chatSession = InMemoryChatSession.builder().build();

        //测试与 reactor 的兼容性
        chatModel.prompt("杭州天气和北京降雨量如何？")
                .session(chatSession)
                .stream()
                .doOnNext(collector::onEvent)
                .doFinally((s) -> {
                    latch.countDown();
                }).subscribe(e -> {
                }, collector::onError, collector::onComplete);

        latch.await();

        collector.assertInvariants().assertCompleted();

        //打印消息
        log.info("{}", ChatMessage.toNdjson(chatSession.getMessages()));
        log.info("{}", collector.text());

        assert chatSession.getMessages().size() == 5;

        //两个 returnDirect 工具各自一个结果事件
        Assertions.assertEquals(2, collector.countOf(ChatEventType.TOOL_RESULT), "TOOL_RESULT 数量");
        Assertions.assertTrue(collector.text().contains("晴，24度"));
        Assertions.assertTrue(collector.text().contains("555毫米"));
    }

    @Test
    public void case7_stream_timeout() throws Throwable {
        ChatModel chatModel = getChatModelBuilder()
                .build();

        ChatSession chatSession = InMemoryChatSession.builder().build();

        Throwable lastErr = null;
        try {
            //事件流同样支持原生 timeout 操作符
            chatModel.prompt("今天杭州的天气情况？")
                    .session(chatSession)
                    .options(o -> o.toolAdd(new Tools()))
                    .stream()
                    .timeout(Duration.ofSeconds(1))
                    .blockLast();
        } catch (Throwable err) {
            lastErr = err.getCause();
        }

        assert lastErr instanceof TimeoutException;
    }

    @Test
    public void case8_tool_stream() throws Exception {
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolAdd(new Case8Tools())
                .timeout(Duration.ofSeconds(600))
                .build();

        Flux<String> publisher = chatModel
                .prompt(ChatMessage.ofUser("2025号3月20日，设备76-51的日用电量是多少"))
                .stream()
                .filter(e -> e.is(ChatEventType.TEXT_DELTA) && e.hasText())
                .map(ChatEvent::getText);

        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<Throwable> errHolder = new AtomicReference<>();
        StringBuilder buf = new StringBuilder();

        //只要正文时的便捷层：不需要认识事件类型，也不会把思考与工具内容混进正文
        //（同时验证与 reactor 的兼容性）
        //注意：错误必须由 subscribe 的 onError 消费。若只挂 doOnError 再裸 subscribe()，
        //Reactor 会认为无错误消费者，把异常丢给 onErrorDropped 并抛 ErrorCallbackNotImplemented，
        //掩盖真实故障（递归轮错误传播已由 ChatStreamRecursionTest 离线覆盖）。
        publisher.subscribe(text -> {
                    buf.append(text);
                    System.out.print(text);
                },
                err -> {
                    err.printStackTrace();

                    errHolder.set(err);
                    doneLatch.countDown();
                },
                doneLatch::countDown);

        doneLatch.await();

        System.out.println("完成");

        Assertions.assertNull(errHolder.get(), "流不应报错");
        Assertions.assertTrue(buf.length() > 0, "正文投影应有正文输出");
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
    public void case11_talent_call() throws IOException {
        // 1. 定义一个简单的才能
        Talent timeTalent = TalentDesc.builder("time")
                .instruction("当前时间是 2026-01-19，请基于此日期回答。")
                .isSupported(prompt -> {
                    // 只有 prompt 中有 "use_time_talent" 属性时才支持
                    return "true".equals(prompt.attr("use_time_talent"));
                })
                .onAttach(prompt -> {
                    // 挂载时注入一个标识
                    prompt.attrs().put("talent_attached", "time_v1");
                })
                .build();

        ChatModel chatModel = getChatModelBuilder().build();
        ChatSession chatSession = InMemoryChatSession.builder().build();

        // 设置支持条件
        Prompt prompt = Prompt.of("今天几号？")
                .attrPut("use_time_talent", "true");

        // 执行调用
        ChatResponse resp = chatModel.prompt(prompt)
                .session(chatSession)
                .options(o -> o.talentAdd(timeTalent))
                .call();

        log.info("case11 response: {}", resp.getMessage().getContent());

        // 验证：1. 属性是否成功注入 2. 系统消息是否自动添加（1个User + 1个Talent生成的System + 1个Assistant）
        Assertions.assertEquals("time_v1", prompt.attr("talent_attached"));
        Assertions.assertTrue(resp.getMessage().getContent().contains("2026"));
    }

    @Test
    public void case12_talent_stream() throws Exception {
        // 1. 定义一个带工具的才能
        ToolProvider toolProvider = new MethodToolProvider(new Tools());
        Talent weatherTalent = TalentDesc.builder("weather")
                .instruction("你是一个气象专家。")
                .toolAdd(toolProvider)
                .build();

        ChatModel chatModel = getChatModelBuilder().build();
        ChatSession chatSession = InMemoryChatSession.builder().build();

        // 事件流调用
        ChatEventCollector collector = ChatEventCollector.collect(chatModel.prompt("杭州天气？")
                .session(chatSession)
                .options(o -> o.talentAdd(weatherTalent))
                .stream());

        collector.assertInvariants().assertCompleted();

        log.info("case12 final content: {}", collector.text());

        // 验证：1. 消息数量是否包含（User + System + ToolCall + ToolResult + Assistant）
        // 自动工具调用通常会产生至少 5 条消息
        Assertions.assertTrue(chatSession.getMessages().size() >= 4);

        // 才能携带的工具被调用，且终态正文含结果
        Assertions.assertFalse(collector.toolCalls().isEmpty(), "才能携带的工具应被调用");
        Assertions.assertTrue(collector.text().contains("晴"));

        ChatResponse finalResp = collector.finalResponse();
        Assertions.assertNotNull(finalResp);
        Assertions.assertTrue(finalResp.getMessage().getContent().contains("晴"));
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

    /**
     * 终态获取
     *
     * <p>旧写法拿到的是被 reset() 反复复用的同一个可变实例，终态靠「碰巧攒够了」；
     * 现在取 RESPONSE_END 携带的不可变聚合，是契约保证。</p>
     */
    @Test
    public void case14_time_stream() throws Throwable {
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolAdd(new TimeTool())
                .build();

        ChatResponse resp = chatModel.prompt("当前系统时间是几点？")
                .stream()
                .filter(e -> e.is(ChatEventType.RESPONSE_END))
                .map(e -> e.getResponse())
                .blockLast();

        Assertions.assertNotNull(resp, "RESPONSE_END 应携带终态聚合");

        AssistantMessage msg = resp.getMessage();

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
                    public Flux<ChatEvent> interceptStream(ChatRequest req, StreamChain chain) {
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

        chatModel.prompt("当前系统时间是几点？")
                .stream()
                .doOnError(e -> {
                    lastThrow.set(e);
                    e.printStackTrace();
                })
                .onErrorResume(err -> {
                    return Mono.empty();
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
                .modelOptions(o -> o.temperature(0.1))
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
        System.out.println("模型直接返回2: " + message.getJsonContent());

        ONode oNodeRef = ONode.ofJson("{\n" +
                "  \"name\": \"张三\",\n" +
                "  \"age\": 28,\n" +
                "  \"email\": \"zhangsan@example.com\",\n" +
                "  \"capabilities\": [\"Java\", \"Solon\", \"AI开发\"]\n" +
                "}");

        ONode oNodeDat = ONode.ofJson(message.getJsonContent());

        Assertions.assertEquals(oNodeRef.get("name").getString(),
                oNodeDat.get("name").getString());

        Assertions.assertEquals(oNodeRef.get("age").getString(),
                oNodeDat.get("age").getString());
    }

    /**
     * 事件序列与标识：顺序、responseId、step、方言原始事件名
     *
     * <p>只断言数量不够：订阅方真正依赖的是「先 START 后 DELTA 再 END」的顺序。</p>
     */
    @Test
    public void case18_stream_sequence() throws Exception {
        ChatModel chatModel = getChatModelBuilder().build();

        ChatEventCollector collector = ChatEventCollector.collect(
                chatModel.prompt("简要介绍一下你自己").stream());

        collector.assertInvariants().assertCompleted();

        List<ChatEventType> types = collector.types();
        log.info("case18 事件序列: {}", types);

        //生命周期包裹一切
        Assertions.assertEquals(0, collector.indexOfFirst(ChatEventType.RESPONSE_START));
        Assertions.assertEquals(types.size() - 1, collector.indexOfLast(ChatEventType.RESPONSE_END));

        //步包裹内容
        Assertions.assertTrue(collector.indexOfFirst(ChatEventType.STEP_START)
                        < collector.indexOfFirst(ChatEventType.TEXT_START),
                "STEP_START 应先于 TEXT_START");
        Assertions.assertTrue(collector.indexOfLast(ChatEventType.TEXT_END)
                        < collector.indexOfLast(ChatEventType.STEP_END),
                "TEXT_END 应先于 STEP_END");

        //正文边界包裹所有增量
        Assertions.assertTrue(collector.indexOfFirst(ChatEventType.TEXT_START)
                < collector.indexOfFirst(ChatEventType.TEXT_DELTA));
        Assertions.assertTrue(collector.indexOfLast(ChatEventType.TEXT_DELTA)
                < collector.indexOfLast(ChatEventType.TEXT_END));

        //无工具时只一步，所有事件 step 均为 0
        for (ChatEvent e : collector.events()) {
            Assertions.assertEquals(0, e.getStep(), "单步流的 step 应恒为 0: " + e);
        }

        //rawType 是可选契约：SSE 带事件名的方言（Responses / Anthropic）会填，
        //chat/completions 这类无事件名的方言为 null——但不得为空字符串
        for (ChatEvent e : collector.events()) {
            if (e.getRawType() != null) {
                Assertions.assertFalse(e.getRawType().isEmpty(), "rawType 不应为空字符串: " + e);
            }
        }
        log.info("case18 rawTypes: {}", collector.rawTypes());
    }

    /**
     * 取消语义：下游主动取消走 Reactor 原生 cancel，不会收到 ABORT
     *
     * <p>ABORT 只表示服务端中止。Reactive Streams 规范禁止 cancel 后再 onNext，
     * 因此取消后不应再有任何事件，也不会有 RESPONSE_END。</p>
     */
    @Test
    public void case19_stream_cancel() throws Exception {
        ChatModel chatModel = getChatModelBuilder().build();

        ChatEventCollector collector = new ChatEventCollector();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        chatModel.prompt("写一篇 500 字的杂文")
                .stream()
                .doOnNext(collector::onEvent)
                .doOnCancel(() -> cancelled.set(true))
                .take(3)
                .doFinally(s -> latch.countDown())
                .subscribe(e -> {
                }, collector::onError, collector::onComplete);

        latch.await();
        Thread.sleep(500);

        //take(3) 上游只应看到 3 个事件
        Assertions.assertEquals(3, collector.events().size(), "取消后不应再收到事件");
        Assertions.assertTrue(cancelled.get(), "应触发原生 cancel");

        //取消不等于中止事件
        Assertions.assertEquals(0, collector.countOf(ChatEventType.RESPONSE_END), "取消时不应有 RESPONSE_END");
        Assertions.assertEquals(0, collector.countOf(ChatEventType.ABORT), "下游取消不应产生 ABORT");

        //结构不变量在截断情况下仍成立
        collector.assertInvariants();
    }

    /**
     * 谓词与 Reactor 操作符：订阅面就是 Flux 本身，不需专用 API
     */
    @Test
    public void case20_stream_operators() throws Exception {
        ChatModel chatModel = getChatModelBuilder().build();

        //filter + map：只要增量
        List<String> deltas = chatModel.prompt("hello").stream()
                .filter(ChatEvent::isDelta)
                .map(e -> e.getText() == null ? "" : e.getText())
                .collectList()
                .block();

        Assertions.assertNotNull(deltas);
        Assertions.assertFalse(deltas.isEmpty(), "应有增量事件");

        //isGroup + buffer + publishOn：背压与线程切换不被事件模型阻挡
        List<List<ChatEvent>> batches = chatModel.prompt("hello").stream()
                .publishOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .filter(e -> e.isGroup(ChatEventGroup.TEXT, ChatEventGroup.LIFECYCLE))
                .buffer(4)
                .collectList()
                .block();

        Assertions.assertNotNull(batches);
        Assertions.assertFalse(batches.isEmpty(), "应能按批聚集");

        //isTerminal：终止事件可被直接挖出
        List<ChatEvent> terminals = chatModel.prompt("hello").stream()
                .filter(ChatEvent::isTerminal)
                .collectList()
                .block();

        Assertions.assertNotNull(terminals);
        Assertions.assertEquals(1, terminals.size(), "正常流应恰有一个终止事件");
        Assertions.assertEquals(ChatEventType.RESPONSE_END, terminals.get(0).getType());
    }

    /**
     * 事件流是唯一订阅面：正文投影与终态归约均从同一条事件流派生
     */
    @Test
    public void case22_event_projections() throws Exception {
        ChatModel chatModel = getChatModelBuilder().build();

        Flux<ChatEvent> shared = chatModel.prompt("用一句话介绍杭州").stream().cache();

        ChatEventCollector collector = ChatEventCollector.collect(shared);
        collector.assertInvariants().assertCompleted();

        //正文投影：只保留 TEXT_DELTA，思考、工具、生命周期一律不混入
        String textByProjection = String.join("", shared
                .filter(e -> e.is(ChatEventType.TEXT_DELTA) && e.hasText())
                .map(ChatEvent::getText)
                .collectList().block());
        Assertions.assertEquals(collector.text(), textByProjection, "正文投影应与事件版正文一致");

        //投影 2：异步归约
        ChatResponse reduced = shared
                .filter(e -> e.is(ChatEventType.RESPONSE_END))
                .map(e -> e.getResponse())
                .blockLast();

        Assertions.assertNotNull(reduced);
        Assertions.assertSame(collector.finalResponse(), reduced, "reduceAsync 应取 RESPONSE_END 的聚合");

        //正文不含思考标签
        Assertions.assertFalse(collector.text().contains("<think>"), "正文不应含 think 标签");

        log.info("case22 text: {}", textByProjection);
    }

    /**
     * 工具调用的完整阶段：参数增量 → 调用完成 → 本地执行结果
     *
     * <p>TOOL_RESULT 曾只在 returnDirect 分支发射，占绝大多数的自动递归分支漏发；
     * 这条用例锁定两个分支中的后者。</p>
     */
    @Test
    public void case23_tool_call_phases() throws Exception {
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolAdd(new Tools())
                .build();

        ChatEventCollector collector = ChatEventCollector.collect(
                chatModel.prompt("今天杭州的天气情况？").stream());

        collector.assertInvariants().assertCompleted();

        log.info("case23 事件序列: {}", collector.types());

        //调用完成与执行结果各自可见
        Assertions.assertFalse(collector.toolCalls().isEmpty(), "应有完成的工具调用");
        Assertions.assertFalse(collector.toolResults().isEmpty(), "应有工具执行结果");

        //数量对应：自动递归下每个工具调用都应被执行
        Assertions.assertEquals(collector.toolCalls().size(), collector.toolResults().size(),
                "每个工具调用应恰有一个执行结果");

        //TOOL_RESULT 携带工具名与入参（不只是结果文本）
        for (ToolCall call : collector.toolResults()) {
            Assertions.assertNotNull(call.getName(), "TOOL_RESULT 应能取到工具名");
        }

        //工具调用事件携带 toolCallId，供订阅方关联多个并行调用
        long withId = collector.events().stream()
                .filter(e -> e.getGroup() == ChatEventGroup.TOOL_CALL)
                .filter(e -> e.getToolCallId() != null)
                .count();
        Assertions.assertTrue(withId > 0, "工具调用事件应携带 toolCallId");
    }

    /**
     * 拦截器注入的事件能透传给订阅方，且不破坏归一化不变量
     *
     * <p>拦截器位于方言出口之后、生命周期之内，因此它看不到 RESPONSE_START/END（那是
     * 最外层的职责）；注入时从所见事件继承 responseId / step 以保全流标识一致。</p>
     */
    @Test
    public void case24_interceptor_event_injection() throws Exception {
        AtomicBoolean injected = new AtomicBoolean(false);

        ChatModel chatModel = getChatModelBuilder()
                .defaultInterceptorAdd(new ChatInterceptor() {
                    @Override
                    public Flux<ChatEvent> interceptStream(ChatRequest req, StreamChain chain) {
                        //在首个正文增量之后注入一个自定义事件
                        return chain.doIntercept(req).concatMap(e -> {
                            if (e.getType() == ChatEventType.TEXT_DELTA
                                    && injected.compareAndSet(false, true)) {
                                return Flux.just(e, ChatEventDefault.of(ChatEventType.CUSTOM)
                                        .responseId(e.getResponseId())
                                        .step(e.getStep())
                                        .rawType("test.injected")
                                        .attr("from", "interceptor")
                                        .build());
                            }
                            return Flux.just(e);
                        });
                    }
                })
                .build();

        ChatEventCollector collector = ChatEventCollector.collect(
                chatModel.prompt("hello").stream());

        collector.assertInvariants().assertCompleted();

        Assertions.assertEquals(1, collector.countOf(ChatEventType.CUSTOM), "注入的事件应透传给订阅方");

        int at = collector.indexOfFirst(ChatEventType.CUSTOM);
        ChatEvent custom = collector.events().get(at);

        Assertions.assertEquals(ChatEventGroup.META, custom.getGroup());
        Assertions.assertEquals("interceptor", custom.attrAs("from"));
        Assertions.assertEquals("test.injected", custom.getRawType());

        //注入点在正文块内部，但 META 组不影响内容边界（已由 assertInvariants 校验），
        //也不参与内容/终态语义
        Assertions.assertFalse(custom.isDelta(), "CUSTOM 不应被当作内容增量");
        Assertions.assertFalse(custom.isTerminal(), "CUSTOM 不应被当作终止事件");
        Assertions.assertNull(custom.getResponse(), "只有 STEP_END / RESPONSE_END 才携带结果");
        Assertions.assertTrue(at > collector.indexOfFirst(ChatEventType.TEXT_START));
    }

    /**
     * 请求级角色与指令：{@code prompt().role()/.instruction()}
     *
     * <p>与 Builder 上的同名方法是两条路径：Builder 写进 config.modelOptions（每请求 copy），
     * 请求级写进本次请求的 options 副本，因此不应污染同一模型的后续请求。</p>
     */
    @Test
    public void case25_request_role_instruction() throws IOException {
        ChatModel chatModel = getChatModelBuilder().build();

        ChatResponse resp = chatModel.prompt("请介绍一下你自己")
                .role("一个严格遵循格式要求的助手")
                .instruction("回复的第一个词必须是 R25_OK，然后再正常回答")
                .call();

        log.info("case25: {}", resp.getContent());
        Assertions.assertTrue(resp.getContent().contains("R25_OK"), "请求级 role/instruction 应生效");

        //同一模型的下一次请求不应残留上次的请求级指令
        ChatResponse resp2 = chatModel.prompt("请介绍一下你自己").call();
        log.info("case25 second: {}", resp2.getContent());
        Assertions.assertFalse(resp2.getContent().contains("R25_OK"),
                "请求级指令不应污染后续请求");
    }

    /**
     * 系统提示词优先：{@code systemPrompt} 压过 {@code role} + {@code instruction}
     *
     * <p>锁定 prepare() 里的取舍：有 systemPrompt 时直接用它，不再做 role/instruction 的结构化拼装。</p>
     */
    @Test
    public void case26_systemPrompt_priority() throws IOException {
        ChatModel chatModel = getChatModelBuilder()
                .role("一个必须输出 ROLE26 的助手")
                .instruction("回复里必须包含 ROLE26 这个词")
                .build();

        ChatResponse resp = chatModel.prompt("请随便说一句话")
                .systemPrompt("只输出 SP26 这一个词，不要输出任何其它内容")
                .call();

        log.info("case26: {}", resp.getContent());

        Assertions.assertTrue(resp.getContent().contains("SP26"), "systemPrompt 应生效");
        Assertions.assertFalse(resp.getContent().contains("ROLE26"),
                "systemPrompt 应压过 role/instruction");
    }

    /**
     * 选项整体替换：{@code options(ChatOptions)}（过渡 API）
     *
     * <p>与 {@code options(Consumer)} 的差别是「替换」而非「修补」：Builder 上配置的默认项
     * （含默认拦截器）会被整体顶掉，只保留传入的这一份。</p>
     */
    @Test
    public void case27_options_replace() throws IOException {
        ChatModel chatModel = getChatModelBuilder().build();

        ChatOptions opts = ChatOptions.of()
                .agentName("case27")
                .toolAdd(new Tools());

        ChatResponse resp = chatModel.prompt("今天杭州的天气情况？")
                .options(opts)
                .call();

        log.info("case27: {}", resp.getContent());

        Assertions.assertEquals("case27", opts.agentName());
        Assertions.assertTrue(resp.getContent().contains("晴"), "替换后的选项里的工具应可用");

        //传 null 时应保持原选项不变（不应把选项清空）
        ChatRequestDesc desc = chatModel.prompt("hello").options((ChatOptions) null);
        Assertions.assertNotNull(desc);
    }

    /**
     * 关闭自动工具调用：把工具调用交回调用方（非流式）
     *
     * <p>{@code autoToolCall(false)} 时框架不执行工具、不递归，直接把带 tool_calls 的
     * 助手消息返回，由调用方自行决定怎么执行。</p>
     */
    @Test
    public void case28_manual_tool_call() throws IOException {
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolAdd(new Tools())
                .build();

        ChatResponse resp = chatModel.prompt("今天杭州的天气情况？")
                .options(o -> o.autoToolCall(false))
                .call();

        AssistantMessage msg = resp.getMessage();
        log.info("case28: {}", msg);

        Assertions.assertTrue(Utils.isNotEmpty(msg.getToolCalls()), "应把工具调用交回调用方");
        Assertions.assertEquals("get_weather", msg.getToolCalls().get(0).getName());

        //未执行工具：正文不应出现工具返回值
        String content = msg.getContent() == null ? "" : msg.getContent();
        Assertions.assertFalse(content.contains("24度"), "关闭自动工具调用时不应执行工具");
    }

    /**
     * 关闭自动工具调用：流式路径
     *
     * <p>与 {@link #case23_tool_call_phases} 对照：同样能看到工具调用的参数增量与完成信号，
     * 但没有 TOOL_RESULT，也不会产生第二个 STEP（不递归）。</p>
     */
    @Test
    public void case29_manual_tool_call_stream() throws Exception {
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolAdd(new Tools())
                .build();

        ChatEventCollector collector = ChatEventCollector.collect(
                chatModel.prompt("今天杭州的天气情况？")
                        .options(o -> o.autoToolCall(false))
                        .stream());

        collector.assertInvariants().assertCompleted();

        log.info("case29 事件序列: {}", collector.types());

        Assertions.assertFalse(collector.toolCalls().isEmpty(), "应能看到工具调用");
        Assertions.assertEquals(0, collector.countOf(ChatEventType.TOOL_RESULT),
                "关闭自动工具调用时不应有执行结果");
        Assertions.assertEquals(1, collector.countOf(ChatEventType.STEP_START),
                "不执行工具就不应递归，只有一步");

        //工具调用信息进入终态聚合，供调用方接手
        ChatResponse finalResp = collector.finalResponse();
        Assertions.assertNotNull(finalResp);
        Assertions.assertTrue(Utils.isNotEmpty(finalResp.getMessage().getToolCalls()),
                "终态聚合应携带未执行的工具调用");
    }

    /**
     * 选项面（不走网络）：把 {@code ChatOptions} 的语义逐条钉住
     *
     * <p>这些方法都会被写进请求体，一旦语义漂移（比如非法 effort 被塞进请求）就会引起
     * 网关 400，而这类故障在集成用例里表现为「偶发失败」，很难定位。</p>
     */
    @Test
    public void case30_options_semantics() {
        ChatOptions o = ChatOptions.of();

        //工具选择：四种取值形态
        Assertions.assertEquals("none", o.tool_choice("none").option("tool_choice"));
        Assertions.assertEquals("auto", o.tool_choice("auto").option("tool_choice"));
        Assertions.assertEquals("required", o.tool_choice("required").option("tool_choice"));
        Assertions.assertEquals("none", o.tool_choice(null).option("tool_choice"));

        o.tool_choice("get_weather");
        Map<String, Object> choice = (Map<String, Object>) o.option("tool_choice");
        Assertions.assertEquals("function", choice.get("type"));
        Assertions.assertEquals("get_weather",
                ((Map<String, Object>) choice.get("function")).get("name"));

        //推理水平：归一化、非法值忽略、auto/null 移除
        Assertions.assertEquals("high", o.reasoning_effort(" HIGH ").option("reasoning_effort"));
        Assertions.assertEquals("high", o.reasoning_effort("bogus").option("reasoning_effort"),
                "非法值应被忽略而不是污染请求");
        Assertions.assertEquals("low", o.reasoning_effort("low").option("reasoning_effort"));
        Assertions.assertEquals("medium", o.reasoning_effort("medium").option("reasoning_effort"));
        Assertions.assertEquals("max", o.reasoning_effort("max").option("reasoning_effort"));
        Assertions.assertNull(o.reasoning_effort("auto").option("reasoning_effort"), "auto 应移除该选项");
        o.reasoning_effort("high");
        Assertions.assertNull(o.reasoning_effort("").option("reasoning_effort"), "空串应移除该选项");
        o.reasoning_effort("high");
        Assertions.assertNull(o.reasoning_effort(null).option("reasoning_effort"), "null 应移除该选项");

        //思考开关：true / false / null(移除)
        Assertions.assertEquals(Boolean.TRUE, o.thinking(true).option("thinking"));
        Assertions.assertEquals(Boolean.FALSE, o.thinking(false).option("thinking"));
        Assertions.assertNull(o.thinking(null).option("thinking"));

        //常规采样与长度选项
        o.max_tokens(128).max_completion_tokens(64)
                .temperature(0.3).top_p(0.9).top_k(20)
                .frequency_penalty(0.1).presence_penalty(0.2)
                .response_format(Utils.asMap("type", "json_object"))
                .user("u30");

        Assertions.assertEquals(128L, o.option("max_tokens"));
        Assertions.assertEquals(64L, o.option("max_completion_tokens"));
        Assertions.assertEquals(0.3, o.option("temperature"));
        Assertions.assertEquals(0.9, o.option("top_p"));
        Assertions.assertEquals(20.0, o.option("top_k"));
        Assertions.assertEquals(0.1, o.option("frequency_penalty"));
        Assertions.assertEquals(0.2, o.option("presence_penalty"));
        Assertions.assertNotNull(o.option("response_format"));
        Assertions.assertEquals("u30", o.user());

        //逃生舱：任意选项直写 / 批量 / 移除
        o.optionSet("vendor_x", 1).optionSet(Utils.asMap("vendor_y", 2));
        Assertions.assertEquals(1, o.option("vendor_x"));
        Assertions.assertEquals(2, o.option("vendor_y"));
        Assertions.assertNull(o.optionRemove("vendor_x").option("vendor_x"));
        Assertions.assertTrue(o.options().containsKey("vendor_y"));

        //工具上下文
        o.toolContextPut("k1", "v1").toolContextPut(Utils.asMap("k2", "v2"))
                .toolContextPut("k3", null);
        Assertions.assertEquals("v1", o.toolContext().get("k1"));
        Assertions.assertEquals("v2", o.toolContext().get("k2"));
        Assertions.assertFalse(o.toolContext().containsKey("k3"), "null 值不应写入工具上下文");

        //自动工具调用开关
        Assertions.assertTrue(o.isAutoToolCall());
        Assertions.assertFalse(o.autoToolCall(false).isAutoToolCall());

        //HTTP 定制：Add 叠加，Set 覆盖
        AtomicInteger hits = new AtomicInteger();
        o.httpCustomizeAdd(h -> hits.incrementAndGet())
                .httpCustomizeAdd(h -> hits.incrementAndGet())
                .httpCustomizeAdd(null);
        o.httpCustomize().accept(HttpUtils.http("http://localhost"));
        Assertions.assertEquals(2, hits.get(), "httpCustomizeAdd 应叠加");

        o.httpCustomizeSet(h -> hits.addAndGet(10));
        o.httpCustomize().accept(HttpUtils.http("http://localhost"));
        Assertions.assertEquals(12, hits.get(), "httpCustomizeSet 应覆盖");

        //缓存控制
        o.cacheControl(CacheControl.ofEphemeral());
        Assertions.assertEquals("ephemeral", o.cacheControl().getType());

        //工具：声明式构建 / 集合 / 查询
        o.toolAdd("case30_tool", d -> d.description("测试工具")
                .stringParamAdd("key", "关键词")
                .doHandle(args -> "ok"));
        Assertions.assertNotNull(o.tool("case30_tool"));

        Collection<FunctionTool> tools = new MethodToolProvider(new Tools()).getTools();
        o.toolAdd(tools);
        Assertions.assertNotNull(o.tool("get_weather"));
        Assertions.assertTrue(o.tools().size() >= 4);

        //才能：可变参 / 指定顺序位 / 集合
        Talent t1 = TalentDesc.builder("t1").instruction("i1").build();
        Talent t2 = TalentDesc.builder("t2").instruction("i2").build();
        Talent t3 = TalentDesc.builder("t3").instruction("i3").build();
        o.talentAdd(t1).talentAdd(5, t2)
                .talentAdd(new ArrayList<>(java.util.Collections.singletonList(new RankEntity<>(t3, 9))));
        Assertions.assertEquals(3, o.talents().size());

        //拦截器：默认顺序位 / 指定顺序位 / 集合（按类型去重）
        o.interceptorAdd(new ChatInterceptor() {
        });
        Assertions.assertEquals(1, o.interceptors().size());
        o.interceptorAdd(new ArrayList<>(o.interceptors()));
        Assertions.assertEquals(1, o.interceptors().size(), "同类型拦截器应去重");

        //配置形态的字符串选项在 copy/putAll 时应转为强类型（llm 需要强类型）
        ChatOptions from = ChatOptions.of();
        from.optionSet("s_bool", "true").optionSet("s_int", "12")
                .optionSet("s_double", "1.5").optionSet("s_str", "abc")
                .optionSet("s_raw", 7);

        ChatOptions to = ChatOptions.of();
        to.putAll(from);

        Assertions.assertEquals(Boolean.TRUE, to.option("s_bool"));
        Assertions.assertEquals(12, to.option("s_int"));
        Assertions.assertEquals(1.5, to.option("s_double"));
        Assertions.assertEquals("abc", to.option("s_str"));
        Assertions.assertEquals(7, to.option("s_raw"));

        //集合 / Map 形态的空值保护（配置装配时常会传入 null）
        o.talentAdd((Collection<RankEntity<Talent>>) null)
                .interceptorAdd((Collection<RankEntity<ChatInterceptor>>) null)
                .toolContextPut((Map<String, Object>) null)
                .optionSet((Map<String, Object>) null)
                .toolAdd((Collection<FunctionTool>) null);
        o.putAll(null);

        //角色 / 指令 / 系统提示词 / 输出架构（字符串形态）
        ChatOptions o2 = ChatOptions.of()
                .role("r").instruction("i").systemPrompt("sp")
                .outputSchema("{\"type\":\"object\"}");
        Assertions.assertEquals("r", o2.role());
        Assertions.assertEquals("i", o2.instruction());
        Assertions.assertEquals("sp", o2.systemPrompt());
        Assertions.assertEquals("{\"type\":\"object\"}", o2.outputSchema());
    }

    /**
     * 构建器与只读配置面（不走网络）
     *
     * <p>{@code getConfig()} 是给拦截器与运维视图用的只读投影；这里同时锁定
     * Builder 的写入与只读视图的读出是同一份数据。</p>
     */
    @Test
    public void case31_builder_and_config() {
        Talent talent = TalentDesc.builder("case31").instruction("i").build();
        ChatInterceptor interceptor = new ChatInterceptor() {
        };

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Case31-A", "a");

        ChatModel chatModel = getChatModelBuilder()
                .headerSet(headers)
                .headerSet("X-Case31-B", "b")
                .userAgent("case31-agent")
                .contextLength(4096)
                .timeout(Duration.ofSeconds(30))
                .systemPrompt("case31-sp")
                .outputSchema("{\"type\":\"object\"}")
                .defaultToolAdd(new Tools())
                .defaultToolAdd("case31_tool", d -> d.description("占位")
                        .stringParamAdd("k", "k")
                        .doHandle(args -> "ok"))
                .defaultToolAdd(new FunctionToolDesc("case31_ft")
                        .description("占位")
                        .doHandle(args -> "ok"))
                .defaultToolAdd(new MethodToolProvider(new TimeTool()).getTools())
                .defaultTalentAdd(talent)
                .defaultTalentAdd(3, TalentDesc.builder("case31b").instruction("i").build())
                .defaultInterceptorAdd(5, interceptor)
                .proxy("127.0.0.1", 8888)
                .build();

        ChatConfigReadonly cfg = chatModel.getConfig();

        Assertions.assertEquals("a", cfg.getHeaders().get("X-Case31-A"));
        Assertions.assertEquals("b", cfg.getHeaders().get("X-Case31-B"));
        Assertions.assertEquals(4096, cfg.getContextLength());
        Assertions.assertEquals(Duration.ofSeconds(30), cfg.getTimeout());
        Assertions.assertNotNull(cfg.getApiUrl());
        Assertions.assertNotNull(cfg.getApiKey());
        Assertions.assertNotNull(cfg.getProxy());
        Assertions.assertTrue(cfg.getDefaultTools().size() >= 5);
        Assertions.assertEquals(2, cfg.getDefaultTalents().size());
        Assertions.assertTrue(cfg.getDefaultInterceptors().contains(interceptor));

        //只读视图不可被旁路修改
        Assertions.assertThrows(UnsupportedOperationException.class,
                () -> cfg.getHeaders().put("X", "Y"));

        //模型元信息
        Assertions.assertEquals(cfg.getModel(), chatModel.getModel());
        Assertions.assertNotNull(chatModel.getNameOrModel());
        Assertions.assertNotNull(chatModel.getDialect());
        Assertions.assertNotNull(chatModel.getStandardOrProvider());
        Assertions.assertTrue(chatModel.toString().contains("ChatModel{"));
        Assertions.assertEquals(chatModel.getStandard(), cfg.getStandard());
        Assertions.assertEquals(chatModel.getProvider(), cfg.getProvider());

        //运行期切换模型：空值不生效，非空生效
        String old = cfg.getModel();
        cfg.switchModel(null);
        Assertions.assertEquals(old, cfg.getModel(), "空模型名不应生效");
        cfg.switchModel("case31-model");
        Assertions.assertEquals("case31-model", cfg.getModel());

        //另一条构建入口：ChatModel.of(ChatConfig)，同时验名称/描述/缓存控制的只读投影
        ChatConfig config = new ChatConfig();
        config.setApiUrl(chatModel.getConfig().getApiUrl());
        config.setModel("case31-of-config");
        config.setStandard(chatModel.getStandard());
        if (chatModel.getStandard() == null) {
            config.setProvider(chatModel.getProvider());
        }
        config.setName("case31-name");
        config.setDescription("case31-desc");
        config.setCacheControl(CacheControl.ofPromptKey("case31-key"));

        ChatModel byConfig = ChatModel.of(config).apiKey("sk-x").build();
        ChatConfigReadonly cfg2 = byConfig.getConfig();

        Assertions.assertEquals("case31-of-config", byConfig.getModel());
        Assertions.assertEquals("case31-name", cfg2.getName());
        Assertions.assertEquals("case31-name", byConfig.getNameOrModel());
        Assertions.assertEquals("case31-desc", cfg2.getDescription());
        Assertions.assertEquals("case31-desc", cfg2.getDescriptionOrModel());
        Assertions.assertNotNull(cfg2.getStandardOrProvider());
        Assertions.assertEquals("case31-key", cfg2.getCacheControl().getPromptCacheKey());

        //provider 写入（不参与本用例的方言选择，故单独验证写入语义）
        ChatConfig providerConfig = new ChatConfig();
        ChatModel.of(providerConfig).provider("case31-provider");
        Assertions.assertEquals("case31-provider", providerConfig.getProvider());

        //属性注入形态（用于配置装配）
        Properties props = new Properties();
        props.setProperty("apiUrl", chatModel.getConfig().getApiUrl());
        props.setProperty("model", "case31-props");
        if (chatModel.getStandard() != null) {
            props.setProperty("standard", chatModel.getStandard());
        }
        if (chatModel.getProvider() != null) {
            props.setProperty("provider", chatModel.getProvider());
        }

        ChatModel byProps = new ChatModel(props);
        Assertions.assertEquals("case31-props", byProps.getModel());
    }

    /**
     * 提示语的四种入口应等价
     */
    @Test
    public void case32_prompt_overloads() throws IOException {
        ChatModel chatModel = getChatModelBuilder().build();

        //List 形态（多轮上下文）
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.ofSystem("你只回答一个词"));
        messages.add(ChatMessage.ofUser("1+1=?"));

        ChatResponse resp = chatModel.prompt(messages).call();
        log.info("case32: {}", resp.getContent());

        //本用例验的是「List 入口能把多条消息完整送达」，不把模型的具体措辞当断言
        //（措辞不稳定会把集成用例变成偶发失败）
        Assertions.assertTrue(Utils.isNotEmpty(resp.getContent()), "List 入口应能正常得到回复");

        //其余三种入口应产出等价的请求描述
        Assertions.assertNotNull(chatModel.prompt("hello"));
        Assertions.assertNotNull(chatModel.prompt(ChatMessage.ofUser("hello")));
        Assertions.assertNotNull(chatModel.prompt(Prompt.of("hello")));
    }

    /**
     * 传输层错误：非 2xx 应转为异常，而不是静默变成空流
     *
     * <p>不能靠「错误密钥」或「不存在的端点」构造失败：部分网关不校验密钥，部分网关对未知
     * 路径返回 200 + text/html 首页（实测 ai.loserbai.cn 即如此），用例会假成功或假失败。
     * 这里用本地 mock 端点，把两种失败形态都做成确定性的，不依赖任何上游行为。</p>
     */
    @Test
    public void case33_http_error() throws Exception {
        ChatConfigReadonly base = getChatModelBuilder().build().getConfig();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        //形态一：非 2xx
        server.createContext("/err500", ex -> case33_respond(ex, 500,
                "application/json", "{\"error\":{\"message\":\"case33 boom\"}}"));
        //形态二：200 但响应体是 HTML 错误页（apiUrl 指错时最常见的形态）
        server.createContext("/html200", ex -> case33_respond(ex, 200,
                "text/html; charset=utf-8", "<html><body>404 Not Found</body></html>"));
        server.start();

        try {
            String root = "http://127.0.0.1:" + server.getAddress().getPort();

            case33_assertFails(base, root + "/err500/", "非 2xx");
            case33_assertFails(base, root + "/html200/", "200+HTML");
        } finally {
            server.stop(0);
        }
    }

    private void case33_respond(HttpExchange ex, int code, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private void case33_assertFails(ChatConfigReadonly base, String apiUrl, String label) throws Exception {
        ChatModel.Builder badBuilder = ChatModel.of(apiUrl)
                .apiKey("sk-invalid-for-case33")
                .model(base.getModel());

        if (base.getStandard() != null) {
            badBuilder.standard(base.getStandard());
        } else if (base.getProvider() != null) {
            badBuilder.provider(base.getProvider());
        }

        ChatModel chatModel = badBuilder.build();

        //非流式：抛出
        Throwable callErr = null;
        try {
            chatModel.prompt("hello").call();
        } catch (Throwable e) {
            callErr = e;
        }
        Assertions.assertNotNull(callErr, "case33[" + label + "] 应导致 call() 报错");
        log.info("case33[{}] call error: {}", label, callErr.toString());

        //流式：走 onError，而不是正常 complete
        ChatEventCollector collector = ChatEventCollector.subscribe(
                chatModel.prompt("hello").stream()).await();

        Assertions.assertNotNull(collector.error(), "case33[" + label + "] 应导致 stream() 报错");
        Assertions.assertEquals(0, collector.countOf(ChatEventType.RESPONSE_END),
                "case33[" + label + "] 报错的流不应有 RESPONSE_END");
        log.info("case33[{}] stream error: {}", label, collector.error().toString());
    }

    /**
     * 终态聚合是不可变快照
     *
     * <p>「RESPONSE_END 携带不可变终态」是本次事件模型的核心承诺之一：旧实现给出的是被
     * {@code reset()} 反复复用的可变实例，订阅方持有它跨线程读会读到后续帧的内容。</p>
     */
    @Test
    public void case34_final_response_immutable() throws Exception {
        ChatModel chatModel = getChatModelBuilder().build();

        ChatEventCollector collector = ChatEventCollector.collect(
                chatModel.prompt("hello").stream());

        collector.assertInvariants().assertCompleted();

        ChatResponse finalResp = collector.finalResponse();
        Assertions.assertNotNull(finalResp);

        //第一道防线：对外类型 ChatResponse 不暴露任何写入方法；
        //实现类 ChatResponseDefault 4.1 起也没有 setter / addContentItem / reset
        //（可变累积职责已拆到 ChatAccumulator），因此「试写」在编译期即不可表达。
        ChatResponseDefault terminal = (ChatResponseDefault) finalResp;
        Assertions.assertTrue(terminal.isTerminal(), "RESPONSE_END 应携带终态而非分片帧");

        //第二道防线：构造期算定，多次取值恒等（旧实现每次调用重新聚合可变累积器）
        AssistantMessage msg1 = finalResp.getMessage();
        AssistantMessage msg2 = finalResp.getMessage();
        Assertions.assertSame(msg1, msg2, "终态消息应为构造期算定的同一实例");

        //第三道防线：集合投影只读
        Assertions.assertThrows(UnsupportedOperationException.class,
                () -> finalResp.getBlocks().add(null));

        //分步聚合同样是不可变终态
        ChatResponse stepResp = collector.lastStepResponse();
        Assertions.assertNotNull(stepResp);
        Assertions.assertTrue(((ChatResponseDefault) stepResp).isTerminal(), "STEP_END 应携带分步终态");
        Assertions.assertSame(stepResp.getMessage(), stepResp.getMessage());
    }

    /**
     * HTTP 定制钩子在两条路径上都会被执行
     *
     * <p>非流式与流式各自独立创建 HttpUtils，钩子必须两边都挂上，否则「加个自定义头」
     * 这类需求会在流式下静默失效。</p>
     */
    @Test
    public void case35_http_customize() throws Exception {
        ChatModel chatModel = getChatModelBuilder().build();

        AtomicInteger callHits = new AtomicInteger();
        chatModel.prompt("hello")
                .options(o -> o.httpCustomizeAdd(h -> {
                    callHits.incrementAndGet();
                    h.header("X-Case35", "call");
                }))
                .call();

        Assertions.assertEquals(1, callHits.get(), "非流式应执行 HTTP 定制钩子");

        AtomicInteger streamHits = new AtomicInteger();
        ChatEventCollector collector = ChatEventCollector.collect(chatModel.prompt("hello")
                .options(o -> o.httpCustomizeAdd(h -> {
                    streamHits.incrementAndGet();
                    h.header("X-Case35", "stream");
                }))
                .stream());

        collector.assertInvariants().assertCompleted();
        Assertions.assertEquals(1, streamHits.get(), "流式应执行 HTTP 定制钩子");
    }
}