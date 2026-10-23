# mcp-core Java 8 语法兼容改造方案

## 1. 目标与验收口径

将 `mcp-core` 中**排除项以外**的生产源码和必要测试源码改为 Java 8 语言语法，并尽量保持现有公开 API、JSON/Jackson 序列化结构、不可变性、异常/null 行为、线程语义和测试意图不变。

本次“Java 8”分为两个必须分别验证的口径：

- **语法/字节码口径**：非排除源码使用 Java 8 语法并以 `--release 8`（或等价 `source/target 8` + API 检查）编译。
- **排除适配口径**：Servlet 与 JDK `HttpClient` 适配实现本身不改写；由于 `java.net.http.HttpClient` 自 Java 11 才存在，不能承诺整个当前源码集合在 JDK 8、单一 classpath 下整体编译。必须通过 Maven profile/source 排除或拆分编译边界明确表达这一点，而不是把失败伪装成已兼容。

## 2. 明确排除范围与边界

以下适配实现及其**适配专属测试**不做 Java 8 适配：

- 使用 `jakarta.servlet.http` 的 Servlet 适配类：
  - `modern/server/transport/HttpServletMcpTransport`
  - `server/transport/HttpServlet*` 相关实现
  - 仅服务于这些类的测试
- 使用 `java.net.http.HttpClient` 的 JDK HTTP Client 适配类：
  - `client/transport/HttpClientSseClientTransport`
  - `client/transport/HttpClientStreamableHttpTransport`
  - `ResponseBodyHandlers` 以及仅服务于这些类的测试

排除仅针对适配实现本身，不得扩展到共享的 `McpSchema`、请求/响应模型、接口、session、通用校验器或工具类。共享代码仍必须完成 Java 8 兼容改造。

必须建立一份最终排除清单，记录每个排除文件及原因；若排除文件引用了共享类型，共享类型仍按 Java 8 规则改造。不得把含 `java.net.http` 的测试、文本块等问题遗漏在“适配排除”名义下。

## 3. 改造前盘点与构建基线

1. 完整检查根 POM、`mcp-core/pom.xml`、profile、编译器、格式化插件、bnd 插件、测试插件及依赖的 Java 最低版本。
2. 当前根 POM 使用 `${java.version}=17` 和 compiler `<release>17</release>`；方案执行时增加一个**仅用于兼容性验证的明确 profile/参数**，不得无条件破坏现有 Java 17 构建。
3. 确定两条构建路径：
   - Java 8-compatible core：只编译非排除生产源码及可在 Java 8 classpath 上解析的共享代码。
   - Java 17 adapter：保留并单独编译 Servlet/JDK HttpClient 适配实现，继续使用现有 Java 17 配置。
4. 核验依赖：当前 JUnit 6、Mockito 5、Tomcat 11、Reactor 2026 等依赖可能要求更高运行时。测试“源码为 Java 8 语法”和“测试可在 JDK 8 运行”必须分开记录；若依赖不支持 JDK 8，不得通过修改业务代码掩盖，需在方案结果中列为构建限制或调整为兼容测试依赖。
5. 改造前先运行现有模块编译/测试（在可用 JDK 上），保存基线错误和测试结果；同时生成源文件、排除文件和 Java 9+ API 的盘点结果。

## 4. 语言特性和 API 降级范围

### 4.1 生产源码与共享测试源码

- 所有 `record`（包括嵌套、局部 record）改为普通 `final` 类或保持原有可继承语义的普通类：字段设为 `private final`，补齐 canonical 构造器、同名 `xxx()` 访问器、`equals`、`hashCode`、`toString`；保留构造器校验、默认值、注解目标、接口实现和泛型。
- 对 Jackson/JSON 模型，逐字段核对 `@JsonProperty`、构造器参数名、序列化字段名、缺省值、null 值和反序列化行为；不能只做语法替换。
- 将模式匹配 `instanceof Type variable`（含 `&&`、否定条件、多分支）改为 Java 8 的判断与显式转换；逐一确认变量作用域和短路逻辑。
- 将 `var`（含增强 for、try-with-resources、测试代码）改为显式类型。
- 将 switch 表达式、箭头 case、文本块、其他 Java 9+ 语言特性改为 Java 8 写法。文本块要保留换行、缩进和 JSON 内容，不得只为通过编译而改变测试数据。
- 将 `String.isBlank`、`formatted`、`repeat`、`strip/stripLeading/stripTrailing` 等 Java 11+ API 替换为等价实现；明确 Unicode 空白语义是否需要保留。
- 将 `Stream.toList()`、`List/Set/Map.of*`、`copyOf`、`Optional` 的高版本 API 逐项替换。集合替换必须保留：空集合、顺序、重复元素/键、null 拒绝、不可变视图与拷贝语义。
- 全面扫描其他 Java 9+ API（如 `Optional.orElseThrow()`、`Collectors` 新 API、`Objects` 新 API、`InputStream/Files` 新方法、`String` 新方法），不能只依赖固定关键词。
- 检查 `javax`/`jakarta`、反射、服务加载、模块描述、注解处理和生成资源是否引入 Java 8 不可解析的类型。

### 4.2 公开 API 与兼容行为

- 不改变包名、公开类型名、方法签名、字段序列化名称、Jackson 注解和 builder API。
- 记录由 record 转普通类带来的反射差异（`java.lang.Record`、组件反射、构造器参数元数据），确认项目 API 是否依赖；若无法保持，必须在结果中明确说明。
- 对每批模型转换补充/保留构造器、访问器、equals/hashCode/toString、JSON round-trip、null/default 和集合不可变性测试。

## 5. 分批执行顺序

1. 先处理通用工具、JSON、schema validator 和 transport/session 接口。
2. 再处理 `spec` 与 `modern` 两套 schema（这是 record 数量最大、共享范围最广的部分）。
3. 处理 `server/client` 的非适配业务、feature DTO、同步/异步 session 和 server feature 类型。
4. 最后处理非排除测试源码及测试夹具；将排除适配测试放入对应 profile，避免其 Java 11/17 API 阻塞 core 兼容验证。
5. 每个小批次执行编译和相关测试，禁止一次性机械替换整个 `McpSchema.java` 后再集中排错。

## 6. 构建、测试和静态验收

1. 正常 Java 17 基线：执行项目规定的 `mvn clean compile`、`mvn test`，确认排除适配仍保持原行为。
2. Java 8 兼容生产代码：使用明确的 Java 8 profile/模块化 source 排除策略执行 `mvn -pl mcp-core -am -DskipTests compile` 的等价命令；编译参数应为 `--release 8` 或可靠等价方案。
3. 在 Java 17 上以 `--release 8` 编译非排除生产源码，避免本机 JDK 的高版本 API 被误用；如要证明可在 JDK 8 运行，再使用真实 JDK 8 运行 smoke test。
4. 运行非排除单测；Servlet/JDK HttpClient 测试在 Java 17 适配 profile 中单独运行。记录因 JUnit/Mockito/Reactor 等第三方依赖最低 Java 版本造成的限制。
5. 做 API/行为回归：JSON 序列化反序列化、record 替换后的相等性、默认值、校验异常、不可变集合、异步流程、服务加载和 session 生命周期。
6. 最终静态扫描（分别针对生产与测试、并标注排除文件）：`record`、模式匹配、`var`、switch 表达式、文本块及所有 Java 9+ API；扫描 import、Javadoc 示例和测试 fixture，避免只扫可执行语句。
7. 检查 `git diff`、生成文件、Maven profile、包清单和排除清单；确保没有意外改动 Servlet/HttpClient 适配逻辑，也没有把兼容性改动泄漏到其他模块。

## 7. 风险与处理原则

- record 数量多且存在紧凑构造器、嵌套类型和 Jackson 注解，转换必须按类型逐个核对，不接受未经测试的正则替换。
- `Map.of/List.of/copyOf` 替换很容易改变 null、顺序或可变性；共用 Java 8 集合辅助方法并为其添加边界测试。
- 适配代码保留 Java 11+/Servlet API 后，整体单一 JDK 8 编译天然不成立；用 profile/source 边界诚实隔离并在报告中说明，不得删除适配功能或声称全模块 JDK 8 可编译。
- 若当前第三方依赖无法在 JDK 8 运行，先区分“源码/字节码 target 8”与“JDK 8 运行时”，再决定只验证编译产物还是调整测试依赖；不得为迁就测试而改变生产 API。
- 每批改造后立即编译和测试，遇到错误先恢复到最近可验证批次，再继续下一批。

## 8. Java 9～17 特性逐版本核对表

执行时按版本逐项核对，不只搜索 `record` 和 `var`：

- **Java 9**：`List/Set/Map.of*`、`copyOf`、`Optional.orElseThrow()`、`Stream.iterate` 新重载、`ServiceLoader.findFirst()`、接口 private 方法、模块/Jigsaw 相关声明；其中 `McpServiceLoader` 当前使用 `ServiceLoader.findFirst()`，必须改为 Java 8 的 iterator/stream 兼容实现。
- **Java 10**：局部变量类型推断 `var`，包括增强 for、try-with-resources、匿名内部类上下文和测试源码。
- **Java 11**：模式相关 API 之外，重点查 `String.isBlank/strip/lines/repeat`、`String.formatted`、`Files.readString/writeString`、HTTP Client；`java.net.http` 按排除清单保留，但它的专属测试也必须隔离。
- **Java 12～13**：switch 表达式预览/演进写法、`Collectors.teeing` 等 API，检查是否出现在源码、测试、Javadoc 示例。
- **Java 14**：正式 switch 表达式、`instanceof` 模式匹配预览、record 预览；所有模式匹配都要转换为显式 cast，并保留短路和变量作用域。
- **Java 15**：文本块；包括测试 JSON、HTTP fixture 和 Javadoc 代码示例，转换时保留实际换行和缩进。
- **Java 16**：正式 record、正式 `instanceof` 模式匹配、`Stream.toList()`、`mapMulti` 等 API；record 替换必须覆盖局部/嵌套 record。
- **Java 17**：sealed/non-sealed/permits、增强伪随机 API、其他 Java 17 API；即使当前扫描没有命中，也要把它们列入最终扫描项。

