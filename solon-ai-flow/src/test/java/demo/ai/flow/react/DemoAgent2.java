package demo.ai.flow.react;

import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.flow.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class DemoAgent2 {
    private static final Logger log = LoggerFactory.getLogger(DemoAgent2.class);

    public static void main(String[] args) throws Throwable {
        final int MAX_REVISIONS = 3;

        FlowEngine flowEngine = FlowEngine.newInstance();

        ChatModel chatModel = ChatModel.of("http://127.0.0.1:11434/api/chat")
                .provider("ollama")
                .model("qwen2.5:1.5b")
                .build();

        FlowContext context = FlowContext.of()
                .put("MAX_REVISIONS", MAX_REVISIONS)
                .put("draft_content", "")
                .put("review_status", "NONE")
                .put("feedback", "")
                .put("revision_count", new AtomicInteger(0))
                .put("messages", Utils.asList(ChatMessage.ofUser("智能家居的未来趋势和潜在挑战。")));


        log.info("--- 启动内容审核2 Agent ---");

        Graph graph = Graph.create("demo2", g -> {
            g.addStart("start").linkAdd("agent");

            g.addActivity("agent").task(new AiNodeAgent(chatModel)).linkAdd("review");
            g.addExclusive("review").task(new AiNodeReview())
                    .linkAdd("final_approval", lc -> lc.when(fc -> "APPROVED".equals(fc.get("review_status"))))
                    .linkAdd("final_failure", lc -> lc.when(fc -> "REJECTED".equals(fc.get("review_status")) && fc.<AtomicInteger>getAs("revision_count").get() >= MAX_REVISIONS))
                    .linkAdd("agent", lc -> lc.when(fc -> "REJECTED".equals(fc.get("review_status")) && fc.<AtomicInteger>getAs("revision_count").get() < MAX_REVISIONS))
                    .linkAdd("review");

            g.addActivity("final_approval").task(new AiNodeFinalApproval()).linkAdd("end");
            g.addActivity("final_failure").task(new AiNodeFinalFailure()).linkAdd("end");

            g.addEnd("end");
        });

        //执行
        flowEngine.eval(graph, context);

        //执行后打印
        System.out.println(context.get("draft_content").toString());

        List<ChatMessage> messageList = context.getAs("messages");
        for (ChatMessage message : messageList) {
            System.out.println(message);
        }
    }
}