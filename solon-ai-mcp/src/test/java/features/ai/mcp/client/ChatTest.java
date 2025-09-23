package features.ai.mcp.client;

import demo.ai.mcp.server.McpServerApp;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.noear.solon.test.HttpTester;
import org.noear.solon.test.SolonTest;
import reactor.core.publisher.Flux;

import java.util.concurrent.CountDownLatch;

/**
 *
 * @author noear 2025/9/23 created
 *
 */
@Slf4j
@SolonTest(McpServerApp.class)
public class ChatTest extends HttpTester {
    @Test
    public void chatStream() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder message = new StringBuilder();

        Flux.from(path("/test/stream").data("prompt", "hello")
                        .execAsSseStream("POST"))
                .doOnNext(sse -> {
                    System.out.println(sse);
                    message.append(sse.getData());
                }).doOnComplete(() -> {
                    latch.countDown();
                })
                .doOnError(err -> {
                    err.printStackTrace();
                    latch.countDown();
                })
                .subscribe();

        latch.await();

        System.out.println("-------message: "+message.toString());

        assert message.length() > 0;
        assert message.toString().endsWith("[DONE]");
    }
}
