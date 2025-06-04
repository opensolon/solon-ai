package demo.ai.mcp.server.outputschema.dataobject;

import org.noear.solon.annotation.Param;

/**
 * @Auther: ityangs@163.com
 * @Date: 2025/5/20 15:59
 * @Description:
 */
public class UserInfo {
    @Param(description = "用户名")
    private String name;

    @Param(description = "年龄")
    private Integer age;

    @Param(description = "性别。0表示女，1表示男")
    private Integer gender;
}

