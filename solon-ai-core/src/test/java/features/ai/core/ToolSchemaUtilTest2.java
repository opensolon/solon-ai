package features.ai.core;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.annotation.Param;


/**
 *
 * @author noear 2025/11/6 created
 *
 */
public class ToolSchemaUtilTest2 {

    @Test
    public void csae1() {
        MethodToolProvider provider = new MethodToolProvider(new Tools());

        Assertions.assertEquals(1, provider.getTools().size());
        for (FunctionTool tool : provider.getTools()) {
            System.out.println(tool.outputSchema());
            Assertions.assertEquals(
                    "{\"type\":\"object\",\"properties\":{\"items\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\",\"description\":\"用户ID\"},\"name\":{\"type\":\"string\",\"description\":\"用户名\"}},\"required\":[\"id\",\"name\"]}},\"total\":{\"type\":\"integer\"}}}",
                    tool.outputSchema());
            break;
        }
    }

    public static class Tools {
        @ToolMapping(description = "获取用户列表")
        public Result<User> getUserList() {
            return new Result<User>();
        }
    }


    public static class Result<T> {
        @Param(description = "数据列表", required = true)
        private List<T> items;

        @Param(description = "总数", required = true)
        private Integer total;
    }

    // 具体数据类
    public static class User {
        @Param(description = "用户ID", required = true)
        private Long id;

        @Param(description = "用户名", required = true)
        private String name;
    }
}