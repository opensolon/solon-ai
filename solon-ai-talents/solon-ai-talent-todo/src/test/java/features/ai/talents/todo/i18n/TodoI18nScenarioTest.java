package features.ai.talents.todo.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.i18n.testkit.GoldenFiles;
import org.noear.solon.ai.talents.todo.TodoTalent;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 没有请求上下文的入口跟随默认语言（description()、getToolAry()、不带 __locale 的旧方法）
 */
public class TodoI18nScenarioTest {
    @TempDir
    Path cwd;

    @AfterEach
    public void restore() {
        AiI18n.setDefaultLocale(Locale.CHINESE);
    }

    @Test
    public void testDefaultLocaleEn() throws Exception {
        AiI18n.setDefaultLocale(Locale.ENGLISH);
        TodoTalent talent = new TodoTalent();

        GoldenFiles.verify("todo_description_en", talent.description(null));
        GoldenFiles.verify("todo_tools", GoldenFiles.dumpTools(talent.getToolAry()));
        //未注入 __locale 时按默认语言
        GoldenFiles.verify("todo_read_empty_en", talent.todoRead(cwd.toString(), "s1", null));
        GoldenFiles.verify("todo_write_pending_en", talent.todoWrite("- [x] a\n- [/] b\n- [ ] c", cwd.toString(), "s3", null));
    }

    @Test
    public void testDefaultLocaleZh() throws Exception {
        TodoTalent talent = new TodoTalent();

        GoldenFiles.verify("todo_description_zh", talent.description(null));
        GoldenFiles.verify("todo_tools", GoldenFiles.dumpTools(talent.getToolAry()));
        GoldenFiles.verify("todo_read_empty_zh", talent.todoRead(cwd.toString(), "s1", null));
    }

    /**
     * 本地化只换描述，工具调用照常转发给原方法
     */
    @Test
    public void testLocalizedToolStillWorks() throws Throwable {
        TodoTalent talent = new TodoTalent();
        Prompt prompt = TodoRenderGoldenTest.prompt(Locale.ENGLISH);

        FunctionTool read = null;
        for (FunctionTool t : talent.getTools(prompt)) {
            if (TodoTalent.TOOL_TODOREAD.equals(t.name())) {
                read = t;
            }
        }

        Assertions.assertNotNull(read);
        Map<String, Object> args = new HashMap<>();
        args.put("__cwd", cwd.toString());
        args.put("__sessionId", "s9");
        args.put(AiI18n.ATTR_LOCALE, "en");
        Assertions.assertEquals(talent.todoRead(cwd.toString(), "s9", "en"), String.valueOf(read.handle(args)));
    }
}
