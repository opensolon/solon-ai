package features.ai.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.jsonSchema.JsonSchema;
import com.fasterxml.jackson.module.jsonSchema.JsonSchemaGenerator;
import org.junit.jupiter.api.Test;
import org.noear.snack.ONode;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.MethodFunctionTool;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.chat.tool.ToolSchemaUtil;
import org.noear.solon.annotation.Param;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;

/**
 * @author noear 2025/4/29 created
 */
public class ToolSchemaUtilTest {
    @Test
    public void type_case1() {
        ONode schemaNode = new ONode();
        ToolSchemaUtil.buildToolParamNode(BigDecimal.class, "test", schemaNode);

        System.out.println(schemaNode);
        assert "{\"type\":\"number\",\"description\":\"test\"}"
                .equals(schemaNode.toJson());
    }

    @Test
    public void type_case2() {
        ONode schemaNode = new ONode();
        ToolSchemaUtil.buildToolParamNode(String[].class, "test", schemaNode);

        System.out.println(schemaNode);
        assert "{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"test\"}"
                .equals(schemaNode.toJson());
    }


    @Test
    public void entity_case1() {
        ONode schemaNode = new ONode();
        ToolSchemaUtil.buildToolParamNode(User.class, "test", schemaNode);

        System.out.println(schemaNode);
        assert "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\",\"description\":\"用户Id\"},\"name\":{\"type\":\"string\",\"description\":\"用户名\"}},\"required\":[\"id\",\"name\"],\"description\":\"test\"}"
                .equals(schemaNode.toJson());
    }

    @Test
    public void entity_case2() {
        ONode schemaNode = new ONode();
        ToolSchemaUtil.buildToolParamNode(User[].class, "test", schemaNode);

        System.out.println(schemaNode);
        assert "{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\",\"description\":\"用户Id\"},\"name\":{\"type\":\"string\",\"description\":\"用户名\"}},\"required\":[\"id\",\"name\"]},\"description\":\"test\"}"
                .equals(schemaNode.toJson());
    }

    @Test
    public void entity_case3() {
        CaseTool caseTool = new CaseTool();
        MethodToolProvider provider = new MethodToolProvider(caseTool);
        FunctionTool functionTool = new ArrayList<>(provider.getTools()).get(0);

        System.out.println(functionTool.inputSchema());

        assert "{\"type\":\"object\",\"properties\":{\"caseBo\":{\"type\":\"object\",\"properties\":{\"caseId\":{\"type\":\"integer\",\"description\":\"案件ID, 传递时使用String类型\"},\"comMeaDuration\":{\"type\":\"integer\",\"description\":\"行政强制措施期限\"},\"aFloat1\":{\"type\":\"number\",\"description\":\"aFloat1\"},\"aDouble1\":{\"type\":\"number\",\"description\":\"aDouble1\"},\"discretionSummary\":{\"type\":\"string\",\"description\":\"自由裁量情况总结\"},\"ext\":{\"type\":\"object\",\"description\":\"案件的拓展数据\"},\"alist1\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"alist1\"},\"penaltyNoticeTime\":{\"type\":\"string\",\"format\":\"date-time\",\"description\":\"行政处罚告知时间\"},\"needDefense\":{\"type\":\"boolean\",\"description\":\"是否陈述申辩\"},\"defenseRecorder\":{\"type\":\"string\",\"description\":\"陈述申辩记录人\"},\"defensePerson\":{\"type\":\"string\",\"description\":\"陈述申辩人\"},\"caseVal\":{\"type\":\"number\",\"description\":\"案值（万元）\"},\"caseVal2\":{\"type\":\"integer\",\"description\":\"案值2（万元）\"}},\"required\":[\"caseId\",\"comMeaDuration\",\"aFloat1\",\"aDouble1\",\"discretionSummary\",\"ext\",\"alist1\",\"penaltyNoticeTime\",\"needDefense\",\"defenseRecorder\",\"defensePerson\",\"caseVal\",\"caseVal2\"],\"description\":\"查询条件\"},\"pageNum\":{\"type\":\"integer\",\"description\":\"页码\"},\"pageSize\":{\"type\":\"integer\",\"description\":\"每页条数\"}},\"required\":[\"caseBo\",\"pageNum\",\"pageSize\"]}"
                .equals(functionTool.inputSchema());
    }

    @Test
    public void entity_case4() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();

        JsonSchemaGenerator generator = new JsonSchemaGenerator(mapper);
        JsonSchema schema = generator.generateSchema(CaseBo.class);
        JsonNode jsonNode = mapper.valueToTree(schema);
        System.out.println(jsonNode.toPrettyString());
    }

    public static class User {
        @Param(description = "用户Id")
        public int id;
        @Param(description = "用户名")
        public String name;
    }
}