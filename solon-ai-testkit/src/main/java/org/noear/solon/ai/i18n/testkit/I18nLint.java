package org.noear.solon.ai.i18n.testkit;

import org.junit.jupiter.api.Assertions;
import org.noear.solon.ai.i18n.I18nMessages;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 模块文案资源的格式与一致性检查（各模块的 lint 用例复用）
 *
 * <ul>
 *     <li>书写格式：每个值都是扁平 key 的 "|-" 块标量，缩进 2 空格，无 Tab、无行尾空白、无重复 key、无行首 "---"；
 *     不得存在同名 .properties（会优先加载，让 yml 静默失效）；</li>
 *     <li>值：只写正文——不含首尾空白（段落间的换行、空格由代码负责）、不含 "${" / "#{"、英文包不含中文；
 *     花括号只能是命名占位符 {name}、JSON 示例 {"...、{...} 或 {}；</li>
 *     <li>跨语言：key 集合一致，每个 key 的占位符名字集合一致；</li>
 *     <li>key 常量：常量类与资源中的 key 完全一致，常量名 = key 转大写（"." 换成 "_"），每个常量都在产品代码中使用；
 *     产品代码不得直接写 key 字面量；</li>
 *     <li>调用点：门面的 fmt / get 的 key 必须是常量；fmt 的变量名必须是字面量、不重复、与占位符完全一致
 *     （Map 形式的 fmt 不检查变量名，由渲染快照兜底）；get 取的文案不得含占位符；</li>
 *     <li>工具：工具描述与参数说明只给模型看，留在 {@code @ToolMapping} / {@code @Param} 注解里，不进文案资源
 *     （资源中不得出现 {@code tool.} 开头的 key）。</li>
 * </ul>
 */
public final class I18nLint {
    /**
     * 工具描述 key 的前缀（已弃用的约定；资源中出现即报错）
     */
    public static final String TOOL_PREFIX = "tool.";

    static final String[] LANGS = {"", "_zh"};
    static final Pattern KEY_LINE = Pattern.compile("^([A-Za-z0-9_.\\-]+): \\|-$");
    static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)\\}");
    static final Pattern KEY_LITERAL = Pattern.compile("\"([a-z]+(?:\\.[a-z0-9_]+)+)\"");

    private final I18nMessages messages;
    private final Class<?> keysClass;
    private final String facade;
    private Path mainSrc = Paths.get("src/main/java");
    private int minFmt;
    private int minGet;

    /**
     * @param messages  被检查的文案实例
     * @param keysClass key 常量类
     * @param facade    门面类的简单名（调用点形如 {@code Facade.get(} / {@code Facade.fmt(}）
     */
    public I18nLint(I18nMessages messages, Class<?> keysClass, String facade) {
        this.messages = messages;
        this.keysClass = keysClass;
        this.facade = facade;
    }

    public I18nLint mainSrc(Path mainSrc) {
        this.mainSrc = mainSrc;
        return this;
    }

    /**
     * 调用点数量下限（防止扫描规则失效后“零调用也通过”）
     */
    public I18nLint minCalls(int minFmt, int minGet) {
        this.minFmt = minFmt;
        this.minGet = minGet;
        return this;
    }

    /**
     * 执行全部检查
     */
    public void verify() throws Exception {
        List<String> errors = new ArrayList<>();
        checkLayout(errors);
        checkValues(errors);
        checkKeyConstants(errors);
        checkCallSites(errors);
        Assertions.assertTrue(errors.isEmpty(), String.join("\n", errors));
    }

    // ---------------- 资源 ----------------

    void checkLayout(List<String> errors) throws IOException {
        for (String bundle : messages.bundles()) {
            for (String lang : LANGS) {
                if (resource(path(bundle, lang, ".properties")) != null) {
                    errors.add("must not exist: " + path(bundle, lang, ".properties"));
                }

                String path = path(bundle, lang, ".yml");
                List<String> lines = readLines(path);
                Set<String> keys = new HashSet<>();

                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String at = path + ":" + (i + 1) + " ";

                    if (line.indexOf('\t') >= 0) {
                        errors.add(at + "tab is not allowed");
                    }
                    if (line.endsWith(" ")) {
                        errors.add(at + "trailing space is not allowed");
                    }
                    if (line.startsWith("---")) {
                        errors.add(at + "line must not start with ---");
                    }

                    if (line.isEmpty() || line.startsWith("#") || line.startsWith("  ")) {
                        continue;
                    }

                    Matcher m = KEY_LINE.matcher(line);
                    if (m.matches() == false) {
                        errors.add(at + "expected 'flat.key: |-', got: " + line);
                    } else if (keys.add(m.group(1)) == false) {
                        errors.add(at + "duplicate key: " + m.group(1));
                    } else if (m.group(1).startsWith("solon.")) {
                        errors.add(at + "reserved key: " + m.group(1));
                    } else if (m.group(1).startsWith(TOOL_PREFIX)) {
                        errors.add(at + "tool descriptions stay in @ToolMapping / @Param, not in yml: " + m.group(1));
                    }
                }
            }
        }
    }

    void checkValues(List<String> errors) throws IOException {
        for (String bundle : messages.bundles()) {
            Map<String, String> en = loadYml(path(bundle, "", ".yml"));
            Map<String, String> zh = loadYml(path(bundle, "_zh", ".yml"));

            if (en.keySet().equals(zh.keySet()) == false) {
                errors.add(bundle + ": en / zh key sets differ: en=" + new TreeSet<>(en.keySet()) + ", zh=" + new TreeSet<>(zh.keySet()));
            }

            for (String key : en.keySet()) {
                if (zh.containsKey(key) && placeholders(en.get(key)).equals(placeholders(zh.get(key))) == false) {
                    errors.add(bundle + ":" + key + " placeholders differ between en and zh");
                }
            }

            for (Map<String, String> map : Arrays.asList(en, zh)) {
                for (Map.Entry<String, String> kv : map.entrySet()) {
                    String at = bundle + ":" + kv.getKey() + " ";
                    String val = kv.getValue();

                    if (val.trim().equals(val) == false) {
                        errors.add(at + "value must not have leading/trailing whitespace (add separators in code)");
                    }
                    if (val.contains("${") || val.contains("#{")) {
                        errors.add(at + "must not contain SnEL template marks");
                    }
                    checkBraces(errors, at, val);
                }
            }

            for (Map.Entry<String, String> kv : en.entrySet()) {
                if (kv.getValue().matches("(?s).*[\\u4e00-\\u9fa5].*")) {
                    errors.add(bundle + ":" + kv.getKey() + " (en) contains chinese: " + kv.getValue());
                }
            }
        }
    }

    // ---------------- key 常量与调用点 ----------------

    void checkKeyConstants(List<String> errors) throws Exception {
        Map<String, String> constants = keyConstants();

        for (Map.Entry<String, String> kv : constants.entrySet()) {
            String expectedName = kv.getValue().toUpperCase(Locale.ROOT).replace('.', '_');
            if (expectedName.equals(kv.getKey()) == false) {
                errors.add("constant " + kv.getKey() + " should be named " + expectedName);
            }
        }

        Set<String> declared = new TreeSet<>(constants.values());
        for (Locale locale : new Locale[]{Locale.CHINESE, Locale.ENGLISH}) {
            Set<String> keys = new TreeSet<>(messages.messages(locale).keySet());

            if (keys.equals(declared) == false) {
                Set<String> noConst = new TreeSet<>(keys);
                noConst.removeAll(declared);
                Set<String> noRes = new TreeSet<>(declared);
                noRes.removeAll(keys);
                errors.add(locale + ": keys without constant " + noConst + "; constants without resource " + noRes);
            }
        }

        String keysFile = keysClass.getSimpleName() + ".java";
        StringBuilder all = new StringBuilder();
        for (Path file : mainSources()) {
            String src = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (file.getFileName().toString().equals(keysFile)) {
                continue;
            }
            all.append(src);

            Matcher m = KEY_LITERAL.matcher(src);
            while (m.find()) {
                if (constants.containsValue(m.group(1))) {
                    errors.add(file + ": use " + keysClass.getSimpleName() + " instead of literal \"" + m.group(1) + "\"");
                }
            }
        }

        for (String name : constants.keySet()) {
            if (Pattern.compile("\\b" + keysClass.getSimpleName() + "\\." + name + "\\b").matcher(all).find() == false) {
                errors.add("unused key constant (remove it from " + keysClass.getSimpleName() + " and the yml files): " + name);
            }
        }
    }

    void checkCallSites(List<String> errors) throws Exception {
        Map<String, String> zh = messages.messages(Locale.CHINESE);
        Map<String, String> en = messages.messages(Locale.ENGLISH);
        Map<String, String> constants = keyConstants();
        int fmtCount = 0;
        int getCount = 0;

        for (Path file : mainSources()) {
            String src = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);

            for (List<String> args : calls(src, facade + ".fmt(")) {
                fmtCount++;
                String key = keyOf(args.get(1), constants);
                if (key == null) {
                    errors.add(file + ": fmt key must be a " + keysClass.getSimpleName() + " constant: " + args.get(1));
                    continue;
                }

                if (args.size() == 3) {
                    //Map 形式：变量名由渲染快照兜底
                    continue;
                }

                Set<String> names = new TreeSet<>();
                for (int i = 2; i < args.size(); i += 2) {
                    String name = literal(args.get(i));
                    if (name == null) {
                        errors.add(file + ": fmt var name must be a string literal: " + key + " -> " + args.get(i));
                    } else if (names.add(name) == false) {
                        errors.add(file + ": fmt var name is duplicated: " + key + " -> " + name);
                    }
                }

                for (Map<String, String> msgs : Arrays.asList(zh, en)) {
                    String val = msgs.get(key);
                    if (val == null) {
                        errors.add(file + ": missing key " + key);
                    } else if (placeholders(val).equals(names) == false) {
                        errors.add(file + ": " + key + " expects " + placeholders(val) + " but got " + names);
                    }
                }
            }

            for (List<String> args : calls(src, facade + ".get(")) {
                getCount++;
                String key = keyOf(args.get(1), constants);
                if (key == null) {
                    errors.add(file + ": get key must be a " + keysClass.getSimpleName() + " constant: " + args.get(1));
                    continue;
                }

                for (Map<String, String> msgs : Arrays.asList(zh, en)) {
                    String val = msgs.get(key);
                    if (val == null) {
                        errors.add(file + ": missing key " + key);
                    } else if (placeholders(val).isEmpty() == false) {
                        errors.add(file + ": " + key + " has placeholders " + placeholders(val) + ", use fmt instead of get");
                    }
                }
            }
        }

        if (fmtCount < minFmt || getCount < minGet) {
            errors.add("call site scan looks broken: fmt=" + fmtCount + " (min " + minFmt + "), get=" + getCount + " (min " + minGet + ")");
        }
    }

    // ---------------- helpers ----------------

    Map<String, String> keyConstants() throws IllegalAccessException {
        Map<String, String> map = new LinkedHashMap<>();
        for (Field f : keysClass.getDeclaredFields()) {
            int mod = f.getModifiers();
            if (Modifier.isPublic(mod) && Modifier.isStatic(mod) && Modifier.isFinal(mod) && f.getType() == String.class) {
                map.put(f.getName(), (String) f.get(null));
            }
        }
        return map;
    }

    String keyOf(String arg, Map<String, String> constants) {
        String s = arg.trim();
        String prefix = keysClass.getSimpleName() + ".";
        return s.startsWith(prefix) ? constants.get(s.substring(prefix.length())) : null;
    }

    List<Path> mainSources() throws IOException {
        try (Stream<Path> s = Files.walk(mainSrc)) {
            return s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }

    static String path(String bundle, String lang, String ext) {
        return bundle.replace('.', '/') + lang + ext;
    }

    static URL resource(String path) {
        return I18nLint.class.getClassLoader().getResource(path);
    }

    static List<String> readLines(String path) throws IOException {
        URL url = resource(path);
        Assertions.assertNotNull(url, "missing resource: " + path);
        try (InputStream in = url.openStream()) {
            //CRLF 由 yml 解析器归一为 \n，这里按行检查时一并忽略
            String text = new String(readAll(in), StandardCharsets.UTF_8).replace("\r\n", "\n");
            return new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, String> loadYml(String path) throws IOException {
        URL url = resource(path);
        Assertions.assertNotNull(url, "missing resource: " + path);
        try (InputStream in = url.openStream()) {
            Object obj = new Yaml().load(new InputStreamReader(in, StandardCharsets.UTF_8));
            Map<String, String> map = new LinkedHashMap<>();
            for (Map.Entry<Object, Object> kv : ((Map<Object, Object>) obj).entrySet()) {
                Assertions.assertTrue(kv.getValue() instanceof String, path + ":" + kv.getKey() + " must be a string");
                map.put(String.valueOf(kv.getKey()), (String) kv.getValue());
            }
            return map;
        }
    }

    public static Set<String> placeholders(String val) {
        Set<String> set = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(val);
        while (m.find()) {
            set.add(m.group(1));
        }
        return set;
    }

    /**
     * 允许的字面花括号：代码块 `{ cmd }`（花括号后紧跟空格）与 glob 备选 `{ts,tsx}`（内含逗号）；二者都不是合法占位符名，格式化时原样保留
     */
    static final Pattern LITERAL_BRACE = Pattern.compile("\\{( |[^{}\\s,]+(,[^{}\\s,]+)+\\})");

    static void checkBraces(List<String> errors, String at, String val) {
        for (int i = val.indexOf('{'); i >= 0; i = val.indexOf('{', i + 1)) {
            String rest = val.substring(i);
            boolean ok = PLACEHOLDER.matcher(rest).lookingAt() || rest.startsWith("{\"") || rest.startsWith("{...}") || rest.startsWith("{}")
                    || LITERAL_BRACE.matcher(rest).lookingAt();
            if (ok == false) {
                errors.add(at + "unexpected brace at " + i + ": " + rest.substring(0, Math.min(20, rest.length())));
            }
        }
    }

    static String literal(String arg) {
        String s = arg.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") && s.indexOf('"', 1) == s.length() - 1) {
            return s.substring(1, s.length() - 1);
        }
        return null;
    }

    /**
     * 找出源码中某方法的所有调用，返回顶层参数列表（处理字符串、字符字面量与嵌套括号）
     */
    static List<List<String>> calls(String src, String head) {
        List<List<String>> result = new ArrayList<>();
        int from = 0;
        while (true) {
            int start = src.indexOf(head, from);
            if (start < 0) {
                return result;
            }

            int open = start + head.length() - 1;
            int depth = 0;
            int argStart = open + 1;
            List<String> args = new ArrayList<>();
            int j = open;
            for (; j < src.length(); j++) {
                char c = src.charAt(j);
                if (c == '"' || c == '\'') {
                    j++;
                    while (src.charAt(j) != c) {
                        j += src.charAt(j) == '\\' ? 2 : 1;
                    }
                } else if (c == '(' || c == '[' || c == '{') {
                    depth++;
                } else if (c == ')' || c == ']' || c == '}') {
                    depth--;
                    if (depth == 0) {
                        args.add(src.substring(argStart, j));
                        break;
                    }
                } else if (c == ',' && depth == 1) {
                    args.add(src.substring(argStart, j));
                    argStart = j + 1;
                }
            }

            result.add(args);
            from = j;
        }
    }
}
