package features.ai.harness.all;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * harness 跨模块结构守护（core + goal/loop/queue/steer 四插件）。
 *
 * <ol>
 *   <li>依赖方向：core 不 import 任何插件；插件之间零 import（只经 core 契约协作：钩子、spi、会话槽位）；</li>
 *   <li>插件内部实现类（{@code *Runtime/*Store/*Mailbox/*Scheduler}）不得 public——宿主只经 {@code XxxExtension} 门面；</li>
 *   <li>未发布前不留兼容层：主代码无 {@code @Deprecated}；</li>
 *   <li>{@code @since} 不超过当前版本线（4.2.0）。</li>
 * </ol>
 *
 * <p>纯文本扫描（聚合模块的工作目录是 {@code solon-ai-harness}，兄弟模块源码按相对路径定位）。</p>
 */
class HarnessStructureGuardTest {
    private static final Path BASE = Paths.get("..").toAbsolutePath().normalize();
    private static final String PKG = "org/noear/solon/ai/harness";
    private static final List<String> PLUGINS = Arrays.asList("goal", "loop", "queue", "steer");

    private static Path mainOf(String module) {
        return BASE.resolve(module).resolve("src/main/java");
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        if (Files.isDirectory(root) == false) {
            return new ArrayList<>();
        }
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static List<Path> allMainFiles() throws IOException {
        List<Path> all = new ArrayList<>(javaFiles(mainOf("solon-ai-harness-core")));
        for (String plugin : PLUGINS) {
            all.addAll(javaFiles(mainOf("solon-ai-harness-" + plugin)));
        }
        return all;
    }

    @Test
    void sourcesAreReachable() throws IOException {
        assertTrue(javaFiles(mainOf("solon-ai-harness-core")).size() > 50, "core 源码未定位到：" + BASE);
        for (String plugin : PLUGINS) {
            assertTrue(javaFiles(mainOf("solon-ai-harness-" + plugin)).isEmpty() == false, plugin + " 源码未定位到");
        }
    }

    @Test
    void coreDoesNotDependOnPlugins() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path f : javaFiles(mainOf("solon-ai-harness-core"))) {
            String src = read(f);
            for (String plugin : PLUGINS) {
                if (src.contains("import org.noear.solon.ai.harness.extension." + plugin + ".")) {
                    violations.add(f.getFileName() + " → " + plugin);
                }
            }
        }
        assertTrue(violations.isEmpty(), "core 不得依赖插件：" + violations);
    }

    @Test
    void pluginsDoNotDependOnEachOther() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String self : PLUGINS) {
            for (Path f : javaFiles(mainOf("solon-ai-harness-" + self))) {
                String src = read(f);
                for (String other : PLUGINS) {
                    if (other.equals(self) == false
                            && src.contains("org.noear.solon.ai.harness.extension." + other + ".")) {
                        violations.add(self + "/" + f.getFileName() + " → " + other);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "插件之间不得互相依赖（经 core 契约协作）：" + violations);
    }

    @Test
    void pluginInternalsAreNotPublic() throws IOException {
        Pattern internal = Pattern.compile("^public\\s+(final\\s+)?(class|interface)\\s+(\\w+(Runtime|Store|Mailbox|Scheduler))\\b",
                Pattern.MULTILINE);
        List<String> violations = new ArrayList<>();
        for (String plugin : PLUGINS) {
            for (Path f : javaFiles(mainOf("solon-ai-harness-" + plugin))) {
                Matcher m = internal.matcher(read(f));
                if (m.find()) {
                    violations.add(plugin + "/" + m.group(3));
                }
            }
        }
        assertTrue(violations.isEmpty(), "插件内部实现类不得 public（宿主只经门面）：" + violations);
    }

    @Test
    void noDeprecatedInMainCode() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path f : allMainFiles()) {
            for (String line : read(f).split("\n")) {
                String t = line.trim();
                if (t.startsWith("@Deprecated") || t.contains("@deprecated")) {
                    violations.add(f.getFileName().toString());
                    break;
                }
            }
        }
        assertTrue(violations.isEmpty(), "未发布前不留兼容层，主代码不得有废弃标记：" + violations);
    }

    @Test
    void sinceDoesNotExceedCurrentVersionLine() throws IOException {
        Pattern since = Pattern.compile("@since\\s+(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");
        List<String> violations = new ArrayList<>();
        for (Path f : allMainFiles()) {
            Matcher m = since.matcher(read(f));
            while (m.find()) {
                int major = Integer.parseInt(m.group(1));
                int minor = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
                int patch = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
                boolean future = major > 4 || (major == 4 && minor > 2) || (major == 4 && minor == 2 && patch > 0);
                if (future) {
                    violations.add(f.getFileName() + ": " + m.group());
                }
            }
        }
        assertTrue(violations.isEmpty(), "@since 不得超过 4.2.0：" + violations);
    }
}
