package org.noear.solon.ai.harness;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * README 默认值事实测试：配置表里写的缺省值必须与 {@link HarnessOptions} 的实际缺省一致，防止文档漂移。
 *
 * <p>只核对布尔类配置（表格第三列形如 {@code `true`} / {@code `false`}，可带「（SERVER 为 ...）」说明）。</p>
 */
class ReadmeFactsTest {
    private static final Path README = Paths.get("README.md");

    /** 表格行：| 分组 | `name(Type)` | `默认值`... | 说明 | */
    private static final Pattern ROW = Pattern.compile("^\\|[^|]*\\|\\s*`(\\w+)\\([^)]*\\)`\\s*\\|\\s*`(true|false)`");

    private static Map<String, Boolean> documentedDefaults() throws Exception {
        Map<String, Boolean> map = new LinkedHashMap<>();
        for (String line : Files.readAllLines(README, StandardCharsets.UTF_8)) {
            Matcher m = ROW.matcher(line.trim());
            if (m.find()) {
                map.putIfAbsent(m.group(1), Boolean.valueOf(m.group(2)));
            }
        }
        return map;
    }

    @Test
    void booleanDefaultsMatchCode() throws Exception {
        assertTrue(Files.exists(README), "core 模块缺少 README.md（引擎配置表就在这里）：" + README.toAbsolutePath());
        Map<String, Boolean> doc = documentedDefaults();
        HarnessOptions o = new HarnessOptions("work", null);

        Map<String, Boolean> actual = new LinkedHashMap<>();
        actual.put("sandboxAllowUserHome", o.isSandboxAllowUserHome());
        actual.put("sandboxSystemRestrict", o.isSandboxSystemRestrict());
        actual.put("runtimeMutable", o.isRuntimeMutable());

        int checked = 0;
        for (Map.Entry<String, Boolean> e : actual.entrySet()) {
            Boolean documented = doc.get(e.getKey());
            if (documented != null) {
                assertEquals(e.getValue(), documented, "README 配置表中 " + e.getKey() + " 的缺省值与代码不一致");
                checked++;
            }
        }
        assertTrue(checked >= 3, "README 配置表格式变化，事实测试未匹配到配置项：" + doc.keySet());
    }
}
