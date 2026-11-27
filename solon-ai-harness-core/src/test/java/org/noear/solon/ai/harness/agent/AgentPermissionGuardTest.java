package org.noear.solon.ai.harness.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import features.ai.harness.i18n.HarnessFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.permission.PermissionContext;
import org.noear.solon.ai.harness.permission.PermissionRule;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 代理权限守卫：子代理按代理定义里的 permissionRules 在执行前判定（deny / ask 都拒绝，没命中放行）；
 * 内置 explore / plan 的规则只放行只读命令；真实运行中被拒的命令不会执行。
 */
public class AgentPermissionGuardTest {
    @TempDir
    Path ws;

    private HttpServer server;
    /** 每次模型请求里 role=tool 的消息内容 */
    private final List<String> toolMessages = new CopyOnWriteArrayList<>();
    /** 依次返回的 bash 命令；用完后回答 done */
    private final Deque<String> scriptedCommands = new ConcurrentLinkedDeque<>();

    @BeforeEach
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    public void stop() {
        server.stop(0);
    }

    // ---------- 判定 ----------

    private static Map<String, Object> cmd(String command) {
        Map<String, Object> args = new HashMap<>();
        args.put("command", command);
        return args;
    }

    private static Map<String, Object> file(String path) {
        Map<String, Object> args = new HashMap<>();
        args.put("file_path", path);
        return args;
    }

    /** 内置的只读子代理：两份定义各写一遍同一白名单 */
    private static final String[] READ_ONLY_AGENTS = {"explore", "plan"};

    private static AgentPermissionGuard builtinGuard(String agentName) {
        HarnessEngine engine = HarnessEngine.of(".", ".").sessionProvider((id, parentId, c) -> InMemoryAgentSession.of(id)).build();
        AgentDefinition def = engine.getAgentManager().getAgent(agentName);
        assertTrue(def.getMetadata().hasPermissionRules(), agentName + " 应声明 permissionRules");
        return new AgentPermissionGuard(PermissionContext.create().addRules(def.getMetadata().getPermissionRules()));
    }

    @Test
    void readOnlyAgentsAllowReadOnlyGitAndPipes() {
        for (String agent : READ_ONLY_AGENTS) {
            AgentPermissionGuard g = builtinGuard(agent);
            for (String ok : new String[]{
                    "git log", "git log -5 --oneline", "git show HEAD:pom.xml", "git diff HEAD~1 -- src",
                    "git blame src/A.java | head -20", "git status", "git ls-files | wc -l",
                    "git rev-parse HEAD", "git shortlog -sn", "grep -rn foo src | wc -l",
                    "git log 2>/dev/null | tail -5", "git log --format='%h %s'"}) {
                assertNull(g.denyReason("bash", cmd(ok), Locale.ROOT), agent + ": " + ok);
            }
            assertNull(g.denyReason("bash_start", cmd("git log -5"), Locale.ROOT), agent + ": bash_start 按 bash 的规则");
        }
    }

    @Test
    void readOnlyAgentsDenyWritesAndUnverifiable() {
        for (String agent : READ_ONLY_AGENTS) {
            AgentPermissionGuard g = builtinGuard(agent);
            for (String bad : new String[]{
                    "touch x", "rm -rf x", "ls -la", "git checkout main", "git commit -m x", "git branch tmp",
                    "mvn test", "git stash",
                    "git log; rm -rf x", "git log && touch x", "git log | sh", "git log > out.txt",
                    "git log --output=out.txt", "git diff --output out.txt", "git show -p HEAD --output=src/App.java", "git log $(rm x)", "git log `rm x`",
                    "git log && (rm x)", "git log $OPTS", "FOO=1 git log", "git -c core.pager=sh log"}) {
                assertNotNull(g.denyReason("bash", cmd(bad), Locale.ROOT), agent + ": " + bad);
            }
            assertNotNull(g.denyReason("bash_start", cmd("touch x"), Locale.ROOT), agent);
        }
    }

    @Test
    void readOnlyAgentsLeaveNonShellToolsUnrestricted() {
        for (String agent : READ_ONLY_AGENTS) {
            AgentPermissionGuard g = builtinGuard(agent);
            assertNull(g.denyReason("read", file("src/App.java"), Locale.ROOT), agent);
            assertNull(g.denyReason("read", file("conf/.env"), Locale.ROOT), agent + " 不限制读 .env（与主代理一致）");
            assertNull(g.denyReason("grep", Collections.<String, Object>singletonMap("pattern", "x"), Locale.ROOT), agent + " 没写规则的工具放行");
        }
    }

