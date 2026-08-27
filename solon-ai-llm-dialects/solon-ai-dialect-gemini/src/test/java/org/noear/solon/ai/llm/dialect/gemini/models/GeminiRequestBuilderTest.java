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
package org.noear.solon.ai.llm.dialect.gemini.models;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolResult;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GeminiRequestBuilder 方言适配单元测试
 * <p>
 * 对齐 Google Gemini 官方规范（Generate Content API / Function Calling）：
 * <ul>
 *   <li>functionResponse 回传 functionCall 携带的 id（Gemini 3+）</li>
 *   <li>functionResponse.response 必须是键值对 JSON Object（数组/标量包装为 {"result": ...}）</li>
 *   <li>回传的 functionCall 需携带服务端生成的 id</li>
 *   <li>toolConfig.functionCallingConfig.mode（AUTO/ANY/NONE）对齐上层 tool_choice</li>
 * </ul>
 */
public class GeminiRequestBuilderTest {
    private final GeminiRequestBuilder builder = new GeminiRequestBuilder();

    // ==================== functionResponse.id 回传 ====================

    @Test
    public void toolMessageIdEcho_whenRealServerId() {
        // Gemini 3：functionCall 携带 id="call-abc-123"，回传 functionResponse 时必须带上相同 id
        ToolMessage toolMessage = new ToolMessage(new ToolResult("晴"), "getWeather", "call-abc-123", false);

        ONode node = builder.buildMessageNode(toolMessage);

        String json = node.toJson();
        assertTrue(json.contains("\"functionResponse\""), json);
        assertTrue(json.contains("\"id\":\"call-abc-123\""), "应回传服务端生成的 id: " + json);
        assertTrue(json.contains("\"name\":\"getWeather\""), json);
    }

    @Test
    public void toolMessageIdSkipped_whenFallbackToName() {
        // Gemini 2.5 / 代理端不返回 id 时，ToolCall.id 回退为函数名；
        // functionResponse 不应写出与 name 相同的 id（避免污染 2.5 请求）
        ToolMessage toolMessage = new ToolMessage(new ToolResult("晴"), "getWeather", "getWeather", false);

        ONode node = builder.buildMessageNode(toolMessage);

        String json = node.toJson();
        assertFalse(json.contains("\"id\""), "fallback id（等于 name）不应写出: " + json);
    }

    @Test
    public void toolMessageIdSkipped_whenNull() {
        ToolMessage toolMessage = new ToolMessage(new ToolResult("晴"), "getWeather", null, false);

        ONode node = builder.buildMessageNode(toolMessage);

        String json = node.toJson();
        assertFalse(json.contains("\"id\""), json);
    }

    // ==================== functionResponse.response JSON Object 约束 ====================

    @Test
    public void toolMessageResponse_objectKept() {
        // 已经是 JSON Object：原样保留
        ToolMessage toolMessage = new ToolMessage(new ToolResult("{\"temp\":30,\"cond\":\"晴\"}"), "getWeather", "call-1", false);

        ONode node = builder.buildMessageNode(toolMessage);

        ONode response = node.get("parts").get(0).get("functionResponse").get("response");
        assertTrue(response.isObject(), "response 必须是 JSON Object: " + response.toJson());
        assertEquals(30, response.get("temp").getInt());
    }

    @Test
    public void toolMessageResponse_arrayWrapped() {
        // 数组响应：官方规范禁止裸数组，必须包装为 {"result": [...]}
        ToolMessage toolMessage = new ToolMessage(new ToolResult("[1,2,3]"), "getWeather", "call-1", false);

        ONode node = builder.buildMessageNode(toolMessage);

        ONode response = node.get("parts").get(0).get("functionResponse").get("response");
        assertTrue(response.isObject(), "数组必须包装为 JSON Object: " + response.toJson());
        assertTrue(response.get("result").isArray(), response.toJson());
    }

    @Test
    public void toolMessageResponse_scalarWrapped() {
        // 纯文本（非 JSON）：包装为 {"result": "..."}
        ToolMessage toolMessage = new ToolMessage(new ToolResult("杭州今日晴"), "getWeather", "call-1", false);

        ONode node = builder.buildMessageNode(toolMessage);

        ONode response = node.get("parts").get(0).get("functionResponse").get("response");
        assertTrue(response.isObject(), "标量必须包装为 JSON Object: " + response.toJson());
        assertEquals("杭州今日晴", response.get("result").getString());
    }

