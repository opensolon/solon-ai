package features.ai.flow.app;

import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.ChatSessionDefault;
import org.noear.solon.ai.flow.components.Attrs;
import org.noear.solon.ai.flow.events.Events;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Produces;
import org.noear.solon.core.handle.UploadedFile;
import org.noear.solon.core.util.MimeType;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.stateful.StatefulFlowEngine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Controller
public class DemoController {
    @Inject
    StatefulFlowEngine flowEngine;

    Map<String, ChatSession> chatSessionMap = new ConcurrentHashMap<>();

    @Produces(MimeType.TEXT_EVENT_STREAM_VALUE)
    @Mapping("chat_case2")
    public Object chat_case2(String message, UploadedFile attachment) throws Exception {
        FlowContext flowContext = new FlowContext();

        flowContext.put(Attrs.CTX_MESSAGE, message);
        flowContext.put(Attrs.CTX_ATTACHMENT, attachment);

        //保存会话记录
        ChatSession chatSession = chatSessionMap.computeIfAbsent("chat_case2", k -> new ChatSessionDefault());
        flowContext.put(Attrs.CTX_CHAT_SESSION, chatSession);

        //监听事件
        flowContext.eventBus().listen(Events.EVENT_FLOW_NODE_START, event -> {
            System.out.println(event);
        });

        flowEngine.eval("chat_case2", flowContext);

        return flowContext.get(Attrs.CTX_MESSAGE);
    }

    @Produces(MimeType.TEXT_EVENT_STREAM_VALUE)
    @Mapping("rag_case2")
    public Object rag_case2(String message, UploadedFile attachment) throws Exception {
        FlowContext flowContext = new FlowContext();

        flowContext.put(Attrs.CTX_MESSAGE, message);
        flowContext.put(Attrs.CTX_ATTACHMENT, attachment);

        flowEngine.eval("rag_case2", flowContext);

        return flowContext.get(Attrs.CTX_MESSAGE);
    }

    @Produces(MimeType.TEXT_EVENT_STREAM_VALUE)
    @Mapping("tool_case2")
    public Object tool_case2(String message, UploadedFile attachment) throws Exception {
        FlowContext flowContext = new FlowContext();

        flowContext.put(Attrs.CTX_MESSAGE, message);
        flowContext.put(Attrs.CTX_ATTACHMENT, attachment);

        flowEngine.eval("tool_case2", flowContext);

        return flowContext.get(Attrs.CTX_MESSAGE);
    }
}