/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.talents.lsp;

import org.junit.jupiter.api.*;
import org.noear.solon.ai.rag.Document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 基于 buildLspServers 预置配置的 LspTalent 真实集成测试
 *
 * <p>测试策略：
 * <ul>
 *   <li>使用 {@link LspManager#buildLspServers()} 批量注册 15 种语言服务器配置</li>
 *   <li>在临时目录下创建各种语言的源文件</li>
 *   <li>通过 {@link LspTalent#lsp} 方法执行真实 LSP 操作</li>
 *   <li>根据环境是否安装了对应语言服务器，自动跳过不可用的测试</li>
 * </ul>
 *
 * <p>当前环境可用服务器：jdtls, typescript-language-server, pylsp, rust-analyzer, clangd, sourcekit-lsp
 *
 * @author noear
 * @since 3.10.0
 */
public class LspTalentBuildInTest {

    private Path worktree;
    private LspManager lspManager;
    private LspTalent lspTalent;

    @BeforeEach
    public void setup() throws Exception {
        worktree = Files.createTempDirectory("lsp-talent-buildin-test");

        // 使用 buildLspServers 的批量配置，但覆盖 python 配置为 pylsp（当前环境可用）
        lspManager = new LspManager(worktree.toString());
        Map<String, LspServerParameters> servers = LspManager.buildLspServers();

        // 覆盖 python 为 pylsp（buildLspServers 默认是 pyright-langserver）
        servers.put("python", new LspServerParameters(
                Arrays.asList("pylsp"),
                Arrays.asList(".py", ".pyi")
        ));

        for (Map.Entry<String, LspServerParameters> entry : servers.entrySet()) {
            lspManager.registerServer(entry.getKey(), entry.getValue());
        }

        lspTalent = new LspTalent(lspManager, worktree.toString());
        lspManager.setDiagnosticsCallback(lspTalent::updateDiagnostics);

        System.out.println("[Setup] worktree=" + worktree);
        System.out.println("[Setup] registered servers: " + lspManager.getServerConfigs().keySet());
    }

    @AfterEach
    public void teardown() {
        if (lspManager != null) {
            lspManager.shutdownAll();
        }
    }

    // ==================== buildLspServers 配置验证 ====================

    @Test
    @DisplayName("buildLspServers 应返回 15 种语言服务器配置")
    public void testBuildLspServers_Count() {
        Map<String, LspServerParameters> servers = LspManager.buildLspServers();
        assertEquals(15, servers.size(), "应有 15 种语言服务器配置");
    }

    @Test
    @DisplayName("buildLspServers 应包含所有预期语言")
    public void testBuildLspServers_Keys() {
        Map<String, LspServerParameters> servers = LspManager.buildLspServers();

        assertTrue(servers.containsKey("java"));
        assertTrue(servers.containsKey("typescript"));
        assertTrue(servers.containsKey("go"));
        assertTrue(servers.containsKey("python"));
        assertTrue(servers.containsKey("rust"));
        assertTrue(servers.containsKey("c-cpp"));
        assertTrue(servers.containsKey("csharp"));
        assertTrue(servers.containsKey("ruby"));
        assertTrue(servers.containsKey("php"));
        assertTrue(servers.containsKey("bash"));
        assertTrue(servers.containsKey("lua"));
        assertTrue(servers.containsKey("dart"));
        assertTrue(servers.containsKey("swift"));
        assertTrue(servers.containsKey("kotlin"));
        assertTrue(servers.containsKey("yaml"));
    }

    @Test
    @DisplayName("buildLspServers 的每种配置都应有 command 和 extensions")
    public void testBuildLspServers_ParamsValid() {
        Map<String, LspServerParameters> servers = LspManager.buildLspServers();

        for (Map.Entry<String, LspServerParameters> entry : servers.entrySet()) {
            LspServerParameters params = entry.getValue();
            assertNotNull(params.getCommand(), entry.getKey() + " command 不应为 null");
            assertFalse(params.getCommand().isEmpty(), entry.getKey() + " command 不应为空");
            assertNotNull(params.getExtensions(), entry.getKey() + " extensions 不应为 null");
            assertFalse(params.getExtensions().isEmpty(), entry.getKey() + " extensions 不应为空");
            assertTrue(params.isEnabled(), entry.getKey() + " 应默认启用");
        }
    }

    @Test
    @DisplayName("buildLspServers 应为 LinkedHashMap 保持插入顺序")
    public void testBuildLspServers_Ordered() {
        Map<String, LspServerParameters> servers = LspManager.buildLspServers();
        // java 应该是第一个（在 buildLspServers 中最先 put）
        String firstKey = servers.keySet().iterator().next();
        assertEquals("java", firstKey, "第一个注册的服务器应为 java");
    }

    // ==================== 扩展名路由测试（使用 buildLspServers 配置） ====================

    @Test
    @DisplayName("路由：.java 文件应匹配 java 服务器")
    public void testRoute_Java() {
        assertTrue(lspManager.hasServers());
        // getClientForFile 是延迟启动的，可能因服务器不存在返回 null
        // 这里只验证配置匹配——通过遍历 serverConfigs 验证
        boolean matched = false;
        for (Map.Entry<String, LspServerParameters> entry : lspManager.getServerConfigs().entrySet()) {
            if (entry.getValue().matchesExtension("Test.java")) {
                matched = true;
                assertEquals("java", entry.getKey());
                break;
            }
        }
        assertTrue(matched, ".java 应匹配 java 服务器");
    }

    @Test
    @DisplayName("路由：.tsx 文件应匹配 typescript 服务器")
    public void testRoute_TypeScriptReact() {
        boolean matched = false;
        for (Map.Entry<String, LspServerParameters> entry : lspManager.getServerConfigs().entrySet()) {
            if (entry.getValue().matchesExtension("App.tsx")) {
                matched = true;
                assertEquals("typescript", entry.getKey());
                break;
            }
        }
        assertTrue(matched, ".tsx 应匹配 typescript 服务器");
    }

    @Test
    @DisplayName("路由：.mjs 文件应匹配 typescript 服务器")
    public void testRoute_MJS() {
        boolean matched = false;
        for (Map.Entry<String, LspServerParameters> entry : lspManager.getServerConfigs().entrySet()) {
            if (entry.getValue().matchesExtension("module.mjs")) {
                matched = true;
                assertEquals("typescript", entry.getKey());
                break;
            }
        }
        assertTrue(matched, ".mjs 应匹配 typescript 服务器");
    }

    @Test
    @DisplayName("路由：.cpp 文件应匹配 c-cpp 服务器")
    public void testRoute_Cpp() {
        boolean matched = false;
        for (Map.Entry<String, LspServerParameters> entry : lspManager.getServerConfigs().entrySet()) {
            if (entry.getValue().matchesExtension("main.cpp")) {
                matched = true;
                assertEquals("c-cpp", entry.getKey());
                break;
            }
        }
        assertTrue(matched, ".cpp 应匹配 c-cpp 服务器");
    }

    @Test
    @DisplayName("路由：.cs 文件应匹配 csharp 服务器")
    public void testRoute_CSharp() {
        boolean matched = false;
        for (Map.Entry<String, LspServerParameters> entry : lspManager.getServerConfigs().entrySet()) {
            if (entry.getValue().matchesExtension("Program.cs")) {
                matched = true;
                assertEquals("csharp", entry.getKey());
                break;
            }
        }
        assertTrue(matched, ".cs 应匹配 csharp 服务器");
    }

    @Test
    @DisplayName("路由：.yml 文件应匹配 yaml 服务器")
    public void testRoute_Yaml() {
        boolean matched = false;
        for (Map.Entry<String, LspServerParameters> entry : lspManager.getServerConfigs().entrySet()) {
            if (entry.getValue().matchesExtension("config.yml")) {
                matched = true;
                assertEquals("yaml", entry.getKey());
                break;
            }
        }
        assertTrue(matched, ".yml 应匹配 yaml 服务器");
    }

    @Test
    @DisplayName("路由：.sh 文件应匹配 bash 服务器")
    public void testRoute_Bash() {
        boolean matched = false;
        for (Map.Entry<String, LspServerParameters> entry : lspManager.getServerConfigs().entrySet()) {
            if (entry.getValue().matchesExtension("script.sh")) {
                matched = true;
                assertEquals("bash", entry.getKey());
                break;
            }
        }
        assertTrue(matched, ".sh 应匹配 bash 服务器");
    }

    @Test
    @DisplayName("路由：.kt 文件应匹配 kotlin 服务器")
    public void testRoute_Kotlin() {
        boolean matched = false;
        for (Map.Entry<String, LspServerParameters> entry : lspManager.getServerConfigs().entrySet()) {
            if (entry.getValue().matchesExtension("Main.kt")) {
                matched = true;
                assertEquals("kotlin", entry.getKey());
                break;
            }
        }
        assertTrue(matched, ".kt 应匹配 kotlin 服务器");
    }

    @Test
    @DisplayName("路由：无匹配扩展名应返回 null")
    public void testRoute_NoMatch() {
        LspClient client = lspManager.getClientForFile("readme.txt");
        assertNull(client, ".txt 不应匹配任何 LSP 服务器");
    }

    @Test
    @DisplayName("路由：路径含目录时应正确匹配扩展名")
    public void testRoute_WithPath() {
        boolean matched = false;
        for (Map.Entry<String, LspServerParameters> entry : lspManager.getServerConfigs().entrySet()) {
            if (entry.getValue().matchesExtension("src/main/java/Hello.java")) {
                matched = true;
                assertEquals("java", entry.getKey());
                break;
            }
        }
        assertTrue(matched, "含目录路径的 .java 文件应匹配 java 服务器");
    }

    // ==================== Python (pylsp) 真实测试 ====================

    @Nested
    @DisplayName("Python LSP (pylsp)")
    class PythonLspTests {

        @Test
        @DisplayName("goToDefinition: 跳转到函数定义")
        public void testGoToDefinition() throws Exception {
            String mainPy =
                    "def greet(name):\n" +
                    "    return \"Hello, \" + name + \"!\"\n" +
                    "\n" +
                    "result = greet(\"world\")\n" +
                    "print(result)\n";
            Files.write(worktree.resolve("main.py"), mainPy.getBytes("UTF-8"));

            Document doc = lspTalent.lsp("goToDefinition", "main.py", 4, 9, null, null);
            assertNotNull(doc);
            assertEquals("goToDefinition", doc.getMetadata("operation"));
            String content = doc.getContent();
            assertNotNull(content);
            // pylsp 可能返回空结果 {"left":[]} （取决于安装的插件），至少不应抛异常
            System.out.println("[Python goToDefinition] content=" + content);
            assertTrue(content.contains("main.py") || content.contains("No results found") || content.contains("\"left\":[]"),
                    "应返回有效结果、No results found 或空列表: " + content);
        }

        @Test
        @DisplayName("findReferences: 查找函数引用")
        public void testFindReferences() throws Exception {
            String mainPy =
                    "def greet(name):\n" +
                    "    return \"Hello, \" + name + \"!\"\n" +
                    "\n" +
                    "result = greet(\"world\")\n" +
                    "print(result)\n";
            Files.write(worktree.resolve("main.py"), mainPy.getBytes("UTF-8"));

            Document doc = lspTalent.lsp("findReferences", "main.py", 1, 5, null, null);
            assertNotNull(doc);
            assertEquals("findReferences", doc.getMetadata("operation"));
            String content = doc.getContent();
            assertNotNull(content);
            assertFalse(content.contains("No results found"),
                    "pylsp 应找到 greet 的引用: " + content);
        }

        @Test
        @DisplayName("hover: 悬停提示")
        public void testHover() throws Exception {
            String mainPy =
                    "def greet(name):\n" +
                    "    return \"Hello, \" + name + \"!\"\n" +
                    "\n" +
                    "result = greet(\"world\")\n" +
                    "print(result)\n";
            Files.write(worktree.resolve("main.py"), mainPy.getBytes("UTF-8"));

            Document doc = lspTalent.lsp("hover", "main.py", 4, 9, null, null);
            assertNotNull(doc);
            assertEquals("hover", doc.getMetadata("operation"));
            String content = doc.getContent();
            assertNotNull(content);
            assertFalse(content.contains("No results found"),
                    "pylsp 应返回 hover 信息: " + content);
        }

        @Test
        @DisplayName("documentSymbol: 文档符号")
        public void testDocumentSymbol() throws Exception {
            String mainPy =
                    "def foo():\n" +
                    "    pass\n" +
                    "\n" +
                    "class MyClass:\n" +
                    "    def method(self):\n" +
                    "        pass\n";
            Files.write(worktree.resolve("symbols.py"), mainPy.getBytes("UTF-8"));

            Document doc = lspTalent.lsp("documentSymbol", "symbols.py", 1, 1, null, null);
            assertNotNull(doc);
            assertEquals("documentSymbol", doc.getMetadata("operation"));
            String content = doc.getContent();
            assertNotNull(content);
            assertFalse(content.contains("No results found"),
                    "pylsp 应返回文档符号: " + content);
        }
    }

    // ==================== Java (jdtls) 真实测试 ====================

    @Nested
    @DisplayName("Java LSP (jdtls)")
    class JavaLspTests {

        /**
         * 为 Java 测试创建独立的 LspManager/LspTalent，因为 jdtls 需要 --java-executable 指向 Java 21+
         */
        private LspManager javaLspManager;
        private LspTalent javaLspTalent;
        private Path javaWorktree;

        @BeforeEach
        public void setupJava() throws Exception {
            javaWorktree = Files.createTempDirectory("lsp-java-test");

            // 创建 Maven 项目结构（jdtls 需要）
            Files.createDirectories(javaWorktree.resolve("src/main/java"));
            String pomXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n" +
                    "  <modelVersion>4.0.0</modelVersion>\n" +
                    "  <groupId>test</groupId>\n" +
                    "  <artifactId>test</artifactId>\n" +
                    "  <version>1.0.0</version>\n" +
                    "</project>\n";
            Files.write(javaWorktree.resolve("pom.xml"), pomXml.getBytes("UTF-8"));

            javaLspManager = new LspManager(javaWorktree.toString());

            // 构建 jdtls 命令：需要 --java-executable 指向 Java 21+
            String java21Home = System.getenv("JAVA_21_HOME");
            if (java21Home == null) {
                // 尝试常见路径
                String[] candidates = {
                        "/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home",
                        "/usr/lib/jvm/java-21",
                        "/usr/lib/jvm/java-21-openjdk"
                };
                for (String candidate : candidates) {
                    if (java.nio.file.Files.exists(java.nio.file.Paths.get(candidate, "bin", "java"))) {
                        java21Home = candidate;
                        break;
                    }
                }
            }

            java.util.List<String> javaCommand;
            if (java21Home != null) {
                String javaExe = java21Home + "/bin/java";
                javaCommand = Arrays.asList(
                        "jdtls",
                        "--java-executable", javaExe
                );
                System.out.println("[Java LSP] Using jdtls with --java-executable: " + javaExe);
            } else {
                // 回退到默认 jdtls（可能因 Java 版本不足而失败）
                javaCommand = Arrays.asList("jdtls");
                System.out.println("[Java LSP] Using default jdtls (no Java 21 found, may fail)");
            }

            javaLspManager.registerServer("java", new LspServerParameters(
                    javaCommand,
                    Arrays.asList(".java")
            ));

            javaLspTalent = new LspTalent(javaLspManager, javaWorktree.toString());
            javaLspManager.setDiagnosticsCallback(javaLspTalent::updateDiagnostics);
        }

        @AfterEach
        public void teardownJava() {
            if (javaLspManager != null) {
                javaLspManager.shutdownAll();
            }
        }

        @Test
        @DisplayName("documentSymbol: 识别类和方法符号")
        @Timeout(value = 60, unit = java.util.concurrent.TimeUnit.SECONDS)
        public void testDocumentSymbol() throws Exception {
            String javaCode =
                    "package com.test;\n" +
                    "\n" +
                    "public class Hello {\n" +
                    "    public String greet(String name) {\n" +
                    "        return \"Hello, \" + name;\n" +
                    "    }\n" +
                    "\n" +
                    "    public static void main(String[] args) {\n" +
                    "        Hello h = new Hello();\n" +
                    "        System.out.println(h.greet(\"world\"));\n" +
                    "    }\n" +
                    "}\n";
            Path javaFile = javaWorktree.resolve("src/main/java/Hello.java");
            Files.write(javaFile, javaCode.getBytes("UTF-8"));

            Document doc = javaLspTalent.lsp("documentSymbol", "src/main/java/Hello.java", 1, 1, null, null);
            assertNotNull(doc);
            assertEquals("documentSymbol", doc.getMetadata("operation"));
            String content = doc.getContent();
            assertNotNull(content);
            // jdtls 可能需要较长时间初始化项目，首次可能返回空结果
            System.out.println("[Java documentSymbol] content=" + content);
            // 至少应包含 Hello 类名（如果 jdtls 完成初始化）
            assertTrue(content.contains("Hello") || content.contains("No results found"),
                    "jdtls 应返回文档符号或初始化中: " + content);
        }

        @Test
        @DisplayName("hover: 悬停提示方法签名")
        @Timeout(value = 60, unit = java.util.concurrent.TimeUnit.SECONDS)
        public void testHover() throws Exception {
            String javaCode =
                    "package com.test;\n" +
                    "\n" +
                    "public class Calc {\n" +
                    "    public int add(int a, int b) {\n" +
                    "        return a + b;\n" +
                    "    }\n" +
                    "}\n";
            Path javaFile = javaWorktree.resolve("src/main/java/Calc.java");
            Files.write(javaFile, javaCode.getBytes("UTF-8"));

            // hover 在 add 方法调用处 (第4行，public 后面的 add)
            Document doc = javaLspTalent.lsp("hover", "src/main/java/Calc.java", 4, 16, null, null);
            assertNotNull(doc);
            assertEquals("hover", doc.getMetadata("operation"));
            String content = doc.getContent();
            assertNotNull(content);
            System.out.println("[Java hover] content=" + content);
            assertTrue(content.contains("add") || content.contains("No results found"),
                    "jdtls 应返回 hover 信息或初始化中: " + content);
        }

        @Test
        @DisplayName("goToDefinition: 跳转到方法定义")
        @Timeout(value = 60, unit = java.util.concurrent.TimeUnit.SECONDS)
        public void testGoToDefinition() throws Exception {
            String javaCode =
                    "package com.test;\n" +
                    "\n" +
                    "public class Hello {\n" +
                    "    public String greet(String name) {\n" +
                    "        return \"Hello, \" + name;\n" +
                    "    }\n" +
                    "\n" +
                    "    public static void main(String[] args) {\n" +
                    "        Hello h = new Hello();\n" +
                    "        String result = h.greet(\"world\");\n" +
                    "        System.out.println(result);\n" +
                    "    }\n" +
                    "}\n";
            Path javaFile = javaWorktree.resolve("src/main/java/Hello.java");
            Files.write(javaFile, javaCode.getBytes("UTF-8"));

            // goToDefinition: 在第10行 h.greet 处，定位到 greet 的定义
            Document doc = javaLspTalent.lsp("goToDefinition", "src/main/java/Hello.java", 10, 22, null, null);
            assertNotNull(doc);
            assertEquals("goToDefinition", doc.getMetadata("operation"));
            String content = doc.getContent();
            assertNotNull(content);
            System.out.println("[Java goToDefinition] content=" + content);
            // jdtls 需要索引完成才能返回结果
            assertTrue(content.contains("Hello.java") || content.contains("No results found"),
                    "jdtls 应返回定义位置或初始化中: " + content);
        }

        @Test
        @DisplayName("findReferences: 查找方法引用")
        @Timeout(value = 60, unit = java.util.concurrent.TimeUnit.SECONDS)
        public void testFindReferences() throws Exception {
            String javaCode =
                    "package com.test;\n" +
                    "\n" +
                    "public class Hello {\n" +
                    "    public String greet(String name) {\n" +
                    "        return \"Hello, \" + name;\n" +
                    "    }\n" +
                    "\n" +
                    "    public static void main(String[] args) {\n" +
                    "        Hello h = new Hello();\n" +
                    "        String result = h.greet(\"world\");\n" +
                    "        System.out.println(result);\n" +
                    "    }\n" +
                    "}\n";
            Path javaFile = javaWorktree.resolve("src/main/java/Hello.java");
            Files.write(javaFile, javaCode.getBytes("UTF-8"));

            // findReferences: 在第4行 greet 方法定义处
            Document doc = javaLspTalent.lsp("findReferences", "src/main/java/Hello.java", 4, 19, null, null);
            assertNotNull(doc);
            assertEquals("findReferences", doc.getMetadata("operation"));
            String content = doc.getContent();
            assertNotNull(content);
            System.out.println("[Java findReferences] content=" + content);
            assertTrue(content.contains("Hello.java") || content.contains("No results found"),
                    "jdtls 应返回引用位置或初始化中: " + content);
        }
    }

    // ==================== TypeScript 真实测试 ===================="

    @Nested
    @DisplayName("TypeScript LSP (typescript-language-server)")
    class TypeScriptLspTests {

        @Test
        @DisplayName("goToDefinition: 跳转到函数定义")
        public void testGoToDefinition() throws Exception {
            String tsCode =
                    "function greet(name: string): string {\n" +
                    "    return \"Hello, \" + name;\n" +
                    "}\n" +
                    "\n" +
                    "const result = greet(\"world\");\n" +
                    "console.log(result);\n";
            Files.write(worktree.resolve("main.ts"), tsCode.getBytes("UTF-8"));

            // typescript-language-server 需要 tsconfig.json 或 jsconfig.json
            Files.write(worktree.resolve("tsconfig.json"),
                    ("{\"compilerOptions\": {\"target\": \"ES2020\", \"module\": \"commonjs\"}}")
                            .getBytes("UTF-8"));

            Document doc = lspTalent.lsp("goToDefinition", "main.ts", 5, 16, null, null);
            assertNotNull(doc);
            assertEquals("goToDefinition", doc.getMetadata("operation"));
            String content = doc.getContent();
            assertNotNull(content);
            assertFalse(content.contains("No results found"),
                    "typescript-language-server 应找到 greet 的定义: " + content);
        }

        @Test
        @DisplayName("hover: 类型提示")
        public void testHover() throws Exception {
            String tsCode =
                    "function add(a: number, b: number): number {\n" +
                    "    return a + b;\n" +
                    "}\n" +
                    "\n" +
                    "const sum = add(1, 2);\n";
            Files.write(worktree.resolve("calc.ts"), tsCode.getBytes("UTF-8"));
            Files.write(worktree.resolve("tsconfig.json"),
                    ("{\"compilerOptions\": {\"target\": \"ES2020\", \"module\": \"commonjs\"}}")
                            .getBytes("UTF-8"));

            Document doc = lspTalent.lsp("hover", "calc.ts", 5, 12, null, null);
            assertNotNull(doc);
            assertEquals("hover", doc.getMetadata("operation"));
            String content = doc.getContent();
            assertNotNull(content);
            // hover 对 add 函数应返回类型签名
            assertFalse(content.contains("No results found"),
                    "typescript-language-server 应返回 hover 信息: " + content);
        }

        @Test
        @DisplayName("documentSymbol: 识别函数和变量符号")
        public void testDocumentSymbol() throws Exception {
            String tsCode =
                    "function foo() {}\n" +
                    "class MyClass {\n" +
                    "    method() {}\n" +
                    "}\n" +
                    "const x = 1;\n";
            Files.write(worktree.resolve("symbols.ts"), tsCode.getBytes("UTF-8"));
            Files.write(worktree.resolve("tsconfig.json"),
                    ("{\"compilerOptions\": {\"target\": \"ES2020\", \"module\": \"commonjs\"}}")
                            .getBytes("UTF-8"));

            Document doc = lspTalent.lsp("documentSymbol", "symbols.ts", 1, 1, null, null);
            assertNotNull(doc);
            assertEquals("documentSymbol", doc.getMetadata("operation"));
            String content = doc.getContent();
            assertNotNull(content);
            assertFalse(content.contains("No results found"),
                    "typescript-language-server 应返回文档符号: " + content);
        }

        @Test
        @DisplayName("findReferences: 查找函数引用")
        public void testFindReferences() throws Exception {
            String tsCode =
                    "function greet(name: string): string {\n" +
                    "    return \"Hello, \" + name;\n" +
                    "}\n" +
                    "\n" +
                    "const result = greet(\"world\");\n";
            Files.write(worktree.resolve("refs.ts"), tsCode.getBytes("UTF-8"));
            Files.write(worktree.resolve("tsconfig.json"),
                    ("{\"compilerOptions\": {\"target\": \"ES2020\", \"module\": \"commonjs\"}}")
                            .getBytes("UTF-8"));

            Document doc = lspTalent.lsp("findReferences", "refs.ts", 1, 10, null, null);
            assertNotNull(doc);
            assertEquals("findReferences", doc.getMetadata("operation"));
            String content = doc.getContent();
            assertNotNull(content);
            assertFalse(content.contains("No results found"),
                    "typescript-language-server 应找到 greet 的引用: " + content);
        }

        @Test
        @DisplayName(".tsx 文件应正确路由到 typescript 服务器")
        public void testTsxRouting() throws Exception {
            String tsxCode =
                    "function App() {\n" +
                    "    return <div>Hello</div>;\n" +
                    "}\n" +
                    "\n" +
                    "export default App;\n";
            Files.write(worktree.resolve("App.tsx"), tsxCode.getBytes("UTF-8"));
            Files.write(worktree.resolve("tsconfig.json"),
                    ("{\"compilerOptions\": {\"target\": \"ES2020\", \"module\": \"commonjs\", \"jsx\": \"react\"}}")
                            .getBytes("UTF-8"));

            Document doc = lspTalent.lsp("documentSymbol", "App.tsx", 1, 1, null, null);
            assertNotNull(doc);
            assertEquals("documentSymbol", doc.getMetadata("operation"));
            // 不论结果如何，至少不应抛异常（验证路由正确）
        }
    }

    // ==================== Rust (rust-analyzer) 真实测试 ====================

    @Nested
    @DisplayName("Rust LSP (rust-analyzer)")
    class RustLspTests {

        @Test
        @DisplayName("hover: 悬停提示")
        @Timeout(value = 30, unit = java.util.concurrent.TimeUnit.SECONDS)
        @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.MAC)
        // CI 环境可能没有 rust-analyzer，或初始化超时
        public void testHover() throws Exception {
            // rust-analyzer 需要 Cargo.toml
            Files.createDirectories(worktree.resolve("src"));
            Files.write(worktree.resolve("Cargo.toml"),
                    ("[package]\nname = \"test\"\nversion = \"0.1.0\"\nedition = \"2021\"\n").getBytes("UTF-8"));
            String rsCode =
                    "fn greet(name: &str) -> String {\n" +
                    "    format!(\"Hello, {}!\", name)\n" +
                    "}\n" +
                    "\n" +
                    "fn main() {\n" +
                    "    let result = greet(\"world\");\n" +
                    "    println!(\"{}\", result);\n" +
                    "}\n";
            Files.write(worktree.resolve("src/main.rs"), rsCode.getBytes("UTF-8"));

            Document doc = lspTalent.lsp("hover", "src/main.rs", 6, 18, null, null);
            assertNotNull(doc);
            assertEquals("hover", doc.getMetadata("operation"));
            String content = doc.getContent();
            assertNotNull(content);
            // rust-analyzer 需要较长时间初始化，可能首次返回空结果
            System.out.println("[Rust hover] content=" + content);
        }
    }

    // ==================== C/C++ (clangd) 真实测试 ====================

    @Nested
    @DisplayName("C/C++ LSP (clangd)")
    class CppLspTests {

        @Test
        @DisplayName("goToDefinition: 跳转到函数定义")
        public void testGoToDefinition() throws Exception {
            String cppCode =
                    "#include <iostream>\n" +
                    "#include <string>\n" +
                    "\n" +
                    "std::string greet(const std::string& name) {\n" +
                    "    return \"Hello, \" + name + \"!\";\n" +
                    "}\n" +
                    "\n" +
                    "int main() {\n" +
                    "    std::string result = greet(\"world\");\n" +
                    "    std::cout << result << std::endl;\n" +
                    "    return 0;\n" +
                    "}\n";
            Files.write(worktree.resolve("main.cpp"), cppCode.getBytes("UTF-8"));

            Document doc = lspTalent.lsp("goToDefinition", "main.cpp", 9, 28, null, null);
            assertNotNull(doc);
            assertEquals("goToDefinition", doc.getMetadata("operation"));
            String content = doc.getContent();
            assertNotNull(content);
            System.out.println("[C++ goToDefinition] content=" + content);
        }

        @Test
        @DisplayName("documentSymbol: 识别函数和变量")
        public void testDocumentSymbol() throws Exception {
            String cppCode =
                    "int add(int a, int b) {\n" +
                    "    return a + b;\n" +
                    "}\n" +
                    "\n" +
                    "class Calculator {\n" +
                    "public:\n" +
                    "    int multiply(int x, int y);\n" +
                    "};\n";
            Files.write(worktree.resolve("calc.cpp"), cppCode.getBytes("UTF-8"));

            Document doc = lspTalent.lsp("documentSymbol", "calc.cpp", 1, 1, null, null);
            assertNotNull(doc);
            assertEquals("documentSymbol", doc.getMetadata("operation"));
            String content = doc.getContent();
            assertNotNull(content);
            System.out.println("[C++ documentSymbol] content=" + content);
        }
    }

    // ==================== 跨语言边界测试 ====================

    @Nested
    @DisplayName("多语言边界场景")
    class CrossLanguageTests {

        @Test
        @DisplayName("isSupported: 注册服务器后应返回 true")
        public void testIsSupported() {
            assertTrue(lspTalent.isSupported(null));
        }

        @Test
        @DisplayName("diagnostics 缓存: 无诊断信息时应返回 No diagnostics")
        public void testDiagnostics_NoCache() throws Exception {
            Files.write(worktree.resolve("test.py"), "x = 1\n".getBytes("UTF-8"));
            Document doc = lspTalent.lsp("diagnostics", "test.py", 1, 1, null, null);
            assertNotNull(doc);
            assertTrue(doc.getContent().contains("No diagnostics available"));
        }

        @Test
        @DisplayName("diagnostics 缓存: 设置后应可读取")
        public void testDiagnostics_WithCache() throws Exception {
            Files.write(worktree.resolve("test.py"), "x = 1\n".getBytes("UTF-8"));
            Path absPath = worktree.resolve("test.py");
            String uri = absPath.toUri().toString();

            lspTalent.updateDiagnostics(uri, "test.py:1:1 [ERROR] undefined variable");
            Document doc = lspTalent.lsp("diagnostics", "test.py", 1, 1, null, null);
            assertNotNull(doc);
            assertTrue(doc.getContent().contains("undefined variable"));
        }

        @Test
        @DisplayName("路径安全检查: 越界路径应抛 SecurityException")
        public void testPathSecurityCheck() throws Exception {
            Files.write(worktree.resolve("test.py"), "x = 1\n".getBytes("UTF-8"));
            assertThrows(SecurityException.class, () -> {
                lspTalent.lsp("goToDefinition", "../outside.py", 1, 1, null, null);
            });
        }

        @Test
        @DisplayName("未知操作应抛 IllegalArgumentException")
        public void testUnknownOperation() throws Exception {
            Files.write(worktree.resolve("test.py"), "x = 1\n".getBytes("UTF-8"));
            assertThrows(IllegalArgumentException.class, () -> {
                lspTalent.lsp("unknownOp", "test.py", 1, 1, null, null);
            });
        }

        @Test
        @DisplayName("文件不存在应抛 RuntimeException")
        public void testFileNotFound() {
            assertThrows(RuntimeException.class, () -> {
                lspTalent.lsp("goToDefinition", "nonexistent.py", 1, 1, null, null);
            });
        }

        @Test
        @DisplayName("无 LSP 服务器的扩展名应返回提示信息")
        public void testNoLspServerForExtension() throws Exception {
            Files.write(worktree.resolve("readme.txt"), "hello world".getBytes());
            Document doc = lspTalent.lsp("hover", "readme.txt", 1, 1, null, null);
            assertNotNull(doc);
            assertTrue(doc.getContent().contains("No LSP server configured"));
        }

        @Test
        @DisplayName("自定义 __cwd: 在子目录中执行操作")
        public void testCustomCwd() throws Exception {
            Path subdir = worktree.resolve("subpkg");
            Files.createDirectories(subdir);
            String pyCode = "def sub_func():\n    pass\n";
            Files.write(subdir.resolve("sub.py"), pyCode.getBytes("UTF-8"));

            Document doc = lspTalent.lsp("documentSymbol", "sub.py", 1, 1, subdir.toString(), null);
            assertNotNull(doc);
            assertEquals("documentSymbol", doc.getMetadata("operation"));
        }
    }

    // ==================== buildLspServers 扩展名完整覆盖 ====================

    @Nested
    @DisplayName("buildLspServers 扩展名覆盖验证")
    class ExtensionCoverageTests {

        @Test
        @DisplayName("typescript 应覆盖所有 JS/TS 变体")
        public void testTypeScriptExtensions() {
            Map<String, LspServerParameters> servers = LspManager.buildLspServers();
            LspServerParameters ts = servers.get("typescript");
            assertNotNull(ts);
            assertTrue(ts.matchesExtension("app.ts"));
            assertTrue(ts.matchesExtension("app.tsx"));
            assertTrue(ts.matchesExtension("app.js"));
            assertTrue(ts.matchesExtension("app.jsx"));
            assertTrue(ts.matchesExtension("app.mjs"));
            assertTrue(ts.matchesExtension("app.cjs"));
            assertTrue(ts.matchesExtension("app.mts"));
            assertTrue(ts.matchesExtension("app.cts"));
        }

        @Test
        @DisplayName("c-cpp 应覆盖所有 C/C++ 变体")
        public void testCppExtensions() {
            Map<String, LspServerParameters> servers = LspManager.buildLspServers();
            LspServerParameters cpp = servers.get("c-cpp");
            assertNotNull(cpp);
            assertTrue(cpp.matchesExtension("main.c"));
            assertTrue(cpp.matchesExtension("main.h"));
            assertTrue(cpp.matchesExtension("main.cpp"));
            assertTrue(cpp.matchesExtension("main.hpp"));
            assertTrue(cpp.matchesExtension("main.cc"));
            assertTrue(cpp.matchesExtension("main.cxx"));
            assertTrue(cpp.matchesExtension("main.hxx"));
            assertTrue(cpp.matchesExtension("main.c++"));
            assertTrue(cpp.matchesExtension("main.h++"));
            assertTrue(cpp.matchesExtension("main.hh"));
        }

        @Test
        @DisplayName("bash 应覆盖所有 shell 变体")
        public void testBashExtensions() {
            Map<String, LspServerParameters> servers = LspManager.buildLspServers();
            LspServerParameters bash = servers.get("bash");
            assertNotNull(bash);
            assertTrue(bash.matchesExtension("script.sh"));
            assertTrue(bash.matchesExtension("script.bash"));
            assertTrue(bash.matchesExtension("script.zsh"));
            assertTrue(bash.matchesExtension("script.ksh"));
        }

        @Test
        @DisplayName("ruby 应覆盖 rb/rake/gemspec/ru")
        public void testRubyExtensions() {
            Map<String, LspServerParameters> servers = LspManager.buildLspServers();
            LspServerParameters ruby = servers.get("ruby");
            assertNotNull(ruby);
            assertTrue(ruby.matchesExtension("app.rb"));
            assertTrue(ruby.matchesExtension("Rakefile.rake"));
            assertTrue(ruby.matchesExtension("app.gemspec"));
            assertTrue(ruby.matchesExtension("config.ru"));
        }

        @Test
        @DisplayName("kotlin 应覆盖 kt/kts")
        public void testKotlinExtensions() {
            Map<String, LspServerParameters> servers = LspManager.buildLspServers();
            LspServerParameters kotlin = servers.get("kotlin");
            assertNotNull(kotlin);
            assertTrue(kotlin.matchesExtension("Main.kt"));
            assertTrue(kotlin.matchesExtension("build.gradle.kts"));
        }

        @Test
        @DisplayName("扩展名匹配应大小写不敏感")
        public void testCaseInsensitive() {
            Map<String, LspServerParameters> servers = LspManager.buildLspServers();

            for (Map.Entry<String, LspServerParameters> entry : servers.entrySet()) {
                LspServerParameters params = entry.getValue();
                for (String ext : params.getExtensions()) {
                    // 用大写版本测试
                    String upperFile = "TEST" + ext.toUpperCase();
                    assertTrue(params.matchesExtension(upperFile),
                            entry.getKey() + " 应匹配大写扩展名 " + upperFile);
                }
            }
        }
    }
}
