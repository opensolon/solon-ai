package org.noear.solon.ai.talents.code;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.talents.code.impl.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 各语言 Provider 的标记识别、版本探测、聚合器判定与指令生成。
 *
 * <p>版本探测全部由正则驱动，任何改动都可能静默失效，故逐个语言固化「能识别」与「识别不到时返回 null」两侧。
 */
public class ProvidersTest {

    private static Path write(Path dir, String name, String content) throws IOException {
        Path f = dir.resolve(name);
        Files.createDirectories(f.getParent());
        Files.write(f, content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private static String rootCmd(LanguageProvider p) {
        StringBuilder buf = new StringBuilder();
        p.appendRootCommands(buf);
        return buf.toString();
    }

    private static String moduleCmd(LanguageProvider p, String moduleName) {
        StringBuilder buf = new StringBuilder();
        p.appendModuleCommands(buf, moduleName);
        return buf.toString();
    }

    /**
     * 所有 Provider 的通用契约：id/typeName 非空，两类指令都必须产出内容
     */
    @Test
    public void allProviders_haveIdTypeNameAndCommands() {
        for (LanguageProvider p : new CodeTalent("x", ".soloncode").providers()) {
            assertNotNull(p.id(), "id 不能为空");
            assertFalse(p.id().isEmpty());
            assertNotNull(p.typeName());
            assertFalse(p.typeName().isEmpty());
            assertNotNull(p.markers());
            assertNotNull(p.ignoreFolders());

            assertFalse(rootCmd(p).isEmpty(), p.id() + " 缺少根项目指令");
            String mod = moduleCmd(p, "sub/mod");
            assertFalse(mod.isEmpty(), p.id() + " 缺少模块指令");
            assertTrue(mod.contains("sub/mod") || mod.contains("sub:mod"),
                    p.id() + " 的模块指令未带上模块名");
        }
    }

    // ---------- Maven ----------

    @Test
    public void maven_matchAndVersions(@TempDir Path dir) throws Exception {
        MavenProvider p = new MavenProvider();
        assertEquals("Maven", p.id());
        assertTrue(p.isIgnored("target"));

        assertFalse(p.isMatch(dir));
        assertNull(p.detectVersion(dir));

        write(dir, "pom.xml", "<project><properties><maven.compiler.source>1.8</maven.compiler.source></properties></project>");
        assertTrue(p.isMatch(dir));
        assertEquals("Java 1.8", p.detectVersion(dir));

        write(dir, "pom.xml", "<project><properties><maven.compiler.release>21</maven.compiler.release></properties></project>");
        assertEquals("Java 21", p.detectVersion(dir));

        write(dir, "pom.xml", "<project><properties><java.version>17</java.version></properties></project>");
        assertEquals("Java 17", p.detectVersion(dir));

        write(dir, "pom.xml", "<project><build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId>"
                + "<configuration><release>11</release></configuration></plugin></plugins></build></project>");
        assertEquals("Java 11", p.detectVersion(dir));
    }

    @Test
    public void maven_versionInheritedFromParent_returnsNull(@TempDir Path dir) throws Exception {
        // 版本继承自外部父 POM：本地解析不到应返回 null，而不是瞎猜
        write(dir, "pom.xml", "<project><parent><artifactId>p</artifactId></parent></project>");

        assertNull(new MavenProvider().detectVersion(dir));
    }

    @Test
    public void maven_propertyPlaceholder_returnsNull(@TempDir Path dir) throws Exception {
        write(dir, "pom.xml", "<project><properties><maven.compiler.source>${jdk.ver}</maven.compiler.source></properties></project>");

        assertNull(new MavenProvider().detectVersion(dir), "占位符不是版本号");
    }

    @Test
    public void maven_aggregatorDetection(@TempDir Path dir) throws Exception {
        MavenProvider p = new MavenProvider();

        write(dir, "pom.xml", "<project><packaging>jar</packaging></project>");
        assertFalse(p.isAggregator(dir, LanguageProvider.listNames(dir)));

        write(dir, "pom.xml", "<project><modules><module>a</module></modules></project>");
        assertTrue(p.isAggregator(dir, LanguageProvider.listNames(dir)));
    }

    @Test
    public void maven_commands(@TempDir Path dir) {
        MavenProvider p = new MavenProvider();

        assertTrue(rootCmd(p).contains("mvn clean compile"));
        assertTrue(rootCmd(p).contains("mvn test -Dtest=ClassName"));
        assertTrue(moduleCmd(p, "mod-a").contains("cd mod-a && mvn test"));
    }

    // ---------- Gradle ----------

    @Test
    public void gradle_matchAndVersions(@TempDir Path dir) throws Exception {
        GradleProvider p = new GradleProvider();
        assertTrue(p.isIgnored("build"));
        assertTrue(p.isIgnored(".gradle"));

        assertFalse(p.isMatch(dir));

        write(dir, "build.gradle", "sourceCompatibility = JavaVersion.VERSION_1_8");
        assertTrue(p.isMatch(dir));
        assertEquals("Java 1.8", p.detectVersion(dir), "VERSION_1_8 应还原成 1.8");

        write(dir, "build.gradle", "java { sourceCompatibility = '11' }");
        assertEquals("Java 11", p.detectVersion(dir));

        write(dir, "build.gradle", "kotlin { jvmToolchain(21) }");
        assertEquals("Java 21", p.detectVersion(dir));

        write(dir, "build.gradle", "java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }");
        assertEquals("Java 17", p.detectVersion(dir));

        write(dir, "build.gradle", "plugins { id 'java' }");
        assertNull(p.detectVersion(dir));
    }

    @Test
    public void gradle_ktsTakesPrecedence(@TempDir Path dir) throws Exception {
        write(dir, "build.gradle", "sourceCompatibility = '8'");
        write(dir, "build.gradle.kts", "kotlin { jvmToolchain(21) }");

        assertEquals("Java 21", new GradleProvider().detectVersion(dir));
    }

    @Test
    public void gradle_settingsMeansAggregator(@TempDir Path dir) throws Exception {
        GradleProvider p = new GradleProvider();

        write(dir, "build.gradle", "");
        assertFalse(p.isAggregator(dir, LanguageProvider.listNames(dir)));

        write(dir, "settings.gradle", "include ':a'");
        assertTrue(p.isMatch(dir), "settings.gradle 本身也是构建标记");
        assertTrue(p.isAggregator(dir, LanguageProvider.listNames(dir)));

        Files.delete(dir.resolve("settings.gradle"));
        write(dir, "settings.gradle.kts", "include(\":a\")");
        assertTrue(p.isMatch(dir), "settings.gradle.kts 也应被识别");
        assertTrue(p.isAggregator(dir, LanguageProvider.listNames(dir)));
    }

    @Test
    public void gradle_moduleCommandsUsePathNotation() {
        String cmd = moduleCmd(new GradleProvider(), "group/mod");

        assertTrue(cmd.contains(":group:mod:build"), "Gradle 模块路径应使用冒号分隔");
    }

    // ---------- Node ----------

    @Test
    public void node_matchAndVersions(@TempDir Path dir) throws Exception {
        NodeProvider p = new NodeProvider();
        assertTrue(p.isIgnored("node_modules"));

        assertFalse(p.isMatch(dir));

        write(dir, "package.json", "{\"name\":\"x\"}");
        assertTrue(p.isMatch(dir));
        assertNull(p.detectVersion(dir));

        write(dir, "package.json", "{\"engines\":{\"npm\":\">=8\",\"node\":\">=18\"}}");
        assertEquals("Node >=18", p.detectVersion(dir));
    }

    @Test
    public void node_nvmrcFallback(@TempDir Path dir) throws Exception {
        write(dir, "package.json", "{}");
        write(dir, ".nvmrc", "20.11.0\n");

        assertEquals("Node 20.11.0", new NodeProvider().detectVersion(dir));
    }

    @Test
    public void node_blankNvmrc_returnsNull(@TempDir Path dir) throws Exception {
        write(dir, "package.json", "{}");
        write(dir, ".nvmrc", "   \n");

        assertNull(new NodeProvider().detectVersion(dir));
    }

    @Test
    public void node_workspacesMeansAggregator(@TempDir Path dir) throws Exception {
        NodeProvider p = new NodeProvider();

        write(dir, "package.json", "{\"name\":\"x\"}");
        assertFalse(p.isAggregator(dir, LanguageProvider.listNames(dir)));

        write(dir, "package.json", "{\"workspaces\":[\"packages/*\"]}");
        assertTrue(p.isAggregator(dir, LanguageProvider.listNames(dir)));
    }

    // ---------- Go ----------

    @Test
    public void go_matchAndVersion(@TempDir Path dir) throws Exception {
        GoProvider p = new GoProvider();
        assertTrue(p.isIgnored("vendor"));

        assertFalse(p.isMatch(dir));

        write(dir, "go.mod", "module demo\n\ngo 1.21\n\nrequire (\n\tx v1.0.0\n)\n");
        assertTrue(p.isMatch(dir));
        assertEquals("Go 1.21", p.detectVersion(dir));

        write(dir, "go.mod", "module demo\n");
        assertNull(p.detectVersion(dir));
    }

    // ---------- Python ----------

    @Test
    public void python_matchAndVersions(@TempDir Path dir) throws Exception {
        PythonProvider p = new PythonProvider();
        assertTrue(p.isIgnored("__pycache__"));
        assertTrue(p.isIgnored(".venv"));

        assertFalse(p.isMatch(dir));

        write(dir, "requirements.txt", "flask\n");
        assertTrue(p.isMatch(dir));
        assertNull(p.detectVersion(dir));

        write(dir, "pyproject.toml", "[project]\nrequires-python = \">=3.9\"\n");
        assertEquals("Python >=3.9", p.detectVersion(dir));
    }

    @Test
    public void python_setupPyAndPythonVersionFallback(@TempDir Path dir) throws Exception {
        PythonProvider p = new PythonProvider();

        write(dir, "setup.py", "setup(name='x', python_requires='>=3.8')");
        assertTrue(p.isMatch(dir));
        assertEquals("Python >=3.8", p.detectVersion(dir));

        Files.delete(dir.resolve("setup.py"));
        write(dir, "requirements.txt", "");
        write(dir, ".python-version", "3.12.1\n");
        assertEquals("Python 3.12.1", p.detectVersion(dir));
    }

    @Test
    public void python_singleFileTestCommand_hasNoMarkerFlag() {
        // pytest 的 -m 是 marker 表达式选项，传文件路径必然报错
        String root = rootCmd(new PythonProvider());
        String module = moduleCmd(new PythonProvider(), "svc");

        assertTrue(root.contains("`pytest path/to/test_file.py`"), "根项目单文件测试命令应直接跟路径");
        assertFalse(root.contains("pytest -m path"), "不得用 -m 传文件路径");
        assertFalse(module.contains("pytest -m path"));
    }

    // ---------- Rust ----------

    @Test
    public void rust_matchAndVersions(@TempDir Path dir) throws Exception {
        RustProvider p = new RustProvider();
        assertTrue(p.isIgnored("target"));

        assertFalse(p.isMatch(dir));

        write(dir, "Cargo.toml", "[package]\nname = \"x\"\nedition = \"2021\"\n");
        assertTrue(p.isMatch(dir));
        assertEquals("Rust edition 2021", p.detectVersion(dir), "无 rust-version 时退化为 edition");

        write(dir, "Cargo.toml", "[package]\nrust-version = \"1.70\"\nedition = \"2021\"\n");
        assertEquals("Rust 1.70", p.detectVersion(dir));

        write(dir, "Cargo.toml", "[package]\nname = \"x\"\n");
        assertNull(p.detectVersion(dir));
    }

    @Test
    public void rust_workspaceMeansAggregator(@TempDir Path dir) throws Exception {
        RustProvider p = new RustProvider();

        write(dir, "Cargo.toml", "[package]\nname = \"x\"\n");
        assertFalse(p.isAggregator(dir, LanguageProvider.listNames(dir)));

        write(dir, "Cargo.toml", "[workspace]\nmembers = [\"a\", \"b\"]\n");
        assertTrue(p.isAggregator(dir, LanguageProvider.listNames(dir)));
    }

    // ---------- Cangjie ----------

    @Test
    public void cangjie_matchAndVersion(@TempDir Path dir) throws Exception {
        CangjieProvider p = new CangjieProvider();
        assertTrue(p.isIgnored(".cjpm"));

        assertFalse(p.isMatch(dir));

        write(dir, "cjpm.toml", "[package]\ncjc-version = \"0.53.4\"\n");
        assertTrue(p.isMatch(dir));
        assertEquals("Cangjie 0.53.4", p.detectVersion(dir));

        write(dir, "cjpm.toml", "[package]\nname = \"x\"\n");
        assertNull(p.detectVersion(dir));
    }

    // ---------- CMake ----------

    @Test
    public void cmake_matchAndVersions(@TempDir Path dir) throws Exception {
        CMakeProvider p = new CMakeProvider();
        assertTrue(p.isIgnored("CMakeFiles"));

        assertFalse(p.isMatch(dir));

        write(dir, "CMakeLists.txt", "set(CMAKE_CXX_STANDARD 17)\n");
        assertTrue(p.isMatch(dir));
        assertEquals("C++ 17", p.detectVersion(dir));

        write(dir, "CMakeLists.txt", "set(CMAKE_C_STANDARD 11)\n");
        assertEquals("C 11", p.detectVersion(dir));

        write(dir, "CMakeLists.txt", "project(demo)\n");
        assertNull(p.detectVersion(dir));
    }

    @Test
    public void cmake_addSubdirectoryMeansAggregator(@TempDir Path dir) throws Exception {
        CMakeProvider p = new CMakeProvider();

        write(dir, "CMakeLists.txt", "project(demo)\nadd_executable(demo main.c)\n");
        assertFalse(p.isAggregator(dir, LanguageProvider.listNames(dir)));

        write(dir, "CMakeLists.txt", "project(demo)\nadd_subdirectory(libs/a)\n");
        assertTrue(p.isAggregator(dir, LanguageProvider.listNames(dir)));
    }

    // ---------- Flutter ----------

    @Test
    public void flutter_matchAndVersions(@TempDir Path dir) throws Exception {
        FlutterProvider p = new FlutterProvider();
        assertTrue(p.isIgnored(".dart_tool"));

        assertFalse(p.isMatch(dir));

        // 真实 pubspec 的版本约束几乎都带比较符
        write(dir, "pubspec.yaml", "name: demo\nenvironment:\n  sdk: \">=3.0.0 <4.0.0\"\n  flutter: \">=3.10.0\"\n");
        assertTrue(p.isMatch(dir));
        assertEquals("Flutter >=3.10.0", p.detectVersion(dir));

        write(dir, "pubspec.yaml", "name: demo\nenvironment:\n  sdk: \">=3.0.0 <4.0.0\"\n");
        assertEquals("Dart SDK >=3.0.0 <4.0.0", p.detectVersion(dir));

        write(dir, "pubspec.yaml", "name: demo\nenvironment:\n  sdk: ^3.2.0\n");
        assertEquals("Dart SDK ^3.2.0", p.detectVersion(dir));
    }

    @Test
    public void flutter_dependencySdkEntry_isNotAVersion(@TempDir Path dir) throws Exception {
        // dependencies 里的 `flutter:\n    sdk: flutter` 不是版本声明，不能被误读
        write(dir, "pubspec.yaml", "name: demo\ndependencies:\n  flutter:\n    sdk: flutter\n");

        assertNull(new FlutterProvider().detectVersion(dir));
    }

    // ---------- PHP ----------

    @Test
    public void php_matchAndVersion(@TempDir Path dir) throws Exception {
        PhpProvider p = new PhpProvider();
        assertTrue(p.isIgnored("vendor"));

        assertFalse(p.isMatch(dir));

        write(dir, "composer.json", "{\"require\":{\"php\":\">=8.1\"}}");
        assertTrue(p.isMatch(dir));
        assertEquals("PHP >=8.1", p.detectVersion(dir));

        write(dir, "composer.json", "{\"require\":{}}");
        assertNull(p.detectVersion(dir));
    }

    // ---------- C#/.NET ----------

    @Test
    public void dotnet_matchByExtension(@TempDir Path dir) throws Exception {
        DotNetProvider p = new DotNetProvider();
        assertEquals(0, p.markers().length, "C# 按扩展名识别，无精确标记文件");
        assertTrue(p.isIgnored("obj"));

        assertFalse(p.isMatch(dir));
        assertNull(p.detectVersion(dir));

        write(dir, "Demo.csproj", "<Project><PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup></Project>");
        assertTrue(p.isMatch(dir, LanguageProvider.listNames(dir)));
        assertEquals(".NET net8.0", p.detectVersion(dir));
    }

    @Test
    public void dotnet_multiTargetTakesFirst(@TempDir Path dir) throws Exception {
        write(dir, "Demo.csproj", "<Project><PropertyGroup><TargetFrameworks>net6.0;net8.0</TargetFrameworks></PropertyGroup></Project>");

        assertEquals(".NET net6.0", new DotNetProvider().detectVersion(dir));
    }

    @Test
    public void dotnet_csprojWithoutTargetFramework_returnsNull(@TempDir Path dir) throws Exception {
        write(dir, "Demo.csproj", "<Project></Project>");

        assertNull(new DotNetProvider().detectVersion(dir));
    }

    @Test
    public void dotnet_solutionMeansAggregator(@TempDir Path dir) throws Exception {
        DotNetProvider p = new DotNetProvider();

        write(dir, "Demo.csproj", "<Project></Project>");
        assertFalse(p.isAggregator(dir, LanguageProvider.listNames(dir)));

        write(dir, "Demo.sln", "Microsoft Visual Studio Solution File");
        assertTrue(p.isMatch(dir, LanguageProvider.listNames(dir)));
        assertTrue(p.isAggregator(dir, LanguageProvider.listNames(dir)));
    }
}
