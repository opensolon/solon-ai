package features.ai.session;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.redisx.RedisClient;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.session.RedisAgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.flow.FlowContext;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * RedisAgentSession 单元测试
 * 覆盖率目标：100%
 */
public class RedisAgentSessionTest {

    private RedisClient getRedisClient() {
        //此处由您手动补入实际的 RedisClient 获取逻辑（或 Mock）
        Properties properties = new Properties();
        properties.put("server","localhost:6379");
        properties.put("password","123456");
        properties.put("db","1");

        return new RedisClient(properties);
    }

    private RedisClient redisClient;
    private final String testId = "test_agent_" + System.currentTimeMillis();

    @BeforeEach
    public void setup() {
        redisClient = getRedisClient();
        // 清理现场
        redisClient.getList(testId + ":messages").clear();
        redisClient.getBucket().remove(testId);
        redisClient.getBucket().remove(testId + ":snapshot");
    }

    @Test
    public void testInit_NewSession() {
        // 覆盖构造函数：Redis 中没有快照的分支
        RedisAgentSession session = new RedisAgentSession(testId, redisClient);

        Assertions.assertEquals(testId, session.getSessionId());
        Assertions.assertNotNull(session.getSnapshot());
        Assertions.assertEquals(testId, session.getSnapshot().getInstanceId());
        // 验证 Agent.KEY_SESSION 注入
        Assertions.assertEquals(session, session.getSnapshot().get(Agent.KEY_SESSION));
    }

    @Test
    public void testInit_ExistingSession() {
        // 预存一个快照到 Redis
        FlowContext oldCtx = FlowContext.of(testId);
        oldCtx.put("key1", "val1");
        redisClient.getBucket().store(testId, oldCtx.toJson());

        // 覆盖构造函数：Redis 中存在快照的分ize
        RedisAgentSession session = new RedisAgentSession(testId, redisClient);

        Assertions.assertEquals("val1", session.getSnapshot().get("key1"));
        Assertions.assertEquals(session, session.getSnapshot().get(Agent.KEY_SESSION));
    }

    @Test
    public void testAddAndGetMessages() {
        RedisAgentSession session = new RedisAgentSession(testId, redisClient);

        // 构造消息：1个System(应过滤), 2个有效消息
        ChatMessage msg1 = ChatMessage.ofUser("User 1");
        ChatMessage msg2 = ChatMessage.ofAssistant("Assistant 1");
        ChatMessage msgSystem = ChatMessage.ofSystem("System Prompt");

        session.addMessage("a");
        session.addMessage("b");
        session.addMessage("c");
        session.addMessage(Arrays.asList(msgSystem, msg1, msg2));

        // 验证 isEmpty
        Assertions.assertFalse(session.isEmpty());

        // 验证 getMessages (默认取最近50条)
        List<ChatMessage> list = session.getLatestMessages(2);
        Assertions.assertEquals(2, list.size());

        // 验证顺序（Redis 是最新的在前面，代码里 Collections.reverse 了，所以 list[0] 应该是旧的，list[1] 是新的）
        // 根据 RedisAgentSession 实现：add 是向 list 头部/尾部 add(toJson)，取决于 RedisList 实现。
        // getLatestMessages 反转了列表，确保逻辑顺序输出。
        Assertions.assertEquals("User 1", list.get(0).getContent());
        Assertions.assertEquals("Assistant 1", list.get(1).getContent());
    }

    @Test
    public void testGetLatestMessages_Empty() {
        RedisAgentSession session = new RedisAgentSession(testId, redisClient);

        // 覆盖 rawList == null || isEmpty 分支
        List<ChatMessage> list = session.getLatestMessages(10);
        Assertions.assertTrue(list.isEmpty());
    }

    @Test
    public void testUpdateSnapshot() {
        RedisAgentSession session = new RedisAgentSession(testId, redisClient);

        FlowContext newCtx = FlowContext.of(testId);
        newCtx.put("status", "running");

        // 执行更新
        session.updateSnapshot(newCtx);

        // 验证内存
        Assertions.assertEquals("running", session.getSnapshot().get("status"));

        // 验证 Redis 持久化 (snapshotKey = instanceId + ":snapshot")
        String json = redisClient.getBucket().get(testId + ":snapshot");
        Assertions.assertNotNull(json);
        Assertions.assertTrue(json.contains("running"));
    }

    @Test
    public void testClear() {
        RedisAgentSession session = new RedisAgentSession(testId, redisClient);
        session.addMessage(Collections.singletonList(ChatMessage.ofUser("temp")));

        Assertions.assertFalse(session.isEmpty());

        session.clear();

        Assertions.assertTrue(session.isEmpty());
    }

    @Test
    public void testConstructor_Exceptions() {
        // 覆盖 Objects.requireNonNull
        Assertions.assertThrows(NullPointerException.class, () -> new RedisAgentSession(null, redisClient));
        Assertions.assertThrows(NullPointerException.class, () -> new RedisAgentSession(testId, null));
    }
}