package features.ai.flow;

import features.ai.flow.app.DemoApp;
import org.junit.jupiter.api.Test;
import org.noear.solon.annotation.Inject;
import org.noear.solon.flow.FlowEngine;
import org.noear.solon.test.HttpTester;
import org.noear.solon.test.SolonTest;

@SolonTest(DemoApp.class)
public class ToolTest extends HttpTester {
    @Inject
    FlowEngine flowEngine;

    @Test
    public void case1() {
        flowEngine.eval("tool_case1");
    }

    @Test
    public void case2() {
        String rst = path("/tool_case2").data("message", "杭州今天天气怎么样?").get();
        System.out.println(rst);
        assert rst.contains("晴");
    }
}
