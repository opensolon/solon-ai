package org.noear.solon.ai.harness.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.i18n.testkit.GoldenFiles;
import org.noear.solon.ai.mount.source.FileMountSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 项目规范对模型可见文本的渲染快照（才能说明 + 注入段、截断提示、变更消息；中英文）
 */
public class ProjectRulesGoldenTest {
    @TempDir
    Path workspace;

    @Test
    public void testZh() throws Throwable {
        GoldenFiles.verify(renderOf(workspace, null, "_zh"));
    }

    @Test
    public void testZhExplicit() throws Throwable {
        GoldenFiles.verify(renderOf(workspace, Locale.CHINA, "_zh"));
    }

    @Test
    public void testEn() throws Throwable {
        GoldenFiles.verify(renderOf(workspace, Locale.ENGLISH, "_en"));
    }

    static Map<String, String> renderOf(Path ws, Locale locale, String suffix) throws Throwable {
        Map<String, String> out = new LinkedHashMap<>();
        Path root = ws.resolve("agents-md-ws");
        Files.createDirectories(root.resolve("app"));
        Files.write(root.resolve("app/AGENTS.md"), "App module: keep answers short.".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("AGENTS.md"), "Build: mvn test. Never use fully qualified class names.".getBytes(StandardCharsets.UTF_8));

        Prompt prompt = Prompt.of("hi");
        if (locale != null) {
            prompt.locale(locale);
        }
        prompt.attrPut(HarnessEngine.ATTR_CWD, root.resolve("app").toString());

        ProjectRulesTalent talent = new ProjectRulesTalent(FileMountSource.of(root), root.toString());
        out.put("project_rules" + suffix, talent.description(prompt) + "\n----\n" + talent.getInstruction(prompt));

        // 单文件超过读取上限时只读开头一段（提示读原文）
        List<ProjectRulesTalent.Part> truncated = Arrays.asList(
                new ProjectRulesTalent.Part("app/AGENTS.md", "App module: kee", true),
                new ProjectRulesTalent.Part("AGENTS.md", "Build: mvn test. Nev", true));
        out.put("project_rules_truncated" + suffix, ProjectRulesTalent.render(truncated, locale));

        // 变更消息：去掉一层、新增一层（工作目录从 app 换到 pkg）、有变化的给整段原文；全部去掉
        List<ProjectRulesTalent.Part> before = talent.load(root.resolve("app").toString());
        Files.write(root.resolve("AGENTS.md"), "Build: mvn verify. Never use fully qualified class names.".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve("pkg"));
        Files.write(root.resolve("pkg/AGENTS.md"), "Package rule.".getBytes(StandardCharsets.UTF_8));
        List<ProjectRulesTalent.Part> after = talent.load(root.resolve("pkg").toString());
        out.put("project_rules_update" + suffix, ProjectRulesTalent.renderUpdate(before, after, locale)
                + "\n----\n" + ProjectRulesTalent.renderUpdate(after, Collections.<ProjectRulesTalent.Part>emptyList(), locale));
        return out;
    }
}
