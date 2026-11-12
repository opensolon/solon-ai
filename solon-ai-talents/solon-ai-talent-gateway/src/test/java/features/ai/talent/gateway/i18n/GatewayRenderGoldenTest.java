package features.ai.talent.gateway.i18n;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.mcp.client.McpClientProperties;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.ai.talents.gateway.McpGatewayTalent;
import org.noear.solon.ai.talents.gateway.OpenApiGatewayTalent;
import org.noear.solon.ai.talents.gateway.ToolGatewayTalent;
import org.noear.solon.ai.talents.gateway.openapi.ApiResolver;
import org.noear.solon.ai.talents.gateway.openapi.ApiSource;
import org.noear.solon.ai.talents.gateway.openapi.ApiTool;
import org.noear.solon.ai.i18n.testkit.GoldenFiles;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * 三个网关才能对模型可见文本的渲染快照（描述、四种模式的指令、工具 schema、中转工具返回值）
 *
 * <p>中文快照由改造前的代码生成，国际化后必须逐字不变；英文快照经人工审校后生成。</p>
 */
public class GatewayRenderGoldenTest {
    static HttpServer server;
    static String base;

    /**
     * 本地 HTTP 服务（按需启动一次；覆盖率统计不支持 @BeforeAll，故不用生命周期注解）
     */
    static synchronized void ensureServer() throws Exception {
        if (server != null) {
            return;
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        reply("/doc", "{}");
        reply("/api/ok", "{\"ok\":true}");
        reply("/api/big", "0123456789abcdefghijklmnopqrstuvwxyz");
        server.createContext("/api/slow", ex -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
            }
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        //慢接口会占住处理线程，用线程池避免拖慢后续用例的文档请求（守护线程，进程结束时自动退出）
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "golden-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    static void reply(String path, String body) {
        server.createContext(path, ex -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    @Test
    public void testZh() throws Throwable {
        GoldenFiles.verify(renderOf(null, "_zh"));
    }

    /**
     * 请求语言为中文时与默认一致
     */
    @Test
    public void testZhExplicit() throws Throwable {
        GoldenFiles.verify(renderOf("zh-CN", "_zh"));
    }

    @Test
    public void testEn() throws Throwable {
        GoldenFiles.verify(renderOf("en", "_en"));
    }

    static Prompt prompt(String tag) {
        Prompt prompt = Prompt.of("hi");
        if (tag != null) {
            prompt.locale(AiI18n.toLocale(tag));
        }
        return prompt;
    }

    /**
     * 经本地化后的 call_tool 调用（与模型调用一致：工具上下文带 __locale）
     */
    static Object callTool(AbsTalent t, String tag, String name) throws Throwable {
        Map<String, Object> args = new HashMap<>();
        args.put("tool_name", name);
        args.put("tool_args", new HashMap<String, Object>());
        if (tag != null) {
            args.put(AiI18n.ATTR_LOCALE, tag);
        }
        return t.getToolMap().get("call_tool").call(args);
    }

    // ---------------- tool gateway ----------------

    static FunctionTool tool(String name, String desc) {
        return new FunctionToolDesc(name)
                .description(desc)
                .inputSchema("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}")
                .doHandle(args -> {
                    if (name.startsWith("boom")) {
                        throw new IllegalStateException("boom!");
                    }
                    return ToolResult.success("ok:" + name);
                });
    }

    static ToolGatewayTalent toolGateway(int count) {
        ToolGatewayTalent talent = new ToolGatewayTalent().dynamicThreshold(1).listThreshold(2).searchThreshold(3).retryConfig(1);
        for (int i = 1; i <= count; i++) {
            talent.addTool(i % 2 == 0 ? "财务" : null, tool("tool_" + i, "工具 " + i));
        }
        return talent;
    }

    // ---------------- mcp gateway ----------------

    static class FakeProvider extends McpClientProvider {
        private final List<FunctionTool> tools;

        FakeProvider(List<FunctionTool> tools) {
            super(new McpClientProperties().then(p -> {
                p.setChannel("sse");
                p.setUrl("http://127.0.0.1:1/mcp");
            }));
            this.tools = tools;
        }

        @Override
        public Collection<FunctionTool> getToolsActivated() {
            return tools;
        }

        @Override
        public void close() {
        }
    }

    static McpGatewayTalent mcpGateway(int count) {
        McpGatewayTalent talent = new McpGatewayTalent().dynamicThreshold(1).listThreshold(2).searchThreshold(3).retryConfig(1, 100);
        List<FunctionTool> alpha = new ArrayList<>();
        List<FunctionTool> beta = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            (i % 2 == 0 ? beta : alpha).add(tool("mcp_" + i, "MCP 工具 " + i));
        }
        talent.addMcpServer("alpha", new FakeProvider(alpha));
        if (beta.isEmpty() == false) {
            talent.addMcpServer("beta", new FakeProvider(beta));
        }
        return talent;
    }

    // ---------------- openapi gateway ----------------

    static ApiTool api(String name, String method, String path, String... tags) {
        ApiTool t = new ApiTool();
        t.setName(name);
        t.setDescription("接口 " + name);
        t.setMethod(method);
        t.setPath(path);
        t.setTags(new ArrayList<>(Arrays.asList(tags)));
        return t;
    }

    static List<ApiTool> richApis() {
        ApiTool full = api("getOrder", "GET", "/api/orders/{id}", "订单", "查询");
        full.setHeaderSchema("{\"type\":\"object\",\"properties\":{\"token\":{\"type\":\"string\"}}}");
        full.setPathSchema("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},\"required\":[\"id\"]}");
        full.setQuerySchema("{\"type\":\"object\",\"properties\":{\"lang\":{\"type\":\"string\"}}}");
        full.setOutputSchema("{\"type\":\"object\"}");

        ApiTool upload = api("upload", "POST", "/api/files", "文件");
        upload.setMultipart(true);
        upload.setBodySchema("{\"type\":\"object\",\"properties\":{\"file\":{\"type\":\"string\"}}}");

        ApiTool json = api("createOrder", "POST", "/api/orders");
        json.setBodySchema("{\"type\":\"object\",\"properties\":{\"sku\":{\"type\":\"string\"}}}");

        return Arrays.asList(full, upload, json);
    }

    static OpenApiGatewayTalent openApiGateway(List<ApiTool> tools) throws Exception {
        ensureServer();
        OpenApiGatewayTalent talent = new OpenApiGatewayTalent()
                .dynamicThreshold(3).listThreshold(4).searchThreshold(5)
                .maxContextLength(10).retryConfig(1)
                .resolver(new ApiResolver() {
                    @Override
                    public String getName() {
                        return "fake";
                    }

                    @Override
                    public List<ApiTool> resolve(String definitionUrl, String source) {
                        return tools;
                    }
                });

        ApiSource source = new ApiSource();
        source.setDocUrl(base + "/doc");
        source.setApiBaseUrl(base);
        source.setTimeout(Duration.ofMillis(300));
        talent.addApi(source);
        return talent;
    }

    static List<ApiTool> plainApis(int count) {
        List<ApiTool> list = new ArrayList<>(richApis());
        for (int i = list.size() + 1; i <= count; i++) {
            list.add(api("api" + i, "GET", "/api/ok", i % 2 == 0 ? "订单" : "文件"));
        }
        return list.subList(0, count);
    }

    /**
     * 中转工具的返回值统一转为文本（列表结果按 toString）
     */
    static String text(Object result) {
        if (result instanceof ToolResult) {
            return ((ToolResult) result).getContent();
        }
        return String.valueOf(result);
    }

    // ---------------- 调用（tag 为工具上下文注入的 __locale） ----------------

    static String toolSearch(ToolGatewayTalent t, String tag, String keyword) {
        return text(t.searchTools(keyword, tag));
    }

    static String toolDetail(ToolGatewayTalent t, String tag, String name) {
        return t.getToolDetail(name, tag);
    }

    static String toolCall(ToolGatewayTalent t, String tag, String name) throws Throwable {
        return text(callTool(t, tag, name));
    }

    static String mcpSearch(McpGatewayTalent t, String tag, String keyword) {
        return text(t.searchTools(keyword, null, tag));
    }

    static String mcpDetail(McpGatewayTalent t, String tag, String name) {
        return t.getToolDetail(name, null, tag);
    }

    static String mcpCall(McpGatewayTalent t, String tag, String name) throws Throwable {
        return text(callTool(t, tag, name));
    }

    static String apiSearch(OpenApiGatewayTalent t, String tag, String keyword) {
        return text(t.searchApis(keyword, tag));
    }

    static String apiDetail(OpenApiGatewayTalent t, String tag, String name) {
        return t.getApiDetail(name, tag);
    }

    static String apiCall(OpenApiGatewayTalent t, String tag, String name, Map<String, Object> path) throws Exception {
        return t.callApi(name, null, path, null, null, tag);
    }

    static Map<String, String> renderOf(String tag, String suffix) throws Throwable {
        Map<String, String> out = new LinkedHashMap<>();
        Prompt prompt = prompt(tag);

        // ---- tool gateway ----
        if (tag == null) {
            out.put("gw_tool_description" + suffix, toolGateway(1).description(null));
        }
        for (int n = 1; n <= 4; n++) {
            ToolGatewayTalent t = toolGateway(n);
            out.put("gw_tool_instruction_" + n + suffix, t.getInstruction(prompt));
            out.put("gw_tool_tools_" + n, GoldenFiles.dumpTools(t.getTools(prompt)));
        }
        ToolGatewayTalent tg = toolGateway(4);
        tg.addTool(tool("boom_1", "会失败的工具"));
        out.put("gw_tool_search_blank" + suffix, toolSearch(tg, tag, ""));
        out.put("gw_tool_search_none" + suffix, toolSearch(tg, tag, "不存在"));
        out.put("gw_tool_detail_blank" + suffix, toolDetail(tg, tag, ""));
        out.put("gw_tool_detail_found" + suffix, toolDetail(tg, tag, "tool_1"));
        out.put("gw_tool_detail_missing" + suffix, toolDetail(tg, tag, "nope"));
        out.put("gw_tool_call_blank" + suffix, toolCall(tg, tag, ""));
        out.put("gw_tool_call_missing" + suffix, toolCall(tg, tag, "nope"));
        out.put("gw_tool_call_error" + suffix, toolCall(tg, tag, "boom_1"));

        // ---- mcp gateway ----
        if (tag == null) {
            out.put("gw_mcp_description" + suffix, mcpGateway(3).description(null));
        }
        for (int n = 1; n <= 4; n++) {
            McpGatewayTalent t = mcpGateway(n);
            out.put("gw_mcp_instruction_" + n + suffix, t.getInstruction(prompt));
            out.put("gw_mcp_tools_" + n, GoldenFiles.dumpTools(t.getTools(prompt)));
        }
        McpGatewayTalent mg = mcpGateway(4);
        mg.addMcpServer("gamma", new FakeProvider(Collections.singletonList(tool("boom_2", "会失败的工具"))));
        out.put("gw_mcp_search_blank" + suffix, mcpSearch(mg, tag, ""));
        out.put("gw_mcp_search_none" + suffix, mcpSearch(mg, tag, "不存在"));
        out.put("gw_mcp_detail_blank" + suffix, mcpDetail(mg, tag, ""));
        out.put("gw_mcp_detail_found" + suffix, mcpDetail(mg, tag, "mcp_1"));
        out.put("gw_mcp_detail_missing" + suffix, mcpDetail(mg, tag, "nope"));
        out.put("gw_mcp_call_blank" + suffix, mcpCall(mg, tag, ""));
        out.put("gw_mcp_call_missing" + suffix, mcpCall(mg, tag, "nope"));
        out.put("gw_mcp_call_error" + suffix, mcpCall(mg, tag, "boom_2"));

        // ---- openapi gateway ----
        if (tag == null) {
            out.put("gw_api_description" + suffix, openApiGateway(plainApis(3)).description(null));
        }
        for (int n = 3; n <= 6; n++) {
            OpenApiGatewayTalent t = openApiGateway(plainApis(n));
            out.put("gw_api_instruction_" + n + suffix, t.getInstruction(prompt));
            out.put("gw_api_tools_" + n, GoldenFiles.dumpTools(t.getTools(prompt)));
        }
        List<ApiTool> apis = new ArrayList<>(richApis());
        apis.add(api("ok", "GET", "/api/ok"));
        apis.add(api("big", "GET", "/api/big"));
        apis.add(api("slow", "GET", "/api/slow"));
        OpenApiGatewayTalent ag = openApiGateway(apis);
        out.put("gw_api_search_blank" + suffix, apiSearch(ag, tag, ""));
        out.put("gw_api_search_none" + suffix, apiSearch(ag, tag, "不存在"));
        out.put("gw_api_detail_blank" + suffix, apiDetail(ag, tag, ""));
        out.put("gw_api_detail_found" + suffix, apiDetail(ag, tag, "getOrder"));
        out.put("gw_api_detail_missing" + suffix, apiDetail(ag, tag, "nope"));
        out.put("gw_api_call_missing" + suffix, apiCall(ag, tag, "nope", null));
        out.put("gw_api_call_no_path" + suffix, apiCall(ag, tag, "getOrder", null));
        out.put("gw_api_call_ok" + suffix, apiCall(ag, tag, "ok", null));
        out.put("gw_api_call_truncated" + suffix, apiCall(ag, tag, "big", null));
        //异常没有信息时（执行阶段抛出无信息的异常）
        ApiTool silent = new ApiTool() {
            @Override
            public String getMethod() {
                throw new IllegalStateException();
            }
        };
        silent.setName("silent");
        silent.setPath("/api/ok");
        out.put("gw_api_call_no_response" + suffix, apiCall(openApiGateway(Collections.singletonList(silent)), tag, "silent", null));
        String slow = apiCall(ag, tag, "slow", null);
        out.put("gw_api_call_error" + suffix, slow.replaceAll("(?<=: ).*?(?= \\()", "<error>"));

        return out;
    }
}