除语言语法外，使用 JDK 8 `ct.sym`/`--release 8` 或 Animal Sniffer 等 API 基线检查，确认没有只在高版本 JDK 上存在的调用、字段或重载。

## 9. 特殊工具类、接口和 SPI 兼容项

“工具接口”不能只按普通接口文本替换，须逐项做 API 兼容检查：

- **SPI/ServiceLoader**：`McpServiceLoader`、`McpJsonMapperSupplier`、`JsonSchemaValidatorSupplier` 要保持供应商泛型、优先级/首个实现选择、显式 supplier 覆盖和无实现异常信息；`ServiceLoader.findFirst()` 改为 Java 8 迭代实现，并验证 `META-INF/services` 资源。
- **类型令牌工具**：`TypeRef` 及其匿名子类要检查 Java 9 的匿名类 diamond 写法（如 `new TypeRef<>() {}`），改成 Java 8 可编译的显式泛型写法，不能丢失泛型实际类型捕获。
- **原子/并发工具**：搜索 `compareAndExchange`、`getAcquire`、`setRelease` 等 Java 9+ `Atomic*`/VarHandle API；例如 session 代码中的原子引用操作须用 Java 8 的 `compareAndSet` 循环或等价的 `getAndSet`，保持并发竞态语义。
- **MCP 自定义函数式接口**：`McpRequestHandler`、`McpNotificationHandler`、`McpStatelessRequestHandler`、`McpAsync/SyncListFilter`、`McpJsonMapperSupplier`、`JsonSchemaValidatorSupplier`、session factory/handler、repository 和 response `Body` 等接口必须保留 `@FunctionalInterface`、SAM 方法参数/返回值、泛型边界、checked exception 约束和默认行为，确保现有 lambda、方法引用和二进制调用仍成立。
- **Reactive/Publisher 接口**：`org.reactivestreams.Publisher`、Reactor `Mono/Flux/Scheduler` 本身不是 JDK 语法替换对象；只改调用方的 Java 9+ API，并核验 `contextWrite`、调度器和 back-pressure 行为不因显式类型替换而改变。`java.util.concurrent.Flow` 属于 JDK HttpClient 适配专属依赖，随排除项隔离，不得误引入共享核心。
- **接口中的 static/default/nested 类型**：Java 8 已支持接口 default/static 方法和嵌套类型，但要检查是否误用了 Java 9 的 private interface method；保留接口二进制形状和可实现性，不把接口任意改成抽象类。
- **工具类语义**：`Assert`、`Utils`、`ToolInputValidator`、URI template、schema validator、集合工厂辅助方法要有 null、Unicode 空白、异常类型/消息、不可变集合和顺序的专门回归测试。

最终增加一张“接口/API 兼容矩阵”：接口/工具名、是否公开、SAM 签名、Java 8 替代点、受影响实现/测试、验证结果；未列入矩阵的特殊工具接口不得视为已完成。

## 10. 参考 `@solon-ai-source/mcp-core` 的转换风格

已核对参考源码中的 Java 8 兼容写法，后续以其**风格和语义处理方式**为参考，不直接复制其 Solon 专属依赖或业务实现：

1. **record/数据对象转换风格**
   - 使用普通 `final` 类、`private final` 字段、显式构造器和 `xxx()` 访问器；不要改成 Lombok `@Data` 或依赖生成器，以免改变序列化、equals/hashCode 或二进制 API。
   - `equals` 先用 `if (this == o) return true`，再用普通 `instanceof` 和显式 cast；字段比较使用 `Objects.equals`，`hashCode` 使用 `Objects.hash`。`toString` 按原 record 的字段顺序保持稳定。
   - 参考源码 `McpServerFeatures` 的做法：构造器中的校验、默认值和字段赋值保持显式，访问器继续使用 `serverInfo()`、`tools()` 这类 record 风格名称；不要改成 `getServerInfo()`。

2. **泛型匿名类/TypeRef 风格**
   - 参考源码 `McpSchema` 使用 `new TypeRef<HashMap<String, Object>>() {}`，因此所有 `new TypeRef<>() {}`、其他匿名泛型类的 diamond 写法统一改成显式泛型参数；保留匿名子类的实际类型捕获。

3. **Java 8 集合与字符串风格**
   - 优先使用 `Collections.emptyList()`、`Collections.emptyMap()`、`Collections.emptySet()` 以及显式 `ArrayList/HashMap` 构造；需要不可变结果时使用 `Collections.unmodifiable*` 或先拷贝再包装。
   - 参考源码的默认值处理方式是显式三元表达式和 `Collections.empty*`，但不能机械替换：对原 `List.of/Map.of/copyOf` 要按 null 拒绝、顺序、重复键和不可变性逐项选择实现。
   - Java 8 字符串格式化使用 `String.format` 或显式拼接；参考源码 `McpServiceLoader` 的错误信息可作为风格参考，同时保留当前项目原有错误类型和消息语义。

4. **SPI/工具接口风格**
   - 参考源码 `McpServiceLoader`：用 `Iterator<S> iterator = ServiceLoader.load(type).iterator()`、`hasNext()`、`next()` 替代 `findFirst()`；保留显式 supplier、缓存、同步和 `ServiceConfigurationError` 行为。
   - 参考源码的 `TypeRef`、`McpJsonMapperSupplier`、`JsonSchemaValidatorSupplier` 和各类 handler 接口说明：接口方法签名保持明确，不用高版本语法推断；保留 `@FunctionalInterface`、泛型和 lambda 可调用性。
   - 参考源码采用显式 `BiFunction`、`BiConsumer`、`Function`、`Supplier` 泛型声明；本项目转换时也要避免因补充显式类型而改变通配符边界、方法引用解析或重载选择。

