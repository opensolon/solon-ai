package lab.ai.a2a;

import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.core.util.Assert;

import java.util.Map;

/**
 * @author noear 2025/8/31 created
 */
public class Agent implements FunctionTool {
    private FunctionToolDesc desc;
    private AgentTaskHandler taskHandler;

    public Agent(String agentName, String description, AgentTaskHandler taskHandler) {
        Assert.notEmpty(agentName, "The agentName is empty");
        Assert.notEmpty(description, "The description is empty");
        Assert.notNull(taskHandler, "The taskHandler is null");

        this.desc = new FunctionToolDesc(agentName)
                .description(description)
                .stringParamAdd("message", "任务消息");
        this.taskHandler = taskHandler;
    }

    public Agent(String agentName, String description, ChatModel chatModel) {
        Assert.notEmpty(agentName, "The agentName is empty");
        Assert.notEmpty(description, "The description is empty");
        Assert.notNull(chatModel, "The chatModel is null");

        this.desc = new FunctionToolDesc(agentName)
                .description(description)
                .stringParamAdd("message", "任务消息");
        this.taskHandler = (message -> chatModel.prompt(message).call().getMessage().getResultContent());
    }

    public Agent(FunctionTool functionTool) {
        Assert.notNull(functionTool, "The functionTool is null");

        this.desc = new FunctionToolDesc(functionTool.name())
                .description(functionTool.description())
                .stringParamAdd("message", "任务消息");

        this.taskHandler = (message -> functionTool.handle(Utils.asMap("message", message)));
    }

    @Override
    public String name() {
        return desc.name();
    }

    @Override
    public String title() {
        return desc.title();
    }

    @Override
    public String description() {
        return desc.description();
    }

    @Override
    public boolean returnDirect() {
        return desc.returnDirect();
    }

    @Override
    public String inputSchema() {
        return desc.inputSchema();
    }

    @Override
    public String handle(Map<String, Object> args) throws Throwable {
        String message = (String) args.get("message");

        return taskHandler.handleTask(message);
    }
}
