package org.noear.solon.ai.skills.data;

import org.noear.redisx.RedisClient;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.annotation.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis 存储技能：为 AI 提供长期记忆能力
 * @author noear
 * @since 3.9.1
 */
public class RedisSkill extends AbsSkill {
    private static final Logger log = LoggerFactory.getLogger(RedisSkill.class);
    private final RedisClient redis;
    private static final String PREFIX = "ai:memory:"; // 强制命名空间，防止覆盖系统数据

    public RedisSkill(RedisClient redis) {
        this.redis = redis;
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
        String content = prompt.getUserContent().toLowerCase();
        // 只有明确提到 记住、存储、查询、回忆 等词汇时才支持
        return content.contains("记住") || content.contains("存储") ||
                content.contains("查询") || content.contains("记得") ||
                content.contains("save") || content.contains("redis");
    }

    @ToolMapping(name = "redis_set", description = "在持久化仓库中存入重要信息。key 应该是简短的标识符。")
    public String set(@Param("key") String key, @Param("value") String value) {
        try {
            String finalKey = PREFIX + key;
            // 存入数据，默认设置较长的有效期（如 30 天），避免无效数据无限堆积
            redis.getBucket().store(finalKey, value, 60 * 60 * 24 * 30);
            log.info("AI Memory Stored: {} -> {}", finalKey, value);
            return "我已经把这件事记在我的笔记里了（Key: " + key + "）。";
        } catch (Exception e) {
            log.error("Redis set error", e);
            return "存储失败，我的记忆模块出了一点小状况。";
        }
    }

    @ToolMapping(name = "redis_get", description = "从持久化仓库中找回之前存入的信息。")
    public String get(@Param("key") String key) {
        try {
            String finalKey = PREFIX + key;
            String val = redis.getBucket().get(finalKey);
            if (val == null) {
                return "我在笔记里没找到关于 [" + key + "] 的内容。";
            }
            return "关于 [" + key + "]，我记得是：" + val;
        } catch (Exception e) {
            log.error("Redis get error", e);
            return "读取失败，我暂时想不起来了。";
        }
    }
}