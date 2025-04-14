package features.ai.mcp.client;

import demo.ai.mcp.server.McpServerApp;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.noear.solon.net.http.textstream.ServerSentEvent;
import org.noear.solon.rx.SimpleSubscriber;
import org.noear.solon.test.HttpTester;
import org.noear.solon.test.SolonTest;

import java.util.concurrent.CountDownLatch;

/**
 * @author noear 2025/4/14 created
 */
@Slf4j
@SolonTest(McpServerApp.class)
public class SseClientTest extends HttpTester {
    @Test
    public void case1() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        path("/test/sse1").execAsSseStream("GET")
                .subscribe(new SimpleSubscriber<ServerSentEvent>()
                        .doOnNext(event -> {
                            System.out.println(event);
                        }).doOnComplete(latch::countDown));

        latch.await();
    }

    @Test
    public void case2() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        path("/test/sse2").execAsSseStream("GET")
                .subscribe(new SimpleSubscriber<ServerSentEvent>()
                        .doOnNext(event -> {
                            System.out.println(event);
                            latch.countDown();
                        }).doOnComplete(latch::countDown));

        latch.await();
    }
}
