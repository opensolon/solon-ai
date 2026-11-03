package features.ai.mcp.server;

import io.modelcontextprotocol.server.transport.ServerHttpHeaderValidator;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.mcp.server.HttpHeaderValidatorAdapter;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 校验器适配与“未配置”判定测试
 *
 * @author noear
 * @since 4.2
 */
public class HttpHeaderValidatorAdapterTest {
    private Map<String, List<String>> headersOf(String name, String value) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put(name, Collections.singletonList(value));
        return headers;
    }

    @Test
    public void case1_newInterfaceIsAdapted() throws Exception {
        ServerHttpHeaderValidator validator = accessor -> {
            if (!accessor.getHeader("role").contains("1")) {
                throw new ServerTransportSecurityException(401, "no auth");
            }
        };

        HttpHeaderValidatorAdapter adapter = new HttpHeaderValidatorAdapter(validator);

        //通过（头名大小写不敏感）
        adapter.validateHeaders(headersOf("ROLE", "1"));

        //拦截
        ServerTransportSecurityException err = Assertions.assertThrows(ServerTransportSecurityException.class,
                () -> adapter.validateHeaders(headersOf("role", "2")));
        Assertions.assertEquals(401, err.getStatusCode());

        //缺失头
        Assertions.assertThrows(ServerTransportSecurityException.class,
                () -> adapter.validateHeaders(Collections.emptyMap()));
    }

    @Test
    public void case2_noopDetection() {
        //未配置（null）
        Assertions.assertTrue(HttpHeaderValidatorAdapter.isNoop(null));

        //旧接口 NOOP
        Assertions.assertTrue(HttpHeaderValidatorAdapter.isNoop(ServerTransportSecurityValidator.NOOP));

        //新接口 NOOP（经适配）
        Assertions.assertTrue(HttpHeaderValidatorAdapter.isNoop(
                new HttpHeaderValidatorAdapter(ServerHttpHeaderValidator.NOOP)));

        //真实校验器
        ServerTransportSecurityValidator real = headers -> {
        };
        Assertions.assertFalse(HttpHeaderValidatorAdapter.isNoop(real));

        ServerHttpHeaderValidator real2 = accessor -> {
        };
        Assertions.assertFalse(HttpHeaderValidatorAdapter.isNoop(new HttpHeaderValidatorAdapter(real2)));
    }

    @Test
    public void case3_headerAccessorSemantics() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Origin", Arrays.asList("http://a.com", "http://b.com"));

        HttpHeaderValidatorAdapter.MapHeaderAccessor accessor = new HttpHeaderValidatorAdapter.MapHeaderAccessor(headers);

        Assertions.assertEquals(2, accessor.getHeader("origin").size());
        Assertions.assertEquals(2, accessor.getHeader("Origin").size());
        Assertions.assertTrue(accessor.getHeader("host").isEmpty());
        Assertions.assertEquals(Collections.singletonList("Origin"), accessor.getHeaderNames());
    }
}
