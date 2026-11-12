package org.noear.solon.ai.harness;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核心子包的依赖方向守卫（4.2 包结构重组后的终态）。
 *
 * <p>子包之间只允许下表列出的依赖（{@link #ALLOWED}，白名单）；对根包的依赖不在检查范围内（引擎门面是有意设计）。</p>
 *
 * <pre>
 * spi        → input, session          只放插件能力接口（可带静态槽位访问方法）
 * input      → command, hitl, scope     输入值对象；带着命令输出工厂 / 解析结果、审批卡、作用域
 * command    → input, scope, session, util  CommandInput 是 HarnessInput 的只读包装，同样暴露本轮作用域
 * agent      → permission, scope, session
 * scope      → agent                   只用 agent.ToolName（4.0.4 起已发布在 agent 包）
 * util       → session
 * session    → hitl                    只放会话事实与归属（SessionOwnership / OwnerCheck / NotOwnerException）
 * hitl       → permission
 * permission → （无）
 * </pre>
 *
 * <p>只允许两个双包循环（{@link #ALLOWED_CYCLES}），其余依赖必须单向：</p>
 * <ul>
 *   <li>{@code {input, command}}：{@code HarnessInput} 带着 {@code CommandOutputFactory}、{@code InputOutcome} 带着
 *   {@code ParsedCommand}，是宿主 API 的一部分；命令又天然要读输入。</li>
 *   <li>{@code {agent, scope}}：{@code InputScope} 的工具名单用 {@code agent.ToolName}（已发布，不能挪），
 *   子代理构建又要挂作用域守卫。</li>
 * </ul>
 *
 * <p>已拆掉的依赖（4.2.0 发布前）：hitl → agent（属性键改用 {@code PermissionContext.ATTR_KEY}）、
 * input → spi（{@code InputOutcome#getDispatchOrigin()}）、scope → input / session（{@code InputScopeResolver} 移到根包）。</p>
 *
 * <p>一条流（run）的生命周期类型 {@code RunTicket}、{@code InterruptPlan} 在根包，任何子包都不得依赖它们（见
 * {@link #subPackagesDoNotDependOnRunLifecycle()}）。</p>
 *
 * <p>另外锁定已退役的包不得复活：{@code runtime}、{@code session.interrupt}、{@code turn}、{@code run}、{@code agent.task}。纯文本扫描 import，不引入 ArchUnit。</p>
 */
class HarnessPackageBoundaryTest {
    private static final Path ROOT = Paths.get("src/main/java/org/noear/solon/ai/harness");
    /** 普通 import 与 static import 都算依赖；只取第一级子包名（command.builtin 归 command） */
    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?org\\.noear\\.solon\\.ai\\.harness\\.([a-z]+)\\.", Pattern.MULTILINE);

    /** 子包 → 允许依赖的子包（白名单；每个子包都必须登记） */
    private static final Map<String, Set<String>> ALLOWED = new TreeMap<>();

    /** 允许存在的循环（强连通分量），理由见类注释 */
    private static final Set<Set<String>> ALLOWED_CYCLES = new HashSet<>();

    static {
        ALLOWED.put("spi", set("input", "session"));
        ALLOWED.put("input", set("command", "hitl", "scope"));
        ALLOWED.put("command", set("input", "scope", "session", "util"));
        ALLOWED.put("agent", set("permission", "scope", "session"));
        ALLOWED.put("scope", set("agent"));
        ALLOWED.put("util", set("session"));
        ALLOWED.put("session", set("hitl"));
        ALLOWED.put("hitl", set("permission"));
        ALLOWED.put("permission", set());

        ALLOWED_CYCLES.add(set("input", "command"));
        ALLOWED_CYCLES.add(set("agent", "scope"));
    }

    private static Set<String> set(String... s) {
        return new TreeSet<>(Arrays.asList(s));
    }

    @Test
    void subPackagesRespectDependencyDirection() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : ALLOWED.entrySet()) {
            Path dir = ROOT.resolve(e.getKey());
            assertTrue(Files.isDirectory(dir), "子包必须存在（否则本护栏形同虚设）：" + e.getKey());
            for (Path file : javaFiles(dir)) {
                String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Matcher m = IMPORT.matcher(content);
                while (m.find()) {
                    String target = m.group(1);
                    if (target.equals(e.getKey()) == false && e.getValue().contains(target) == false) {
                        violations.add(e.getKey() + "/" + file.getFileName() + " -> " + target);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "核心子包出现未登记的依赖（确有必要先改 ALLOWED 并说明理由）：" + violations);
    }

    /** 新建子包必须登记到 ALLOWED，否则会绕过方向检查。 */
    @Test
    void everySubPackageIsRegistered() throws IOException {
        Set<String> actual = new TreeSet<>();
        try (Stream<Path> s = Files.list(ROOT)) {
            s.filter(Files::isDirectory).forEach(p -> actual.add(p.getFileName().toString()));
        }
        assertTrue(ALLOWED.keySet().equals(actual), "子包与登记表不一致：实际 " + actual + "，登记 " + ALLOWED.keySet());
    }

    /** 按实际 import 建图求强连通分量：多包组成的长环也能查出来，只允许 ALLOWED_CYCLES 里的环。 */
    @Test
    void noUnexpectedCycles() throws IOException {
        Map<String, Set<String>> graph = actualGraph();
        List<Set<String>> unexpected = new ArrayList<>();
        for (Set<String> scc : stronglyConnected(graph)) {
            if (scc.size() > 1 && ALLOWED_CYCLES.contains(scc) == false) {
                unexpected.add(scc);
            }
        }
        assertTrue(unexpected.isEmpty(), "子包之间出现未登记的循环：" + unexpected + "，实际依赖图 " + graph);
    }

    /** 子包 → 实际 import 的子包（不含自身与根包） */
    private static Map<String, Set<String>> actualGraph() throws IOException {
        Map<String, Set<String>> graph = new TreeMap<>();
        try (Stream<Path> s = Files.list(ROOT)) {
            for (Path dir : s.filter(Files::isDirectory).collect(Collectors.toList())) {
                String pkg = dir.getFileName().toString();
                Set<String> deps = new TreeSet<>();
                for (Path file : javaFiles(dir)) {
                    Matcher m = IMPORT.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                    while (m.find()) {
                        if (m.group(1).equals(pkg) == false) {
                            deps.add(m.group(1));
                        }
                    }
                }
                graph.put(pkg, deps);
            }
        }
        return graph;
    }

    /** Tarjan 强连通分量 */
    private static List<Set<String>> stronglyConnected(Map<String, Set<String>> graph) {
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        List<Set<String>> result = new ArrayList<>();
        int[] counter = {0};
        for (String v : graph.keySet()) {
            if (index.containsKey(v) == false) {
                tarjan(v, graph, index, low, stack, onStack, result, counter);
            }
        }
        return result;
    }

    private static void tarjan(String v, Map<String, Set<String>> graph, Map<String, Integer> index, Map<String, Integer> low,
                               Deque<String> stack, Set<String> onStack, List<Set<String>> result, int[] counter) {
        index.put(v, counter[0]);
        low.put(v, counter[0]);
        counter[0]++;
        stack.push(v);
        onStack.add(v);
        for (String w : graph.getOrDefault(v, Collections.emptySet())) {
            if (graph.containsKey(w) == false) {
                continue;
            }
            if (index.containsKey(w) == false) {
                tarjan(w, graph, index, low, stack, onStack, result, counter);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (onStack.contains(w)) {
                low.put(v, Math.min(low.get(v), index.get(w)));
            }
        }
        if (low.get(v).equals(index.get(v))) {
            Set<String> scc = new TreeSet<>();
            String w;
            do {
                w = stack.pop();
                onStack.remove(w);
                scc.add(w);
            } while (w.equals(v) == false);
            result.add(scc);
        }
    }

    @Test
    void retiredPackagesStayRetired() {
        assertFalse(Files.exists(ROOT.resolve("runtime")), "runtime 包已退役（RoundResult→goal，ErrorClassifier→util）");
        assertFalse(Files.exists(ROOT.resolve("session/interrupt")), "session.interrupt 已退役（InterruptPlan→根包，InterruptCommand→command.builtin）");
        assertFalse(Files.exists(ROOT.resolve("turn")), "turn 包已退役（SessionTurn→根包 RunTicket，InterruptPlan→根包）");
        assertFalse(Files.exists(ROOT.resolve("run")), "run 包已并入根包（只有 RunTicket、InterruptPlan 两个类）");
        assertFalse(Files.exists(ROOT.resolve("agent/task")), "agent.task 已并回 agent（子代理的定义、注册、构建、委派同处一包；TaskTalent/GenerateTalent/TaskWrapEvent 回到 4.1.1 的发布位置）");
    }

    /**
     * 引擎池留在 core 根包：不用 HarnessHost 的宿主（直接 engine.handle 的 L1 宿主）同样需要按租户托管引擎，
     * 池只依赖引擎公开的 isQuiescent() / shutdown()，放在 core 不会反向依赖 host。
     */
    @Test
    void enginePoolLivesInCore() {
        assertTrue(Files.exists(ROOT.resolve("HarnessEnginePool.java")),
                "HarnessEnginePool 留在 core 根包（org.noear.solon.ai.harness），不要再迁到 host");
    }

    /**
     * 作用域解析器是配给引擎的策略（{@code Builder.scopeResolver}），留在根包：放回 scope 包会让 scope 依赖 input / session，
     * 把 {input, command} 与 {agent, scope} 两个小环连成一个大环。
     */
    @Test
    void scopeResolverLivesInRoot() {
        assertTrue(Files.exists(ROOT.resolve("InputScopeResolver.java")), "InputScopeResolver 在根包");
        assertFalse(Files.exists(ROOT.resolve("scope/InputScopeResolver.java")), "InputScopeResolver 不得回到 scope 包");
    }

    /** 子包不得依赖流生命周期类型（原 turn/run 包的约束，并入根包后保留；javadoc 里的全类名链接不算依赖）。 */
    @Test
    void subPackagesDoNotDependOnRunLifecycle() throws IOException {
        Pattern lifecycleImport = Pattern.compile("^import\\s+org\\.noear\\.solon\\.ai\\.harness\\.(RunTicket|InterruptPlan)\\s*;", Pattern.MULTILINE);
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(ROOT)) {
            if (file.getParent().equals(ROOT)) {
                continue;
            }
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Matcher m = lifecycleImport.matcher(content);
            if (m.find()) {
                violations.add(ROOT.relativize(file) + " -> " + m.group(1));
            }
        }
        assertTrue(violations.isEmpty(), "子包不得依赖流生命周期类型：" + violations);
    }

    /** spi 只放接口：顶层类型必须是 interface；会话归属归 session 包，不得回到 spi。 */
    @Test
    void spiHoldsOnlyPluginCapabilityInterfaces() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(ROOT.resolve("spi"))) {
            String name = file.getFileName().toString().replace(".java", "");
            Class<?> c = Class.forName("org.noear.solon.ai.harness.spi." + name);
            if (!c.isInterface()) {
                violations.add(name);
            }
        }
        assertTrue(violations.isEmpty(), "spi 包只放接口：" + violations);
        assertFalse(Files.exists(ROOT.resolve("spi/SessionOwnership.java")), "SessionOwnership 已归 session 包（与 OwnerCheck / NotOwnerException 同处）");
        assertFalse(Files.exists(ROOT.resolve("ownership")), "不单设 ownership 包：归属是会话事实的一部分");
    }

    @Test
    void interruptCoreIsPackagePrivate() throws Exception {
        Class<?> c = Class.forName("org.noear.solon.ai.harness.HarnessInterrupts");
        assertFalse(java.lang.reflect.Modifier.isPublic(c.getModifiers()),
                "中断核心实现必须包内可见：宿主一律经 engine.interrupt(session, mode) / prepareInterrupt");
    }

    private static List<Path> javaFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }
}
