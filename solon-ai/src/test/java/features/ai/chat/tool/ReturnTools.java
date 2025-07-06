package features.ai.chat.tool;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

import java.io.Serializable;

/**
 * @author noear 2025/4/25 created
 */
public class ReturnTools {
    @ToolMapping(description = "获取指定城市的天气情况", returnDirect = true)
    public String get_weather(@Param(name = "location", description = "根据用户提到的地点推测城市") String location) {
        if (location == null) {
            throw new IllegalStateException("arguments location is null (Assistant recognition failure)");
        }

        return "晴，24度";
    }

    @ToolMapping(description = "查询城市降雨量", returnDirect = true)
    public String get_rainfall(@Param(name = "location", description = "城市位置") String location) {
        if (location == null) {
            throw new IllegalStateException("arguments location is null (Assistant recognition failure)");
        }

        return "555毫米";
    }

    @ToolMapping(description = "根据用户id查询用户信息", returnDirect = true)
    public User get_user(@Param(description = "用户Id") long userId) {
        return new User(userId, "a1", 12);
    }

    public static class User implements Serializable {
        private long userId;
        private String name;
        private int age;

        public User(long userId, String name, int age) {
            this.userId = userId;
            this.name = name;
            this.age = age;
        }

        public String getName() {
            return name;
        }

        public int getAge() {
            return age;
        }
    }
}
