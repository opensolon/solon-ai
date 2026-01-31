package features.ai.skills.social;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.skills.social.DingTalkSkill;
import org.noear.solon.ai.skills.social.WeComSkill;

/**
 * 社交/通知类技能单元测试
 */
public class SocialSkillTests {

    // 假设的 Webhook 地址
    private final String mockUrl = "https://oapi.dingtalk.com/robot/send?access_token=test";

    @Test
    public void testDingTalkMarkdownLogic() {
        // 只有在提供 title 时，钉钉才会切换到 Markdown 模式
        DingTalkSkill skill = new DingTalkSkill(mockUrl);

        // 模拟 AI 调用：带标题
        String result = skill.send("今日天气", "### 晴天\n温度 25度", null);

        // 这里验证逻辑：如果内部逻辑正确，result 不应为空
        Assertions.assertNotNull(result);
    }

    @Test
    public void testDingTalkSignature() throws Exception {
        // 测试签名计算是否抛出异常（Security 校验）
        String secret = "this_is_a_secret";
        DingTalkSkill skill = new DingTalkSkill(mockUrl, secret);

        // 即使不发送，我们也验证签名 URL 的生成逻辑是否健壮
        // 这里模拟调用内部逻辑，确保 getSignUrl 不会因 Secret 为空而崩掉
        String result = skill.send(null, "测试签名内容", null);

        Assertions.assertNotNull(result);
    }

    @Test
    public void testWeComSmartType() {
        WeComSkill weCom = new WeComSkill(mockUrl);

        // 验证 1：普通文本逻辑
        String textRes = weCom.send("这是一条普通文本");
        Assertions.assertNotNull(textRes);

        // 验证 2：触发 Markdown 逻辑（包含 Markdown 特征符号）
        String mdRes = weCom.send("> 这是一个引用消息\n**加粗文字**");
        Assertions.assertNotNull(mdRes);
    }

    @Test
    public void testIsSupportedLogic() {
        DingTalkSkill dingTalk = new DingTalkSkill(mockUrl);

        // 验证关键词识别
        Assertions.assertTrue(dingTalk.isSupported(Prompt.of("发个钉钉消息")));
        Assertions.assertTrue(dingTalk.isSupported(Prompt.of("Ding one message")));
        Assertions.assertFalse(dingTalk.isSupported(Prompt.of("发邮件给老板")));
    }
}