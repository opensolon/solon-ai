package demo.ai.harness;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentSessionProvider;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessProperties;

import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 * @author noear 2026/4/3 created
 *
 */
public class DemoApp {
    public static void main(String[] arg) {
        HarnessProperties agentProps = new HarnessProperties(".solon/");

        ChatModel chatModel = ChatModel.of(agentProps.getChatModel()).build();
        Map<String, AgentSession> sessionMap = new ConcurrentHashMap<>();

        AgentSessionProvider sessionProvider = (sessionId) -> sessionMap.computeIfAbsent(sessionId, key ->
                new FileAgentSession(key, Paths.get(agentProps.getWorkspace(), agentProps.getHarnessSessions())
                        .resolve(key).normalize().toFile().toString()));

        HarnessEngine agentRuntime = HarnessEngine.builder()
                .chatModel(chatModel)
                .properties(agentProps)
                .sessionProvider(sessionProvider)
                .build();
    }
}
