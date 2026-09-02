/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.llm.dialect.dashscope;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.core.util.MultiMap;
import org.noear.solon.net.http.HttpUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DashScope 方言的请求侧契约：方言匹配、SSE 请求头、原生 {@code input/parameters} 报文结构
 *
 * <p>原生协议（非 OpenAI 兼容口）：消息在 {@code input.messages}，其余参数在 {@code parameters}；
 * 流式由请求头 {@code X-DashScope-SSE} 控制，思考开关为 {@code parameters.enable_thinking}。</p>
 *
 * @author noear
 */
public class DashscopeChatDialectRequestTest {
    private static final String NATIVE_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";

    private final DashscopeChatDialect dialect = DashscopeChatDialect.getInstance();

    private ChatConfig config(String standard, String provider, String apiUrl) {
        ChatConfig config = new ChatConfig();
        config.setModel("qwen-plus");
        config.setStandard(standard);
        config.setProvider(provider);
        config.setApiUrl(apiUrl);
        return config;
    }

    private ONode build(ChatOptions options, boolean isStream) {
        ChatConfig config = new ChatConfig();
        config.setModel("qwen-plus");
        return dialect.buildRequestJson(config, options,
                Collections.singletonList(ChatMessage.ofUser("hi")), isStream);
    }

    private ONode parameters(ChatOptions options) {
        return build(options, true).get("parameters");
    }

    /**
     * 读取 HttpUtils 上已设置的请求头（无公开读取入口，反射取实现字段；不发起任何请求）
     */
    @SuppressWarnings("unchecked")
    private static String headerOf(HttpUtils httpUtils, String name) throws Exception {
        Class<?> clazz = httpUtils.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField("_headers");
                field.setAccessible(true);
                MultiMap<String> headers = (MultiMap<String>) field.get(httpUtils);
                return headers == null ? null : headers.get(name);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        fail("HttpUtils 实现未暴露 _headers 字段");
        return null;
    }

    /// ////////////////////////// 方言匹配

    /**
     * 接口规范显式声明：大小写不敏感
     */
    @Test
    public void matchedByStandard() {
        assertTrue(dialect.matched(config("dashscope", null, "https://any.example.com/v1")));
        assertTrue(dialect.matched(config("DashScope", null, "https://any.example.com/v1")));
    }

    /**
     * 未声明 standard 时回退 provider（getStandardOrProvider）
     */
    @Test
    public void matchedByProviderWhenStandardAbsent() {
        assertTrue(dialect.matched(config(null, "dashscope", "https://any.example.com/v1")));
    }

    /**
     * 非 dashscope 规范：仅当地址命中原生服务前缀才认领
     */
    @Test
    public void matchedByNativeUrlPrefix() {
        assertTrue(dialect.matched(config("openai", "aliyun", NATIVE_URL)));
        assertFalse(dialect.matched(config("openai", "openai", "https://api.openai.com/v1/chat/completions")),
                "OpenAI 兼容口不应被原生方言认领");
        assertFalse(dialect.matched(config(null, null,
                        "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")),
                "百炼的 OpenAI 兼容口不是原生协议地址");
    }

    /// ////////////////////////// SSE 请求头

    /**
     * 流式由请求头开启（官方文档：X-DashScope-SSE=enable），非流式不得带
     */
    @Test
    public void streamEnablesSseHeaderOnly() throws Exception {
        ChatConfig config = config("dashscope", null, NATIVE_URL);

        assertEquals("enable", headerOf(dialect.createHttpUtils(config, true), "X-DashScope-SSE"));
        assertNull(headerOf(dialect.createHttpUtils(config, false), "X-DashScope-SSE"),
                "非流式请求不得开启 SSE");
    }

    /// ////////////////////////// 报文骨架

    /**
     * 原生骨架：model / input.messages / stream / parameters.result_format
     */
    @Test
    public void nativeRequestSkeleton() {
        ONode req = build(ChatOptions.of(), false);

        assertEquals("qwen-plus", req.get("model").getString());
        assertEquals(1, req.get("input").get("messages").getArray().size());
        assertEquals("hi", req.get("input").get("messages").get(0).get("content").getString());
        assertFalse(req.get("stream").getBoolean());
        assertEquals("message", req.get("parameters").get("result_format").getString(),
                "result_format=message 才有 output.choices[].message 结构");
    }

    /**
     * 模型为空：不写出 model 键（交由端点默认）
     */
    @Test
    public void modelKeyOmittedWhenEmpty() {
        ChatConfig config = new ChatConfig();

        ONode req = dialect.buildRequestJson(config, ChatOptions.of(),
                Collections.singletonList(ChatMessage.ofUser("hi")), false);

        assertFalse(req.hasKey("model"), "空模型不得写出 model 键");
        assertTrue(req.get("input").hasKey("messages"));
    }

