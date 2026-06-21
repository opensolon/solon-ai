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

    @Test
    public void binaryProbe_allowsMavenStyleOutput() throws Exception {
        // Maven 风格的日志输出（[INFO] Tests run: N, Failures: 0）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("[INFO] Tests run: 3, Failures: 0\n");
        }
        assertFalse(isLikelyBinary(sb.toString()), "Maven 风格日志输出不应误判");
    }

    @Test
    public void binaryProbe_allowsOutputWithProgressArtifacts() throws Exception {
        // 头部含进度条残留（\r + ANSI 擦除），主体为干净文本
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("\r[INFO] Downloading: ").append(i).append("%\u001B[K\n");
        }
        for (int i = 0; i < 100; i++) {
            sb.append("[INFO] Tests run: 3, Failures: 0\n");
        }
        assertFalse(isLikelyBinary(sb.toString()), "含进度条残留的输出不应误判");
    }

    @Test
    public void binaryProbe_allowsLongContinuousText() throws Exception {
        // 长段连续可打印文本（>120 字节），模拟大型编译/日志输出
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz ");
            if (i % 5 == 0) sb.append('\n');
        }
        assertFalse(isLikelyBinary(sb.toString()), "长段连续文本不应误判");
    }

    @Test
    public void binaryProbe_detectsHighControlCharDensity() throws Exception {
        // 高密度控制字符（>50%），无 NUL，模拟压缩数据段
        byte[] data = new byte[1000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 2 == 0 ? 0x01 : 0x02); // 全是 SOH/STX
        }
        assertTrue(isLikelyBinary(data), "高密度控制字符（无 NUL）应判定为二进制");
    }

    @Test
    public void binaryProbe_detectsMixedBinaryWithoutNul() throws Exception {
        // 混合二进制：少量文本 + 大量控制字符，无 NUL
        byte[] data = new byte[500];
        // 前半段：控制字符密集
        for (int i = 0; i < 400; i++) {
            data[i] = (byte) (0x01 + (i % 30)); // 0x01-0x1E 循环
        }
        // 后半段：插一段文本
        String tail = "some text here but not enough to save it from being binary\n";
        System.arraycopy(tail.getBytes(StandardCharsets.UTF_8), 0, data, 400, tail.length());
        assertTrue(isLikelyBinary(data), "控制字符占优的输出应判定为二进制");
    }

    @Test
    public void binaryProbe_detectsNulInLargeBuffer() throws Exception {
        // 大缓冲区（>64KB）中隐藏 NUL
        byte[] data = new byte[100_000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) 'A';
        }
        data[99999] = 0x00; // 尾部 NUL
        assertTrue(isLikelyBinary(data), "大缓冲区尾部 NUL 应判定为二进制");
    }

    @Test
    public void binaryProbe_allowsBackspaceAndFormFeed() throws Exception {
        // 含退格(\b)和换页(\f)的文本输出（某些终端工具会产生）
        String text = "progress: 50%\b\b\b\b100%\n\f\n[INFO] Done\n";
        assertFalse(isLikelyBinary(text), "含退格和换页的文本不应误判");
    }
}
