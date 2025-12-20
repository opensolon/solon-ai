package demo.ai.mcp.client_ssl;

import io.modelcontextprotocol.spec.McpSchema;
import org.noear.solon.Utils;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.net.http.impl.HttpSslSupplierDefault;
import reactor.core.publisher.Mono;

/**
 *
 * @author noear 2025/12/20 created
 *
 */
public class SamplingDemo {
    public void case1() {
        //通过 queryString 传递（需要 3.2.1-M1 或之后）
        McpClientProvider clientProvider = McpClientProvider.builder()
                .apiUrl("http://xxx.xxx.xxx/sse?key=yyy")
                .httpSsl(HttpSslSupplierDefault.getInstance())
                .customize(spec -> {
                    spec.capabilities(McpSchema.ClientCapabilities.builder().sampling().build());
                    spec.sampling(req -> {
                        return Mono.just(new McpSchema.CreateMessageResult());
                    });
                })
                .build();


        clientProvider.getClient()
                .callTool(new McpSchema.CallToolRequest("demo", Utils.asMap("a", 1)))
                .doOnNext(rest -> {
                    System.out.println(rest);
                })
                .subscribe();
    }
}