    @Test
    void denyAndAskBothRejectWithDistinctMessages() {
        AgentPermissionGuard g = new AgentPermissionGuard(PermissionContext.create()
                .addRule(PermissionRule.deny("bash", "rm *"))
                .addRule(PermissionRule.ask("bash", "mvn *")));

        String deny = g.denyReason("bash", cmd("ls && rm -rf x"), Locale.CHINESE);
        String ask = g.denyReason("bash", cmd("mvn test"), Locale.CHINESE);
        assertNotNull(deny);
        assertNotNull(ask);
        assertTrue(deny.contains("禁止") && deny.contains("ls && rm -rf x"), deny);
        assertTrue(ask.contains("确认") && ask.contains("mvn test"), ask);
        assertNull(g.denyReason("bash", cmd("ls -la"), Locale.CHINESE), "没命中放行（只写拒绝清单时）");
        assertNotNull(g.denyReason("bash", cmd("(rm -rf x)"), Locale.CHINESE), "子 shell 里的拒绝项无法核实，不放行");
    }

    @Test
    void bashWaitInputIsRejectedOnlyWhenShellIsRuled() {
        Map<String, Object> input = new HashMap<>();
        input.put("session_id", "s");
        input.put("chars", "!rm -rf x\n");
        Map<String, Object> waitOnly = new HashMap<>();
        waitOnly.put("session_id", "s");

        AgentPermissionGuard ruled = new AgentPermissionGuard(PermissionContext.create().addRule(PermissionRule.deny("bash", "rm *")));
        assertNotNull(ruled.denyReason("bash_wait", input, Locale.ROOT));
        assertNull(ruled.denyReason("bash_wait", waitOnly, Locale.ROOT));
        assertNull(ruled.denyReason("bash_stop", waitOnly, Locale.ROOT));

        AgentPermissionGuard readOnly = new AgentPermissionGuard(PermissionContext.create().addRule(PermissionRule.ask("read", "*.env")));
        assertNull(readOnly.denyReason("bash_wait", input, Locale.ROOT), "没有 shell 规则时不干预");
    }

    // ---------- 装配 ----------

    private static boolean hasGuard(ReActAgent agent) {
        return agent.getConfig().getDefaultOptions().getInterceptors().stream()
                .anyMatch(r -> r.target instanceof AgentPermissionGuard && r.index == AgentPermissionGuard.INDEX);
    }

    @Test
    void guardIsInstalledOnSubagentsWithRulesOnly() {
        HarnessEngine engine = engine();
        AgentManager agents = engine.getAgentManager();

        assertTrue(hasGuard(AgentFactory.create(engine, agents.getAgent("explore")).build()), "explore 有规则");
        assertTrue(hasGuard(AgentFactory.create(engine, agents.getAgent("plan")).build()), "plan 有规则");
        assertFalse(hasGuard(AgentFactory.create(engine, agents.getAgent("general")).build()), "没写规则的子代理不挂");
        assertFalse(hasGuard(engine.getMainAgent()), "主代理的代理规则走 HITL（ask 弹卡），不挂守卫");

        AgentDefinition mainWithRules = AgentDefinition.builder().name(AgentDefinition.AGENT_MAIN).toolsAdd("bash").build();
        mainWithRules.getMetadata().addPermissionRule(PermissionRule.deny("bash"));
        assertFalse(hasGuard(AgentFactory.create(engine, mainWithRules).build()));
        assertTrue(AgentPermissionGuard.INDEX > org.noear.solon.ai.harness.scope.ScopeGuardInterceptor.INDEX && AgentPermissionGuard.INDEX < 0,
                "作用域守卫之后、HITL（缺省 0）之前");
    }

    // ---------- 真实运行 ----------