5. **参考边界与差异记录**
   - 参考源码中出现的 `Collections.empty*`、显式 record 替代、`ServiceLoader` iterator、TypeRef 显式泛型属于可采纳的 Java 8 风格。
   - 参考源码中的 Solon、Lombok、`solon-web-*` 等依赖不属于本项目转换目标，不得为了模仿风格引入新依赖、替换 Spring/现有 Reactor 架构或修改适配层。
   - 每批改造后记录“参考文件/参考模式/本项目对应文件/是否存在行为差异”，并用现有测试验证，避免只做外观统一。
   
   ### 10.1 数据对象：record 转 Java 8 普通 final 类
   
   参考文件：`@solon-ai-source/mcp-core/src/main/java/io/modelcontextprotocol/server/McpServerFeatures.java`，其中 `Async` 使用私有 final 字段、显式构造器、record 风格访问器，以及普通 `instanceof`/显式 cast。
   
   **待转换的 Java 17 写法：**
   
   ```java
   public record ServerOptions(Implementation serverInfo,
                            ServerCapabilities capabilities,
                            String instructions) {
   
    public ServerOptions {
        Objects.requireNonNull(serverInfo, "serverInfo");
    }
   }
   ```
   
   **Java 8 目标写法：**
   
   ```java
   public final class ServerOptions {
   
    private final Implementation serverInfo;
    private final ServerCapabilities capabilities;
    private final String instructions;
   
    public ServerOptions(Implementation serverInfo,
                         ServerCapabilities capabilities,
                         String instructions) {
        if (serverInfo == null) {
            throw new IllegalArgumentException("serverInfo");
        }
        this.serverInfo = serverInfo;
        this.capabilities = capabilities;
        this.instructions = instructions;
    }
   
    // 保留 record 组件访问器名称，不改成 getServerInfo()
    public Implementation serverInfo() { return serverInfo; }
    public ServerCapabilities capabilities() { return capabilities; }
    public String instructions() { return instructions; }
   
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ServerOptions)) return false;
        ServerOptions that = (ServerOptions) o;
        return Objects.equals(serverInfo, that.serverInfo)
                && Objects.equals(capabilities, that.capabilities)
                && Objects.equals(instructions, that.instructions);
    }
   
    @Override
    public int hashCode() {
        return Objects.hash(serverInfo, capabilities, instructions);
    }
   
    @Override
    public String toString() {
        return "ServerOptions[serverInfo=" + serverInfo
                + ", capabilities=" + capabilities
                + ", instructions=" + instructions + "]";
    }
   }
   ```
   
   执行时应把 `Implementation`、`ServerCapabilities` 替换为本项目实际类型，并将 record 的紧凑构造器校验、默认值、注解和字段顺序逐项迁移。参考源码中的构造器默认值模式如下：
   
   ```java
   this.tools = (tools != null) ? tools : Collections.emptyList();
   this.resources = (resources != null) ? resources : Collections.emptyMap();
   ```
   
   ### 10.2 `instanceof` 模式匹配转显式判断和转换
   
   参考文件：`@solon-ai-source/mcp-core/src/main/java/io/modelcontextprotocol/json/TypeRef.java`。源码先判断 `instanceof Class`，再在下一步显式转换，正是 Java 8 可用的写法。
   
   **待转换的 Java 14+ 写法：**
   
   ```java
   if (superClass instanceof Class<?> clazz) {
    throw new IllegalStateException("TypeRef constructed without actual type information");
   }
   ```
   
   **Java 8 目标写法：**
   
   ```java
   if (superClass instanceof Class) {
    throw new IllegalStateException("TypeRef constructed without actual type information");
   }
   
   ParameterizedType parameterizedType = (ParameterizedType) superClass;
   this.type = parameterizedType.getActualTypeArguments()[0];
   ```
   
   含 `&&` 的情况必须先判断类型，再在同一分支内转换，不能把模式变量直接搬到 Java 8：
   
   ```java
   if (value instanceof String && !((String) value).isEmpty()) {
    String text = (String) value;
    use(text);
   }
   ```
   
   ### 10.3 `var` 转显式类型
   
   参考源码的 `TypeRef`、`McpServiceLoader` 和各类 server feature 实现均使用显式类型声明。转换时保留泛型边界，尤其不要把带通配符的类型简化成原始类型。
   
   **待转换的写法：**
   
   ```java
   var loader = ServiceLoader.load(type);
   var iterator = loader.iterator();
   var resource = jsonMapper.convertValue(params, new TypeRef<ResourceRequest>() {});
   ```
   
   **Java 8 目标写法：**
   
   ```java
   ServiceLoader<S> loader = ServiceLoader.load(type);
   Iterator<S> iterator = loader.iterator();
   ResourceRequest resource = jsonMapper.convertValue(
        params, new TypeRef<ResourceRequest>() {});
   ```
   
   增强 `for` 和 try-with-resources 也必须显式声明：
   
   ```java
   for (Map.Entry<String, Object> entry : values.entrySet()) {
    process(entry.getKey(), entry.getValue());
   }
   
   try (InputStream input = connection.getInputStream()) {
    read(input);
   }
   ```
   
   ### 10.4 TypeRef 和其他匿名泛型类的 diamond 转显式泛型
   
   参考文件：`@solon-ai-source/mcp-core/src/main/java/io/modelcontextprotocol/spec/McpSchema.java`，以及 `McpAsyncClient.java` 等文件中的 `TypeRef` 常量。
   
   **Java 9+ 写法：**
   
   ```java
   TypeRef<HashMap<String, Object>> ref = new TypeRef<>() {};
   TypeRef<List<Root>> rootsRef = new TypeRef<>() {};
   ```
   
   **Java 8 目标写法：**
   
   ```java
   TypeRef<HashMap<String, Object>> ref = new TypeRef<HashMap<String, Object>>() {};
   TypeRef<List<Root>> rootsRef = new TypeRef<List<Root>>() {};
   ```
   
   显式类型参数必须同时保留左侧声明和匿名子类实际捕获的类型；不能改成 `TypeRef` 原始类型，也不能改成 `Class<T>`，否则 Jackson/JSON 转换会丢失泛型信息。
   
   ### 10.5 集合工厂、默认值与不可变性
   
   参考文件：`McpServerFeatures.java`、`McpStatelessServerFeatures.java` 和 `McpClientFeatures.java`。参考源码使用三元表达式配合 `Collections.emptyList/emptyMap`：
   
   ```java
   this.tools = (tools != null) ? tools : Collections.emptyList();
   this.resources = (resources != null) ? resources : Collections.emptyMap();
   ```
   
   转换示例：
   
   ```java
   // Java 9+
   List<String> names = List.of("tools", "resources");
   Map<String, Integer> limits = Map.of("tools", 10);
   List<String> snapshot = List.copyOf(names);
   
   // Java 8：根据原语义选择实现
   List<String> names = Collections.unmodifiableList(
        Arrays.asList("tools", "resources"));
   Map<String, Integer> limits = Collections.unmodifiableMap(
        Collections.singletonMap("tools", 10));
   List<String> snapshot = Collections.unmodifiableList(
        new ArrayList<String>(names));
   ```
   
   多元素 map 不得机械使用 `singletonMap`；应先显式填充 `LinkedHashMap` 以保持顺序，再包装：
   
   ```java
   Map<String, Integer> limits = new LinkedHashMap<String, Integer>();
   limits.put("tools", 10);
   limits.put("resources", 20);
   limits = Collections.unmodifiableMap(limits);
   ```
   
   每处替换都要检查 null 拒绝、重复键异常、顺序、可变性和拷贝/视图语义；仅用于默认空值时优先沿用参考源码的 `Collections.empty*`。
   
   ### 10.6 字符串 API 和格式化
   
   参考文件：`McpServiceLoader.java` 使用 `String.format` 生成 `ServiceConfigurationError` 消息。
   
   ```java
   throw new ServiceConfigurationError(
        String.format("No %s available for creating McpJsonMapper",
                supplierType.getSimpleName()));
   ```
   
   转换示例：
   
   ```java
   // Java 11+
   String message = "No %s available".formatted(typeName);
   boolean missing = value.isBlank();
   
   // Java 8：
   String message = String.format("No %s available", typeName);
   boolean missing = value == null || value.trim().isEmpty();
   ```
   
   `trim()` 只等价于 ASCII/`char <= U+0020` 范围的旧语义；如果原代码依赖 Java 11 `isBlank()` 的 Unicode 空白语义，必须实现专用辅助方法并补充边界测试，不能无条件替换。
   
   ### 10.7 switch 表达式、文本块和高版本 API
   
   参考源码的 Java 8 风格是普通 `switch`、普通字符串和显式语句；若参考目录没有对应高版本写法，仍按以下目标形式转换，并在改造记录中标记为“无直接参考样例”。
   
   ```java
   // Java 14+
   String method = switch (kind) {
    case REQUEST -> "request";
    case NOTIFICATION -> "notification";
    default -> "unknown";
   };
   
   // Java 8
   String method;
   switch (kind) {
    case REQUEST:
        method = "request";
        break;
    case NOTIFICATION:
        method = "notification";
        break;
    default:
        method = "unknown";
        break;
   }
   ```
   
   文本块应改成普通字符串，并保留实际换行、转义和 JSON 内容：
   
   ```java
   String json = "{\n"
        + "  \"method\": \"ping\"\n"
        + "}";
   ```
   
   同时将 `stream.toList()` 改为 `stream.collect(Collectors.toList())`，将 `Optional.orElseThrow()` 改为显式提供 Java 8 的异常 supplier，并逐项确认结果是否可变。
   
   ### 10.8 ServiceLoader、Optional 和错误消息
   
   参考文件：`McpServiceLoader.java`。核心转换不能只把 `findFirst()` 改成 stream；应保留 iterator 行为、空服务处理、缓存和同步：
   
   ```java
   protected Optional<S> serviceLoad(Class<S> type) {
    Iterator<S> iterator = ServiceLoader.load(type).iterator();
    return iterator.hasNext() ? Optional.of(iterator.next()) : Optional.empty();
   }
   
   public synchronized R getDefault() {
    if (supplierResult == null) {
        if (supplier == null) {
            Optional<?> loaded = serviceLoad(supplierType);
            if (!loaded.isPresent()) {
                throw new ServiceConfigurationError(
                        String.format("No %s available for creating McpJsonMapper",
                                supplierType.getSimpleName()));
            }
            supplier = (S) loaded.get();
        }
        supplierResult = supplier.get();
    }
    return supplierResult;
   }
   ```
   
   参考文件中的显式 `Optional` 仅表示“找到/未找到”；不要用 `get()` 替代 `isPresent()` 检查，也不要删除 `synchronized` 或供应商缓存。`McpJsonInternal` 中的 `META-INF/services` 加载同样要验证资源存在和异常传播。
   
   ### 10.9 函数式接口、泛型边界和 lambda
   
   参考文件：`McpClientSession.java`、`McpJsonMapperSupplier.java`、`JsonSchemaValidatorSupplier.java`。Java 8 目标必须保留 `@FunctionalInterface`、SAM 方法、返回值和泛型边界：
   
   ```java
   @FunctionalInterface
   public interface RequestHandler<T> {
    Mono<T> handle(Object params);
   }
   
   @FunctionalInterface
   public interface McpJsonMapperSupplier
        extends Supplier<McpJsonMapper> {
   }
   ```
   
   调用方显式声明泛型，但不改变通配符方向：
   
   ```java
   Function<? super Mono<Void>, ? extends Publisher<Void>> connectHook =
        publisher -> publisher;
   Map<String, RequestHandler<?>> handlers = new HashMap<String, RequestHandler<?>>();
   handlers.put("ping", params -> Mono.just(Collections.emptyMap()));
   ```
   
   `McpRequestHandler`、`McpNotificationHandler`、session factory 以及 `BiFunction`/`Function`/`Supplier` 字段均按同样规则迁移。若显式类型导致 lambda 或方法引用重载解析变化，应调整局部变量声明或增加明确 cast，但不得改变公开方法签名。
   
   ### 10.10 原子操作、Reactive 类型和接口结构
   
   原子操作示例（仅在扫描发现 Java 9+ 原子 API 时使用）：
   
   ```java
   // 高版本 API
   if (state.compareAndExchange(oldValue, newValue) == oldValue) {
    onChanged();
   }
   
   // Java 8 等价的 CAS 循环
   for (;;) {
    State current = state.get();
    if (!Objects.equals(current, oldValue)) {
        break;
    }
    if (state.compareAndSet(current, newValue)) {
        onChanged();
        break;
    }
   }
   ```
   
   要以实际并发语义为准决定使用 `compareAndSet` 循环还是 `getAndSet`，不能仅为通过编译而替换。`Mono`、`Flux`、`Publisher`、`Scheduler` 不是 Java 8 语法问题；只转换其调用方的高版本 Java API，保留 back-pressure、调度和 `contextWrite` 等 Reactor 语义。Java 8 已支持 interface `default`/`static` 方法和嵌套类型，只有 Java 9 的 private interface method 才需要改为外部辅助方法或实现类方法。
   
   ### 10.11 示例使用与验收要求
   
   以上代码是迁移模板，不是可直接复制到所有文件的机械替换。执行每个批次时必须在记录中填写：
   
   | 风格 | 参考文件/位置 | 本项目对应文件 | 行为核对 |
   |---|---|---|---|
   | 普通 final 数据类 | `McpServerFeatures.Async` | 实际 record/DTO | 构造器校验、访问器、equals/hashCode/toString |
   | 显式 cast | `TypeRef` 构造器 | 模式匹配命中处 | 短路、作用域、异常行为 |
   | 显式泛型 TypeRef | `McpSchema`、`McpAsyncClient` | 匿名泛型类 | 保留实际类型捕获 |
   | Collections 默认值 | `McpServerFeatures` | 集合工厂调用处 | null、顺序、重复、不可变性 |
   | ServiceLoader iterator | `McpServiceLoader` | SPI 加载处 | 首个实现、空实现、缓存、同步 |
   | 函数式接口 | `McpClientSession` 等 | handler/supplier 接口 | SAM、泛型、lambda/方法引用 |
   | 原子/Reactive/接口 | 参考目录对应工具和会话类 | 实际调用处 | 竞态、back-pressure、二进制形状 |
   
   最终仍须按第 6 节执行编译、测试和静态扫描；其中 Servlet 与 `java.net.http.HttpClient` 适配实现继续按第 2 节排除，不因新增示例而扩大或缩小排除边界。
   
   ### 10.12 Java 8 辅助方法能否替代高版本 API
   
   可以通过 `Utils` 或包内专用兼容辅助类集中封装部分 Java 9+ API 的替代实现，但不能把所有调用机械改成“工具方法调用”。辅助方法必须先定义并测试与原 API 相同的契约，包括 null 处理、不可变性、顺序、重复键、异常类型以及并发线性化语义。Java 中没有真正的扩展方法，推荐使用静态方法或静态导入；若辅助方法仅服务迁移实现，优先放在非公开的兼容工具类中，避免无必要地扩大 `Utils` 公共 API。
   
   #### 10.12.1 `compareAndExchange`：可以封装，但必须保留 identity 和 CAS 语义
   
   `AtomicReference.compareAndExchange(expected, update)` 的判断是对象身份比较（`==`），返回值是操作线性化点观察到的旧值；不能使用 `Objects.equals`，也不能简单用一次 `getAndSet` 替代，因为后者会无条件覆盖并破坏“仅当期望值匹配才更新”的语义。
   
   可复用的 Java 8 实现示例：
   
   ```java
   public static <T> T compareAndExchange(AtomicReference<T> reference,
                                      T expected,
                                      T update) {
    for (;;) {
        T current = reference.get();
        if (current != expected) {
            return current;
        }
        if (reference.compareAndSet(expected, update)) {
            return expected;
        }
        // 其他线程已经改变了值，重新读取并返回新的观察值或继续竞争。
    }
   }
   ```
   
   注意：对于本项目 `AtomicReference` 的“若当前 stream 仍是 this，则替换为 missing session”场景，也可以直接使用 `compareAndSet(this, missing)`，因为调用方只关心是否成功；若必须保持 JDK 9 API 的返回旧值行为，才使用上面的 helper。`AtomicInteger`、`AtomicLong` 等类型不能未经语义核对共用引用版本，应分别实现或保留原子类型专用逻辑。
   
   #### 10.12.2 `Map.of`/`List.of`：可以封装固定规模工厂，但不建议用 `Object...` 伪造通用 API
   
   对于空集合、单元素集合和少量固定元素，可增加类型安全的重载，例如：
   
   ```java
   public static <K, V> Map<K, V> immutableMap() {
    return Collections.emptyMap();
   }
   
   public static <K, V> Map<K, V> immutableMap(K key, V value) {
    return Collections.unmodifiableMap(Collections.singletonMap(
            Objects.requireNonNull(key, "key"),
            Objects.requireNonNull(value, "value")));
   }
   
   public static <K, V> Map<K, V> immutableMap(K key1, V value1,
                                             K key2, V value2) {
    K checkedKey1 = Objects.requireNonNull(key1, "key1");
    K checkedKey2 = Objects.requireNonNull(key2, "key2");
    if (checkedKey1.equals(checkedKey2)) {
        throw new IllegalArgumentException("duplicate key: " + checkedKey1);
    }
    Map<K, V> result = new LinkedHashMap<K, V>();
    result.put(checkedKey1, Objects.requireNonNull(value1, "value1"));
    result.put(checkedKey2, Objects.requireNonNull(value2, "value2"));
    return Collections.unmodifiableMap(result);
   }
   
   public static <K, V> Map<K, V> immutableCopy(Map<? extends K, ? extends V> source) {
    Objects.requireNonNull(source, "source");
    Map<K, V> copy = new LinkedHashMap<K, V>();
    for (Map.Entry<? extends K, ? extends V> entry : source.entrySet()) {
        copy.put(Objects.requireNonNull(entry.getKey(), "key"),
                Objects.requireNonNull(entry.getValue(), "value"));
    }
    return Collections.unmodifiableMap(copy);
   }
   ```
   
   但要注意以下边界：
   
   - `Map.of` 拒绝 null，辅助方法必须显式 `requireNonNull`，不能让 `HashMap` 静默接收 null。
   - `Map.of` 对重复键抛出异常；直接 `put` 会覆盖旧值，必须手工检测重复键或使用 entry 列表检测。
   - `Map.copyOf` 是拷贝后不可变结果；`Collections.unmodifiableMap(source)` 只是不可变视图，源 map 后续变化会被观察到。替代 `copyOf` 时必须先复制。
   - `Map.of` 的迭代顺序不应被业务依赖；若原代码/测试明确依赖插入顺序，使用 `LinkedHashMap` 后复制并包装，同时记录这是项目所需的额外顺序保证。
   - 不建议使用 `immutableMap(Object... keyValues)`：它会丢失泛型约束、容易接受奇数参数，并可能改变重复键和 null 的报错位置。多元素场景优先使用显式 `LinkedHashMap` 填充，或提供类型安全的 `Map.Entry<K,V>` 工厂。
   
   因此，方案执行时的优先级是：空集合使用 `Collections.empty*`；固定少量集合可使用经过测试的类型安全 helper；复杂或需要特殊顺序/重复键语义的集合在调用点显式构造；原子操作 helper 只用于已确认相同并发语义的场景。
   
   ---
   
   ## 11. 全量类扫描结论与方案修订（2026-10-21）
   
   ### 11.1 扫描结论
   
   已扫描 `mcp-core/src/main/java` 下的全部 Java 类，并按 `client`、`common`、`json`、`modern`、`server`、`spec`、`util`、transport 及测试边界逐类核对。本方案此前的转换原则是正确的，但**不能仅凭原有章节保证每个类都能被完整转换**。主要缺口是：
   
   - `modern/server` 与 `modern/server/feature` 没有逐类迁移指引；
   - `StdioMcpTransport` 不是排除项，但此前没有明确列出；
   - `java.net.http` 的直接传递依赖没有形成闭包清单；
   - 大量嵌套、局部和私有 `record` 没有单独验收契约；
   - 异常、资源所有权、取消、线程和 Reactive 时序没有形成逐类矩阵；
   - 测试、Javadoc、JSON fixture 与 OSGi 资源没有形成独立覆盖矩阵；
   - Maven compiler、bnd、jar 内容和 profile 的关系仍停留在原则层面。
   
   因此，本节及后续章节是对前文的补充和细化；若前文与本节的文件边界冲突，以本节的闭包清单和逐类矩阵为准。此次扫描不修改任何生产源码。
   
   ### 11.2 类群覆盖矩阵
   
   | 类群 | 转换范围 | 必查内容 | 执行批次 | 覆盖状态 |
   |---|---|---|---|---|
   | `json`、`json/schema` | 全部保留 | `TypeRef`、SPI、supplier 缓存、嵌套 `ValidationResponse` | A1 | 已补强 |
   | `common` | 全部保留 | `Map.copyOf` 快照、null、不可变性、context API | A1 | 已补强 |
   | `util` | 全部保留 | `ServiceLoader`、字符串空白、URI、工具异常与资源 | A1 | 已补强 |
   | `spec` 基础接口/异常 | 全部保留 | SAM、default/static、异常序列化、泛型边界 | A2 | 已补强 |
   | `spec.McpSchema` | 全部保留 | JSON-RPC、能力、资源、工具、提示、内容、分页、builder | A2-A4 | 已拆批 |
   | `spec` session/transport | 全部保留 | CAS、并发集合、close/cancel、Reactive 时序 | A5 | 已补强 |
   | `client` 非 JDK HTTP | 全部保留 | lifecycle、sync/async、TypeRef、stdio、Feature DTO | A6 | 已补强 |
   | `server` 非 Servlet | 全部保留 | sync/async/stateless、builder、feature specification、过滤器 | A7-A8 | 已补强 |
   | `modern` schema/JSON-RPC | 全部保留 | 独立 record 族、JSON 注解、结果层次和多态分派 | B1 | 新增 |
   | `modern.server` | 全部保留 | response、input-required、streaming、HMAC、订阅、异常 | B2 | 新增 |
   | `modern.server.feature` | 全部保留 | page、repository、change、sync/async adapter、分页和背压 | B3 | 新增 |
   | `modern.server.transport.StdioMcpTransport` | 全部保留 | `InFlight`、stdin/stdout、取消、关闭和 `Thread.onSpinWait` | B4 | 新增 |
   | Servlet 适配 | 不转换 | `jakarta.servlet.http` 及专属测试 | C1 | 明确排除 |
   | JDK HTTP 适配闭包 | 不转换 | `java.net.http`、`Flow` 及专属测试 | C2 | 明确排除 |
   
   “全量”不等于每个普通接口都要有独立示例；每个文件必须归入上表一个类群，并在执行记录中填入文件路径、命中项、批次和验证结果。未归类文件不得标记为完成。
   
   ### 11.3 完整排除清单与依赖闭包
   
   #### Servlet 适配及专属测试
   
   生产源码排除：
   
   - `src/main/java/io/modelcontextprotocol/modern/server/transport/HttpServletMcpTransport.java`
   - `src/main/java/io/modelcontextprotocol/server/transport/HttpServletHeaderAccessor.java`
   - `src/main/java/io/modelcontextprotocol/server/transport/HttpServletRequestUtils.java`
   - `src/main/java/io/modelcontextprotocol/server/transport/HttpServletSseServerTransportProvider.java`
   - `src/main/java/io/modelcontextprotocol/server/transport/HttpServletStatelessServerTransport.java`
   - `src/main/java/io/modelcontextprotocol/server/transport/HttpServletStreamableServerTransportProvider.java`
   
   仅服务于上述实现的测试和 fixture 随同排除；`HeaderAccessor`、`ServerTransportSecurityValidator`、`DefaultServerTransportSecurityValidator`、`ServerHttpHeaderValidator`、`McpTransportContext`、session、schema、通用异常和 `StdioServerTransportProvider` 不得因被 Servlet 引用而排除。
   
   #### `java.net.http.HttpClient` 适配及专属依赖闭包
   
   除前文列出的两个 transport 和 `ResponseBodyHandlers` 外，以下直接依赖 `java.net.http` 的类型也归入适配闭包，不做 Java 8 转换：
   
   - `src/main/java/io/modelcontextprotocol/client/transport/HttpRequestSnapshot.java`
   - `src/main/java/io/modelcontextprotocol/client/transport/McpHttpClientTransportAuthorizationException.java`
   - `src/main/java/io/modelcontextprotocol/client/transport/customizer/McpAsyncHttpClientRequestCustomizer.java`
   - `src/main/java/io/modelcontextprotocol/client/transport/customizer/McpSyncHttpClientRequestCustomizer.java`
   - `src/main/java/io/modelcontextprotocol/client/transport/customizer/McpHttpClientAuthorizationErrorHandler.java`
   - `src/main/java/io/modelcontextprotocol/client/transport/customizer/McpHttpClientTransportAuthorizationErrorHandler.java`
   - `src/main/java/io/modelcontextprotocol/client/transport/customizer/DelegatingMcpAsyncHttpClientRequestCustomizer.java`
   - `src/main/java/io/modelcontextprotocol/client/transport/customizer/DelegatingMcpSyncHttpClientRequestCustomizer.java`
   - `src/main/java/io/modelcontextprotocol/client/transport/HttpClientSseClientTransport.java`
   - `src/main/java/io/modelcontextprotocol/client/transport/HttpClientStreamableHttpTransport.java`
   - `src/main/java/io/modelcontextprotocol/client/transport/ResponseBodyHandlers.java`
   
   `java.util.concurrent.Flow` 在本项目中与上述 JDK HTTP response body 适配绑定，随 C2 闭包隔离。`StdioClientTransport`、`ServerParameters`、`SseMessageEndpointValidator`、`DefaultSseMessageEndpointValidator` 及不直接引用上述类型的通用接口仍须转换。排除判断以直接类型依赖和专属依赖闭包为准，不能按整个 `client.transport` 包排除。
   
   每个排除文件必须填写：直接依赖、仅服务的上游/下游类型、测试归属、Java 8 profile 行为。若共享类型同时被 core 引用，保留共享类型并在适配 profile 中编译适配层。
   
   ### 11.4 Java 9+ API 补充清单
   
   除前文 API 外，本次扫描确认必须显式处理：
   
   - `Optional.isEmpty()` → `!isPresent()`，仅命中 `util/McpServiceLoader.java`（`sl.isEmpty()` 形式）；第二轮定点扫描修正：`modern/server/McpServer.java` 并无 `Optional.isEmpty()`，该文件的实际命中是 `instanceof` 模式匹配（第 140 行）与 `String.isBlank()`（第 140、234 行）；
   - `Thread.onSpinWait()`，命中 `modern/server/feature/McpChangeFeed.java`。不得无条件删除；应根据自旋目的选择 `Thread.yield()`、有限退避或 Java 8 兼容的空操作，并用并发/性能测试确认；
   - `Stream.toList()`，命中 client/server/feature 类；替换为 `Collectors.toList()` 时单独确认可变性；
   - `findFirst()` 本身是 Java 8 API，不得把所有 `findFirst()` 误报为高版本问题；只替换 `ServiceLoader.findFirst()`，并保留普通 Stream `findFirst()` 语义；
   - `String.isBlank()`、`String.formatted()`、`List/Set/Map.of*`、`copyOf`、switch 表达式、模式匹配、文本块和增强 try-with-resources按前文规则处理。
   
    扫描结果必须分为“语言语法”“JDK API”“第三方 Reactor API”三类，避免把 Reactor 方法误判成 JDK 8 问题。
   
    #### 11.4.1 第二轮全量扫描修正与精确命中清单（2026-10-21）
   
    本节由第二轮逐文件扫描产生，是对 11.4 的修正与数据化。执行时以本清单为基线核对，每完成一个文件划掉对应项：
   
    **生产源码精确命中（非排除）：**
   
    | 语法/API | 精确命中文件与位置 |
    |---|---|
    | `record`（含嵌套） | 21 个文件（见 11.5 契约），重灾区：`spec/McpSchema.java`、`modern/McpSchema.java`、`modern/JsonRpc.java`、两套 server features、4 个 Page 类、`HmacRequestStateCodec.Payload`、`StdioMcpTransport.InFlight`、`json/schema/JsonSchemaValidator.java` 的嵌套 `ValidationResponse`、session 内部 `MethodNotFoundError`/`PendingRequest` |
    | `var` | 22 个文件：`client/McpAsyncClient`、`client/McpClient`、`client/McpClientFeatures`、`client/transport/StdioClientTransport`、`json/schema/JsonSchemaValidator`、`server/McpAsyncListFilter`、`server/McpAsyncServer`、`server/McpServer`、`server/McpServerFeatures`、`server/McpStatelessAsyncServer`、`server/McpStatelessServerFeatures`、`server/transport/ServerTransportSecurityValidator`、`server/transport/StdioServerTransportProvider`、`spec/McpClientSession`、`spec/McpError`、`spec/McpSchema`、`spec/McpServerSession`、`spec/McpStreamableServerSession`、`util/ToolInputValidator` |
    | instanceof 模式匹配 | `client/McpAsyncClient`、`modern/server/feature/SyncAdapters`、`modern/server/feature/ToolsFeature`、`modern/server/McpServer`（140 行）、`modern/server/SubscriptionsFeature`、`modern/server/transport/StdioMcpTransport`、`server/DefaultMcpStatelessServerHandler`、`server/McpAsyncServer`、`server/McpAsyncServerExchange`、`server/McpStatelessAsyncServer`、`spec/McpClientSession`、`spec/McpServerSession`、`spec/McpStreamableServerSession` |
    | switch 箭头表达式 | 仅 `modern/server/McpServer.java`（非排除）；另两处 `HttpClientSseClientTransport`、`HttpClientStreamableHttpTransport` 属排除闭包 |
    | `List/Map.of` 系 | 36 个文件（含 `common/DefaultMcpTransportContext`、`spec/McpError`、`spec/McpTransport`、`spec/McpStatelessServerTransport`、`spec/McpServerTransportProviderBase`、`util/ToolInputValidator`、`server/McpAsyncListFilter`、各 feature 接口的 `Set.of(...)` 方法集） |
    | `Set.of(...)` | 11 处、8 个文件：`StdioClientTransport`(50)、`McpFeature`(45)、`DiscoverFeature`(38)、`SubscriptionsFeature`(69)、`CompletionsFeature`(65)、`ResourcesFeature`(76,102)、`PromptsFeature`(78,114)、`ToolsFeature`(90,166) |
    | `Stream.toList()` | `client/McpAsyncClient`、`server/McpAsyncServer`、`server/McpServerFeatures`、`server/McpStatelessAsyncServer`、`server/McpStatelessServerFeatures` |
    | `String.isBlank`/`formatted` | `util/Assert`、`util/McpServiceLoader`、`util/Utils`、`spec/McpSchema`、`spec/McpTransportSessionClosedException`、`modern/server/McpServer`、`modern/server/transport/StdioMcpTransport`、`server/transport/DefaultServerTransportSecurityValidator`；`formatted` 仅 `McpServiceLoader`(59) |
    | `ServiceLoader.findFirst()` | 仅 `util/McpServiceLoader.java`(48) |
    | `Objects.requireNonNullElse` | `spec/McpClientSession`(166)、`spec/McpServerSession`(321)、`spec/McpStreamableServerSession`(545)、`server/DefaultMcpStatelessServerHandler`(52) —— **Java 9 API，前几版方案未列出，必须替换为三元表达式或 Utils helper** |
    | `Collectors.toUnmodifiableMap` | `server/transport/ServerTransportSecurityValidator`(42) —— **Java 10 API，前几版方案未列出，替换为 `collect(toMap)` + `unmodifiableMap`，或 Utils.immutableCopy(Map)** |
    | `AtomicReference.compareAndExchange` | `spec/McpStreamableServerSession`(570,582,600) —— 按第 10.12.1 节处理 |
    | `Thread.onSpinWait()` | `modern/server/feature/McpChangeFeed`(34) —— Java 9 API，按 11.4 处理 |
    | 匿名类 diamond `new TypeRef<>() {}` | 24 个文件、50+ 处：重灾区 `client/McpAsyncClient`（约 20 处静态常量 + 588/996 行内联）、`server/McpAsyncServer`(834,1027)、`server/McpStatelessAsyncServer`(625,767)、`server/McpAsyncServerExchange`(41-50)、`spec/McpSchema`(197)、`modern/McpSchema`(38)、`modern/server/McpServer`(58)、`util/KeepAliveScheduler`(38)；非 TypeRef 的 `new ArrayList<>()`、`new HashMap<>()` 等**非匿名类 diamond，Java 8 完全合法，不需要转换** |
    | 文本块 | 生产源码 0 处；仅测试源码 2 处（见 11.9） |
   
    **重要更正：** 第二轮扫描确认以下两类属于误报，不得浪费时间：
   
    - `new ArrayList<>()`、`new HashMap<>()` 等普通泛型构造的 diamond 是 Java 7 特性，**不需要转换**；只有匿名类上的 diamond（`new TypeRef<>() {}`）才是 Java 9 特性。
    - Stream 的 `.findFirst()`（如 `DefaultServerTransportSecurityValidator:161`）是 Java 8 API，**不需要转换**；只有 `ServiceLoader.findFirst()` 需要转换。
   
    **测试源码精确命中（非排除）：**
   
    | 类型 | 文件 |
    |---|---|
    | record 声明 | `McpServerMrtrTests`、`GsonMcpJsonMapperTests` |
    | 文本块 | `GsonMcpJsonMapperTests`、`LoopbackMcpHttpServer`（后者属 C2 排除） |
    | 匿名 diamond | `McpClientSessionTests`(40) |
    | var/List.of/模式匹配等 | `LifecycleInitializerTests`、`LifecycleInitializerPostInitializationHookTests`、`McpAsyncClientElicitationDefaultsTests`、`McpAsyncClientTest`、`McpSyncClientTest`、`McpTransportContextTests`、`McpUriTemplateManagerTests`、`MockMcpClientTransport`、modern 全部 feature/`McpServerTests`/`McpServerMrtrTests`/`ModernTestFixtures`/`SubscriptionsFeatureTests`/`StdioMcpTransportTests`、`DefaultMcpStatelessServerHandlerTests`、`McpAsyncListFilterTests`、两个 Exchange tests、`ResourceTemplateListingTest`、两个 ToolSpecification builder tests、`DefaultServerTransportSecurityValidatorTests`、`DefaultMcpTransportSessionTests`、`JSONRPCRequestMcpValidationTest`、`McpClientSessionTests`、`McpErrorTest`、`McpStreamableServerSessionTests`、`AssertTests`、`ToolInputValidatorTests`、`ToolsUtils`、`UtilsTests`、`McpServerMrtrTests` |
    | C1 Servlet 排除测试 | `HttpServletRequestUtilsTests`（含 jakarta 引用）、`DefaultServerTransportSecurityValidatorTests` 内引用 Servlet 类的部分需核对 |
   | C2 JDK HTTP 排除测试 | `DelegatingMcpAsyncHttpClientRequestCustomizerTest`、`DelegatingMcpSyncHttpClientRequestCustomizerTest`、`McpHttpClientAuthorizationErrorHandlerTest`、`McpHttpClientTransportAuthorizationErrorHandlerTest`、`LargeSseEventDecodingTests`、`ResponseBodyHandlersSendAsyncTests`（均含 `java.net.http` 引用）；`LoopbackMcpHttpServer`（loopback 服务器工具，被排除测试引用）；`HttpClientSseClientTransportConnectTests`、`HttpClientStreamableHttpTransportSendMessageTests`、`HttpClientStreamableHttpTransportSseEventTypeTest`、`HttpClientHttpTransportLeakTests`、`ResponseBodyHandlersSendAsyncTests`、`Utf8LineDecoderTests`、`Utf8LineDecoderBoundTests`、`SseEventParserTests`（测试排除类 ResponseBodyHandlers 内嵌的 decoder/parser，随闭包排除） |
   
    **资源文件：** `src/main/resources/OSGI-INF/io.modelcontextprotocol.json.McpJsonDefaults.xml` 与 `src/test/resources/logback.xml` 无 Java 语法问题，仅需验证随 jar 打包与运行时行为（11.8 节）。
   
    #### 11.4.2 转换难度分级与辅助工具预置
   
    为避免执行时误判难度，按文件给出难度分级：
   
    | 级别 | 文件 | 原因 |
    |---||---|---|
    | T1 轻量（零星 var/集合工厂） | `spec/HttpHeaders`、`spec/ProtocolVersions`、各异常类、`common/*`、`util/KeepAliveScheduler`（仅 TypeRef diamond）、`json/McpJsonDefaults` | 语法点少且独立 |
    | T2 中等（模式匹配/switch/高版本 API 组合） | 三大 session、`McpServiceLoader`、`DefaultMcpStatelessServerHandler`、`ServerTransportSecurityValidator`、`Assert`、`Utils`、各 feature 接口实现 | 需逐处核对语义 |
    | T3 重量（record 大户） | `spec/McpSchema`、`modern/McpSchema`、`modern/JsonRpc`、两套 server features、各 Page、`HmacRequestStateCodec`、`StdioMcpTransport`、两套 `JsonSchemaValidator`、`McpClientFeatures` | record 数量大、Jackson 注解密集 |
    | T4 排除（不转） | 11.3 节全部闭包 + 对应排除测试 | — |
   
    预置的 Utils 兼容辅助方法（按 10.12 节契约，先写测试再供调用方使用）：
   
    1. `Utils.isBlank(String)`（Unicode 语义与 `String.isBlank` 一致）；
    2. `Utils.format(String, Object...)`（内部 `String.format`）；
    3. `Utils.requireNonNullElse(T, T)`；
    4. `Utils.immutableMap/immutableList/immutableSet` 固定规模重载；
    5. `Utils.immutableCopy(Map)` / `immutableCopy(List)` / `immutableCopy(Set)`（对应 `Map.copyOf` 等）；
    5. `Utils.compareAndExchange(AtomicReference<T>, T, T)`（identity 语义，见 10.12.1）；
    6. `Utils.joinUnmodifiable(Map)`（对应 `Collectors.toUnmodifiableMap` 场景，保留 null 拒绝与合并冲突行为）。
   
    每个辅助方法必须先有单元测试覆盖 null、重复键、顺序、不可变性和异常类型，才允许被调用方使用（见 10.12 与 11.11）。执行时优先用既有 Java 8 API 直写（如 `Set.of(a,b)` → 显式 `HashSet` 填充 + `unmodifiableSet`），仅在高频复用点引入 helper，防止 Utils 膨胀。
   
     ### 11.4.3 第三轮全量重扫修正（2026-10-21，本节为当前最权威清单）
   
     本节由对全部 141 个生产文件、59 个测试文件的逐特征正则重扫产生，修正 11.4.1 的漏项与误报。**执行时以本节清单为唯一基线**，11.4.1 与之冲突处以本节为准：
   
     **(a) 11.4.1 漏报项（必须补充处理）：**
   
     | 缺口 | 精确位置 | 修正指引 |
   |---|---|---|
     | `compareAndExchange` 漏列 `LifecycleInitializer` | `client/LifecycleInitializer.java:275`，与 `getAndSet`(254,340,352)、`compareAndSet`(286) 混用 | 按 10.12.1 处理：`compareAndExchange(null, newInit)` 需返回旧值的场景用 identity-CAS helper；其余 `getAndSet` 是 Java 8 API 无需转换 |
     | instanceof 模式匹配漏列 `modern/JsonRpc.java` | 第 44 行 `!(... instanceof Map<?, ?> map)` | 泛型通配符模式变量也须显式 cast；旧正则因 `Map` 在排除名单被漏掉 |
     | instanceof 模式匹配漏列 `CompletionsFeature.java` | 第 70 行 `!(params instanceof Map<?, ?> map)` | 同上，须补入 B3 批次 |
     | instanceof 模式匹配漏列 `McpAsyncServerExchange.java` | 190 行 `instanceof McpSchema.ElicitFormRequest formRequest` | 已在其他清单，但 181/186 行普通 instanceof 无需转换，注意区分 |
     | switch 箭头漏列 `HttpServletMcpTransport.java` | 2 处 | 属 C1 Servlet 排除，随闭包排除即可，无需转换 |
     | `Set.of` 漏列两个 Resource repository | `McpAsyncResourceRepository.java:31`、`McpSyncResourceRepository.java:24`（`List.of()` 传参） | 11.4.1 的 Set.of 8 文件清单不含 repository，须补入 B3 批次 |
     | 测试文本块确认 2 处 | `GsonMcpJsonMapperTests`、`LoopbackMcpHttpServer`（后者 C2 排除） | 与 11.4.1 一致，非排除侧仅 Gson 一处 |
     | 测试匿名 diamond | `McpClientSessionTests.java:40` | 与 11.4.1 一致 |
   
     **(b) 本轮扫描确认的精确文件级命中统计（非排除 55 个文件命中，69 个零命中）：**
   
     - `record`：19 个非排除文件（11.4.1 记 21 个含排除项，非排除为 19）；
     - `var`：22 个文件（含 `server/McpServer.java` 全部 12 处集中于 builder 方法 240-2590 行，均为局部推断，直改显式类型即可）；
     - instanceof 模式匹配（含泛型通配符）：13 个非排除文件：`McpAsyncClient`、`SyncAdapters`、`ToolsFeature`、`modern/McpServer`、`SubscriptionsFeature`、`StdioMcpTransport`、`DefaultMcpStatelessServerHandler`、`McpAsyncServer`、`McpAsyncServerExchange`、`McpStatelessAsyncServer`、`McpClientSession`、`McpServerSession`、`McpStreamableServerSession`，外加 `modern/JsonRpc`、`CompletionsFeature` 共 15 个；
     - switch 箭头：非排除仅 `modern/server/McpServer.java`（2 处）；
     - 匿名类 diamond：9 个非排除文件（`json/TypeRef.java` 的 Javadoc 示例中也含 diamond，需一并显式化或改为文字描述）；
     - `List/Map/Set.of`：33 个非排除文件；`.copyOf`：7 个非排除文件；
     - `String.isBlank/formatted`：11 个非排除文件；`requireNonNullElse`：4 个文件；
     - `Stream.toList/toUnmodifiable`：6 个非排除文件；`compareAndExchange`：2 个文件；`onSpinWait`：1 个；`ServiceLoader.findFirst`：1 个。
   
     **(c) 零命中文件处理原则：** 69 个非排除零命中文件（如 `common/McpTransportContext`、全部 `spec` 异常类、`modern/server` 各接口与 Notifier、各 repository 接口、`util` 各接口）只需编译级核对（package 声明、import、序列化 ID、javadoc 示例），无需语法转换。但零命中**不等于免检**：仍须过 11.11 节全量清单登记，其中以下文件因间接依赖仍须专项核对：
     - `McpSyncClient`/`McpStatelessSyncServer`（包装 async 同类，验证委托不引入高版本 API）；
     - `McpTransportSession`（接口含 default 方法聚合多个抽象方法，二进制兼容核对）；
     - `json/TypeRef.java`（Javadoc 中 `new TypeRef<>() {}` 示例须处理，属文档性语法，编译可过但应同步修）；
     - `Params.java`、`InputResponses.java`（被 feature 实现类引用，随 B3 批次编译联动核对）。
   
     **(d) 测试源码第三轮确认：**
   
     - record：仅 `McpServerMrtrTests`（`ElicitAnswer`、`ExtensionInputRequired` 两个局部 record，153/251 行）与 `GsonMcpJsonMapperTests`（`Person`，18 行）；
     - 文本块：非排除仅 `GsonMcpJsonMapperTests`；`LoopbackMcpHttpServer` 属 C2 排除随闭包处理；
     - 匿名 diamond：`McpClientSessionTests:40`；
     - var/List.of/模式匹配：43 个测试文件（见 11.9 矩阵），其中 `McpServerTests` 实测 8 处 `Map.of/List.of`、0 处 `.toList()`、0 处 var（11.4.1 笼统归入“modern 全部 tests”过于宽泛，执行时应以逐文件 grep 结果为准，不得按目录批量假设）。
   
     **(e) 扫描方法学修正（防止下轮再漏）：**
   
     - 逐特征 grep 必须使用宽正则（如 `instanceof\s+\S+\s+[a-z]`），不能依赖类型白名单或窄正则；本轮已证明窄正则会漏掉 `Map<?, ?> map` 这类泛型通配符模式匹配；
     - 文件级并集统计必须与方案清单双向 diff（plan 有源无 → 误报需修正；源有 plan 无 → 漏报需补）；
     - `grep -c` 返回 0 时以退出码 1 中断管道，需用 `|| true` 防护，避免统计截断；
     - 测试清单不得按目录笼统归类，须落到文件级。
   
     ### 11.5 Record/枚举/匿名类逐类型契约
   
   每个顶层、嵌套、局部或私有 `record` 都必须建立一行迁移记录，至少包含：
   
   | 字段 | 要求 |
   |---|---|
   | 原始全限定名和嵌套层级 | 保持公开类型名、嵌套关系和可见性 |
   | 字段顺序与访问器 | 保留 `foo()`，不得自动改为 `getFoo()` |
   | canonical/compact/重载构造器 | 保持校验顺序、默认值、访问级别和委托关系 |
   | 注解 | 分别核对 type、field、method、parameter target，保留 Jackson 注解 |
   | 接口与泛型 | 保留 implements、上下界、协变/逆变和 SAM 形状 |
   | equals/hashCode/toString | 记录手写覆盖或 record 默认行为，并补回归测试 |
   | JSON/反射/二进制 | 分别验证 JSON 字段、`Record` 反射差异、`javap` 描述符 |
   
   必须单独覆盖以下批次：`spec.McpSchema`、`modern.McpSchema`、`modern.JsonRpc`、两套 server feature specification、page DTO、`ServerChange`、session 内部 `MethodNotFoundError`/`PendingRequest`、`HmacRequestStateCodec.Payload`、`StdioMcpTransport.InFlight` 以及 C2 排除闭包内的 record。`InFlight` 等并发内部对象不能套用 DTO 模板，需验证 `Disposable.Swap`、取消标志、可见性和关闭流程。
   
   枚举和匿名类新增规则：核对自定义字段/构造器/方法、常量级匿名类体、Jackson 枚举值、`name/ordinal/toString`、enum switch 完整性、匿名类捕获变量、diamond 泛型实际类型、lambda/方法引用重载选择。`new TypeRef<>() {}` 只是匿名泛型类的一种，不能作为唯一扫描对象。
   
   ### 11.6 Modern server 逐类转换指引
   
   #### `modern.server`
   
   - `McpAsyncResponse`、`McpSyncResponse`、`McpTransportResponse`：保留 `Result`、`Error`、`Streaming`、`Body` 的泛型边界、SAM 签名、Mono/Flux 返回类型和完成/异常时序；
   - `McpServer`：分别验证 dispatch、blocking/non-blocking、错误响应、input-required 和 streaming 路径；显式转换所有模式匹配，处理 `isBlank`、集合快照、`Optional.isEmpty` 和 switch；
   - `McpRequestContext`、`InputResponses`：区分空集合、不可变视图和拷贝语义，保持 metadata/input response 的 null 和重复键行为；
   - `HmacRequestStateCodec`：将 `Payload` 转类时保持 JSON 字段 `v/s/exp`、签名输入字节和过期判断；
   - `SubscriptionsFeature`、`DefaultAsyncNotifier`：保持订阅过滤、通知完成、取消、背压和异常传播；
   - `DiscoverFeature`、各 Notifier/Response/Transport 接口：加入接口兼容矩阵，保留 default/static 方法、泛型和可调用性。
   
   #### `modern.server.feature`
   
   - `ToolsPage`、`ResourcesPage`、`ResourceTemplatesPage`、`PromptsPage`：record 转 final 类，核对页面字段、TTL、cache scope、列表拷贝/可变性和工厂方法；
   - `ServerChange`：各嵌套 record 分别转换，并验证订阅分派的 `instanceof` 命中；
   - `ToolsFeature`、`ResourcesFeature`、`PromptsFeature`、`CompletionsFeature`：显式转换模式匹配，保留分页、repository 泛型、MCP 错误类型和消息；
   - 各 async/sync repository 与 `SyncAdapters`：保留 default/SAM、同步异常包装、Mono 调度、阻塞边界和 back-pressure；
   - `McpChangeFeed`、`McpChangePublisher`、`McpChangeBroadcaster`：将 `Thread.onSpinWait` 替代方案与广播并发、取消和资源释放一起验证。
   
   #### 非排除的 stdio transport
   
   `modern/server/transport/StdioMcpTransport.java` 不属于 Servlet 或 JDK HTTP 排除项，必须转换：处理 `InFlight`、模式匹配、`isBlank`、数字 ID 归一化、自旋等待、stdin/stdout 线程、取消和重复 close。它与传统 `StdioServerTransportProvider` 需要分别验证，不得直接复制实现。
   
   ### 11.7 异常、原生序列化、资源和 Reactive 语义矩阵
   
   JSON/Jackson 兼容、Java 原生序列化、公开 API/二进制兼容是三套独立验收口径。所有 Exception/RuntimeException/Error 子类逐一检查 `serialVersionUID`、新增字段和反序列化行为；不能因 record 转类而默认认为异常兼容。
   
   每个 session、transport、stdio、scheduler、process、stream、`Disposable`、`Disposable.Swap`、executor 和 subscription 都填写：创建者、所有者、正常关闭、error/cancel/timeout/EOF 关闭、重复关闭、关闭顺序和异常抑制关系。必须覆盖 `close`、`closeGracefully`、`usingWhen`、`doFinally`、stdin/stdout 和 pending request 清理。
   
   对 Reactor 类不做语法替换，而是建立 API/版本矩阵：当前 Reactor BOM `2026.0.0-M2`、最低可接受版本、每个调用（如 `contextWrite`、`deferContextual`、`usingWhen`、`switchOnFirst`、`filterWhen`、`Schedulers.boundedElastic`、`Disposable.Swap`）的引入版本和 Java 8 运行要求。分别测试线程切换、Context 传播、取消、背压、异常信号、同步 `.block()` 边界、scheduler/非 daemon 线程泄漏。
   
   ### 11.8 SPI、OSGi、并发和接口矩阵
   
   `McpServiceLoader` 必须将 `ServiceLoader.load(type).findFirst()` 改为 Java 8 iterator，并替换 `Optional.isEmpty()`、`formatted()`；验证首个实现、空实现、`ServiceConfigurationError`、supplier override/unset、缓存失效、同步和类加载器行为。`McpJsonDefaults` 的 supplier 生命周期与其一并测试。除 `META-INF/services` 外，还必须验证 `src/main/resources/OSGI-INF/io.modelcontextprotocol.json.McpJsonDefaults.xml` 的 SCR 资源进入 jar、激活、注入和注销。
   
   逐点记录 CAS：`LifecycleInitializer` 的一次性初始化竞争使用 identity CAS；`McpStreamableServerSession` 的 listening stream 替换使用 identity CAS；禁止 `Objects.equals` 和无条件 `getAndSet`。并发集合必须记录弱一致遍历、快照/视图、关闭期间 pending request、订阅广播和 session sweep 的可接受行为。
   
   接口/API 矩阵必须覆盖 public/protected/package-private 类型、嵌套接口、default/static 方法、`@FunctionalInterface`、checked exception、泛型通配符、Mono/Flux/Publisher 返回值、builder 返回类型和现代 repository/response/feature 接口。对公开 API 使用 `javap -p -s` 或 japicmp/API diff 检查方法描述符、协变返回和 bridge method。
   
   ### 11.9 测试、Javadoc、fixture 和资源覆盖矩阵
   
   扫描报告分成五类，不得只扫描可执行语句：
   
   1. 非排除生产源码；
   2. 非排除测试源码；
   3. Servlet/JDK HTTP 排除测试；
   4. Javadoc、注释中的代码示例；
   5. JSON fixture、资源文件、`META-INF/services`、OSGi XML。
   
   测试类必须登记所属生产类、是否适配排除、是否使用 record/var/文本块/模式匹配/高版本 API、是否验证 JSON round-trip、集合不可变性、Reactive 时序、CAS 或资源释放。特别处理 `GsonMcpJsonMapperTests` 的测试 record、modern stdio tests、session concurrency tests 和 JDK HTTP loopback tests。排除测试不得拖入 core Java 8 profile；共享测试工具不得反向引用排除类型。
   
   ### 11.10 Maven/bnd/profile 执行方案
   
   当前 `mcp-core/pom.xml` 由根 POM 继承 `<release>${java.version}</release>`，并且 bnd 会处理编译输出；因此仅改变 compiler source 或仅排除 `javac` 文件不足以证明兼容。执行前必须在 `effective-pom` 中确认 source/test includes/excludes、bnd 输入目录、jar 内容和 profile 行为。
   
   优选方案是将 Java 8 core 与 Servlet/JDK HTTP adapter 拆成独立 artifact/module；若本次必须保持 `mcp-core` 单模块，则建立互斥的 `java8-core` 与 `java17-adapters` 验证 profile，至少明确：
   
   - compiler 使用 `--release 8`，而不是只设置 source/target；
   - Java 8 profile 的生产 includes/excludes 覆盖第 11.3 节完整闭包；
   - testCompile/test 也使用独立 includes/excludes；
   - `jakarta.servlet-api`、JDK HTTP 专属测试依赖和 Byte Buddy 的 scope/是否参与 Java 8 profile；
   - bnd 只处理当前 profile 的 class 集合，不能扫描未编译适配类；
   - core jar 不包含排除类，adapter profile 单独验证完整 Java 17 类；
   - `Automatic-Module-Name`、OSGi Service-Component、`META-INF/services` 和导出包保持预期；
   - 明确这是源码/字节码兼容验证还是可发布 Java 8 core artifact，不得混淆；
   - JUnit/Mockito/Reactor 等第三方依赖的 Java 最低版本单独记录，不能把测试依赖限制伪装成生产代码兼容。
   
   profile 不是本次自动创建；只有在实际执行迁移并确认构建拓扑后，才能修改 POM。方案执行阶段须输出 profile 的 `mvn help:effective-pom`、依赖树、jar tf、bnd manifest 和 `--release 8` 编译日志。
   
   ### 11.11 全量静态扫描与完成判定
   
   完成前必须生成机器可读清单（CSV/Markdown 均可），每个 `src/main/java/**/*.java` 和纳入验证的 `src/test/java/**/*.java` 各占一行，字段至少为：路径、类/嵌套类型、生产/测试、排除原因、record/enum/匿名类、模式匹配、var、switch/text block、JDK 9+ API、Reactor API、线程/资源/CAS、所属批次、编译结果、行为测试结果。
   
   非排除源码完成判定：
   
   - 无 Java 9+ 语言语法和 JDK 9+ API；
   - 无 `java.net.http`、`java.util.concurrent.Flow` 或 Servlet 类型引用；
   - `Map/List/Set` 的 null、重复键、顺序、快照/视图和不可变性已验证；
   - record/接口/泛型/异常/JSON/反射/二进制差异已记录；
   - Reactive、线程、取消、关闭和资源释放测试通过；
   - Javadoc、fixture、SPI、OSGi 资源已独立检查；
   - Java 8 profile 的编译输出、bnd、jar、module name 和依赖边界通过验收。
   
   在上述条件全部满足前，不能声称“每个类均已完成 Java 8 转换”，只能报告为“方案已覆盖/仍有待转换项”。

   ### 11.12 第三轮重扫后的方案充分性结论（2026-10-21）

   以 11.4.3 的文件级清单为准，方案对每个类的指引充分性判定如下：

   - **124 个非排除生产文件**：55 个命中文件均有对应转换指引（record/var/模式匹配/switch/集合工厂/字符串 API/原子 API/ServiceLoader 各有专门章节与示例）；69 个零命中文件按 11.4.3(c) 原则只需编译级核对 + 间接依赖专项核对，方案已给出清单。指引充分。
   - **排除闭包 17 个生产文件**（C1 Servlet 6 个 + C2 java.net.http 11 个）：11.3 节清单与本轮 grep（I/J 两组）结果完全一致，无多排无漏排。边界正确。
   - **测试源码 59 个**：44 个非排除文件落入 11.9 矩阵；15 个排除测试落入 C1/C2；record/文本块/diamond 命中点已精确到行。测试清单充分。
   - **资源文件**：`OSGI-INF` XML 与 `logback.xml` 维持 11.4.1 结论，无 Java 语法问题。

   残留风险（不阻碍方案执行，但执行时必须遵守）：

   1. 本轮扫描为正则扫描，无法替代 `--release 8` 编译器的最终判定；执行时以 11.4.3 为基线，编译报错为准绳，两者不一致时以编译器为准并回写本方案；
   2. Javadoc/注释中的高版本示例（如 `json/TypeRef.java` 的 `new TypeRef<>() {}`）不影响编译，但已列入同步修复项；
   3. Reactor 2026.0.0-M2 的 Java 8 运行时兼容性仍未验证（11.7 节矩阵项），属执行阶段任务，不是方案缺口。

   **最终判定：方案已能指引 mcp-core 每个类完成转换。** 11.4.1 的 6 处漏报与 2 类误报已由 11.4.3 修正并升格为执行基线；后续执行按第 5 节批次顺序 + 11.4.3 文件清单推进，每文件完成即在 11.11 节清单登记。

