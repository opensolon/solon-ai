package demo.ai.mcp.client_auth;

import org.noear.solon.ai.mcp.client.McpClientProvider;

/**
 * @author noear 2025/4/21 created
 */
public class AuthDemo {
    public void case1() {
        //通过 queryString 传递（需要 3.2.1-M1 或之后）
        McpClientProvider toolProvider = McpClientProvider.builder()
                .apiUrl("http://xxx.xxx.xxx/sse?key=yyy")
                .build();
    }

    public void case2() {
        //通过 baisc auth 传递
        McpClientProvider toolProvider = McpClientProvider.builder()
                .apiUrl("http://xxx.xxx.xxx/sse")
                .apiKey("yyy")
                .build();
    }

    public void case3() {
        //通过 baisc auth 传递
        McpClientProvider toolProvider = McpClientProvider.builder()
                .apiUrl("http://xxx.xxx.xxx/sse")
                .headerSet("tokey", "yyy")
                .build();
    }
}
