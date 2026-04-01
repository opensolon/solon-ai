package features.ai.schema;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.annotation.Param;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

public class CustomDateFormatTest {
    private static final Logger log = LoggerFactory.getLogger(CustomDateFormatTest.class);

    @Test
    public void testCustomDateFormat() throws Throwable {
        ChatModel chatModel =ChatModel.of("https://ai.gitee.com/v1/chat/completions")
                .apiKey("PE6JVMP7UQI81GY6AZ0J8WEWWLFHWHROG15XUP18")
                .model("Qwen3-32B")
                .role("天气助手")
                .instruction("你是天气助手，帮用户查询某个时间某个城市的天气。")
                .defaultToolAdd(new WeatherTools())
                .modelOptions(o -> o.temperature(0.01F))
                .build();

        log.info("--- Agent 开始工作 ---");

        String question = "查询北京现在的天气";
        log.info("用户提问: {}", question);

        try {
            String response = chatModel.prompt(question).call().getContent();

            log.info("--- 最终答复 ---");
            log.info("Agent 响应: {}", response);

        } catch (Exception e) {
            log.error("--- 解析失败！---");
            log.error("错误信息: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * 工具类
     */
    public static class WeatherTools {

        /**
         * 获取当前时间 - 返回中文格式的时间字符串（不在 Snack4 默认 PATTERNS 中）
         */
        @ToolMapping(name = "get_current_time", description = "获取当前时间")
        public String get_current_time() {
            // 返回中文格式：yyyy年MM月dd日 HH时mm分ss秒
            String timeStr = "2026年03月25日 11时00分00秒";
            log.info("get_current_time 返回: {}", timeStr);
            return timeStr;
        }

        /**
         * 查询天气 - 接收城市和时间参数
         */
        @ToolMapping(name = "query_weather",
                description = "查询指定城市和时间的天气，时间格式：yyyy年MM月dd日 HH时mm分ss秒")
        public String query_weather(
                @Param(description = "城市名称") String city,
                @Param(description = "时间", format="yyyy年MM月dd日 HH时mm分ss秒") LocalDateTime time) {

            log.info("query_weather 被调用：city={}, time={}", city, time);
            return city + " 在 " + time + " 时天气晴朗，气温 25 摄氏度。";
        }
    }
}