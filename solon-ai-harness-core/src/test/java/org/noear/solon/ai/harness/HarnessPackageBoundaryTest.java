package org.noear.solon.ai.harness;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核心子包的依赖方向守卫（4.2 包结构重组后的终态）。
 *
 * <pre>
 * input   → (根包)                 纯输入值对象，只依赖 command 的工厂/解析类型
 * hitl    → agent, permission
 * session → input, hitl            只放会话事实与归属（SessionOwnership / OwnerCheck / NotOwnerException），不得依赖 command、spi
 * spi     → session, input        只放插件能力接口（可带静态槽位访问方法）
 * command → input, session, util
 * </pre>
 *
 * <p>一条流（run）的生命周期类型 {@code RunTicket}、{@code InterruptPlan} 在根包，任何子包都不得依赖它们（见
 * {@link #subPackagesDoNotDependOnRunLifecycle()}）。</p>
 *
 * <p>另外锁定已退役的包不得复活：{@code runtime}、{@code session.interrupt}、{@code turn}、{@code run}、{@code agent.task}。纯文本扫描 import，不引入 ArchUnit。</p>
 */
class HarnessPackageBoundaryTest {
    private static final Path ROOT = Paths.get("src/main/java/org/noear/solon/ai/harness");
    private static final Pattern IMPORT = Pattern.compile("^import\\s+org\\.noear\\.solon\\.ai\\.harness\\.([a-z]+)\\.", Pattern.MULTILINE);

    /** 子包 → 不允许依赖的子包 */
    private static final Map<String, Set<String>> FORBIDDEN = new HashMap<>();

    static {
        FORBIDDEN.put("input", set("session", "agent", "util"));
        FORBIDDEN.put("session", set("command", "agent", "spi"));
        FORBIDDEN.put("spi", set("command", "agent", "hitl", "permission", "scope"));
        FORBIDDEN.put("hitl", set("input", "session", "command"));
        FORBIDDEN.put("permission", set("input", "session", "command", "hitl", "agent"));
        FORBIDDEN.put("util", set("input", "command", "hitl", "agent"));
    }

    private static Set<String> set(String... s) {
        return new HashSet<>(Arrays.asList(s));
    }

    @Test
    void subPackagesRespectDependencyDirection() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : FORBIDDEN.entrySet()) {
            Path dir = ROOT.resolve(e.getKey());
            assertTrue(Files.isDirectory(dir), "子包必须存在（否则本护栏形同虚设）：" + e.getKey());
            for (Path file : javaFiles(dir)) {
                String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Matcher m = IMPORT.matcher(content);
                while (m.find()) {
                    if (e.getValue().contains(m.group(1))) {
                        violations.add(e.getKey() + "/" + file.getFileName() + " -> " + m.group(1));
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "核心子包依赖方向被破坏：" + violations);
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
