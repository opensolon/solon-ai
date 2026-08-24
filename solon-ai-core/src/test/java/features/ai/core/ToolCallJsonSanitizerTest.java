package features.ai.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatResponseDefault;
import org.noear.solon.ai.chat.dialect.AbstractChatDialect;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.InMemoryChatSession;
import org.noear.solon.ai.chat.tool.ToolCallBuilder;
import org.noear.solon.ai.chat.tool.ToolCallJsonSanitizer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ToolCallJsonSanitizer 回归测试：截断的 tool_call arguments 不得毒化会话
 *
 * <p>真实案例（2026-08）：glm-5.2 流式输出被截断，bash 工具的 arguments
 * 停在 '{"command":"cd ... ChatOptions'（无闭合引号与 '}'}），框架原样入历史并回传，
 * 服务端 json.loads 失败返回 400，会话永久中毒。</p>
 */
public class ToolCallJsonSanitizerTest {
    /**
     * 可实例化的测试方言
     */
    static class TestDialect extends AbstractChatDialect {
        @Override
        public boolean matched(ChatConfig config) {
            return true;
        }

        @Override
        public boolean parseResponseJson(ChatConfig config, ChatResponseDefault resp, String json) {
            return false;
        }
    }

    /**
     * 可构造的响应对象（供流式聚合测试）
     */
    static class TestResponse extends ChatResponseDefault {
        public TestResponse() {
            super(new ChatRequest(
                    new ChatConfig(),
                    new TestDialect(),
                    ChatOptions.of(),
                    InMemoryChatSession.builder().build(),
                    null,
                    null,
                    true), true);
        }
    }

    /**
     * 真实事故案例：Unterminated string（无闭合引号、无 '}'）
     */
    @Test
    public void truncatedArgumentsShouldBeResetToEmptyObject() {
        String truncated = "{\"command\":\"cd @solon-ai-source && grep -m2 \\\"<version>\\\" pom.xml && grep -rn " +
                "\\\"LinkedHashMap\\\\|options()\\\" solon-ai-core/src/main/java/org/noear/solon/ai/chat/ChatOptions";

        Assertions.assertEquals("{}", ToolCallJsonSanitizer.sanitizeArguments(truncated, "bash"));
    }

    @Test
    public void nullOrEmptyArgumentsShouldBeResetToEmptyObject() {
        Assertions.assertEquals("{}", ToolCallJsonSanitizer.sanitizeArguments(null, "bash"));
        Assertions.assertEquals("{}", ToolCallJsonSanitizer.sanitizeArguments("", "bash"));
        Assertions.assertEquals("{}", ToolCallJsonSanitizer.sanitizeArguments("   ", "bash"));
    }

    @Test
    public void scalarOrArrayArgumentsShouldBeResetToEmptyObject() {
        Assertions.assertEquals("{}", ToolCallJsonSanitizer.sanitizeArguments("abc", "bash"));
        Assertions.assertEquals("{}", ToolCallJsonSanitizer.sanitizeArguments("123", "bash"));
        Assertions.assertEquals("{}", ToolCallJsonSanitizer.sanitizeArguments("[1,2,3]", "bash"));
    }

    @Test
    public void validArgumentsShouldSurvive() {
        String result = ToolCallJsonSanitizer.sanitizeArguments("{\"command\":\"ls -la\"}", "bash");

        ONode parsed = ONode.ofJson(result);
        Assertions.assertTrue(parsed.isObject());
        Assertions.assertEquals("ls -la", parsed.get("command").getString());
    }

    /**
     * 双重编码（JSON 字符串套 JSON 字符串）不是 object，兑底为 "{}"
     */
    @Test
    public void doubleEncodedArgumentsShouldBeResetToEmptyObject() {
        String doubleEncoded = "\"{\\\"command\\\":\\\"ls\\\"}\"";

        Assertions.assertEquals("{}", ToolCallJsonSanitizer.sanitizeArguments(doubleEncoded, "bash"));
    }

    /**
     * 单引号宽松格式可解析，规范化为标准 JSON 输出（避免过不了服务端严格解析）
     */
    @Test
    public void looseQuoteArgumentsShouldBeNormalized() {
        String result = ToolCallJsonSanitizer.sanitizeArguments("{'command':'ls'}", "bash");

        Assertions.assertEquals("{\"command\":\"ls\"}", result);
    }

