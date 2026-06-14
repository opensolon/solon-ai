package features.ai.talents.sys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.talents.sys.ProcessExecutor;

/**
 * 验证 talent-sys 版 ProcessExecutor 面向 LLM 的输出截断与字节级二进制探测逻辑，
 * 确保其与 talent-cli 版能力一致（消除多处实现漂移）。
 * 这两个方法为包级可见，但测试位于不同包，故通过反射调用。
 */
public class ProcessExecutorOutputTest {

    private static boolean isLikelyBinary(byte[] bytes) throws Exception {
        Method m = ProcessExecutor.class.getDeclaredMethod("isLikelyBinary", byte[].class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, (Object) bytes);
    }

    private static boolean isLikelyBinary(String text) throws Exception {
        return isLikelyBinary(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String truncateForLlm(String text, int max) throws Exception {
        Method m = ProcessExecutor.class.getDeclaredMethod("truncateForLlm", String.class, int.class);
        m.setAccessible(true);
        return (String) m.invoke(null, text, max);
    }

    @Test
    public void truncate_keepsHeadAndTail_whenOverLimit() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100_000; i++) {
            sb.append('a');
        }
        for (int i = 0; i < 100_000; i++) {
            sb.append('z');
        }
        String result = truncateForLlm(sb.toString(), 64_000);

        assertTrue(result.length() < sb.length(), "应当被截断");
        assertTrue(result.startsWith("aaa"), "应保留头部");
        assertTrue(result.endsWith("zzz"), "应保留尾部");
        assertTrue(result.contains("输出过大已截断"), "应包含截断占位说明");
    }

    @Test
    public void truncate_noChange_whenUnderLimit() throws Exception {
        String text = "hello world";
        assertEquals(text, truncateForLlm(text, 64_000));
    }

    @Test
    public void binaryProbe_detectsNulBytes() throws Exception {
        String binary = "ELF\u0000\u0000\u0000\u0001\u0002rubbish\u0000\u0007payload";
        assertTrue(isLikelyBinary(binary), "含 NUL 应判定为二进制");
    }

    @Test
    public void binaryProbe_detectsJarBytes() throws Exception {
        // 模拟 jar/zip 文件头（PK\x03\x04）+ 随后含 NUL 的压缩字节
        byte[] jar = new byte[]{
                0x50, 0x4b, 0x03, 0x04, 0x14, 0x00, 0x08, 0x08,
                0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                (byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe, 0x00, 0x12, 0x34, 0x00
        };
        assertTrue(isLikelyBinary(jar), "jar 字节（含 NUL）应判定为二进制");
    }

    @Test
    public void binaryProbe_allowsPlainText() throws Exception {
        assertFalse(isLikelyBinary("normal log line\nwith tabs\tand newlines\r\n"), "纯文本不应误判");
    }

    @Test
    public void binaryProbe_allowsUtf8Chinese() throws Exception {
        // UTF-8 中文为多字节（高位字节 >= 0x80），不应被误判为二进制
        assertFalse(isLikelyBinary("构建成功：编译了 42 个文件，耗时 3.5 秒。\n警告：未发现测试。\n"),
                "UTF-8 中文不应误判为二进制");
    }

    @Test
    public void binaryProbe_allowsAnsiColoredText() throws Exception {
        // ANSI 颜色码（ESC 0x1B）不应被判为二进制
        String colored = "\u001B[31mERROR\u001B[0m something failed\n\u001B[32mOK\u001B[0m done\n";
        assertFalse(isLikelyBinary(colored), "ANSI 着色日志不应误判");
    }
}
