package features.ai.harness.all;


import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.host.HarnessHost;
import org.noear.solon.ai.harness.host.HostSink;
import org.noear.solon.ai.harness.host.RunSink;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 防锁定守卫：HarnessHost 面向任意 agent / agent SaaS，不能长成某一个宿主（如 soloncode Web 端）的形状。
 *
 * <ol>
 *   <li>每个扩展点都必须被参考宿主 A（无状态 HTTP）或 B（文本 IM）用到——只被 C（多会话推送 + 每轮状态）
 *       用到的扩展点，说明它是为某种特定宿主加的，应删除或降回宿主自己的代码；</li>
 *   <li>主代码不得出现宿主专有词；</li>
 *   <li>{@link HostSink} / {@link RunSink} 没有抽象方法（{@code new HostSink(){}} 必须能跑通）；</li>
 *   <li>规模上限：Builder 配置方法 ≤ 6、{@link HostSink} 回调 ≤ 11、{@link RunSink} 回调 ≤ 4——超出先评审。</li>
 * </ol>
 */
class HostGenericityGuardTest {
    private static final Path TEST_DIR = Paths.get("src/test/java/features/ai/harness/all");
    private static final Path MAIN_DIR = Paths.get("src/main/java/org/noear/solon/ai/harness/host");

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    /** 参考宿主源码切片：A / B / C 三段（按嵌套类声明切开） */
    private static String section(String src, String className, String nextClassName) {
        int from = src.indexOf("static final class " + className);
        int to = nextClassName == null ? src.length() : src.indexOf("static final class " + nextClassName);
        assertTrue(from >= 0 && to > from, "参考宿主未定位到：" + className);
        return src.substring(from, to);
    }

    /** 扩展点 → 在参考宿主源码里的用法特征 */
    private static final String[][] EXTENSION_POINTS = {
            {"HostSink#admitted", "void admitted("},
            {"HostSink#reply|RunSink#reply", "void reply("},
            {"HostSink#deferred", "void deferred("},
            {"HostSink#steered", "void steered("},
            {"HostSink#rejected", "void rejected("},
            {"HostSink#notOwner", "void notOwner("},
            {"HostSink#hitl", "void hitl("},
            {"HostSink#hitlExpired", "void hitlExpired("},
            {"HostSink#open", "RunSink open("},
            {"HostSink#event|runFinished|RunSink#event|finished", "void runFinished(|void finished(|void event("},
            {"RunSink#prepare", "void prepare("},
            {"Builder#wrap", ".wrap("},
            {"Builder#input", ".input("},
            {"Builder#programRuns", ".programRuns("},
            {"HarnessHost#submit|HarnessHost#stream", "), new RunSink() {"},
    };

    @Test
    void everyExtensionPointServesANonPushHost() throws IOException {
        String src = read(TEST_DIR.resolve("ReferenceHosts.java"));
        String a = section(src, "SseChatSample", "ImBotSample");
        String b = section(src, "ImBotSample", "PushWebSample");

        List<String> onlyForC = new ArrayList<>();
        for (String[] point : EXTENSION_POINTS) {
            boolean used = false;
            for (String token : point[1].split("\\|")) {
                if (a.contains(token) || b.contains(token)) {
                    used = true;
                    break;
                }
            }
            if (used == false) {
                onlyForC.add(point[0]);
            }
        }
        assertTrue(onlyForC.isEmpty(), "这些扩展点只有推送型宿主用到（删除，或降回宿主自己的代码）：" + onlyForC);
    }

