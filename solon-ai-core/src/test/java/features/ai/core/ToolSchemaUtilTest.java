package features.ai.core;

import org.junit.jupiter.api.Test;
import org.noear.snack.ONode;
import org.noear.solon.ai.annotation.ToolParam;
import org.noear.solon.ai.chat.tool.ToolSchemaUtil;

import java.math.BigDecimal;

/**
 * @author noear 2025/4/29 created
 */
public class ToolSchemaUtilTest {
    @Test
    public void type_case1() {
        ONode schemaNode = new ONode();
        ToolSchemaUtil.buildToolParamNode(BigDecimal.class, "test", schemaNode);

        System.out.println(schemaNode);
        assert "{\"type\":\"string\",\"description\":\"test\"}".equals(schemaNode.toJson());
    }

    @Test
    public void type_case2() {
        ONode schemaNode = new ONode();
        ToolSchemaUtil.buildToolParamNode(String[].class, "test", schemaNode);

        System.out.println(schemaNode);
        assert "{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"test\"}".equals(schemaNode.toJson());
    }


    @Test
    public void entity_case1() {
        ONode schemaNode = new ONode();
        ToolSchemaUtil.buildToolParamNode(User.class, "test", schemaNode);

        System.out.println(schemaNode);
        assert "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\",\"description\":\"用户Id\"},\"name\":{\"type\":\"string\",\"description\":\"用户名\"}},\"required\":[\"id\",\"name\"],\"description\":\"test\"}".equals(schemaNode.toJson());
    }

    @Test
    public void entity_case2() {
        ONode schemaNode = new ONode();
        ToolSchemaUtil.buildToolParamNode(User[].class, "test", schemaNode);

        System.out.println(schemaNode);
        assert "{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\",\"description\":\"用户Id\"},\"name\":{\"type\":\"string\",\"description\":\"用户名\"}},\"required\":[\"id\",\"name\"]},\"description\":\"test\"}".equals(schemaNode.toJson());
    }

    public static class User {
        @ToolParam(description = "用户Id")
        public int id;
        @ToolParam(description = "用户名")
        public String name;
    }
}