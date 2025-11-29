package demo.ai.flow.react;

import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.annotation.Component;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.TaskComponent;

import java.util.List;

/**
 *
 * @author noear 2025/11/29 created
 *
 */
@Component("final_approval")
public class AiNodeFinalApproval implements TaskComponent {
    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        List<ChatMessage> messages = context.getAs("messages");
        messages.add(ChatMessage.ofAssistant("最终内容已发布！"));
    }
}
