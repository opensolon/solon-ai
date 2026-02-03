package features.ai.skills.social;

import demo.ai.skills.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.agent.simple.SimpleResponse;
import org.noear.solon.ai.skills.social.WeComSkill;

/**
 * 社交/通知类技能单元测试：验证逻辑、签名与报文结构
 */
public class WebComSkillTests {
    private final String apiUrl = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=8a05cb93-7f9a-40ad-af14-7f925acd11aa";

    public WeComSkill getSocialSkill(){
        return new WeComSkill(apiUrl);
    }

    @Test
    public void testMarkdownLogic() {
        WeComSkill skill = getSocialSkill();
        String result = skill.send( "测试内容");

        System.out.println(result);
        Assertions.assertNotNull(result);
    }

    @Test
    public void testAgentDrivenNotification() throws Throwable {
        // 1. 初始化 Agent 并注入钉钉技能
        SimpleAgent agent = SimpleAgent.of(LlmUtil.getChatModel())
                .role("运维告警助手")
                .defaultSkillAdd(getSocialSkill())
                .build();

        // 2. 模拟一段原始的系统日志或异常信息
        String errorLogs = "ERROR 2026-02-01 20:15:01 [main] - Connection timed out to database: 192.168.1.100\n" +
                "WARN 2026-02-01 20:15:05 [main] - Retrying connection (1/3)...";

        String query = "这是刚刚捕获的日志：\n" + errorLogs +
                "\n请分析原因并总结成简短的告警消息，发送到企业微信。标题固定为 '数据库异常告警'。";

        System.out.println("[Agent 正在分析并执行通知...]");

        // 3. 执行任务
        SimpleResponse resp = agent.prompt(query).call();

        System.out.println("[AI 回复]: " + resp.getContent());

        // 4. 验证：AI 是否成功触发了钉钉工具调用
        // 由于 API Token 是模拟或内网的，我们主要验证执行链路是否完整
        Assertions.assertTrue(resp.getContent().contains("成功") ||
                        resp.getContent().toLowerCase().contains("sent"),
                "Agent 应当反馈钉钉消息已发送");
    }
}