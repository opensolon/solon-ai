package demo.ai.flow.react;

import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.FlowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

//@Configuration
public class DemoAgent {
    private static final Logger log = LoggerFactory.getLogger(DemoAgent.class);

    @Inject
    FlowEngine flowEngine;

    @Bean
    public ChatModel chatModel(){
        return ChatModel.of("http://127.0.0.1:11434/api/chat")
                .provider("ollama")
                .model("qwen2.5:1.5b")
                .build();
    }

    @Init
    public void test() {
        FlowContext context = FlowContext.of()
                .put("MAX_REVISIONS", 3)
                .put("draft_content", "")
                .put("review_status", "NONE")
                .put("feedback", "")
                .put("revision_count", new AtomicInteger(0))
                .put("messages", Utils.asList(ChatMessage.ofUser("智能家居的未来趋势和潜在挑战。")));


        log.info("--- 启动内容审核 Agent ---");

        //执行
        flowEngine.eval("demo1", context);

        //执行后打印
        System.out.println(context.get("draft_content").toString());

        List<ChatMessage> messageList = context.getAs("messages");
        for (ChatMessage message : messageList) {
            System.out.println(message);
        }
    }
}