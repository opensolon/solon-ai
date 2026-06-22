package features.ai.react.intercept;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.StopLoopInterceptor;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.prompt.PromptImpl;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * StopLoopInterceptor 全面单元测试
 *
 * <p>覆盖：构造器边界、空消息安全、工具调用循环检测、文本 Action 循环检测、
 * 循环触发后行为验证、窗口滑动机制、指纹生成逻辑</p>
 */
public class StopLoopInterceptorTest {

    private ReActTrace trace;
    private Map<String, Object> extras;
    private Prompt workingMemory;
    private StopLoopInterceptor interceptor;

    @BeforeEach
    public void setUp() {
        trace = mock(ReActTrace.class);
        extras = new HashMap<>();
        workingMemory = new PromptImpl();
        AgentSession session = InMemoryAgentSession.of();

        // 模拟 Trace 的状态存储能力
        when(trace.getExtraAs(anyString())).thenAnswer(inv -> extras.get(inv.getArgument(0)));
        doAnswer(inv -> {
            extras.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(trace).setExtra(anyString(), any());

        when(trace.getAgentName()).thenReturn("TestAgent");
        when(trace.getSession()).thenReturn(session);
        when(trace.getWorkingMemory()).thenReturn(workingMemory);

        // 默认阈值 3 次，窗口 6
        interceptor = new StopLoopInterceptor(3, 6);
    }

    // ==================== 构造器测试 ====================

    @Test
    @DisplayName("默认构造器使用 (3, 8)")
    public void testDefaultConstructor() {
        StopLoopInterceptor interceptor = new StopLoopInterceptor();
        // 通过行为验证：3 次重复触发循环，2 次不触发
        AssistantMessage msg = createToolCallMsg("get_weather");

        interceptor.onThought(trace, "", msg);
        interceptor.onThought(trace, "", msg);
        // 2 次 — 未达到阈值
        assertTrue(workingMemory.isEmpty(), "2 次重复不应触发循环");

        interceptor.onThought(trace, "", msg);
        // 3 次 — 触发循环
        assertFalse(workingMemory.isEmpty(), "3 次重复应触发循环");
        assertTrue(workingMemory.getLastMessage().getContent().contains("Loop Detected"),
                "循环消息应包含 Loop Detected");
    }

    @Test
    @DisplayName("构造器边界保护：低于最小值时自动提升")
    public void testConstructorBoundaries() {
        // maxRepeatCount=1 → 提升到 2；windowSize=2 → 提升到 4
        StopLoopInterceptor interceptor = new StopLoopInterceptor(1, 2);
        Prompt wm = new PromptImpl();
        when(trace.getWorkingMemory()).thenReturn(wm);

        AssistantMessage msg = createToolCallMsg("search");
        interceptor.onThought(trace, "", msg);
        // 1 次 — 不应触发（因为被提升到 2）
        assertTrue(wm.isEmpty(), "1 次重复不应触发循环（被提升到 2）");

        interceptor.onThought(trace, "", msg);
        // 2 次 — 触发
        assertTrue(wm.getLastMessage().getContent().contains("Loop Detected"),
                "2 次重复应触发循环（被提升到 2）");
    }

    @Test
    @DisplayName("构造器接受正常自定义值")
    public void testConstructorCustomValues() {
        StopLoopInterceptor interceptor = new StopLoopInterceptor(4, 10);
        Prompt wm = new PromptImpl();
        when(trace.getWorkingMemory()).thenReturn(wm);

        AssistantMessage msg = createToolCallMsg("search");
        for (int i = 0; i < 3; i++) {
            interceptor.onThought(trace, "", msg);
        }
        // 3 次 — 未达阈值 4
        assertTrue(wm.isEmpty(), "3 次重复不应触发（阈值 4）");

        interceptor.onThought(trace, "", msg);
        // 4 次 — 触发
        assertFalse(wm.isEmpty(), "4 次重复应触发循环（阈值 4）");
    }

    // ==================== 空/边界输入测试 ====================

    @Test
    @DisplayName("null 消息不处理，不抛异常")
    public void testNullAssistantMessage() {
        assertDoesNotThrow(() -> interceptor.onThought(trace, "", null));
        // history 不应被初始化
        assertNull(extras.get("stoploop_history"), "null 消息不应创建 history");
        assertTrue(workingMemory.isEmpty(), "null 消息不应注入工作记忆");
    }

    @Test
    @DisplayName("既无 toolCalls 也无 content 的消息不生成指纹，不处理")
    public void testEmptyAssistantMessage() {
        // 构造既无 toolCalls 也无 content 的消息
        String json = "{\"role\": \"assistant\"}";
        AssistantMessage msg = (AssistantMessage) ChatMessage.fromJson(json);

        assertNull(msg.getToolCalls());
        assertNull(msg.getContent());

        interceptor.onThought(trace, "", msg);

        assertNull(extras.get("stoploop_history"), "空消息不应创建 history");
        assertTrue(workingMemory.isEmpty(), "空消息不应注入工作记忆");
    }

    @Test
    @DisplayName("toolCalls 为空列表时不生成指纹")
    public void testEmptyToolCallsList() {
        // 构造 toolCalls 为空数组的消息
        String json = "{\"role\": \"assistant\", \"toolCalls\": []}";
        AssistantMessage msg = (AssistantMessage) ChatMessage.fromJson(json);

        assertNotNull(msg.getToolCalls());
        assertTrue(msg.getToolCalls().isEmpty());

        interceptor.onThought(trace, "", msg);

        assertNull(extras.get("stoploop_history"), "空 toolCalls 不应创建 history");
    }

    // ==================== 工具调用模式 — 循环检测 ====================

    @Test
    @DisplayName("相同工具调用 3 次触发循环检测")
    public void testToolCallLoopTriggered() {
        AssistantMessage msg = createToolCallMsg("get_weather");

        interceptor.onThought(trace, "", msg); // 1
        interceptor.onThought(trace, "", msg); // 2
        // 第 3 次触发
        interceptor.onThought(trace, "", msg); // 3

        assertFalse(workingMemory.isEmpty(), "循环应注入工作记忆消息");
        assertTrue(workingMemory.getLastMessage().getContent().contains("Loop Detected"),
                "工作记忆消息应包含 Loop Detected");
    }

    @Test
    @DisplayName("相同工具调用 2 次不触发循环（低于阈值 3）")
    public void testToolCallLoopNotTriggered() {
        AssistantMessage msg = createToolCallMsg("get_weather");

        interceptor.onThought(trace, "", msg); // 1
        interceptor.onThought(trace, "", msg); // 2

        assertTrue(workingMemory.isEmpty(), "2 次重复不应触发循环");
    }

    @Test
    @DisplayName("相同工具名不同参数仍触发循环（指纹忽略参数）")
    public void testToolCallSameNameDifferentArgsTriggered() {
        // 工具名相同但参数不同 — 指纹相同，应触发循环
        AssistantMessage msg1 = createToolCallMsg("search", "{\"q\":\"solon\"}");
        AssistantMessage msg2 = createToolCallMsg("search", "{\"q\":\"noear\"}");

        interceptor.onThought(trace, "", msg1);
        interceptor.onThought(trace, "", msg2);
        interceptor.onThought(trace, "", msg1); // 第 3 次 "tool:search"

        assertFalse(workingMemory.isEmpty(), "同工具名不同参数仍应触发循环");
    }

    @Test
    @DisplayName("不同工具名交叉调用不触发循环")
    public void testToolCallDifferentNamesNotTriggered() {
        AssistantMessage msg1 = createToolCallMsg("search");
        AssistantMessage msg2 = createToolCallMsg("calculate");

        interceptor.onThought(trace, "", msg1);
        interceptor.onThought(trace, "", msg2);
        interceptor.onThought(trace, "", msg1);

        assertTrue(workingMemory.isEmpty(), "不同工具名不应触发循环");
    }

    @Test
    @DisplayName("单条消息包含多个工具调用时，指纹拼接所有工具名")
    public void testToolCallMultipleInOneMessage() {
        // 包含两个工具调用的消息
        String json = "{" +
                "  \"role\": \"assistant\"," +
                "  \"toolCalls\": [" +
                "    {\"id\": \"call_1\", \"name\": \"search\", \"arguments\": {}}," +
                "    {\"id\": \"call_2\", \"name\": \"read\", \"arguments\": {}}" +
                "  ]" +
                "}";
        AssistantMessage msg = (AssistantMessage) ChatMessage.fromJson(json);

        assertNotNull(msg.getToolCalls());
        assertEquals(2, msg.getToolCalls().size());

        // 三次相同的复合指纹
        interceptor.onThought(trace, "", msg); // 1 -> fingerprint: "tool:searchtool:read"
        interceptor.onThought(trace, "", msg); // 2
        interceptor.onThought(trace, "", msg); // 3 -> 触发

        assertFalse(workingMemory.isEmpty(), "复合指纹相同应触发循环");
    }

    // ==================== 文本模式 — 循环检测 ====================

    @Test
    @DisplayName("文本 Action 模式：相同 Action 重复 3 次触发循环")
    public void testTextActionLoopTriggered() {
        AssistantMessage msg = createActionMsg("check_status");

        interceptor.onThought(trace, "", msg); // 1
        interceptor.onThought(trace, "", msg); // 2
        interceptor.onThought(trace, "", msg); // 3

        assertFalse(workingMemory.isEmpty(), "相同 Action 重复应触发循环");
    }

    @Test
    @DisplayName("文本 Action 模式：不同 Action 不触发循环")
    public void testTextActionDifferentNotTriggered() {
        AssistantMessage msg1 = createActionMsg("check_status");
        AssistantMessage msg2 = createActionMsg("read_file");

        interceptor.onThought(trace, "", msg1);
        interceptor.onThought(trace, "", msg2);
        interceptor.onThought(trace, "", msg1);

        assertTrue(workingMemory.isEmpty(), "不同 Action 不应触发循环");
    }

    @Test
    @DisplayName("文本 Action 模式：Action 行中有参数 { 时只取到 { 之前")
    public void testTextActionWithArguments() {
        // Action: check_status{"id":1} -> 指纹取 "Action: check_status"
        String json = "{" +
                "  \"role\": \"assistant\"," +
                "  \"content\": \"Thought: let me check.\\nAction: check_status{\\\"id\\\":1}\"" +
                "}";
        AssistantMessage msg = (AssistantMessage) ChatMessage.fromJson(json);

        interceptor.onThought(trace, "", msg);
        interceptor.onThought(trace, "", msg);
        interceptor.onThought(trace, "", msg);

        assertFalse(workingMemory.isEmpty(), "带参数的 Action 应忽略参数进行循环检测");
    }

    @Test
    @DisplayName("纯文本内容（无 Action）取前 50 字符作为指纹")
    public void testTextContentNoAction() {
        // 构造包含较长内容但无 Action: 的消息
        String longText = "I am analyzing the data and thinking about what to do next. " +
                "Let me consider the options carefully.";
        String json = "{" +
                "  \"role\": \"assistant\"," +
                "  \"content\": \"" + escapeJson(longText) + "\"" +
                "}";
        AssistantMessage msg = (AssistantMessage) ChatMessage.fromJson(json);

        interceptor.onThought(trace, "", msg);
        interceptor.onThought(trace, "", msg);
        interceptor.onThought(trace, "", msg);

        assertFalse(workingMemory.isEmpty(), "相同纯文本内容应触发循环");
    }

    @Test
    @DisplayName("短文本内容（小于 50 字符）全部作为指纹")
    public void testTextContentShortMessage() {
        String shortText = "Just a short thought.";
        String json = "{" +
                "  \"role\": \"assistant\"," +
                "  \"content\": \"" + escapeJson(shortText) + "\"" +
                "}";
        AssistantMessage msg = (AssistantMessage) ChatMessage.fromJson(json);

        interceptor.onThought(trace, "", msg);
        interceptor.onThought(trace, "", msg);
        interceptor.onThought(trace, "", msg);

        assertFalse(workingMemory.isEmpty(), "短文本内容重复应触发循环");
    }

    // ==================== 循环触发后行为验证 ====================

    @Test
    @DisplayName("循环触发后注入用户消息到工作记忆")
    public void testLoopInjectsWorkingMemoryMessage() {
        AssistantMessage msg = createToolCallMsg("get_weather");

        interceptor.onThought(trace, "", msg);
        interceptor.onThought(trace, "", msg);
        interceptor.onThought(trace, "", msg);

        assertEquals(1, workingMemory.getMessages().size(),
                "工作记忆应被注入 1 条消息");
        String content = workingMemory.getLastMessage().getContent();
        assertAll("工作记忆消息内容验证",
                () -> assertTrue(content.contains("Loop Detected"),
                        "应包含 Loop Detected 标记"),
                () -> assertTrue(content.contains("系统提示"),
                        "应包含系统提示前缀"),
                () -> assertTrue(content.contains("暂停当前操作思路"),
                        "应引导 LLM 暂停当前思路")
        );
    }

    @Test
    @DisplayName("循环触发后 history 被清空（避免注入的消息再次触发循环）")
    public void testLoopClearsHistory() {
        AssistantMessage msg = createToolCallMsg("get_weather");

        interceptor.onThought(trace, "", msg);
        interceptor.onThought(trace, "", msg);
        interceptor.onThought(trace, "", msg); // 触发，清空 history

        @SuppressWarnings("unchecked")
        LinkedList<String> history = (LinkedList<String>) extras.get("stoploop_history");
        assertNotNull(history, "history 不应为 null");
        assertTrue(history.isEmpty(), "循环触发后 history 应被清空");
    }

    @Test
    @DisplayName("循环触发后不调用 setFinalAnswer（让 LLM 自纠）")
    public void testLoopDoesNotSetFinalAnswer() {
        AssistantMessage msg = createToolCallMsg("get_weather");

        interceptor.onThought(trace, "", msg);
        interceptor.onThought(trace, "", msg);
        interceptor.onThought(trace, "", msg);

        verify(trace, never()).setFinalAnswer(anyString());
    }

    @Test
    @DisplayName("循环触发后不修改 route（让当前 tool call 正常执行）")
    public void testLoopDoesNotChangeRoute() {
        AssistantMessage msg = createToolCallMsg("get_weather");

        interceptor.onThought(trace, "", msg);
        interceptor.onThought(trace, "", msg);
        interceptor.onThought(trace, "", msg);

        verify(trace, never()).setRoute(anyString());
    }

    // ==================== 窗口滑动机制 ====================

    @Test
    @DisplayName("窗口滑动：超过 windowSize 后最早的历史记录被移除")
    public void testWindowSlidingEviction() {
        // 使用小窗口拦截器：windowSize=4
        StopLoopInterceptor smallWindow = new StopLoopInterceptor(3, 4);
        Prompt wm = new PromptImpl();
        when(trace.getWorkingMemory()).thenReturn(wm);

        // 插入 4 种不同工具名的消息，填满窗口
        String[] tools = {"a", "b", "c", "d"};
        for (String tool : tools) {
            smallWindow.onThought(trace, "", createToolCallMsg(tool));
        }

        // 此时 history = [tool:a, tool:b, tool:c, tool:d]（size=4）
        @SuppressWarnings("unchecked")
        LinkedList<String> history = (LinkedList<String>) extras.get("stoploop_history");
        assertEquals(4, history.size());

        // 再插入 "a" — 此时 history 变为 [tool:b, tool:c, tool:d, tool:a]
        // 窗口中 "a" 只出现 1 次，不应触发循环
        smallWindow.onThought(trace, "", createToolCallMsg("a"));
        assertEquals(4, history.size(),
                "插入第 5 条后窗口仍保持 windowSize 大小");
        assertEquals("tool:b", history.getFirst(),
                "最旧的 tool:a（第一次出现）应被移除");

        // 此时窗口为 [tool:b, tool:c, tool:d, tool:a]，"tool:a" 出现 1 次 → 不应触发
        assertTrue(wm.isEmpty(), "滑动后 a 只出现 1 次不应触发");
    }

    @Test
    @DisplayName("窗口滑动后旧记录不影响新计数，避免误判")
    public void testWindowSlidingPreventsFalsePositive() {
        // windowSize=4, maxRepeatCount=3
        StopLoopInterceptor smallWindow = new StopLoopInterceptor(3, 4);
        Prompt wm = new PromptImpl();
        when(trace.getWorkingMemory()).thenReturn(wm);

        // 指纹存储为 "tool:a", "tool:b" ...
        // 插入顺序：a, b, c, a, a
        // 前 4 次：[tool:a, tool:b, tool:c, tool:a]
        smallWindow.onThought(trace, "", createToolCallMsg("a"));
        smallWindow.onThought(trace, "", createToolCallMsg("b"));
        smallWindow.onThought(trace, "", createToolCallMsg("c"));

        assertTrue(wm.isEmpty(), "前 3 次不同工具不应触发");

        smallWindow.onThought(trace, "", createToolCallMsg("a"));
        // 此时 tool:a 出现 2 次，未达阈值 3
        assertTrue(wm.isEmpty(), "tool:a 出现 2 次不应触发");

        // 再插入 "a" → [tool:b, tool:c, tool:a, tool:a]（最旧的 tool:a 被移除）
        smallWindow.onThought(trace, "", createToolCallMsg("a"));
        // 窗口中 tool:a 出现 2 次（第 2 个和第 4 个），未达 3
        assertTrue(wm.isEmpty(),
                "窗口滑动后 tool:a 出现 2 次不应触发（最旧的已被移除）");
    }

    // ==================== 混合场景 ====================

    @Test
    @DisplayName("连续触发后工作记忆累积消息")
    public void testMultipleLoopInjections() {
        // 循环触发后 history 被清空，所以再次相同调用可再次触发
        AssistantMessage msg = createToolCallMsg("get_weather");

        // 第一次触发循环
        interceptor.onThought(trace, "", msg);
        interceptor.onThought(trace, "", msg);
        interceptor.onThought(trace, "", msg); // 触发，清空 history
        assertEquals(1, workingMemory.getMessages().size(),
                "第一次循环应注入 1 条消息");

        // history 已被清空，所以再来 3 次会再次触发
        interceptor.onThought(trace, "", msg);
        interceptor.onThought(trace, "", msg);
        interceptor.onThought(trace, "", msg); // 再次触发

        assertEquals(2, workingMemory.getMessages().size(),
                "第二次循环应再注入 1 条消息，共 2 条");
    }

    @Test
    @DisplayName("多种消息类型混合——工具调用和文本消息互不影响计数")
    public void testMixedMessageTypes() {
        AssistantMessage toolMsg = createToolCallMsg("get_weather");
        AssistantMessage textMsg = createActionMsg("check_status");

        // 工具调用
        interceptor.onThought(trace, "", toolMsg); // fingerprint: tool:get_weather
        interceptor.onThought(trace, "", toolMsg); // fingerprint: tool:get_weather

        // 文本消息（不同指纹）
        interceptor.onThought(trace, "", textMsg); // fingerprint: Action: check_status

        // 再一个工具调用 — 现在 3 次 tool:get_weather 了
        interceptor.onThought(trace, "", toolMsg); // 第 3 次 tool:get_weather → 触发

        assertFalse(workingMemory.isEmpty(),
                "工具调用 3 次应触发循环（不受文本消息影响）");
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建仅包含指定工具名的工具调用消息
     */
    private AssistantMessage createToolCallMsg(String toolName) {
        return createToolCallMsg(toolName, "{}");
    }

    /**
     * 创建包含指定工具名和参数的工具调用消息
     */
    private AssistantMessage createToolCallMsg(String toolName, String argsJson) {
        String json = "{" +
                "  \"role\": \"assistant\"," +
                "  \"toolCalls\": [{" +
                "    \"id\": \"call_" + Math.abs(System.nanoTime()) + "\"," +
                "    \"name\": \"" + toolName + "\"," +
                "    \"arguments\": " + argsJson +
                "  }]" +
                "}";
        return (AssistantMessage) ChatMessage.fromJson(json);
    }

    /**
     * 创建包含指定 Action 的文本消息（Action 不带参数）
     */
    private AssistantMessage createActionMsg(String actionName) {
        String json = "{" +
                "  \"role\": \"assistant\"," +
                "  \"content\": \"Thought: I need to do something.\\nAction: " + actionName + "\"" +
                "}";
        return (AssistantMessage) ChatMessage.fromJson(json);
    }

    /**
     * JSON 字符串转义（用于 content 字段）
     */
    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
