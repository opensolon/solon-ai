package features.ai.i18n;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.i18n.AgentI18n;
import org.noear.solon.ai.agent.i18n.AgentI18nKeys;
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
 * 内置文案资源的格式与一致性检查（yml）
 *
 * <ul>
 *     <li>书写格式：每个值都是扁平 key 的 "|-" 块标量，缩进 2 空格，无 Tab、无行尾空白、无重复 key、
 *     无行首 "---"（会被配置加载器当作文档分隔）；</li>
 *     <li>值：只写正文——不含首尾空白（段落间的换行、空格由代码负责，扩展语言时无需关心）、不含空白标记符、
 *     不含 "${" / "#{"、英文包不含中文；</li>
 *     <li>花括号只能是命名占位符 {name}、JSON 示例 {"... 或 {...}；</li>
 *     <li>跨语言：key 集合一致，每个 key 的占位符名字集合一致；</li>
 *     <li>key 常量：{@link AgentI18nKeys} 与资源 key 集合完全一致，常量名 = key 转大写（"." 换成 "_"），
 *     每个常量都在产品代码中被使用；产品代码不得直接写 key 字面量；</li>
 *     <li>调用点：AgentI18n.fmt / get 的 key 必须是 AgentI18nKeys 常量，fmt 传入的变量名必须与占位符完全一致，
 *     get 取的文案不得含占位符。</li>
 * </ul>
 */
public class I18nResourceLintTest {
    static final String[] LANGS = {"", "_zh"};
    static final Pattern KEY_LINE = Pattern.compile("^([A-Za-z0-9_.\\-]+): \\|-$");
    static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)\\}");
    static final Path MAIN_SRC = Paths.get("src/main/java");

    @Test
    public void testYmlLayout() throws IOException {
        for (String bundle : AgentI18n.BUNDLES) {
            for (String lang : LANGS) {
                String path = path(bundle, lang, ".yml");
                List<String> lines = readLines(path);
                Set<String> keys = new HashSet<>();

                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String at = path + ":" + (i + 1) + " ";

                    Assertions.assertFalse(line.indexOf('\t') >= 0, at + "tab is not allowed");
                    Assertions.assertFalse(line.endsWith(" "), at + "trailing space is not allowed");
                    Assertions.assertFalse(line.startsWith("---"), at + "line must not start with ---");

                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }

                    if (line.startsWith("  ")) {
                        Assertions.assertTrue(i > 0, at + "value without key");
                        continue;
                    }

                    Matcher m = KEY_LINE.matcher(line);
                    Assertions.assertTrue(m.matches(), at + "expected 'flat.key: |-', got: " + line);
                    Assertions.assertTrue(keys.add(m.group(1)), at + "duplicate key: " + m.group(1));
                    Assertions.assertFalse(m.group(1).startsWith("solon.env"), at + "reserved key: " + m.group(1));
                }
            }
        }
    }

    @Test
    public void testNoPropertiesShadow() {
        //同名 .properties 会优先于 .yml 加载，导致 yml 静默失效
        for (String bundle : AgentI18n.BUNDLES) {
            for (String lang : LANGS) {
                Assertions.assertNull(resource(path(bundle, lang, ".properties")),
                        "must not exist: " + path(bundle, lang, ".properties"));
            }
        }
    }

    @Test
    public void testValues() throws IOException {
        for (String bundle : AgentI18n.BUNDLES) {
            Map<String, String> en = loadYml(path(bundle, "", ".yml"));
            Map<String, String> zh = loadYml(path(bundle, "_zh", ".yml"));

            Assertions.assertEquals(en.keySet(), zh.keySet(), bundle + ": en / zh key sets differ");

            for (String key : en.keySet()) {
                Assertions.assertEquals(placeholders(en.get(key)), placeholders(zh.get(key)),
                        bundle + ":" + key + " placeholders differ between en and zh");
            }

            for (Map<String, String> map : new Map[]{en, zh}) {
                for (Map.Entry<String, String> kv : map.entrySet()) {
                    String at = bundle + ":" + kv.getKey() + " ";
                    String val = kv.getValue();

                    Assertions.assertEquals(val.trim(), val, at + "value must not have leading/trailing whitespace (add separators in code)");
                    Assertions.assertFalse(val.indexOf('\u23CE') >= 0 || val.indexOf('\u2423') >= 0,
                            at + "whitespace marks are not supported, add separators in code");
                    Assertions.assertFalse(val.contains("${") || val.contains("#{"), at + "must not contain SnEL template marks");
                    assertBraces(at, val);
                }
            }

            for (Map.Entry<String, String> kv : en.entrySet()) {
                Assertions.assertFalse(kv.getValue().matches("(?s).*[\\u4e00-\\u9fa5].*"),
                        bundle + ":" + kv.getKey() + " (en) contains chinese: " + kv.getValue());
            }
        }
    }

    /**
     * key 常量类与资源一一对应，且全部在用、命名规则统一；产品代码不得绕过常量直接写 key
     */
    @Test
    public void testKeyConstants() throws Exception {
        Map<String, String> constants = keyConstants();
        List<String> errors = new ArrayList<>();

        for (Map.Entry<String, String> kv : constants.entrySet()) {
            String expectedName = kv.getValue().toUpperCase(Locale.ROOT).replace('.', '_');
            if (expectedName.equals(kv.getKey()) == false) {
                errors.add("constant " + kv.getKey() + " should be named " + expectedName);
            }
        }

        for (Locale locale : new Locale[]{Locale.CHINESE, Locale.ENGLISH}) {
            Set<String> keys = new TreeSet<>(AgentI18n.messages(locale).keySet());
            Set<String> declared = new TreeSet<>(constants.values());
            if (keys.equals(declared) == false) {
                Set<String> noConst = new TreeSet<>(keys);
                noConst.removeAll(declared);
                Set<String> noRes = new TreeSet<>(declared);
                noRes.removeAll(keys);
                errors.add(locale + ": keys without constant " + noConst + "; constants without resource " + noRes);
            }
        }

        StringBuilder all = new StringBuilder();
        for (Path file : mainSources()) {
            if (file.endsWith(KEYS_FILE)) {
                continue;
            }
            String src = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            all.append(src);

            Matcher m = KEY_LITERAL.matcher(src);
            while (m.find()) {
                if (constants.containsValue(m.group(1))) {
                    errors.add(file + ": use AgentI18nKeys instead of literal \"" + m.group(1) + "\"");
                }
            }
        }

        for (String name : constants.keySet()) {
            if (Pattern.compile("\\bAgentI18nKeys\\." + name + "\\b").matcher(all).find() == false) {
                errors.add("unused key constant (remove it from AgentI18nKeys and the yml files): " + name);
            }
        }

        Assertions.assertTrue(errors.isEmpty(), String.join("\n", errors));
    }

    /**
     * 源码调用点与资源的一致性：变量名写错（或漏传）会让占位符原样留在提示词里，这里提前拦住
     */
    @Test
    public void testCallSites() throws Exception {
        Map<String, String> zh = AgentI18n.messages(Locale.CHINESE);
        Map<String, String> en = AgentI18n.messages(Locale.ENGLISH);
        Map<String, String> constants = keyConstants();
        List<String> errors = new ArrayList<>();
        int fmtCount = 0;
        int getCount = 0;

        for (Path file : mainSources()) {
            String src = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);

            for (List<String> args : calls(src, "AgentI18n.fmt(")) {
                fmtCount++;
                String key = keyOf(args.get(1), constants);
                if (key == null) {
                    errors.add(file + ": fmt key must be an AgentI18nKeys constant: " + args.get(1));
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

                for (Map<String, String> msgs : new Map[]{zh, en}) {
                    String val = msgs.get(key);
                    if (val == null) {
                        errors.add(file + ": missing key " + key);
                    } else if (placeholders(val).equals(names) == false) {
                        errors.add(file + ": " + key + " expects " + placeholders(val) + " but got " + names);
                    }
                }
            }

            for (List<String> args : calls(src, "AgentI18n.get(")) {
                getCount++;
                String key = keyOf(args.get(1), constants);
                if (key == null) {
                    errors.add(file + ": get key must be an AgentI18nKeys constant: " + args.get(1));
                    continue;
                }

                for (Map<String, String> msgs : new Map[]{zh, en}) {
                    String val = msgs.get(key);
                    if (val == null) {
                        errors.add(file + ": missing key " + key);
                    } else if (placeholders(val).isEmpty() == false) {
                        errors.add(file + ": " + key + " has placeholders " + placeholders(val) + ", use fmt instead of get");
                    }
                }
            }
        }

        Assertions.assertTrue(fmtCount > 50 && getCount > 100, "call site scan looks broken: fmt=" + fmtCount + ", get=" + getCount);
        Assertions.assertTrue(errors.isEmpty(), String.join("\n", errors));
    }

    // ---------------- helpers ----------------

    static final Path KEYS_FILE = Paths.get("AgentI18nKeys.java");
    static final Pattern KEY_LITERAL = Pattern.compile("\"([a-z]+(?:\\.[a-z0-9_]+)+)\"");

    /**
     * AgentI18nKeys 中的常量（常量名 -&gt; key）
     */
    static Map<String, String> keyConstants() throws IllegalAccessException {
        Map<String, String> map = new LinkedHashMap<>();
        for (Field f : AgentI18nKeys.class.getDeclaredFields()) {
            int mod = f.getModifiers();
            if (Modifier.isPublic(mod) && Modifier.isStatic(mod) && Modifier.isFinal(mod) && f.getType() == String.class) {
                map.put(f.getName(), (String) f.get(null));
            }
        }
        return map;
    }

    static String keyOf(String arg, Map<String, String> constants) {
        String s = arg.trim();
        String prefix = "AgentI18nKeys.";
        return s.startsWith(prefix) ? constants.get(s.substring(prefix.length())) : null;
    }

    static List<Path> mainSources() throws IOException {
        try (Stream<Path> s = Files.walk(MAIN_SRC)) {
            return s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }

    static String path(String bundle, String lang, String ext) {
        return bundle.replace('.', '/') + lang + ext;
    }

    static URL resource(String path) {
        return I18nResourceLintTest.class.getClassLoader().getResource(path);
    }

    static List<String> readLines(String path) throws IOException {
        URL url = resource(path);
        Assertions.assertNotNull(url, "missing resource: " + path);
        try (InputStream in = url.openStream()) {
            byte[] bytes = readAll(in);
            //CRLF 由 yml 解析器归一为 \n，这里按行检查时一并忽略
            String text = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
            List<String> lines = new ArrayList<>();
            for (String line : text.split("\n", -1)) {
                lines.add(line);
            }
            return lines;
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
    static Map<String, String> loadYml(String path) throws IOException {
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

    static Set<String> placeholders(String val) {
        Set<String> set = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(val);
        while (m.find()) {
            set.add(m.group(1));
        }
        return set;
    }

    static void assertBraces(String at, String val) {
        for (int i = val.indexOf('{'); i >= 0; i = val.indexOf('{', i + 1)) {
            String rest = val.substring(i);
            boolean ok = PLACEHOLDER.matcher(rest).lookingAt() || rest.startsWith("{\"") || rest.startsWith("{...}");
            Assertions.assertTrue(ok, at + "unexpected brace at " + i + ": " + rest.substring(0, Math.min(20, rest.length())));
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
