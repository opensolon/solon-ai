package demo.ai.react;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActResponse;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.core.util.RunUtil;

import java.util.Map;

/**
 *
 * @author noear 2026/4/8 created
 *
 */
public class PendingDemo {
    public void case1() throws Throwable {
        ReActAgent agent = ReActAgent.of(null)
                .defaultInterceptorAdd(new ReActInterceptor() {
                    @Override
                    public void onAction(ReActTrace trace, String toolName, Map<String, Object> args) {
                        trace.getSession().pending(true, "不让你查");
                    }
                })
                .build();

        //调步调用
        AgentSession session = InMemoryAgentSession.of();
        ReActResponse resp = agent.prompt("查一下杭州今天的天气").session(session).call();

        if(session.isPending()){
            System.out.println("已挂起");
        }
    }

    public void case2() throws Throwable {
        ReActAgent agent = ReActAgent.of(null)
                .build();

        //异步调用
        AgentSession session = InMemoryAgentSession.of();
        agent.prompt("查一下杭州今天的天气").session(session).callAsync()
                .whenComplete((resp, err) -> {

                });

        session.pending(true, "强行中断");
    }

    public void case3() {
        ReActAgent agent = ReActAgent.of(null)
                .build();

        //流式调用
        AgentSession session = InMemoryAgentSession.of();
        agent.prompt("查一下杭州今天的天气").session(session).stream()
                .doOnNext(chunk -> {

                })
                .subscribe();

        session.pending(true, "强行中断");
    }
}