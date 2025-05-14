package features.ai.chat;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.rx.SimpleSubscriber;
import org.noear.solon.test.SolonTest;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * @author noear 2025/1/28 created
 */
@SolonTest
public class OpenaiR1Test {
    //JQC6M0GTNPGSCEXZOBUGUX0HVHCOLDIMN6XOSSSA
    private static final Logger log = LoggerFactory.getLogger(OpenaiR1Test.class);
    private static final String apiUrl = "https://api.deepseek.com/v1/chat/completions";
    private static final String apiKey = "sk-9f4415ddc570496581897c22e3d41a54";
    private static final String model = "deepseek-reasoner"; //deepseek-reasoner//deepseek-chat

    private ChatModel.Builder getChatModelBuilder() {
        return ChatModel.of(apiUrl)
                .apiKey(apiKey)
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