package features.ai.chat;

import features.ai.chat.tool.Case8Tools;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.rx.SimpleSubscriber;
import org.noear.solon.test.SolonTest;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author noear 2025/5/7 created
 */
@SolonTest
public class SiliconflowTest {
    private static final Logger log = LoggerFactory.getLogger(SiliconflowTest.class);
    private static final String apiUrl = "https://api.siliconflow.cn/v1/chat/completions";
    private static final String apiKey = "sk-mrfdvlzykghny";
    private static final String model = "Qwen/Qwen2.5-72B-Instruct";// deepseek-ai/DeepSeek-R1-Distill-Qwen-32B

    private ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of(apiUrl)
                .apiKey(apiKey)
                .model(model);
    }

    @Test
    public void case8() throws Exception {
        ChatModel chatModel = getChatModelBuilder()
                .defaultToolsAdd(new Case8Tools())
                .timeout(Duration.ofSeconds(600))
                .build();

        ArrayList<ChatMessage> list = new ArrayList<>();

        // list.add(chatMessage);
        list.add(ChatMessage.ofUser("2025号3月20日，设备76-51的日用电量是多少"));

        Publisher<ChatResponse> publisher = chatModel.prompt(list)
                .stream();

        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<Throwable> errHolder = new AtomicReference<>();
        publisher.subscribe(new SimpleSubscriber<ChatResponse>()
                .doOnNext(resp -> {
                    if (resp.getMessage().getContent() != null) {
                        System.out.print(resp.getMessage().getContent());
                    }

                }).doOnComplete(() -> {
                    doneLatch.countDown();
                }).doOnError(err -> {
                    err.printStackTrace();

                    errHolder.set(err);
                    doneLatch.countDown();
                }));

        doneLatch.await();

        System.out.println("完成");

        assert errHolder.get() == null;
    }
}
