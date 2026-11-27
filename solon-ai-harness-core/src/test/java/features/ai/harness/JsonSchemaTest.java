package features.ai.harness;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.harness.agent.TaskTalent;

import java.util.List;
import java.util.Map;

public class JsonSchemaTest {
    @Test
    public void case1() {
        TaskTalent taskTalent = new TaskTalent(null);

        FunctionTool functionTool;

        functionTool = ((List<FunctionTool>) taskTalent.getToolAry("task")).get(0);
        System.out.println(functionTool.inputSchema());
        assert "{\"type\":\"object\",\"properties\":{\"agent_name\":{\"type\":\"string\",\"description\":\"子代理名称\"},\"description\":{\"type\":\"string\",\"description\":\"任务内容的极简摘要（如：'重构用户认证逻辑'）。该描述将作为标签出现在执行日志和结果摘要中，用于快速识别任务意图。\"},\"prompt\":{\"type\":\"string\",\"description\":\"发给子代理的详细指令。子代理看不到主会话的历史，必须在此提供任务所需的所有背景信息、具体要求及预期输出格式。\"},\"task_id\":{\"type\":\"string\",\"description\":\"可选。接着之前的任务做（取自结果里的 <task_id>，成败均可）：子代理保留之前的全部上下文。失败时从断点重试，prompt 填“继续”或修正说明；完成后可追问或补充要求，prompt 填后续指令。agent_name 以原任务为准。新任务不要填。\"}},\"required\":[\"agent_name\",\"prompt\",\"description\"]}".equals(functionTool.inputSchema());

        // task_start 与 task 参数一致
        Assertions.assertEquals(functionTool.inputSchema(),
                ((List<FunctionTool>) taskTalent.getToolAry(TaskTalent.TOOL_TASK_START)).get(0).inputSchema());
    }

    @Test
    public void case2() throws Throwable {
        TaskTalent taskTalent = new TaskTalent(null);

        FunctionTool functionTool = ((List<FunctionTool>) taskTalent.getToolAry(TaskTalent.TOOL_TASK_START)).get(0);

        ONode oNode = ONode.ofJson(json, Feature.Read_AllowUnescapedControlCharacters);

        Map args = oNode.get("function").get("arguments").toBean(Map.class);

        Assertions.assertThrowsExactly(IllegalStateException.class, ()->{
            functionTool.call(args);
        },"__sessionId is required");
    }

    String json = "{\"id\":\"tool-1\",\"type\":\"function\",\"function\":{\"name\":\"task_start\",\"arguments\":{\"agent_name\":\"general\",\"description\":\"开发后端\",\"prompt\":\"第一行\n第二行\"}}}";
}