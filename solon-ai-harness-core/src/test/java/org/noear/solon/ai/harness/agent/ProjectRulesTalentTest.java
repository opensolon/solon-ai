package org.noear.solon.ai.harness.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import features.ai.harness.i18n.HarnessFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.Talent;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.mount.source.FileMountSource;
import org.noear.solon.ai.mount.source.FindOptions;
import org.noear.solon.ai.mount.source.MountCapabilities;
import org.noear.solon.ai.mount.source.MountEntry;
import org.noear.solon.ai.mount.source.MountSource;
import org.noear.solon.ai.mount.source.MoveOptions;
import org.noear.solon.ai.mount.source.WriteOptions;
import org.noear.solon.ai.util.MarkdownUtil;
import org.noear.solon.core.util.RankEntity;
import org.noear.snack4.ONode;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 项目规范（AGENTS.md，参考 opencode）契约：经工作区来源读取、由近及远分层、主代理与子代理都带上、
 * 角色段被替换也不丢、{@code #{...}} 原样、可整体关闭；每次运行现读、运行中途的变更以消息追加；
 * read / ls 触发的子目录按需加载。
 */
public class ProjectRulesTalentTest {
    private static final String HH = ".soloncode/";

    @TempDir
    Path ws;
    @TempDir
    Path userHome;
    @TempDir
    Path outside;

    private HttpServer server;
    /** 每次模型请求里的系统提示词 */
    private final List<String> systemPrompts = new CopyOnWriteArrayList<>();
    /** 每次模型请求的非系统消息（role: content） */
    private final List<List<String>> requests = new CopyOnWriteArrayList<>();
    /** 依次返回的工具调用（{name, argumentsJson}）；用完后回答 done */
    private final Deque<String[]> scriptedToolCalls = new ConcurrentLinkedDeque<>();
    /** 下一次模型请求应答前执行一次（用于模拟运行中途改文件） */
    private final Deque<ThrowingRunnable> beforeReply = new ConcurrentLinkedDeque<>();

    private interface ThrowingRunnable {
        void run() throws IOException;
    }

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

    private void handle(HttpExchange ex) throws IOException {
        String body = readBody(ex.getRequestBody());
        ONode req = ONode.ofJson(body);
        List<String> others = new ArrayList<>();
        for (ONode m : req.get("messages").getArray()) {
            if ("system".equals(m.get("role").getString())) {
                systemPrompts.add(m.get("content").getString());
            } else {
                others.add(m.get("role").getString() + ": " + m.get("content").getString());
            }
        }
        requests.add(others);
        ThrowingRunnable hook = beforeReply.poll();
        if (hook != null) {
            hook.run();
        }

        boolean stream = req.get("stream").getBoolean();
        String[] call = scriptedToolCalls.poll();
        String message = call == null
                ? "\"role\":\"assistant\",\"content\":\"done\""
                : "\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"index\":0,\"id\":\"c" + requests.size()
                + "\",\"type\":\"function\",\"function\":{\"name\":\"" + call[0] + "\",\"arguments\":" + jsonString(call[1]) + "}}]";
        String finish = call == null ? "stop" : "tool_calls";
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

    private HarnessEngine engine(Consumer<HarnessEngine.Builder> custom) {
        ChatConfig model = new ChatConfig();
        model.setName("srv");
        model.setModel("m");
        model.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        model.setApiKey("test");

        Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
        HarnessEngine.Builder builder = HarnessEngine.of(ws.toString(), HH)
                .modelAdd(model)
                .defaultModel("srv")
                .autoRethink(false)
                .sessionProvider((id, create) -> create
                        ? sessions.computeIfAbsent(id, InMemoryAgentSession::of)
                        : sessions.get(id));
        if (custom != null) {
            custom.accept(builder);
        }
        return builder.build();
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private String lastSystemPrompt() {
        assertFalse(systemPrompts.isEmpty(), "模型应至少收到一次请求");
        return systemPrompts.get(systemPrompts.size() - 1);
    }

    private static List<String> labels(List<ProjectRulesTalent.Part> parts) {
        return parts.stream().map(ProjectRulesTalent.Part::getPath).collect(Collectors.toList());
    }

    // ---------- 加载规则 ----------

    @Test
    public void layersNearestFirstAgentsMdOnly() throws Exception {
        write(ws.resolve("AGENTS.md"), "root agents");
        write(ws.resolve("CLAUDE.md"), "root claude (ignored)");
        write(ws.resolve("a/CLAUDE.md"), "a claude (ignored)");
        write(ws.resolve("a/b/AGENTS.md"), "ab agents");
        write(ws.resolve(HH + "AGENTS.md"), "tool rules (host's business)");
        Files.createDirectories(ws.resolve("a/b/c"));

        ProjectRulesTalent talent = new ProjectRulesTalent(FileMountSource.of(ws), ws.toString());
        List<ProjectRulesTalent.Part> parts = talent.load(ws.resolve("a/b/c").toString());

        // 工作目录由近及远到工作区根；只认 AGENTS.md，不读 CLAUDE.md；工具专属目录（harnessHome）不是项目层，不读
        assertEquals(Arrays.asList("a/b/AGENTS.md", "AGENTS.md"), labels(parts));
        assertEquals("ab agents", parts.get(0).getContent());
    }

    @Test
    public void engineReadsNeitherUserLevelNorHarnessHome() throws Exception {
        write(userHome.resolve(HH + "AGENTS.md"), "user rules (host's business)");
        write(ws.resolve(HH + "AGENTS.md"), "tool rules (host's business)");
        write(ws.resolve("AGENTS.md"), "root agents");

        // 用户级、工具专属目录因产品而异，由宿主自行处理（如并入 systemPrompt）；引擎只读项目各层。
        // 临时把 user.home 指到放了规范的目录，锁定引擎不会自己去读用户目录
        String saved = System.getProperty("user.home");
        HarnessEngine engine;
        try {
            System.setProperty("user.home", userHome.toString());
            engine = engine(null);
        } finally {
            System.setProperty("user.home", saved);
        }

        assertEquals(Arrays.asList("AGENTS.md"), labels(engine.getProjectRulesTalent().load(null)));
    }

    @Test
    public void cwdOutsideWorkspaceReadsOnlyWorkspaceRoot() throws Exception {
        write(ws.resolve("AGENTS.md"), "root agents");
        write(outside.resolve("AGENTS.md"), "outside (must not read)");

        HarnessEngine engine = engine(null);
        List<ProjectRulesTalent.Part> parts = engine.getProjectRulesTalent().load(outside.toString());

        assertEquals(Arrays.asList("AGENTS.md"), labels(parts));
    }

    @Test
    public void noBudgetLikeOpencode() throws Exception {
        write(ws.resolve("AGENTS.md"), repeat('r', 30000));

        HarnessEngine engine = engine(null);
        String ins = engine.getProjectRulesTalent().getInstruction(prompt(ws.toString(), Locale.ENGLISH));
        assertTrue(ins.startsWith("Instructions from: AGENTS.md\n"), "opencode 格式: " + ins.substring(0, 40));
        assertTrue(ins.contains(repeat('r', 30000)), "完整注入");
        assertFalse(ins.contains("read `"), "没截断就不提示");
    }

    @Test
    public void changesAreVisibleOnNextRun() throws Exception {
        write(ws.resolve("AGENTS.md"), "v1");
        HarnessEngine engine = engine(null);
        ProjectRulesTalent talent = engine.getProjectRulesTalent();
        assertEquals("v1", talent.load(null).get(0).getContent());

        write(ws.resolve("AGENTS.md"), "version two");
        assertEquals("version two", talent.load(null).get(0).getContent());

        Files.delete(ws.resolve("AGENTS.md"));
        assertTrue(talent.load(null).isEmpty());
    }

    // ---------- 健壮性 ----------

    @Test
    public void readFailureKeepsLastContentButDeletionRemovesIt() throws Exception {
        write(ws.resolve("AGENTS.md"), "v1");
        FlakySource src = new FlakySource(FileMountSource.of(ws));
        ProjectRulesTalent talent = new ProjectRulesTalent(src, ws.toString());
        assertEquals("v1", talent.load(null).get(0).getContent());

        src.failStat = true;
        assertEquals(Arrays.asList("AGENTS.md"), labels(talent.load(null)), "stat 出错时沿用上次内容");
        assertEquals("v1", talent.load(null).get(0).getContent());

        src.failStat = false;
        src.failRead = true;
        write(ws.resolve("AGENTS.md"), "version two");
        assertEquals("v1", talent.load(null).get(0).getContent(), "内容变了但读取出错，仍用上次的");

        src.failRead = false;
        assertEquals("version two", talent.load(null).get(0).getContent(), "恢复后读到新内容");

        Files.delete(ws.resolve("AGENTS.md"));
        assertTrue(talent.load(null).isEmpty(), "文件确实删了才去掉该段");

        ProjectRulesTalent fresh = new ProjectRulesTalent(src, ws.toString());
        write(ws.resolve("AGENTS.md"), "v3");
        src.failRead = true;
        assertTrue(fresh.load(null).isEmpty(), "没有上次内容时读取出错就跳过该层");
    }

    @Test
    public void largeFileReadsOnlyHeadAndHintsRead() throws Exception {
        write(ws.resolve("AGENTS.md"), repeat('\u4e2d', 400_000)); // 约 1.2MB，每字 3 字节
        FlakySource src = new FlakySource(FileMountSource.of(ws));
        ProjectRulesTalent talent = new ProjectRulesTalent(src, ws.toString());

        ProjectRulesTalent.Part part = talent.load(null).get(0);
        assertTrue(part.isTruncated());
        assertTrue(src.bytesRead.get() <= ProjectRulesTalent.MAX_FILE_BYTES + 1, "只读开头 1MB: " + src.bytesRead.get());
        assertEquals(ProjectRulesTalent.MAX_FILE_BYTES / 3, part.getContent().length(), "按 UTF-8 边界截断");
        assertEquals(-1, part.getContent().indexOf('\uFFFD'), "不切断多字节字符");

        String ins = talent.getInstruction(prompt(ws.toString(), Locale.ENGLISH));
        assertTrue(ins.endsWith("read `AGENTS.md` for the full content)"), "超过上限时提示读原文");

        write(ws.resolve("AGENTS.md"), "small");
        assertFalse(talent.load(null).get(0).isTruncated());
        assertFalse(talent.getInstruction(prompt(ws.toString(), Locale.ENGLISH)).contains("read `AGENTS.md`"));
    }

    @Test
    public void sameFileIsLoadedOnlyOnce() throws Exception {
        write(ws.resolve("AGENTS.md"), "root agents");
        Files.createDirectories(ws.resolve("a"));
        try {
            // 子目录的规范是指向根目录规范的软链接：同一个文件经两层到达
            Files.createSymbolicLink(ws.resolve("a/AGENTS.md"), ws.resolve("AGENTS.md"));
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "当前文件系统不支持软链接");
        }

        ProjectRulesTalent talent = new ProjectRulesTalent(FileMountSource.of(ws), ws.toString());
        assertEquals(Arrays.asList("a/AGENTS.md"), labels(talent.load(ws.resolve("a").toString())),
                "同一个文件经不同层到达只收一次");
    }

    @Test
    public void symlinkAliasesDoNotDropLayersAndEscapesAreSkipped() throws Exception {
        write(ws.resolve("AGENTS.md"), "root agents");
        write(ws.resolve("a/AGENTS.md"), "a agents");
        write(ws.resolve("a/b/AGENTS.md"), "ab agents");
        write(outside.resolve("AGENTS.md"), "outside (must not read)");

        Path alias = outside.resolve("ws-alias");
        Path escape = ws.resolve("escape");
        try {
            Files.createSymbolicLink(alias, ws);
            Files.createSymbolicLink(escape, outside);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "当前文件系统不支持软链接");
        }

        // 工作区按别名配置、工作目录给的是真实路径（如 /tmp 与 /private/tmp）
        ProjectRulesTalent viaAlias = new ProjectRulesTalent(FileMountSource.of(alias), alias.toString());
        assertEquals(Arrays.asList("a/b/AGENTS.md", "a/AGENTS.md", "AGENTS.md"),
                labels(viaAlias.load(ws.toRealPath().resolve("a/b").toString())));

        // 反过来：工作区是真实路径、工作目录经别名给出
        ProjectRulesTalent viaReal = new ProjectRulesTalent(FileMountSource.of(ws), ws.toString());
        assertEquals(Arrays.asList("a/b/AGENTS.md", "a/AGENTS.md", "AGENTS.md"),
                labels(viaReal.load(alias.resolve("a/b").toString())));

        // 工作区内指向外部的软链接目录视为工作区外
        assertEquals(Arrays.asList("AGENTS.md"), labels(viaReal.load(escape.toString())));
    }

    // ---------- 注入 ----------

    @Test
    public void mainAgentGetsAgentsMdAfterRoleAndKeepsSnelLiteral() throws Throwable {
        write(ws.resolve("AGENTS.md"), "Use #{id} literally in mapper xml.");
        HarnessEngine engine = engine(b -> b.systemPrompt("ROLE-ENGINE"));

        engine.handle(HarnessInput.of(engine.getSession("s1"), "hi")).call();

        String sp = lastSystemPrompt();
        assertTrue(sp.contains("ROLE-ENGINE"), sp);
        assertTrue(sp.contains("Use #{id} literally in mapper xml."), "#{...} 不得被模板渲染: " + sp);
        assertTrue(sp.indexOf("ROLE-ENGINE") < sp.indexOf("Use #{id}"), "规范排在角色段之后");
        assertTrue(sp.contains("[Talent: ProjectRules]"), "才能标题用规范语义的名字: " + sp);
        assertFalse(sp.contains("ProjectRulesTalent"), "提示词里不出现类名: " + sp);
    }

    @Test
    public void mainAgentWithDefaultTemplateGetsAgentsMd() throws Throwable {
        write(ws.resolve("AGENTS.md"), "RULE-DEFAULT-TEMPLATE");
        HarnessEngine engine = engine(null);

        engine.handle(HarnessInput.of(engine.getSession("s1"), "hi")).call();

        assertTrue(lastSystemPrompt().contains("RULE-DEFAULT-TEMPLATE"), "未配 systemPrompt（默认模板）也要带上");
    }

    @Test
    public void inputSystemPromptReplacesRoleButKeepsAgentsMd() throws Throwable {
        write(ws.resolve("AGENTS.md"), "RULE-KEPT");
        HarnessEngine engine = engine(b -> b.systemPrompt("ROLE-ENGINE"));

        engine.handle(HarnessInput.of(engine.getSession("s1"), "hi").systemPrompt("ROLE-INPUT")).call();

        String sp = lastSystemPrompt();
        assertTrue(sp.contains("ROLE-INPUT"), sp);
        assertFalse(sp.contains("ROLE-ENGINE"), "角色段被整段替换");
        assertTrue(sp.contains("RULE-KEPT"), "替换角色段不得丢掉项目规范");
    }

    @Test
    public void fileChangeTakesEffectWithoutRebuildingMainAgent() throws Throwable {
        write(ws.resolve("AGENTS.md"), "RULE-OLD");
        HarnessEngine engine = engine(null);
        engine.handle(HarnessInput.of(engine.getSession("s1"), "hi")).call();
        assertTrue(lastSystemPrompt().contains("RULE-OLD"));

        write(ws.resolve("AGENTS.md"), "RULE-NEW-CONTENT");
        engine.handle(HarnessInput.of(engine.getSession("s2"), "hi")).call();
        assertTrue(lastSystemPrompt().contains("RULE-NEW-CONTENT"), "主代理已缓存，规范仍应现读");
    }

    @Test
    public void exploreSubagentGetsAgentsMd() throws Throwable {
        write(ws.resolve("AGENTS.md"), "RULE-FOR-SUBAGENT");
        HarnessEngine engine = engine(null);
        HarnessFixtures.bindTrace(engine.getSession("s1"), HarnessFixtures.trace(null, false));

        TaskTalent.SingleTaskOp op = new TaskTalent.SingleTaskOp();
        op.agent_name = "explore";
        op.description = "d";
        op.prompt = "look";
        ToolResult r = engine.getTaskTalent().task(op, ws.toString(), "s1", null);

        assertFalse(r.isError(), r.getContent());
        assertTrue(lastSystemPrompt().contains("RULE-FOR-SUBAGENT"), "子代理用自己的正文，也要带上项目规范");
    }

    @Test
    public void noAgentsMdLeavesSystemPromptUntouched() throws Throwable {
        HarnessEngine engine = engine(null);
        engine.handle(HarnessInput.of(engine.getSession("s1"), "hi")).call();

        assertFalse(lastSystemPrompt().contains("[Talent: " + ProjectRulesTalent.NAME + "]"), "没有 AGENTS.md 时不出现该段");
    }

    // ---------- 每次运行现读 + 运行中途的变更消息（opencode InstructionState） ----------

    /** 最后一次请求里，由项目规范注入的用户消息 */
    private List<String> lastInjected() {
        assertFalse(requests.isEmpty());
        List<String> out = new ArrayList<>();
        for (String m : requests.get(requests.size() - 1)) {
            if (m.startsWith("user: ") && (m.contains("instructions") || m.contains("Instructions from:"))) {
                out.add(m);
            }
        }
        return out;
    }

    @Test
    public void changesBetweenRunsGoToNextSystemPrompt() throws Throwable {
        write(ws.resolve("AGENTS.md"), "RULE-V1");
        HarnessEngine engine = engine(b -> b.locale(Locale.ENGLISH));
        AgentSession s1 = engine.getSession("s1");

        engine.handle(HarnessInput.of(s1, "hi")).call();
        String first = lastSystemPrompt();
        assertTrue(first.contains("RULE-V1"), first);
        assertTrue(lastInjected().isEmpty(), "未变化时不追加消息");

        engine.handle(HarnessInput.of(s1, "same")).call();
        assertEquals(first, lastSystemPrompt(), "文件没改时系统提示词逐字不变（提示词缓存命中）");

        write(ws.resolve("AGENTS.md"), "RULE-V2");
        engine.handle(HarnessInput.of(s1, "again")).call();
        assertTrue(lastSystemPrompt().contains("RULE-V2") && lastSystemPrompt().contains("RULE-V1") == false, "下一次运行现读");
        assertTrue(lastInjected().isEmpty(), "系统提示词里已是新内容，不发变更消息: " + lastInjected());

        Files.delete(ws.resolve("AGENTS.md"));
        engine.handle(HarnessInput.of(s1, "third")).call();
        assertFalse(lastSystemPrompt().contains("[Talent: " + ProjectRulesTalent.NAME + "]"));
        assertTrue(lastInjected().isEmpty());
    }

    @Test
    public void midRunChangesArriveAsMessageOnce() throws Throwable {
        write(ws.resolve("AGENTS.md"), "RULE-V1");
        write(ws.resolve("x.txt"), "x");
        HarnessEngine engine = engine(b -> b.locale(Locale.ENGLISH).toolsAdd(ToolName.TOOL_READ));

        scriptedToolCalls.add(new String[]{"read", "{\"file_path\":\"x.txt\"}"});
        scriptedToolCalls.add(new String[]{"read", "{\"file_path\":\"x.txt\"}"});
        scriptedToolCalls.add(new String[]{"read", "{\"file_path\":\"x.txt\"}"});
        beforeReply.add(() -> write(ws.resolve("AGENTS.md"), "RULE-V2")); // 第 1 步后改
        beforeReply.add(() -> { });                                        // 第 2 步不改
        beforeReply.add(() -> Files.delete(ws.resolve("AGENTS.md")));      // 第 3 步后删
        engine.handle(HarnessInput.of(engine.getSession("s1"), "look")).call();

        assertEquals(4, requests.size(), String.valueOf(requests));
        for (String sp : systemPrompts) {
            assertEquals(systemPrompts.get(0), sp, "同一次运行内系统提示词不变");
        }
        assertTrue(systemPrompts.get(0).contains("RULE-V1"));

        assertEquals(Arrays.asList("user: The instructions changed:\nInstructions from: AGENTS.md\nRULE-V2"),
                injectedIn(1), "改了：给整段原文");
        assertEquals(1, injectedIn(2).size(), "没再改：不重复发");
        List<String> last = injectedIn(3);
        assertEquals(2, last.size(), String.valueOf(last));
        assertEquals("user: Previously loaded instructions no longer apply.", last.get(1), "删了：告知不再适用");
    }

    @Test
    public void midRunAddedFileArrivesAsNewInstructions() throws Throwable {
        // 运行开始时还没有规范，运行中途新建
        write(ws.resolve("x.txt"), "x");
        HarnessEngine engine = engine(b -> b.locale(Locale.ENGLISH).toolsAdd(ToolName.TOOL_READ));

        scriptedToolCalls.add(new String[]{"read", "{\"file_path\":\"x.txt\"}"});
        beforeReply.add(() -> write(ws.resolve("AGENTS.md"), "RULE-ROOT"));
        engine.handle(HarnessInput.of(engine.getSession("s1"), "look")).call();

        assertFalse(lastSystemPrompt().contains("RULE-ROOT"));
        assertEquals(Arrays.asList("user: New instructions apply from:\nInstructions from: AGENTS.md\nRULE-ROOT"), lastInjected());
    }

    /** 第 index 次请求里，由项目规范注入的用户消息 */
    private List<String> injectedIn(int index) {
        List<String> out = new ArrayList<>();
        for (String m : requests.get(index)) {
            if (m.startsWith("user: ") && (m.contains("instructions") || m.contains("Instructions from:"))) {
                out.add(m);
            }
        }
        return out;
    }

    @Test
    public void sessionCwdChangeFollowsNewDirectory() throws Throwable {
        write(ws.resolve("AGENTS.md"), "RULE-ROOT");
        write(ws.resolve("a/AGENTS.md"), "RULE-A");
        write(ws.resolve("b/AGENTS.md"), "RULE-B");
        HarnessEngine engine = engine(b -> b.locale(Locale.ENGLISH));
        AgentSession s1 = engine.getSession("s1");

        engine.setSessionCwd(s1, ws.resolve("a").toString());
        engine.handle(HarnessInput.of(s1, "hi")).call();
        assertTrue(lastSystemPrompt().contains("RULE-A"), lastSystemPrompt());

        // 换工作目录（桌面端每次输入都会设）：按新目录读，不发变更消息
        engine.setSessionCwd(s1, ws.resolve("b").toString());
        engine.handle(HarnessInput.of(s1, "again")).call();
        String second = lastSystemPrompt();
        assertTrue(second.contains("RULE-B") && second.contains("RULE-ROOT"), second);
        assertFalse(second.contains("RULE-A"), "不得沿用旧目录的规范: " + second);
        assertTrue(lastInjected().isEmpty(), "换目录不是规范变更: " + lastInjected());

        engine.handle(HarnessInput.of(s1, "third")).call();
        assertEquals(second, lastSystemPrompt(), "同一目录下系统提示词保持不变");
        assertTrue(lastInjected().isEmpty(), "不得每轮重复发消息: " + lastInjected());
    }

    @Test
    public void sessionOutsideProviderWorksTheSame() throws Throwable {
        write(ws.resolve("AGENTS.md"), "RULE-V1");
        write(ws.resolve("x.txt"), "x");
        HarnessEngine engine = engine(b -> b.locale(Locale.ENGLISH).toolsAdd(ToolName.TOOL_READ));
        AgentSession foreign = InMemoryAgentSession.of("foreign"); // 宿主绕过引擎自建会话：不依赖会话提供者

        engine.getMainAgent().prompt(Prompt.of("hi")).session(foreign).call();
        assertTrue(lastSystemPrompt().contains("RULE-V1"));

        int before = requests.size();
        scriptedToolCalls.add(new String[]{"read", "{\"file_path\":\"x.txt\"}"});
        beforeReply.add(() -> write(ws.resolve("AGENTS.md"), "RULE-V2"));
        engine.getMainAgent().prompt(Prompt.of("again")).session(foreign).call();
        assertTrue(requests.size() >= before + 2, "应有工具调用后的第二步");
        assertTrue(lastSystemPrompt().contains("RULE-V1"), "同一次运行内系统提示词不变");
        String msg = lastInjected().get(0);
        assertTrue(msg.contains("RULE-V2") && msg.contains("The instructions changed"), msg);
    }

    // ---------- 子目录按需加载（opencode read 工具） ----------

    @Test
    public void readInSubdirectoryInjectsNearbyRulesOnce() throws Throwable {
        write(ws.resolve("AGENTS.md"), "RULE-ROOT");
        write(ws.resolve("pkg/AGENTS.md"), "RULE-PKG");
        write(ws.resolve("pkg/sub/x.txt"), "hello");
        write(ws.resolve("pkg/sub/y.txt"), "world");
        HarnessEngine engine = engine(b -> b.locale(Locale.ENGLISH).toolsAdd(ToolName.TOOL_READ));

        scriptedToolCalls.add(new String[]{"read", "{\"file_path\":\"pkg/sub/x.txt\"}"});
        scriptedToolCalls.add(new String[]{"read", "{\"file_path\":\"pkg/sub/y.txt\"}"});
        engine.handle(HarnessInput.of(engine.getSession("s1"), "look")).call();

        assertFalse(systemPrompts.get(0).contains("RULE-PKG"), "子目录规范不在基线里");
        int hits = 0;
        for (String m : requests.get(requests.size() - 1)) {
            if (m.contains("Instructions from: pkg/AGENTS.md\nRULE-PKG")) {
                hits++;
                assertTrue(m.startsWith("user: "), m);
            }
            assertFalse(m.contains("Instructions from: AGENTS.md"), "工作目录及以上已在基线里，不重复注入: " + m);
        }
        assertEquals(1, hits, "同一次运行里只注入一次: " + requests.get(requests.size() - 1));
        assertTrue(requests.get(1).toString().contains("RULE-PKG"), "第一次读到后的下一步就能看到");
    }

    @Test
    public void discoverRules() throws Exception {
        write(ws.resolve("AGENTS.md"), "root");
        write(ws.resolve("a/AGENTS.md"), "a");
        write(ws.resolve("a/CLAUDE.md"), "a claude (ignored)");
        write(ws.resolve("a/b/AGENTS.md"), "ab");
        write(ws.resolve("a/b/c/CLAUDE.md"), "abc claude (ignored)");
        write(ws.resolve("a/b/c/x.txt"), "x");
        write(outside.resolve("AGENTS.md"), "outside");
        ProjectRulesTalent talent = new ProjectRulesTalent(FileMountSource.of(ws), ws.toString());

        assertEquals(Arrays.asList("a/b/AGENTS.md", "a/AGENTS.md"), labels(talent.discover(null, "a/b/c/x.txt", false)),
                "由近及远，止于工作区根（不含）；不读 CLAUDE.md");
        assertEquals(Arrays.asList("a/b/AGENTS.md", "a/AGENTS.md"), labels(talent.discover(null, "a/b", true)),
                "列目录从该目录本身开始");
        assertEquals(Arrays.asList("a/b/AGENTS.md"), labels(talent.discover(ws.resolve("a").toString(), "b/c/x.txt", false)),
                "止于工作目录（不含）：工作目录及以上已在基线里");
        assertTrue(talent.discover(null, "a/AGENTS.md", false).isEmpty(), "被读的就是规范文件：不再注入它");
        assertTrue(talent.discover(null, outside.resolve("x.txt").toString(), false).isEmpty(), "工作区外不处理");
        assertTrue(talent.discover(null, "@pool/x.txt", false).isEmpty(), "逻辑路径不处理");
    }

    // ---------- 关闭 ----------

    @Test
    public void engineSwitchDisablesInjection() throws Throwable {
        write(ws.resolve("AGENTS.md"), "RULE-OFF");
        HarnessEngine engine = engine(b -> b.projectRulesEnabled(false));

        engine.handle(HarnessInput.of(engine.getSession("s1"), "hi")).call();

        assertFalse(lastSystemPrompt().contains("RULE-OFF"));
    }

    @Test
    public void everyAgentMountsProjectRulesFirst() {
        HarnessEngine engine = engine(null);

        AgentDefinition def = AgentDefinition.fromParsedMarkdown(MarkdownUtil.resolve(Arrays.asList(
                "---", "name: reader", "description: r", "tools: [\"read\"]", "---", "You read.")));

        List<String> names = talentNames(AgentFactory.create(engine, def).build());
        assertEquals(ProjectRulesTalent.NAME, names.get(0), "排在其它才能之前: " + names);
    }

    // ---------- 辅助 ----------

    private static List<String> talentNames(ReActAgent agent) {
        List<String> names = new ArrayList<>();
        for (RankEntity<Talent> t : agent.getConfig().getDefaultOptions().getModelOptions().talents()) {
            names.add(t.target.name());
        }
        return names;
    }

    private static Prompt prompt(String cwd, Locale locale) {
        Prompt p = Prompt.of("x");
        p.attrPut(HarnessEngine.ATTR_CWD, cwd);
        p.locale(locale);
        return p;
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    /** 委托式来源：可模拟 stat / 读取出错，并统计实际读取的字节数 */
    private static final class FlakySource implements MountSource {
        final MountSource d;
        volatile boolean failStat;
        volatile boolean failRead;
        final AtomicLong bytesRead = new AtomicLong();

        FlakySource(MountSource d) {
            this.d = d;
        }

        @Override
        public MountEntry stat(String path) throws IOException {
            if (failStat) {
                throw new IOException("source unavailable");
            }
            return d.stat(path);
        }

        @Override
        public InputStream openRead(String path) throws IOException {
            if (failRead) {
                throw new IOException("read failed");
            }
            return new FilterInputStream(d.openRead(path)) {
                @Override
                public int read() throws IOException {
                    int b = super.read();
                    if (b >= 0) {
                        bytesRead.incrementAndGet();
                    }
                    return b;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int n = super.read(b, off, len);
                    if (n > 0) {
                        bytesRead.addAndGet(n);
                    }
                    return n;
                }
            };
        }

        @Override
        public String getScheme() {
            return d.getScheme();
        }

        @Override
        public String normalize(String path) {
            return d.normalize(path);
        }

        @Override
        public List<MountEntry> list(String path) throws IOException {
            return d.list(path);
        }

        @Override
        public List<MountEntry> find(String path, FindOptions options) throws IOException {
            return d.find(path, options);
        }

        @Override
        public OutputStream openWrite(String path, WriteOptions options) throws IOException {
            return d.openWrite(path, options);
        }

        @Override
        public void delete(String path) throws IOException {
            d.delete(path);
        }

        @Override
        public void move(String source, String target, MoveOptions options) throws IOException {
            d.move(source, target, options);
        }

        @Override
        public MountCapabilities capabilities() {
            return d.capabilities();
        }

        @Override
        public Optional<Path> getLocalRoot() {
            return d.getLocalRoot();
        }

        @Override
        public Optional<Path> getLocalPath(String path) {
            return d.getLocalPath(path);
        }
    }
}
