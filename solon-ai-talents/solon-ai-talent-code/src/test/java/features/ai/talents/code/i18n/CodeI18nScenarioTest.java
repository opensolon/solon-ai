package features.ai.talents.code.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.i18n.testkit.GoldenFiles;
import org.noear.solon.ai.talents.code.CodeTalent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 语言来源：才能不保存语言，一律取请求语言（prompt）
 *
 * <ul>
 *     <li>description(null) 跟随默认语言；指令与结论按请求语言；</li>
 *     <li>CODE.md 是共用文件：已有文件的语言优先（按标题识别），新建时用请求语言。</li>
 * </ul>
 */
public class CodeI18nScenarioTest {
    @TempDir
    Path base;

    @AfterEach
    public void restore() {
        AiI18n.setDefaultLocale(Locale.CHINESE);
    }

    private Path multiOf(String name) throws Exception {
        Path root = base.resolve(name);
        CodeRenderGoldenTest.write(root.resolve("pom.xml"), "<project><modules><module>core</module></modules><properties><java.version>1.8</java.version></properties></project>");
        CodeRenderGoldenTest.write(root.resolve("core/pom.xml"), "<project><properties><maven.compiler.release>17</maven.compiler.release></properties></project>");
        CodeRenderGoldenTest.write(root.resolve("api/pom.xml"), "<project></project>");
        CodeRenderGoldenTest.write(root.resolve("web/package.json"), "{\"engines\":{\"node\":\">=18\"}}");
        return root;
    }

    private static Prompt promptOf(Path root, Locale locale) {
        Prompt prompt = CodeRenderGoldenTest.promptOf(root);
        prompt.locale(locale);
        return prompt;
    }

    private static Path codeMdFile(Path root) {
        return root.resolve(CodeRenderGoldenTest.CODE_DIR).resolve(CodeTalent.NAME_CODE_MD);
    }

    @Test
    public void testDefaultLocaleEn() throws Exception {
        AiI18n.setDefaultLocale(Locale.ENGLISH);
        Path root = multiOf("multi");

        CodeTalent talent = new CodeTalent(root.toString(), CodeRenderGoldenTest.CODE_DIR);
        GoldenFiles.verify("code_description_en", talent.description(null));
        GoldenFiles.verify("code_multi_instruction_en", talent.getInstruction(CodeRenderGoldenTest.promptOf(root)));
        GoldenFiles.verify("code_multi_md_en", CodeRenderGoldenTest.codeMd(root));
        GoldenFiles.verify("code_multi_verified_en", talent.init(null, null));
    }

    /**
     * 新建时按请求语言；之后其它语言的请求只影响指令与结论，不改写已有文件
     */
    @Test
    public void testExistingCodeMdLanguageWins() throws Exception {
        Path root = multiOf("multi");
        CodeTalent talent = new CodeTalent(root.toString(), CodeRenderGoldenTest.CODE_DIR);

        //首个请求（英文）新建：文件为英文
        GoldenFiles.verify("code_multi_instruction_en", talent.getInstruction(promptOf(root, Locale.ENGLISH)));
        GoldenFiles.verify("code_multi_md_en", CodeRenderGoldenTest.codeMd(root));

        //中文请求：指令为中文（同一份缓存按请求语言渲染），文件保持英文；强制重扫也只是“已验证”
        GoldenFiles.verify("code_multi_instruction_zh", talent.getInstruction(promptOf(root, Locale.CHINESE)));
        GoldenFiles.verify("code_multi_verified_zh", talent.init(null, Locale.CHINESE));
        GoldenFiles.verify("code_multi_md_en", CodeRenderGoldenTest.codeMd(root));

        //另一个才能实例（如另一个引擎）、默认语言（中文）：同样沿用已有文件的语言
        CodeTalent other = new CodeTalent(root.toString(), CodeRenderGoldenTest.CODE_DIR);
        GoldenFiles.verify("code_multi_verified_zh", other.init(null, null));
        GoldenFiles.verify("code_multi_md_en", CodeRenderGoldenTest.codeMd(root));
    }

