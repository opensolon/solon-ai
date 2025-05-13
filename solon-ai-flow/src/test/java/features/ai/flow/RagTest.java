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
public class RagTest extends HttpTester {
    @Inject
    FlowEngine flowEngine;

    @Test
    public void case1() {
        flowEngine.eval("rag_case1");
    }

    @Test
    public void case2() {
        String rst = path("/rag_case2").data("message", "Solon 是谁开发的？").get();
        System.out.println(rst);
        assert rst.contains("无耳");
    }

    @Test
    public void case2_mock() {
        Context ctx = new ContextEmpty();
        ctx.paramMap().put(Attrs.META_IO_MESSAGE, "Solon 是谁开发的？");

        try {
            ContextHolder.currentSet(ctx);
            flowEngine.eval("rag_case2");

            String rst = ctx.attr("output");
            System.out.println(rst);
            assert rst.contains("无耳");
        } finally {
            ContextHolder.currentRemove();
        }
    }
}