    @Test
    public void sanitizeToolCallsRawShouldDeepCopyAndFixArguments() {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", "bash");
        fn.put("arguments", "{\"command\":\"cd "); //截断

        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", "call_5e615271546f4e549073f418");
        call.put("type", "function");
        call.put("function", fn);

        List<Map> raw = new ArrayList<>(Arrays.asList(call));
        List<Map> sanitized = ToolCallJsonSanitizer.sanitizeToolCallsRaw(raw);

        //深拷贝：入参不变
        Assertions.assertEquals("{\"command\":\"cd ", fn.get("arguments"));

        //净化：arguments 变为合法 object 串，其余字段保留
        Map<String, Object> fn2 = (Map<String, Object>) sanitized.get(0).get("function");
        Assertions.assertEquals("{}", fn2.get("arguments"));
        Assertions.assertEquals("bash", fn2.get("name"));
        Assertions.assertEquals("call_5e615271546f4e549073f418", sanitized.get(0).get("id"));

        //合法项保持语义
        Map<String, Object> fnOk = new LinkedHashMap<>();
        fnOk.put("name", "read");
        fnOk.put("arguments", "{\"file_path\":\"src/demo.md\"}");
        Map<String, Object> callOk = new LinkedHashMap<>();
        callOk.put("id", "call_ok");
        callOk.put("type", "function");
        callOk.put("function", fnOk);

        List<Map> sanitized2 = ToolCallJsonSanitizer.sanitizeToolCallsRaw(Arrays.asList(callOk));
        Map<String, Object> fnOk2 = (Map<String, Object>) sanitized2.get(0).get("function");
        Assertions.assertEquals("{\"file_path\":\"src/demo.md\"}", fnOk2.get("arguments"));
    }

    /**
     * arguments 已是对象形态（部分网关返回 Map）时原样保留，不误伤
     */
    @Test
    public void objectArgumentsShouldBeKeptAsIs() {
        Map<String, Object> argsMap = new HashMap<>();
        argsMap.put("command", "ls");

        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", "bash");
        fn.put("arguments", argsMap);

        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", "call_x");
        call.put("type", "function");
        call.put("function", fn);

        List<Map> sanitized = ToolCallJsonSanitizer.sanitizeToolCallsRaw(Arrays.asList(call));
        Map<String, Object> fn2 = (Map<String, Object>) sanitized.get(0).get("function");

        Assertions.assertSame(argsMap, fn2.get("arguments"));
    }

    /**
     * 端到端：带毒历史的 AssistantMessage 经 buildChatMessageNode 出站，
     * 整体请求节点可被严格 JSON 解析，毒 arguments 被替换为 "{}"（会话可复活）
     */
    @Test
    public void poisonedHistoryShouldNotBreakOutboundRequest() {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", "bash");
        fn.put("arguments", "{\"command\":\"cd @solon-ai-source && grep -rn " +
                "\\\"LinkedHashMap\\\\|options()\\\" solon-ai-core/src/main/java/org/noear/solon/ai/chat/ChatOptions");

        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", "call_5e615271546f4e549073f418");
        call.put("type", "function");
        call.put("function", fn);

        AssistantMessage poisoned = new AssistantMessage(
                null, false, null, Arrays.asList(call), null, null);

        TestDialect dialect = new TestDialect();
        ONode node = dialect.buildChatMessageNode(new ChatConfig(), poisoned);

        //模拟服务端严格 json.loads：整体请求体必须可解析（毒参数已被净化，不再 400）
        String requestJson = node.toJson();
        ONode parsed = ONode.ofJson(requestJson);
        Assertions.assertEquals("assistant", parsed.get("role").getString());

        //毒 arguments 已被净化为合法空 object
        ONode toolCall = parsed.get("tool_calls").get(0);
        Assertions.assertEquals("bash", toolCall.get("function").get("name").getString());
        Assertions.assertEquals("{}", toolCall.get("function").get("arguments").getString());

        //content=null 语义保留（OpenAI 规范：有 tool_calls 时 content 字段存在；与 AssistantMessageTest 同口径直接判节点）
        Assertions.assertTrue(node.hasKey("content"));
    }

    /**
     * 流式聚合出口：buildAssistantToolCallMessageNode 对截断的 argumentsBuilder 输出净化节点
     */
    @Test
    public void streamAggregationShouldSanitizeTruncatedArguments() {
        TestDialect dialect = new TestDialect();
        ToolCallBuilder builder =
                new ToolCallBuilder();
        builder.idBuilder.append("call_5e615271546f4e549073f418");
        builder.nameBuilder.append("bash");
        builder.argumentsBuilder.append("{\"command\":\"cd @solon-ai-source && grep -rn " +
                "\\\"LinkedHashMap\\\\|options()\\\" solon-ai-core/src/main/java/org/noear/solon/ai/chat/ChatOptions");

        Map<String, ToolCallBuilder> builders = new LinkedHashMap<>();
        builders.put("call_5e615271546f4e549073f418", builder);

        ONode node = dialect.buildAssistantToolCallMessageNode(new TestResponse(), builders);

        //聚合节点必须整体可严格解析（该节点随后会被 parseAssistantMessage 解析并入 session）
        ONode parsed = ONode.ofJson(node.toJson());
        Assertions.assertEquals("{}", parsed.get("tool_calls").get(0).get("function").get("arguments").getString());
        Assertions.assertEquals("bash", parsed.get("tool_calls").get(0).get("function").get("name").getString());
    }

