package demo.ai.flow.case1;

import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.flow.components.Attrs;
import org.noear.solon.annotation.*;
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
    @Mapping("chat")
    public void chat(Context ctx) throws Exception {
        FlowContext flowContext = FlowContext.of();

        //保存会话记录
        ChatSession chatSession = chatSessionMap.computeIfAbsent(ctx.sessionId(), k -> InMemoryChatSession.builder().sessionId(k).build());
        flowContext.put(Attrs.CTX_CHAT_SESSION, chatSession);

        flowEngine.eval("chat1", flowContext);
    }
}