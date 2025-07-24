package demo.ai.mcp.client_ssl;

import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.net.http.impl.HttpSslSupplierDefault;

/**
 * @author noear 2025/7/24 created
 */
public class SslDemo {
    public void case1() {
        //通过 queryString 传递（需要 3.2.1-M1 或之后）
        McpClientProvider toolProvider = McpClientProvider.builder()
                .apiUrl("http://xxx.xxx.xxx/sse?key=yyy")
                .httpSsl(HttpSslSupplierDefault.getInstance())
                .build();
    }
}