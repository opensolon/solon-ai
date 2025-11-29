package demo.ai.flow.react;

import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.annotation.Component;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.TaskComponent;

import java.util.List;


@Component("final_failure")
public class AiNodeFinalFailure implements TaskComponent {
    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        List<ChatMessage> messages = context.getAs("messages");
        messages.add(ChatMessage.ofAssistant("流程失败：内容修改次数过多，已退出。"));
    }
}
