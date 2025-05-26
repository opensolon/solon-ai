package features.ai.flow;

import features.ai.flow.app.DemoApp;
import org.junit.jupiter.api.Test;
import org.noear.solon.annotation.Inject;
import org.noear.solon.flow.FlowEngine;
import org.noear.solon.test.HttpTester;
import org.noear.solon.test.SolonTest;

@SolonTest(DemoApp.class)
public class ChatTest extends HttpTester {
    @Inject
    FlowEngine flowEngine;

    @Test
    public void case1() {
        flowEngine.eval("chat_case1");
    }

    @Test
    public void case1_json() {
        flowEngine.eval("chat_case1_json");
    }

    @Test
    public void case2() {
        String rst = path("/chat_case2").data("message", "你好").get();
        System.out.println(rst);
        assert rst.contains("你好");
    }

    @Test
    public void case2_json() {
        String rst = path("/chat_case2_json").data("message", "你好").get();
        System.out.println(rst);
        assert rst.contains("你好");
    }
}