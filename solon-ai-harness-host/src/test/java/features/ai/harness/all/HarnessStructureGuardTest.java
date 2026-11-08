package features.ai.harness.all;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 *   <li>{@code @since} 不超过当前版本线（4.2.0）；</li>
 *   <li>嵌套的公开枚举只属于外层类型：出现在别的类型的公开签名里就应独立为顶层枚举（反射检查）。</li>
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

        // 聚合包自身没有测试：在这里确认它只引 host，而 host 引了核心与全部插件（依赖清单只维护一处）
        String pom = read(BASE.resolve("solon-ai-harness").resolve("pom.xml"));
        assertTrue(pom.contains("<artifactId>solon-ai-harness-host</artifactId>"), "聚合包 pom 必须引用 host");
        for (String module : Arrays.asList("core", "goal", "loop", "queue", "steer")) {
            assertFalse(pom.contains("<artifactId>solon-ai-harness-" + module + "</artifactId>"),
                    "聚合包不要重复列出 host 已传递的模块：" + module);
        }
        String hostPom = read(BASE.resolve("solon-ai-harness-host").resolve("pom.xml"));
        List<String> missing = new ArrayList<>();
        for (String module : Arrays.asList("core", "goal", "loop", "queue", "steer")) {
            if (hostPom.contains("<artifactId>solon-ai-harness-" + module + "</artifactId>") == false) {
                missing.add(module);
            }
        }
        assertTrue(missing.isEmpty(), "host pom 漏引用：" + missing);
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
        Pattern internal = Pattern.compile("^public\\s+(final\\s+)?(class|interface)\\s+(\\w+(Runtime|Store|Mailbox|Scheduler|Attrs|Limits))\\b",
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

    /** 注释去掉后的源码（判断「字面量里有没有用户文案」时只用它） */
    private static String codeOnly(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    /**
     * 宿主骨架不得替宿主做「呈现」决定：主代码的字符串字面量里不能出现中文（日志与错误码用英文，
     * 用户文案由 {@code HostSink} 的结构化回调交给宿主决定）。
     */
    @Test
    void hostMainHasNoUserFacingText() throws IOException {
        Pattern cjkLiteral = Pattern.compile("\"[^\"]*[\\u4e00-\\u9fa5][^\"]*\"");
        List<String> violations = new ArrayList<>();
        for (Path f : javaFiles(mainOf("solon-ai-harness-host"))) {
            Matcher m = cjkLiteral.matcher(codeOnly(read(f)));
            while (m.find()) {
                violations.add(f.getFileName() + ": " + m.group());
            }
        }
        assertTrue(violations.isEmpty(),
                "框架不自带用户文案（文案归宿主 / i18n），改用结构化回调或英文日志：" + violations);
    }

    /**
     * 鉴权归宿主：框架拿不到角色、租户成员关系、限流等上下文，鉴权钩子会变成一条重复路径，
     * 而且默认值只能是 fail-open。不要再把鉴权拉回框架。
     */
    @Test
    void adminAuthorizerStaysRetired() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path f : javaFiles(mainOf("solon-ai-harness-host"))) {
            if (f.getFileName().toString().equals("AdminAuthorizer.java")) {
                violations.add("AdminAuthorizer：鉴权请在控制器 / 拦截器层完成");
            }
            if (codeOnly(read(f)).contains("AdminAuthorizer")) {
                violations.add(f.getFileName() + " 仍在引用 AdminAuthorizer");
            }
        }
        assertTrue(violations.isEmpty(), violations.toString());
    }

    /**
     * 枚举放哪里（与 JDK 一致）：只被一个类型使用的枚举嵌套在它里面（如 {@code Thread.State}）；
     * 出现在两个以上类型的公开签名里的放到顶层（如 {@code TimeUnit}；本项目的 {@code RejectReason}、
     * {@code BusyPolicy}、{@code Disposition}、{@code InterruptMode}、{@code DeployMode}）。
     *
     * <p>反例：回调里没有 {@code SubmitResult}，却要写 {@code input.getKind() == SubmitResult.Kind.STARTED}。
     * 同一外层类型的嵌套类（如 {@code HarnessEnginePool.Builder}）算同一个类型。</p>
     */
    @Test
    void nestedPublicEnumsStayWithTheirOwner() throws Exception {
        List<Class<?>> types = new ArrayList<>();
        List<Path> roots = new ArrayList<>();
        roots.add(mainOf("solon-ai-harness-core"));
        for (String plugin : PLUGINS) {
            roots.add(mainOf("solon-ai-harness-" + plugin));
        }
        roots.add(mainOf("solon-ai-harness-host"));
        ClassLoader cl = getClass().getClassLoader();
        for (Path root : roots) {
            for (Path f : javaFiles(root)) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                if (rel.endsWith("package-info.java")) {
                    continue;
                }
                String name = rel.substring(0, rel.length() - ".java".length()).replace('/', '.');
                collectPublicTypes(Class.forName(name, false, cl), types);
            }
        }
        assertTrue(types.size() > 50, "公开类型未定位到：" + types.size());

        Set<String> violations = new TreeSet<>();
        for (Class<?> owner : types) {
            Set<Type> used = new LinkedHashSet<>();
            for (Method m : owner.getDeclaredMethods()) {
                if (isExposed(m.getModifiers()) && m.isSynthetic() == false) {
                    used.add(m.getGenericReturnType());
                    used.addAll(Arrays.asList(m.getGenericParameterTypes()));
                }
            }
            for (Constructor<?> c : owner.getDeclaredConstructors()) {
                if (isExposed(c.getModifiers()) && c.isSynthetic() == false) {
                    used.addAll(Arrays.asList(c.getGenericParameterTypes()));
                }
            }
            for (Field fd : owner.getDeclaredFields()) {
                if (isExposed(fd.getModifiers()) && fd.isSynthetic() == false) {
                    used.add(fd.getGenericType());
                }
            }
            Set<Class<?>> raw = new LinkedHashSet<>();
            for (Type t : used) {
                rawClasses(t, raw);
            }
            for (Class<?> e : raw) {
                if (e.isEnum() && e.getEnclosingClass() != null && Modifier.isPublic(e.getModifiers())
                        && outermost(e) != outermost(owner)) {
                    violations.add(e.getName().replace('$', '.') + " ← " + owner.getName().replace('$', '.'));
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "嵌套公开枚举出现在别的类型的公开签名里，应独立为顶层枚举：" + violations);
    }

    private static boolean isExposed(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static void collectPublicTypes(Class<?> type, List<Class<?>> out) {
        if (Modifier.isPublic(type.getModifiers()) == false) {
            return;
        }
        out.add(type);
        for (Class<?> nested : type.getDeclaredClasses()) {
            collectPublicTypes(nested, out);
        }
    }

    private static Class<?> outermost(Class<?> type) {
        Class<?> c = type;
        while (c.getEnclosingClass() != null) {
            c = c.getEnclosingClass();
        }
        return c;
    }

    private static void rawClasses(Type t, Set<Class<?>> out) {
        if (t instanceof Class) {
            Class<?> c = (Class<?>) t;
            while (c.isArray()) {
                c = c.getComponentType();
            }
            out.add(c);
        } else if (t instanceof ParameterizedType) {
            ParameterizedType p = (ParameterizedType) t;
            rawClasses(p.getRawType(), out);
            for (Type a : p.getActualTypeArguments()) {
                rawClasses(a, out);
            }
        } else if (t instanceof GenericArrayType) {
            rawClasses(((GenericArrayType) t).getGenericComponentType(), out);
        } else if (t instanceof WildcardType) {
            for (Type b : ((WildcardType) t).getUpperBounds()) {
                rawClasses(b, out);
            }
            for (Type b : ((WildcardType) t).getLowerBounds()) {
                rawClasses(b, out);
            }
        }
    }
}
