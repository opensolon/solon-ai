package features.ai.harness;

import org.junit.jupiter.api.Test;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.harness.agent.TaskSkill;

import java.util.List;

public class JsonSchemaTest {
    @Test
    public void case1() {
        TaskSkill taskSkill = new TaskSkill(null);

        FunctionTool functionTool = ((List<FunctionTool>) taskSkill.getToolAry("multitask")).get(0);
        System.out.println(functionTool.inputSchema());
        assert "{\"type\":\"object\",\"properties\":{\"tasks\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"agent_name\":{\"type\":\"string\",\"description\":\"子代理名称\"},\"description\":{\"type\":\"string\",\"description\":\"简短的任务描述（50字以内）。返回结果时会附上这个描述，方便识别\"},\"index\":{\"type\":\"integer\",\"description\":\"任务序号（从1开始）\",\"default\":1},\"prompt\":{\"type\":\"string\",\"description\":\"派给子代理的任务描述。每次都是重新开始，要非常详细的描述任务，并传递用户的原始意图。\"}},\"required\":[\"index\",\"agent_name\",\"prompt\",\"description\"]},\"description\":\"任务列表\"}},\"required\":[\"tasks\"]}".equals(functionTool.inputSchema());


        functionTool = ((List<FunctionTool>) taskSkill.getToolAry("task")).get(0);
        System.out.println(functionTool.inputSchema());
        assert "{\"type\":\"object\",\"properties\":{\"agent_name\":{\"type\":\"string\",\"description\":\"子代理名称\"},\"description\":{\"type\":\"string\",\"description\":\"简短的任务描述（50字以内）。返回结果时会附上这个描述，方便识别\"},\"index\":{\"type\":\"integer\",\"description\":\"任务序号（从1开始）\",\"default\":1},\"prompt\":{\"type\":\"string\",\"description\":\"派给子代理的任务描述。每次都是重新开始，要非常详细的描述任务，并传递用户的原始意图。\"}},\"required\":[\"index\",\"agent_name\",\"prompt\",\"description\"]}".equals(functionTool.inputSchema());
    }

    @Test
    public void case2() throws Throwable {
        TaskSkill taskSkill = new TaskSkill(null);

        FunctionTool functionTool = ((List<FunctionTool>) taskSkill.getToolAry("multitask")).get(0);


       assert "WARNING: 任务列表为空".equals(functionTool.call(Utils.asMap(
                "tasks", "[]",
                "__cwd", "1",
                "__sessionId", "1"
        )).getContent());
    }
}