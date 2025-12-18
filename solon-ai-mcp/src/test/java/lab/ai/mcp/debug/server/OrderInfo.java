package lab.ai.mcp.debug.server;

import lombok.Data;

import java.util.Date;

/**
 *
 * @author noear 2025/12/18 created
 *
 */
@Data
public class OrderInfo {
    private long id;
    private String title;
    private Date created;
}
