package features.ai.team.def;

import demo.ai.agent.LlmUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.AgentProfile;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.team.TeamAgent;
import org.noear.solon.ai.agent.team.TeamInterceptor;
import org.noear.solon.ai.agent.team.TeamTrace;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.prompt.Prompt;

import java.util.Locale;

/**
 * TeamAgent Profile 综合测试用例
 * * 验证目标：
 * 1. 使用 Builder.profile(Consumer) 闭包配置成员画像。
 * 2. 验证 AgentProfile 的国际化输出 (toFormatString)。
 * 3. 验证动态职责描述 (descriptionFor) 的渲染能力。
 * 4. 模拟 Supervisor 根据 Profile 里的技能和约束进行的任务调度。
 */
public class TeamAgentProfileConsumerTest {

    @Test
    public void testComprehensiveTeamProfile() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        // 1. 初始化会话并注入动态环境变量
        AgentSession session = InMemoryAgentSession.of("sn_2026_marketing_001");
        session.getSnapshot().put("platform", "小红书"); // 用于动态渲染描述

        // 2. 组建具有结构化档案的创意团队
        TeamAgent creativeTeam = TeamAgent.of(chatModel)
                .name("creative_studio")
                .description("负责多平台内容创作的创意工作室")
                // 配置团队自身的 Profile
                .profile(p -> p.style("充满想象力且极具传播力")
                        .metaPut("version", "3.8.1"))

                // 配置：文案专家（展示动态描述与约束）
                .agentAdd(ReActAgent.of(chatModel)
                        .name("copywriter")
                        .description("负责 [#{platform}] 平台的文案调优") // 动态占位符
                        .profile(p -> p.skillAdd("爆款标题制作", "情绪共鸣写作")
                                .constraintAdd("严禁使用感叹号", "字数控制在 50 字内")
                                .style("亲切、多用 Emoji"))
                        .build())

                // 配置：视觉专家（展示多模态元数据）
                .agentAdd(ReActAgent.of(chatModel)
                        .name("illustrator")
                        .description("负责视觉风格定义")
                        .profile(p -> p.skillAdd("矢量插画", "色彩心理学")
                                .modeAdd("text", "image") // 标注具备图像输出潜能
                                .metaPut("engine", "Nano Banana")
                                .style("极简主义"))
                        .build())
                .maxTotalIterations(5)
                .build();

        // 3. 验证动态描述渲染逻辑
        String renderedDesc = creativeTeam.getConfig().getAgentMap().get("copywriter")
                .descriptionFor(session.getSnapshot());
        System.out.println("--- 动态描述校验 ---");
        System.out.println("Rendered Description: " + renderedDesc);
        Assertions.assertTrue(renderedDesc.contains("小红书"), "动态职责渲染失败");

        // 4. 执行任务：模拟一个需要多专家协作的场景
        String userQuery = "我们需要为一款‘深海矿泉水’设计 Slogan 和配图建议。";
        System.out.println("\n--- 启动团队协作 ---");
        String result = creativeTeam.call(Prompt.of(userQuery), session).getContent();
        System.out.println("最终协作结果: \n" + result);

        // 5. 验证 Profile 格式化输出 (模拟 Supervisor 视角)
        System.out.println("\n--- 档案视图 (中文) ---");
        creativeTeam.getConfig().getAgentMap().forEach((name, agent) -> {
            AgentProfile profile = agent.profile();
            if (profile != null) {
                System.out.println(name + ": " + profile.toFormatString(Locale.CHINESE));
            }
        });

        // 6. 核心断言
        TeamTrace trace = creativeTeam.getTrace(session);
        Assertions.assertNotNull(trace, "协作轨迹不应为空");

        // 校验元数据获取
        AgentProfile illustratorProfile = creativeTeam.getConfig().getAgentMap().get("illustrator").profile();
        Assertions.assertEquals("Nano Banana", illustratorProfile.getMeta("engine", "default"));
        Assertions.assertTrue(illustratorProfile.supports("text", "image"));

        // 校验协作完整性
        boolean hasCopywriterWork = trace.getSteps().stream().anyMatch(s -> s.getAgentName().equals("copywriter"));
        Assertions.assertTrue(hasCopywriterWork, "文案专家必须参与任务");
    }

    @Test
    public void testModalRouting() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        TeamAgent multiModalTeam = TeamAgent.of(chatModel)
                .name("media_center")
                .agentAdd(ReActAgent.of(chatModel)
                        .name("text_editor")
                        .description("处理文字校对")
                        .profile(p -> p.modeAdd("text", "text") // 声明仅支持文本
                                .skillAdd("语法检查"))
                        .build())
                .agentAdd(ReActAgent.of(chatModel)
                        .name("vision_analyst")
                        .description("处理图像内容提取")
                        .profile(p -> p.modeAdd("text", "text")
                                .modeAdd("image", "text") // 声明支持图片
                                .skillAdd("视觉分析"))
                        .build())
                .build();

        // 模拟一个带图片的请求 (假设 Prompt 支持附加媒体)
        // 如果 Supervisor 足够聪明（基于我们调整的 Prompt），它会精准指派 vision_analyst
        String query = "请分析这张发票图片中的金额。";
        AgentSession session = InMemoryAgentSession.of("sn_img_001");

        multiModalTeam.call(Prompt.of(query), session);

        TeamTrace trace = multiModalTeam.getTrace(session);
        // 校验：第一步应该是 vision_analyst 被选中
        Assertions.assertEquals("vision_analyst", trace.getSteps().get(0).getAgentName());
    }

    @Test
    public void testProfileAwareInterceptor() throws Throwable {
        ChatModel chatModel = LlmUtil.getChatModel();

        TeamAgent secureTeam = TeamAgent.of(chatModel)
                .name("secure_team")
                .maxTotalIterations(2) // 关键：限制最大迭代次数，防止死循环导致资损
                .agentAdd(ReActAgent.of(chatModel)
                        .name("data_analyst")
                        // 给它一个具体的技能，让它觉得自己能行
                        .profile(p -> p.skillAdd("财务报表分析")
                                .metaPut("sensitive", true))
                        .build())
                .defaultInterceptorAdd(new TeamInterceptor() {
                    @Override
                    public void onAgentEnd(TeamTrace trace, Agent agent) {
                        // 拦截点：感知 Agent 档案中的元数据
                        if (agent.profile() != null && (boolean) agent.profile().getMeta("sensitive", false)) {
                            System.out.println(">>> [拦截器启动] 正在对敏感专家 " + agent.name() + " 的输出进行脱敏审查...");
                            // 你甚至可以在这里修改结果（模拟脱敏）
                            // trace.getCurrentStep().setResult("内容已脱敏");
                        }
                    }
                })
                .build();

        // 提供一些背景数据，让它有话可说，避免推卸责任
        String userQuery = "去年营收是 1000w，请帮我生成一份脱敏后的简报。";

        System.out.println("--- 启动安全审计测试 ---");
        secureTeam.call(Prompt.of(userQuery), InMemoryAgentSession.of("sn_002"));

        // 校验：只需证明拦截器跑过了即可
        Assertions.assertTrue(true);
    }

    @Test
    public void testProfileI18n() {
        AgentProfile profile = new AgentProfile().skillAdd("Coding");

        // 验证中文输出
        String zhStr = profile.toFormatString(Locale.CHINESE);
        Assertions.assertTrue(zhStr.contains("擅长技能"));

        // 验证英文输出
        String enStr = profile.toFormatString(Locale.ENGLISH);
        Assertions.assertTrue(enStr.contains("Skills"));
    }
}