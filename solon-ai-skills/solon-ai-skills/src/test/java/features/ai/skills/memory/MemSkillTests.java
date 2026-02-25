package features.ai.skills.memory;

import demo.ai.skills.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.noear.redisx.RedisClient;
import org.noear.solon.Solon;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.simple.SimpleResponse;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.skills.memory.MemSearchProvider;
import org.noear.solon.ai.skills.memory.MemSearchResult;
import org.noear.solon.ai.skills.memory.MemSkill;
import org.noear.solon.test.SolonTest;

import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * MemSkill 综合测试
 * 涵盖：认知提取、冲突对比、语义搜索集成、Agent 闭环测试
 */
@SolonTest
public class MemSkillTests {

    private MemSkill memSkill;
    private MemSearchProvider mockSearchProvider;
    private final String testUserId = "user_123";

    @BeforeEach
    public void setup() {
        // 1. 模拟搜索供应商
        mockSearchProvider = Mockito.mock(MemSearchProvider.class);

        // 2. 初始化 Redis（假设测试环境已配置或使用 Mock）
        Properties properties = Solon.cfg().getProp("solon.redis");
        RedisClient redisClient = new RedisClient(properties);

        // 3. 实例化 MemSkill
        memSkill = new MemSkill(redisClient, mockSearchProvider);

        // 清理历史数据防止干扰
        memSkill.prune("test_hobby", testUserId);
        memSkill.prune("user_profile", testUserId);
    }

    // --- 1. 核心逻辑测试：提取与反思 (Extract & Reflection) ---

    @Test
    public void testExtractWithReflection() {
        String key = "test_hobby";

        // 第一次存储：存入初始信息
        String ret1 = memSkill.extract(key, "喜欢游泳", 5, testUserId);
        Assertions.assertTrue(ret1.contains("操作成功"));

        // 第二次存储：触发“认知对比”
        String ret2 = memSkill.extract(key, "现在更喜欢滑雪了", 8, testUserId);

        System.out.println("[认知对比反馈]:\n" + ret2);

        // 验证是否包含了旧信息（反思逻辑）
        Assertions.assertTrue(ret2.contains("发现历史记录"));
        Assertions.assertTrue(ret2.contains("喜欢游泳"));
        Assertions.assertTrue(ret2.contains("认知进化"));
    }

    // --- 2. 语义搜索模拟测试 (Search) ---

    @Test
    public void testSearchIntegration() {
        // 模拟搜索返回结果
        MemSearchResult mockResult = new MemSearchResult("user_profile", "用户是一名 Java 架构师", 5, "2026-02-22 10:00:00");

        when(mockSearchProvider.search(anyString(), eq("职业"), anyInt()))
                .thenReturn(Collections.singletonList(mockResult));

        // 调用搜索工具
        String searchRet = memSkill.search("职业", testUserId);

        Assertions.assertTrue(searchRet.contains("Java 架构师"));
        Assertions.assertTrue(searchRet.contains("2026-02-22"));
    }

    // --- 3. 认知整合测试 (Consolidate) ---

    @Test
    public void testConsolidate() {
        // 预存两个碎片
        memSkill.extract("frag_1", "喜欢红色", 3, testUserId);
        memSkill.extract("frag_2", "喜欢跑车", 3, testUserId);

        // 执行整合
        String consRet = memSkill.consolidate(
                Arrays.asList("frag_1", "frag_2"),
                "car_pref",
                "用户对红色的高性能跑车有强烈偏好",
                testUserId
        );

        Assertions.assertTrue(consRet.contains("心智进化成功"));

        // 验证旧碎片已被修剪 (Prune)
        String recallNone = memSkill.recall("frag_1", testUserId);
        Assertions.assertTrue(recallNone.contains("未找到"));

        // 验证新认知已存入
        String recallNew = memSkill.recall("car_pref", testUserId);
        Assertions.assertTrue(recallNew.contains("强烈偏好"));
    }

    // --- 4. Agent 集成测试 (模拟 AI 真实感知) ---

    @Test
    public void testAgentMindEvolution() throws Throwable {
        // 使用一个真实的 ChatModel (如 DeepSeek, OpenAI 等)
        ChatModel chatModel = LlmUtil.getChatModel();
        AgentSession session = InMemoryAgentSession.of(testUserId);

        SimpleAgent agent = SimpleAgent.of(chatModel)
                .role("全知记忆管家")
                .defaultSkillAdd(memSkill)
                .build();

        // 步骤 1：告知信息（Agent 会调用 mem_extract）
        agent.prompt("记一下，我最近在研究 Solon AI 框架。")
                .session(session)
                .call();

        // 步骤 2：变更信息（Agent 调用 mem_extract，触发反馈后应在回复中体现反思）
        SimpleResponse resp = agent.prompt("别研究 Solon AI 了，帮我看看 MemSkill 怎么实现。")
                .session(session)
                .call();

        System.out.println("AI 进化回复: " + resp.getContent());
        // 期望 AI 回复中包含类似：“好的，已更新认知。我记得你刚才还在看框架，现在重点转向 MemSkill 了。”
    }

    @Test
    public void testChatMindEvolution() throws Throwable {
        // 使用一个真实的 ChatModel (如 DeepSeek, OpenAI 等)
        ChatModel chatModel = LlmUtil.getChatModel();
        AgentSession session = InMemoryAgentSession.of(testUserId);


        // 步骤 1：告知信息（Agent 会调用 mem_extract）
        chatModel.prompt("记一下，我最近在研究 Solon AI 框架。")
                .session(session)
                .options(o -> o.skillAdd(memSkill))
                .call();

        // 步骤 2：变更信息（Agent 调用 mem_extract，触发反馈后应在回复中体现反思）
        ChatResponse resp = chatModel.prompt("别研究 Solon AI 了，帮我看看 MemSkill 怎么实现。")
                .session(session)
                .options(o -> o.skillAdd(memSkill))
                .call();

        System.out.println("AI 进化回复: " + resp.getContent());
        // 期望 AI 回复中包含类似：“好的，已更新认知。我记得你刚才还在看框架，现在重点转向 MemSkill 了。”
    }
}