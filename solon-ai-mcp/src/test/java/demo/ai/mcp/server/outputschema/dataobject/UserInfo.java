package demo.ai.mcp.server.outputschema.dataobject;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.noear.solon.annotation.Param;

import java.util.Date;

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

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Param(description = "创建时间")
    private Date created = new Date(1751798348208L);
}