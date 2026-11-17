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
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.mcp.client.McpClientProperties;
import org.noear.solon.ai.talents.gateway.McpGatewayTalent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * selected 视野语义：
 * search_tools 按视野收窄；get_tool_detail / call_tool 是点名使用，只受强制边界（_mcps_allowed）约束。
 *
 * <p>定位语义：工具按「服务 → 工具」定位，search_tools 结果带 server_name，
 * get_tool_detail / call_tool 以 server_name + tool_name 点名（server_name 为空且边界内唯一时自动补上）。
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
            this(tools, null);
        }

        FakeProvider(List<FunctionTool> tools, String description) {
            super(props(description));
            this.tools = tools;
        }

        private static McpClientProperties props(String description) {
            return new McpClientProperties().then(p -> {
                p.setChannel("sse");
                p.setUrl("http://127.0.0.1:1/mcp");
                p.setDescription(description);
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

        assertEquals(Collections.singletonList("alpha"), talent.getServersOfTool("ALPHA", "alpha_fetch"), "服务名大小写不敏感");
        assertTrue(talent.getServersOfTool("beta", "alpha_fetch").isEmpty(), "服务里没有该工具");
        assertTrue(talent.getServersOfTool("ghost", "alpha_fetch").isEmpty());
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
            assertFalse(t.inputSchema().contains("_mcps_selected"), t.name() + " schema leaks implicit param");
        }
    }

    // ---------- 视野（_mcps_selected） ----------

    private static String search(Map<String, FunctionTool> tools, List<String> selected, List<String> allowed) throws Throwable {
        Map<String, Object> args = new HashMap<>();
        args.put("keyword", "搜索");
        if (selected != null) {
            args.put(McpGatewayTalent.CTX_MCPS_SELECTED, selected);
        }
        if (allowed != null) {
            args.put(McpGatewayTalent.CTX_MCPS_ALLOWED, allowed);
        }
        return String.valueOf(tools.get("search_tools").handle(args));
    }

    @Test
    void searchRespectsSelected() throws Throwable {
        McpGatewayTalent talent = build();
        Map<String, FunctionTool> tools = toolsOf(talent);

        // 有视野：只搜得到视野内的服务
        String selected = search(tools, Arrays.asList("beta"), null);
        assertTrue(selected.contains("beta_search"));
        assertFalse(selected.contains("alpha_search"));

        // 无视野 / 空视野：不限制
        String all = search(tools, null, null);
        assertTrue(all.contains("beta_search"));
        assertTrue(all.contains("alpha_search"));

        String empty = search(tools, new ArrayList<>(), null);
        assertTrue(empty.contains("beta_search"));
        assertTrue(empty.contains("alpha_search"));

        // 视野与强制边界叠加：取交集
        String both = search(tools, Arrays.asList("alpha", "beta"), Arrays.asList("beta"));
        assertTrue(both.contains("beta_search"));
        assertFalse(both.contains("alpha_search"));

        // 视野里只有不存在的服务：结果为空，不退回全量
        String ghost = search(tools, Arrays.asList("ghost"), null);
        assertFalse(ghost.contains("beta_search"));
        assertFalse(ghost.contains("alpha_search"));

        // 点名使用不受视野限制
        Map<String, Object> detail = new HashMap<>();
        detail.put("tool_name", "alpha_search");
        detail.put(McpGatewayTalent.CTX_MCPS_SELECTED, Arrays.asList("beta"));
        String detailText = String.valueOf(tools.get("get_tool_detail").handle(detail));
        assertTrue(detailText.contains("alpha_search"));
        assertFalse(detailText.contains("未找到"));
    }

    // ---------- 同名工具（按服务定位） ----------

    private static McpGatewayTalent buildDuplicate(AtomicInteger alphaCounter, AtomicInteger betaCounter) {
        McpGatewayTalent talent = new McpGatewayTalent().dynamicThreshold(8);
        talent.addMcpServer("alpha", new FakeProvider(Arrays.asList(
                mockTool("query", "阿尔法查询", alphaCounter),
                mockTool("alpha_fetch", "阿尔法抓取", alphaCounter))));
        talent.addMcpServer("beta", new FakeProvider(Arrays.asList(
                mockTool("query", "贝塔查询", betaCounter))));
        return talent;
    }

    private static Object call(Map<String, FunctionTool> tools, String server, String tool, List<String> allowed) throws Throwable {
        Map<String, Object> args = new HashMap<>();
        if (server != null) {
            args.put("server_name", server);
        }
        args.put("tool_name", tool);
        args.put("tool_args", new HashMap<>());
        if (allowed != null) {
            args.put(McpGatewayTalent.CTX_MCPS_ALLOWED, allowed);
        }
        return tools.get("call_tool").handle(args);
    }

    private static String detail(Map<String, FunctionTool> tools, String server, String tool, List<String> allowed) throws Throwable {
        Map<String, Object> args = new HashMap<>();
        if (server != null) {
            args.put("server_name", server);
        }
        args.put("tool_name", tool);
        if (allowed != null) {
            args.put(McpGatewayTalent.CTX_MCPS_ALLOWED, allowed);
        }
        return String.valueOf(tools.get("get_tool_detail").handle(args));
    }

    @Test
    void duplicateToolNamesAreLocatedByServer() throws Throwable {
        AtomicInteger alpha = new AtomicInteger();
        AtomicInteger beta = new AtomicInteger();
        McpGatewayTalent talent = buildDuplicate(alpha, beta);
        Map<String, FunctionTool> tools = toolsOf(talent);

        // 两个同名工具都被索引（不再互相覆盖）
        assertEquals(Arrays.asList("alpha", "beta"), talent.getServersOfTool(null, "query"));
        assertNull(talent.getServerOfTool("query"), "同名工具属于多个服务时不给唯一归属");

        // 点名服务：调到的是指定服务的工具（服务名大小写不敏感）
        assertEquals("ok:query", ((ToolResult) call(tools, "beta", "query", null)).getContent());
        assertEquals(0, alpha.get());
        assertEquals(1, beta.get());
        call(tools, "ALPHA", "query", null);
        assertEquals(1, alpha.get());

        // 不带服务名且有多个候选：提示指定服务，不执行
        String ambiguous = ((ToolResult) call(tools, null, "query", null)).getContent();
        assertTrue(ambiguous.contains("alpha") && ambiguous.contains("beta"), ambiguous);
        assertTrue(ambiguous.contains("server_name"), ambiguous);
        assertEquals(1, alpha.get());
        assertEquals(1, beta.get());

        // 不带服务名且工具唯一：自动定位（兼容旧写法）
        call(tools, null, "alpha_fetch", null);
        assertEquals(2, alpha.get());

        // 服务里没有该工具：按不存在处理
        assertTrue(((ToolResult) call(tools, "beta", "alpha_fetch", null)).getContent().contains("未找到"));
        assertEquals(2, alpha.get());

        // 详情带所属服务
        String betaDetail = detail(tools, "beta", "query", null);
        assertTrue(betaDetail.contains("贝塔查询"), betaDetail);
        assertTrue(betaDetail.contains("beta"), betaDetail);
        assertTrue(detail(tools, null, "query", null).contains("server_name"), "详情同样提示指定服务");
    }

    @Test
    void duplicateToolCannotBypassAllowedBoundary() throws Throwable {
        AtomicInteger alpha = new AtomicInteger();
        AtomicInteger beta = new AtomicInteger();
        McpGatewayTalent talent = buildDuplicate(alpha, beta);
        Map<String, FunctionTool> tools = toolsOf(talent);
        List<String> allowed = Collections.singletonList("alpha");

        // 点名边界外服务的同名工具：按不存在处理，绝不执行
        String denied = ((ToolResult) call(tools, "beta", "query", allowed)).getContent();
        assertTrue(denied.contains("未找到"), denied);
        assertEquals(0, beta.get());
        assertTrue(detail(tools, "beta", "query", allowed).contains("未找到"));

        // 不带服务名：只在边界内找，唯一候选自动定位，且不泄露边界外服务
        call(tools, null, "query", allowed);
        assertEquals(1, alpha.get());
        assertEquals(0, beta.get());
        String d = detail(tools, null, "query", allowed);
        assertTrue(d.contains("阿尔法查询"), d);
        assertFalse(d.contains("beta"), d);
    }

    @Test
    void searchResultsCarryServerName() throws Throwable {
        McpGatewayTalent talent = buildDuplicate(new AtomicInteger(), new AtomicInteger());
        Map<String, FunctionTool> tools = toolsOf(talent);

        Map<String, Object> args = new HashMap<>();
        args.put("keyword", "查询");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> rows = (List<Map<String, String>>) tools.get("search_tools").handle(args);
        assertEquals(2, rows.size(), "同名工具分别列出");
        assertEquals("alpha", rows.get(0).get("server_name"));
        assertEquals("beta", rows.get(1).get("server_name"));
        assertEquals("query", rows.get(1).get("tool_name"));
        assertEquals(Arrays.asList("server_name", "tool_name", "tool_description"), new ArrayList<>(rows.get(0).keySet()));
    }

    @Test
    void removingServerKeepsOtherServersSameNamedTool() throws Throwable {
        AtomicInteger beta = new AtomicInteger();
        McpGatewayTalent talent = buildDuplicate(new AtomicInteger(), beta);

        talent.removeMcpServer("alpha");
        assertEquals("beta", talent.getServerOfTool("query"));

        call(toolsOf(talent), null, "query", null);
        assertEquals(1, beta.get());

        // 刷新也只替换本服务的工具
        talent.refreshMcpServer("beta");
        assertEquals("beta", talent.getServerOfTool("query"));
    }

    @Test
    void callToolDoesNotForwardFrameworkKeys() throws Throwable {
        Map<String, Object> received = new HashMap<>();
        McpGatewayTalent talent = new McpGatewayTalent();
        talent.addMcpServer("alpha", new FakeProvider(Collections.singletonList(new FunctionToolDesc("alpha_search")
                .description("a").inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .doHandle(args -> {
                    received.putAll(args);
                    return ToolResult.success("ok");
                }))));

        Map<String, Object> toolArgs = new HashMap<>();
        toolArgs.put("q", "x");
        Map<String, Object> args = new HashMap<>();
        args.put("tool_name", "alpha_search");
        args.put("tool_args", toolArgs);
        args.put(McpGatewayTalent.CTX_MCPS_ALLOWED, Arrays.asList("alpha"));
        args.put(McpGatewayTalent.CTX_MCPS_SELECTED, Arrays.asList("alpha"));
        args.put("__locale", "zh-CN");
        toolsOf(talent).get("call_tool").handle(args);

        assertEquals("x", received.get("q"), "模型给的参数照常转发");
        assertFalse(received.containsKey(McpGatewayTalent.CTX_MCPS_ALLOWED));
        assertFalse(received.containsKey(McpGatewayTalent.CTX_MCPS_SELECTED));
        assertFalse(received.containsKey("__locale"));
    }

    // ---------- 服务目录与 list_server_tools ----------

    private static String list(Map<String, FunctionTool> tools, String server, String keyword,
                               List<String> selected, List<String> allowed) throws Throwable {
        Map<String, Object> args = new HashMap<>();
        args.put("server_name", server);
        if (keyword != null) {
            args.put("keyword", keyword);
        }
        if (selected != null) {
            args.put(McpGatewayTalent.CTX_MCPS_SELECTED, selected);
        }
        if (allowed != null) {
            args.put(McpGatewayTalent.CTX_MCPS_ALLOWED, allowed);
        }
        return String.valueOf(tools.get("list_server_tools").handle(args));
    }

    @Test
    void listServerToolsRespectsScope() throws Throwable {
        McpGatewayTalent talent = build();
        Map<String, FunctionTool> tools = toolsOf(talent);

        String all = list(tools, "ALPHA", null, null, null);
        assertTrue(all.contains("alpha_search") && all.contains("alpha_fetch"), "服务名大小写不敏感");
        assertFalse(all.contains("beta_search"));

        String filtered = list(tools, "alpha", "抓取", null, null);
        assertTrue(filtered.contains("alpha_fetch"));
        assertFalse(filtered.contains("alpha_search"), "keyword 在服务内过滤");

        // 发现属性：视野外、边界外的服务都按不存在处理
        assertTrue(list(tools, "alpha", null, Arrays.asList("beta"), null).contains("未找到"));
        assertTrue(list(tools, "alpha", null, null, Arrays.asList("beta")).contains("未找到"));
        assertTrue(list(tools, "beta", null, Arrays.asList("beta"), Arrays.asList("beta")).contains("beta_search"));
        assertTrue(list(tools, "ghost", null, null, null).contains("未找到"));

        assertFalse(tools.get("list_server_tools").inputSchema().contains("_mcps_"), "隐式参数不进 schema");
    }

    @Test
    void listServerToolsTruncates() throws Throwable {
        McpGatewayTalent talent = build().listToolsLimit(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) talent.listServerTools("alpha", null, null, null, null);
        assertEquals(2, result.get("tool_count"), "tool_count 是命中总数");
        assertEquals(1, ((List<?>) result.get("tools")).size());
        assertTrue(String.valueOf(result.get("tip")).contains("keyword"));
    }

    @Test
    void serverDescriptionAndTools() {
        McpGatewayTalent talent = new McpGatewayTalent();
        talent.addMcpServer("weather", new FakeProvider(Arrays.asList(
                mockTool("forecast", "天气预报", new AtomicInteger()),
                mockTool("now", "实时天气", new AtomicInteger())), "  天气查询  "));
        talent.addMcpServer("plain", new FakeProvider(Collections.singletonList(
                mockTool("echo", "回显", new AtomicInteger()))));

        assertEquals("天气查询", talent.getMcpServerDescription("WEATHER"));
        assertNull(talent.getMcpServerDescription("plain"), "未配置且未连接：没有描述");
        assertNull(talent.getMcpServerDescription("ghost"));

        List<String> names = new ArrayList<>();
        for (FunctionTool t : talent.getMcpServerTools("weather")) {
            names.add(t.name());
        }
        assertEquals(Arrays.asList("forecast", "now"), names, "顺序同服务端");
        assertTrue(talent.getMcpServerTools("ghost").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> talent.getMcpServerTools("weather").clear());
    }

    private static List<String> toolNames(McpGatewayTalent talent, Prompt prompt) {
        List<String> names = new ArrayList<>();
        for (FunctionTool t : talent.getTools(prompt)) {
            names.add(t.name());
        }
        return names;
    }

    @Test
    void catalogModeShowsServersAndOffersDrillDown() {
        McpGatewayTalent talent = build().dynamicThreshold(0).listThreshold(0).searchThreshold(1).catalogThreshold(2);
        talent.addMcpServer("alpha", new FakeProvider(Arrays.asList(
                mockTool("alpha_search", "阿尔法搜索", new AtomicInteger()),
                mockTool("alpha_fetch", "阿尔法抓取", new AtomicInteger())), "阿尔法服务"));

        String instruction = talent.getInstruction(null);
        assertTrue(instruction.contains("**alpha**（2 个工具）：阿尔法服务"), instruction);
        assertTrue(instruction.contains("**beta**（1 个工具）：包含 beta_search"), "没有描述时自动生成");
        assertFalse(instruction.contains("alpha_fetch"), "目录不列工具");
        assertTrue(toolNames(talent, null).containsAll(Arrays.asList("search_tools", "list_server_tools", "get_tool_detail", "call_tool")));

        // 目录按视野收窄
        Prompt prompt = Prompt.of("hi");
        McpGatewayTalent.putSelected(prompt, Collections.singletonList("alpha"));
        assertFalse(talent.searchThreshold(1).getInstruction(prompt).contains("beta"));

        // 服务数超过 catalogThreshold：退回搜索模式，仍提供 list_server_tools
        talent.catalogThreshold(1);
        assertFalse(talent.getInstruction(null).contains("阿尔法服务"));
        assertTrue(toolNames(talent, null).contains("list_server_tools"));

        // LIST 与 SUMMARY 模式不提供下钻；有描述时服务标题带上描述
        talent.searchThreshold(10);
        assertFalse(toolNames(talent, null).contains("list_server_tools"));
        assertTrue(talent.getInstruction(null).contains("**alpha** (阿尔法服务)"));
        talent.listThreshold(10);
        assertFalse(toolNames(talent, null).contains("list_server_tools"));
        assertTrue(talent.getInstruction(null).contains("**[alpha]**: 阿尔法服务"));
    }

    @Test
    void duplicateNamesDisableFullMode() {
        McpGatewayTalent talent = buildDuplicate(new AtomicInteger(), new AtomicInteger());
        // 3 个工具不超过 dynamicThreshold，但有同名：降为摘要模式，走带服务名的中转
        List<String> names = new ArrayList<>();
        for (FunctionTool t : talent.getTools(null)) {
            names.add(t.name());
        }
        assertTrue(names.contains("call_tool"), names.toString());
        assertFalse(names.contains("query"), names.toString());

        // 只看一个服务时没有同名：恢复直接平铺
        Prompt prompt = Prompt.of("hi");
        McpGatewayTalent.putSelected(prompt, Collections.singletonList("alpha"));
        names.clear();
        for (FunctionTool t : talent.getTools(prompt)) {
            names.add(t.name());
        }
        assertEquals(Arrays.asList("query", "alpha_fetch"), names);
    }
}
