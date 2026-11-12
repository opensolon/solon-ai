package features.ai.talents.code.i18n;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.i18n.testkit.GoldenFiles;
import org.noear.solon.ai.talents.code.CodeTalent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * CodeTalent 对模型可见文本的渲染快照（描述、指令、CODE.md、初始化结论）
 *
 * <p>中文快照由改造前的代码生成，国际化后必须逐字不变；英文快照经人工审校后生成。
 * 每个场景只含一种异构模块，避免目录遍历顺序影响输出。</p>
 */
public class CodeRenderGoldenTest {
    static final String CODE_DIR = ".soloncode";

    /**
     * 各语言的标志文件与版本声明：{目录名后缀, 标志文件, 内容}
     */
    static final String[][] STACKS = {
            {"maven", "pom.xml", "<project><properties><java.version>1.8</java.version></properties></project>"},
            {"gradle", "build.gradle", "sourceCompatibility = 17"},
            {"node", "package.json", "{\"engines\":{\"node\":\">=18\"}}"},
            {"go", "go.mod", "module x\n\ngo 1.21\n"},
            {"python", "pyproject.toml", "requires-python = \">=3.9\""},
            {"rust", "Cargo.toml", "[package]\nrust-version = \"1.70\"\n"},
            {"cangjie", "cjpm.toml", "cjc-version = \"0.53\""},
            {"cmake", "CMakeLists.txt", "set(CMAKE_CXX_STANDARD 17)"},
            {"flutter", "pubspec.yaml", "environment:\n  flutter: \">=3.10.0\"\n"},
            {"php", "composer.json", "{\"require\":{\"php\":\">=8.1\"}}"},
            {"dotnet", "app.csproj", "<Project><PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup></Project>"},
    };

    @TempDir
    Path base;

    @Test
    public void testZh() throws Exception {
        GoldenFiles.verify(renderOf(base, null, "_zh"));
    }

    /**
     * 请求语言显式为中文时与默认一致（新建的 CODE.md 也按请求语言）
     */
    @Test
    public void testZhExplicit() throws Exception {
        GoldenFiles.verify(renderOf(base, Locale.CHINA, "_zh"));
    }

    @Test
    public void testEn() throws Exception {
        GoldenFiles.verify(renderOf(base, Locale.ENGLISH, "_en"));
    }

    /**
     * 当前用例的请求语言（也是新建 CODE.md 的语言；null 为默认）
     */
    static final ThreadLocal<Locale> LOCALE = new ThreadLocal<>();

    static Prompt promptOf(Path root) {
        Prompt prompt = Prompt.of("hi");
        prompt.attrPut(CodeTalent.ATTR_CWD, root.toString());
        if (LOCALE.get() != null) {
            prompt.locale(LOCALE.get());
        }
        return prompt;
    }

    /**
     * 只带语言、不带工作目录的提示词
     */
    static Prompt localeOnly() {
        Prompt prompt = Prompt.of("hi");
        prompt.locale(LOCALE.get());
        return prompt;
    }

    static CodeTalent talentOf(Path root) {
        return new CodeTalent(root == null ? null : root.toString(), CODE_DIR);
    }

    /**
     * 按当前用例的请求语言强制初始化
     */
    static String init(CodeTalent talent) {
        return talent.init(null, LOCALE.get());
    }

    static String codeMd(Path root) throws IOException {
        return new String(Files.readAllBytes(root.resolve(CODE_DIR).resolve(CodeTalent.NAME_CODE_MD)), StandardCharsets.UTF_8);
    }

    static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    static Map<String, String> renderOf(Path base, Locale locale, String suffix) throws Exception {
        LOCALE.set(locale);
        try {
            return renderOf(base, locale == null, suffix);
        } finally {
            LOCALE.remove();
        }
    }

    static Map<String, String> renderOf(Path base, boolean withDescription, String suffix) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();

        if (withDescription) {
            //默认语言：description() 无请求上下文
            out.put("code_description" + suffix, talentOf(base).description(null));
        }

        //多模块：父 POM（聚合器）+ 同构子模块（有 / 无版本）+ 一个异构模块
        Path multi = base.resolve("multi");
        write(multi.resolve("pom.xml"), "<project><modules><module>core</module></modules><properties><java.version>1.8</java.version></properties></project>");
        write(multi.resolve("core/pom.xml"), "<project><properties><maven.compiler.release>17</maven.compiler.release></properties></project>");
        write(multi.resolve("api/pom.xml"), "<project></project>");
        write(multi.resolve("web/package.json"), "{\"engines\":{\"node\":\">=18\"}}");
        CodeTalent talent = talentOf(multi);
        out.put("code_multi_instruction" + suffix, talent.getInstruction(promptOf(multi)));
        out.put("code_multi_md" + suffix, codeMd(multi));
        out.put("code_multi_verified" + suffix, init(talent));

        //各语言：根项目 + 同构子模块（类型名）；仓库根 + 异构子模块（模块指令）
        for (String[] s : STACKS) {
            Path root = base.resolve("root_" + s[0]);
            write(root.resolve(s[1]), s[2]);
            write(root.resolve("sub").resolve(s[1]), s[2]);
            out.put("code_root_" + s[0] + "_md" + suffix, codeMd(root, init(talentOf(root)), out, "code_root_" + s[0] + "_msg" + suffix));

            Path mod = base.resolve("module_" + s[0]);
            Files.createDirectories(mod.resolve(".git"));
            write(mod.resolve("libs/core").resolve(s[1]), s[2]);
            out.put("code_module_" + s[0] + "_md" + suffix, codeMd(mod, init(talentOf(mod)), out, "code_module_" + s[0] + "_msg" + suffix));
        }

        //未识别技术栈
        Path empty = base.resolve("empty");
        Files.createDirectories(empty.resolve(".git"));
        out.put("code_empty_instruction" + suffix, talentOf(empty).getInstruction(promptOf(empty)));
        out.put("code_empty_md" + suffix, codeMd(empty));

        //病态规模：清单截断（单个标志、多个标志、无精确标志）
        out.put("code_truncated_maven_md" + suffix, truncated(base.resolve("big_maven"), "pom.xml", "<project></project>"));
        out.put("code_truncated_python_md" + suffix, truncated(base.resolve("big_python"), "requirements.txt", ""));
        out.put("code_truncated_dotnet_md" + suffix, truncated(base.resolve("big_dotnet"), "app.csproj", "<Project></Project>"));

        //失败：目录不可写；工作目录未设置
        Path readonly = base.resolve("readonly");
        Files.createDirectories(readonly);
        readonly.toFile().setWritable(false);
        try {
            out.put("code_readonly_instruction" + suffix, talentOf(readonly).getInstruction(promptOf(readonly)));
        } finally {
            readonly.toFile().setWritable(true);
        }
        out.put("code_nocwd_instruction" + suffix, talentOf(null).getInstruction(LOCALE.get() == null ? null : localeOnly()));

        return out;
    }

    static String codeMd(Path root, String msg, Map<String, String> out, String msgName) throws IOException {
        out.put(msgName, msg);
        return codeMd(root);
    }

    static String truncated(Path root, String marker, String content) throws IOException {
        write(root.resolve(marker), content);
        for (int i = 0; i <= 500; i++) {
            write(root.resolve(String.format("m%03d", i)).resolve(marker), content);
        }
        init(talentOf(root));
        return codeMd(root);
    }
}
