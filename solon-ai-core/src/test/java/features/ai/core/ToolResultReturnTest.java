package features.ai.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.annotation.Param;

import java.util.Collections;
import java.util.Map;

/**
 * 工具方法返回 {@link ToolResult}（错误标记信封）时：
 * 不生成 outputSchema（与返回 String 一致），且 call 能拿到 isError。
 *
 * @author noear 2026/?? created
 */
public class ToolResultReturnTest {

    @Test
    public void outputSchemaIsEmpty() {
        MethodToolProvider provider = new MethodToolProvider(new DemoTalent());

        FunctionTool tool = provider.getTools().iterator().next();
        Assertions.assertEquals("", tool.outputSchema());
    }

    @Test
    public void callKeepsErrorFlag() throws Throwable {
        MethodToolProvider provider = new MethodToolProvider(new DemoTalent());

        FunctionTool tool = provider.getTools().iterator().next();
        Map<String, Object> args = Collections.singletonMap("id", 1);

        ToolResult ok = tool.call(args);
        Assertions.assertFalse(ok.isError());
        Assertions.assertEquals("ok:1", ok.getContent());

        args = Collections.singletonMap("id", 0);
        ToolResult err = tool.call(args);
        Assertions.assertTrue(err.isError());
        Assertions.assertEquals("bad:0", err.getContent());
    }

    public static class DemoTalent {
        @ToolMapping(name = "demo", description = "演示")
        public ToolResult demo(@Param("id") int id) {
            if (id == 0) {
                return ToolResult.error("bad:" + id);
            }
            return ToolResult.success("ok:" + id);
        }
    }
}
