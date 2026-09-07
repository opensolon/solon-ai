package features.ai.trace;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.agent.trace.Metrics;

/**
 * Metrics 用量聚合与缓存命中率（getCacheRate）单元测试
 *
 * 覆盖：addUsage 累加、addMetrics 父子汇总、执行增量、聚合缓存率、
 * reset 清零、空指标缓存率、异常数据收敛到 0-100
 */
public class MetricsTest {

    private AiUsage usage(long promptTokens, long completionTokens,
                          long cacheCreationInputTokens, long cacheReadInputTokens) {
        return AiUsage.builder()
                .promptTokens(promptTokens)
                .thinkTokens(10L)
                .completionTokens(completionTokens)
                .totalTokens(promptTokens + completionTokens)
                .cacheCreationInputTokens(cacheCreationInputTokens)
                .cacheReadInputTokens(cacheReadInputTokens)
                .build();
    }

    private AiUsage fullUsage(long factor) {
        return AiUsage.builder()
                .promptTokens(100L * factor)
                .thinkTokens(10L * factor)
                .completionTokens(20L * factor)
                .totalTokens(120L * factor)
                .cacheCreationInputTokens(30L * factor)
                .cacheReadInputTokens(40L * factor)
                .cacheCreation5mInputTokens(7L * factor)
                .cacheCreation1hInputTokens(8L * factor)
                .webSearchRequests(2L * factor)
                .webFetchRequests(3L * factor)
                .build();
    }

    @Test
    @DisplayName("addUsage 累加全部数值用量字段")
    public void testAddUsageAccumulate() {
        Metrics metrics = new Metrics();
        metrics.addUsage(fullUsage(1L));
        metrics.addUsage(fullUsage(2L));

        assertFullUsage(metrics, 3L);
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
    @DisplayName("addMetrics 父子 Agent 汇总全部数值用量字段")
    public void testAddMetricsMerge() {
        Metrics parent = new Metrics();
        Metrics child = new Metrics();
        child.addUsage(fullUsage(1L));
        child.addUsage(fullUsage(2L));

        parent.addMetrics(child);
        assertFullUsage(parent, 3L);
        Assertions.assertEquals(40, parent.getCacheRate());
    }

    @Test
    @DisplayName("deltaSince 只返回恢复执行后的新增用量")
    public void testDeltaSince() {
        Metrics metrics = new Metrics();
        metrics.addUsage(fullUsage(1L));
        metrics.addTotalDuration(100L);
        Metrics baseline = metrics.snapshot();

        metrics.addUsage(fullUsage(2L));
        metrics.addTotalDuration(200L);
        Metrics delta = metrics.deltaSince(baseline);

        assertFullUsage(delta, 2L);
        Assertions.assertEquals(200L, delta.getTotalDuration());
        assertFullUsage(metrics, 3L);
        Assertions.assertEquals(300L, metrics.getTotalDuration());

        Metrics parent = new Metrics();
        parent.addMetrics(baseline);
        parent.addMetrics(delta);
        assertFullUsage(parent, 3L);
        Assertions.assertEquals(0L, parent.getTotalDuration(), "父级不应累加子级耗时");
    }

    @Test
    @DisplayName("reset 后全部归零，缓存率为 0")
    public void testReset() {
        Metrics metrics = new Metrics();
        metrics.addUsage(fullUsage(1L));
        metrics.setTotalDuration(1234L);

        metrics.reset();
        assertFullUsage(metrics, 0L);
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
    @DisplayName("异常数据：聚合缓存读取大于输入时收敛到 100%")
    public void testOverflowClampTo100() {
        Metrics metrics = new Metrics();
        metrics.addUsage(usage(1000L, 100L, 0L, 1500L));
        metrics.addUsage(usage(1000L, 100L, 0L, 1500L));

        Assertions.assertEquals(2000L, metrics.getPromptTokens());
        Assertions.assertEquals(100, metrics.getCacheRate());
    }

    @Test
    @DisplayName("异常数据：缓存读取为负数时收敛到 0%")
    public void testNegativeClampToZero() {
        Metrics metrics = new Metrics();
        metrics.addUsage(usage(100L, 10L, 0L, -1L));

        Assertions.assertEquals(0, metrics.getCacheRate());
    }

    @Test
    @DisplayName("setter 直接赋值生效")
    public void testSetters() {
        Metrics metrics = new Metrics();
        metrics.setPromptTokens(100L);
        metrics.setThinkTokens(10L);
        metrics.setCompletionTokens(50L);
        metrics.setTotalTokens(150L);
        metrics.setCacheCreationInputTokens(30L);
        metrics.setCacheReadInputTokens(70L);
        metrics.setCacheCreation5mInputTokens(7L);
        metrics.setCacheCreation1hInputTokens(8L);
        metrics.setWebSearchRequests(2L);
        metrics.setWebFetchRequests(3L);
        metrics.setTotalDuration(900L);
        metrics.addTotalDuration(99L);

        Assertions.assertEquals(100L, metrics.getPromptTokens());
        Assertions.assertEquals(10L, metrics.getThinkTokens());
        Assertions.assertEquals(50L, metrics.getCompletionTokens());
        Assertions.assertEquals(150L, metrics.getTotalTokens());
        Assertions.assertEquals(30L, metrics.getCacheCreationInputTokens());
        Assertions.assertEquals(70L, metrics.getCacheReadInputTokens());
        Assertions.assertEquals(7L, metrics.getCacheCreation5mInputTokens());
        Assertions.assertEquals(8L, metrics.getCacheCreation1hInputTokens());
        Assertions.assertEquals(2L, metrics.getWebSearchRequests());
        Assertions.assertEquals(3L, metrics.getWebFetchRequests());
        Assertions.assertEquals(999L, metrics.getTotalDuration());
        Assertions.assertEquals(70, metrics.getCacheRate());
    }

    private void assertFullUsage(Metrics metrics, long factor) {
        Assertions.assertEquals(100L * factor, metrics.getPromptTokens());
        Assertions.assertEquals(10L * factor, metrics.getThinkTokens());
        Assertions.assertEquals(20L * factor, metrics.getCompletionTokens());
        Assertions.assertEquals(120L * factor, metrics.getTotalTokens());
        Assertions.assertEquals(30L * factor, metrics.getCacheCreationInputTokens());
        Assertions.assertEquals(40L * factor, metrics.getCacheReadInputTokens());
        Assertions.assertEquals(7L * factor, metrics.getCacheCreation5mInputTokens());
        Assertions.assertEquals(8L * factor, metrics.getCacheCreation1hInputTokens());
        Assertions.assertEquals(2L * factor, metrics.getWebSearchRequests());
        Assertions.assertEquals(3L * factor, metrics.getWebFetchRequests());
    }
}
