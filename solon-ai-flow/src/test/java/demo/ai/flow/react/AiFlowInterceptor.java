package demo.ai.flow.react;

import org.noear.solon.annotation.Component;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.FlowException;
import org.noear.solon.flow.Node;
import org.noear.solon.flow.intercept.FlowInterceptor;
import org.noear.solon.flow.intercept.FlowInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author noear 2025/11/29 created
 *
 */
@Component
public class AiFlowInterceptor implements FlowInterceptor {
    private static final Logger log = LoggerFactory.getLogger(AiFlowInterceptor.class);

    @Override
    public void doIntercept(FlowInvocation invocation) throws FlowException {
        invocation.invoke();
    }

    @Override
    public void onNodeStart(FlowContext context, Node node) {

    }

    @Override
    public void onNodeEnd(FlowContext context, Node node) {
        if (node.getId().equals("review")) {
            log.info("流程暂停：等待人工审核输入...");
        } else if (node.getId().equals("agent")) {
            log.info("流程暂停：等待人工审核输入...");
        } else if (node.getId().equals("end")) {
            log.info("[流程结束]");
        }
    }
}