---

## 12. 执行状态（2026 实施记录）

### 12.1 转换完成情况

| 批次 | 范围 | 状态 |
|---|---|---|
| A1 | util/common/json 包（Utils 增加 isBlank/compareAndExchange helper、TypeRef 修复） | 完成 |
| A5 | spec 包 28 文件（McpStreamableServerSession 亲转；8 文件委派转换；18 零命中确认） | 完成 |
| A2-A4 | spec/McpSchema.java 分 S1(L1-2100,30 record)/S2(2101-4300,10 record)/S3(4880-尾,44 record) 三段完成 | 完成 |
| A6 | client 包（StdioClientTransport 3 处；其余此前已部分转换） | 完成 |
| A7-A8 | server 包根目录 8 文件转换 + transport 非 Servlet 4 文件 | 完成 |
| B1 | modern/McpSchema.java 分 M1(L1-2287,31 record)/M2a(2288-3590,16)/M2b(3593-4456,12)/M2c(4457-尾,14) 四段完成；JsonRpc.java 同步完成 | 完成 |
| B2-B4 | modern/server 6 文件 + feature 包 13 文件 + StdioMcpTransport | 完成 |

### 12.2 编译验证

- 命令：JDK17 javac `--release 8` 直接编译非排除源码（Maven 构建仍按原 Java 17 配置，未改动 POM）
- classpath：reactor-core 3.8.7、reactive-streams 1.0.4、jackson-databind 2.21.1、jackson-annotations 2.20、jackson-core 2.21.1、slf4j-api 2.0.16
- 结果：**124 个非排除生产文件全部编译通过（EXIT=0）**，仅余 deprecation/unchecked 提示
- 排除闭包 17 文件（Servlet 6 + JDK HttpClient 11）按方案 11.3 保留原样

