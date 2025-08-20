package features.ai.core;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.SystemMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;

/**
 *
 * @author noear 2025/8/8 created
 *
 */
@Slf4j
public class InMemoryChatSessionTest {
    @Test
    public void maxSize() {
        InMemoryChatSession session = InMemoryChatSession.builder()
                .maxMessages(3)
                .build();

        session.addMessage(ChatMessage.ofUser("1"));
        session.addMessage(ChatMessage.ofUser("2"));
        session.addMessage(ChatMessage.ofUser("3"));

        log.warn("{}", session.getMessages());
        assert "[{role=user, content='1'}, {role=user, content='2'}, {role=user, content='3'}]".equals(session.getMessages().toString());

        session.addMessage(ChatMessage.ofUser("4"));

        log.warn("{}", session.getMessages());
        assert "[{role=user, content='2'}, {role=user, content='3'}, {role=user, content='4'}]".equals(session.getMessages().toString());

        session.addMessage(ChatMessage.ofUser("5"));

        log.warn("{}", session.getMessages());
        assert "[{role=user, content='3'}, {role=user, content='4'}, {role=user, content='5'}]".equals(session.getMessages().toString());
    }

    @Test
    public void maxSize1() {
        InMemoryChatSession session = InMemoryChatSession.builder()
                .maxMessages(3)
                .systemMessages(ChatMessage.ofSystem("system"))
                .build();

        session.addMessage(ChatMessage.ofUser("1"));
        session.addMessage(ChatMessage.ofUser("2"));
        session.addMessage(ChatMessage.ofUser("3"));

        log.warn("{}", session.getMessages());
        assert "[{role=system, content='system'}, {role=user, content='2'}, {role=user, content='3'}]".equals(session.getMessages().toString());

        session.addMessage(ChatMessage.ofUser("4"));

        log.warn("{}", session.getMessages());
        assert "[{role=system, content='system'}, {role=user, content='3'}, {role=user, content='4'}]".equals(session.getMessages().toString());

        session.addMessage(ChatMessage.ofUser("5"));

        log.warn("{}", session.getMessages());
        assert "[{role=system, content='system'}, {role=user, content='4'}, {role=user, content='5'}]".equals(session.getMessages().toString());
    }
}