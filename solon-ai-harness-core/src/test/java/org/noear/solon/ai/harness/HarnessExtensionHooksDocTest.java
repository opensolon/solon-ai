package org.noear.solon.ai.harness;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守住 {@link HarnessExtension} 的方法清单与类注释一致（文档不写易变的计数）。
 */
class HarnessExtensionHooksDocTest {
    /** 扩展接口自身声明的方法（不含 AiSwitchable 继承来的开关） */
    private static final Set<String> EXPECTED = new TreeSet<>(Arrays.asList(
            "onAttach", "onDetach", "onAgentBuild", "onInput", "onRequestAssemble",
            "onSessionInterrupt", "contributeSessionStatus", "onSessionRemove",
            "isQuiescent", "getToolNames"));

    @Test
    void declaredMethodsMatchDocumentedList() {
        Set<String> actual = new TreeSet<>();
        for (Method m : HarnessExtension.class.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.isSynthetic()) {
                continue;
            }
            assertTrue(m.isDefault(), "扩展接口的方法必须全部 default：" + m.getName());
            actual.add(m.getName());
        }
        assertEquals(EXPECTED, actual, "HarnessExtension 方法清单变化：请同步类注释里的回调图与开关表，再更新本测试");
    }

    @Test
    void javadocMentionsEveryMethodAndNoCount() throws IOException {
        String src = new String(Files.readAllBytes(Paths.get(
                "src/main/java/org/noear/solon/ai/harness/HarnessExtension.java")), StandardCharsets.UTF_8);
        String header = src.substring(0, src.indexOf("public interface HarnessExtension"));

        List<String> missing = new ArrayList<>();
        for (String name : EXPECTED) {
            if (header.contains(name) == false) {
                missing.add(name);
            }
        }
        assertTrue(missing.isEmpty(), "类注释缺少方法：" + missing);

        Set<String> counts = new HashSet<>(Arrays.asList("8 个回调点", "八个钩子", "六个不看", "两个看"));
        for (String c : counts) {
            assertFalse(header.contains(c), "类注释不要写易变的计数：" + c);
        }
    }
}