### 12.3 编译阶段额外修复（正则扫描漏网的 Java 9+ 特性）

| 位置 | 问题 | 修复 |
|---|---|---|
| server/McpServer.java | interface 内 private static 方法（Java 9+） | 移入嵌套 final 工具类 SchemaValidation，调用点加前缀 |
| modern/server/McpServer.java:214 | `instanceof Streaming<? extends Result> streaming` 泛型模式匹配 | raw instanceof + @SuppressWarnings cast |
| server/transport/StdioServerTransportProvider.java:335 | inner class 内 static 方法 | 去掉 static（改实例方法） |
| server/McpAsyncServer.java:584、McpStatelessAsyncServer.java:464 | `Optional.isEmpty()`（Java 11） | `!isPresent()` |
| client/transport/StdioClientTransport.java:347 | `StringBuilder.isEmpty()`（Java 15） | `length() == 0` |
| client/transport/StdioClientTransport.java:421 | `Process.onExit()`（Java 9） | CompletableFuture.supplyAsync + isAlive 轮询（50ms） |

### 12.4 遗留事项

- 测试代码未转换（用户指示暂缓）；测试目录仍为 Java 17 语法
- Maven POM 未加 Java 8 profile（属构建配置调整，超出本轮"转换+编译确认"范围）
- json-schema-validator 相关运行时依赖行为需测试阶段验证

