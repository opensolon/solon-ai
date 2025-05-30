如果您对开源感兴趣且愿意学习和贡献，欢迎您共建 Solon 生态。

### 1、版权说明

本仓库的源码版权归 noear 开源组织所有。

### 2、贡献分类

代码贡献：

* 修复问题或优化现有的代码
* 新增功能插件
* 添加 Solon Cloud 适配插件
* 为现有的模块丰富单元测试用例；为官网丰富配套示例。等...

合作贡献：

* 有开源框架的同道，在自己仓库里添加 solon 框架的便利适配（需要帮忙随时联系交流）
* 基于 Solon 开发开源项目或框架。等...

其它贡献：

* 通过 Issue，提交需求、提交问题
* 发博客宣传、录视频界面、在交流群或社区推荐 Solon。等...


### 3、代码贡献说明

1. 提交 Issue ，并与管理员进行确认（避免重复工作）
2. Fork 仓库
3. 在 dev 分支上编写代码，并添加对应的单元测试
4. 统一使用 solon-test-junit5 做单测（为了批量跑单测）
5. pr 时，选择 dev 分支进行合并（提交时需关联一个 Issue）
6. 如果是分布式中间件的适配，优先适配成 solon cloud 规范
7. 注释多些点：）

### 4、代码分支保护规则说明


| 操作 | master (or main)  | dev |
| -------- | -------- | -------- |
|  可推送代码成员  | 禁止任何人     | 仓库管理员     |
|  可合并 Pull Request 成员   | 禁止任何人     | 仓库管理员     |




### 5、代码模块测试目上录结构规范说明

| 目录                 | 说明                    |
|--------------------|-----------------------|
| src/test/benchmark | 压测目录（可选）              |
| src/test/demo      | 简单示例目录（必须，只是看看的放这里）   |
| src/test/features  | 特性测试目录（必选，会进入全项目批量单测） |
| src/test/labs      | 实验目录（可选，不能批量跑的单测）     |

不要增加别的目录

### 6、代码提前描述的前缀规范


| 前缀 | 示例                                    | 说明               |
|----|---------------------------------------|------------------|
| 新增 | 新增 solon-xxx 模块                       | 表示增加一个全新模块       |
| 添加 | 添加 solon-xxx Yyy 工具类                  | 表示在一个模块里增加新的能力   |
| 优化 | 优化 solon-xxx Yyy 延尽订阅处理逻辑             | 表示优化现有代码（没有兼容风险） |
| 修复 | 修复 solon-xxx Yyy 无法读取元数据问题            | 表示修复现有问题（没有兼容风险） |
| 调整 | 调整 solon-xxx Yyy 默认值为 true（之前为 false） | 表示调整现有代码（会有兼容风险） |
| 移除 | 移除 solon-xxx Yyy 注解类（之前已弃用一年）         | 表示移除多余的类         |
| 文档 | 文档 solon-xxx Yyy 的注释完善                | 表示文档相关的完善        |
| 测试 | 测试 solon-xxx 补充 Yyy 测试用例              | 表示测试相关的完善        |
| 其它 | 其它 solon-xxx 配置示例变化                   | 其它相关内容           |


### 7、主要代码仓库说明


| 代码仓库                                            | 描述                               | 
|-------------------------------------------------|----------------------------------| 
| https://gitee.com/opensolon/solon               | Solon ,主代码仓库                     | 
| https://gitee.com/opensolon/solon-examples      | Solon ,官网配套示例代码仓库                |
|                                                 |                                  |
| https://gitee.com/opensolon/solon-ai            | Solon Ai ,代码仓库                   | 
| https://gitee.com/opensolon/solon-flow          | Solon Flow ,代码仓库                 | 
| https://gitee.com/opensolon/solon-cloud         | Solon Cloud ,代码仓库                | 
| https://gitee.com/opensolon/solon-admin         | Solon Admin ,代码仓库                | 
| https://gitee.com/opensolon/solon-jakarta       | Solon Jakarta ,代码仓库（base java17） | 
| https://gitee.com/opensolon/solon-integration   | Solon Integration ,代码仓库          | 
|                                                 |                                  |
| https://gitee.com/opensolon/solon-gradle-plugin | Solon Gradle ,插件代码仓库             | 
| https://gitee.com/opensolon/solon-maven-plugin  | Solon Gradle ,插件代码仓库             | 
|                                                 |                                  |
| https://gitee.com/opensolon/solon-idea-plugin   | Solon Idea ,插件代码仓库               | 
| https://gitee.com/opensolon/solon-vscode-plugin | Solon VsCode ,插件代码仓库             | 
|                                                 |                                  |
| https://gitee.com/dromara/solon-plugins         | Solon 第三方扩展插件代码仓库                | 

