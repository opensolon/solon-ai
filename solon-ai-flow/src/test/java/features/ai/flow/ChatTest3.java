package features.ai.flow;

import org.noear.solon.Solon;
import org.noear.solon.flow.FlowEngine;

/**
 * @author noear 2025/5/16 created
 */
public class ChatTest3 {
    public static void main(String[] args) {
        Solon.start(ChatTest3.class, args);

        FlowEngine flowEngine = Solon.context().getBean(FlowEngine.class);

        flowEngine.eval("chat_case3");
    }
}