    /**
     * 空聚合（vllm 兼容）：argumentsBuilder 为空时仍输出 "{}"
     */
    @Test
    public void streamAggregationShouldKeepEmptyArgumentsForObject() {
        TestDialect dialect = new TestDialect();
        ToolCallBuilder builder =
                new ToolCallBuilder();
        builder.idBuilder.append("call_a");
        builder.nameBuilder.append("read");

        Map<String, ToolCallBuilder> builders = new LinkedHashMap<>();
        builders.put("call_a", builder);

        ONode node = dialect.buildAssistantToolCallMessageNode(new TestResponse(), builders);
        Assertions.assertEquals("{}", node.get("tool_calls").get(0).get("function").get("arguments").getString());
    }

    /**
     * 非流式响应（供“分片期不校验、非流式仍校验”的对照测试）
     */
    static class TestResponseNonStream extends ChatResponseDefault {
        public TestResponseNonStream() {
            super(new ChatRequest(
                    new ChatConfig(),
                    new TestDialect(),
                    ChatOptions.of(),
                    InMemoryChatSession.builder().build(),
                    null,
                    null,
                    false), false);
        }
    }

    /**
     * 流式分片帧：arguments 是 JSON 片段（如 '{"comm'），不得被逐帧净化成 "{}"
     *
     * <p>对齐 openai-java ChatCompletionAccumulator / anthropic MessageAccumulator：
     * 分片期只做字符串累积，不校验 JSON。否则订阅侧拿不到增量，且会刷一堆 WARN。</p>
     */
    @Test
    public void streamChunkArgumentsShouldNotBeSanitized() {
        TestDialect dialect = new TestDialect();

        ONode oMessage = new ONode();
        oMessage.getOrNew("tool_calls").asArray().addNew()
                .set("index", 0)
                .set("id", "call_a")
                .set("type", "function")
                .getOrNew("function")
                .set("name", "bash")
                .set("arguments", "{\"comm");

        List<AssistantMessage> messages = dialect.parseAssistantMessage(new TestResponse(), oMessage);

        Assertions.assertFalse(messages.isEmpty());
        AssistantMessage msg = messages.get(0);
        Assertions.assertNotNull(msg.getToolCallsRaw());

        Map fn = (Map) msg.getToolCallsRaw().get(0).get("function");
        //分片原样保留，未被改写为 "{}"
        Assertions.assertEquals("{\"comm", fn.get("arguments"));
    }

    /**
     * 非流式响应仍必须净化：截断的 arguments 不得入历史
     */
    @Test
    public void nonStreamArgumentsShouldStillBeSanitized() {
        TestDialect dialect = new TestDialect();

        ONode oMessage = new ONode();
        oMessage.getOrNew("tool_calls").asArray().addNew()
                .set("id", "call_a")
                .set("type", "function")
                .getOrNew("function")
                .set("name", "bash")
                .set("arguments", "{\"comm");

        List<AssistantMessage> messages = dialect.parseAssistantMessage(new TestResponseNonStream(), oMessage);

        Assertions.assertFalse(messages.isEmpty());
        Map fn = (Map) messages.get(0).getToolCallsRaw().get(0).get("function");
        Assertions.assertEquals("{}", fn.get("arguments"));
    }

    /**
     * 端到端：多个 arguments 分片按 index 聚合后，出口得到完整合法 JSON（而非 "{}"）
     */
    @Test
    public void streamChunksShouldAggregateIntoCompleteArguments() {
        TestDialect dialect = new TestDialect();
        ToolCallBuilder builder = new ToolCallBuilder();
        builder.idBuilder.append("call_a");
        builder.nameBuilder.append("bash");

        //模拟 OpenAI completions 的 delta 分片序列
        for (String chunk : new String[]{"{\"", "comm", "and\"", ":\"", "ls", " -la", "\"}"}) {
            builder.argumentsBuilder.append(chunk);
        }

        Map<String, ToolCallBuilder> builders = new LinkedHashMap<>();
        builders.put("idx:0", builder);

        ONode node = dialect.buildAssistantToolCallMessageNode(new TestResponse(), builders);
        ONode fn = ONode.ofJson(node.toJson()).get("tool_calls").get(0).get("function");

        Assertions.assertEquals("bash", fn.get("name").getString());

        //聚合后是完整参数，不应被兜底成 "{}"
        ONode args = ONode.ofJson(fn.get("arguments").getString());
        Assertions.assertEquals("ls -la", args.get("command").getString());
    }
}
