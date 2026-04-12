package features.ai.harness;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.harness.agent.TaskSkill;

import java.util.List;
import java.util.Map;

public class JsonSchemaTest {
    @Test
    public void case1() {
        TaskSkill taskSkill = new TaskSkill(null);

        FunctionTool functionTool = ((List<FunctionTool>) taskSkill.getToolAry("multitask")).get(0);
        System.out.println(functionTool.inputSchema());
        assert "{\"type\":\"object\",\"properties\":{\"tasks\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"agent_name\":{\"type\":\"string\",\"description\":\"子代理名称\"},\"description\":{\"type\":\"string\",\"description\":\"任务内容的极简摘要（如：'重构用户认证逻辑'）。该描述将作为标签出现在执行日志和结果摘要中，用于快速识别任务意图。\"},\"index\":{\"type\":\"integer\",\"description\":\"任务唯一序号，每个任务分配唯一的递增整数（从1开始），以便匹配返回结果\",\"default\":1},\"prompt\":{\"type\":\"string\",\"description\":\"发给子代理的详细指令。由于子代理是无状态的（上下文隔离），必须在此提供任务所需的所有背景信息、具体要求及预期输出格式。\"}},\"required\":[\"index\",\"agent_name\",\"prompt\",\"description\"]},\"description\":\"任务列表\"}},\"required\":[\"tasks\"]}".equals(functionTool.inputSchema());


        functionTool = ((List<FunctionTool>) taskSkill.getToolAry("task")).get(0);
        System.out.println(functionTool.inputSchema());
        assert "{\"type\":\"object\",\"properties\":{\"agent_name\":{\"type\":\"string\",\"description\":\"子代理名称\"},\"description\":{\"type\":\"string\",\"description\":\"任务内容的极简摘要（如：'重构用户认证逻辑'）。该描述将作为标签出现在执行日志和结果摘要中，用于快速识别任务意图。\"},\"prompt\":{\"type\":\"string\",\"description\":\"发给子代理的详细指令。由于子代理是无状态的（上下文隔离），必须在此提供任务所需的所有背景信息、具体要求及预期输出格式。\"}},\"required\":[\"agent_name\",\"prompt\",\"description\"]}".equals(functionTool.inputSchema());
    }

    @Test
    public void case2() throws Throwable {
        TaskSkill taskSkill = new TaskSkill(null);

        FunctionTool functionTool = ((List<FunctionTool>) taskSkill.getToolAry("multitask")).get(0);


        ONode oNode = ONode.ofJson(json, Feature.Read_AllowUnescapedControlCharacters);

        Map args = oNode.get("function").get("arguments").toBean(Map.class);

        Assertions.assertThrowsExactly(IllegalStateException.class, ()->{
            functionTool.call(args);
        },"__sessionId is required");
    }

