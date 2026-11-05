package features.ai.harness;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.agent.AgentFactory;

public class AgentFactoryCommandSessionTest {

    /**
     * 白名单数组里只有归组名 "bash"；bash_start / bash_wait / bash_stop 由
     * AgentFactory.toolAddDo 的 "bash" 分支动态展开注册（见该处 case "bash"）。
     * 因此这里断言的是"归组名存在"，而非展开后的工具名。
     */
    @Test
    public void publicToolSetGroupsBashSessionTools() throws Exception {
        assertTrue(toolSetContains("TOOL_ALL_PUBLIC", "bash"));
        // 展开后的三个长命令工具不应以字面量重复出现在白名单里
        assertTrue(!toolSetContains("TOOL_ALL_PUBLIC", "bash_start"));
        // bash_stdin 已并入 bash_wait(chars)，不再作为独立工具暴露
        assertTrue(!toolSetContains("TOOL_ALL_PUBLIC", "bash_stdin"));
    }

    @Test
    public void piToolSetGroupsBashSessionTools() throws Exception {
        assertTrue(toolSetContains("TOOL_PI", "bash"));
        assertTrue(!toolSetContains("TOOL_PI", "bash_start"));
        assertTrue(!toolSetContains("TOOL_PI", "bash_stdin"));
    }

    private static boolean toolSetContains(String fieldName, String toolName) throws Exception {
        Field field = AgentFactory.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        String[] tools = (String[]) field.get(null);
        for (String tool : tools) {
            if (toolName.equals(tool)) {
                return true;
            }
        }
        return false;
    }
}