### 12.5 测试阶段修复：record→class 后 Gson 绕过构造器校验（2026）

**现象**：`StdioMcpTransportTests.invalidEnvelopeProducesInvalidRequestAndTransportKeepsServing`、`invalidRequestIsAnsweredWithItsId` 超时（无响应）；`ResourcesFeatureTests.readWithoutUriIsInvalidParams` 得到 -32603 而非 -32602。

**根因**：测试侧 `GsonMcpJsonMapper` 在 record 时代会调用规范构造器（校验生效）；record→final class 后，Gson 用 `Unsafe` 实例化、不调构造器，`@JsonCreator` 仅 Jackson 识别，所有 `Assert` 校验被绕过：

- `id:null`/`jsonrpc:"1.0"` 的 envelope 被当作合法 request 构造出来（前者 dispatch 时 `putIfAbsent(null)` NPE，只打 warn 无响应；后者正常进 never 无响应）；
- `ReadResourceRequest` 缺 `uri` 时不再抛 IllegalArgumentException→INVALID_PARAMS，而是把 null 传到下游→INTERNAL_ERROR。

**修复**（恢复 record 语义、mapper 无关，生产代码 7 文件 + 新增 1 文件，未改任何单测）：

1. 新增 `util/SelfValidating.java`：校验重放接口，实现者暴露 `validate()` 重放规范构造器校验；
2. `JsonRpc` 三个 envelope 类实现 `SelfValidating`；`deserializeMessage` 在 `convertValue` 后重放校验；
3. `McpSchema` 四个入站请求类（CallToolRequest/ReadResourceRequest/GetPromptRequest/CompleteRequest）实现 `SelfValidating`；
4. `feature/Params.decode` 在 `convertValue` 后重放校验，失败仍归一为 INVALID_PARAMS。

