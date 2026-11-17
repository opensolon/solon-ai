package features.ai.talent.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
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

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 三个网关的一致性与健壮性：中转工具不重名、调用失败标为报错、只在未送达时重试、参数宽松读取、
 * 运行中增删不出并发异常、刷新失败保留原接口。
 */
public class GatewayConsistencyTest {

    // ---------------- 辅助 ----------------

    static FunctionTool echoTool(String name, AtomicReference<Map<String, Object>> received) {
        return new FunctionToolDesc(name)
                .description("回显 " + name)
                .inputSchema("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}")
                .doHandle(args -> {
                    received.set(new HashMap<>(args));
                    return ToolResult.success("ok:" + name);
                });
    }

    static FunctionTool failingTool(String name, AtomicInteger calls, Throwable error) {
        return new FunctionToolDesc(name)
                .description("会失败的 " + name)
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .doHandle(args -> {
                    calls.incrementAndGet();
                    throw error;
                });
    }

    static class FakeProvider extends McpClientProvider {
        private final List<FunctionTool> tools;
        // 设置后，拉取工具（模拟握手）会先打开 entered，再等 gate 放行
        volatile CountDownLatch entered;
        volatile CountDownLatch gate;
        final AtomicBoolean closed = new AtomicBoolean();

        FakeProvider(List<FunctionTool> tools) {
            super(new McpClientProperties().then(p -> {
                p.setChannel("sse");
                p.setUrl("http://127.0.0.1:1/mcp");
            }));
            this.tools = tools;
        }

