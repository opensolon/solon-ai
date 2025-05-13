package features.ai.flow.app;

import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.flow.FlowEngine;

/**
 * @author noear 2025/5/13 created
 */
@Controller
public class DemoController {
    @Inject
    FlowEngine flowEngine;

    @Mapping("chat_case2")
    public void chat_case2() throws Exception {
        flowEngine.eval("chat_case2");
    }

    @Mapping("rag_case2")
    public void rag_case2() throws Exception {
        flowEngine.eval("rag_case2");
    }

    @Mapping("tool_case2")
    public void tool_case2() throws Exception {
        flowEngine.eval("tool_case2");
    }
}
