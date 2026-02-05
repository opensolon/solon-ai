package features.ai.react.intercept;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.StopLoopInterceptor;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

public class StopLoopInterceptorTest {

    private ReActTrace trace;
    private Map<String, Object> extras;
    private StopLoopInterceptor interceptor;

    @BeforeEach
    public void setUp() {
        trace = mock(ReActTrace.class);
        extras = new HashMap<>();

        // 模拟 Trace 的状态存储能力
        when(trace.getExtraAs(anyString())).thenAnswer(inv -> extras.get(inv.getArgument(0)));
        doAnswer(inv -> {
            extras.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(trace).setExtra(anyString(), any());

        when(trace.getAgentName()).thenReturn("TestAgent");

        // 阈值 3 次，窗口 6
        interceptor = new StopLoopInterceptor(3, 6);
    }

    @Test
    public void testLoopDetection_ByJson() {
        // 使用 JSON 方式构造完全一致的工具调用消息
        // 注意：role 必须是 assistant，且包含 tool_calls 结构
        String json = "{" +
                "  \"role\": \"assistant\"," +
                "  \"toolCalls\": [{" +
                "    \"id\": \"call_001\"," +
                "    \"name\": \"get_weather\"," +
                "    \"arguments\": {\"city\": \"Shanghai\"}" +
                "  }]" +
                "}";

        AssistantMessage msg = (AssistantMessage) ChatMessage.fromJson(json);

        // 调试确认点：如果这里是空的，指纹生成就会返回 null，拦截器就失效了
        assertNotNull(msg.getToolCalls(), "ToolCalls should not be null after fromJson");
        assertFalse(msg.getToolCalls().isEmpty(), "ToolCalls should not be empty");

        interceptor.onReason(trace, msg); // 1
        interceptor.onReason(trace, msg); // 2

        // 验证 extras 存储是否生效
        assertNotNull(extras.get("stoploop_history"), "History should be initialized in trace extras");

        interceptor.onReason(trace, msg); // 3 -> 此处应该触发 interrupt

        verify(trace, times(1)).pending(anyString());
    }

    @Test
    public void testDifferentArgumentsNoLoop() {
        // 参数不同，指纹就不同，不应触发死循环
        AssistantMessage msg1 = createAssistantMessage("search", "{\"q\":\"solon\"}");
        AssistantMessage msg2 = createAssistantMessage("search", "{\"q\":\"noear\"}");

        interceptor.onReason(trace, msg1);
        interceptor.onReason(trace, msg2);
        interceptor.onReason(trace, msg1);

        verify(trace, never()).pending(anyString());
    }

    /**
     * 辅助方法：通过 JSON 模板动态生成消息
     */
    private AssistantMessage createAssistantMessage(String name, String argsJson) {
        String jsonTemplate = "{" +
                "  \"role\": \"assistant\"," +
                "  \"tool_calls\": [{" +
                "    \"id\": \"call_%d\"," +
                "    \"name\": \"%s\"," +
                "    \"arguments\": %s" +
                "  }]" +
                "}";

        String json = String.format(jsonTemplate, System.nanoTime(), name, argsJson);
        return (AssistantMessage) ChatMessage.fromJson(json);
    }

    @Test
    public void testTextModeLoopByJson() {
        // 测试文本模式下的 Action 协议重复
        String json = "{" +
                "  \"role\": \"assistant\"," +
                "  \"content\": \"Thought: I need to check.\\nAction: check_status{\\\"id\\\":1}\"" +
                "}";

        AssistantMessage msg = (AssistantMessage) ChatMessage.fromJson(json);

        interceptor.onReason(trace, msg);
        interceptor.onReason(trace, msg);
        interceptor.onReason(trace, msg);

        verify(trace, times(1)).pending(anyString());
    }
}