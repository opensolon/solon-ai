package org.noear.solon.ai.talents.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.talents.cli.EnvironmentResolver;

/**
 * 实时 PATH 解析核心逻辑：Machine/User 合并规则（系统在前、用户在后、去重保序）。
 */
public class EnvironmentResolverTest {

    @Test
    public void merge_machineFirstUserSecond() {
        String merged = EnvironmentResolver.mergePathLists(
                "C:\\Windows\\System32;C:\\Program Files",
                "C:\\Users\\me\\bin;C:\\Users\\me\\AppData\\Local\\Microsoft\\WindowsApps");

        assertEquals(
                "C:\\Windows\\System32;C:\\Program Files;"
                        + "C:\\Users\\me\\bin;C:\\Users\\me\\AppData\\Local\\Microsoft\\WindowsApps",
                merged);
    }

    @Test
    public void merge_dedupKeepsFirstOccurrenceOrder() {
        String merged = EnvironmentResolver.mergePathLists(
                "C:\\Windows\\System32;C:\\Tools",
                "C:\\Tools;C:\\Windows\\System32;C:\\Users\\me\\bin");

        // 重复项保留首次出现位置（Machine 优先），User 中的重复项被丢弃
        assertEquals("C:\\Windows\\System32;C:\\Tools;C:\\Users\\me\\bin", merged);
    }

    @Test
    public void merge_skipsEmptyAndBlankParts() {
        String merged = EnvironmentResolver.mergePathLists(
                "C:\\Windows;;; ",
                ";C:\\Users\\me\\bin;  ;");

        assertEquals("C:\\Windows;C:\\Users\\me\\bin", merged);
    }

    @Test
    public void merge_nullUserOnlyMachine() {
        String merged = EnvironmentResolver.mergePathLists("C:\\Windows", null);
        assertEquals("C:\\Windows", merged);
    }

    @Test
    public void merge_nullMachineOnlyUser() {
        String merged = EnvironmentResolver.mergePathLists(null, "C:\\Users\\me\\bin");
        assertEquals("C:\\Users\\me\\bin", merged);
    }

    @Test
    public void merge_bothNullReturnsNull() {
        assertNull(EnvironmentResolver.mergePathLists(null, null));
    }

    @Test
    public void merge_bothEmptyReturnsNull() {
        assertNull(EnvironmentResolver.mergePathLists("", ""));
        assertNull(EnvironmentResolver.mergePathLists(";", ";;"));
    }

    @Test
    public void merge_caseInsensitiveDedup() {
        // Windows 路径大小写不敏感：仅大小写不同的条目视为重复，保留首次出现形式
        String merged = EnvironmentResolver.mergePathLists(
                "C:\\Windows\\System32;C:\\Tools",
                "c:\\windows\\system32;C:\\Users\\me\\bin");

        assertEquals("C:\\Windows\\System32;C:\\Tools;C:\\Users\\me\\bin", merged);
    }

    @Test
    public void mergeInherited_keepsNonRegistryEntriesAppended() {
        // 实时 PATH 优先置顶；继承 PATH 中未出现在注册表里的条目（GPO/启动脚本/conda）追加在后
        String merged = EnvironmentResolver.mergeInheritedPath(
                "C:\\Windows\\System32;C:\\Program Files",
                "C:\\Tools;C:\\Windows\\System32;D:\\conda\\envs\\py38");

        assertEquals(
                "C:\\Windows\\System32;C:\\Program Files;C:\\Tools;D:\\conda\\envs\\py38",
                merged);
    }

    @Test
    public void mergeInherited_nullSystemKeepsInherited() {
        assertEquals("C:\\Tools", EnvironmentResolver.mergeInheritedPath(null, "C:\\Tools"));
    }

    @Test
    public void mergeInherited_nullInheritedReturnsSystem() {
        assertEquals("C:\\Windows", EnvironmentResolver.mergeInheritedPath("C:\\Windows", null));
        assertEquals("C:\\Windows", EnvironmentResolver.mergeInheritedPath("C:\\Windows", ""));
    }

    @Test
    public void mergeInherited_caseInsensitiveDedup() {
        // 继承 PATH 中与实时 PATH 仅大小写不同的条目不追加
        String merged = EnvironmentResolver.mergeInheritedPath(
                "C:\\Windows\\System32",
                "c:\\windows\\system32;D:\\extra");
        assertEquals("C:\\Windows\\System32;D:\\extra", merged);
    }

    @Test
    public void mergeInherited_allInheritedAreDuplicatesReturnsSystemUnchanged() {
        // 继承 PATH 全部与实时 PATH 重复：应原样返回实时 PATH，不追加任何尾部分隔符
        String merged = EnvironmentResolver.mergeInheritedPath(
                "C:\\Windows\\System32;C:\\Tools",
                "C:\\Tools;c:\\windows\\system32");
        assertEquals("C:\\Windows\\System32;C:\\Tools", merged);
    }

    @Test
    public void mergeInherited_trimsWhitespaceAndSkipsBlankParts() {
        // 继承 PATH 含空白/空段：trim 后去重追加，空段跳过
        String merged = EnvironmentResolver.mergeInheritedPath(
                "C:\\Windows\\System32",
                "  C:\\Windows\\System32  ;; D:\\extra ;");
        assertEquals("C:\\Windows\\System32;D:\\extra", merged);
    }

    @Test
    public void resolvePath_onNonWindowsSafeFallback() {
        // 无论运行平台，resolvePath 都不应抛异常（非 Windows 返回 null，Windows 失败时降级 null）
        String path = EnvironmentResolver.resolvePath();
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            // Windows 上返回合并后的 PATH（至少含 System32）；解析失败时允许 null（安全降级）
            if (path != null) {
                assertTrue(path.contains("System32") || path.contains("Windows"), path);
            }
        } else {
            assertNull(path);
        }
    }
}