    /**
     * 思考消息不回传（原生多轮建议不回灌 reasoning_content）
     */
    @Test
    public void thinkingMessagesAreNotSentBack() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.ofUser("hi"));
        messages.add(new AssistantMessage("", "内部思考", true));
        messages.add(ChatMessage.ofAssistant("你好"));

        ChatConfig config = new ChatConfig();
        config.setModel("qwen-plus");
        ONode messagesNode = dialect.buildRequestJson(config, ChatOptions.of(), messages, false)
                .get("input").get("messages");

        assertEquals(2, messagesNode.getArray().size(), "思考消息必须被过滤");
        assertEquals("user", messagesNode.get(0).get("role").getString());
        assertEquals("assistant", messagesNode.get(1).get("role").getString());
        assertEquals("你好", messagesNode.get(1).get("content").getString());
    }

    /// ////////////////////////// 思考开关

    /**
     * 统一 thinking(Boolean) → 原生 parameters.enable_thinking（不透传 thinking 键）
     */
    @Test
    public void thinkingBooleanMapsToEnableThinking() {
        ONode on = parameters(ChatOptions.of().thinking(true));
        assertTrue(on.get("enable_thinking").getBoolean());
        assertFalse(on.hasKey("thinking"), "统一开关不得原样透传");

        ONode off = parameters(ChatOptions.of().thinking(false));
        assertTrue(off.hasKey("enable_thinking"));
        assertFalse(off.get("enable_thinking").getBoolean());
    }

    /**
     * 非布尔 thinking（供应商原生结构逃生舱）：按原 key 透传
     */
    @Test
    public void thinkingNonBooleanIsPassedThrough() {
        Map<String, Object> native0 = new LinkedHashMap<>();
        native0.put("type", "enabled");

        ONode params = parameters(ChatOptions.of().optionSet("thinking", native0));

        assertFalse(params.hasKey("enable_thinking"), "逃生舱不得被改写为布尔开关");
        assertEquals("enabled", params.get("thinking").get("type").getString());
    }

    /**
     * reasoning_effort 不是原生 parameters 字段：只作「开启思考」信号，本身不写出
     */
    @Test
    public void reasoningEffortOnlyImpliesThinking() {
        ONode params = parameters(ChatOptions.of().optionSet("reasoning_effort", "high"));

        assertFalse(params.hasKey("reasoning_effort"), "原生协议无该字段，不得透传");
        assertTrue(params.get("enable_thinking").getBoolean(), "选了档位即隐式开启思考");
    }

    /**
     * 无效档位（null / 空 / auto / none）：既不透传也不隐式开启
     */
    @Test
    public void blankReasoningEffortNeverEnablesThinking() {
        for (Object effort : Arrays.asList("", "  ", "auto", "AUTO", "none")) {
            ONode params = parameters(ChatOptions.of().optionSet("reasoning_effort", effort));
            assertFalse(params.hasKey("enable_thinking"), "无效档位 [" + effort + "] 不得开启思考");
            assertFalse(params.hasKey("reasoning_effort"));
        }

        ONode nullEffort = parameters(ChatOptions.of().optionSet("reasoning_effort", null));
        assertFalse(nullEffort.hasKey("enable_thinking"), "null 档位不得开启思考");
        assertFalse(nullEffort.hasKey("reasoning_effort"));
    }

    /**
     * 关闭优先：thinking(false) 压过 reasoning_effort 的隐式开启
     */
    @Test
    public void explicitThinkingFalseBeatsReasoningEffort() {
        ChatOptions options = ChatOptions.of().thinking(false);
        options.optionSet("reasoning_effort", "high");

        ONode params = parameters(options);

        assertTrue(params.hasKey("enable_thinking"));
        assertFalse(params.get("enable_thinking").getBoolean(), "关闭优先");
    }

    /**
     * 已写出的 enable_thinking 不被隐式逻辑覆盖
     */
    @Test
    public void explicitThinkingTrueIsNotOverwritten() {
        ChatOptions options = ChatOptions.of().thinking(true);
        options.optionSet("reasoning_effort", "medium");

        assertTrue(parameters(options).get("enable_thinking").getBoolean());
    }

    /**
     * 用户直接指定原生 enable_thinking：隐式逻辑不覆盖
     */
    @Test
    public void userEnableThinkingOptionWins() {
        ChatOptions options = ChatOptions.of().optionSet("enable_thinking", false);
        options.optionSet("reasoning_effort", "high");

        ONode params = parameters(options);

        assertTrue(params.hasKey("enable_thinking"));
        assertFalse(params.get("enable_thinking").getBoolean(), "用户显式值优先");
    }

    /**
     * 其余选项按原样落到 parameters
     */
    @Test
    public void plainOptionsGoIntoParameters() {
        ChatOptions options = ChatOptions.of().temperature(0.7D);
        options.optionSet("top_p", 0.9D);
        options.optionSet("stop", Arrays.asList("\n\n"));

        ONode params = parameters(options);

        assertEquals(0.7D, params.get("temperature").getDouble(), 0.0001D);
        assertEquals(0.9D, params.get("top_p").getDouble(), 0.0001D);
        assertEquals("\n\n", params.get("stop").get(0).getString());
    }

    /**
     * 工具声明写在 parameters.tools（原生协议位置）
     */
    @Test
    public void toolsGoIntoParameters() {
        ChatOptions options = ChatOptions.of();
        options.toolAdd("get_weather", desc -> desc
                .description("查天气")
                .stringParamAdd("location", "城市"));

        ONode tools = parameters(options).get("tools");

        assertEquals(1, tools.getArray().size());
        assertEquals("function", tools.get(0).get("type").getString());
        assertEquals("get_weather", tools.get(0).get("function").get("name").getString());
        assertTrue(tools.get(0).get("function").get("parameters").get("properties").hasKey("location"));
    }

    /**
     * 无工具时不写出 tools 键（空数组会被部分端点拒绝）
     */
    @Test
    public void noToolsKeyWhenEmpty() {
        assertFalse(parameters(ChatOptions.of()).hasKey("tools"));
    }
}
