package features.ai.flow.app;

import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.flow.components.Attrs;
import org.noear.solon.ai.flow.events.Events;
import org.noear.solon.ai.flow.events.NodeEvent;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Produces;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.util.MimeType;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.FlowEngine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Controller
public class DemoController {
    @Inject
    FlowEngine flowEngine;

    Map<String, ChatSession> chatSessionMap = new ConcurrentHashMap<>();

    @Produces(MimeType.TEXT_EVENT_STREAM_VALUE)
    @Mapping("chat_case2")
    public void chat_case2(Context ctx) throws Exception {
        FlowContext flowContext = new FlowContext();

        //事件
        flowContext.<NodeEvent, String>eventBus().listen(Events.EVENT_FLOW_NODE_END, (event) -> {
            event.getContent().getContext();
        });

        //保存会话记录
        ChatSession chatSession = chatSessionMap.computeIfAbsent(ctx.sessionId(), k -> InMemoryChatSession.builder().sessionId(ctx.sessionId()).build());
        flowContext.put(Attrs.CTX_CHAT_SESSION, chatSession);

        flowEngine.eval("chat_case2", flowContext);
    }

    @Produces(MimeType.TEXT_EVENT_STREAM_VALUE)
    @Mapping("chat_case2_json")
    public void chat_case2_json(Context ctx) throws Exception {
        FlowContext flowContext = new FlowContext();

        //保存会话记录
        ChatSession chatSession = chatSessionMap.computeIfAbsent(ctx.sessionId(), k -> InMemoryChatSession.builder().sessionId(ctx.sessionId()).build());
        flowContext.put(Attrs.CTX_CHAT_SESSION, chatSession);

        flowEngine.eval("chat_case2_json", flowContext);
    }

    @Produces(MimeType.TEXT_EVENT_STREAM_VALUE)
    @Mapping("pk_case2")
    public void pk_case2(Context ctx) throws Exception {
        FlowContext flowContext = new FlowContext();

        //保存会话记录
        ChatSession chatSession = chatSessionMap.computeIfAbsent(ctx.sessionId(), k -> InMemoryChatSession.builder().sessionId(ctx.sessionId()).build());
        flowContext.put(Attrs.CTX_CHAT_SESSION, chatSession);

        flowEngine.eval("pk_case2", flowContext);
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