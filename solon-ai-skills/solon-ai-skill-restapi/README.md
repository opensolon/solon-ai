# Solon AI Skill OpenAPI

solon-ai-skill-openapi 是 Solon AI 的一个标准插件，让 AI Agent 能够直接“看懂”并“调用”现有的 REST 接口。

通过解析 OpenAPI (Swagger) 定义，AI 可以自动将用户的自然语言需求转化为标准的 HTTP 请求。


###  核心特性

* 自动对接：只需提供 OpenAPI JSON 地址，自动生成 AI 技能。
* 自适应模式：接口少时全量注入 (FULL)，接口多时自动切换到动态探测模式 (DYNAMIC)，节省 Token。
* 全谓词支持：支持 GET, POST, PUT, DELETE。
* 参数智能构造：自动处理 Path 变量、Query 参数和 JSON 请求体。

### 快速接入

#### 1. 引入依赖

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-skill-openapi</artifactId>
</dependency>
```

#### 2. 应用示例

```java
import org.noear.solon.ai.agent.simple.SimpleAgent;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.skills.restapi.RestApiSkill;
import org.noear.solon.ai.skills.restapi.SchemaMode;

public class Demo {
    public void test(ChatModel chatModel) throws Throwable {
        // 1. 定义接口文档和基址
        String docUrl = "http://api.example.com/v3/api-docs";
        String baseUrl = "http://api.example.com";

        // 2. 创建技能
        RestApiSkill apiSkill = new RestApiSkill(docUrl, baseUrl)
                .schemaMode(SchemaMode.DYNAMIC);

        // 3. 构发建 Agent 或 ChatModel
        SimpleAgent agent = SimpleAgent.of(chatModel)
                .defaultSkillAdd(apiSkill)
                .build();

        // 4. 直接对话，AI 会自动选择合适的接口调用
        agent.prompt("帮我查询 ID 为 1024 的用户状态").call();
        agent.prompt("新建一个名为 'Noear' 的用户").call();
    }
}
```