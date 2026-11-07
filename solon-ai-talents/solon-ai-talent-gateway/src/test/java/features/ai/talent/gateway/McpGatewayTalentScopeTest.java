/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package features.ai.talent.gateway;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.client.McpClientProperties;
import org.noear.solon.ai.talents.gateway.McpGatewayTalent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * selected 视野语义：
 * 发现层（search_tools / get_tool_detail）按视野收窄；执行层（call_tool）不硬控。
 */
public class McpGatewayTalentScopeTest {
    private static FunctionTool mockTool(String name, String desc, AtomicInteger counter) {
        return new FunctionToolDesc(name)
                .description(desc)
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .doHandle(args -> {
                    counter.incrementAndGet();
                    return ToolResult.success("ok:" + name);
                });
    }

    private static class FakeProvider extends McpClientProvider {
        private final List<FunctionTool> tools;

        FakeProvider(List<FunctionTool> tools) {
            super(props());
            this.tools = tools;
        }

        private static McpClientProperties props() {
            return new McpClientProperties().then(p -> {
                p.setChannel("sse");
                p.setUrl("http://127.0.0.1:1/mcp");
            });
        }

        @Override
        public Collection<FunctionTool> getToolsActivated() {
            return tools;
        }

        @Override
        public void close() {
        }
    }

    private static McpGatewayTalent build() {
        AtomicInteger counter = new AtomicInteger();
        McpGatewayTalent talent = new McpGatewayTalent();

        talent.addMcpServer("alpha", new FakeProvider(Arrays.asList(
                mockTool("alpha_search", "阿尔法搜索", counter),
                mockTool("alpha_fetch", "阿尔法抓取", counter))));
        talent.addMcpServer("beta", new FakeProvider(Arrays.asList(
                mockTool("beta_search", "贝塔搜索", counter))));

        return talent;
    }

    @Test
    void callToolIsNotBlockedByScope() throws Throwable {
        // selected 是视野不是权限：点名执行必须放行（用户可能在 prompt 里引导）
        McpGatewayTalent talent = build();

        ToolResult result = talent.callTool("beta_search", new HashMap<>());
        assertNotNull(result);
        assertFalse(result.getContent().contains("不在本次选中"));
    }

    // ---------- 强制边界（_mcps_allowed） ----------

    private static Map<String, FunctionTool> toolsOf(McpGatewayTalent talent) {
        Map<String, FunctionTool> map = new HashMap<>();
        for (FunctionTool t : talent.getToolAry()) {
            map.put(t.name(), t);
        }
        return map;
    }

    @Test
    void serverOfTool() {
        McpGatewayTalent talent = build();
        assertEquals("alpha", talent.getServerOfTool("alpha_fetch"));
        assertEquals("beta", talent.getServerOfTool("BETA_SEARCH"));
        assertNull(talent.getServerOfTool("bash"));
    }

    @Test
    void callToolOutsideAllowedLooksLikeMissingAndBoundaryNotForwarded() throws Throwable {
        AtomicInteger counter = new AtomicInteger();
        Map<String, Object> received = new HashMap<>();
        McpGatewayTalent talent = new McpGatewayTalent();
        talent.addMcpServer("alpha", new FakeProvider(Arrays.asList(new FunctionToolDesc("alpha_search")
                .description("a").inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .doHandle(args -> {
                    counter.incrementAndGet();
                    received.putAll(args);
                    return ToolResult.success("ok");
                }))));
        talent.addMcpServer("beta", new FakeProvider(Arrays.asList(mockTool("beta_search", "b", counter))));

        FunctionTool callTool = toolsOf(talent).get("call_tool");

        Map<String, Object> args = new HashMap<>();
        args.put("tool_name", "beta_search");
        args.put("tool_args", new HashMap<>());
        args.put(McpGatewayTalent.CTX_MCPS_ALLOWED, Arrays.asList("alpha"));
        ToolResult denied = (ToolResult) callTool.handle(args);
        assertTrue(denied.getContent().contains("未找到MCP工具"), "越界按不存在处理");
        assertEquals(0, counter.get());

        args.put("tool_name", "alpha_search");
        callTool.handle(args);
        assertEquals(1, counter.get());
        assertFalse(received.containsKey(McpGatewayTalent.CTX_MCPS_ALLOWED), "边界键不转发给 MCP 服务");
    }

    @Test
    void searchAndDetailRespectAllowed() throws Throwable {
        McpGatewayTalent talent = build();
        Map<String, FunctionTool> tools = toolsOf(talent);

        Map<String, Object> args = new HashMap<>();
        args.put("keyword", "搜索");
        args.put(McpGatewayTalent.CTX_MCPS_ALLOWED, Arrays.asList("beta"));
        String found = String.valueOf(tools.get("search_tools").handle(args));
        assertTrue(found.contains("beta_search"));
        assertFalse(found.contains("alpha_search"));

        Map<String, Object> detail = new HashMap<>();
        detail.put("tool_name", "alpha_search");
        detail.put(McpGatewayTalent.CTX_MCPS_ALLOWED, Arrays.asList("beta"));
        assertTrue(String.valueOf(tools.get("get_tool_detail").handle(detail)).contains("未找到"));

        for (FunctionTool t : talent.getToolAry()) {
            assertFalse(t.inputSchema().contains("_mcps_allowed"), t.name() + " schema leaks implicit param");
        }
    }
}
