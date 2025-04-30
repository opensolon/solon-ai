
函数(Function)相关的概念改为工具概念(Tool)

* ChatFunction(聊天函数) -> FunctionTool（函数工具）
  * ChatFunctionDecl（聊天函数申明） -> FunctionToolDesc（函数工具描述）
* ChatModel:globalFunctionAdd(...) -> ChatModel:defaultToolsAdd(...)
  * ChatOptions:functionAdd(...) - > ChatModel:toolsAdd(...)
* `@FunctionMapping` -> `@ToolMapping`
  * `@FunctionParam` -> `@ToolParam`
  * `@ToolParam` -> `@Param`