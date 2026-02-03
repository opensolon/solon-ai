package features.ai.react.intercept;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.intercept.ToolRetryInterceptor;
import org.noear.solon.ai.chat.interceptor.ToolChain;
import org.noear.solon.ai.chat.interceptor.ToolRequest;
import org.noear.solon.ai.chat.tool.FunctionTool;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ToolRetryInterceptorTest {

    @Test
    public void testSuccessfulExecution() throws Throwable {
        // 验证正常执行路径
        ToolRetryInterceptor interceptor = new ToolRetryInterceptor(3, 100);
        ToolChain chain = mock(ToolChain.class);
        ToolRequest req = mock(ToolRequest.class);
        FunctionTool tool = mock(FunctionTool.class);

        when(chain.getTool()).thenReturn(tool);
        when(chain.doIntercept(req)).thenReturn("Success");

        String result = interceptor.interceptTool(req, chain);
        assertEquals("Success", result);
        verify(chain, times(1)).doIntercept(req);
    }

    @Test
    public void testIllegalArgumentException_NoRetry() throws Throwable {
        // 验证参数错误（自愈逻辑）：不重试，直接返回 Observation 给 AI
        ToolRetryInterceptor interceptor = new ToolRetryInterceptor(3, 100);
        ToolChain chain = mock(ToolChain.class);
        ToolRequest req = mock(ToolRequest.class);
        FunctionTool tool = mock(FunctionTool.class);

        when(tool.name()).thenReturn("calculator");
        when(chain.getTool()).thenReturn(tool);
        // 模拟参数异常
        when(chain.doIntercept(req)).thenThrow(new IllegalArgumentException("Missing 'a'"));

        String result = interceptor.interceptTool(req, chain);

        // 验证：包含提示信息，且只调用了一次（没有重试）
        assertTrue(result.contains("Invalid arguments"));
        assertTrue(result.contains("Please fix the arguments"));
        verify(chain, times(1)).doIntercept(req);
    }

    @Test
    public void testPhysicalRetryUntilSuccess() throws Throwable {
        // 验证物理异常：第二次重试成功
        ToolRetryInterceptor interceptor = new ToolRetryInterceptor(3, 100);
        ToolChain chain = mock(ToolChain.class);
        ToolRequest req = mock(ToolRequest.class);
        FunctionTool tool = mock(FunctionTool.class);

        when(tool.name()).thenReturn("weather_api");
        when(chain.getTool()).thenReturn(tool);

        // 第一次抛异常，第二次返回成功
        when(chain.doIntercept(req))
                .thenThrow(new RuntimeException("Network timeout"))
                .thenReturn("Sunny");

        String result = interceptor.interceptTool(req, chain);

        assertEquals("Sunny", result);
        verify(chain, times(2)).doIntercept(req);
    }

    @Test
    public void testExhaustedRetries() throws Throwable {
        // 验证重试耗尽
        int maxRetries = 3;
        ToolRetryInterceptor interceptor = new ToolRetryInterceptor(maxRetries, 10);
        ToolChain chain = mock(ToolChain.class);
        ToolRequest req = mock(ToolRequest.class);
        FunctionTool tool = mock(FunctionTool.class);

        when(tool.name()).thenReturn("database");
        when(chain.getTool()).thenReturn(tool);
        when(chain.doIntercept(req)).thenThrow(new RuntimeException("DB Down"));

        String result = interceptor.interceptTool(req, chain);

        // 验证：包含重试耗尽后的错误提示
        assertTrue(result.contains("Execution error in tool [database]"));
        assertTrue(result.contains("DB Down"));
        verify(chain, times(maxRetries)).doIntercept(req);
    }
}