    @Test
    void extensionPointListCoversThePublicSurface() {
        // 新增公开回调 / 配置时，必须同步登记到 EXTENSION_POINTS（否则上一条守卫形同虚设）
        Set<String> registered = Arrays.stream(EXTENSION_POINTS).map(p -> p[0]).collect(Collectors.toSet());
        String all = String.join("|", registered);
        List<String> missing = new ArrayList<>();
        for (String m : callbacks(HostSink.class)) {
            if (all.contains("HostSink#" + m) == false && all.contains("|" + m) == false) missing.add("HostSink#" + m);
        }
        for (String m : callbacks(RunSink.class)) {
            if (all.contains("RunSink#" + m) == false && all.contains("|" + m) == false) missing.add("RunSink#" + m);
        }
        for (String m : builderMethods()) {
            if (all.contains("Builder#" + m) == false && m.equals("engines") == false && m.equals("sink") == false
                    && m.equals("executor") == false) {
                missing.add("Builder#" + m);
            }
        }
        for (Method m : HarnessHost.class.getDeclaredMethods()) {
            // 带「本次监听」的提交入口也是扩展点
            if (Modifier.isPublic(m.getModifiers()) && Arrays.asList(m.getParameterTypes()).contains(RunSink.class)
                    && all.contains("HarnessHost#" + m.getName()) == false) {
                missing.add("HarnessHost#" + m.getName());
            }
        }
        assertTrue(missing.isEmpty(), "未登记的扩展点：" + missing);
    }

    @Test
    void sinksHaveNoAbstractMethods() {
        for (Class<?> type : Arrays.asList(HostSink.class, RunSink.class)) {
            for (Method m : type.getDeclaredMethods()) {
                assertTrue(Modifier.isAbstract(m.getModifiers()) == false,
                        type.getSimpleName() + "#" + m.getName() + " 必须有缺省实现");
            }
        }
    }

    @Test
    void surfaceStaysSmall() {
        assertTrue(builderMethods().size() <= 6, "Builder 配置方法超过 6 个，先评审：" + builderMethods());
        assertTrue(callbacks(HostSink.class).size() <= 11, "HostSink 回调超过 11 个，先评审：" + callbacks(HostSink.class));
        assertTrue(callbacks(RunSink.class).size() <= 4, "RunSink 回调超过 4 个，先评审：" + callbacks(RunSink.class));
    }

    @Test
    void mainCodeHasNoHostSpecificWords() throws IOException {
        Pattern words = Pattern.compile("(?i)soloncode|workspace|WebEventMapper|SAEP");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> s = Files.walk(MAIN_DIR)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList())) {
                Matcher m = words.matcher(read(f));
                while (m.find()) {
                    violations.add(f.getFileName() + ": " + m.group());
                }
            }
        }
        assertTrue(violations.isEmpty(), "主代码不得出现宿主专有词：" + violations);
    }

    /**
     * 术语：一次输入叫 HostInput、一次开流叫 HostRun。Turn 与 Run 近义（steer 的 RUN_CHANGED、ReAct 的 maxTurns
     * 里 turn 都指运行或推理步），不再用 Turn 命名宿主层类型。
     */
    @Test
    void hostTurnStaysRetired() throws IOException {
        assertFalse(Files.exists(MAIN_DIR.resolve("HostTurn.java")), "HostTurn 已改名 HostInput");
        Pattern words = Pattern.compile("\\bHostTurn\\b|\\bgetTurn\\(");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> s = Files.walk(MAIN_DIR)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList())) {
                if (words.matcher(read(f)).find()) {
                    violations.add(f.getFileName().toString());
                }
            }
        }
        assertTrue(violations.isEmpty(), "主代码不得复活 HostTurn / getTurn：" + violations);
    }

    @Test
    void submitSpecStaysRetired() throws IOException {
        // 提交只有一个输入类型：HarnessInput（与直连 engine.handle 相同），不再有第二套描述对象
        assertFalse(Files.exists(MAIN_DIR.resolve("SubmitSpec.java")), "SubmitSpec 已并入 HarnessInput");
        Pattern words = Pattern.compile("\\bSubmitSpec\\b|\\.customize\\(");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> s = Files.walk(MAIN_DIR)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList())) {
                if (words.matcher(read(f)).find()) {
                    violations.add(f.getFileName().toString());
                }
            }
        }
        assertTrue(violations.isEmpty(), "主代码不得复活 SubmitSpec / customize：" + violations);
    }

    private static Set<String> callbacks(Class<?> type) {
        Set<String> names = new LinkedHashSet<>();
        for (Method m : type.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) == false && Modifier.isPublic(m.getModifiers())) {
                names.add(m.getName());
            }
        }
        return names;
    }

    private static Set<String> builderMethods() {
        Set<String> names = new LinkedHashSet<>();
        for (Method m : HarnessHost.Builder.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && m.getName().equals("build") == false) {
                names.add(m.getName());
            }
        }
        return names;
    }
}
