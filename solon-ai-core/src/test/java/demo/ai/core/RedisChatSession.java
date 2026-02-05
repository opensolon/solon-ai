package demo.ai.core;

import org.noear.redisx.RedisClient;
import org.noear.redisx.plus.RedisList;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;

import java.util.Collection;
import java.util.List;

/**
 *
 * @author noear 2025/12/28 created
 *
 */
public class RedisChatSession implements ChatSession {
    private final String sessionId;
    private final RedisList redisList;
    private final InMemoryChatSession memSession; //用它做缓冲

    public RedisChatSession(RedisClient redisClient, InMemoryChatSession memSession) {
        this.memSession = memSession;
        this.sessionId = memSession.getSessionId();
        this.redisList = redisClient.getList(sessionId);
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 获取最大消息数
     */
    public int getMaxMessages() {
        return memSession.getMaxMessages();
    }

    @Override
    public List<ChatMessage> getMessages() {
        return memSession.getMessages();
    }

    @Override
    public List<ChatMessage> getLatestMessages(int windowSize) {
        return memSession.getLatestMessages(windowSize);
    }

    @Override
    public void addMessage(Collection<? extends ChatMessage> messages) {
        for (ChatMessage m : messages) {
            memSession.addMessage(m);
            redisList.add(ChatMessage.toJson(m));
        }
    }

    @Override
    public boolean isEmpty() {
        return memSession.isEmpty();
    }

    @Override
    public void clear() {
        memSession.clear();
        redisList.clear();
    }
}
