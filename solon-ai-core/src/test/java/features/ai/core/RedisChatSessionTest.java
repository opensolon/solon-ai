package features.ai.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.redisx.RedisClient;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.session.RedisChatSession;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * RedisChatSession 单元测试
 * 重点验证：内存缓存与 Redis 持久化的同步逻辑
 */
public class RedisChatSessionTest {

    private RedisClient getRedisClient() {
        // 此处补入实际的 RedisClient 获取逻辑
        Properties properties = new Properties();
        properties.put("server", "localhost:6379");
        properties.put("password", "123456");
        properties.put("db", "1");

        return new RedisClient(properties);
    }

    private RedisClient redisClient;
    private final String testId = "test_chat_" + System.currentTimeMillis();

    @BeforeEach
    public void setup() {
        redisClient = getRedisClient();
        // 清理现场：RedisChatSession 的 Key 是 instanceId 本身 (或根据你定义的 messagesKey)
        // 根据代码逻辑：this.messagesKey = instanceId + ":messages";
        redisClient.getList(testId + ":messages").clear();
        redisClient.getBucket().remove(testId);
    }

    @Test
    public void testInit_And_Recovery() {
        // 1. 往 Redis 里预存一些消息
        String messagesKey = testId + ":messages";
        redisClient.getList(messagesKey).add(ChatMessage.toJson(ChatMessage.ofUser("1")));
        redisClient.getList(messagesKey).add(ChatMessage.toJson(ChatMessage.ofAssistant("2")));

        // 2. 初始化 Session，验证是否自动从 Redis 加载到内存缓存
        RedisChatSession session = new RedisChatSession(testId, redisClient);

        Assertions.assertEquals(2, session.getMessages().size());
        // 验证顺序：Redis 是列表存储，loadMessagesToCache 内部做了 Collections.reverse
        // 确保读出来的顺序是：[User:1, Assistant:2]
        Assertions.assertEquals("1", session.getMessages().get(0).getContent());
        Assertions.assertEquals("2", session.getMessages().get(1).getContent());
    }

    @Test
    public void testAddMessage_FilteringSystem() {
        RedisChatSession session = new RedisChatSession(testId, redisClient);

        ChatMessage msg1 = ChatMessage.ofUser("Hello");
        ChatMessage msgSystem = ChatMessage.ofSystem("Ignore me in Redis");

        // 添加消息（Collection 接口）
        session.addMessage(Arrays.asList(msg1, msgSystem));

        // 验证内存缓存：内存通常不保留 System 消息（根据 InMemoryChatSession 实现）
        // 如果 InMemoryChatSession 允许保留，则 size 为 2；
        // 但根据 RedisChatSession 的 addMessage 逻辑，System 消息不会写进 Redis
        String messagesKey = testId + ":messages";
        long redisSize = redisClient.getList(messagesKey).size();
        Assertions.assertEquals(1, redisSize, "System message should not be persisted to Redis");
    }

    @Test
    public void testGetLatestMessages() {
        RedisChatSession session = new RedisChatSession(testId, redisClient);

        session.addMessage(Collections.singletonList(ChatMessage.ofUser("msg 1")));
        session.addMessage(Collections.singletonList(ChatMessage.ofUser("msg 2")));
        session.addMessage(Collections.singletonList(ChatMessage.ofUser("msg 3")));

        // 验证滑窗获取（走内存缓存）
        List<ChatMessage> latest = session.getLatestMessages(2);
        Assertions.assertEquals(2, latest.size());
        Assertions.assertEquals("msg 2", latest.get(0).getContent());
        Assertions.assertEquals("msg 3", latest.get(1).getContent());
    }

    @Test
    public void testIsEmptyAndClear() {
        RedisChatSession session = new RedisChatSession(testId, redisClient);

        Assertions.assertTrue(session.isEmpty());

        session.addMessage(Collections.singletonList(ChatMessage.ofUser("test")));
        Assertions.assertFalse(session.isEmpty());

        // 执行清理
        session.clear();

        // 验证内存和 Redis 均已清理
        Assertions.assertTrue(session.isEmpty());
        String messagesKey = testId + ":messages";
        Assertions.assertEquals(0, redisClient.getList(messagesKey).size());
    }

    @Test
    public void testConstructor_Exceptions() {
        Assertions.assertThrows(NullPointerException.class, () -> new RedisChatSession(null, redisClient));
        Assertions.assertThrows(NullPointerException.class, () -> new RedisChatSession(testId, null));
    }
}