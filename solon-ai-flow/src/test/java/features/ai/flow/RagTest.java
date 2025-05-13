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
public class RagTest {
    @Inject
    FlowEngine flowEngine;

    @Test
    public void case3() {
        flowEngine.eval("case3");
    }
}