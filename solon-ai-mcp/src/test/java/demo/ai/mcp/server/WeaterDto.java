package demo.ai.mcp.server;

import org.noear.solon.annotation.Param;

import java.io.Serializable;

/**
 * @author noear 2025/11/19 created
 */
public class WeaterDto implements Serializable {
    @Param(description = "城市位置")
    public String location;

    @Override
    public String toString() {
        return "WeaterDto{" +
                "location='" + location + '\'' +
                '}';
    }
}
