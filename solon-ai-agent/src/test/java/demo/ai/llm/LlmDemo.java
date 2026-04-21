package demo.ai.llm;

import org.noear.solon.ai.agent.react.ReActAgent;

/**
 *
 * @author noear 2026/1/4 created
 *
 */
public class LlmDemo {
    public static void main() throws Throwable {
        ReActAgent agent = ReActAgent.of(null)
                .modelOptions(o -> {

                })
                .build();

        agent.prompt("xxx").options(o -> {
            //请求时动态切换 chatModel
            o.chatModel(null);
        }).call();
    }
}
