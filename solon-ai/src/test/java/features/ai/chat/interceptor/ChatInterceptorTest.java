package features.ai.chat.interceptor;

import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.interceptor.*;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.io.IOException;

/**
 * @author noear 2025/5/30 created
 */
public class ChatInterceptorTest implements ChatInterceptor {
    private static final Logger log = LoggerFactory.getLogger(ChatInterceptorTest.class);
    @Override
    public ChatResponse interceptCall(ChatRequest req, CallChain chain) throws IOException {
        log.warn("ChatInterceptor-interceptCall: " + req.getConfig().getModel());
        return chain.doIntercept(req);
    }

    @Override
    public Flux<ChatResponse> interceptStream(ChatRequest req, StreamChain chain) {
        log.warn("ChatInterceptor-interceptStream: " + req.getConfig().getModel());
        return chain.doIntercept(req);
    }

    @Override
    public ToolResult interceptTool(ToolRequest req, ToolChain chain) throws Throwable {
        log.warn("ChatInterceptor-interceptTool: " + req.getRequest().getConfig().getModel());

        return chain.doIntercept(req);
    }
}