package demo.ai.talents.text2sql;


import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.talents.text2sql.Text2SqlTalent;

import javax.sql.DataSource;

public class Demo {
    public void test(DataSource dataSource) throws Throwable {
        // 实例化工具包：指定受控的数据源和表名
        Text2SqlTalent sqlTalent = new Text2SqlTalent(dataSource, "users", "orders", "order_refunds")
                .maxRows(50); // 限制返回行数，保护内存

        // 构建 Agent 或 ChatModel
        ChatModel agent = ChatModel.of("https://api.moark.com/v1/chat/completions")
                .apiKey("***")
                .model("Qwen3-32B")
                .role("财务数据分析师")
                .instruction("你负责分析订单与退款数据。金额单位均为元。")
                .defaultTalentAdd(sqlTalent) // 注入 SQL 工具包
                .build();

        // 发起自然语言查询
        AssistantMessage resp = agent.prompt("去年消费最高的 VIP 客户是谁？")
                .call()
                .getMessage();
    }
}