    String json = "{\"id\":\"tool-f52c8e347ee54c86860ce093090d2bc6\",\"type\":\"function\",\"function\":{\"name\":\"multitask\",\"arguments\":{\"tasks\":\"[{\\\"index\\\": 1, \\\"agent_name\\\": \\\"backend-dev\\\", \\\"description\\\": \\\"开发权限管理后端系统\\\", \\\"prompt\\\": \\\"你是 demo1-web 权限管理系统的后端开发工程师。请基于已有的架构设计文档完成整个后端开发。\\n\\n## 已完成的设计文档\\n- 架构设计: demo1-web/backend/docs/architecture.md\\n- 数据库设计: demo1-web/backend/docs/database.md\\n- API设计: demo1-web/backend/docs/api.md\\n\\n## 技术要求 (严格遵守!)\\n### 框架: Solon v3.10.1 (不是 Spring!)\\n必须使用 Solon 框架，严禁使用任何 Spring 注解和依赖!\\n\\n### Solon 注解对照表:\\n| Spring | Solon |\\n|--------|-------|\\n| @SpringBootApplication | @SolonMain |\\n| @RestController | @Controller |\\n| @RequestMapping | @Mapping |\\n| @GetMapping | @Get |\\n| @PostMapping | @Post |\\n| @PutMapping | @Put |\\n| @DeleteMapping | @Delete |\\n| @Autowired | @Inject |\\n| @Service | @Component |\\n| @Repository | @Component |\\n| @Value | @Inject(\\\\\\\"${key}\\\\\\\") |\\n| @Configuration | @Configuration |\\n| @Bean | @Bean |\\n\\n### 项目配置:\\n- pom.xml 父 POM: org.noear:solon-parent:3.0.10 (或 3.0.10-M1)\\n- Solon 版本: 3.10.1 对应的依赖版本\\n- Java 版本: 8\\n- 数据库: H2 内存数据库\\n- ORM: MyBatis-Flex\\n\\n## 开发任务\\n\\n### 1. 创建项目结构\\n```\\ndemo1-web/backend/\\n├── pom.xml\\n├── src/main/java/com/demo/\\n│   ├── App.java (启动类，用 @SolonMain)\\n│   ├── controller/\\n│   ├── service/\\n│   ├── mapper/\\n│   ├── entity/\\n│   ├── dto/\\n│   ├── config/\\n│   ├── common/\\n│   └── exception/\\n├── src/main/resources/\\n│   ├── app.yml (注意: 不是 application.yml!)\\n│   └── schema.sql\\n└── src/test/\\n```\\n\\n### 2. 实现核心模块\\n- 用户管理 (UserController, UserService, UserMapper)\\n- 角色管理 (RoleController, RoleService, RoleMapper)\\n- 权限管理 (PermissionController, PermissionService, PermissionMapper)\\n- 菜单管理 (MenuController, MenuService, MenuMapper)\\n- 认证授权 (AuthController, AuthService, JwtUtil)\\n\\n### 3. 数据库初始化\\n- 创建 schema.sql 初始化脚本\\n- 包含默认管理员账号: admin/123456\\n\\n### 4. 实现细节\\n- 统一响应格式: Result<T>\\n- 密码 BCrypt 加密\\n- JWT Token 认证\\n- 跨域配置\\n- 全局异常处理\\n\\n### 5. 启动验证\\n- 使用 Solon.start(App.class, args) 启动\\n- 默认端口: 8080\\n\\n## 重要提醒\\n1. 所有注解必须使用 Solon 的，不能用 Spring 的\\n2. 配置文件名是 app.yml，不是 application.yml\\n3. 启动类用 @SolonMain 注解\\n4. 确保项目可以正常编译运行\\n\\n请完成所有后端代码开发，确保项目结构完整、代码规范、可以编译运行。\\\"}, {\\\"index\\\": 2, \\\"agent_name\\\": \\\"frontend-dev\\\", \\\"description\\\": \\\"开发权限管理前端界面\\\", \\\"prompt\\\": \\\"你是 demo1-web 权限管理系统的前端开发工程师。请基于已有的架构设计文档完成整个前端开发。\\n\\n## 已完成的设计文档\\n- 架构设计: demo1-web/backend/docs/architecture.md\\n- API设计: demo1-web/backend/docs/api.md\\n\\n## 技术要求\\n- 框架: Vue 3 (Composition API + script setup)\\n- 构建: Vite\\n- UI: Element Plus\\n- 状态: Pinia\\n- 路由: Vue Router 4\\n- HTTP: Axios\\n- 样式: SCSS\\n\\n## 开发任务\\n\\n### 1. 创建项目结构\\n```\\ndemo1-web/frontend/\\n├── package.json\\n├── vite.config.js\\n├── index.html\\n├── src/\\n│   ├── main.js\\n│   ├── App.vue\\n│   ├── api/\\n│   │   ├── auth.js\\n│   │   ├── user.js\\n│   │   ├── role.js\\n│   │   ├── permission.js\\n│   │   └── menu.js\\n│   ├── views/\\n│   │   ├── Login.vue\\n│   │   ├── Layout.vue\\n│   │   ├── Home.vue\\n│   │   ├── user/\\n│   │   │   └── UserList.vue\\n│   │   ├── role/\\n│   │   │   └── RoleList.vue\\n│   │   ├── permission/\\n│   │   │   └── PermList.vue\\n│   │   └── menu/\\n│   │       └── MenuList.vue\\n│   ├── components/\\n│   │   └── layout/\\n│   │       ├── Sidebar.vue\\n│   │       └── Header.vue\\n│   ├── stores/\\n│   │   ├── user.js\\n│   │   └── menu.js\\n│   ├── router/\\n│   │   └── index.js\\n│   ├── utils/\\n│   │   ├── request.js\\n│   │   └── auth.js\\n│   └── styles/\\n│       ├── variables.scss\\n│       └── global.scss\\n└── public/\\n```\\n\\n### 2. 实现核心功能\\n- 登录页面 (账号密码登录)\\n- 主布局 (左侧菜单 + 顶部导航 + 内容区)\\n- 用户管理 (列表、新增、编辑、删除、分配角色)\\n- 角色管理 (列表、新增、编辑、删除、分配权限)\\n- 权限管理 (树形结构、增删改查)\\n- 菜单管理 (树形结构、增删改查)\\n\\n### 3. 界面设计要求\\n- 整体风格: 清爽、现代、简约\\n- 配色: 主色调蓝色系 (#409EFF)\\n- 布局: 经典后台管理布局\\n- 交互: 友好提示、加载状态\\n\\n### 4. API 对接\\n- 后端地址: http://localhost:8080/api\\n- 请求拦截: 自动添加 Authorization 头\\n- 响应拦截: 统一错误处理\\n- Token 存储: localStorage\\n\\n### 5. 默认账号\\n- 用户名: admin\\n- 密码: 123456\\n\\n## 开发要求\\n1. 使用 Composition API 和 script setup 语法\\n2. 组件命名 PascalCase\\n3. API 请求统一管理\\n4. 表单验证完整\\n5. 代码整洁规范\\n\\n请完成所有前端代码开发，确保项目结构完整、界面美观、功能完整。\\\"}]\"}}}";
}