**验证**：JDK17 `javac --release 8` 全量非排除源码 0 错误；`StdioMcpTransportTests`(26)+`ResourcesFeatureTests`(2) 全绿，modern 包全量 96 测试全部通过。

### 12.6 测试阶段排查：HmacRequestStateCodecTests 7 例全错（2026）

**现象**：12.5 修复后用户侧反馈 `HmacRequestStateCodecTests` 7 个 case 全部报错（此前全绿）。

**取证与根因**：真实错误栈为 `Unresolved compilation problem: The method of() is undefined for the type Map`（错误点统一在测试辅助方法 `ctx()` 第 29 行的 `Map.of`）。这是 Eclipse ECJ 特征错误，不是运行时断言失败。证据链：

1. `target/test-classes/.../HmacRequestStateCodecTests.class`（07:39 由 IDE 增量编译产出，class 版本 52=Java 8，class 内嵌 `Unresolved compilation problem` 标记）；
2. `mcp-core/.settings/org.eclipse.jdt.core.prefs` 的 `compliance/source/targetPlatform` 均为 **1.8**——生产源码转 Java 8 后 IDE 将整个模块（含测试源码）按 1.8 语法级别编译；
3. 测试源码仍是 Java 17 语法（12.4 明确暂缓），`Map.of` 在 1.8 级别下不可解析，ECJ 产出"带病 class"，surefire 一加载即抛错，7 个 case 共用 `ctx()` 故全部失败。

**结论**：非生产代码缺陷，非 12.5 修复的回归；是 IDE 测试编译级别与"测试代码保持 Java 17"的既有决策冲突。

**修复**（未改任何单测与生产源码）：

1. `mcp-core/.settings/org.eclipse.jdt.core.prefs` 恢复 `source/compliance/targetPlatform=17`（该文件未被 git 跟踪，属 IDE 本地配置；已备份 `/tmp/jdt.core.prefs.bak`）；
2. `rm -rf target/test-classes` 清除损坏产物，`mvn test` 重新编译验证。

**验证**（JDK17 + surefire 3.1.2）：`HmacRequestStateCodecTests` 7/7；回归 `StdioMcpTransportTests`(26)+`McpServerTests`(24)+`ResourcesFeatureTests`(2)+`McpServerMrtrTests`(9)=61/61；modern 全量 96/96 全绿，无新增回归。

**提示**：后续若在 IDE 中跑测试再遇同类批量 `Unresolved compilation problem`，优先核对 `.settings/org.eclipse.jdt.core.prefs` 与 target 下 class 时间戳，而非怀疑源码改动。
