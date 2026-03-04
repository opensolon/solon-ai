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
            System.out.println(tool.inputSchema());
            Assertions.assertEquals(
                    "{\"type\":\"object\",\"properties\":{\"亲属ID\":{\"type\":\"integer\",\"description\":\"\",\"default\":11}},\"required\":[\"亲属ID\"]}\n",
                    tool.inputSchema());

            System.out.println(tool.outputSchema());
            Assertions.assertEquals(
                    "{\"type\":\"object\",\"properties\":{\"data\":{\"type\":\"object\",\"properties\":{},\"required\":[],\"description\":\"执行成功的结果\"},\"message\":{\"type\":\"string\",\"description\":\"执行错误的信息\"},\"success\":{\"type\":\"boolean\",\"description\":\"是否执行成功\",\"default\":false}},\"required\":[\"success\",\"data\",\"message\"]}",
                    tool.outputSchema());
            break;
        }
    }

    @AllArgsConstructor
    @Data
    public static class ToolResult<T> {
        @Param(description = "是否执行成功", required = true, defaultValue = "false")
        private Boolean success;

        @Param(description = "执行成功的结果")
        private T data;

        @Param(description = "执行错误的信息")
        private String message;

        public static ToolResult SUCCESS = new ToolResult<>(true, null, null);

    }

    public class TestMcpServerEndpoint {
        @ToolMapping(description = "根据ID查询亲属信息")
        public ToolResult<Void> getKinsfolkById(@Param(value = "亲属ID", required = true, defaultValue = "11") Long id) {
            return ToolResult.SUCCESS;
        }
    }
}
