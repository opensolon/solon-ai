package features.ai.flow.manual;

import features.ai.flow.manual.app.DemoApp;
import org.junit.jupiter.api.Test;
import org.noear.solon.annotation.Inject;
import org.noear.solon.flow.FlowEngine;
import org.noear.solon.test.HttpTester;
import org.noear.solon.test.SolonTest;

@SolonTest(DemoApp.class)
public class McpTest extends HttpTester {
    @Inject
    FlowEngine flowEngine;

    @Test
    public void case1() {
        flowEngine.eval("mcp_case1");
    }

    @Test
    public void case1_json() {
        flowEngine.eval("mcp_case1_json");
    }
}