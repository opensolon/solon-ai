package org.noear.solon.ai.talents.lsp.i18n;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.i18n.testkit.GoldenFiles;
import org.noear.solon.ai.talents.lsp.LspManager;
import org.noear.solon.ai.talents.lsp.LspServerParameters;
import org.noear.solon.ai.talents.lsp.LspTalent;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * LspTalent 对模型可见文本的渲染快照（描述、工具 schema、返回值）
 *
 * <p>中文快照由改造前的代码生成，国际化后必须逐字不变；英文快照经人工审校后生成。</p>
 */
public class LspRenderGoldenTest {
    @TempDir
    Path workspace;

    @Test
    public void testZh() throws Exception {
        GoldenFiles.verify(renderOf(newTalent(workspace), Prompt.of("hi"), null, "_zh"));
    }

    @Test
    public void testZhExplicit() throws Exception {
        GoldenFiles.verify(renderOf(newTalent(workspace), prompt(Locale.CHINA), "zh-CN", "_zh"));
    }

    @Test
    public void testEn() throws Exception {
        GoldenFiles.verify(renderOf(newTalent(workspace), prompt(Locale.ENGLISH), "en", "_en"));
    }

    static Prompt prompt(Locale locale) {
        Prompt prompt = Prompt.of("hi");
        prompt.locale(locale);
        return prompt;
    }

    static LspTalent newTalent(Path workspace) {
        LspManager manager = new LspManager(workspace.toString());
        //注册一个不存在的命令：保证 isSupported 为 true，且绝不会真的启动进程
        manager.registerServer("java", new LspServerParameters(
                Arrays.asList("definitely-not-a-real-lsp-binary-xyz"), Arrays.asList(".java")));
        return new LspTalent(manager, workspace.toString());
    }

    /**
     * @param locale 工具上下文注入的 __locale（null 表示未注入，走不带 __locale 的旧方法，用默认语言）
     */
    static Map<String, String> renderOf(LspTalent talent, Prompt prompt, String locale, String suffix) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("lsp_description" + suffix, String.valueOf(talent.description(null)));
        out.put("lsp_instruction" + suffix, String.valueOf(talent.getInstruction(prompt)));
        out.put("lsp_tools", GoldenFiles.dumpTools(talent.getTools(prompt)));

        //文件不存在
        try {
            if (locale == null) {
                talent.lsp("hover", "src/NoSuch.java", 1, 1, null, "s1");
            } else {
                talent.lsp("hover", "src/NoSuch.java", 1, 1, null, "s1", locale);
            }
            out.put("lsp_file_missing" + suffix, "<no error>");
        } catch (RuntimeException e) {
            out.put("lsp_file_missing" + suffix, e.getMessage());
        }
        return out;
    }
}
