package demo.ai.skills.text2sql;

import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.skills.text2sql.Text2SqlSkill;
import org.noear.solon.annotation.Inject;

import javax.sql.DataSource;


/**
 *
 * @author noear 2026/1/30 created
 *
 */
public class DataAnalystAgent {
    public DataAnalystAgent(@Inject("db1") DataSource dataSource) {
        ChatModel chatModel = ChatModel.of("")
                .defaultSkillAdd(new Text2SqlSkill(dataSource, "user", "orders"))
                .build();


        //"你是一个财务分析助手。请通过查询数据库来回答用户的财务问题。"

    }
}