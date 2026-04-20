/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.skills.lsp;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LspServerParameters 单元测试
 *
 * @author noear
 * @since 3.10.0
 */
public class LspServerParametersTest {

    // ==================== 构造函数测试 ====================

    @Test
    public void testDefaultConstructor() {
        LspServerParameters params = new LspServerParameters();

        assertNotNull(params.getCommand());
        assertTrue(params.getCommand().isEmpty());
        assertNotNull(params.getExtensions());
        assertTrue(params.getExtensions().isEmpty());
        assertFalse(params.isDisabled());
        assertNotNull(params.getInitialization());
        assertTrue(params.getInitialization().isEmpty());
        assertNotNull(params.getEnv());
        assertTrue(params.getEnv().isEmpty());
    }

    @Test
    public void testParameterizedConstructor() {
        LspServerParameters params = new LspServerParameters(
                Arrays.asList("jdtls", "-data", "/tmp/ws"),
                Arrays.asList(".java", ".kt")
        );

        assertEquals(3, params.getCommand().size());
        assertEquals("jdtls", params.getCommand().get(0));
        assertEquals(2, params.getExtensions().size());
        assertTrue(params.getExtensions().contains(".java"));
        assertTrue(params.getExtensions().contains(".kt"));
    }

    // ==================== matchesExtension 测试 ====================

    @Test
    public void testMatchesExtension_Java() {
        LspServerParameters params = new LspServerParameters(
                Arrays.asList("jdtls"), Arrays.asList(".java"));

        assertTrue(params.matchesExtension("Test.java"));
        assertTrue(params.matchesExtension("src/main/Test.java"));
        assertTrue(params.matchesExtension("MYCLASS.JAVA"));
    }

    @Test
    public void testMatchesExtension_TypeScript() {
        LspServerParameters params = new LspServerParameters(
                Arrays.asList("tsserver"), Arrays.asList(".ts", ".tsx"));

        assertTrue(params.matchesExtension("app.ts"));
        assertTrue(params.matchesExtension("app.tsx"));
        assertTrue(params.matchesExtension("src/components/App.TSX"));
        assertFalse(params.matchesExtension("app.js"));
    }

    @Test
    public void testMatchesExtension_NoMatch() {
        LspServerParameters params = new LspServerParameters(
                Arrays.asList("jdtls"), Arrays.asList(".java"));

        assertFalse(params.matchesExtension("script.py"));
        assertFalse(params.matchesExtension("readme.md"));
        assertFalse(params.matchesExtension("test"));
    }

    @Test
    public void testMatchesExtension_EmptyExtensions() {
        LspServerParameters params = new LspServerParameters();
        params.setExtensions(Arrays.asList());

        assertFalse(params.matchesExtension("Test.java"));
    }

    @Test
    public void testMatchesExtension_NullExtensions() {
        LspServerParameters params = new LspServerParameters();
        params.setExtensions(null);

        assertFalse(params.matchesExtension("Test.java"));
    }

    // ==================== getter/setter 测试 ====================

    @Test
    public void testCommandGetterSetter() {
        LspServerParameters params = new LspServerParameters();
        params.setCommand(Arrays.asList("gopls", "serve"));

        assertEquals(2, params.getCommand().size());
        assertEquals("gopls", params.getCommand().get(0));
    }

    @Test
    public void testExtensionsGetterSetter() {
        LspServerParameters params = new LspServerParameters();
        params.setExtensions(Arrays.asList(".go"));

        assertEquals(1, params.getExtensions().size());
        assertEquals(".go", params.getExtensions().get(0));
    }

    @Test
    public void testDisabledGetterSetter() {
        LspServerParameters params = new LspServerParameters();

        assertFalse(params.isDisabled());

        params.setDisabled(true);
        assertTrue(params.isDisabled());

        params.setDisabled(false);
        assertFalse(params.isDisabled());
    }

    @Test
    public void testInitializationGetterSetter() {
        LspServerParameters params = new LspServerParameters();
        Map<String, Object> init = new HashMap<>();
        init.put("extendedClientCapabilities", new HashMap<String, Object>() {{
            put("completionLiteralSnippets", true);
        }});

        params.setInitialization(init);

        assertEquals(1, params.getInitialization().size());
        assertNotNull(params.getInitialization().get("extendedClientCapabilities"));
    }

    @Test
    public void testEnvGetterSetter() {
        LspServerParameters params = new LspServerParameters();
        Map<String, String> env = new HashMap<>();
        env.put("JAVA_HOME", "/usr/lib/jvm/java-17");

        params.setEnv(env);

        assertEquals(1, params.getEnv().size());
        assertEquals("/usr/lib/jvm/java-17", params.getEnv().get("JAVA_HOME"));
    }

    // ==================== getCommandArray 测试 ====================

    @Test
    public void testGetCommandArray() {
        LspServerParameters params = new LspServerParameters(
                Arrays.asList("jdtls", "-data", "/tmp/ws"), Arrays.asList(".java"));

        String[] arr = params.getCommandArray();
        assertArrayEquals(new String[]{"jdtls", "-data", "/tmp/ws"}, arr);
    }

    @Test
    public void testGetCommandArray_Empty() {
        LspServerParameters params = new LspServerParameters();

        String[] arr = params.getCommandArray();
        assertEquals(0, arr.length);
    }
}
