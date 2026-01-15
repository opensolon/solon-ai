package features.ai.flow.manual;

import org.noear.solon.Solon;
import org.noear.solon.flow.FlowEngine;

/**
 * @author noear 2025/5/16 created
 */
public class ChatTest3 {
    public static class Yaml {
        public static void main(String[] args) {
            Solon.start(ChatTest3.class, args);

            FlowEngine flowEngine = Solon.context().getBean(FlowEngine.class);

            flowEngine.eval("chat_case3");
        }
    }

    public static class Json {
        public static void main(String[] args) {
            Solon.start(ChatTest3.class, args);

            FlowEngine flowEngine = Solon.context().getBean(FlowEngine.class);

            flowEngine.eval("chat_case3_json");
        }
    }
}