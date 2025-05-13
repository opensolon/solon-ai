package features.ai.flow;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.flow.components.Attrs;
import org.noear.solon.annotation.Inject;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.ContextEmpty;
import org.noear.solon.core.handle.ContextHolder;
import org.noear.solon.flow.FlowEngine;
import org.noear.solon.test.SolonTest;

@SolonTest
public class ChatTest {
    @Inject
    FlowEngine flowEngine;

    @Test
    public void case1() {
        flowEngine.eval("case1");
    }

    @Test
    public void case2() {
        Context ctx = new ContextEmpty();
        ctx.paramMap().put(Attrs.META_DATA_IO_MESSAGE, "hello");

        try {
            ContextHolder.currentSet(ctx);
            flowEngine.eval("case2");

            System.out.println((String) ctx.attr("output"));
        } finally {
            ContextHolder.currentRemove();
        }
    }

    @Test
    public void case3() {
        flowEngine.eval("case3");
    }
}