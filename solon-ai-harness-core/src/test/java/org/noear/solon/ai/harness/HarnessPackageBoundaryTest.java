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
 * session → input, hitl            只放会话事实，不得依赖 turn / command
 * turn    → input, session         受理、派发、轮次生命周期、中断计划
 * command → input, session, util（不得依赖 turn）
 * </pre>
 *
 * <p>另外锁定已退役的包不得复活：{@code runtime}、{@code session.interrupt}。纯文本扫描 import，不引入 ArchUnit。</p>
 */
class HarnessPackageBoundaryTest {
    private static final Path ROOT = Paths.get("src/main/java/org/noear/solon/ai/harness");
    private static final Pattern IMPORT = Pattern.compile("^import\\s+org\\.noear\\.solon\\.ai\\.harness\\.([a-z]+)\\.", Pattern.MULTILINE);

    /** 子包 → 不允许依赖的子包 */
    private static final Map<String, Set<String>> FORBIDDEN = new HashMap<>();

    static {
        FORBIDDEN.put("input", set("session", "turn", "agent", "util"));
        FORBIDDEN.put("session", set("turn", "command", "agent"));
        FORBIDDEN.put("turn", set("command", "agent", "hitl"));
        FORBIDDEN.put("command", set("turn"));
        FORBIDDEN.put("hitl", set("input", "session", "turn", "command"));
        FORBIDDEN.put("permission", set("input", "session", "turn", "command", "hitl", "agent"));
        FORBIDDEN.put("util", set("input", "turn", "command", "hitl", "agent"));
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
        assertFalse(Files.exists(ROOT.resolve("session/interrupt")), "session.interrupt 已退役（InterruptPlan→turn，InterruptCommand→command.builtin）");
    }

    @Test
    void interruptCoreIsPackagePrivate() throws Exception {
        Class<?> c = Class.forName("org.noear.solon.ai.harness.HarnessInterrupts");
        assertFalse(java.lang.reflect.Modifier.isPublic(c.getModifiers()),
                "中断核心实现必须包内可见：宿主一律经 engine.interrupt / prepareInterrupt");
    }

    private static List<Path> javaFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }
}
