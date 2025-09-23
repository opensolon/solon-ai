package demo.ai.mcp.server;

import lombok.extern.slf4j.Slf4j;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Produces;
import org.noear.solon.core.util.MimeType;
import org.noear.solon.web.sse.SseEvent;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * @author noear 2025/4/14 created
 */
@Slf4j
@Controller
public class SseController {
    @Produces(MimeType.TEXT_EVENT_STREAM_VALUE)
    @Mapping("/test/sse1")
    public Flux<SseEvent> sse1() {
        String sessionId = UUID.randomUUID().toString();

        // Send initial endpoint event
        return Flux.just(new SseEvent()
                .name("test")
                .data("/message?sessionId=" + sessionId));

    }

    @Produces(MimeType.TEXT_EVENT_STREAM_VALUE)
    @Mapping("/test/sse2")
    public Flux<SseEvent> sse2() {
        return Flux.create(sink -> {
            String sessionId = UUID.randomUUID().toString();

            log.debug("Created new SSE connection for session: {}", sessionId);

            // Send initial endpoint event
            sink.next(new SseEvent()
                    .name("test")
                    .data("/message?sessionId=" + sessionId));
            sink.onCancel(() -> {
                log.debug("Session {} cancelled", sessionId);
            });
        });
    }
}