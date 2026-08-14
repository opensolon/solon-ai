package features.ai.trace;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.agent.trace.Metrics;

/**
 * Metrics 令牌聚合与缓存命中率（getCacheRate）单元测试
 *
 * 覆盖：addUsage 累加、addMetrics 父子汇总、聚合缓存率、
 * reset 清零、空指标缓存率、异常数据超 100 收敛
 */
public class MetricsTest {

    private AiUsage usage(long promptTokens, long completionTokens,
                          long cacheCreationInputTokens, long cacheReadInputTokens) {
        return new AiUsage(promptTokens, 10L, completionTokens, promptTokens + completionTokens,
                cacheCreationInputTokens, cacheReadInputTokens, null);
    }

    @Test
    @DisplayName("addUsage 累加全部令牌字段")
    public void testAddUsageAccumulate() {
        Metrics metrics = new Metrics();
        metrics.addUsage(usage(8000L, 2000L, 1000L, 2000L));
        metrics.addUsage(usage(2000L, 500L, 500L, 3000L));

        Assertions.assertEquals(10000L, metrics.getPromptTokens());
        Assertions.assertEquals(2500L, metrics.getCompletionTokens());
        Assertions.assertEquals(12500L, metrics.getTotalTokens());
        Assertions.assertEquals(1500L, metrics.getCacheCreationInputTokens());
        Assertions.assertEquals(5000L, metrics.getCacheReadInputTokens());
    }

    @Test
    @DisplayName("聚合缓存率按总量计算：5000/10000 → 50%")
    public void testAggregatedCacheRate() {
        Metrics metrics = new Metrics();
        metrics.addUsage(usage(8000L, 2000L, 1000L, 2000L));
        metrics.addUsage(usage(2000L, 500L, 500L, 3000L));

        Assertions.assertEquals(50, metrics.getCacheRate());
    }

    @Test
    @DisplayName("addMetrics 父子 Agent 汇总后缓存率正确")
    public void testAddMetricsMerge() {
        Metrics parent = new Metrics();
        Metrics child = new Metrics();
        child.addUsage(usage(8000L, 2000L, 1000L, 2000L));
        child.addUsage(usage(2000L, 500L, 500L, 3000L));

        parent.addMetrics(child);
        Assertions.assertEquals(10000L, parent.getPromptTokens());
        Assertions.assertEquals(5000L, parent.getCacheReadInputTokens());
        Assertions.assertEquals(50, parent.getCacheRate());
    }

    @Test
    @DisplayName("reset 后全部归零，缓存率为 0")
    public void testReset() {
        Metrics metrics = new Metrics();
        metrics.addUsage(usage(8000L, 2000L, 1000L, 2000L));
        metrics.setTotalDuration(1234L);

        metrics.reset();
        Assertions.assertEquals(0L, metrics.getPromptTokens());
        Assertions.assertEquals(0L, metrics.getCompletionTokens());
        Assertions.assertEquals(0L, metrics.getTotalTokens());
        Assertions.assertEquals(0L, metrics.getCacheCreationInputTokens());
        Assertions.assertEquals(0L, metrics.getCacheReadInputTokens());
        Assertions.assertEquals(0L, metrics.getTotalDuration());
        Assertions.assertEquals(0, metrics.getCacheRate());
    }

    @Test
    @DisplayName("空指标缓存率为 0（防除零）")
    public void testEmptyMetricsCacheRate() {
        Metrics metrics = new Metrics();
        Assertions.assertEquals(0, metrics.getCacheRate());
    }

    @Test
    @DisplayName("异常数据：聚合缓存读取 3000 > 输入 2000 → 收敛到 100%")
    public void testOverflowClampTo100() {
        Metrics metrics = new Metrics();
        metrics.addUsage(usage(1000L, 100L, 0L, 1500L));
        metrics.addUsage(usage(1000L, 100L, 0L, 1500L));

        Assertions.assertEquals(2000L, metrics.getPromptTokens());
        Assertions.assertEquals(100, metrics.getCacheRate());
    }

    @Test
    @DisplayName("setter 直接赋值生效")
    public void testSetters() {
        Metrics metrics = new Metrics();
        metrics.setPromptTokens(100L);
        metrics.setCompletionTokens(50L);
        metrics.setTotalTokens(150L);
        metrics.setCacheCreationInputTokens(30L);
        metrics.setCacheReadInputTokens(70L);
        metrics.setTotalDuration(999L);

        Assertions.assertEquals(100L, metrics.getPromptTokens());
        Assertions.assertEquals(50L, metrics.getCompletionTokens());
        Assertions.assertEquals(150L, metrics.getTotalTokens());
        Assertions.assertEquals(30L, metrics.getCacheCreationInputTokens());
        Assertions.assertEquals(70L, metrics.getCacheReadInputTokens());
        Assertions.assertEquals(999L, metrics.getTotalDuration());
        Assertions.assertEquals(70, metrics.getCacheRate());
    }
}
