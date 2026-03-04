package features.ai.skills.data;

import demo.ai.skills.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.redisx.RedisClient;
import org.noear.solon.Solon;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.simple.SimpleResponse;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.skills.data.RedisSkill;
import org.noear.solon.test.SolonTest;

import java.util.Properties;

/**
 * RedisSkill 综合测试
 * 涵盖：意图识别检测、Agent 集成调用存取
 */
@SolonTest
public class RedisSkillTests {

    private RedisSkill redisSkill;

    @BeforeEach
    public void setup() {
        // 从容器获取或根据配置实例化（假设测试环境已配置 redis）
        Properties properties = Solon.cfg().getProp("solon.redis");
        redisSkill = new RedisSkill(new RedisClient(properties));
    }


    // --- 1. 工具方法直接调用测试 ---

    @Test
    public void testToolMethodDirectly() {
        String key = "test_key";
        String value = "hello_solon_ai";

        // 测试存储
        String setRet = redisSkill.set(key, value);
        Assertions.assertTrue(setRet.contains("记在我的笔记里了"));

        // 测试读取
        String getRet = redisSkill.get(key);
        Assertions.assertTrue(getRet.contains(value));

        // 测试读取不存在的键
        String getNone = redisSkill.get("none_key");
        Assertions.assertTrue(getNone.contains("没找到"));
    }

    // --- 2. Agent 集成测试 (模拟 AI 完整链路) ---

    @Test
    public void testAgentIntegration() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        SimpleAgent agent = SimpleAgent.of(chatModel)
                .role("记忆助手")
                .defaultSkillAdd(redisSkill)
                .build();

        // 步骤1：让 AI 存储信息
        String storeQuery = "我的幸运数字是 888，请记住它。";
        System.out.println("[AI 存储测试]: " + storeQuery);
        SimpleResponse storeResp = agent.prompt(storeQuery).call();
        System.out.println("[AI 结果]：" + storeResp.getContent());
        Assertions.assertTrue(storeResp.getContent().contains("记"));

        // 步骤2：让 AI 找回信息
        String retrieveQuery = "我之前告诉过你我的幸运数字是多少吗？";
        System.out.println("[AI 提取测试]: " + retrieveQuery);
        SimpleResponse retrieveResp = agent.prompt(retrieveQuery).call();

        // 验证 AI 是否调用了 redis_get 并正确回答了内容
        Assertions.assertTrue(retrieveResp.getContent().contains("888"));
    }
}