        @Override
        public Collection<FunctionTool> getToolsActivated() {
            CountDownLatch g = gate;
            if (g != null) {
                entered.countDown();
                try {
                    g.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return tools;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    static Set<String> relayNames(AbsTalent talent) {
        return new TreeSet<>(talent.getToolMap().keySet());
    }

    // ---------------- 中转工具不重名 ----------------

    /** 三个网关可以挂在同一个 Agent 上：中转工具名两两不重叠（框架按工具名合并，同名会互相覆盖） */
    @Test
    void relayToolNamesDoNotClash() {
        Set<String> tool = relayNames(new ToolGatewayTalent());
        Set<String> mcp = relayNames(new McpGatewayTalent());
        Set<String> api = relayNames(new OpenApiGatewayTalent());

        assertEquals(new TreeSet<>(Arrays.asList("call_biz_tool", "get_biz_tool_detail", "search_biz_tools")), tool);
        assertEquals(new TreeSet<>(Arrays.asList("call_tool", "get_tool_detail", "list_server_tools", "search_tools")), mcp);
        assertEquals(new TreeSet<>(Arrays.asList("call_api", "get_api_detail", "search_apis")), api);

        Set<String> all = new HashSet<>();
        for (Set<String> names : Arrays.asList(tool, mcp, api)) {
            for (String name : names) {
                assertTrue(all.add(name), "中转工具重名: " + name);
            }
        }
    }

    // ---------------- Tool 网关 call_biz_tool ----------------

    private static ToolResult callBiz(ToolGatewayTalent talent, Map<String, Object> args) throws Throwable {
        return talent.getToolMap().get("call_biz_tool").call(args);
    }

    @Test
    void toolGatewayCallHandlesNullAndJsonStringArgs() throws Throwable {
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        ToolGatewayTalent talent = new ToolGatewayTalent().addTool(echoTool("echo", received));

        // tool_args 缺省：不再空指针
        Map<String, Object> args = new HashMap<>();
        args.put("tool_name", "echo");
        ToolResult result = callBiz(talent, args);
        assertFalse(result.isError(), result.getContent());
        assertEquals("ok:echo", result.getContent());

        // tool_args 是 JSON 字符串：解析一次再转发
        args.put("tool_args", "{\"q\":\"hi\"}");
        result = callBiz(talent, args);
        assertFalse(result.isError(), result.getContent());
        assertEquals("hi", received.get().get("q"));

        // 不是 JSON 对象：报错并附参数定义
        args.put("tool_args", "[1]");
        result = callBiz(talent, args);
        assertTrue(result.isError());
        assertTrue(result.getContent().contains("\"q\""), result.getContent());
    }

    @Test
    void toolGatewayCallDoesNotMutateModelArgsAndContextWins() throws Throwable {
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        ToolGatewayTalent talent = new ToolGatewayTalent().addTool(echoTool("echo", received));

        Map<String, Object> toolArgs = new HashMap<>();
        toolArgs.put("q", "hi");
        toolArgs.put("__cwd", "/forged");

        Map<String, Object> args = new HashMap<>();
        args.put("tool_name", "echo");
        args.put("tool_args", toolArgs);
        args.put("__cwd", "/work");
        args.put("__sessionId", "s1");

        assertFalse(callBiz(talent, args).isError());

        // 原始参数不被改动
        assertEquals(2, toolArgs.size());
        assertEquals("/forged", toolArgs.get("__cwd"));
        // 本地工具拿得到上下文，同名键以上下文为准
        assertEquals("/work", received.get().get("__cwd"));
        assertEquals("s1", received.get().get("__sessionId"));
        assertEquals("hi", received.get().get("q"));
        assertFalse(received.get().containsKey("tool_name"));
        assertFalse(received.get().containsKey("tool_args"));
    }

    @Test
    void toolGatewayCallFailuresAreErrors() throws Throwable {
        AtomicInteger calls = new AtomicInteger();
        ToolGatewayTalent talent = new ToolGatewayTalent().retryConfig(3, 100)
                .addTool(failingTool("boom", calls, new IllegalStateException("boom!")));

        assertTrue(talent.callTool("", null).isError(), "名字为空");
        assertTrue(talent.callTool("nope", null).isError(), "未找到");

        ToolResult failed = talent.callTool("boom", null);
        assertTrue(failed.isError(), "执行异常");
        assertTrue(failed.getContent().contains("boom!"));
        // 业务异常不重试（重试结果不变，对非幂等工具还会重复执行）
        assertEquals(1, calls.get());
    }

    @Test
    void toolGatewayRetriesOnlyWhenNotDelivered() {
        AtomicInteger refused = new AtomicInteger();
        AtomicInteger timeout = new AtomicInteger();
        ToolGatewayTalent talent = new ToolGatewayTalent().retryConfig(3, 100)
                .addTool(failingTool("refused", refused, new RuntimeException(new ConnectException("Connection refused"))))
                .addTool(failingTool("timeout", timeout, new RuntimeException(new SocketTimeoutException("Read timed out"))));

        assertTrue(talent.callTool("refused", null).isError());
        assertEquals(3, refused.get(), "连接被拒（未送达）才重试");

        assertTrue(talent.callTool("timeout", null).isError());
        assertEquals(1, timeout.get(), "读超时可能已执行，不重试");
    }

    /** 缺省不重试：本地工具可能多步，异常链里的「连接被拒」不说明前面的步骤没执行 */
    @Test
    void toolGatewayDoesNotRetryByDefault() {
        AtomicInteger refused = new AtomicInteger();
        ToolGatewayTalent talent = new ToolGatewayTalent()
                .addTool(failingTool("refused", refused, new RuntimeException(new ConnectException("Connection refused"))));

        assertTrue(talent.callTool("refused", null).isError());
        assertEquals(1, refused.get());
    }

    /** 4.1.1 误公开的中转工具类保留为废弃壳，行为委托给网关 */
    @Test
    @SuppressWarnings("deprecation")
    void deprecatedRelayClassesDelegateToGateway() throws Throwable {
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();

        ToolGatewayTalent tool = new ToolGatewayTalent().addTool(echoTool("echo", received));
        FunctionTool toolShell = new ToolGatewayTalent.ToolCallTool(tool);
        assertEquals("call_biz_tool", toolShell.name());
        assertEquals(tool.getToolMap().get("call_biz_tool").inputSchema(), toolShell.inputSchema());
        Map<String, Object> args = new HashMap<>();
        args.put("tool_name", "echo");
        assertEquals("ok:echo", toolShell.call(args).getContent());

        McpGatewayTalent mcp = new McpGatewayTalent();
        mcp.addMcpServer("alpha", new FakeProvider(Collections.singletonList(echoTool("a1", received))));
        FunctionTool mcpShell = new McpGatewayTalent.McpToolCallTool(mcp);
        assertEquals("call_tool", mcpShell.name());
        assertEquals(mcp.getToolMap().get("call_tool").inputSchema(), mcpShell.inputSchema());
        args.put("tool_name", "a1");
        assertEquals("ok:a1", mcpShell.call(args).getContent());
        args.put("tool_name", "nope");
        assertTrue(mcpShell.call(args).isError());
    }

    @Test
    void toolGatewayAddAndRemove() {
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        ToolGatewayTalent talent = new ToolGatewayTalent().dynamicThreshold(0).listThreshold(10);
        talent.addTool("A", echoTool("x", received));
        talent.addTool("B", echoTool("X", received));

        // 同名（大小写不敏感）替换，且从原分组移走，不在两个分组里各列一次
        String instruction = talent.getInstruction(null);
        assertFalse(instruction.contains("[A]"), instruction);
        assertTrue(instruction.contains("[B]"), instruction);
        assertTrue(instruction.contains("共 1 个"), instruction);

        talent.removeTool("x");
        assertFalse(talent.isSupported(null));
        talent.removeTool("x");
        talent.removeTool(null);
    }

    /** 运行中添加工具与渲染指令并发进行：写时复制，不抛 ConcurrentModificationException */
    @Test
    void toolGatewayConcurrentAddWhileRendering() throws Exception {
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        ToolGatewayTalent talent = new ToolGatewayTalent().dynamicThreshold(0).listThreshold(100000).searchThreshold(100000);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            started.countDown();
            try {
                while (stop.get() == false) {
                    talent.getInstruction(null);
                    talent.getTools(null);
                    talent.searchTools("t", null);
                }
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        reader.start();
        started.await();

        for (int i = 0; i < 2000; i++) {
            talent.addTool(i % 3 == 0 ? null : "g" + (i % 7), echoTool("t" + i, received));
        }
        stop.set(true);
        reader.join(10000);

        assertNull(failure.get(), String.valueOf(failure.get()));
        assertTrue(talent.getInstruction(null).contains("2000"));
    }

    // ---------------- MCP 网关：边界（List<String>，与 _skills_allowed 同口径） ----------------

    @Test
    void mcpAllowedIsPlainList() {
        Map<String, Object> ctx = new HashMap<>();
        assertNull(McpGatewayTalent.getAllowed(ctx), "未设置 = 不限制");
        assertNull(McpGatewayTalent.getAllowed(null));

        List<String> names = Arrays.asList("alpha", "beta");
        ctx.put(McpGatewayTalent.CTX_MCPS_ALLOWED, names);
        assertSame(names, McpGatewayTalent.getAllowed(ctx));

        ctx.put(McpGatewayTalent.CTX_MCPS_ALLOWED, new ArrayList<>());
        assertEquals(Collections.emptyList(), McpGatewayTalent.getAllowed(ctx), "空名单 = 全部禁止");
    }

    @Test
    void mcpBoundaryGuardsCallToolAndPrompt() throws Throwable {
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        McpGatewayTalent talent = new McpGatewayTalent();
        talent.addMcpServer("alpha", new FakeProvider(Collections.singletonList(echoTool("a1", received))));
        talent.addMcpServer("beta", new FakeProvider(Collections.singletonList(echoTool("b1", received))));

        Map<String, Object> args = new HashMap<>();
        args.put("tool_name", "b1");
        args.put(McpGatewayTalent.CTX_MCPS_ALLOWED, Collections.singletonList("alpha"));
        ToolResult result = talent.getToolMap().get("call_tool").call(args);
        assertTrue(result.isError(), "越界按不存在处理: " + result.getContent());

        args.put("tool_name", "a1");
        assertFalse(talent.getToolMap().get("call_tool").call(args).isError());

        // Prompt 侧写入边界：指令与描述只算边界内的服务
        Prompt prompt = Prompt.of("hi");
        McpGatewayTalent.putAllowed(prompt, Collections.singletonList("alpha"));
        assertTrue(talent.getInstruction(prompt).contains("共 1 个"), talent.getInstruction(prompt));
        McpGatewayTalent.putAllowed(prompt, Collections.emptyList());
        assertFalse(talent.isSupported(prompt), "空边界 = 全部禁止");
    }

    // ---------------- MCP 网关：同名替换先握手再换 ----------------

    /** 同名替换：新连接握手期间旧工具仍可用；换上后关闭旧连接 */
    @Test
    void mcpReplaceKeepsOldToolsDuringHandshake() throws Exception {
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        McpGatewayTalent talent = new McpGatewayTalent();
        FakeProvider oldProvider = new FakeProvider(Collections.singletonList(echoTool("v1", received)));
        talent.addMcpServer("alpha", oldProvider);

        FakeProvider newProvider = new FakeProvider(Collections.singletonList(echoTool("v2", received)));
        newProvider.entered = new CountDownLatch(1);
        newProvider.gate = new CountDownLatch(1);
        Thread adder = new Thread(() -> talent.addMcpServer("alpha", newProvider));
        adder.start();
        assertTrue(newProvider.entered.await(5, TimeUnit.SECONDS));

        // 握手中：旧服务仍在、连接未关
        assertFalse(talent.callTool("alpha", "v1", null).isError());
        assertFalse(oldProvider.closed.get());

        newProvider.gate.countDown();
        adder.join(5000);

        assertTrue(oldProvider.closed.get(), "换上后关闭旧连接");
        assertTrue(talent.callTool("alpha", "v1", null).isError());
        assertFalse(talent.callTool("alpha", "v2", null).isError());
        assertSame(newProvider, talent.getMcpServer("alpha"));

        // 同一个连接重复添加不会把它关掉
        talent.addMcpServer("alpha", newProvider);
        assertFalse(newProvider.closed.get());
        assertFalse(talent.callTool("alpha", "v2", null).isError());
    }

    /** 旧连接的 refresh 还在拉取时服务被替换：拉完不把旧工具写回去 */
    @Test
    void mcpStaleRefreshDoesNotOverwriteReplacement() throws Exception {
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        McpGatewayTalent talent = new McpGatewayTalent();
        FakeProvider oldProvider = new FakeProvider(Collections.singletonList(echoTool("v1", received)));
        talent.addMcpServer("alpha", oldProvider);

        oldProvider.entered = new CountDownLatch(1);
        oldProvider.gate = new CountDownLatch(1);
        Thread refresher = new Thread(() -> talent.refreshMcpServer("alpha"));
        refresher.start();
        assertTrue(oldProvider.entered.await(5, TimeUnit.SECONDS));

        talent.addMcpServer("alpha", new FakeProvider(Collections.singletonList(echoTool("v2", received))));
        oldProvider.gate.countDown();
        refresher.join(5000);

        assertTrue(talent.callTool("alpha", "v1", null).isError(), "旧工具不应复活");
        assertFalse(talent.callTool("alpha", "v2", null).isError());
    }

    // ---------------- OpenAPI 网关 ----------------

    static HttpServer server(AtomicInteger hits) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/doc", ex -> reply(ex, "{}"));
        server.createContext("/api/ok", ex -> reply(ex, "{\"ok\":true}"));
        server.createContext("/api/slow", ex -> {
            hits.incrementAndGet();
            try {
                Thread.sleep(800);
            } catch (InterruptedException ignored) {
            }
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        return server;
    }

    static void reply(HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    static ApiTool api(String name, String path) {
        ApiTool t = new ApiTool();
        t.setName(name);
        t.setDescription("接口 " + name);
        t.setMethod("GET");
        t.setPath(path);
        return t;
    }

    /** 可切换结果的解析器（模拟文档变化或拉取失败） */
    static class SwitchResolver implements ApiResolver {
        volatile List<ApiTool> tools;
        volatile boolean fail;

        @Override
        public String getName() {
            return "switch";
        }

        @Override
        public List<ApiTool> resolve(String definitionUrl, String source) {
            if (fail) {
                throw new IllegalStateException("bad doc");
            }
            return tools;
        }
    }

    static OpenApiGatewayTalent openApi(String base, SwitchResolver resolver) {
        OpenApiGatewayTalent talent = new OpenApiGatewayTalent().retryConfig(3, 100).resolver(resolver);
        ApiSource source = new ApiSource();
        source.setDocUrl(base + "/doc");
        source.setApiBaseUrl(base);
        source.setTimeout(Duration.ofMillis(300));
        return talent.addApi(source);
    }

    @Test
    void openApiCallFailuresAreErrorsAndReadTimeoutIsNotRetried() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = server(hits);
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            SwitchResolver resolver = new SwitchResolver();
            resolver.tools = Arrays.asList(api("ok", "/api/ok"), api("slow", "/api/slow"));
            OpenApiGatewayTalent talent = openApi(base, resolver);

            assertTrue(talent.callApi(null, null, null, null, null, null).isError(), "名字为空不再空指针");
            assertTrue(talent.callApi("nope", null, null, null, null, null).isError(), "未找到");

            ToolResult ok = talent.callApi("ok", null, null, null, null, null);
            assertFalse(ok.isError());
            assertEquals("{\"ok\":true}", ok.getContent());

            ToolResult slow = talent.callApi("slow", null, null, null, null, null);
            assertTrue(slow.isError(), slow.getContent());
            Thread.sleep(1000);
            assertEquals(1, hits.get(), "读超时时请求可能已执行，不重试");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void openApiRefreshKeepsToolsWhenReloadFails() throws Exception {
        HttpServer server = server(new AtomicInteger());
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            SwitchResolver resolver = new SwitchResolver();
            resolver.tools = Collections.singletonList(api("ok", "/api/ok"));
            OpenApiGatewayTalent talent = openApi(base, resolver);
            String docUrl = base + "/doc";

            resolver.fail = true;
            talent.refreshApi(docUrl, true);
            assertTrue(talent.isSupported(null), "拉取失败保留原接口");
            assertFalse(talent.callApi("ok", null, null, null, null, null).isError());

            resolver.fail = false;
            resolver.tools = Collections.singletonList(api("ok2", "/api/ok"));
            talent.refreshApi(docUrl, true);
            assertTrue(talent.callApi("ok", null, null, null, null, null).isError(), "旧接口已被新文档替换");
            assertFalse(talent.callApi("ok2", null, null, null, null, null).isError());

            talent.removeApi(docUrl);
            assertFalse(talent.isSupported(null));
            assertFalse(talent.hasApiSource(docUrl));
        } finally {
            server.stop(0);
        }
    }
}
