package features.ai.flow.manual;

import features.ai.flow.manual.app.DemoApp;
import org.junit.jupiter.api.Test;
import org.noear.solon.annotation.Inject;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.FlowEngine;
import org.noear.solon.test.SolonTest;

/**
 *
 * @author noear 2025/11/18 created
 *
 */
@SolonTest(DemoApp.class)
public class GenerateTest {
    @Inject
    FlowEngine flowEngine;

    @Test
    public void case1() {
        FlowContext flowContext =  FlowContext.of();
        flowEngine.eval("generate_case1", flowContext);

    }
}
