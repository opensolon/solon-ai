package demo.ai.mcp.server.outputschema.dataobject;

import org.noear.solon.annotation.Param;

/**
 * @Auther: ityangs@163.com
 * @Date: 2025/5/20 16:01
 * @Description:
 */
public class CityInfo {
    @Param(description = "城市名")
    private String name;

    @Param(description = "城市编码")
    private String code;

    // 构造器、getter、setter 省略
}