package features.ai.core;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;

/**
 *
 * @author noear 2025/11/11 created
 *
 */
public class ToolSchemaUtilTest3 {

    @Test
    public void case1(){
        MethodToolProvider provider = new MethodToolProvider(new TestMcpServerEndpoint());

        Assertions.assertEquals(1, provider.getTools().size());
        for (FunctionTool tool : provider.getTools()) {
            System.out.println(tool.outputSchema());
            Assertions.assertEquals(
                    "{\"type\":\"object\",\"properties\":{\"success\":{\"type\":\"boolean\"},\"data\":{\"type\":\"object\"},\"message\":{\"type\":\"string\"}},\"required\":[\"success\",\"data\",\"message\"]}",
                    tool.outputSchema());
            break;
        }
    }

    @AllArgsConstructor
    @Data
    public static class ToolResult<T> {
        @Param(value = "是否执行成功", required = true)
        private Boolean success;

        @Param(value = "执行成功的结果")
        private T data;

        @Param(value = "执行错误的信息")
        private String message;

        public static ToolResult SUCCESS = new ToolResult<>(true, null, null);

    }

    public class TestMcpServerEndpoint {
        @ToolMapping(description = "根据ID查询亲属信息")
        public ToolResult<Void> getKinsfolkById(@Param(value = "亲属ID", required = true) Long id) {
            return ToolResult.SUCCESS;
        }
    }
}
