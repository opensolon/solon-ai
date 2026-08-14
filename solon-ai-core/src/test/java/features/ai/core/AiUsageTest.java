package features.ai.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.AiUsage;

/**
 * AiUsage 缓存命中率（getCacheRate）单元测试
 *
 * 覆盖：普通命中率、全命中、未命中、默认构造（无缓存数据）、
 * 异常数据超 100 收敛、输入为 0 防除零
 */
public class AiUsageTest {

    private AiUsage build(long promptTokens, long cacheCreationInputTokens, long cacheReadInputTokens) {
        return new AiUsage(promptTokens, 10L, 200L, promptTokens + 200L,
                cacheCreationInputTokens, cacheReadInputTokens, null);
    }

    @Test
    @DisplayName("普通命中率：1234/8765 → 14%")
    public void testNormalCacheRate() {
        AiUsage usage = build(8765L, 500L, 1234L);
        Assertions.assertEquals(14, usage.getCacheRate());
    }

    @Test
    @DisplayName("全命中：8765/8765 → 100%")
    public void testFullCacheHit() {
        AiUsage usage = build(8765L, 0L, 8765L);
        Assertions.assertEquals(100, usage.getCacheRate());
    }

    @Test
    @DisplayName("未命中：0/8765 → 0%")
    public void testNoCacheHit() {
        AiUsage usage = build(8765L, 8765L, 0L);
        Assertions.assertEquals(0, usage.getCacheRate());
    }

    @Test
    @DisplayName("无缓存数据（默认构造）→ 0%")
    public void testDefaultConstructorNoCache() {
        AiUsage usage = new AiUsage(8765L, 10L, 200L, 8965L, null);
        Assertions.assertEquals(0, usage.getCacheRate());
    }

    @Test
    @DisplayName("异常数据：缓存读取 12000 > 输入 8765 → 收敛到 100%")
    public void testOverflowClampTo100() {
        AiUsage usage = build(8765L, 0L, 12000L);
        Assertions.assertEquals(100, usage.getCacheRate());
    }

    @Test
    @DisplayName("输入为 0（防除零）→ 0%")
    public void testZeroPromptTokens() {
        AiUsage usage = build(0L, 0L, 100L);
        Assertions.assertEquals(0, usage.getCacheRate());
    }

    @Test
    @DisplayName("缓存字段 getter 取值正确")
    public void testCacheFieldGetters() {
        AiUsage usage = build(8000L, 1000L, 2000L);
        Assertions.assertEquals(8000L, usage.promptTokens());
        Assertions.assertEquals(1000L, usage.cacheCreationInputTokens());
        Assertions.assertEquals(2000L, usage.cacheReadInputTokens());
        Assertions.assertEquals(8200L, usage.totalTokens());
        Assertions.assertEquals(200L, usage.completionTokens());
        Assertions.assertEquals(10L, usage.thinkTokens());
    }
}
