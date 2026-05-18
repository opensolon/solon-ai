package features.ai.harness;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.agent.AgentFactory;

public class AgentFactoryCommandSessionTest {

    @Test
    public void publicToolSetIncludesCommandSessionTools() throws Exception {
        assertTrue(toolSetContains("TOOL_ALL_PUBLIC", "bash_start"));
        assertTrue(toolSetContains("TOOL_ALL_PUBLIC", "bash_wait"));
        assertTrue(toolSetContains("TOOL_ALL_PUBLIC", "bash_stdin"));
        assertTrue(toolSetContains("TOOL_ALL_PUBLIC", "bash_stop"));
    }

    @Test
    public void piToolSetIncludesCommandSessionTools() throws Exception {
        assertTrue(toolSetContains("TOOL_PI", "bash_start"));
        assertTrue(toolSetContains("TOOL_PI", "bash_wait"));
        assertTrue(toolSetContains("TOOL_PI", "bash_stdin"));
        assertTrue(toolSetContains("TOOL_PI", "bash_stop"));
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
