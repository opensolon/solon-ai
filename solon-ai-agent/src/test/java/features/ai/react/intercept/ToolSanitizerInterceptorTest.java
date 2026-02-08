package features.ai.react.intercept;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.intercept.ToolSanitizerInterceptor;
import org.noear.solon.ai.chat.interceptor.ToolChain;
import org.noear.solon.ai.chat.interceptor.ToolRequest;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ToolSanitizerInterceptorTest {

    @Test
    public void testEmptyResultHandling() throws Throwable {
        // 验证 null 或空字符串的容错
        ToolSanitizerInterceptor interceptor = new ToolSanitizerInterceptor();
        ToolChain chain = mock(ToolChain.class);
        ToolRequest req = mock(ToolRequest.class);

        // 模拟返回 null
        when(chain.doIntercept(req)).thenReturn(null);
        String result = interceptor.interceptTool(req, chain).getText();
        assertEquals("[No output from tool]", result);

        // 模拟返回空字符串
        when(chain.doIntercept(req)).thenReturn(new ToolResult(""));
        result = interceptor.interceptTool(req, chain).getText();
        assertEquals("[No output from tool]", result);
    }

    @Test
    public void testCustomSanitizer() throws Throwable {
        // 验证自定义脱敏逻辑（如：隐藏 API Key）
        ToolSanitizerInterceptor interceptor = new ToolSanitizerInterceptor(2000);
        interceptor.setCustomSanitizer(s -> new ToolResult(s.getText().replace("sk-123456", "sk-******")));

        ToolChain chain = mock(ToolChain.class);
        ToolRequest req = mock(ToolRequest.class);
        when(chain.doIntercept(req)).thenReturn(new ToolResult("The key is sk-123456."));

        String result = interceptor.interceptTool(req, chain).getText();
        assertEquals("The key is sk-******.", result);
    }

    @Test
    public void testTruncation() throws Throwable {
        // 验证物理长度截断
        int maxLength = 10;
        ToolSanitizerInterceptor interceptor = new ToolSanitizerInterceptor(maxLength);

        ToolChain chain = mock(ToolChain.class);
        ToolRequest req = mock(ToolRequest.class);
        FunctionTool tool = mock(FunctionTool.class);

        when(tool.name()).thenReturn("long_text_tool");
        when(chain.getTool()).thenReturn(tool);
        // 返回一个超过 10 字符的字符串
        when(chain.doIntercept(req)).thenReturn(new ToolResult("1234567890ABCDE"));

        String result = interceptor.interceptTool(req, chain).getText();

        // 验证：长度应被截断到 maxLength，并附带说明
        assertTrue(result.startsWith("1234567890"));
        assertTrue(result.contains("[Content Truncated due to length]"));
        // 检查实际返回的字符串是否比原串短
        assertTrue(result.length() < "1234567890ABCDE... [Content Truncated due to length]".length());
    }

    @Test
    public void testCombinedLogic() throws Throwable {
        // 验证“脱敏后截断”的组合逻辑
        // 设最大长度为 10
        ToolSanitizerInterceptor interceptor = new ToolSanitizerInterceptor(10);
        // 让脱敏逻辑把长度 10 变成 11
        interceptor.setCustomSanitizer(s -> new ToolResult(s.getText() + "!"));

        ToolChain chain = mock(ToolChain.class);
        ToolRequest req = mock(ToolRequest.class);
        FunctionTool tool = mock(FunctionTool.class);

        when(tool.name()).thenReturn("mix_tool");
        when(chain.getTool()).thenReturn(tool);
        // 原始结果刚好等于最大长度
        when(chain.doIntercept(req)).thenReturn(new ToolResult("0123456789"));

        String result = interceptor.interceptTool(req, chain).getText();

        // 调试打印
        System.out.println("Final Result: " + result);

        // 验证：
        // 1. 结果应该以截断后的前 10 位开头
        assertEquals("0123456789", result.substring(0, 10));
        // 2. 应该包含完整的截断后缀
        assertTrue(result.contains("[Content Truncated due to length]"),
                "Should contain the full truncation marker. Actual: " + result);
    }
}