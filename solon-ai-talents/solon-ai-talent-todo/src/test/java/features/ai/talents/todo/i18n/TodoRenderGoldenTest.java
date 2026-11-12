package features.ai.talents.todo.i18n;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.i18n.testkit.GoldenFiles;
import org.noear.solon.ai.talents.todo.TodoTalent;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * TodoTalent 对模型可见文本的渲染快照（指令、描述、工具 schema、返回值）
 *
 * <p>中文快照由改造前的代码生成，国际化后必须逐字不变；英文快照经人工审校后生成。</p>
 */
public class TodoRenderGoldenTest {
    @TempDir
    Path cwd;

    @Test
    public void testZh() throws Exception {
        GoldenFiles.verify(renderOf(new TodoTalent(), Prompt.of("hi"), null, cwd, "_zh"));
    }

    /**
     * 请求语言为中文时与默认一致
     */
    @Test
    public void testZhExplicit() throws Exception {
        GoldenFiles.verify(renderOf(new TodoTalent(), prompt(Locale.CHINA), "zh-CN", cwd, "_zh"));
    }

    @Test
    public void testEn() throws Exception {
        GoldenFiles.verify(renderOf(new TodoTalent(), prompt(Locale.ENGLISH), "en", cwd, "_en"));
    }

    static Prompt prompt(Locale locale) {
        Prompt prompt = Prompt.of("hi");
        prompt.locale(locale);
        return prompt;
    }

    /**
     * @param tag 工具上下文注入的 __locale（null 表示未注入，用默认语言）
     */
    static Map<String, String> renderOf(TodoTalent talent, Prompt prompt, String tag, Path cwd, String suffix) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        String dir = cwd.toString();

        if (tag == null) {
            //默认语言：description() 无请求上下文
            out.put("todo_description" + suffix, talent.description(null));
        }
        out.put("todo_instruction" + suffix, talent.getInstruction(prompt));
        out.put("todo_tools", GoldenFiles.dumpTools(talent.getTools(prompt)));

        out.put("todo_read_empty" + suffix, talent.todoRead(dir, "s1", tag));
        out.put("todo_write_plain" + suffix, talent.todoWrite("just notes", dir, "s2", tag));
        out.put("todo_write_pending" + suffix, talent.todoWrite("- [x] a\n- [/] b\n- [ ] c", dir, "s3", tag));
        out.put("todo_write_long" + suffix, talent.todoWrite("- [ ] " + repeat('长', 100), dir, "s4", tag));
        out.put("todo_write_done" + suffix, talent.todoWrite("- [x] a\n- [X] b", dir, "s5", tag));
        out.put("todo_read_done" + suffix, talent.todoRead(dir, "s5", tag));
        return out;
    }

    static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
