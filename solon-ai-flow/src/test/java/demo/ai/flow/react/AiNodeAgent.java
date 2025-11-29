package demo.ai.flow.react;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.annotation.Component;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.TaskComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

//Agent 根据状态编写内容或修改内容。
@Component("agent")
public class AiNodeAgent implements TaskComponent {
    private static final Logger log = LoggerFactory.getLogger(AiNodeAgent.class);

    private final ChatModel chatModel;
    public AiNodeAgent(ChatModel chatModel) {
        this.chatModel = chatModel;

    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        AtomicInteger revision_count = context.getAs("revision_count");
        String draft_content = context.getAs("draft_content");
        String feedback = context.getAs("feedback");
        List<ChatMessage> messages = context.getAs("messages");

        Prompt prompt = new Prompt();
        //构建 LLM 提示词
        if (revision_count.get() == 0) {
            //第一次：编写内容
            String topic = messages.get(0).getContent();
            prompt.addMessage(ChatMessage.ofSystem("你是一个专业的内容创作者，请根据主题草拟一篇简短的文章。"));
            prompt.addMessage(ChatMessage.ofUser("请草拟关于主题 '" + topic + "' 的文章。"));
        } else {
            //循环：根据反馈修改内容
            prompt.addMessage(ChatMessage.ofSystem("你是一个专业的内容创作者。你收到了人工审核员的反馈，请根据反馈修改你的草稿。"));
            prompt.addMessage(ChatMessage.ofUser("这是你的旧草稿：\\n---\\n" + draft_content + "\\n---\\n这是人工审核员的反馈：\\n---\\n" + feedback + "\\n---\\n请提供修改后的新草稿。"));
        }

        ChatMessage new_draft = chatModel.prompt(prompt).call().getMessage();

        revision_count.incrementAndGet();
        log.info("--- LLM 完成第 {} 次草稿/修改 ---", revision_count.get());

        context.put("draft_content", new_draft.getContent());
        context.put("review_status", "PENDING");
        messages.add(ChatMessage.ofAssistant("提交第 " + revision_count.get() + " 次草稿进行审核。"));
    }
}