package features.ai.harness.all;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * harness 跨模块结构守护（core + goal/loop/queue/steer 四插件 + 宿主骨架 host + 聚合包）。
 *
 * <ol>
 *   <li>依赖方向：core 不 import 任何插件；插件之间零 import（只经 core 契约协作：钩子、spi、会话槽位）；
 *       host 模块主代码只在 {@code host} 包（可以依赖全部插件），core 与插件不得反向依赖它；</li>
 *   <li>聚合包 {@code solon-ai-harness} 只做引用：没有任何源码；</li>
 *   <li>插件内部实现类（{@code *Runtime/*Store/*Mailbox/*Scheduler}）不得 public——宿主只经 {@code XxxExtension} 门面；</li>
 *   <li>插件门面写方法统一返回 {@code HarnessResult}：不用 {@code int} 哨兵值或 {@code boolean} 表达业务失败；</li>
 *   <li>未发布前不留兼容层：主代码无 {@code @Deprecated}（白名单除外）；</li>
 *   <li>{@code @since} 不超过当前版本线（4.2.0）。</li>
 * </ol>
 *
 * <p>纯文本扫描（本模块的工作目录是 {@code solon-ai-harness-host}，兄弟模块源码按相对路径定位）。</p>
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
        all.addAll(javaFiles(mainOf("solon-ai-harness-host")));
        return all;
    }

    @Test
    void aggregateHasNoSources() throws IOException {
        Path src = BASE.resolve("solon-ai-harness").resolve("src");
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(src)) {
            try (Stream<Path> s = Files.walk(src)) {
                s.filter(Files::isRegularFile).forEach(files::add);
            }
        }
        assertTrue(Files.isRegularFile(BASE.resolve("solon-ai-harness").resolve("pom.xml")), "聚合包未定位到：" + BASE);
        assertTrue(files.isEmpty(), "聚合包只做引用，不得有源码（宿主代码放 solon-ai-harness-host）：" + files);

        // 聚合包自身没有测试：在这里确认它引用了核心、全部插件与宿主骨架
        String pom = read(BASE.resolve("solon-ai-harness").resolve("pom.xml"));
        List<String> missing = new ArrayList<>();
        for (String module : Arrays.asList("core", "goal", "loop", "queue", "steer", "host")) {
            if (pom.contains("<artifactId>solon-ai-harness-" + module + "</artifactId>") == false) {
                missing.add(module);
            }
        }
        assertTrue(missing.isEmpty(), "聚合包 pom 漏引用：" + missing);
    }

    /** 插件门面的写方法名（状态变更类）；读方法（get/list/has*）与续发机制（claimNext/drainLoop/offer）不在其列 */
    private static final Pattern FACADE_WRITE = Pattern.compile(
            "^    public\\s+(?!static)([\\w<>?, ]+?)\\s+(create|update|schedule|pause|resume|trigger|remove|removeAll|enqueue"
                    + "|cancelItem|promote|cancelPending|clearPending|importItems)\\(", Pattern.MULTILINE);

    @Test
    void pluginFacadeWritesReturnHarnessResult() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String plugin : Arrays.asList("goal", "loop", "queue")) {
            for (Path f : javaFiles(mainOf("solon-ai-harness-" + plugin))) {
                if (f.getFileName().toString().endsWith("Extension.java") == false) {
                    continue;
                }
                Matcher m = FACADE_WRITE.matcher(read(f));
                while (m.find()) {
                    if (m.group(1).trim().startsWith("HarnessResult") == false) {
                        violations.add(plugin + "/" + m.group(2) + " → " + m.group(1).trim());
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "插件门面写方法必须返回 HarnessResult：" + violations);
    }

    @Test
    void hostLayerIsTopOnly() throws IOException {
        List<String> violations = new ArrayList<>();
        Path hostRoot = mainOf("solon-ai-harness-host");
        assertTrue(javaFiles(hostRoot).isEmpty() == false, "host 源码未定位到：" + hostRoot);
        for (Path f : javaFiles(hostRoot)) {
            String rel = hostRoot.relativize(f).toString().replace('\\', '/');
            if (rel.startsWith(PKG + "/host/") == false) {
                violations.add("host 模块主代码只能在 host 包：" + rel);
            }
        }
        List<Path> lower = new ArrayList<>(javaFiles(mainOf("solon-ai-harness-core")));
        for (String plugin : PLUGINS) {
            lower.addAll(javaFiles(mainOf("solon-ai-harness-" + plugin)));
        }
        for (Path f : lower) {
            if (read(f).contains("org.noear.solon.ai.harness.host.")) {
                violations.add("core/插件不得依赖 host：" + f.getFileName());
            }
        }
        assertTrue(violations.isEmpty(), violations.toString());
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

    /**
     * 白名单：4.2.0 之前已发布、必须保留的旧入口（文件名 → 废弃标记之后紧跟的方法签名前缀）。
     * 只有「以前版本就有、向下兼容需要」的方法才允许进入；4.2.0 新增的 API 未发布，直接改名或删除。
     */
    private static final List<String> DEPRECATED_ALLOWED = Arrays.asList(
            "HarnessEngine.java#public ReActRequest prompt(",
            "HarnessEngine.java#public ReActRequest resume(");

    /**
     * 白名单：4.1.x 已发布、4.2.0 收进视图（{@code getTalents()} / {@code getSettings()}）的引擎方法，
     * 保留一个版本的委托桥接（4.3.0 删除）。只列方法名：同名重载（如 setHttpProxy）一并放行。
     */
    private static final Set<String> ENGINE_MOVED_TO_VIEWS = new HashSet<>(Arrays.asList((
            "getTerminalTalent getSkillTalent getTodoTalent getTaskTalent getCodeTalent getMemoryTalent "
                    + "getGenerateTalent getCodeSearchTalent getClockTalent getWebsearchTalent getWebfetchTalent "
                    + "getMcpGatewayTalent getOpenApiGatewayTalent getLspTalent "
                    + "getHarnessHome getHarnessSessions getHarnessSkills getHarnessAgents getHarnessCommands "
                    + "getHarnessMemory getHarnessChannels getUserDir getUserHome getUserAgent getCacheControl "
                    + "getSystemPrompt getMaxTurns isAutoRethink getSessionWindowSize getCompressionMaxMessages "
                    + "getCompressionMaxContextRatio getCompressionDefaultContextLength isMemoryEnabled "
                    + "isSandboxEnabled isHitlEnabled isSubagentEnabled isBashAsyncEnabled getCommandPrefix "
                    + "getAgentPrefix getApiRetries getMcpRetries getModelRetries setMaxTurns setModelRetries "
                    + "setMcpRetries setApiRetries setMemoryEnabled setMemoryRelevanceCount setMemoryPriorityCount "
                    + "setMemorySummaryLength setSandboxEnabled setSandboxAllowUserHome isSandboxSystemRestrict "
                    + "setSandboxSystemRestrict setHitlEnabled setSubagentEnabled getSubagentDefaultConcurrency "
                    + "setSubagentDefaultConcurrency setBashAsyncEnabled setSessionWindowSize "
                    + "setCompressionThreshold setCompressionDefaultContextLength setSystemPrompt setCacheControl "
                    + "setHttpProxy getHttpProxy addHttpCustomize setHttpCustomize getHttpCustomize").split(" ")));

    private static final Pattern PUBLIC_METHOD = Pattern.compile("public [\\w<>?,. \\[\\]]+? (\\w+)\\(");

    @Test
    void noDeprecatedInMainCode() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path f : allMainFiles()) {
            String name = f.getFileName().toString();
            String[] lines = read(f).split("\n");
            for (int i = 0; i < lines.length; i++) {
                String t = lines[i].trim();
                if (t.startsWith("@Deprecated") || t.contains("@deprecated")) {
                    if (isAllowedDeprecation(name, lines, i) == false) {
                        violations.add(name + ":" + (i + 1));
                        break;
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "未发布前不留兼容层，主代码不得有废弃标记（白名单除外）：" + violations);
    }

    /** 废弃标记之后的第一行声明是否命中白名单 */
    private static boolean isAllowedDeprecation(String fileName, String[] lines, int from) {
        for (int j = from + 1; j < lines.length; j++) {
            String t = lines[j].trim();
            if (t.startsWith("public ") || t.startsWith("protected ")) {
                if ("HarnessEngine.java".equals(fileName)) {
                    Matcher m = PUBLIC_METHOD.matcher(t);
                    if (m.lookingAt() && ENGINE_MOVED_TO_VIEWS.contains(m.group(1))) {
                        return true;
                    }
                }
                for (String allowed : DEPRECATED_ALLOWED) {
                    int sep = allowed.indexOf('#');
                    if (allowed.substring(0, sep).equals(fileName) && t.startsWith(allowed.substring(sep + 1))) {
                        return true;
                    }
                }
                return false;
            }
        }
        return false;
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
