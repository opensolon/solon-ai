package features.ai.flow;

import features.ai.flow.app.DemoApp;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.flow.components.Attrs;
import org.noear.solon.annotation.Inject;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ContextEmpty;
import org.noear.solon.core.handle.ContextHolder;
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

    @Test
    public void case2_mock() {
        Context ctx = new ContextEmpty();
        ctx.paramMap().put(Attrs.META_DATA_IO_MESSAGE, "杭州今天天气怎么样？");

        try {
            ContextHolder.currentSet(ctx);
            flowEngine.eval("tool_case2");

            String rst = ctx.attr("output");
            System.out.println(rst);
            assert rst.contains("晴");
        } finally {
            ContextHolder.currentRemove();
        }
    }
}
