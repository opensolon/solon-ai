package features.ai.chat;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;

/**
 * @author noear 2025/7/24 created
 */
public abstract class AbsThinkTest {
    private static final Logger log = LoggerFactory.getLogger(AbsThinkTest.class);

    protected abstract ChatModel.Builder getChatModelBuilder();

    /**
     * 流式会话与序列化：事件消费版
     *
     * <p>旧写法靠采样 {@code AssistantMessage.isThinking()} 反推「既有思考帧也有正文帧」；
     * 现在直接看事件类型，不再依赖帧内标志位。</p>
     */
    @Test
    public void case2() throws Exception {
        ChatModel chatModel = getChatModelBuilder().build();

        ChatSession chatSession = InMemoryChatSession.builder().build();

        //事件流
        ChatEventCollector collector = ChatEventCollector.collect(chatModel.prompt("hello")
                .session(chatSession)
                .stream());

        collector.assertInvariants().assertCompleted();

        //序列化测试
        String ndjson1 = ChatMessage.toNdjson(chatSession.getMessages());
        System.out.println(ndjson1);

        chatSession.clear();
        chatSession.addMessage(ChatMessage.fromNdjson(ndjson1));
        String ndjson2 = ChatMessage.toNdjson(chatSession.getMessages());
        System.out.println(ndjson2);
        assert ndjson1.equals(ndjson2);

        //有思考的，也有非思考的（两类内容各走自己的事件通道，不再混在同一种帧里）
        Assertions.assertTrue(collector.countOf(ChatEventType.THINKING_DELTA) > 0, "应有思考增量");
        Assertions.assertTrue(collector.countOf(ChatEventType.TEXT_DELTA) > 0, "应有正文增量");

        //思考与正文互不混淆
        Assertions.assertTrue(collector.thinking().length() > 0, "应有思考内容");
        Assertions.assertTrue(collector.text().length() > 0, "应有正文内容");
    }

    /**
     * 思考边界：直接断言事件，不再数 {@code <think>} / {@code </think>} 字符串
     *
     * <p>边界由核心 ChatEventNormalizer 保证：THINKING_START / THINKING_END 全流各恰好一次，
     * 且 START 先于 END；思考内容不再以标签形式混入正文。</p>
     */
    @Test
    public void case_trink() throws Exception {
        ChatModel chatModel = getChatModelBuilder()
                .build();

        ChatSession chatSession = InMemoryChatSession.builder().build();

        ChatEventCollector collector = new ChatEventCollector();
        CountDownLatch doneLatch = new CountDownLatch(1);

        //事件流，兼容标准 reactor 操作符
        chatModel.prompt("如何保证睡眠质量？")
                .session(chatSession)
                .stream()
                .doOnNext(collector::onEvent)
                //错误交由 subscribe 的 onError 消费（仅挂 doOnError 再裸 subscribe() 会抛
                //ErrorCallbackNotImplemented 并掩盖真实故障）
                .subscribe(e -> {
                        },
                        err -> {
                            collector.onError(err);
                            doneLatch.countDown();
                        },
                        () -> {
                            log.debug("::完成!");
                            collector.onComplete();
                            doneLatch.countDown();
                        });

        doneLatch.await();

        log.warn(ChatMessage.toNdjson(chatSession.getMessages()));

        //通用契约：顺序、配平、标识一致（思考与正文块不重叠也在其中）
        collector.assertInvariants().assertCompleted();

        //思考边界各恰好一次
        Assertions.assertEquals(1, collector.countOf(ChatEventType.THINKING_START), "THINKING_START 数量");
        Assertions.assertEquals(1, collector.countOf(ChatEventType.THINKING_END), "THINKING_END 数量");
        Assertions.assertTrue(collector.indexOfFirst(ChatEventType.THINKING_START)
                        < collector.indexOfFirst(ChatEventType.THINKING_END),
                "THINKING_START 应先于 THINKING_END");

        //思考先于正文（推理模型的固定次序）
        Assertions.assertTrue(collector.indexOfFirst(ChatEventType.THINKING_END)
                        < collector.indexOfFirst(ChatEventType.TEXT_START),
                "思考应在正文之前闭合");

        //思考有内容，且不以标签形式污染正文
        Assertions.assertTrue(collector.thinking().length() > 0, "应有思考内容");
        Assertions.assertFalse(collector.text().contains("<think>"), "正文不应含 <think>");
        Assertions.assertFalse(collector.text().contains("</think>"), "正文不应含 </think>");

        //思考签名（若方言给了）应有文本负载
        for (org.noear.solon.ai.chat.event.ChatEvent e : collector.events()) {
            if (e.getType() == ChatEventType.THINKING_SIGNATURE) {
                Assertions.assertNotNull(e.getText(), "THINKING_SIGNATURE 应携带签名值");
            }
        }
    }
}
