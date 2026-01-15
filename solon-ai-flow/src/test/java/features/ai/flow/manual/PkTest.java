package features.ai.flow.manual;

import features.ai.flow.manual.app.DemoApp;
import org.junit.jupiter.api.Test;
import org.noear.solon.annotation.Inject;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.FlowEngine;
import org.noear.solon.net.http.textstream.ServerSentEvent;
import org.noear.solon.rx.SimpleSubscriber;
import org.noear.solon.test.HttpTester;
import org.noear.solon.test.SolonTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

@SolonTest(DemoApp.class)
public class PkTest extends HttpTester {
    @Inject
    FlowEngine flowEngine;

    @Test
    public void case1() {
        FlowContext context = FlowContext.of("demo1");
        context.put("demo", new AtomicInteger(0));

        flowEngine.eval("pk_case1", context);
    }

    @Test
    public void case1_json() {
        FlowContext context = FlowContext.of("demo1");
        context.put("demo", new AtomicInteger(0));

        flowEngine.eval("pk_case1_json", context);
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