    /**
     * 升级前生成的中文 CODE.md：英文请求下保持中文（兼容旧行为）
     */
    @Test
    public void testLegacyZhCodeMdKept() throws Exception {
        Path root = multiOf("multi");
        new CodeTalent(root.toString(), CodeRenderGoldenTest.CODE_DIR).init(null, Locale.CHINESE);
        GoldenFiles.verify("code_multi_md_zh", CodeRenderGoldenTest.codeMd(root));

        CodeTalent talent = new CodeTalent(root.toString(), CodeRenderGoldenTest.CODE_DIR);
        GoldenFiles.verify("code_multi_verified_en", talent.init(null, Locale.ENGLISH));
        GoldenFiles.verify("code_multi_md_zh", CodeRenderGoldenTest.codeMd(root));
    }

    /**
     * 想换语言：删掉文件，下一次按请求语言重新生成
     */
    @Test
    public void testDeleteToSwitchLanguage() throws Exception {
        Path root = multiOf("multi");
        CodeTalent talent = new CodeTalent(root.toString(), CodeRenderGoldenTest.CODE_DIR);
        talent.init(null, Locale.CHINESE);
        GoldenFiles.verify("code_multi_md_zh", CodeRenderGoldenTest.codeMd(root));

        Files.delete(codeMdFile(root));
        Assertions.assertTrue(talent.init(null, Locale.ENGLISH).startsWith("Updated"));
        GoldenFiles.verify("code_multi_md_en", CodeRenderGoldenTest.codeMd(root));
    }

    /**
     * 首行对不上任何语言（被手工改过、空文件）：按请求语言重新生成
     */
    @Test
    public void testUnknownTitleRegeneratedInRequestLanguage() throws Exception {
        Path root = multiOf("multi");
        CodeRenderGoldenTest.write(codeMdFile(root), "# my notes\n");

        CodeTalent talent = new CodeTalent(root.toString(), CodeRenderGoldenTest.CODE_DIR);
        Assertions.assertTrue(talent.init(null, Locale.ENGLISH).startsWith("Updated"));
        GoldenFiles.verify("code_multi_md_en", CodeRenderGoldenTest.codeMd(root));

        Files.write(codeMdFile(root), new byte[0]);
        Assertions.assertTrue(talent.init(null, Locale.CHINESE).startsWith("已更新"));
        GoldenFiles.verify("code_multi_md_zh", CodeRenderGoldenTest.codeMd(root));
    }

    /**
     * 未翻译的语言（文案落回英文）与英文文件互认：不会来回改写
     */
    @Test
    public void testFallbackLocaleSharesEnglishFile() throws Exception {
        Path root = multiOf("multi");
        CodeTalent talent = new CodeTalent(root.toString(), CodeRenderGoldenTest.CODE_DIR);
        talent.init(null, Locale.ENGLISH);
        String md = CodeRenderGoldenTest.codeMd(root);

        Assertions.assertTrue(talent.init(null, Locale.JAPANESE).startsWith("Verified"));
        Assertions.assertTrue(talent.init(null, Locale.ENGLISH).startsWith("Verified"));
        Assertions.assertEquals(md, new String(Files.readAllBytes(codeMdFile(root)), StandardCharsets.UTF_8));
    }

    /**
     * 只实现了旧方法（不带语言）的扩展语言：按语言的默认方法回落到旧方法
     */
    @Test
    public void testCustomProviderWithoutLocale() throws Exception {
        Path root = base.resolve("mylang");
        CodeRenderGoldenTest.write(root.resolve("mytool.cfg"), "version = \"2\"");
        CodeRenderGoldenTest.write(root.resolve("sub/mytool.cfg"), "version = \"2\"");
        Files.createDirectories(base.resolve("mod/.git"));
        CodeRenderGoldenTest.write(base.resolve("mod/x/mytool.cfg"), "");

        CodeTalent talent = new CodeTalent(root.toString(), CodeRenderGoldenTest.CODE_DIR);
        talent.init(null, Locale.ENGLISH);
        String md = CodeRenderGoldenTest.codeMd(root);
        Assertions.assertTrue(md.startsWith("## Build and Test Commands\n\n### 根项目 (MyLang)\n- 构建: `mytool build`\n\n"), md);
        Assertions.assertTrue(md.contains("- MyLang 项目 (1)\n  - sub (MyLang 2)\n"), md);

        CodeTalent talent2 = new CodeTalent(base.resolve("mod").toString(), CodeRenderGoldenTest.CODE_DIR);
        talent2.init(null, Locale.ENGLISH);
        Assertions.assertTrue(CodeRenderGoldenTest.codeMd(base.resolve("mod"))
                .contains("### 模块 (Module): x (MyLang)\n- 构建: `cd x && mytool build`\n\n"));
    }
}
