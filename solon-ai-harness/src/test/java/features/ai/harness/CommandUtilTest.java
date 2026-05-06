package features.ai.harness;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.harness.command.CommandUtil;

import java.util.List;

/**
 * CommandUtil 解析测试
 *
 * @author noear 2026/5/6 created
 */
public class CommandUtilTest {

    @Test
    public void testParseArguments() {
        List<String> rst;

        // 1. 标准 Cron 指令测试（保留引号原始状态）
        rst = CommandUtil.parseArguments("/loop cron:\"1 * * * * ? \" 查下淘宝网最新的推荐商品");
        Assertions.assertEquals(3, rst.size());
        Assertions.assertEquals("/loop", rst.get(0));
        Assertions.assertEquals("cron:\"1 * * * * ? \"", rst.get(1));
        Assertions.assertEquals("查下淘宝网最新的推荐商品", rst.get(2));

        // 2. 简单空格分隔测试
        rst = CommandUtil.parseArguments("/loop 1m 查下淘宝网最新的推荐商品");
        Assertions.assertEquals(3, rst.size());
        Assertions.assertEquals("1m", rst.get(1));

        // 3. 多重连续空格与首尾空格测试
        rst = CommandUtil.parseArguments("  /cmd   arg1    arg2  ");
        Assertions.assertEquals(3, rst.size());
        Assertions.assertEquals("/cmd", rst.get(0));
        Assertions.assertEquals("arg1", rst.get(1));
        Assertions.assertEquals("arg2", rst.get(2));

        // 4. 单引号包裹与嵌套引号测试
        rst = CommandUtil.parseArguments("/say name:'\"Solon\"' hello");
        Assertions.assertEquals(3, rst.size());
        Assertions.assertEquals("name:'\"Solon\"'", rst.get(1)); // 确保内部双引号被保留

        // 5. 空字符串与 null 测试
        Assertions.assertTrue(CommandUtil.parseArguments("").isEmpty());
        Assertions.assertTrue(CommandUtil.parseArguments(null).isEmpty());

        // 6. 只有命令没有参数
        rst = CommandUtil.parseArguments("/help");
        Assertions.assertEquals(1, rst.size());
        Assertions.assertEquals("/help", rst.get(0));

        // 7. 复杂的 Cron + Key-Value
        rst = CommandUtil.parseArguments("/task --id=101 --cron:\"0 0/1 * * * ?\"");
        Assertions.assertEquals(3, rst.size());
        Assertions.assertEquals("--id=101", rst.get(1));
        Assertions.assertEquals("--cron:\"0 0/1 * * * ?\"", rst.get(2));
    }
}