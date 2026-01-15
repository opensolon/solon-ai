package features.ai.flow.manual;

import features.ai.flow.manual.app.DemoApp;
import org.junit.jupiter.api.Test;
import org.noear.solon.annotation.Inject;
import org.noear.solon.flow.FlowEngine;
import org.noear.solon.test.HttpTester;
import org.noear.solon.test.SolonTest;

@SolonTest(DemoApp.class)
public class RagTest extends HttpTester {
    @Inject
    FlowEngine flowEngine;

    @Test
    public void case1() {
        flowEngine.eval("rag_case1");
    }

    @Test
    public void case1_json() {
        flowEngine.eval("rag_case1_json");
    }

    @Test
    public void case2() {
        String rst = path("/rag_case2").data("message", "Solon 是谁开发的？").get();
        System.out.println(rst);
        assert rst.contains("无耳");
    }
}