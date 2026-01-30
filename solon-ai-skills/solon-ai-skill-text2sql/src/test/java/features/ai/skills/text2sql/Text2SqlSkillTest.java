package features.ai.skills.text2sql;

import demo.ai.skills.text2sql.LlmUtil;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.skills.text2sql.Text2SqlSkill;
import org.noear.solon.annotation.Inject;
import org.noear.solon.data.sql.SqlUtils;
import org.noear.solon.test.SolonTest;
import org.junit.jupiter.api.Test;


/**
 * Text2SqlSkill 财务分析实战演示
 *
 * @author noear 2026/1/30 created
 */
@SolonTest
public class Text2SqlSkillTest {

    @Inject("h2")
    SqlUtils sqlUtils;

    @Test
    public void demo() throws Throwable {
        // 1. 获取 ChatModel（确保 LLM 能力足够，建议使用 GPT-4o, Claude 3 或 DeepSeek-V3）
        ChatModel chatModel = LlmUtil.getChatModel();

        // 2. 初始化 H2 内存数据库
        sqlUtils.initDatabase("classpath:db.sql");

        // 3. 构建 Skill，并指定需要让 AI 获知的表名
        // 这里会自动提取表结构、主外键、注释、DDL和样例数据
        Text2SqlSkill sqlSkill = new Text2SqlSkill(sqlUtils, "users", "orders")
                .maxRows(20)          // 限制 AI 每次最多读取 20 条
                .maxContextLength(4000); // 限制返回给 AI 的上下文长度

        // 4. 构建 ReAct 智能体
        ReActAgent agent = ReActAgent.of(chatModel)
                .role("高级财务数据分析师")
                .instruction("你可以通过执行 SQL 查询 'users' 和 'orders' 表来回答业务问题。")
                .defaultSkillAdd(sqlSkill)
                .build();

        // 5. 进行多轮测试
        System.out.println("--- 财务助手准备就绪 ---");

        // 测试 A：简单统计（体现备注理解：status=1 为已支付）
        ask(agent, "张三一共支付了多少钱？");

        // 测试 B：跨表关联（体现主外键关联能力）
        ask(agent, "帮我查一下所有 VIP 用户在 2026 年 1 月份的订单明细");

        // 测试 C：复杂业务规则（体现对状态码备注的理解）
        ask(agent, "目前还有多少笔订单没付钱？对应的客户姓名是谁？");
    }

    private void ask(ReActAgent agent, String question) throws Throwable {
        System.out.println("\n[用户问题]: " + question);
        String result = agent.prompt(question).call().getContent();

        // 输出 Agent 的最后一条回答（即 ReAct 思考后的最终结论）
        System.out.println("[助手回答]: " + result);
    }
}