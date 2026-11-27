package features.ai.session;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.tool.ToolCall;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 快照落盘读回：工具执行期间的 trace 状态（lastReasonMessage 的 toolCall uuid、extras 嵌套结构、
 * 结果消息元数据）重启后要原样可用，宿主据此把运行中/已中断的工具调用关联回子会话。
 */
public class FileAgentSessionTraceRoundTripTest {
    private static final String TRACE_KEY = "__trace_rt";

    @Test
    public void runningToolCallStateSurvivesReopen() throws Exception {
        Path dir = Files.createTempDirectory("solon_ai_trace_rt_");
        String sid = "rt-" + System.nanoTime();

        FileAgentSession session = new FileAgentSession(sid, dir.toString());
        ReActTrace trace = new ReActTrace();
        session.getContext().put(TRACE_KEY, trace);

        //已完成的一步：调用与结果成对在工作记忆里，结果带子任务索引元数据
        Map<String, Object> doneArgs = new HashMap<>();
        doneArgs.put("description", "done-one");
        ToolCall doneCall = new ToolCall("0", "call_done", "task", "{}", doneArgs);
        AssistantMessage doneReason = new AssistantMessage("", null, Collections.singletonList(doneCall), null);
        ToolMessage doneResult = ChatMessage.ofTool("ok", "task", "call_done");
        Map<String, Object> idx = new LinkedHashMap<>();
        idx.put("taskId", "task-aaa");
        idx.put("status", "success");
        doneResult.addMetadata("_subtasks", new ArrayList<>(Collections.singletonList(idx)));
        trace.getWorkingMemory().addMessage(doneReason);
        trace.getWorkingMemory().addMessage(doneResult);

        //执行中的一步：调用只在 lastReasonMessage 里
        Map<String, Object> runArgs = new HashMap<>();
        runArgs.put("description", "running-one");
        ToolCall runCall = new ToolCall("0", "call_run", "task_start", "{}", runArgs);
        trace.setLastReasonMessage(new AssistantMessage("thinking...", "deep", Collections.singletonList(runCall), null));

        Map<String, Object> item1 = new LinkedHashMap<>();
        item1.put("taskId", "task-bbb");
        item1.put("agentName", "explore");
        item1.put("description", "running-one");
        Map<String, Object> item2 = new LinkedHashMap<>(item1);
        item2.put("taskId", "task-ccc");
        item2.put("status", "failure");
        Map<String, Object> running = new LinkedHashMap<>();
        running.put(runCall.getUuid(), new ArrayList<>(Arrays.asList(item1, item2)));
        trace.setExtra("_runningSubtasks", running);

        session.updateSnapshot();

        //模拟重启
        FileAgentSession reopened = new FileAgentSession(sid, dir.toString());
        ReActTrace back = reopened.getContext().getAs(TRACE_KEY);
        Assertions.assertNotNull(back);

        AssistantMessage lastReason = back.getLastReasonMessage();
        Assertions.assertNotNull(lastReason);
        Assertions.assertEquals(1, lastReason.getToolCalls().size());
        Assertions.assertEquals(runCall.getUuid(), lastReason.getToolCalls().get(0).getUuid(), "toolCall uuid 读回后必须保持原值");
        Assertions.assertEquals("task_start", lastReason.getToolCalls().get(0).getName());

        Map<String, Object> runningBack = back.getExtraAs("_runningSubtasks");
        Assertions.assertNotNull(runningBack);
        Object listBack = runningBack.get(runCall.getUuid());
        Assertions.assertTrue(listBack instanceof List, "索引值应读回为 List: " + listBack);
        List<?> items = (List<?>) listBack;
        Assertions.assertEquals(2, items.size());
        Assertions.assertTrue(items.get(0) instanceof Map);
        Assertions.assertEquals("task-bbb", ((Map<?, ?>) items.get(0)).get("taskId"));
        Assertions.assertEquals("explore", ((Map<?, ?>) items.get(0)).get("agentName"));
        Assertions.assertEquals("task-ccc", ((Map<?, ?>) items.get(1)).get("taskId"));
        Assertions.assertEquals("failure", ((Map<?, ?>) items.get(1)).get("status"));

        List<ChatMessage> wm = back.getWorkingMemory().getMessages();
        Assertions.assertEquals(2, wm.size());
        Assertions.assertEquals(doneCall.getUuid(), ((AssistantMessage) wm.get(0)).getToolCalls().get(0).getUuid());
        Object subtasks = wm.get(1).getMetadata().get("_subtasks");
        Assertions.assertTrue(subtasks instanceof List, "结果消息的 _subtasks 应读回为 List: " + subtasks);
        Assertions.assertEquals("task-aaa", ((Map<?, ?>) ((List<?>) subtasks).get(0)).get("taskId"));
    }
}
