package org.noear.solon.ai.skills.data;

import org.noear.redisx.RedisClient;
import org.noear.solon.Utils;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;
import org.noear.solon.lang.Preview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis 存储技能：为 AI 提供跨会话的“长期记忆”能力
 *
 * <p>该技能允许 AI Agent 将关键信息（如用户偏好、任务中间状态、历史事实）以 Key-Value 形式持久化。
 *  相比于 Context Window 的短期记忆，本技能提供了可跨越不同会话周期的状态保持方案。</p>
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class RedisSkill extends AbsSkill {
    private static final Logger LOG = LoggerFactory.getLogger(RedisSkill.class);
    private final RedisClient redis;
    private final String keyPrefix;
    private static final String BASE_PREFIX = "ai:memory:";

    public RedisSkill(RedisClient redis) {
        this(redis, null);
    }

    /**
     * @param keyPrefix 自定义前缀（例如：user_001）
     */
    public RedisSkill(RedisClient redis, String keyPrefix) {
        this.redis = redis;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public String name() {
        return "redis_storage";
    }

    @Override
    public String description() {
        return "长期记忆专家：可以将重要信息持久化存储，以便在之后的对话中找回。";
    }

    @Override
    public boolean isSupported(Prompt prompt) {
        return true;
    }

    /**
     * 获取最终的 Redis Key
     */
    protected String getFinalKey(String key) {
        if (Utils.isNotEmpty(keyPrefix)) {
            return BASE_PREFIX + keyPrefix + ":" + key;
        } else {
            return BASE_PREFIX + key;
        }
    }

    /**
     * 存入记忆。
     * AI 引导：当用户提到“记住我喜欢喝咖啡”时，AI 应生成 key='user_preference_coffee'
     */
    @ToolMapping(name = "redis_set",
            description = "在持久化仓库中存入重要信息。key 应该是简短的标识符。")
    public String set(@Param("key") String key, @Param("value") String value) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Setting key: {} -> {}", key, value);
        }

        try {
            String finalKey = getFinalKey(key);
            // 存入数据，默认设置 30 天有效期
            redis.getBucket().store(finalKey, value, 60 * 60 * 24 * 30);
            LOG.info("AI Memory Stored: {} -> {}", finalKey, value);
            return "我已经把这件事记在我的笔记里了（Key: " + key + "）。";
        } catch (Exception e) {
            LOG.error("Redis set error", e);
            return "存储失败，我的记忆模块出了一点小状况。";
        }
    }

    @ToolMapping(name = "redis_get", description = "从持久化仓库中找回之前存入的信息。")
    public String get(@Param("key") String key) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Getting key: {}", key);
        }

        try {
            String finalKey = getFinalKey(key);
            String val = redis.getBucket().get(finalKey);
            if (val == null) {
                return "我在笔记里没找到关于 [" + key + "] 的内容。";
            }
            return "关于 [" + key + "]，我记得是：" + val;
        } catch (Exception e) {
            LOG.error("Redis get error", e);
            return "读取失败，我暂时想不起来了。";
        }
    }
}