    @Test
    void exploreSubagentDoesNotRunDeniedCommands() throws Throwable {
        Files.write(ws.resolve("seed.txt"), "SEED-LINE-42\n".getBytes(StandardCharsets.UTF_8));
        HarnessEngine engine = engine();
        HarnessFixtures.bindTrace(engine.getSession("s1"), HarnessFixtures.trace(null, false));

        scriptedCommands.add("head -n 1 seed.txt");
        scriptedCommands.add("touch made1.txt");
        scriptedCommands.add("head -n 1 seed.txt; touch made2.txt");
        scriptedCommands.add("head -n 1 seed.txt > made3.txt");
        scriptedCommands.add("tail -n 1 seed.txt && (touch made4.txt)");

        TaskTalent.SingleTaskOp op = new TaskTalent.SingleTaskOp();
        op.agent_name = "explore";
        op.description = "d";
        op.prompt = "look";
        ToolResult r = engine.getTaskTalent().task(op, ws.toString(), "s1", null);
        assertFalse(r.isError(), r.getContent());

        for (int i = 1; i <= 4; i++) {
            assertFalse(Files.exists(ws.resolve("made" + i + ".txt")), "被拒的命令不得执行: made" + i);
        }

        assertEquals(5, toolMessages.size(), toolMessages.toString());
        assertTrue(toolMessages.get(0).contains("SEED-LINE-42"), "放行的命令真正执行: " + toolMessages.get(0));
        for (int i = 1; i < 5; i++) {
            String m = toolMessages.get(i);
            assertTrue(m.contains("made" + i + ".txt") && (m.contains("代理") || m.contains("agent")), "返回守卫说明: " + m);
        }
    }

    @Test
    void planSubagentRunsReadOnlyGitButNotWrites() throws Throwable {
        Files.write(ws.resolve("seed.txt"), "SEED-LINE-42\n".getBytes(StandardCharsets.UTF_8));
        HarnessEngine engine = engine();
        HarnessFixtures.bindTrace(engine.getSession("s1"), HarnessFixtures.trace(null, false));

        scriptedCommands.add("tail -n 1 seed.txt");
        scriptedCommands.add("tail -n 1 seed.txt && touch made1.txt");

        TaskTalent.SingleTaskOp op = new TaskTalent.SingleTaskOp();
        op.agent_name = "plan";
        op.description = "d";
        op.prompt = "plan";
        ToolResult r = engine.getTaskTalent().task(op, ws.toString(), "s1", null);
        assertFalse(r.isError(), r.getContent());

        assertFalse(Files.exists(ws.resolve("made1.txt")), "被拒的命令不得执行");
        assertEquals(2, toolMessages.size(), toolMessages.toString());
        assertTrue(toolMessages.get(0).contains("SEED-LINE-42"), "放行的命令真正执行: " + toolMessages.get(0));
        assertTrue(toolMessages.get(1).contains("made1.txt"), "返回守卫说明: " + toolMessages.get(1));
    }

    // ---------- 假模型 ----------

    private HarnessEngine engine() {
        ChatConfig model = new ChatConfig();
        model.setName("srv");
        model.setModel("m");
        model.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        model.setApiKey("test");

        Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
        return HarnessEngine.of(ws.toString(), ".soloncode/")
                .modelAdd(model)
                .defaultModel("srv")
                .autoRethink(false)
                .sessionProvider((id, parentId, create) -> create
                        ? sessions.computeIfAbsent(id, InMemoryAgentSession::of)
                        : sessions.get(id))
                .build();
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = readBody(ex.getRequestBody());
        ONode req = ONode.ofJson(body);
        List<String> tools = new ArrayList<>();
        for (ONode m : req.get("messages").getArray()) {
            if ("tool".equals(m.get("role").getString())) {
                tools.add(m.get("content").getString());
            }
        }
        // 每次请求带上全部历史：只记新增的那条
        if (tools.size() > toolMessages.size()) {
            toolMessages.add(tools.get(tools.size() - 1));
        }

        boolean stream = req.get("stream").getBoolean();
        String command = scriptedCommands.poll();
        String message = command == null
                ? "\"role\":\"assistant\",\"content\":\"done\""
                : "\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"index\":0,\"id\":\"c" + toolMessages.size()
                + "\",\"type\":\"function\",\"function\":{\"name\":\"bash\",\"arguments\":"
                + jsonString("{\"command\":" + jsonString(command) + "}") + "}}]";
        String finish = command == null ? "stop" : "tool_calls";
        String usage = "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}";
        if (stream) {
            send(ex, "text/event-stream; charset=utf-8",
                    "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{" + message + "},\"finish_reason\":null}]}\n\n"
                            + "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"" + finish + "\"}]," + usage + "}\n\n"
                            + "data: [DONE]\n\n");
        } else {
            send(ex, "application/json", "{\"id\":\"x\",\"object\":\"chat.completion\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{"
                    + message + "},\"finish_reason\":\"" + finish + "\"}]," + usage + "}");
        }
    }

    private static String jsonString(String s) {
        StringBuilder buf = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') {
                buf.append('\\').append(c);
            } else if (c == '\n') {
                buf.append("\\n");
            } else {
                buf.append(c);
            }
        }
        return buf.append('"').toString();
    }

    private static String readBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
