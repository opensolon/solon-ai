package demo.ai.agent;

import org.noear.redisx.RedisClient;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.session.RedisAgentSession;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Inject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 * @author noear 2026/1/10 created
 *
 */
public class AgentSessoinConfig {
    // 使用 Redis 会话存储
    @Bean
    public AgentSessionProvider redisSession(RedisClient redisClient) {
        Map<String, AgentSession> map = new ConcurrentHashMap<>();
        return (sessionId) -> map.computeIfAbsent(sessionId, k -> new RedisAgentSession(k, redisClient));
    }

    // 使用内存会话存储（默认）
    @Bean
    public AgentSessionProvider inMemorySessoin() {
        Map<String, AgentSession> map = new ConcurrentHashMap<>();
        return (sessionId) -> map.computeIfAbsent(sessionId, k -> new InMemoryAgentSession(k));
    }

    public static class Demo {
        @Inject
        AgentSessionProvider sessionProvider;
        @Inject
        ReActAgent reActAgent;

        public String test() throws Throwable{
            AgentSession session = sessionProvider.getSession("test");

            return reActAgent.prompt("你好呀!")
                    .session(session)
                    .call()
                    .getContent();
        }
    }
}
