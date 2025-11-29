package demo.ai.flow.react;

import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.annotation.Component;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.TaskComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

//模拟人工审核流程，流程在此暂停，等待人工输入。
@Component("review")
public class AiNodeReview implements TaskComponent {
    private static final Logger log = LoggerFactory.getLogger(AiNodeReview.class);

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        AtomicInteger revision_count = context.getAs("revision_count");
        String draft_content = context.getAs("draft_content");
        List<ChatMessage> messages = context.getAs("messages");

        String feedback;
        String status = null;

        log.info("**人机回路节点激活** - 当前草稿 ({} 次修改):{}",
                revision_count.get(),
                (draft_content.length() > 200 ? draft_content.substring(0, 200) + "..." : draft_content));

        //为了演示，我们用 "控制台" 模拟人工输入：

        while (true) {
            System.out.println("请输入审核结果 (approve or reject):");
            String action = getInput();

            if ("approve".equals(action)) {
                feedback = "Approved.";
                status = "APPROVED";
                break;
            } else if ("reject".equals(action)) {
                System.out.println("请输入拒绝反馈意见: ");
                feedback = getInput();
                status = "REJECTED";
                break;
            } else {
                System.out.println("输入无效，请重新输入。");
            }
        }

        context.put("review_status", status);
        context.put("feedback", feedback);

        messages.add(ChatMessage.ofUserTmpl("审核结果: #{status}. 反馈: #{feedback}")
                .paramAdd("status", status)
                .paramAdd("feedback", feedback)
                .generate());
    }

    private String getInput() throws Throwable {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        return reader.readLine();
    }
}