    // ==================== 回传 functionCall 携带 id ====================

    @Test
    public void assistantFunctionCallIdEcho_whenRealServerId() {
        // 历史 Assistant 消息回传：functionCall 需携带服务端生成的 id（Gemini 3+ 多轮关联依据）
        ToolCall call = new ToolCall("getWeather", "call-abc-123", "getWeather", "{}", new HashMap<>());
        AssistantMessage assistantMessage = new AssistantMessage("", "",false, null, null, Collections.singletonList(call), null, null);

        ONode node = builder.buildMessageNode(assistantMessage);

        String json = node.toJson();
        assertTrue(json.contains("\"functionCall\""), json);
        assertTrue(json.contains("\"id\":\"call-abc-123\""), "functionCall 应回传真实 id: " + json);
    }

    @Test
    public void assistantFunctionCallIdSkipped_whenFallbackToName() {
        // 无服务端 id（fallback 到 name）时不写 id，兼容 Gemini 2.5
        ToolCall call = new ToolCall("getWeather", "getWeather", "getWeather", "{}", new HashMap<>());
        AssistantMessage assistantMessage = new AssistantMessage("", "",false, null, null, Collections.singletonList(call), null, null);

        ONode node = builder.buildMessageNode(assistantMessage);

        String json = node.toJson();
        assertFalse(json.contains("\"id\""), json);
    }

    // ==================== toolConfig.functionCallingConfig.mode ====================

    @Test
    public void toolConfig_none() {
        ChatOptions options = ChatOptions.of();
        options.toolAdd(new FunctionToolDesc("getWeather").description("查询天气"));
        options.optionSet("tool_choice", "none");

        ONode root = new ONode();
        builder.buildToolsNode(root, new ChatConfig(), options);

        assertEquals("NONE", root.get("toolConfig").get("functionCallingConfig").get("mode").getString());
    }

    @Test
    public void toolConfig_any() {
        ChatOptions options = ChatOptions.of();
        options.toolAdd(new FunctionToolDesc("getWeather").description("查询天气"));
        options.optionSet("tool_choice", "required");

        ONode root = new ONode();
        builder.buildToolsNode(root, new ChatConfig(), options);

        assertEquals("ANY", root.get("toolConfig").get("functionCallingConfig").get("mode").getString());
    }

    @Test
    public void toolConfig_allowedFunctionNames() {
        ChatOptions options = ChatOptions.of();
        options.toolAdd(new FunctionToolDesc("getWeather").description("查询天气"));
        Map<String, Object> choiceMap = new HashMap<>();
        choiceMap.put("type", "function");
        choiceMap.put("function", Collections.singletonMap("name", "getWeather"));
        options.optionSet("tool_choice", choiceMap);

        ONode root = new ONode();
        builder.buildToolsNode(root, new ChatConfig(), options);

        ONode fcc = root.get("toolConfig").get("functionCallingConfig");
        assertEquals("ANY", fcc.get("mode").getString());
        assertTrue(fcc.get("allowedFunctionNames").get(0).getString().equals("getWeather"),
                fcc.toJson());
    }

    @Test
    public void toolConfig_auto_omitted() {
        // auto 是 Gemini 默认模式，不应写出 toolConfig（保持请求简洁）
        ChatOptions options = ChatOptions.of();
        options.toolAdd(new FunctionToolDesc("getWeather").description("查询天气"));
        options.optionSet("tool_choice", "auto");

        ONode root = new ONode();
        builder.buildToolsNode(root, new ChatConfig(), options);

        assertFalse(root.hasKey("toolConfig"), "auto 模式不应写出 toolConfig: " + root.toJson());
    }

    @Test
    public void toolConfig_absent_whenNoChoice() {
        // 未配置 tool_choice 时：不输出 toolConfig（默认 AUTO 行为）
        ChatOptions options = ChatOptions.of();
        options.toolAdd(new FunctionToolDesc("getWeather").description("查询天气"));

        ONode root = new ONode();
        builder.buildToolsNode(root, new ChatConfig(), options);

        assertFalse(root.hasKey("toolConfig"), root.toJson());
    }
}
