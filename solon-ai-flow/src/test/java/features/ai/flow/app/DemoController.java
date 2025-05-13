package features.ai.flow.app;

import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.ChatSessionDefault;
import org.noear.solon.ai.flow.components.Attrs;
import org.noear.solon.ai.flow.events.Events;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.FlowEngine;
import org.noear.solon.flow.stateful.StatefulFlowEngine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author noear 2025/5/13 created
 */
@Controller
public class DemoController {
    @Inject
    StatefulFlowEngine flowEngine;

    Map<String, ChatSession> chatSessionMap = new ConcurrentHashMap<>();

    @Mapping("chat_case2")
    public void chat_case2() throws Exception {
        ChatSession chatSession = chatSessionMap.computeIfAbsent("chat_case2", k -> new ChatSessionDefault());

        FlowContext flowContext = new FlowContext();
        flowContext.put(Attrs.CTX_SESSION, chatSession);
        flowContext.eventBus().listen(Events.EVENT_FLOW_NODE_START, event -> {
            System.out.println(event);
        });

        flowEngine.eval("chat_case2", flowContext);
    }

    @Mapping("rag_case2")
    public void rag_case2() throws Exception {
        ChatSession chatSession = chatSessionMap.computeIfAbsent("rag_case2", k -> new ChatSessionDefault());

        FlowContext flowContext = new FlowContext();
        flowContext.put(Attrs.CTX_SESSION, chatSession);

        flowEngine.eval("rag_case2", flowContext);
    }

    @Mapping("tool_case2")
    public void tool_case2() throws Exception {
        ChatSession chatSession = chatSessionMap.computeIfAbsent("tool_case2", k -> new ChatSessionDefault());

        FlowContext flowContext = new FlowContext();
        flowContext.put(Attrs.CTX_SESSION, chatSession);

        flowEngine.eval("tool_case2", flowContext);
    }
}
