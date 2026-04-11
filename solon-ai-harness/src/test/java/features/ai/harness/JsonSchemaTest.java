package features.ai.harness;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.harness.agent.TaskSkill;

import java.util.List;

public class JsonSchemaTest {
    @Test
    public void case1() {
        TaskSkill taskSkill = new TaskSkill(null);

        FunctionTool functionTool = ((List<FunctionTool>) taskSkill.getToolAry("multitask")).get(0);
        System.out.println(functionTool.inputSchema());
        assert "{\"type\":\"object\",\"properties\":{\"tasks\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"agent_name\":{\"type\":\"string\",\"description\":\"子代理名称\"},\"description\":{\"type\":\"string\",\"description\":\"简短的任务描述\"},\"prompt\":{\"type\":\"string\",\"description\":\"派给子代理的任务描述。子代理看不见当前历史，每次都是重新开始，必须要非常详细的描述任务，并传递用户的原始意图。\"},\"task_id\":{\"type\":\"string\",\"description\":\"任务ID（仅支持字母和数字）。示例：task1，task2\"}},\"required\":[\"task_id\",\"agent_name\",\"prompt\"]},\"description\":\"任务列表\"}},\"required\":[\"tasks\"]}".equals(functionTool.inputSchema());


        functionTool = ((List<FunctionTool>) taskSkill.getToolAry("task")).get(0);
        System.out.println(functionTool.inputSchema());
        assert "{\"type\":\"object\",\"properties\":{\"agent_name\":{\"type\":\"string\",\"description\":\"子代理名称\"},\"description\":{\"type\":\"string\",\"description\":\"简短的任务描述\"},\"prompt\":{\"type\":\"string\",\"description\":\"派给子代理的任务描述。子代理看不见当前历史，每次都是重新开始，必须要非常详细的描述任务，并传递用户的原始意图。\"},\"task_id\":{\"type\":\"string\",\"description\":\"任务ID（仅支持字母和数字）。示例：task1，task2\"}},\"required\":[\"task_id\",\"agent_name\",\"prompt\"]}".equals(functionTool.inputSchema());
    }
}