package features.ai.chat.tool;

import org.noear.solon.ai.chat.annotation.ToolMapping;
import org.noear.solon.ai.chat.annotation.ToolParam;

public class EntityTools {
    @ToolMapping(description = "提交用户数据", returnDirect = true)
    public String get_weather(@ToolParam(description = "用户数据") User user) {
        System.out.println(user);
        return "成功";
    }

    public static class User {
        @ToolParam(description = "用户id")
        private int id;

        @ToolParam(description = "用户名字")
        private String name;

        public int getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public void setId(int id) {
            this.id = id;
        }

        public void setName(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return "User{" +
                    "id=" + id +
                    ", name='" + name + '\'' +
                    '}';
        }
    }
}