package features.ai.flow;

import features.ai.flow.app.DemoApp;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.flow.components.Attrs;
import org.noear.solon.annotation.Inject;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ContextEmpty;
import org.noear.solon.core.handle.ContextHolder;
import org.noear.solon.flow.FlowEngine;
import org.noear.solon.net.http.textstream.ServerSentEvent;
import org.noear.solon.rx.SimpleSubscriber;
import org.noear.solon.test.HttpTester;
import org.noear.solon.test.SolonTest;

import java.util.concurrent.CountDownLatch;

@SolonTest(DemoApp.class)
public class PkTest extends HttpTester {
    @Inject
    FlowEngine flowEngine;

    @Test
    public void case1() {
        flowEngine.eval("pk_case1");
    }

    @Test
    public void case1_json() {
        flowEngine.eval("pk_case1_json");
    }

    @Test
    public void case2() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        path("/pk_case2").execAsSseStream("GET")
                .subscribe(new SimpleSubscriber<ServerSentEvent>()
                        .doOnNext(e -> {
                            System.out.println(e);
                        }).doOnComplete(() -> {
                            System.out.println("完成...");
                            latch.countDown();
                        })
                        .doOnError(err -> {
                            err.printStackTrace();
                            latch.countDown();
                        }));
        latch.await();
    }
}