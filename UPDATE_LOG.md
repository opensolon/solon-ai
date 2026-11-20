### vNEXT

  * **talent-cli：文件里有解不了的字节时，`edit` 不再改坏文件，`read` / `grep` 不再读不出或漏结果**（4.2.0，修复，有行为变化）：针对 GBK 等非 UTF-8 编码的文件、或混入零星坏字节的文件（以下按默认 `fileCharset` = UTF-8 描述）。① **`edit`（本地与虚拟来源）原样保留解不了的字节**：原来按替换模式解码、改完整体写回，哪怕只改一行 ASCII，全文所有坏字节（GBK 文件就是全部中文）都被写成 `EF BF BD`（按 GBK 看即“锕斤拷”），无法恢复且不提示。现在用 `TerminalSupport.decodeKeepingRawBytes` / `encodeKeepingRawBytes`：能解码的部分照常解码，解不了的字节逐个映射为孤立低代理 U+DC00+b（同 Python 的 surrogateescape），写回时还原，未编辑的部分逐字节不变；BOM、CRLF 照旧。含坏字节时先校验“解码再编码与原字节相同”，UTF-8 总成立；个别字符集不成立（如带 BOM 的 UTF-16 小端文件）时拒绝编辑（`terminal.edit.not_preservable`），文件不动。匹配失败（内容不匹配、全文替换未命中、执行失败）时，若文件含坏字节，在错误后追加一行说明（`terminal.edit.raw_bytes_hint`：这些字节显示为 �、可能是 GBK 等编码、old_str 匹配不到它们、只替换不含 � 的文本），诊断里的文件片段把映射字符显示为 �，不把孤立代理输出给模型。② **`new_str` 无法按文件字符集保存时拒绝**（`terminal.edit.unencodable`，文件不动）：原来孤立代理、以及 `fileCharset` 为 GBK 时的 emoji 等会被无声写成 `?`；顺带挡住“用 `\uDCxx` 往文件里写原字节”。③ **本地 `read` 改为替换模式解码**：原来用 `Files.lines`，遇到坏字节整个读取失败，模型只拿到 `UncheckedIOException: MalformedInputException: Input length = 1`；现在坏字节显示为 �、其余照常输出（与虚拟来源的 `read` 一致）。④ **本地 `grep` 改为替换模式解码**（含超过 10 MB 的流式回退路径）：原来读到第一个坏字节就停、只输出已解码部分的命中（小文件一条不出，大文件取决于 8 KB 解码缓冲位置），模型会把“没找到”当成确实没有；现在与 `grepVirtual` 一致，替换后继续搜，命中行里可能带 �。字节预筛随之放宽为“模式不含 U+FFFD”即可（每处坏字节至少产生一个 U+FFFD，不含它的命中片段必然来自合法字节）。下文“`grep` 读文件提速”一条中“坏字节时行为不变”的表述以本条为准。**未改**：`write`（整文件覆盖，按 UTF-8 写是其本意）；用中文搜 GBK 文件仍搜不到、`read` 仍把 GBK 中文显示为 �——自动识别文件编码另议。**i18n**：新增 3 个 key（中英），渲染快照新增 6 组场景，既有快照不变。**测试**：新增 `TerminalEditRawBytesTest` 10 例（GBK 文件改 ASCII 行含行号/宽松/全文替换、零星坏字节紧贴被替换文本与文件末尾不完整序列、BOM+CRLF、old_str 带 � 或真实中文时的提示与文件不动、不可保存的 new_str、UTF-16 拒绝、200 个随机合法文件与原实现逐字节相同、2 万组任意字节编解码往返、`read` 显示 �、虚拟来源）；`LegacySearch` 新增 `grepReplacing`（只把读取器换成替换模式），`TerminalGrepContentTest` / `TerminalSearchEquivalenceTest` 改以它为对照，坏字节用例改为断言新口径并证明原口径确会丢命中，模式集加入 `\uFFFD` 等，流式回退用例加入坏字节。变异验证 12 处全部被拦住：预筛不排除 U+FFFD、grep 主路径/回退路径改回报错模式、read 改回 `Files.lines`、本地/虚拟 edit 改回替换解码、本地/虚拟去掉 new_str 校验、映射判定不看前一个高代理、去掉往返校验、诊断不做展示替换、去掉提示。cli 全量 769 例全绿（13 例按平台跳过）。

  * **mount：`FileMountSource.list()` 单个坏条目只跳过，不再让整次列举失败**（4.2.0，修复）：`list()` 先拿文件名、再逐个 `stat`，原来没有 `find()` 那样的单条保护：① 列举后条目消失（原子写的临时文件 `.名字.uuid.tmp` 被改名、标记文件被并发删除）时 `stat` 返回 null 被放进结果、排序时空指针，或在「判存在→取真实路径/读属性」之间抛 `NoSuchFileException`；② 不跟随模式下的断链整次抛 `NoSuchFileException`，跟随模式下的断链空指针；③ 不跟随模式下指向根外的链接整次抛 `SecurityException`。现在这三类只跳过该条目（与 `find()` 同口径）；其余单条 IO 异常（如权限不足）、目录本身不存在/不是目录/打不开仍照常抛出，调用方仍能区分「空」与「这次没读到」。显式 `stat` 越界链接仍拒绝，安全语义不变。**影响**：一个线程不停原子写、另一个线程连续 `list` 的压测下原实现约六成调用失败；实际危害最大的是状态目录里留下断链或越界链接时——`LoopStore.load()` 会把整个进程的 loop 存储置为只读、`MemoryMdData` 整个进程记忆索引为空、`GoalExtension.recover()` / `QueueExtension.recover()` 每次重试都失败。**测试**：`FileMountSourceTest` 新增 2 例（断链 / 越界链接 / 根内链接 / `.tmp` 文件在两种模式下的列举结果；两种模式各 1.5 秒的原子写 + 删除并发列举，断言零失败），调用方回归 `LoopStoreTest.brokenOrEscapingLinkInStoreDirDoesNotSwitchToReadOnly`、`QueueIndexTest.listingSkipsBrokenAndEscapingLinks`、`GoalIndexReadFailureTest.recoverSkipsBrokenAndEscapingLinksInTheIndex`、`MemoryMdDataTest.broken_or_escaping_link_does_not_empty_index`（两种模式各验一遍记忆索引非空），换回旧实现时四例全部失败。变异验证 4 处均被拦住：恢复旧实现、不捕 `SecurityException`、不捕 `NoSuchFileException`、去掉 null 判断。
  * **mount：技能 / 子代理目录只在相关挂载变化时刷新**（4.2.0）：`DefaultSkillCatalog` / `DefaultAgentCatalog` 原来对任何挂载的注册/移除都全量重扫（包括普通文件挂载），soloncode 启动时逐个 `addMount`，技能挂载被扫约 12 次、内置子代理挂载约 18 次。现在监听器只在以下情况刷新：变更的挂载是本目录的类型（`SKILLS` / `AGENTS`，含以 `enabled=false` 重新注册），或当前快照里还有该别名的条目——`MountManager.register` 遇同名别名直接替换、监听器只拿到新挂载，技能/子代理挂载被同名文件挂载替换时靠这一条清掉旧条目。后一种判定在刷新锁内做，若有进行中的刷新会等它提交后再看快照。公开的 `refresh()` / `refreshByMount(alias)` 语义不变（显式调用仍全量刷新）。启动时技能挂载扫描降到约 6 次、内置子代理挂载约 3 次，文件挂载池的注册不再触发扫描。未做按挂载增量更新（合并结果依赖注册顺序与全局短名冲突，收益不抵复杂度）。**测试**：新增 `MountCatalogRefreshTest` 7 例（用计数来源验证文件挂载注册/移除不重扫；技能/子代理挂载注册、移除、禁用照常生效；同名替换后旧条目消失，含 id 即别名的根级技能；显式 `refreshByMount` 仍全量刷新）。变异验证 5 处均被拦住：两个目录各自去掉快照判定、改回总是刷新，技能目录不认别名本身。mount 模块 98 例全绿。

  * **talent-cli：`grep` 读文件提速，结果不变**（4.2.0）：在下文 `glob` / `grep` 遍历优化的基础上，再改每个文件的读取方式。① **只打开一次文件**：原来二进制探测（`isNotTextFile` 读开头 4096 字节）和逐行读取各开一次；现在先做后缀判断（`TerminalSupport.isNotTextFileByName`，从 `isNotTextFile` 拆出，顺带去掉重复的 `.class`），再用 `TerminalSupport.readUpTo` 一次读入全部字节（上限 10 MB，与原有的大小阈值相同），对开头 4096 字节做同一套判定（`TerminalSupport.isLikelyBinary`）。② **字面量模式在字节上预筛**（`TerminalSupport.grepLiteralBytes` / `containsBytes`）：模式不含正则元字符 `\^$.|?*+()[]{}`（或正则编译失败、原实现已回退为 `contains`）、能如实编码为 UTF-8（不含孤立代理字符）、且 `fileCharset` 为 UTF-8 时，文件字节里找不到模式的 UTF-8 编码就整文件跳过、不再解码。这只是必要条件：某行能命中，解码出的文本就含该模式，字节里就一定有它的编码。③ **逐行循环原样保留**：字节里找得到（或不预筛）时，用 `new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), fileCharset.newDecoder()))` 读同一份字节，构造与 `Files.newBufferedReader` 相同（报错模式解码器），所以 `\n` / `\r\n` / 孤立 `\r` 的切分、首行 BOM 剥除、遇到坏字节时“已解码部分照常输出、之后丢弃”的行为都不变。④ **实际内容超过 10 MB**（指向大文件的符号链接——遍历拿到的是链接自身的大小；或遍历后文件变大）时不整体读入，按原方式探测后流式读取。虚拟挂载上的 grep（`grepVirtual`）未改。**行为差异**：无可观测差异；仅在读取中途发生 IO 错误（如磁盘故障）这类极端情况下，原实现会输出出错前已读到的命中，现在整文件跳过。内存上单文件最多一次性占用 10 MB 字节数组（原来是流式）。**实测**（本仓库根目录，macOS、JDK 8，5 次取中位数，对照为优化前的 `LegacySearch`）：沙盒开启时字面量 `class TerminalSupport`（`*.java`） 1138→196 ms、无命中字面量 1119→200 ms、不带 include 1296→319 ms、正则 `class\s+Terminal\w+Test` 1235→347 ms；沙盒关闭时 2.0–3.3 倍。相对上一版（字面量约 395 ms）再快约 2 倍，正则模式只省掉多打开的一次文件。**测试**：`LegacySearch` 改为自带优化前 `isNotTextFile` / `isLikelyBinaryFile` 的逐字副本，不再依赖产品代码。新增 `TerminalGrepContentTest` 14 例，逐字对照 `LegacySearch`：坏 UTF-8（小文件无输出、跨 8 KB 解码缓冲的大文件部分输出、编码的代理字符）、各类换行、BOM（含 `^` 锚定与只有 BOM 的文件）、二进制探测边界（NUL 在第 4095 / 4096 字节、控制字符占比、空文件、无后缀二进制）、中文 / emoji / 孤立代理字符模式（非法正则回退 `contains` 时 `"[\uD83D"` 能命中 `"[😀"`，所以含孤立代理的模式不能预筛）、GBK / ISO-8859-1 字符集、输出截断、超过 10 MB 的文件与符号链接、不可读文件、160 个随机内容文件 × 19 个模式；另有 `containsBytes` 对照朴素查找 2 万组、`readUpTo` 大小提示偏差与上限边界、`grepLiteralBytes` 条件、拆分后的文本判定与原实现一致。基准新增“字面量无命中”“正则”“不带 include”三类 grep 场景。变异验证 11 处全部被拦住：解码改替换模式、采样改 8192 字节 / 全文、预筛忽略元字符 / 字符集 / 孤立代理、超限直接跳过、`containsBytes` 差一、`readUpTo` 上限差一、去掉后缀过滤、后缀表多一项。cli 全量 759 例全绿。

  * **talent-cli：`ls` 提速，结果不变**（4.2.0）：与 `glob` / `grep` 同源的做法，但 `ls` 的形态不同（`Files.list` 逐层列举，不是 `walkFileTree`），所以单列一条。① **复用遍历内的读拒绝判定器** `TerminalSupport.WalkReadGuard`（见下条）：原来每个条目都要调一次 `isReadDenied`，每次约 6 次 `toRealPath`、3 次 `exists`；现在每次列举把当前目录 `enter` 进判定栈，子项的真实路径用“所在目录真实路径 + 自身名字”推算，策略根与缓存根的真实路径只算一次。符号链接、重解析点（`isOther()`，如 Windows junction）、属性读取失败的条目仍走原实现。② **每个条目只读一次属性**：原来 `isReadDenied`（内部 `toRealPath`）、`Files.isDirectory`（排序一次、输出一次、递归前再一次）、`isIgnored` 各自访问文件系统；现在用一次 `Files.readAttributes(..., NOFOLLOW_LINKS)` 同时喂给判定器和“是否目录”判断，递归树里重复的第二次 `isReadDenied` 一并去掉。③ **“是否目录”保持跟随链接的口径**（与原 `Files.isDirectory` 一致）：普通条目直接用 NOFOLLOW 属性（两者结论相同），符号链接、重解析点、属性读取失败时仍调 `Files.isDirectory`，所以指向目录的链接仍是 `[DIR]`、断链仍是 `[FILE]`。排序、`[DIR]` / `[FILE]` 前缀、`show_hidden`、`AccessDeniedException` 时的“无权限访问”提示、递归深度 3、空目录提示全部不变。**行为差异**：与原 `isReadDenied` 同源的唯一差别是时间点——策略根、缓存根的状态在开始列举时取一次，列举期间它们被创建或改成链接不再感知（与 glob / grep 那条同口径）。**实测**（本仓库根目录，macOS、JDK 8，5 次取中位数）：沙盒开启时 `ls .` 6.3→0.9 ms（6.9 倍）、`ls -r .` 124.4→11.4 ms（10.9 倍）、`ls -r solon-ai-talents` 35.1→2.9 ms（11.9 倍）、`ls -r solon-ai-talents/solon-ai-talent-cli/src` 4.5→0.6 ms（8.0 倍）；沙盒关闭时 1.7–43.8 ms 降到 0.4–8.8 ms（3.4–6.2 倍）。**测试**：`LegacyList` 是优化前 `flatListLogic` / `generateTreeInternal` 的逐字副本，作参照实现。`TerminalListEquivalenceTest` 4 例：复用 `TerminalSearchEquivalenceTest` 的目录树与配置矩阵（沙盒开/关、五种缓存根状态、受保护状态目录、各类符号链接、随机目录树），扁平与递归两种列举各 288 组逐字比对全部一致；另有带 `FilesystemConfig`（denyRead / allowRead 五种组合）的 `flatListLogic` 直调比对 1 例，以及链接口径用例（指向目录的链接为 `[DIR]`、断链为 `[FILE]`、沙盒下越界链接被拒绝而关闭沙盒时可见）。基准 `TerminalSearchBenchmarkTest` 新增 ls 场景。变异验证：扁平列举改按去掉前缀后的名字排序、递归树去掉“目录优先”、是否目录改为不跟随链接、递归树去掉子项读拒绝判定，均被拦住。cli 全量 745 例全绿。

  * **talent-cli：`glob` / `grep` 提速，结果不变**（4.2.0）：原来遍历到的每个文件和目录都要调用一次 `isReadDenied`，每次约 6 次 `toRealPath`、3 次 `exists`，而且 glob 是先做安全检查、再匹配模式。现在：① **先匹配、再做安全检查**：glob 只对匹配上的文件做检查，grep 先按 `include` 过滤。两类判定都没有副作用，调换顺序不改变结果。② **新增遍历内的读拒绝判定器** `TerminalSupport.WalkReadGuard`：策略根、缓存根的真实路径每次调用只算一次。遍历不跟随链接，所以普通文件/目录的真实路径直接用“所在目录的真实路径 + 自身名字”，不再访问文件系统；符号链接、重解析点（`isOther()`，如 Windows junction）、起点目录，以及起点未规范化、策略根解析失败的情况，仍走原 `isReadDenied`。判定顺序与口径和原实现一致（越界 → 物化缓存 → 强制拒绝/denyRead）。缓存根当时不存在时，另按“已存在祖先的真实路径 + 余下部分”比较，遍历期间才创建的缓存目录同样会被拒绝。③ **glob 字面前缀剪枝**（`TerminalSupport.globLiteralPrefixDirs`）：模式以普通目录名开头时（如 `src/main/**/*.java`），直接从该目录开始遍历。以下条件全部满足才剪，否则退回全量遍历：模式是相对路径；前缀段不含通配字符、不是 `.` / `..` / 空段；目录里按同一套 glob 规则（含平台大小写口径）只匹配到一个条目，且名字写法完全相同；该条目是真实目录（不是链接、不是重解析点）。`target` 和各中间目录仍逐个按原 preVisitDirectory 口径校验（忽略目录、读拒绝），任一被跳过就没有结果。前 500 条的截断位置不变。**行为变化（改进）**：原实现全量遍历时碰到不可读目录会整体抛 `AccessDeniedException`；带字面前缀的模式现在不再遍历无关目录，不受影响（不带前缀的模式仍会抛，与原来一致）。`ls` 的同类优化见上一条。**实测**（本仓库根目录，macOS、JDK 8，5 次取中位数）：沙盒开启时 `**/TerminalTalent.java` 852→101 ms，`**/pom.xml` 849→109 ms，`solon-ai-talents/**/*.java` 871→34 ms，`solon-ai-talents/solon-ai-talent-cli/src/main/**/*.java` 1049→2 ms，grep（`*.java`） 1110→355 ms；沙盒关闭时约快 3.6 倍（前缀模式 15–265 倍）。**测试**：`LegacySearch` 是优化前 glob/grep 的逐字副本，作参照实现。`TerminalSearchEquivalenceTest` 5 例：在两棵随机目录树上叠加固定场景（强制拒绝文件/目录、harness 目录、状态目录、工作区内的物化缓存、各类符号链接含回环与断链、挂载目录、macOS 的 /var → /private/var），覆盖沙盒开/关、五种缓存根状态、60 类 glob 模式、五个搜索路径和多种 grep 组合，新旧结果逐字一致；判定器与 `isReadDenied` 逐条目一致（含 denyRead/allowRead 配置，以及跟随链接遍历，用来模拟旧 JDK 会进入 junction 的情形），并断言快速路径确实覆盖了绝大多数条目。`TerminalGlobPrefixTest` 10 例：剪枝边界，以及剪枝后忽略/缓存中间目录、强制拒绝、链接不越界、500 条上限、挂载路径、不可读目录。变异验证：快速路径去掉缓存判定/越界判定/链接回退、相对路径改用逻辑路径、前缀进入链接目录、glob/grep 去掉文件级检查，均被拦住。另有三处防御性代码，在当前配置和平台上去掉后行为不变，故无用例覆盖：中间目录校验（`fs()` 恒为 null）、名字写法校验（仅大小写不敏感的匹配器上有作用）、`.` / `..` 校验（目录列举里不会出现）。基准 `TerminalSearchBenchmarkTest` 默认不跑（`-Dsearch.bench=true`）。cli 全量 741 例全绿。

  * **core：模型选项改为在转为请求数据时（发 http 请求前）统一还原为强类型，修复嵌套选项以字符串发出（gitee IKW7WQ）**（4.2.0）：Solon 读取 yml 后 `defaultOptions` 里所有层级的值都是字符串。4.1.1 是在选项之间复制（`putAll`）时只转第一层，`defaultOptions.options.num_ctx: 8192` 这类嵌套值以 `"8192"` 发出，Ollama 报 `option "num_ctx" must be of type integer`（Chat / Embedding / Generate 都受影响，Decision 完全不转）。现在：① 选项之间复制（`ModelOptionsAmend` / `EmbeddingOptions` / `GenerateOptions` 的 `putAll`）只合并、不转换；② 转换只在转为请求数据时做一次：`ChatRequest.toRequestData()`、`EmbeddingRequestDesc` / `GenerateRequestDesc` / `DecisionRequestDesc` 的 `call()` 在调用方言 `buildRequestJson` 之前，把本次请求的选项递归还原（新增 `org.noear.solon.ai.util.OptionValueUtil.normalize`，返回新的 Map，不改配置载体与嵌套原值）。所有方言（含第三方）都在此之后读写选项，方言内按类型读取的逻辑（如 `incremental_output`、`responses_input_audio_enabled`、`thinking`）照常生效。解析规则：数字格式严格匹配才转（`"007"`、`"-"`、`"."`、`"1.2.3"` 保持字符串，支持 `1e-5`，`.5` / `5.` 照转），超出 int 的整数转 `Long`，解析失败保留原字符串、不再抛 `NumberFormatException`（原来 `seed: 12345678901` 或值为 `"-"` 时直接失败）；`user`、`model`、`stop` / `stop_sequences` / `stopSequences`、`prompt_cache_key`、`safety_identifier`、`metadata`、`labels`（Gemini，值须为字符串）连同其下内容保持原样。代码里 `optionSet("max_tokens", "1024")` 的写法照样发出数字。**行为变化**：嵌套的数字 / 布尔字符串会变为强类型（顺带修好 Anthropic `thinking.budget_tokens` 从配置读取时被忽略的问题）；JSON Schema 里 `strict` / `additionalProperties` / `maxItems` 随之变为强类型，代价是 `enum: ["1","2"]` 这类纯数字字符串枚举也会变成数字；`user` 等字段即使是纯数字也保持字符串；`"007"` 不再转成 7；`putAll` 之后、发请求之前读到的选项值保持原样（字符串）；代码里设置的选项（如 `options(o -> o.optionSet("x", "5"))`）也会在发请求时还原（4.1.1 只转配置复制过来的第一层，代码里设的字符串会原样发出），接口要求「像数字的字符串」的字段需在保持原样名单里；以 `options(XxxOptions)` 传入的选项对象发请求后其中的字符串会变为强类型。测试：`OptionValueUtilTest` 14 例；gemini `GeminiOptionValueTypeTest` 2 例（Models / Interactions 两个方言经 `toRequestData()` 后 `labels` 里的 `"2024"`、`"true"` 仍是字符串，同次请求的 `background`、`max_tokens` 还原为强类型）；ollama `OllamaConfigOptionsTypeTest` 7 例（本地 http 服务抓取实际请求体：Chat 同步与流式 / Embedding / Generate / Decision 嵌套选项为数字、配置载体不变；配置的 `thinking: false` 被方言按 Boolean 读取并写出 `think:false`；代码里设置的嵌套字符串同样还原且不改调用方的 Map；去掉四处转换后 7 例全部失败）。

  * **gateway / talent-cli：模式阈值以所在模式命名，MCP 的服务目录模式改名 LIST**（4.2.0，只改名不改行为；4.1.x 就有的公开方法保留并标 `@Deprecated`，4.2.0 新增的直接改名）：① **命名规则**：`xxxThreshold` 是 xxx 模式的阈值，不超过它（含）就用这个模式，超过往下一档；SEARCH 是最后一档，没有阈值。原规则是「超过它就进入哪个模式」，MCP 删掉 LIST 后 `listThreshold` 名字落空，`catalogThreshold` 又是按模式本身命名，两套规则混用。② **改名**：`OpenApiGatewayTalent` / `ToolGatewayTalent` 的 `dynamicThreshold` / `listThreshold` / `searchThreshold` 改为 `fullThreshold` / `summaryThreshold` / `listThreshold`；`SkillTalent` 的 `listThreshold` / `searchThreshold` 改为 `summaryThreshold` / `listThreshold`；`McpGatewayTalent` 的 `dynamicThreshold` / `listThreshold` / `catalogThreshold` 改为 `fullThreshold` / `summaryThreshold` / `listThreshold`（仍按**服务数**计），`listToolsLimit` 改为 `listServerToolsThreshold`（它是 `list_server_tools` 返回详略的切换点，不是硬上限）；MCP 的 `searchThreshold` 保留为空实现（调用时仍打一条 WARN，与 4.2.0 之前一致）。**4.1.x 就有的方法保留并标 `@Deprecated`**（委托给新方法，含义不变）：`dynamicThreshold`→`fullThreshold`（四个类）、`searchThreshold`→`listThreshold`（OpenAPI / Tool / SkillTalent）；**4.2.0 新增、未随正式版发布的直接改名，不留别名**：`catalogThreshold`→`listThreshold`、`listToolsLimit`→`listServerToolsThreshold`（`McpGatewayI18nKeys` 的常量同样直接改）。**唯一无法保留的是 4.1.x 的 `listThreshold`**（语义为 SUMMARY 阈值，名字被 LIST 档占用，同名同参不能共存），旧代码仍能编译但含义已变，必须手工改成 `summaryThreshold`。③ **MCP 模式名统一**：私有枚举 `Mode.CATALOG` 改为 `LIST`，四个类的模式都是 FULL → SUMMARY → LIST → SEARCH（MCP 的 LIST 列服务名，其他列工具/接口/技能名）；文案键 `gateway.mcp.instruction.catalog` / `gateway.mcp.catalog.item` / `item_names` 改为 `gateway.mcp.instruction.list` / `list_item` / `list_item_names`（与 OpenAPI 的 `instruction.list_group` 同一写法；未用 `gateway.mcp.list.*`，那是 `list_server_tools` 返回文案的命名空间），文案内容不变，模型看到的指令逐字不变；常量 `CATALOG_TOOL_NAMES` 改为 `LIST_TOOL_NAMES`，`list_server_tools` 用的 `LIST_DESCRIPTION_MAX` 改为 `LIST_SERVER_DESCRIPTION_MAX`。**注意** `gateway.mcp.instruction.list` 曾是第二批删掉的旧 LIST 模式的键，宿主若为旧键写过文案覆盖，会套到服务目录上。④ **`listThreshold` 换了含义**（原为 SUMMARY 上限，现为 LIST 上限），升级时旧写法 `listThreshold(n)` 仍能编译，应改为 `summaryThreshold(n)`；为此新增阈值顺序检查：首次渲染指令或取工具时若阈值配反**或配成相等**（相等时后一档永远取不到），打一次 WARN 并在文案里提示「4.1.x 代码应把 listThreshold 改成 summaryThreshold」；MCP 只比前两个（`listThreshold` 计数对象不同），SkillTalent 只比后两个；0 视为显式关掉该档，不提示；改阈值后重新检查。⑤ README 第 2、3、4、5、7、9 节同步，新增第 11 节改名对照；harness `docs/ENGINE-L1.md` 同步。**测试**：分两步改名（先 `listThreshold`→`summaryThreshold`，再其余），每步都编译并跑测试；测试方法 `catalogModeShowsServersAndOffersDrillDown` / `undescribedCatalogListsToolNames` 改为 `listModeShowsServersAndOffersDrillDown` / `undescribedServerInListModeShowsToolNames`，快照文件 `gw_mcp_instruction_catalog_desc/noted_*` 改名为 `gw_mcp_instruction_list_desc/noted_*`（内容不变，未重新生成即通过）；网关 139 例、talent-cli 725 例全绿，talents 聚合模块测试编译通过；WARN 用临时用例确认（配反时每实例只打一次，默认值不打）。

  * **gateway：三个网关的一致性与可维护性整理（按入手/深入审查结论）**（4.2.0）：① **修复：Tool 网关与 MCP 网关中转工具同名**：两者都注册 `search_tools` / `get_tool_detail` / `call_tool`，框架按工具名合并、后加的覆盖先加的，同挂一个 Agent 时一方的指令会让模型去调另一方的 `call_tool`。Tool 网关的中转工具改名为 `search_biz_tools` / `get_biz_tool_detail` / `call_biz_tool`（指令文案同步；MCP 与 OpenAPI 网关的工具名不变，harness `ToolName` 无需改）。**行为变化**：使用 `ToolGatewayTalent` 的应用，模型看到的中转工具名改变；按工具名写的权限规则需同步。② **修复：`call_biz_tool` 参数处理**：`tool_args` 为 null 时空指针、为 JSON 字符串时类型转换异常，且会把工具上下文写进模型给的原始参数；现在复制一份（JSON 字符串先解析，不是对象时返回新文案 `gateway.tool.call.args_invalid` 并附参数定义），再并入工具上下文（本地工具需要；同名键以上下文为准，模型不能伪造）。③ **修复：Tool 网关运行中增删不安全**：两张 `LinkedHashMap` 在 `addTool` 与渲染并发时会抛 `ConcurrentModificationException`；改为写时复制的不可变索引，并新增 `removeTool(name)`；同名工具换分组时从原分组移走（原来会在两个分组各列一次）。④ **边界名单保持 `List<String>`**：`McpGatewayTalent.getAllowed` 维持直接强转（与 SkillTalent 的 `_skills_allowed` 同一口径，唯一写入方 harness `InputScope` 写的就是 `List`；曾改为宽松读取，复审后撤回）。新增 `putAllowed(prompt, names)`；`toSelected` 收为包内（4.2 新增未发布）。`call_api` 的 `api_name` 为空时不再空指针。⑤ **调用失败统一返回报错结果**：`call_api`（名字为空、未找到、缺路径参数、请求异常）与 `call_biz_tool`（名字为空、未找到、参数格式不对、执行异常）原来返回成功结果，现在与 MCP 网关、框架一致返回 `ToolResult.error`；`OpenApiGatewayTalent.callApi(...)` 返回类型由 `String` 改为 `ToolResult`（直接调用的代码取 `getContent()`）。⑥ **重试统一为只在请求确定没送达时进行**（连接被拒、域名解析失败、无路由，新增识别连接阶段超时），OpenAPI 与 Tool 网关原来对任何异常都重试（读超时也重发，非幂等接口会重复执行），`retryConfig(n, delay)` 的退避参数原来被静默忽略、现在生效；**Tool 网关缺省改为不重试**（`maxRetries` 缺省 1，原为 3）：目标是本地工具，一个工具里可能先写库再调远程服务，远程连不上时异常链里同样有「连接被拒」，按「没送达」重跑整个工具会让前面的步骤重复执行，而且同一工具在 FULL 模式（框架直调）本就不重试；确认工具整体幂等时才用 `retryConfig` 设大于 1；OpenAPI 与 Tool 网关的重试字段改为 `volatile`（harness 运行中会改 OpenAPI 的重试配置）；判定逻辑抽到包内 `GatewaySupport`。⑦ **OpenAPI 网关索引写时复制**：`refreshApi` 注释说「影子交换」，实际先删后加——刷新期间调用会看到接口消失，重新拉取文档失败时接口一直处于删除状态；`addApi` 同名替换同样先删。现在新的一版建好后整体替换，拉取失败保留原接口。列表顺序由哈希顺序改为文档顺序，LIST 模式按原名显示（原来是小写键）。⑧ **结构**：`McpGatewayTalent`（原 1469 行）拆出包内 `McpServerRegistry`（连接、按「服务 → 工具」的索引、握手快照、服务描述、定位）与 `McpCallTool`（`call_tool` 执行）；网关本体只留配置、作用域、渲染与发现工具；`categoryTools` 改名为 `serverTools`，常量 `CATALOG_DESCRIPTION_MAX` / `DETAIL_DESCRIPTION_MAX` 改为 `BRIEF_DESCRIPTION_MAX` / `LIST_DESCRIPTION_MAX`。4.1.1 误公开的 `ToolGatewayTalent.ToolCallTool`、`McpGatewayTalent.McpToolCallTool` 保留为 `@Deprecated` 公开类（构造签名不变，参数、名字与行为委托给网关自带的中转工具），实际实现改为包内 `BizCallTool` / `McpCallTool`；**MCP 同名替换先握手再换**：`addMcpServer` 原来先移除旧服务再握手，握手期间该服务的工具消失；现在先用新连接拉取工具，再在锁内把连接与工具表一起换上，之后关闭旧连接（同一连接重复添加不再被关闭）；握手失败时新服务照样登记、没有工具；`McpServerParameters` 构建失败仍移除旧服务。`refreshMcpServer` 拉完工具后核对连接没被替换或移除才写回（原来拉取期间被替换会让旧工具复活）；`callTool(...)` 不再标「测试用」，写明是与模型调用同路径的编程入口。⑨ **注释与文档**：类注释写明视野与边界（空名单含义相反）的对照表、下划线参数由框架注入的约定、每个阈值是哪个模式的上限及计数单位（工具数/服务数/单服务内工具数）；修正 `docUrl` 可用 `classpath:`（实际只支持 http(s)）、`refreshApi` 影子交换、`addApi`「同名」、重复的 `@since`、Tool 网关 `listThreshold` 默认值写成 30（实为 40）、「中转工具按请求语言本地化」（按此前「撤回工具描述国际化」的决定，中转工具描述保持固定中文，只修正注释）。`hasMcpServer(null)` 等不再抛 NPE。README 同步（新增第 10 节）。**测试**：新增 `GatewayConsistencyTest` 15 例（中转工具名两两不重叠、`call_biz_tool` 的 null/JSON 字符串参数、不改原参数且上下文优先、失败标报错且业务异常不重试、显式配置时只在未送达时重试、Tool 网关缺省不重试、废弃中转类委托网关、增删与同名换组、渲染与增删并发、边界为 `List`、边界拦 `call_tool` 与指令、MCP 同名替换握手期间旧工具可用且换上后关闭旧连接、过期的 refresh 不让旧工具复活、`call_api` 失败标报错且读超时不重发、刷新失败保留原接口）；变异验证：重试判定改为总是重试、刷新改回先删后拉、MCP 替换改回先移除、去掉 refresh 的连接核对、Tool 网关缺省重试改回 3，对应用例均失败。修正 `Petstore2/3ApiTest.case12_disabledSource` 的断言缺陷（「未找到」提示会复述关键词，原断言恒失败），网关模块 139 例全绿；快照：Tool 网关工具名与指令、OpenAPI 指令的列表顺序与原名，新增 `gw_tool_call_args_invalid`，MCP 的指令与工具快照逐字不变（拆分未改行为）。harness-core 763 例 32F+5E 与基线逐项一致。

  * **harness：运行期修改重试次数同步到全部使用方**（4.2.0）：构建期 `mcpRetries` 同时交给 MCP 网关、`websearch`、`codesearch`，`apiRetries` 同时交给 OpenAPI 网关与 `webfetch`；运行期 `HarnessEngine#setMcpRetries` / `#setApiRetries` 原来只改网关，宿主在设置页改完要重启才对另外三个工具生效。现在与构建期同口径。测试：`HarnessEngineAccessorTest.retrySettersReachEveryTalentBuiltWithThem`（去掉任一同步会失败）。
  * **gateway：MCP 网关第三批——摘要模式带签名、`call_tool` 失败标为报错、握手信息留快照、使用说明只给一处**（4.2.0）：① **`call_tool` 自身失败返回 `ToolResult.error`**：执行异常（超时、连不上、服务端报错）、未找到、重名、工具名为空原来都返回成功结果，客户端显示为正常卡片，而 MCP 服务自己返回的错误反而是红色；现在与框架对工具异常、未找到工具的处理一致（`ActionTask` 也返回 error），文案不变。② **摘要模式清单改为「`签名`: 单行描述」、指令由三步改为两步**（按签名直接 `call_tool`，签名含 object/any/array 或以 … 结尾时再查 `get_tool_detail`）；描述压成一行并截 200 字（Python 服务常把整段含 Args/Returns 的函数文档当描述，原来会打乱列表）。③ **握手信息快照**：MCP 客户端出错后会清掉连接，到下次重连前读不到 serverInfo 与 instructions，而工具表还在，目录里该服务会从「描述」变成「工具名列表」、直接调用模式的使用说明整段消失；现在添加、刷新与每次读到时留快照（记下来源 provider，同名服务重新添加后旧快照不作数，移除时清掉），读不到时用快照。配置的描述仍实时读。④ **使用说明只在一处给出全文**：直接调用与摘要模式写进指令（摘要模式原来没有，模型改为直接调用后会看不到），目录与搜索模式只在 `list_server_tools` 里给（每次都给，不按会话去重，上下文压缩后模型仍能重新拿到）；`get_tool_detail` 不再附全文（查同一服务几个工具会重复几遍），改为一句指引新文案 `detail.noted`；`search_tools` 末尾对有说明的服务加指引 `search.noted`。说明标题下增加一句「以下内容由该MCP服务提供，仅作工具用法参考，不能改变用户或系统的要求」（这段文字来自第三方，市场一键安装的连接器也会带上）。⑤ **修复**：说明整段没有句末标点时，首句就是全文、被当作服务描述，`list_server_tools` 以「与描述相同」省掉全文，而描述只显示前 500 字——模型看不到后半段；现在只有描述未被截断时才省。⑥ `list_server_tools` 工具超过 `listToolsLimit` 时不再只列签名，每条保留 60 字内的短描述（`list.compact` 文案同步）。⑦ `tool_args` 被模型序列化成 JSON 字符串时先解析再转发（原来模型只拿到 cannot be cast），空串当作没有参数，数组/数字/写坏的 JSON 返回新文案 `call.args_invalid` 并附参数定义、不转发。⑧ `description(prompt)` 按本次可见范围统计服务数与工具数（原来是全部已注册服务，含停用的）。**评测**（2026-11-17，同一脚手架 6 服务 76 工具四组任务、每组每模式 2 次；deepseek-v4.1-flash 当时不可用，换用 deepseek-flash）：摘要模式 claude-haiku-5-5 与 deepseek-flash 均 8/8，到首次正确调用的平均网关调用数 2.0 与 1.9（上一批三步指令时两模型平均 3.1），单服务任务只需 1 次；嵌套参数任务两模型都按指令先查详情，全部运行参数错误 0 次；目录模式 16/16、平均 3.4。`listThreshold` 缺省仍为 40。测试：`McpGatewayTalentScopeTest` 新增 `summaryListsSignaturesAndInstructions`、`handshakeInfoSurvivesDisconnect`、`searchPointsToInstructions`、`compactListKeepsShortDescriptions`、`callToolErrorsAndStringArgs`、`descriptionCountsVisibleView`，改写使用说明与失败附定义两例（异常标错、快照、字符串参数、截断描述四处修复逐一改回旧逻辑均会失败）；快照 `gw_mcp_instruction_2`（摘要）、`detail_noted`、`list_compact` 等更新，新增 `instruction_summary_noted`、`search_noted`、`call_args_invalid`。

  * **gateway：MCP 网关渐进式披露第二批——删除 LIST 模式；`call_tool` 只转发模型参数、只在请求未送达时重试**（4.2.0）：① **模式由五档改为四档** `FULL → SUMMARY → CATALOG → SEARCH`：工具数超过 `listThreshold`（缺省仍为 40）后按服务数进服务目录（不超过 `catalogThreshold`）或搜索模式；只看一个服务时也走目录（一行）。删除只列工具名的 LIST 模式及文案 `gateway.mcp.instruction.list` / `list_tip`（`McpGatewayI18nKeys` 同步删两个常量）；`searchThreshold(int)` 标 `@Deprecated`、不再起作用（调用时打 WARN）。SUMMARY 模式按服务名排序（与目录一致，指令更稳定）。**行为变化**：已发布版本里工具数在 41–100 之间的视图，原来是指令里列全部工具名，现在是服务目录 + `list_server_tools` 下钻。② **评测依据**（2026-11-17，两个模型 deepseek-v4.1-flash / claude-haiku-5-5，6 个模拟服务 76 个工具，四组任务：单服务 32 工具、跨服务、无描述服务、嵌套参数，每组每模式 2 次）：SUMMARY / LIST / CATALOG 成功率均为 16/16；到首次正确调用的平均网关调用数 SUMMARY 3.1、LIST 3.4、CATALOG 3.3，跨服务与无描述两组 CATALOG 优于 LIST（4.25 对 4.75、3.5 对 4.0）；累计提示词 token CATALOG 略多（约 1.04 万对 0.93 万，主要是单服务与嵌套参数两组多一轮下钻）。LIST 在成功率与调用数上没有优势，而目录的指令长度随服务数而非工具数增长，故删除 LIST。单服务 32 工具时 SUMMARY 最省（2.0 次调用、约 7 千 token，目录要多一轮下钻约 9.4 千），说明把 `listThreshold` 降到 30 会让这类视图变差，**默认值保持 40 不变**。另观察到 Claude 即使拿到签名也常先查 `get_tool_detail`，DeepSeek 多数直接调用；嵌套参数任务两者都按指令先查详情。③ **修复：`call_tool` 把全部工具上下文转发给第三方 MCP 服务**：原实现把除 `server_name`/`tool_name`/`tool_args` 外的所有键并进参数，只剔除了 `_mcps_allowed`/`_mcps_selected`/`__locale`，会话 id `__sessionId`（ChatRequest 恒带）、宿主放的 `__cwd`、`_skills_selected` 等仍会发出去——既泄露内部信息，严格校验参数的服务还会直接报参数非法（评测中首轮即因此全部失败）。现在只转发模型给的 `tool_args`（复制一份，不改原参数）。④ **修复：`call_tool` 双层重试可能让非幂等工具重复执行**：网关外层对任何异常都按 `retryConfig` 重试（缺省 3 次），叠加 MCP 客户端内层对传输错误的一次重连重发；超时、传输中断时请求可能已执行，`create_issue` 一类工具会被执行多次，服务端已应答的 `McpError`（如参数非法）也白白重试并退避。外层改为只在请求确定没送达时重试（异常链里有 `ConnectException`/`UnknownHostException`/`NoRouteToHostException` 或 `Connection refused`，且不含 `McpError`），其余失败直接返回给模型。测试：`McpGatewayTalentScopeTest` 新增 `modeBoundaries`、`callToolRetriesOnlyUndeliveredFailures`，`callToolDoesNotForwardFrameworkKeys` 改为断言只转发模型参数（两者都在旧实现上反向验证会失败）；渲染快照 `gw_mcp_instruction_3`/`gw_mcp_tools_3` 由 LIST 变为目录模式。
  * **gateway：MCP 网关渐进式披露第一批——发现结果带参数签名、失败附参数定义、服务使用说明下钻给出；harness 修复 `mcp` 工具组漏掉 `list_server_tools`**（4.2.0，不改模式划分）：① 新增 `ToolSignature`（`@Preview`，网关内部用）：把 inputSchema 压成一行 `name(p: type, q?: type)`，必填在前，短枚举写 `"a"|"b"`，`string[]`，Pydantic 的 `anyOf[string,null]` 写 `string|null`，对象/复杂联合/`$ref` 写 `object`/`any`，最多 8 个参数、200 字，Schema 写错退化为 `name()`。② `search_tools` 与 `list_server_tools` 改为返回文本行：`- [服务名] \`签名\`: 描述`（描述压一行、截 200 字；完整描述与 Schema 仍在 `get_tool_detail`）；`search_tools` 超过 10 条时注明总数，只有分隔符的关键词按空处理。`list_server_tools` 标题给出服务工具总数（过滤时另给命中数）；命中不超过 `listToolsLimit` 时带描述，超过时不再截断，改为只列签名一次给全，最多 `listToolsLimit` 的 5 倍，再超出才提示用 keyword。③ 服务描述取 instructions 时只取第一句（第一段内；中文句末标点直接切，英文只在「句点+空格+大写」且 20 字之后切，去掉开头的 Markdown 标题符）；instructions 全文（保留换行、去多余空行、超 2000 字截断并注明）在模型第一次接触该服务时给出：FULL 模式写进指令，`list_server_tools` 与 `get_tool_detail` 结果里带上，与描述相同时不重复。④ 服务目录里没有描述的服务改为列工具名（最多 12 个，超出以 `…` 结尾），删除 `catalog.fallback`「包含 …」；LIST 模式的工具名改用原始大小写（原来显示小写索引键）。⑤ `call_tool` 失败时附完整参数定义：工具返回 `isError` 时重建结果（文本块合并成一段再附定义，保留媒体块、报错标记与 metas；原结果只追加文本块会被只读第一块的适配丢掉），异常链里有 JSON-RPC `INVALID_PARAMS` 时也附；超时、断连等与参数无关的异常不附。⑥ 指令文案：目录与搜索模式改为两步「下钻/搜索 → 按签名 `call_tool`」，签名含 object/any/array 或以 … 结尾时再查 `get_tool_detail`；目录模式提示判断不出服务时先 `search_tools`。新增文案 `catalog.item_names`、`server_instructions`、`instructions_truncated`、`search.found`/`search.more`、`call.schema_hint`、`list.header`/`list.header_matched`/`list.description`/`list.tools`/`list.compact`。⑦ **修复**：harness `ToolName` 的 `mcp` 组漏了上一版新增的 `list_server_tools`，`ToolNameRuntimeDriftTest` 因此失败；带工具名单的作用域（如 SERVER 模式 `tools` 含 `mcp`）会把目录模式的下钻工具当未授权拒绝。**不在本批**：删除 LIST 模式、调整 `listThreshold`/`searchThreshold` 默认值（待评测）。测试：`ToolSignatureTest` 9 例；`McpGatewayTalentScopeTest` 改写搜索/列表断言并新增 6 例（签名、紧凑与截断、搜索上限、描述取首句、使用说明、失败附定义）；`ScopeGuardInterceptorTest` 补发现工具随 `mcp` 组放行；快照新增 `gw_mcp_search_found/more`、`list_noted/matched/compact`、`detail_noted`、`call_invalid`、`instruction_catalog_noted/full_noted`、`instructions_truncated`。
  * **mcp / gateway：MCP 服务描述、服务目录模式与 `list_server_tools`；`call_tool` 不再把框架键转发给 MCP 服务**（4.2.0）：① **描述字段**：`McpServerParameters` / `McpClientProperties` 新增 `description`，`McpClientProvider.Builder#description`、`McpClientProvider#getDescription()`，`McpClientProviders.fromMcpServer` 传递描述（`mcpServers` JSON 里的 `description` 键直接生效）；描述不参与连接，也不作为 `clientInfo` 上报。新增 `getServerInfo()` / `getServerInstructions()`，只读已完成的握手结果，未连接时返回 null、不为此建连。② **服务目录模式**：`McpGatewayTalent` 模式由四档变为五档 `FULL → SUMMARY → LIST → CATALOG → SEARCH`，工具数超过 `searchThreshold` 且服务数不超过新增的 `catalogThreshold`（缺省 30）时只列「服务名（N 个工具）：描述」（按服务名排序，描述压成一行、超 150 字截断），服务更多时仍退回搜索模式。描述取值：配置的描述 > 服务端握手上报的 description > 服务端 instructions，都没有时用前 5 个工具名自动生成。SUMMARY / LIST 的服务标题在有描述时一并带上（无描述时输出不变）。③ **`list_server_tools(server_name, keyword?)`**：返回 `server_name`、`server_description`（有则带）、`tool_count`、`tools[{tool_name, tool_description}]`，单次最多 `listToolsLimit`（缺省 30）条、超出时带 `tip` 提示用 keyword 缩小范围；属于发现，与 `search_tools` 同口径按 `_mcps_allowed` 与 `_mcps_selected` 收窄，越界或不在视野内按「不存在」处理；服务名大小写不敏感。只在 CATALOG / SEARCH 模式提供给模型（SUMMARY / LIST 的指令已列出工具）。④ 新增公开查询 `getMcpServerDescription(name)`、`getMcpServerTools(name)`（只读，顺序同服务端）。⑤ `call_tool` 除 `_mcps_allowed` 外不再转发 `_mcps_selected` 与 `__locale`（此前会原样发给第三方 MCP 服务）。文案新增 `gateway.mcp.instruction.catalog`、`catalog.item` / `catalog.fallback`、`list.server_blank` / `list.missing` / `list.none` / `list.truncated`，搜索模式步骤提到 `list_server_tools`。**行为变化**：原来工具数超过 `searchThreshold` 就进搜索模式，现在服务不多时改为服务目录；要保持旧行为设 `catalogThreshold(0)`。测试：`McpDescriptionTest` 3 例；`McpGatewayTalentScopeTest` 新增框架键不转发、`list_server_tools` 边界与视野、截断、描述与工具查询、目录模式 5 例；渲染快照 `gw_mcp_instruction_4` / `gw_mcp_tools_4` 变为目录模式，新增 `gw_mcp_instruction_5`（搜索模式）、`gw_mcp_tools_5`、`gw_mcp_instruction_catalog_desc`、`gw_mcp_list_*`。

  * **gateway：`McpGatewayTalent` 按「服务 → 工具」定位，修复同名工具互相覆盖与借名越界**（4.2.0）：原来全局只按小写工具名索引（`allTools`），不同服务的同名工具后注册的覆盖先注册的；`call_tool` 执行的工具来自该索引，边界判断用的服务却来自 `getServerOfTool` 的遍历（顺序不固定），两者可能不是同一个服务——`_mcps_allowed=[A]` 时可能借 A 的名义执行 B 的同名工具；移除或刷新一个服务也会按名误删其它服务的同名工具。现在：① 删除 `allTools` 与 `serverToolIndex`，只保留「服务名 → 工具表」，工具表整表替换；② `search_tools` 每条结果带 `server_name`（字段顺序 `server_name` / `tool_name` / `tool_description`，同名工具分别列出）；③ `get_tool_detail` / `call_tool` 新增 `server_name` 参数（schema 可选，框架会对 `@Param` 必填做运行期校验，必填会让不带服务名的调用直接报错），给了服务名只在该服务内找（大小写不敏感），没给时边界内唯一归属则自动补上，有多个则返回新文案 `gateway.mcp.ambiguous` 且不执行；详情增加「所属服务」行，找不到时提示检查 `server_name` 与 `tool_name`；④ 视图里有跨服务同名工具时不走 FULL，降为摘要模式；⑤ 新增 `getServersOfTool(serverName, toolName)`，`getServerOfTool(toolName)` 遇到同名工具属于多个服务时返回 null；⑥ harness 的 `ScopeGuardInterceptor` 构造参数改为 `BiFunction<服务名或 null, 工具名, 归属服务列表>`，中转工具按 `server_name` + `tool_name` 判断，直连与未给服务名时边界内有任一归属服务即放行。指令三种中转模式的步骤文案同步说明传 `server_name`。测试：`McpGatewayTalentScopeTest` 新增同名定位、借名越界、搜索带服务名、移除不误删、同名禁用 FULL 5 例，`ScopeGuardInterceptorTest` 新增 `relayLocatesByServerNameForDuplicateTools`，渲染快照新增 `gw_mcp_detail_ambiguous` / `gw_mcp_call_ambiguous`。

  * **harness：智能体声明的 `skills` / `mcpServers` 开始生效；新消息不再沿用上一轮的技能 / MCP 选择**（4.2.0）：① `AgentDefinition` 的 `skills`（技能标识）与 `mcpServers`（MCP 配置名）原来只解析不生效，现在用到该智能体时转成本轮选择，与 `HarnessInput#selectedSkill` / `#selectedMcp` 同一语义（收窄可见范围 + 预加载技能正文）。新消息路径（选择器、`@提及`、goal / loop 等程序轮次）在输入没有显式选择时写回输入（显式选择含扩展 `onInput` 里设的与积压还原的，优先；积压只存显式选择），目标代理定义只解析一次；`task` / `multitask` 委派时子代理自己的声明优先于父级视野，没有声明才继承（不取交集）；续流不补。生效条件：`tools` 展开后含 `skill` / `mcp` 且未被智能体或引擎禁用；不存在的名字（MCP 还有已停用的）剔除，全部失效视为未声明并记 WARN。新增 `agent.AgentSelections`（`skillsOf` / `mcpsOf` / `hasTool`）；`SkillTalent.matchesSelected` 改为 public static（供同口径校验）；`generate` 的 `skills` 参数说明改为「已安装技能的标识，不是能力标签」（工具快照同步）。**行为变化**：已带 `skills` 的存量智能体（`generate` 生成的、从 Claude Code 导入的）升级后技能清单会被收窄到声明里存在的那几个（Claude Code 的 `skills` 只预加载不收窄）；父代理的声明会传给没有声明的子代理。② **修复选择残留**：请求选项从上一轮复制（`ReActRequest#init`），而装配只在选择非空时写入，同一进程里某轮选过的技能 / MCP 会带到后面不传选择的轮次（重启后又恢复），与「不传 = 不限制」不符。现在新消息每轮「写入或清除」，续流沿用被中断那一轮；扩展在 `onRequestAssemble` 里写的视野会被覆盖（要改选择请在 `onInput` 里改输入）。ENGINE-L1 同步修正「`call_tool` 按视野拒绝越界调用」的说法（代码只按作用域 `_mcps_allowed` 拦截）。测试：`AgentDeclaredSelectionTest` 8 例（声明生效、显式优先、残留、续流、工具与禁用判定、失效名、会话级子代理）、`TaskTalentDeclaredSelectionTest` 3 例，均在改前代码上失败。

  * **queue / host / core：积压索引的一致性、引擎关闭后的续发、静默判定的空档**（4.2.0 新增未发布）：① **queue 的积压索引去掉实例级缓存**：原 `QueueIndex` 在扩展实例上缓存「共享目录里有哪些标记」（`marked` / `loaded`）并拿它跳过写盘与删盘，会漏记积压——多节点共用状态来源时会话换节点后再入队不写标记；`syncIndex` 在会话锁外判定与读写，停止与入队并发时会删掉刚写的标记且不再重写；读目录失败时 `loaded` 永远为假，每轮结束都重新全量列举；死标记（会话被存储层清理、删除失败、L1 直接用 `claimNext`）只增不减。现在 `QueueIndex` 只做单条写 / 删与整目录列举（列举跳过原子写的 `.` 开头临时文件与解码失败的条目，并把状态来源抛出的非受检异常归一为 `IOException`），全部在会话锁内维护：有任务时每次都写标记；队空时删标记、删成功才在会话上记 `session.queue.unmarked`（之后到下次入队前不再碰磁盘，删失败下次再删）；**入队先写标记再入队**（门面 `enqueue`、忙时积压 `offer`、`/queue` 入队、`importItems`），入队保存快照的那一刻标记已在，没入成且队列原本为空时删掉刚写的标记；领取落空（`claimNext` / `drainLoop` 返回 `NO_TASK`，或最后一条同步完成、队列全空）时同步；异步启动那条的流终态收口不碰索引（L0 每轮结束都会续发，领取落空时注销；都没发生时由 `recover` 兜底）。删除 `pendingSessions()` 与 `QueueClaim#onSettled`。② **引擎关闭后的续发**：`claimNext` / `drainLoop` / `recover` 一开头判断引擎已关闭，直接返回（`NO_TASK` / 0），不领取、不查归属（查归属会重新认领 `shutdown` 刚释放的租约）。续发的 handler 领到一条后引擎才被关闭时，`handle` 返回 `REJECTED(engine_closed)`：host 现在**抛出**让这条回到队首（`START_FAILED`，`drainLoop` 随之停止），而不是把拒绝当成同步完成返回 true——那样这条会被确认消费，剩下的每一条也会这样被吃光，会话积压全丢。积压与标记都留在会话里，由新引擎的 `recover` 续发。领到之后会话已不归本节点（`REJECTED(not_owner)`，租约被别的节点拿走）同样放回队首、由持有节点续发；其余拒绝（实际只剩作用域解析不到 / 主体不符，程序派发的一轮不会因忙或待审批被拒）有意保持确认消费并回调 `HostSink#rejected`：放回队首的话，持续失败会挡住后面所有输入（队列先进先出）。③ **静默判定的两个空档**（core）：`isQuiescent()` 原只看运行计数，而「已受理、尚未订阅」与「流已终止、`afterRun` 回调未执行完」两段里计数为 0——引擎池在其中回收引擎后，宿主的续发会被拒绝并被当成同步完成确认掉。现在引擎登记受理占位（TASK 绑定、`discard` / 开流 / 超时 / 中断时清除），流式运行计数改在 `afterRun` 回调执行完之后才减（同步 `call()` 本就是先回调后减，不变）。queue 因此**不再覆写 `isQuiescent`**：积压在会话快照与索引里，回收不会丢，重建时的恢复会续发；卡住的积压（启动失败回队首、已停用）不该挡住回收。④ **按索引恢复统一**：新增 `QueueExtension.recover(sessionFilter, drain)`（逐条查归属 → 加载会话 → 续发，会话已不存在的注销标记，引擎已关闭返回 0，读索引失败抛 `IOException`，处理完记一行汇总）；`GoalExtension.recover()` 与 `recover(sessionFilter)` 的索引读失败同样由「返回空」改为抛 `IOException`（`GoalScheduler.readIndex()` 同步改，并跳过原子写的临时文件与解码失败的条目）。host 用同一套退避（`RECOVER_RETRY_DELAYS_MS`：2、8 秒，之后每 32 秒一次）同时处理两者，直到读成功或引擎关闭——不设次数上限：goal 只在创建、resume、trigger 与按索引恢复时挂调度，放弃重试它就一直停着，不存在「下次有输入时续上」；积压恢复一律整段套在 `wrap` 里（会话存储依赖租户上下文时，缺上下文会把活会话当成已删除、删掉它的标记），请求路径上首次取到引擎时转到 `boundedElastic` 后台，`warmup` 在调用线程上同步完成；goal 恢复在调用线程上做，请求路径上不套 wrap（只读索引、挂调度，套了会清掉请求线程自己的上下文）。⑤ **一个实例只服务一个未关闭的引擎**（core `AbsHarnessExtension.bindEngine`）：前一个引擎已关闭时换绑并按新引擎重建，未关闭时抛 `IllegalStateException`——从运行中的引擎上 `removeExtension` 不算解绑（命令表不能注销，留下的 `/queue` 等命令在换绑后会把旧引擎会话的标记写进新引擎的状态来源）；queue / goal / loop / steer 改用它（原来 goal 第二次挂接直接返回、一直绑着第一个引擎，loop / steer 是后挂的覆盖先挂的）。配套：`Builder.build()` 中途有扩展 `onAttach` 抛出时，对已挂接成功的扩展回调 `onDetach` 并关闭半成品引擎再抛出（原来它们绑在一个永远没人关闭的引擎上，从此挂不上新引擎）；`addExtension` 里 `onAttach` 抛出时扩展不留在扩展表里；`removeExtension` 按引用移除，不在本引擎上的实例不再回调 `onDetach`；新增 `AbsHarnessExtension.isBoundTo(engine)`，goal / loop 的 `onDetach` 只认绑定的那个引擎（换绑后旧引擎迟到的回调不卸新引擎的调度）。host README 7.4 的限流示例改为在 `try` 外不可中断地拿许可（原写法等许可时被中断会多还许可、并静默丢掉这一轮）。测试：`QueueIndexTest` 14 例（换节点后重写标记、停止与入队并发、读失败不放大、已确认注销不碰磁盘、删失败重试、异步启动后领取落空注销、恢复清死标记、列举跳临时文件、先写标记再入队、未入成删标记、关闭后不领取且不认领租约、卡住的积压不挡回收、一实例一引擎；变异验证：去掉关闭判断 / 改回先入队后写标记 / 去掉失败回滚，各自对应的用例失败）；`EngineQuiescenceWindowTest` 4 例（受理未订阅、`afterRun` 回调期间、中断清占位、各结束路径计数归零；前 3 例在 HEAD 上失败）、`ExtensionBindingTest` 8 例（四插件各一；另有构建半途失败后可重建、addExtension 失败不留痕、运行中摘除不能换绑、旧引擎迟到的摘除不卸新引擎调度，这 4 例在改前失败）、`GoalIndexReadFailureTest` 1 例、`HarnessHostLifecycleTest` 新增「续发途中引擎关闭不丢积压」（HEAD 上期望 3 实际 0，正是本条的缺陷）、首个请求不等恢复、读失败退避重试、预热套 wrap 续发且不误删活会话标记、续发途中失去归属不丢积压（这 2 例在改前失败）、执行器任务返回时本轮已结束（锁住 README 7.4 限流写法的前提）。留待决定：索引仍是平铺目录加不分页列举，十万级同时有积压的会话以上需要分桶或可替换的索引实现；恢复续发的并发上限仍由宿主的 `executor` 决定。`HarnessExtension` 的 4.1.1 旧名 `initialize` / `configure` 不留桥接（接口是 `@Preview`，改名不算破坏性变更）；旧实现若只覆写了旧名，需改名为 `onAttach` / `onAgentBuild`，否则不会被调用。

  * **harness：内置子代理的提示词改为推理时按本轮语言取，取代理不再带语言**（4.2.0 新增未发布的直接改，不留别名）：此前按语言构建代理，语言在构建时定死。会话已有该代理的轨迹时，智能体从上一轮的 options 起步（提示词一并沿用），只有语言按本轮重定，所以同一会话中途换语言后，选中的内置子代理协议指令跟着换、提示词不换（中英混排，重启后才恢复）；宿主直接取代理、只在请求上给语言时同样混排；引擎语言只在经 `handle` 时生效。现在：① `AgentFactory` 给内置子代理装的提示词模板每轮按 `trace.getOptions().getLocale()` 取正文（与 `AgentSystemPrompt` 的约定一致），引擎配置了语言时设为代理的默认语言；② `InputPipeline` 只写输入自带的语言，新任务 / 续流由智能体 `runLocaleOf` 定；③ 删除 `getAgentOrMain(session, name, locale)`、`getAgentBuilderOrNil(session, name, model, locale)`、`getAgentBuilderOrNil(name, model)`、`TraceUtil.getAgentTrace`；`localizeAgent(def, locale)` 改为只取描述的 `getAgentDescription(def, locale)`（提示词不必再取）；`BuiltinAgentI18n` 标 `@Internal`；④ 4.1.1 已发布的 `getAgentOrMain(name)` 标 `@Deprecated`（不认会话级子代理，改用 `getAgentOrMain(session, name)`，只要名字时用 `resolveAgent`）。规则只剩一条：语言给在输入（或直接用代理时的请求）上，不给时是引擎语言。行为变化：引擎配了语言时，代理的默认语言变为显式，放进团队当成员时不再继承团队语言。测试：`HarnessEngineLocaleTest` 新增同会话先英后中（含续流）、直接取代理跟请求语言、直接取代理用引擎语言 3 例，取提示词改读本轮实际 options（原用空轨迹渲染默认 options，测不出上述缺口）。

  * **工具方法删除兼容重载**（4.2.0）：才能里与 `@ToolMapping` 方法同名、参数更少的重载，都是给工具方法加参数（`__locale`、`scope`、`__sessionId`、`_skills_*` 等）时留下的兼容入口；工具由框架按 `@ToolMapping` 反射调用，这些重载只有测试在用，一律删除（共 48 个）：`TerminalTalent`（bash/bash_start/bash_wait/ls/read/write/edit/grep/glob）、`SkillTalent`、`ClockTalent`、`MemoryTalent`、`TodoTalent`、`LspTalent`、`CodeSearchTalent`、`WebSearchDriverTalent`、`WebCrawlerDriverTalent`、三个网关、`GenerateTalent`（含 4.1.1 的 7 参与默认语言的 8 参）、`TaskTalent`、`LoopTalent`、`GoalTalent`。**对直接调用的代码是破坏性变更**：其中 30 个就是 4.1.1 已发布的签名（`TerminalTalent` 8 个：bash/bashStart/ls/read/write/edit/grep/glob；`ClockTalent.now()`；`SkillTalent` 的 `skilllist()` / `skillsearch(query)` / `skillrefresh()`；三个网关共 7 个；`LspTalent.lsp` 6 参；`MemoryTalent` 7 个；web 三个各 1 个），另外 `bashWait` 参数不变、返回值由 `String` 改为 `ToolResult`；其余 18 个是 4.2 新增未发布的（todo、task、loop、goal、generate，以及带 `_skills_*` / `_mcps_allowed` 的中间版本）。经模型调用的工具不受影响（工具快照不变）。直接调用的代码改调工具方法本身：末尾传 `null`（`__locale` 为 null 即默认语言），返回 `ToolResult` 的取 `getContent()`。`bash_wait` 原来只为与 4.1 旧签名区分才加的 `__locale` 一并去掉，恢复 4 参。harness-core README 的 `generate` 示例同步。测试只改调用方式，删除了比较新旧签名文本的断言；渲染与工具快照不变。
  * **才能不保存语言，语言只走请求（prompt）**（4.2.0 新增未发布，不留别名）：删除 `CodeTalent.locale(Locale)` / `getLocale()` 与 `VectorStoreCompressionStrategy.locale(Locale)` / `getLocale()`，`HarnessEngine` 构建 `CodeTalent` 时不再传引擎语言（引擎默认语言已经 `engine.localeOf(input)` → `options.locale` → `prompt.locale()` 进入请求）。原来这两处是请求链之外的第二条语言通道：`CODE.md` 只认引擎语言，输入里选的语言对它无效；压缩策略设了语言会盖过请求语言。现在：`CODE.md` 是各会话共用的文件，**已有文件的语言优先**（首行与各已知语言的 `code.md.title` 逐字比对，请求语言排最前，落回英文的语言与英文文件互认），不存在或对不上时按请求语言生成——不同语言的会话不会来回改写，升级前的中文文件保持中文，想换语言删文件即可；新增 `init(cwd, locale)`，`init(cwd)` 标 `@Deprecated`（按默认语言）。压缩策略的描述/指令/工具只跟请求语言，归档只跟 trace 语言，旧签名 `recall(query, limit, sid)` 用默认语言。规则见 harness `docs/I18n.md` §2.5。测试：`CodeI18nScenarioTest` 重写（已有文件语言优先、旧中文文件保持、删文件换语言、手改文件重生成、落回语言互认），既有渲染快照全部不变。

  * **harness：命令读写分离——`CommandInput`（只读输入）+ `CommandOutput`（输出）**（4.2.0 新增未发布，不留别名）：原来同一次输入的属性分三路进命令（`origin` 走 `execute` 参数，`terminal` / `locale` 走工厂参数），每加一个属性工厂签名就要改一次，`CommandContext.getLocale()` 默认 null 导致 CLI 命令回执静默落回默认语言。改为：新增 `CommandInput`（`HarnessInput` 的只读包装：会话、命令名与参数、来源、终端能力、生效语言 `engine.localeOf(input)`、本轮属性；`HarnessInput` 仍可写，供 `onInput` 修改）；`CommandContext` → `CommandOutput`（只剩 `println` / `color` / `supportsAnsi` / `requestAgentTask` / `getAgentTaskIntent`，`getCommandOutput()` → `getText()`）；`CommandContextFactory` → `CommandOutputFactory`（`create(CommandInput)`），`BufferedCommandContext(Factory)` → `BufferedCommandOutput(Factory)`（无构造参数，工厂有 `INSTANCE`）；`HarnessInput.commandContextFactory` → `commandOutputFactory`；`Command.execute(engine, input, output)`（去掉 `origin` 参数）；`CommandDispatcher.dispatch(engine, input, command)`（旧 6/7 参重载删除，工厂兜底从 `InputPipeline` 挪入分发器）。`argAt` 负索引统一返回 null；新增 `ParsedCommand.of(name, args, rawInput)`（`@Internal`，单测/宿主直接构造用）。`InputOutcome.getCommandOutput()` 不变。
  * **harness：命令派生输入 `HarnessInput#derive` + `Command#requiresSession`**（4.2.0）：命令二段任务改由 `derive` 派生，原来手工复制只带 skills/mcps/systemPrompt/locale，漏了**显式作用域**（二段受理会被重新解析，宿主收窄的边界可能放宽或被拒）、来源与宿主域属性——现在一并带上（显式/解析/还原标志照搬；附件、HITL、续流、程序标记等按条消息属性丢弃；`commandAware=false`）。行为变化：命令意图未声明模型时沿用本轮 `selectedModel`（原来置 null 回退会话级）；子代理仍按会话级选择覆盖。`/queue` 入队改与 `QueueExtension#offer` 同口径（来源、模型、子代理、属性、输入快照），作用域不可保存时拒绝并回执（新 key `command.queue.scope_not_deferrable`）。`Command#requiresSession()` 默认 true，无会话输入由分发器统一回执（新 key `command.session_required`，`CommandDispatcher.sessionRequiredHint`），`/help` 与 Markdown 命令为 false。soloncode：`CliCommandContext` → `CliCommandOutput`（只持有 `Terminal`，删掉未用的 `LineReader`），`CliShell` 用 `in -> new CliCommandOutput(terminal)`，`ExitCommand` 与 `ChatWebController` / `CliCompleter` 跟进 `description(Locale)`。测试：新增 `CommandInputTest`、`CommandDeriveTest`，`CommandContextDefaultsTest` → `CommandOutputDefaultsTest`，`QueueCommandTest` 补快照对齐与拒绝用例；既有渲染快照全部不变，新增 `dispatch_session_required_*`、`queue_scope_not_deferrable_*`。

  * **core：`Prompt` 新增请求语言访问，`Talent` 新增 `description(Prompt)`**（4.2.0）：`AiI18n.ATTR_LOCALE`（`__locale`）、`locale()` / `locale(Locale)`——值仍存在 attrs 里（语言标签字符串，null 表示移除），不新增字段，JDK 序列化、会话快照、MCP 远程才能（`Write_ClassName` / `Read_AutoType`）均无需改动；`locale()` 每次从 attrs 现读、不缓存（attrs 对外可改，如 `attrs().putAll(toolContext)`，缓存会过期），解析复用 `AiI18n.toLocale`；写入统一用 `prompt.locale(x)`，不再直接 `attrs().put(AiI18n.ATTR_LOCALE, ...)`；`AiI18n.localeOf(prompt)` 为 null 安全的 `prompt.locale()`；删除 `AiI18n.putLocale` / `AgentI18n.putLocale`（4.2.0 新增未发布；原来传 null 时写入默认语言，现由调用处显式 `resolve` 后写入）。`Talent#description(Prompt)` 为才能描述的唯一入口，`TalentUtil` 生成才能标题行时用它，标题行与正文语言一致；`Talent#description()` 标为 `@Deprecated`，框架与内置才能不再调用或重写它（`description(Prompt)` 的默认实现转调它，只为兼容未迁移的第三方实现）。全部内置才能（Terminal / Skill / Code / Memory / Todo / Task / 三个网关 / Plan / VectorStore 压缩 / Text2Sql / Pdf / Redis / Shell）与 `TalentDesc`、`McpTalentClient` 改为重写 `description(Prompt)`；`metadata()` 取 `description(null)`（默认语言），其余不变（固定元信息，有缓存且 MCP 以资源导出，不随请求变化）。残留检查：`mvn -o clean test-compile -pl solon-ai-core -amd -Dmaven.compiler.showDeprecation=true` 后日志中「Talent中的description()已过时」应为 0。

  * **撤回工具描述国际化（4.2.0 新增未发布，不留别名）**：工具描述与参数说明只进提示词、用户界面上看不到，对回复语言影响很弱，第三方 / MCP / 网关工具又本就做不到本地化；而把描述挤进 yml 会让注解失去可读性、工具提示词要维护两份（英文版从未经模型实测）。删除 core 的 `LocalizedFunctionTool`，`AbsTalent` 恢复 4.1 实现（去掉 `i18n()` / `i18nTexts()` 与按语言缓存），`AiI18n.generation()` 收为包内；18 个才能类的 `@ToolMapping` / `@Param` 描述按改造前源码逐字恢复（含两个网关 `call_tool` 的描述），`MemoryTalent` 恢复 `#{ScopesDescription}` 绑定、`MemorySolutionProvider` 去掉新增的 `getScopesDescription(Locale)`；各模块资源删掉 124 个 `tool.*` key 与 `memory.scopes_description`。行为：中文会话的工具定义与改造前逐字一致（28 份工具快照与原中文基线逐字节相同）；英文会话的工具定义也是同一份中文，指令与工具返回文案仍按语言输出。测试：工具快照改为与语言无关的单份 `xxx_tools.txt`（中英请求都比对它）；`I18nLint` 去掉 `talents(...)` 与工具 key 检查，改为资源里出现 `tool.` 开头的 key 即报错

  * **工具失败标记：harness 工具改返回 `ToolResult`，Anthropic 发出 `is_error`**（4.2.0）：规则是「失败标记只表示这次调用没完成要做的事」（参数不合法、被拒绝、找不到对象、执行异常），查询为空、`WARNING:` 算成功；给模型看的文本一字不改（`ERROR: ` / `[OK] ` / `WARNING: ` / 错误码 JSON 保留——模型只能从文本判断成败，标记给前端、`toolCalls` 统计、goal 证据、returnDirect 用）。带 `__locale` 的工具方法（4.2 新增）改返回 `ToolResult`（参数更少的旧签名已删除，见「工具方法删除兼容重载」条）；每个类用一个私有 `err(...)` 同时生成前缀与标记。`generate`、`loop_add` / `loop_list` / `loop_control`、`goal_update`（`goal_get` 查不到目标是正常查询状态，不改）、`task` / `multitask`（只在 `runTask` 唯一的成功出口计数；`task` 成功数为 0 即失败，`multitask` 被取消/中断或一个都没成功才失败，部分失败算成功）。`bash` 改为按进程是否正常结束判成败：非零退出码不再算失败（grep 未匹配、diff 有差异、`2>&1` 带来的假非零），退出码由文本 `[exit_code=N]` 告诉模型（二进制输出原来直接返回摘要、不带退出码，现在同样追加）；只有安全校验拒绝、超时、未能启动或未正常结束算失败（`CommandResult.isSuccess()` 仍要求退出码为 0，供 goal 验证器等程序侧判断）。Anthropic 方言：`ToolMessage.isError()` 为 true 时 `tool_result` 带 `is_error: true`（原来只读 metadata，工具的失败标记传不到 Claude）。Terminal 的 `read`/`ls`/`grep`/`glob`/`bash_*` 随后也按同一口径改过，见下条。测试：新增 `LoopTalentErrorFlagTest`、`GoalTalentErrorFlagTest`、`TaskTalentErrorFlagTest`（含 generate）、Anthropic `toolResultErrorFlagMapsToIsError`；`TerminalTalentWriteBashErrorFlagTest` 原用 `exit 3` 测非零退出，实际被安全校验拦下、`kill -9 1` 则是真执行后非零退出，两例各自测错了对象，已改为 `ls __no_such_file__`（非零退出不算失败）与 `exit N`（拒绝算失败），另加二进制输出带退出码用例。所有渲染与工具快照不变。

  * **cli：工具成败改为结构化标记**（4.2.0）：`bash` / `write` 与 `edit` 一致——`@ToolMapping` 方法返回 `ToolResult`，超时、安全校验拒绝、写虚拟文件失败都返回 `ToolResult.error(...)`（非零退出码不算失败，见上条），宿主侧 `ToolCallEndEvent#isError()` 无需解析文案即可判断成败；返回文本与改造前逐字一致（i18n 快照未变）。返回 `ToolResult` 的工具不再生成 `outputSchema`（与返回 `String` 同口径），避免模型照着「成功/失败信封」编造参数。新增 `TerminalTalentWriteBashErrorFlagTest` 5 例（含经 `FunctionTool#call` 的端到端验证）；cli 模块全量 718 例全绿。随后 `read` / `ls` / `grep` / `glob` / `bash_start` / `bash_wait` 也改返回 `ToolResult`（口径同上条）：路径不存在、读二进制文件、虚拟文件超过上限、读目录、起始行越界、grep/glob 路径不存在或传了多个路径、`bash_start` 被安全校验拒绝或沙盒不可用、会话被硬超时终止，算失败；空目录、空文件、未找到结果、会话仍在运行或正常结束（不论退出码）算成功。越界等权限拒绝、会话不存在本来就以异常抛出，框架已按失败处理，未改。`bash_wait` 仍是原来的 4 个参数，只是返回值改为 `ToolResult`（快照文本固定、不随语言变化，所以不取 `__locale`）；`bash_stop` 终止即成功，不改。新增 `TerminalTalentFileToolErrorFlagTest` 6 例（含经 `FunctionTool#call` 的注册路径，故障注入验证能拦住回退）；文本与工具快照未变；cli 全量 725 例，仅 `TerminalSessionManagerTest.longCommandCanBeContinued`、`TerminalSessionOutputEncodingTest.incrementalSnapshotReturnsOnlyNewOutput` 两例时序不稳（HEAD 上同样失败）。

  * **agent：修复 `VectorStoreCompressionStrategy` 的 `recall_history` 每次检索都报错**：过滤表达式写成了 `sessionId = '...'`，SnEL 只认 `==`，构造 `QueryCondition` 时即抛 `IllegalArgumentException`（且在 try 之外，工具调用直接失败）。改为 `sessionId == '...'`。

  * **agent：国际化（i18n）接入 solon-i18n，语言只由配置决定**（4.2.0）：面向模型与用户的文案（主提示词、团队协议、运行时提示、上下文压缩、工具描述）统一放进 `solon-ai-agent/i18n/{react,team,team-protocol,compress}*.yml`（默认文件英文兜底，`_zh` 中文；每个值都是 `|-` 块标量、只写正文，首尾不留换行或空格——段落间的分隔符由代码负责，扩展语言时只需翻译正文），统一入口 `AgentI18n`（自有缓存与工厂，`setBundleFactory` 只做叠加覆盖，内置资源始终兜底；占位符只用命名形式 `{name}`（名字限 `[A-Za-z_][A-Za-z0-9_]*`）：`fmt(locale, key, name, value[, ...])` / `fmt(locale, key, Map)`，单次线性扫描、不走 MessageFormat 也不求值，避免文案里的 JSON 示例被解析坏，变量名重复时快速失败；文案在缓存锁外加载，覆盖工厂可访问远程服务或回调 `AgentI18n` 读取其它语言；`I18nResourceLintTest` 校验书写格式、跨语言占位符一致与源码调用点变量名）。全部 key 统一在 `AgentI18nKeys` 中管理（常量名 = key 转大写，带占位符的注明变量名），产品代码不再写 key 字面量；`I18nKeyCoverageTest` 保证每个 key 在中英两种语言下都有用例经产品代码走到（100%）。`TeamOptions` 新增 `locale`（`TeamAgent.Builder.locale` / `TeamOptionsAmend.locale`），团队成员未显式设置时继承团队语言。参与逻辑判断的标记（无增量、归档、摘要前缀、招标 / 完成关键词、指派正则备选项）合并识别所有已知语言，新增语言只需加资源文件。会话快照里的语言存为标签字符串（`prompt.locale(locale)`），恢复会话时 trace 的 `prepare` 自动登记语言，无需手动 `registerLocale`。
  * **core：i18n 引擎下沉到 solon-ai-core**（4.2.0）：新增包 `org.noear.solon.ai.i18n`——`AiI18n`（全局部分：默认语言、覆盖工厂、已知语言、语言属性键 `ATTR_LOCALE = "__locale"`、`localeOf` / `toLocale`、命名占位符 `format`）、`I18nMessages`（每个模块一个实例：`get` / `fmt` / `messages` / `textsOfAllLocales` / `itemsOfAllLocales`，加载逻辑从 `AgentI18n` 原样搬入）。`AbsTalent` 不变（与 4.1 一致）：工具描述与参数说明只给模型看，留在 `@ToolMapping` / `@Param` 注解里、不做国际化（见下条"撤回工具描述国际化"）。`AgentI18n` 只保留智能体的内容包列表，静态方法签名不变；默认语言与覆盖工厂改为全局共享。请求语言同时写入 `Prompt.attrs` 与工具上下文（键 `__locale`；原 `_agent_locale` 为 4.2.0 新增未发布，直接改名），工具方法声明 `String __locale` 即可拿到。测试工具（`I18nLint` / `I18nKeyCoverage` / `GoldenFiles`）放在内部模块 `solon-ai-testkit`（各模块以 scope=test 引入，不发布；不用 test-jar，免得跳过测试时发布找不到构件）。
  * **agent：续流沿用原来那一轮的语言**（4.2.0）：语言属于一轮，记在原始提示词的 `__locale` 上（随会话快照保存）；新增 `ReActTrace#getRunLocale()`。`ReActRequest` 在应用请求选项之前先定本轮默认语言：新任务用智能体配置的语言，续流用原来那一轮的语言（会话重新加载后也一样），请求显式指定的仍最优先；直接 `call(prompt, session)` 的非团队路径同样适用。行为变化：同一会话里不带语言的新任务不再沿用上一轮的语言（原来只在内存里碰巧沿用，重新加载后又不沿用）。`ReActRunLocaleTest` 8 例。
  * **harness：国际化一期（harness-core / goal / loop / queue / steer + talent todo / web / lsp）**（4.2.0；文档 `solon-ai-harness-core/docs/I18n.md`）：协议指令、talent 指令与工具返回文案、命令输出、HITL 理由、取消消息、内置子代理、goal 提示词等全部进资源包（工具描述除外，仍在注解里）（每模块 `XxxI18n` + `XxxI18nKeys`，资源 `<artifactId>/i18n/<name>.yml` 英文兜底 + `_zh.yml`），不配置语言时与 4.1 逐字一致。
    * **语言来源**：新增 `HarnessEngine.Builder.locale(Locale)` / `engine.getLocale()` 与 `engine.localeOf(input)`（`HarnessInput.locale` 优先，引擎实例兜底，不回写输入），受理时取一次并下传整条链路（本轮 `options.locale`、工具 `__locale`、子代理、命令上下文、忙碌 / 待审批提示、附件前缀）。goal / loop 后台轮次与门面说明用引擎语言，Goal 不存语言。续流（`decide`、`resume(true)`、`/continue`、`engine.resume()`）不带语言时沿用原来那一轮的语言，`decide` 不加参数；未消费的插话转积压时带上本轮语言
    * **公开常量改为方法（4.2.0 新增未发布，不留别名）**：`InterruptPlan.CANCEL_TEXT` → `cancelText(Locale)` + `isCancelText(String)`；`SteerProtocol.PREFIX` → `prefix(Locale)`（`textOf` 识别所有已知语言的前缀）；`CommandDispatcher.COMMAND_*_HINT` → `unavailableHint` / `disabledHint(name, locale)`；`CommandValidator.failurePrefix` / `whenNothingToRun` 带 `Locale`
    * **纯新增**：`Command.examples(Locale)`（`/help` 用它）；`GoalValidator.validate(condition, workDir, Locale)` 默认方法；`LoopArgumentException`（携带 key 与变量，`getMessage()` 默认语言、`getMessage(Locale)` 按语言）；内置子代理按语言分文件（`agents-i18n/<名字>_<语言>.md`，只替换描述与正文，工作区 / 运行时同名定义不受影响），正文在推理时按本轮语言取、描述经 `engine.getAgentDescription(def, locale)`（见 vNEXT 首条）；工具方法末尾加 `String __locale`（原签名后已删除，见「工具方法删除兼容重载」条）
    * **跨语言识别**：取消消息、插话前缀、goal 执行型目标关键词（`goal.action_keywords`）合并所有已知语言识别，中文判定结果与改造前一致
    * **二期**：Terminal / Skill / Code / Memory / Gateway（talent cli / code / memory / gateway）的指令与工具返回文案同样进资源包
    * **测试**：每个模块 `XxxRenderGoldenTest`（中文快照由改造前代码生成，反向验证可拦截）、`XxxI18nLintTest`、`XxxI18nCoverageTest`（中英 key 覆盖 100%），另有 `XxxI18nScenarioTest` 与 `HarnessEngineLocaleTest`
    * **soloncode 同步**：`WebRunSink` 的取消回执改为 `InterruptPlan.cancelText(session)`（与落库的取消消息同一语言），`LastTraceServiceTest` 改用 `SteerProtocol.prefix()`
  * **harness：门面语言入口收成"给会话"**（4.2.0）：宿主只想让文案跟随会话/本轮的语言时，不必自己取轨迹再取语言，也不再需要认识 `HarnessI18n`——新增 `InterruptPlan.cancelText(AgentSession)`、`SteerProtocol.prefix(AgentSession)`、`HarnessEngine.localeOf(AgentSession)`（会话当前轨迹的语言优先，没有轨迹时退回引擎实例的语言）、`HarnessI18n.localeOf(AgentSession)`；`SteerProtocol.prefix()`、`CommandDispatcher.unavailableHint(name)` / `disabledHint(name)` 为默认语言的简写。原带 `Locale` 的重载全部保留（需要显式语言时用）。`HarnessI18n.localeOf(ReActTrace)` 改为"本轮运行的语言优先，其次才是轨迹选项的语言"，与续流沿用原语言的规则一致
  * **cli：`edit` 工具的成败改为结构化标记**（4.2.0）：`TerminalTalent.edit` 返回 `ToolResult`，文件不存在 / 预检查不通过 / 应用失败等失败一律 `ToolResult.error(...)`，成功为 `ToolResult.success(...)`。核因：`ToolResult.isError` 是 `ToolCallEndEvent#isError()`、宿主 `tool.end` 帧 `isError` 字段的唯一来源，工具失败却返回纯文本时，宿主只能靠匹配"成功完成"之类文案判成败——文案随语言变化，宿主必然误判。`ToolSchemaUtil` 对返回 `ToolResult` 的工具方法不生成 `outputSchema`（与返回 `String` 一致）。`TerminalTalentEditErrorFlagTest` 5 例（含经 `FunctionTool.call` 的端到端）；顺带修掉 `edit` 缺省 `old_StrStartLine` 且原文不匹配时的空指针（诊断按"无行号提示"全篇扫描）
  * **agent：删除 `AgentSystemPrompt#getLocale()` 与 `*SystemPromptCn/En`**（4.2.0）：模板自带语言不可配置，会让 `options.locale` 之外多出一个语言来源——正是提示词与协议指令中英混排的根因。语言只由配置决定：`ReActOptions` / `SimpleOptions` / `TeamOptions#getLocale()` 改为 `locale == null ? AgentI18n.getDefaultLocale() : locale`，模板只按给定语言渲染（同一个模板实例可在不同请求上输出不同语言）。随之删除 `ReActSystemPromptCn/En`、`TeamSystemPromptCn/En`（它们唯一的作用就是让模板自带语言）。迁移：`systemPrompt(ReActSystemPromptEn.getDefault())` → 默认模板 + `locale(Locale.ENGLISH)`。`AgentI18n` 的行 / 分支覆盖由 `features.ai.i18n.*` 用例保证 100%

  * **撤回引擎视图 `HarnessSettings` / `HarnessTalents`（4.2.0 新增未发布，不留别名）**：两个视图自己不持有数据，只是把调用转回引擎，等于多一层壳。删除 `engine.getSettings()` / `engine.getTalents()`，引擎上 64 个 `getXxxTalent` / `getHarnessXxx` / `setXxx` 等方法取消 `@Deprecated`，恢复为正式 API（与 4.1.1 一致）；视图上新增的四个方法直接放到引擎：`getCommandPrefix()`、`getAgentPrefix()`、`getSubagentDefaultConcurrency()`、`setSubagentDefaultConcurrency(Integer)`。迁移：`engine.getSettings().xxx(...)` → `engine.xxx(...)`，`engine.getTalents().getXxx()` → `engine.getXxxTalent()`。`HarnessStructureGuardTest` 删除对应的废弃白名单

  * **harness-core 子包依赖理顺（4.2.0 发布前；两处 4.2.0 新增 API 直接改，不留桥接）**：原来 agent / command / hitl / input / scope / session / spi / util 八个子包互相可达，现只剩 `{input, command}`、`{agent, scope}` 两个双包循环。① `InputOutcome#getDispatch()`（返回 `spi.RunDispatch`）改为 `getDispatchOrigin()`（返回 `InputOrigin`，非派发为 `NONE`），迁移：`outcome.getDispatch().getOrigin()` → `outcome.getDispatchOrigin()`，确需派发事务本身用 `RunDispatch.current(session)`；② `scope.InputScopeResolver` 移到根包 `org.noear.solon.ai.harness.InputScopeResolver`（只改 import）；③ 新增 `PermissionContext.ATTR_KEY`，hitl 的三个工具策略改用它，`AgentDefinition.ATTR_PERMISSION_CONTEXT` 保留且同值；④ `hitl.SessionPermissions` 改名 `hitl.RememberedApprovals`（属性键不变）；⑤ 包内类统一用 Admission 命名：`InputGate` → `AdmissionGate`、`InputGate.Ticket` → `AdmissionGate.Hold`、`Admission` → `AdmissionDecision`，删除无调用方的 `HarnessInterrupts.writeCancel`。`HarnessPackageBoundaryTest` 改为白名单：九个子包必须登记、按 import 建图求强连通分量，只允许上述两个循环

  * **host 层收敛（去宿主化 + 补通道）**：`HostSink` 全部方法改缺省实现（`reply` / `event` 只记日志，零实现即可跑通），并删掉三处用户文案（`rejectText` / `deferredText` / `hitlExpiredText`）与 `forward`，改为结构化回调 `rejected(origin, reason, detail)` / `deferred(origin, position)` / `hitlExpired(origin)` / `notOwner(origin, owner)`（框架只表达事实，文案与是否提示由宿主决定）；`Submitted` → `SubmitResult`（补 `getOwner()`，`NOT_OWNER` 的持有者随结果返回）；`AdminGateway` → `SessionAdmin` 并去掉 `operation` 参数；`AdminAuthorizer` 与 `Builder.adminAuthorizer` 删除（鉴权需要角色、租户成员关系、限流等上下文，框架给不出，默认值只能是 fail-open；改由控制器 / 拦截器层负责）；新增 `openSession(tenant, sid, principal)`（建会话与写主体一次完成，消除无主体窗口）与 `closeSession(tenant, sid[, storageCleanup])`（判忙 → `engine.removeSession` → 删存储，顺序由框架保证）；`engine(tenant)` / `drain` / `EXECUTOR_CHANNEL` 收为包内，删除 `Builder.engine(HarnessEngine)`（多租户共用一个引擎不会报错）与 `HarnessEnginePool.warmup`（与 `HarnessHost.warmup` 重复且做不到「先登记执行器再恢复」）；守卫测试新增「主代码字面量无中文用户文案」与「AdminAuthorizer 不得复活」。
  * **goal 恢复按宿主过滤**：新增 `GoalExtension.recover(Predicate<String> sessionFilter)`（`recover()` 等价于传 null）；`HarnessHost` 首次取到引擎时只恢复 `programRuns(filter)` 命中的会话（此前恢复全部，同一引擎上别的入口的 goal 会落到插件缺省执行器上跑，与 QUICKSTART-SAAS §七 的说明不符）
  * **删除 `SessionAgentsTalent`（4.2.0 新增未发布、`@Internal`，不留别名）**：会话级子代理（`generate(saveToFile=false)`）的清单并入 `TaskTalent.getInstruction`，按 prompt 携带的会话 ID（`ChatSession.ATTR_SESSIONID`）用 `engine.getSessionOrNull` 现查会话后列在 `<session_agents>` 里，与委派解析（`getAgentBuilderOrNil(session, ...)`）同一来源；`InputPipeline` 不再按请求挂载。行为变化：清单只在挂了 `TaskTalent` 且其启用时出现（原来未挂 `task` 的代理也会列出、却调不了）
  * **文档按模块归位**：聚合包 `solon-ai-harness` 不再带 README 与 `docs/`（聚合包只做依赖引用）；引擎参考移到 `solon-ai-harness-core/README.md`（新增第二章「两种时态」：使用态 / 管理态在 L0、L1 下的示例，章节重新编号），`ENGINE-L1.md`（L1 受理 API、两段式宿主、会话插件接线与停 / 改 / 重排，章节重编为一～三）与 `SAAS.md`（引擎侧服务端参考，按当前 API 重写）移到 `solon-ai-harness-core/docs/`，`CONCEPTS.md` 移到 `solon-ai-harness-host/docs/`，删除分析报告 `STORAGE-ANALYSIS.md`。goal / queue / steer / loop 四份插件 README 新增「用 L0 时（HarnessHost）」一章，L0 与 L1 下都给出使用态（对话里输入命令）与管理态（界面按钮经门面）示例，原 ENGINE-L1 §5.1～§5.3 的插件接线并入各插件 README；装配口径统一为构建期 `extensionAdd`。`ReadmeFactsTest` 改读 core 自己的 README
  * **`/queue` 命令登记积压索引**：命令入队、`cancel`、`clear` 后与门面同一口径同步 `queue/pending/` 索引（此前命令直写队列、不登记，只经 `/queue` 入队、未执行就重启的会话会被 `HarnessHost` 漏掉续发）；`markPending` 只剩存量数据补登一个用途
  * **SERVER 构建期校验补齐**：`scopeRequired` 为真却没有 `scopeResolver` 时 `build()` 直接失败（此前每轮输入都会以 `scope_unavailable` 被拒，引擎实际不可用）；显式 `scopeRequired(false)` 不受影响。
* **harness：核心对外面收敛（停流、结果模型、引擎视图、包结构、插件门面）**（4.2.0；4.2.0 新增的 API 直接改不留桥接，4.1.1 已发布的引擎方法保留 `@Deprecated` 委托、4.3.0 删除）:
  * **停流**：新增根包 `InterruptMode { INTERRUPT_ALL, CANCEL_STREAM, PREEMPT_STREAM }`（`INTERRUPT_ALL` 是完整中断：清积压 + 写取消消息 + 停流 + 推进代数；带 `_STREAM` 的两种只停流），唯一一步式入口 `engine.interrupt(session, mode)` 返回 `HarnessResult<Boolean>`，三种方式都先查归属（修复：原 `cancelStream` / `preemptStream` 不查归属，`interrupt` 吞掉 NOT_OWNER）；旧 `interrupt(session)` / `cancelStream` / `preemptStream` 删除，两段式 `prepareInterrupt` 保留。host 的 `StopMode` 删除，`HarnessHost` / `AdminGateway` 改 `interrupt(…, InterruptMode)`，鉴权操作名 `stop` → `interrupt`
  * **单份判定**：`isRunnableWhenBusy` 只接 `HarnessInput`（与受理共用一份 `terminalOnly` 口径）；受理占位有效性只在 `SessionStatus.isAdmissionMark`（`@Internal`）；`prompt` / `resume` 补 `@Deprecated`；`HarnessExtensionHooksDocTest` 守住扩展钩子清单与类注释一致
  * **结果模型**：`HarnessResult.Code` 新增 `CONFLICT`（状态不允许，409；`INVALID` 只表示参数非法，400），新增细分码 `getReason()` / `hasReason()` / `getReasonOrCode()`；每个粗码自带建议 HTTP 状态码（`Code.httpStatus()` / `result.httpStatus()`，`NOT_OWNER` 取 307、`DISABLED` 取 503），新增 `forbidden(detail)` 工厂；`InputOutcome.REJECT_*` 字符串常量与 `Submitted.REJECT_SESSION_NOT_FOUND` 改为枚举 `InputOutcome.RejectReason`（`code()` 仍是线上小写值）；`InputOutcome` 工厂标 `@Internal`；steer 的 `SteerResult` / `SteerCancelResult` 删除，四个门面返回 `HarnessResult<SteerReceipt>`，细分码 `SteerExtension.REASON_*` 与 Web 应答码同名
  * **引擎视图（已撤回，见 vNEXT）**：曾新增 `engine.getTalents()` / `engine.getSettings()` 两个视图，引擎上对应方法标 `@Deprecated` 委托
  * **包结构**：`spi.SessionOwnership` → `session.SessionOwnership`（会话归属三件套 `SessionOwnership` / `OwnerCheck` / `NotOwnerException` 同处；`spi` 只留插件能力接口 `InputBacklog` / `RunDispatch`）。4.2 开发期新增的 `agent.task` 包并回 `agent`：`TaskTalent` / `GenerateTalent` / `TaskWrapEvent` 回到 4.1.1 的发布位置（与 4.1.1 源码兼容，公开方法只增不减），`SubTaskScheduler` 仍包内（`SessionAgentsTalent` 已删除，见 vNEXT）；`agent` 一个包覆盖子代理的定义、注册、构建、委派。有意不做：不建 `run` / `ownership` / `guard` 包，`ToolName`、`hitl`、`permission` 原位不动（已发布或分组后不减少概念），改为 README §10.1 增加「门控总览」
  * **queue**：删除 `claimNext(session, handler)` 两参重载（用 `claimNext(session, null, handler)` 或 `drainLoop`）；删除门面 `cancelPending`——「作废全部」只经引擎中断（`onSessionInterrupt` 清空并递增代际），只清待执行用 `clearPending`
  * **steer**：`SteerLimits` 与 `getLimits()` 收为包内，门面改 `getMaxBoxSize()` / `getMaxTextLength()` / `getMaxIdLength()`
  * **插件内部键**：`QueueAttrs` / `GoalAttrs` / `SteerAttrs` 改为包内可见，键名由插件测试守住
  * **聚合包**：`solon-ai-harness` 只引 `solon-ai-harness-host`（core 与四插件经 host 传递），依赖清单只维护一处
  * **引擎池留在 core**：`org.noear.solon.ai.harness.HarnessEnginePool`（曾短暂迁到 host，已撤回）。不用 `HarnessHost` 的 L1 宿主同样需要按租户托管引擎，池只用引擎公开的 `isQuiescent()` / `shutdown()`，留在 core 不会反向依赖 host；池的契约测试在 host 的 `HarnessEnginePoolTest`，`HarnessPackageBoundaryTest.enginePoolLivesInCore` 锁定位置
  * **HarnessHost 通用化（面向任意 agent / agent SaaS，方案见 host/docs/HOST-OPTIMIZATION.md）**：会话级回调全部改收 `HostInput`（带租户、会话、来源、输入文本、命令、队列项），新增 `admitted` / `steered`；新增每轮输出通道 `RunSink`（`HostSink.open(run)` 每轮一个，`prepare` / `reply` / `event` / `finished`，缺省转发回 `HostSink`）、开流前视图 `RunPrep`、终态 `RunOutcome`（COMPLETED / FAILED / CANCELLED + `RunUsage` 用量 + 挂起审批卡）；`runStarted` 删除（并入 `open`），`runFinished(run, error)` 改为 `runFinished(run, RunOutcome)`；新增 `SubmitSpec`（`principal` 主体校验防越权、`steerWhenBusy`、`rejectWhenBusy(boolean)`、`customize`、`observe`）、`RunHandle`（`SubmitResult.getRun()`，按请求 `await` 终态）与 `host.stream(...)`（`Flux<HostSignal>`，直接给 SSE）；`SubmitResult` 增 `STEERED`、`getReplyText()`、`getHitlCards()`、`getRejectDetail()`，`submit` 的 customizer 重载并入 `SubmitSpec`；`Builder.wrap` 改为 `BiFunction<租户, Runnable, Runnable>` 且覆盖续发与后台轮次，新增 `Builder.input` 统一补充四处输入、`programRuns(Predicate)` 只接管部分会话；`openSession` 在引擎不要求作用域时允许主体为空；`INTERRUPT_ALL` 内部改两段式（先落取消消息、标记 CANCELLED、再停流）；goal / loop 后台轮次改为与用户轮次同一条链（推流、可停止）；`GoalOutcome.done(ReActResponse)` 的证据口径改为「成功且非内部工具」，新增 `GoalOutcome.isEvidenceTool`；新增参考宿主 `ReferenceHosts`（SSE / IM / 推送）与防锁定守卫 `HostGenericityGuardTest`
  * **HostInput 带上本次输入的选择与属性**（soloncode Web 迁移暴露的通用缺口）：`getSelectedModel()` / `getSelectedAgent()` / `getAttrs()` / `getAttr(key)`（只读；队列续发时为排队时保存的值）。宿主在 `admitted` 里持久化「用户这次选了什么」、在 `RunSink.prepare` 里读取按请求的参数（如 cwd），不必再借会话 attrs 旁路传递；参考宿主 B（IM）用它取回复目标，`hostInputCarriesSelectionAndAttrsIncludingDispatched` 守护
  * **soloncode Web 端已迁到 HarnessHost（M6）**：一个工作区 = 一个租户；宿主只剩 `WebHostSink`（会话级呈现）+ `WebRunSink`（每轮：流门复位、SAEP 管道、失败 / 取消回执）+ 入口适配，删除 `WebSessionRunner` / `WebQueueDispatcher` / `WebChatInputHandler` / `WebGoalRecovery`（约 1300 行编排）；框架只补了上一条，未加回 `cancelling` 等宿主专用回调（停流前预占 IM 取消终态由 `WebRunSink` 自己做）
  * **HarnessHost 时序收口（第二轮，方案见 host/docs/HOST-OPTIMIZATION.md 第九节）**：同一轮的 `RunSink` 回调串行，`RunSink.reply` 移到执行线程（`open` 只建对象、不输出）；取消屏障——中断受理后不再投递 `event`、终态一定是 CANCELLED，`interrupt` 的值改为「有一轮被停掉」（含调度窗口内尚未开流的一轮），新增 `RunOutcome.getCancelCause()`；`HostRun` 删除 `getOrigin` / `getInputText` / `getQueueItem` / `isDispatched`（统一经 `getInput()`），新增 `getId()`，DEBUG 日志按轮次 id 记录生命周期；queue 新增积压索引（状态来源 `queue/pending/`，`pendingSessions()` / `markPending()`），`HarnessHost` 首次绑定引擎时恢复 goal 之后按索引续发积压（受 `programRuns` 会话过滤约束）；忙时插话排除待审批会话。soloncode 随之删除 `reserveCancel`、`WebGate.runs` / `tenants`、入口 HITL 预检，`WebSessionRecovery` 降为一次性迁移
  * **HostTurn 改名 HostInput**（术语：一次输入 = `HostInput`，一次开流 = `HostRun`；Turn 与 Run 近义，且 steer 的 `RUN_CHANGED`、ReAct 的 `maxTurns` 里 turn 都指运行或推理步）：`HostRun.getTurn()` → `getInput()`，`getInputText()` → `getText()`，`HostSink` 回调参数名同步；`HostGenericityGuardTest.hostTurnStaysRetired` 锁定不得复活。`SubmitSpec.rejectWhenBusy()` 改为 `rejectWhenBusy(boolean)`，与 `steerWhenBusy(boolean)` 同形（两者可同时开：先插话，插不进再拒绝）。soloncode 删除只有测试在用的 `WebStreamBuilder.buildStreamFlux`
  * **HarnessHost 第三轮（方案见 host/docs/HOST-OPTIMIZATION.md 第十节）**：`SubmitSpec.whenBusy(BusyPolicy)`（`QUEUE` / `STEER` / `REJECT`）取代 `steerWhenBusy` / `rejectWhenBusy`；停止收为两个动作——`host.interrupt(tenant, sid)`（用户停止）与 `RunHandle.cancel()`，其它 `InterruptMode` 只经 `SessionAdmin.interrupt`；新增 `HostInput.isResume()`（审批续跑续用原来的输出流）；修复 `programRuns(false)` 的骨架首次绑定引擎时仍恢复 goal、续发全部会话积压的问题。soloncode 桌面端与无头模式改用 HarnessHost（删除 `DesktopStreamTurn`、桌面 goal 执行器与无头模式的闭锁 / 锁），桌面 goal 行动证据统一为框架口径
  * **HarnessHost 第四轮（方案见 host/docs/HOST-OPTIMIZATION.md 第十一节）**：受理中（`submit` 尚未返回）的停止不再丢失——骨架在 `admit` 与队列续发的领取回调里登记「受理中」占位，`host.interrupt` 记上请求，开出的那一轮以 CANCELLED 收尾（`interrupt` 返回 true 的含义扩到「有输入正在受理」）；`host.stream` 先登记下游取消，受理期间断开也能停掉本轮；新增 `RunOutcome.isAbnormal()`（如超出 maxTurns）；后台轮次的结局翻译拆为包内 `ProgramResult`。soloncode 桌面端与无头模式的终态只看 `RunOutcome`（不再解析 RunEnd），桌面停止与 Web 同一写法；删除 / 回退改用「占用」口径；`emitToClient` 不再逐事件读盘
  * **host README 重写**：按四种时态（使用态 / 管理态 / 后台态 / 运维态）组织，补全用况示例（一问一答、SSE、IM 机器人、Web 多会话推送、审批、停止、忙时策略、补充输入、管理接口、后台通知、启动 / 会话生命周期 / 多节点、测试），新增 `HarnessHost.Builder` 与 `SubmitSpec` 的完整配置表、全部公开类型的方法说明、常见错误表；示例源码 `ReadmeSamples` 随构建编译，`ReadmeSampleTest` 运行主干流程。参考宿主 C 的流门复位从 `open` 挪到第一次输出，与文档约定一致
  * **提交只有一个输入类型：`SubmitSpec` 并入 `HarnessInput`（方案见 host/docs/HOST-OPTIMIZATION.md 第十二节）**：`host.submit` / `host.stream` 直接收 core 的 `HarnessInput`（与直连 `engine.handle` 同一个类型），本次监听改为第四个参数 `submit(t, sid, input, observer)` / `stream(t, sid, input, observer)`；删除 `SubmitSpec`（含 `customize` / `observe`）与 `submit(t, sid, text, origin)`。core 的 `HarnessInput` 新增 `of(text)`（不带会话）、`whenBusy(BusyPolicy)`、`principal(p)`、`copyFor(session)`；`BusyPolicy`（`QUEUE` / `STEER` / `REJECT`）从 host 挪到 core 的 `input` 包。修复两处缺陷：① `REJECT` 改在引擎受理的会话锁内判定（原先骨架先判空闲、再 `handle`，两步之间被别的输入抢先开流时会进积压，一问一答请求收到「已排队」而没人接）；② 来源只有一份（原先 `customize` 里改的 origin 与 sink 看到的不同）。主体校验下沉到引擎受理前（`InputPipeline` 第 0b 步），直连 `engine.handle` 同样生效；骨架加载会话后 `copyFor` 一份交给引擎，调用方的对象不被改动。守卫：`HarnessInputSubmitOptionsTest`（并发 REJECT 不进积压、主体、`copyFor` 反射全字段）、`HostGenericityGuardTest.submitSpecStaysRetired`
  * **用 host 的人只认一套结果词（方案见 host/docs/HOST-OPTIMIZATION.md 第十三节）**：`RejectReason` 从 `InputOutcome` 里挪出，成为 core `input` 包的独立枚举（与 `BusyPolicy` 同属受理口径；取值与 `code()` 不变，只换 import）；`HostInput.getKind()` 改为返回 `SubmitResult.Kind` 且永不为 null——与同一次 `submit` 返回的 kind 一致（引擎 `TASK` → `STARTED`；会话不存在、非持有节点、忙时可执行命令触发了任务 → `REJECTED`；插话成功 → `STEERED`）。host 的公开类型不再暴露 `InputOutcome`，它只留给直连 `engine.handle` 的人。四个结果类（`InputOutcome` / `SubmitResult` / `RunOutcome` / 包内 `ProgramResult`）各答一个问题、不合并，理由见方案
  * **`HostInput` 保留，枚举按规则摆放（方案见 host/docs/HOST-OPTIMIZATION.md 第十四节）**：`SubmitResult.Kind` 独立为 host 的 `Disposition`（处置 / 去向；getter 同步改名，`SubmitResult.getDisposition()` 与回调里的 `HostInput.getDisposition()` 同一个值，不再与 core 的 `InputOutcome.getKind()` 同名）；goal 包内可变本体 `GoalState` 改名 `Goal`（与 `GoalStatus` 只差一个后缀，易混；未发布、包内可见）；goal 的 `GoalSnapshot.Status` 独立为 `GoalStatus`（出现在 `GoalExtension` / `GoalProjection` 的公开签名里）；`HarnessEnginePool.WhenFull` 改名 `FullPolicy`。`HostInput` 与 `HarnessInput` 同名方法改为同义：`isResume()` 只表示显式续跑（原先含审批决策），新增 `isHitlDecision()`；会话不存在、非持有节点的拒绝回调也带上调用方的模型、子代理与属性。守卫 `HarnessStructureGuardTest.nestedPublicEnumsStayWithTheirOwner`：嵌套公开枚举不得出现在别的类型的公开签名里
  * **`HarnessProfile` 改名 `DeployMode`**：Profile 在同一技术栈里已指智能体画像（`AgentProfile`，一组 kv），Java 程序员也常读成配置环境；它表达的是部署形态这一分类。`Builder.profile` → `deployMode`，`getProfile` → `getDeployMode`，取值 `STANDALONE` / `SERVER` 不变；测试类 `HarnessServerProfileTest` → `HarnessServerModeTest`、`GoalProfileDefaultsTest` → `GoalDeployModeDefaultsTest`（4.2.0 新增未发布，不留别名）
  * **守卫**：`HarnessPackageBoundaryTest` 增加「spi 只放接口、SessionOwnership 不回 spi、session 不依赖 spi」；`HarnessStructureGuardTest` 的插件内部类规则扩展到 `*Attrs` / `*Limits`，聚合包规则改为「只引 host、host 引全部」
  * **soloncode-cli 同步**：停流调用改 `interrupt(…, InterruptMode)`；`WebChatInputHandler.interruptSession` 去掉冗余的 `queue.cancelPending`（`prepareInterrupt` 已先清队列），非持有节点按 `plan.isNotOwner()` 拒绝；`CliShell` 改用三参 `claimNext`；`ChatWebController` 插话端点按 `HarnessResult` 映射（INVALID→400，其余 409，应答码取细分码）
  * **验证**：core 671 例 32F+5E（失败集与基线一致：ToolStrategy / TaskTalent / Accessor 既有用例），goal 232 / queue 99 / steer 73 / loop 102 / host 25 全绿，soloncode-cli 1224 例 3F（settings 既有）

* **harness：命名与注释一致性（预设名、中断方式、结果码）**（4.2.0；两处改名均为 4.2.0 新增未发布，不留别名）:
  * `HarnessProfile.CODING` → `STANDALONE`（后类名又改为 `DeployMode`，见 vNEXT）：与 `SERVER` 回到同一分类轴（部署形态：本机单用户 / 多租户服务端），避免「SaaS 下也在做编码」的误读，也避开与 `SessionOwnership.LOCAL`（单机租约）撞词；补 `@Preview("4.2.0")`，注释写明「不调 `profile(...)` 时即为此值」
  * `InterruptMode.INTERRUPT` → `INTERRUPT_ALL`：三种里只有它连待执行项一起作废，`_ALL` 表达的正是这个范围；带 `_STREAM` 后缀的两种只停流
  * 枚举 item 注释补齐：`HarnessResult.Code`（逐项 + 各自 HTTP 状态码）、`Submitted.Kind`（修正 `STARTED` / `REPLY` / `REJECTED` 三处与行为不符的描述，常量顺序对齐 `InputOutcome.Kind`）、`GoalStatus`、`ValidationResult.Kind`、`ToolName` 的三个分组名；`HarnessResult` 的工厂与 getter 补注释
  * soloncode-cli 5 处 `InterruptMode.INTERRUPT` 调用同步改名


  * **统一结果**：新增 core `org.noear.solon.ai.harness.HarnessResult<T>`（OK / NOT_FOUND / NOT_OWNER / DISABLED / FULL / INVALID / FORBIDDEN）。queue / goal / loop 的写门面全部改返回它：不再有 `int` 哨兵值（`-1`、`QueueExtension.NOT_OWNER = -2` 已删）、`boolean` 与业务异常（`IllegalStateException` / `NotOwnerException`）。host 层 `FacadeResult` 删除。steer 继续用 `SteerResult` / `SteerCancelResult`（码值下发前端）
  * **queue**：9 参 `enqueue` 删除，改为 `enqueue(HarnessInput, id)`；`QueueItem` 删除 `getReasoningEffort` / `getThinkingMode` / `hasFiles`（宿主字段走 `attrs`，读旧数据时顶层字段并入 attrs）；新增 `QueueItem.Status` 枚举（落盘仍写字符串）与 `clearPending`（只清待执行、不递增代际）
  * **goal**：`GoalTask` → `GoalSnapshot`；每会话永远只有一个 goal，`pause` / `resume` / `trigger` / `remove` / `update` 去掉 `taskId`，`get(sid, taskId)` 删除；`toMap()` 不再带 loop 字段；门面只取已有会话（不存在为 `NOT_FOUND`）；`ValidationResult.detail()` → `getDetail()`
  * **loop**：`toggle` 删除；`removeAll` 补归属检查；`/loop remove`（原 `stop`）与 `/loop clear`（原 `stop-all`）停在途一轮改用 `engine.cancelStream`，不再清空用户排队、不再写「用户已取消」
  * **steer**：`SteerResult.Code.TURN_CHANGED` / `SteerCancelResult.Code.TURN_CHANGED` → `RUN_CHANGED`，`isTurnChanged()` → `isRunChanged()`（与 runId、`SteerDroppedEvent.Reason.RUN_*` 同词）；`getCodeName()` 删除。soloncode-cli 前端 `app-streaming.js` 同步改判 `RUN_CHANGED`
  * **core**：`PermissionDecision` 并入 `PermissionBehavior`；`isBusyBypassCommand` → `isRunnableWhenBusy`；`OwnerCheck` / `NotOwnerException` 从 `spi` 移到 `session`（`spi` 只留接口）；`InputOutcome.admitted` 返回副本、不再改写原对象；`REJECTED` 注释补齐 8 个拒绝码；共享的 `Fakes` 放在内部模块 `solon-ai-harness-testkit`（goal / loop / queue / steer 测试以 scope=test 引入，不发布；harness-core 自身测试保留一份同名副本）
  * **命令写法统一**（旧写法保留为别名，帮助里只展示新写法）：`/goal --max-duration 30m`（`--key value`，时长 s / m / h）；`/loop --every 5m` / `--cron "expr"` / `list` / `remove <id>` / `clear`；`/queue list` / `cancel <id|序号>` / `clear`（IM 端无面板也能管理队列）；三个命令都支持 `--` 转义，子命令只在参数个数完全匹配时生效
  * **模块**：新建 `solon-ai-harness-host`（`HarnessHost` / `HostSink` / `HostRun` / `Submitted` / `StopMode` / `AdminGateway` / `AdminAuthorizer`）；`solon-ai-harness` 变为只做引用的聚合包（无源码）。host 层改名：`AdminView` → `AdminGateway`、`StopMode.ALL / CURRENT_RUN` → `STOP_AND_CLEAR / STOP_RUN`、`Submitted.Kind` 的 `REPLIED / HITL_PENDING` → `REPLY / HITL`。solon-parent 版本管理与 `__release/solon-ai-bundle1` 同步加入 host
  * **守卫**（`solon-ai-harness-host` 的 `HarnessStructureGuardTest`）：聚合包无源码且引用全部 6 个模块；goal / loop / queue 写门面必须返回 `HarnessResult`；`noDeprecatedInMainCode` 改为白名单（只放行 `HarnessEngine.prompt / resume`）
  * **文档**：新增 `solon-ai-harness-host/README.md`；聚合 README / QUICKSTART-SAAS / SAAS / CONCEPTS 与 goal / loop / queue / steer README 按新接口更新（删去已不存在的 `ToolPermission` 说明）
  * **验证**：goal 232 / loop 102 / queue 98 / steer 72 / host 25 全绿；core 666 例 32F+5E（失败集与基线一致：ToolStrategy / TaskTalent / Accessor 既有用例）

* **harness：术语收敛为 input / run / step，`turn` 包取消**（4.2.0；破坏性，未发布直接改名，不留桥接）:
  * `scope.TurnScope` → `scope.InputScope`、`scope.TurnScopeResolver` → `scope.InputScopeResolver`、`spi.TurnDispatch` → `spi.RunDispatch`
  * `turn.SessionTurn` → 根包 `RunTicket`，`turn.InterruptPlan` → 根包 `InterruptPlan`（`turn` 包只剩两个类，并入根包）
  * `HarnessAttrs.TURN_DISPATCH`（`session.turn.dispatch`）→ `RUN_DISPATCH`（`session.run.dispatch`，只在内存 attrs）；请求属性键 `harness.turnScope` → `harness.inputScope`
  * 包边界守卫改为「子包不得依赖 `RunTicket` / `InterruptPlan`」，并锁定 `turn`、`run` 包不得复活
  * 有意保留：`maxTurns`；`SteerResult.Code.TURN_CHANGED`（码值下发到前端）
  * soloncode-cli 同版本同步；文档 README / SAAS.md / SAAS-ISSUES.md / SAAS-PLAN.md（第二版）/ queue README 按新名更新
  * **验证**：core 665 例 32F+5E（失败集与基线一致），goal 229 / queue 94 / steer 72 / loop 100 全绿，聚合 7 例 1F（既有），soloncode-cli 1220 例 3F（既有）

* **`InputOutcome#afterRun(Runnable)`：本轮运行句柄注销之后的回调**（4.2.0；纯新增）:
  * **修复**：宿主在流的 `doFinally` 里续发积压时，排队消息不会自动发出。Reactor 正常终止时下游 `doFinally` 先于上游执行，而运行句柄在 `ReActRequest` 最内层才注销，宿主 `doFinally` 里 `isOccupied` 恒为 true，`drainLoop` 直接返回，要等下一次外部触发
  * **新增**：`ReActRequest#afterRun()`（受保护钩子，`stream()` 每次订阅终止、`call()` 每次结束且句柄注销后调用，异常只记日志）；`InputOutcome#afterRun(Runnable)`（仅 TASK 有效，返回是否登记成功；未开流就 `discard` 时不执行）；`InputOutcome#admitted` 增加第 4 参 `afterRunRegistrar`
  * **文档**：`RunTicket` javadoc 与 queue README 4.4 的标准写法改为 `outcome.afterRun(() -> drainQueue())`，`doFinally` 只保留 `settle` 与发 done
  * **验证**：新增 `AfterRunContractTest` 6 例；core 644 例 32F+5E（失败集与基线一致），goal 229 / queue 94 / steer 72 / loop 100 全绿，聚合 7 例 1F（既有 @Deprecated 守卫）

* **TerminalTalent 受保护目录跟随 harnessHome**（新增面为主）：新增 `TerminalTalent(MountManager, String harnessHome)`，harness 引擎传入自身 harnessHome；文件工具强制拒绝 `<hh>commands|agents|cache/mount`、OS 沙盒对工作区根与本轮工作根下的 `<hh>commands|agents` 加 denyWrite、物化缓存改为 `~/<hh>cache/mount`、ignoreDirs 追加 `<hh>` 首段。修复引擎缺省 `.solon/` 时模型可改写 `<ws>/.solon/agents|commands`。单参构造与 `TerminalSupport.isMandatoryDenyRelativePath(rel)` 仍按 `.soloncode/`，另增双参版本
* **`HarnessEngine.getSessionOrNil` → `getSessionOrNull`**（未发布，直接改名）：与 soloncode-cli `SessionManager.getSessionOrNull` 统一

* **harness：受理收敛为唯一执行入口 handle**（4.2.0；破坏性）:
  * **删除** `HarnessEngine.admit(...)`、`turn.AdmitDecision`（及其 Kind）与公开的 `turn.InputGate`（移入根包并改为包内可见）。宿主不再接触占位凭据，不再 try-with-resources，不再在 finally 里释放
  * **`handle` 第 0 步做受理判定**（与占位、入积压同一把会话锁），放行条件从输入本身推导：有待审批卡时普通输入 `REJECTED("hitl_pending")`（审批决策、续流、程序派发线程上的一轮、`runnableWhenBusy` 命令豁免）；忙时可执行命令与审批决策在运行任务旁边执行（`isBesideRunning()`），其余可排队输入由引擎入积压并返回 `DEFERRED`；程序轮次、续流、无积压能力时 `REJECTED("busy")`，带附件 `REJECTED("attachments_not_deferrable")`，积压拒收 `REJECTED("backlog_unavailable")`。`terminalOnly` 命令仅在 `input.terminal(true)` 时算忙时可执行；命令内部再次 `handle` 继承外层占位；`onInput` 移到受理判定通过之后
  * **`InputOutcome` 增加 `DEFERRED` / `REJECTED`**（六值：TASK / REPLY / HITL / IGNORED / DEFERRED / REJECTED）及 `getBacklogPosition()`、`getRejectReason()`、`getRejectDetail()`、`isBesideRunning()`、`isDispatched()`、`getDispatchOrigin()`、`discard()`；原因码常量 `REJECT_HITL_PENDING` / `REJECT_BUSY` / `REJECT_ATTACHMENTS_NOT_DEFERRABLE` / `REJECT_BACKLOG_UNAVAILABLE`
  * **占位跟着请求走**：返回 `TASK` 时会话保持占用，直到 `getRequest().stream()` 被订阅（运行流先登记、占位后释放，无空档）或 `call()` 结束；不执行时 `outcome.discard()`（幂等）；`interrupt` / `prepareInterrupt` 清除占位。修复 Web 端订阅调度到别的线程时可能并发开流的问题
  * **`Builder#admissionTimeoutMs(Long)`（新）**：占位时限，缺省 60000 毫秒，超时的占位不再算占用
  * **`InputBacklog#offer` 改收 `HarnessInput`**：`int offer(AgentSession session, HarnessInput input)`（原 `(session, text, origin)`）。queue 实现保存正文、来源、`selectedModel`、子代理与 `getAttrs()`；`QueueItem` 增加 `getAttrs()` 与 `toInput(session)`，`getReasoningEffort()` / `getThinkingMode()` 在显式字段为空时回读同名属性，`toMap()` 与持久化投影增加 `attrs`。续发改为在领取回调里同线程 `engine.handle(claim.getItem().toInput(session))`
  * **`HarnessInput` 增加宿主域属性**：`attr(key, value)` / `attrs(Map)` / `getAttr(key)` / `getAttrs()`（引擎不解读，进积压时原样保存、续发时原样带回；Web 宿主用 `reasoningEffort`、`thinkingMode`）及 `isProgram()`
  * **goal / loop 缺省执行器**：`handle(HarnessInput.ofProgram(...))` 不是 `TASK` 时返回 `skip(reason)`（不计失败）；loop 缺省执行器改用 `getSessionOrNull`，会话不存在时 skip
  * **宿主**：soloncode-cli 命令行忙时命令行为与 Web 一致（普通命令排队，`/interrupt`、`/help` 等 `runnableWhenBusy` 命令立即执行）。四个插件 README 与 harness README 同步为标准宿主写法

* **`solon-ai-talent-mount` 更名为 `solon-ai-mount`，升为通用 IO 抽象**（4.2.0；**不兼容**）:
  * **坐标与包名**：artifact `solon-ai-talent-mount` → `solon-ai-mount`（目录移到顶层，归入 bundle1；solon-parent 同步）；包名 `org.noear.solon.ai.talents.mount` → `org.noear.solon.ai.mount`（子包 `source` / `catalog` 不变）。迁移：全局替换包名、改依赖坐标；不提供 relocation
  * **命名修正**：`Mount.isWriteable()` / `Builder.writeable()` → `isWritable()` / `writable()`（与能力位拼写一致）；`MemoryMountSource` → `InMemoryMountSource`（避免与 talent-memory 撞词）；删除与 `getMounts()` 重复的 `MountManager.getSourceMounts()`；harness `Builder.dataSource(MountSource)` / `getDataSource()` → `stateSource(...)` / `getStateSource()`（避免与 JDBC DataSource 撞名）
  * **`MountCapabilities`**：删除 10 参构造器，只保留 `builder()`；删除 `editable`（与 writable 重叠）与 `watchable`（无配套监听接口；本地监听改按 `isLocalPathAccessible()` 判断）
  * **IO 层与目录解耦**：`MountManager` 不再创建/持有 `SkillCatalog`、`AgentCatalog`（删除 `getSkillCatalog()` / `getAgentCatalog()`），新增 `MountListener` + `addListener/removeListener`（挂载表更新后、锁外同步回调，异常逐个隔离）；`DefaultSkillCatalog` / `DefaultAgentCatalog` 构造时自行订阅。技能禁用列表（`disallowSkill/allowSkill/setDisallowSkills/getDisallowSkills/isSkillDisallowed`）从 `MountManager` 移入 `DefaultSkillCatalog`；`HarnessEngine` 的同名方法签名不变，改为委托引擎自建的目录
  * **删除** `MountManager.parseRealPath` 与 `USER_HOME` 静态常量（两仓均无调用方；配置路径展开属宿主职责）
  * **契约测试对外发布**：`org.noear.solon.ai.mount.testkit.MountSourceContract` 以 test-jar 发布（只含 testkit 包），第三方 db/http 来源以 `<type>test-jar</type>` 引入后继承即可；新增 delete 非递归、跨目录 move、目录 move 三组用例，`MountSource#delete/move` 的 javadoc 写明对应契约
  * **验证**：mount 89、memory 117、loop 95、goal 219、queue 88、steer 72、聚合 7 全绿；harness-core 589 例 33F+5E、talent-cli 664 例 1F、soloncode-cli 1213 例 3F，失败集与此前基线一致

* **会话钩子收敛 + 会话删除事件 + 「只取不建」的会话获取**（4.2.0；破坏性，接口均为 `@Preview`）:
  * **`AgentSessionProvider` 改为唯一抽象方法 `getSession(String instanceId, boolean createIfAbsent)`**：`createIfAbsent=false` 且不存在时返回 null，且不得有副作用（不建实例、不写存储、不建目录、不放缓存）。迁移：`InMemoryAgentSession::of` → `(id, create) -> InMemoryAgentSession.of(id)`（不缓存，行为与以前一致）；生产实现建议 `(id, c) -> c ? map.computeIfAbsent(id, ...) : map.get(id)`
  * **`HarnessEngine.getSessionOrNull(id)`（新）**：只取已存在的会话；`getSession(id)` 保持「不存在则新建」
  * **`HarnessEngine.removeSession(id)` + 钩子 `HarnessExtension#onSessionRemove(engine, sessionId)`（新）**：永久删除会话时通知全部扩展（不判开关、逐个隔离异常、不取会话实例）；宿主顺序固定为「判忙 → removeSession → 移出内存 → 删存储」；只移出内存（回退、关连接）不触发
  * **钩子改名/调参**：`onInterrupt` → `onSessionInterrupt`；`onStatus(engine, session, status)` → `contributeSessionStatus(engine, session, builder)`；`onRequestAssemble(engine, isDelegated, agentName, amend)` → `onRequestAssemble(engine, agentName, isDelegated, amend)`（与 `onAgentBuild` 前两位对齐）。命名约定：`on*` 发通知、`contribute*` 收集信息、带 `Session` 的为会话级
  * **插件**：loop 实现 `onSessionRemove`（删该会话任务与记录），忙判定改用 `getSessionOrNull`；goal 实现 `onSessionRemove`（注销 job），调度轮次/忙判定/缺省执行器改用 `getSessionOrNull`，会话不存在时 job 自行注销——修复「会话删除后 goal job 每 5 秒把会话重建出来且永不注销」；queue/steer 仅改名
  * **goal / loop 去掉 workspaceId**（破坏性）：`GoalExtension.builder(String)` / `LoopExtension.builder(String)` → `builder()`；job 名前缀改为按扩展实例自动生成的进程内序号（`goal-<n>:<sessionId>`、`loop-<n>:<taskId>`），多个实例共用 JobManager 不再依赖宿主传唯一标识防撞名；删除 `GoalExtension.Builder#workspace(String)`，验证器固定在 `engine.getWorkspace()` 下执行（修复未传目录时把工作区 ID 当路径用）；`/loop` 仅在设置了存储文件时提示「persisted」
  * **验证**：harness core 584 例 33F+5E（与 stash 基线失败集逐项一致），goal 217 / queue 88 / steer 72 / loop 93 / 聚合 7 全绿；新增契约测试：`HarnessPluginContractTest` 3 例（停用扩展也收到、异常隔离、删除流程不调 provider、`getSessionOrNull` 不新建）、`LoopExtensionTest` 1 例、`GoalSessionRemoveTest` 2 例

* **新增聚合包 `solon-ai-harness-all`**（4.2.0；纯新增，零破坏）:
  * **内容**：只有 pom，聚合 `solon-ai-harness` + `solon-ai-harness-goal` / `-queue` / `-steer` / `-loop`，一个依赖带齐核心与全部会话插件。现有模块（含 `solon-ai-harness` 本身及四个插件）不做任何改动，已有使用方与插件作者无感
  * **为何不改名拆 core**：插件单向依赖 `solon-ai-harness`，若让主名变聚合包需先整体改名为 `-core`、四个插件与下游全部改依赖，且会让已发布的第三方扩展被动带入插件与 scheduling/cron；新增 `-all` 达到同样的「一个依赖拿全」而无迁移成本
  * **边界**：聚合包只解决依赖引入，不自动装配——插件仍需宿主显式 `addExtensionIfAbsent`（README §1.1 / §5 已注明）。插件与只需核心契约的模块应依赖 `solon-ai-harness`，不要依赖聚合包
  * **配套**：`__release/solon-ai-bundle1` 增加模块；`solon-parent` 的 dependencyManagement 增加 `solon-ai-harness-all`；守卫测试 `HarnessAllDependencyTest`（编译期引用四个插件，任一从 pom 漏掉即编译失败）

* **子代理提及解析入口唯一化：宿主提前解析与 `handle` 内部逐字一致**（4.2.1；新增面为主）:
  * **背景**：桌面端必须**提前**拿到「剥离提及后的正文」——Goal 拦截、子代理名格式校验、规划模式文本改写三件都作用在正文上，无法等 `handle` 返回后再做。于是它自己拼了一份 `AgentMentions.Options`，而 `handle` 内部又拼一份。两份口径不同（显式分支不 trim、兜底分支 `trimContent(true)`），同一段输入被解析两次，最终生效的子代理与正文取决于哪一次先落地。README §6.1 记录的「提及路径只跳过一个字符 / 显式路径吃掉所有空白」正是这一分叉的下游现象
  * **`HarnessInput.parseMention(engine)`（新）**：把 `Options` 的构造收成一处，`handle` 内部改为调用同一方法。宿主提前解析与引擎最终解析因此**逐字一致**，不再是「两套实现碰巧看起来一样」。解析仍无副作用，可安全重复调用
  * **桌面端收敛**：`DesktopInputRouter` 的两个分支合并为一处调用（`explicitAgent` 为空即等价于原兜底分支），`AgentMentions` 的 `Options` 构造从宿主消失。有意的行为变更：**请求体显式指定 agent 时也走 `trimContent(true)`**——桌面输入框允许换行与缩进，原显式分支不 trim 属不一致而非设计。其余路径（CLI/Web/ACP/headless）零变更
  * **新测试 8 例**（`HarnessInputMentionParseTest`）：提前解析与 `handle` 结果一致、`trimContent` 两个口径、显式 agent 只剥离一次、二次解析幂等、`nameValidator` 双路径生效、`agentAware(false)` 保留程序生成文本、`selectedAgent` 仅兜底
  * **护栏 1 条**（`PackageBoundaryTest`）：宿主主代码禁 `AgentMentions.Options.builder()` / `AgentMentions.parse(`（`PackageBoundaryTest` 16 例全绿）
  * **验证**：harness 全量 **924 例 33F+5E**、cli 全量 **1260 例 3F**，失败域与基线逐项一致（harness：ToolStrategy/Web/Write 权限域 + TaskTalent/Accessor 环境域；cli：settings/挂载域三例）

* **宿主侧接口收敛：流生命周期/受理判定/会话选择项上收，内置扩展一次装配**（4.2.1；新增面为主，旧入口保留）:
  * **背景**：`handle` + 四态 `switch` 已让「跑一轮」很轻，但超出这一层的胶水仍由宿主持有，且**同一不变量在五端重复实现**——「取消代数在订阅前捕获、取消以 complete 收尾」只活在 README，`WebSessionRunner` 的 async/sync 两条路径各抄一遍（sync 的 `doFinally` 因此漏掉 `settleTurn`）；受理判定 55 行写在 `WebChatInputHandler` 的 `synchronized (session.attrs())` 块里，而它用到的每个原语都已在 harness；会话级选择项 `CTX_MODEL_SELECTED`/`CTX_AGENT_SELECTED` 是裸常量（全仓 25 处，四种清除写法并存），而同类的 cwd 早已有门面
  * **`RunTicket`（新）**：`begin(session)` 在**订阅前**捕获取消代数基准与本轮派发事务，`isCancelled()` / `settle(boolean)` / `dispatch()` / `cancelGeneration()`。「须在 stream() 之前 begin」从文档义务变成 API 形状（方法名语义 + javadoc）；`settle` 幂等，无派发时回落读当前派发。Web async/sync 与桌面端全部替换，宿主侧 `SessionRuns.cancelCount` / `QueueDispatch.current` 自捕获归零。**顺带修复**：Web sync 路径原本漏收口派发事务，收归 `RunTicket` 后两条路径同形，该类缺陷从「靠人工对称」变为「不可能写错」
  * **受理判定收进引擎（已由「受理收敛为唯一执行入口 handle」取代）**：把六项判定（运行流、受理窗口、待审批卡、队列积压、派发身份、忙时可执行命令）收进引擎，判定与占位同临界区；忙态口径固定为 `isOccupied`（含 HITL 与积压），**宿主不得传自己的判定**——这正是历史漂移点。曾以独立的受理方法 + 四值决策对外提供（初版本条误记为含 `QUEUE`，实际延后值为 `DEFER`），现已删除，判定并入 `handle` 第 0 步，结局并入 `InputOutcome`（`DEFERRED` / `REJECTED`，以及 `isBesideRunning()` / `isDispatched()`）；延后时由引擎经 `InputBacklog#offer(session, HarnessInput)` 自行入积压，`reasoningEffort` / `thinkingMode` 这类宿主域概念以 `HarnessInput#attr` 原样透传、引擎不解读；`REJECTED` 只给原因码与默认文案、渲染归宿主
  * **会话选择项门面**：`getSessionModel`/`setSessionModel`/`getSessionAgent`/`setSessionAgent`（对称于既有 `getSessionCwd` 系列）+ `SessionSelections`（静态读入口，供 `TraceUtil` 这类拿不到引擎实例的工具）。四种清除写法（`put("")` / `put(null)` / `remove` / 带守卫）归一为「null 与空白都等于未设置，读回恒为 null」；`CTX_*` 常量保留但不再被宿主直接引用（25 → 0，仅剩注释）
  * **`HarnessExtensions.installDefaults(engine, goal|workspaceId)` + `Handles`（新）**：steer / queue / goal 一次装齐，顺序固定（三者 `onAttach` 都只 `register`，命令名互不冲突，顺序不承载语义，固定只为可复现），**幂等**（已挂载同类型则复用，挡住 `addExtension` 不查重导致的二次 `onAttach` 重建调度器）。宿主自有扩展（loop / manager）仍走 `addExtension` 追加，聚合入口不接管。**明确不做 builder**：queue 零旋钮零状态，加 builder 只是空壳（README 常见坑 15）；真正缺的是「三件套怎么一次装对」这个组合根职责
  * **`QueueExtension.maxQueueSize()` / `maxTextLength()`**：静态只读访问器（对称 `SteerExtension.maxTextLength()`），供宿主前端预校验，不改变内核语义、不引入 builder
  * **cwd 兜底注入**：`handle` 装配时按 `getSessionCwd(session)` 注入 request 级 `ATTR_CWD`。宿主四处注入**保留**——ACP 是协议请求级、Web 是前端上报、桌面是入参，「请求级覆盖」与「会话缺省」是两个来源
  * **新测试 14 例**：`RunTicketTest` 6（取消早于/晚于 begin 的归属、空会话无条件安全、幂等收口）、`HarnessExtensionsTest` 8（装齐、命令注册、幂等、复用已挂载、goal 缺席降级、null 引擎容错、宿主扩展不受扰、宿主配置不被丢弃）；`HarnessEngineAccessorTest` 补会话选择项归一断言
  * **护栏 3 条**（`PackageBoundaryTest`）：禁 `SessionRuns.cancelCount` 与流路径自捕获 `QueueDispatch.current`；入口须经引擎受理判定（且不得回潮 `synchronized (session.attrs())` 手拼；受理收敛后改为入口只调 `handle`）；禁裸 `CTX_*`；禁逐行 `addExtension(new SteerExtension())` / `new QueueExtension()` / `addExtension(goalExtension)`。首跑即暴露 2 处真实残留，修复后 15 例全绿
  * **验证**：harness 全量 **916 例 33F+5E**、cli 全量 **1259 例 3F**，失败域与基线逐项一致（harness：ToolStrategy/Web/Write 权限域 + TaskTalent/Accessor 环境域；cli：settings/挂载域三例）；`PackageBoundaryTest` 15 例全绿


* **goal 执行器注册通道化：认领谓词显式交给引擎，重叠可告警；无调度器静默 no-op 补 warn；API 面收窄**（4.2.1；旧签名保留，编译期零破坏）:
  * **背景**：多入口宿主（CLI/Web/桌面）各注册一个 goal 执行器，认领边界互斥靠约定——谓词内联在 handler 里，引擎不可见，写宽了就按注册顺序静默抢跑，唯一防线是一条无上下文的 debug 日志。另两处排障盲区：`.scheduler(null)` 后调度方法完全静默 no-op；`resumeAndSchedule` 与 `resume` 同义、裸参 attach 重载靠位置才能知道第四参是毫秒
  * **`GoalEngine.addTaskExecutor(String channel, Predicate<String> claim, TaskHandler handler)`**：认领条件从 handler 的 null 返回值升级为显式谓词，引擎可在不执行 handler 的前提下静态评估互斥性——重叠时 `LOG.warn`（每命中组合一次，防批量恢复刷屏；组合集合超 64 个时停止追加防病态注册撑爆内存）。认领日志带通道名；谓词异常按不命中计；谓词是「预过滤」不是「权威判定」：命中后 handler 返 null 仍穿透到下一注册者（运行时端口暂不可用等场景依赖此回退）。旧形态注册（无谓词）继续可用但不可评估。`channelsClaiming(sessionId)` / `overlapWarnCount()` 包内观测口（测试 classpath 无 SLF4J 后端，日志捕抓不可用）
  * **`GoalExtension.addTaskExecutor(channel, claim, handler)` 门面直通 + `Builder.taskExecutor(channel, claim, handler)` 装配时形态**（两者对称，旧单参 `taskExecutor(handler)` 保留）；宿主三注册点（CliShell / WorkspaceManager / WsGate）全部切换：`goal.addTaskExecutor("cli", SessionIdOwnership::isCli, handler)`，内联 if-return-null 谓词样板消失
  * **`warnOnceNoScheduler`**：`scheduleNow` / `resumeScheduleOnly` / `unschedule` / `stopAll` 在无调度器时首次调用打一条 warn（AtomicBoolean 门闩，不刷屏，四方法共用一个闩）——「调了 scheduleNow 却没跑」的排障盲区消除。告警只在「确实没调度器」时触发（`sessionId == null` 或扩展停用是另一类原因，不得误报）；`onDetach` 直连内部字段而非 `stopAll()`——摘除时无调度器属正常形态，不应告警
  * **API 收窄（弃用而非删除，下个 minor 移除）**：`GoalExtension.resumeAndSchedule`（与 `resume` 同义且与 `resumeScheduleOnly` 名字易混）、`attach` / `attachAndSchedule` 的 5 裸参重载（指向 `GoalSpec` 版，单位自描述）。门面内部互调已改走保留入口（`attachAndSchedule(spec)` → `attach(session, spec)` + `scheduleNow`），不自产弃用告警。`GoalEngine` 的裸参 attach 不弃用（门面保留入口仍委托它）
  * **宿主死代码清理**：`WorkspaceContext.getGoalEngine()` / `getGoalScheduler()` / `attachGoal(GoalEngine, GoalScheduler)` 旧重载与 `goalEngine` / `goalScheduler` 字段删除（主代码 0 调用，属过渡期残留）；`GoalLoopEndpointTest` 的 `verify(context, never()).getGoalScheduler()` 随之删除——该意图已由编译期保证（getter 不存在即不可能绕门面）
  * **新增测试 11 例**（`GoalChannelExecutorRegistrationTest`）：空白通道名/空谓词拒绝、旧形态不进评估、重叠每组合告警一次且注册顺序语义不变、谓词命中但 handler 返 null 穿透、抛异常谓词按不命中、门面直通、无调度器门闩恰触发一次、谓词每轮只评估一次、`stopAll` 同门闩、`onDetach` 不误报、builder 通道化按声明序生效
  * **谓词只评估一次**：`execute` 原先把谓词跑两遍（互斥检测一遍 + 路由一遍），宿主谓词常是正则（`isDesktop = matches("\\d{1,18}")`），改为单次评估后结果复用；`channelsClaiming` 仅为测试观测口，不动
  * **验证**：harness goal 定向（GoalChannelExecutorRegistrationTest 7 + GoalExtensionTest 20 + GoalRoundExecutionTest + GoalEngineTest + GoalCommandTest + GoalBoundaryTest）全绿；`InjectedRoundChannelContractTest` 断言升级为通道化形态（三入口各自锁定 `addTaskExecutor("cli"/"web"/"desktop", SessionIdOwnership::…)`）；cli 全量与基线一致

* **steer 域宿主封装收尾：忙判定自算、严格轮次语义入内核、容量可配、批量取消**（4.2.0 未发布，重载新增无破坏）:
  * **背景**：steer 内核已收口，但宿主端到端仍要自拼四处本可上收的判定——`busyRecheck` 回调、`runId` 空值语义、入参长度校验、状态码字符串 switch。其中「忙判定」是唯一一处「有唯一正确答案却外包给宿主」的残留（答案在 `engine.status(session).isBusy()`）
  * **`SteerExtension` 持有 engine**：`onAttach` 保存引擎，新增 `defaultBusy(session)`＝`status(session).isBusy() || isAdmitting()`（与宿主 `SessionActivity.isBusy` 同口径）。新增 5 参 `steer(session, runId, steerId, text, origin)` 与 3 参 `steer(session, text, origin)` 走缺省口径；6 参全参重载保留作为「口径不同」的高级入口
  * **`steerOnRun(...)`**：runId 必填，空即 `TURN_CHANGED`。Web 端「任务首个事件到达前不放行」从 `ChatWebController` 的一条 if 收归门面（`SteerHelper.steer(..., requireRunId)` 实现，拒绝时不生成 steerId、不碰邮箱）
  * **`SteerLimits` + `builder()`**：邮箱容量/文本长度/ID 长度由 `public static final` 常量升级为可注入值对象；`new SteerExtension()` 等价 `SteerLimits.DEFAULT`，`SteerHelper` 的常量保留为缺省值（6 个测试类引用不破）。`getLimits()` 供宿主读实例配置而非静态缺省
  * **`cancelAll(ids)` / `clearPending()`**：前端「清空待生效插话」从此前的逐条 `cancel` 循环（N 轮往返 + 中途失败半截状态）改为一次收口；`clearPending` 只交还条目、不替宿主决定降级去向
  * **`beginTurn(session)`**：`handover` 的语义化别名——宿主只需记住「新任务开流前 beginTurn」。该义务仍是硬义务（`onAgentEnd` 在 interrupt/异常路径不触发，正是最需要交接的路径），但命名让遗漏更难发生
  * **attrs 键更名 `web.steerBox`/`web.activeRunId` → `harness.steerBox`/`harness.activeRunId`**：原前缀违反 `HarnessAttrs` 命名空间规约（`web.*` 归宿主私有）。attrs 是 transient、不落 snapshot，同一 JVM 内读写同源，无跳版兼容问题，故直接更名不做双读
  * **宿主侧删除**：`ChatWebController` 的入参校验（11 行，与内核完全重叠）、`runId` 空判（3 行）、忙判定 lambda；`WebChatInputHandler` 的 `busyRecheck` lambda。状态码 switch 由字符串字面量改为 `SteerStatus` 枚举分支（新增态至少编译期提示，不会静默落 409）。忙态**预判**保留在宿主——它是协议语义（`NOT_RUNNING` 回落普通发送 vs `TURN_CHANGED` 转排队），不是重复判定
  * **`WorkspaceContext.getExtension` 修假 `@Nullable`**：实现由 `Optional.get()`（永不返回 null，导致调用方判空全成死代码，而真正会抛 NPE 处无保护）改为 `orElse(null)`；三处 steer 门面调用随之显式判空，未装配时回 `503 STEER_UNAVAILABLE`/`STEER_DISABLED` 而不是 `NoSuchElementException`
  * **新端点 `/web/chat/steer/cancelAll`**：`steerIds` 逗号分隔，省略即清空；返回实际取消条数（已消费的不计）。前端 `discardPendingSteers` 改一次请求，未确认的一律置 `unknown` 等终态事件收口，不猜测
  * **护栏**：`PackageBoundaryTest` 新增两条——宿主主代码禁止 `import SteerHelper`、禁止硬编码 `web.steerBox`/`web.activeRunId`。现状零违规（`LastTraceService`/`ChatWebController` 的 javadoc 引用已同步改为门面/拦截器）
  * **新增测试（10 例）**：`steerWithoutBusyRecheckUsesEngineDefault` / `detachedExtensionDoesNotBlockSteer` / `explicitBusyRecheckOverridesEngineDefault` / `steerOnRunRequiresRunId` / `steerOnRunRejectsForeignRunId` / `builderLimitsAreEnforced` / `defaultConstructorUsesDefaultLimits` / `cancelAllCountsOnlyActuallyRemoved` / `clearPendingReturnsItemsAndEmptiesBox` / `beginTurnIsHandoverAlias`。`ImBusySteerContractTest` 两条源码契约同步改为锁定新形态（并反向断言 `() -> SessionActivity.isBusy(session)` 不得回潮）
  * **验证**：harness steer 定向 **69 例全绿**（19 例 `SteerExtensionTest` 全过，含源码契约 75-86 未改动）；harness 全量 **882 例 33F+5E** 与基线逐项一致（ToolStrategy/Web/Write 权限域 + TaskTalent/Accessor 环境域既有，steer 域零失败）；cli 全量 **1254 例 3F** 与基线逐项一致（settings/挂载域既有）；cli steer 相关定向 27 例全绿

* **queue 消费协议收敛：`QueueClaimHandler` 返回值化，同步收口内核化，宿主不再手写 acknowledge**（4.2.0 未发布，签名直接改，编译器收口全部调用点）:
  * **背景**：队列机制已封装到位，但「消费队列」没有——同步宿主（CLI）必须知道「ack 失败必须抛异常」这条只活在注释里的铁律（丢弃 `acknowledge()` 返回值编译照过，后果是 Stop 被静默击败、任务无限重跑）；异步宿主（Web）必须用 `isBusy` 反推「同步命令已当场完成」再手动 `settleDispatch`。两个陷阱都是「API 允许写出看起来对、实际错的代码」
  * **`QueueClaimHandler.run` 签名 `void` → `boolean`**：返回值即收口契约——`true` = 任务在回调返回前已同步完成，内核当场 `acknowledge()`；`false` = 异步启动，终态由宿主经捕获的 `QueueDispatch` 收口。收口时机只有两种，正好由布尔表达
  * **内核同步收口分支**（`QueueHelper.claimNext`）：返回 true 后 ack 被拒（期间被 `cancelPending`）→ 自动接 `rollback()`（代际已变走丢弃分支）→ 返回 `START_FAILED` 让循环终止，任务不复活、后续不继续跑。静默吞掉的旧写法会让调用方以为启动成功而继续领取下一条
  * **`WebQueueDispatcher.drainSessionQueue` 回调改返回值语义**：`return !isBusy(session)` 替代「isBusy 反推 + 手动 `settleDispatch`」——忙态从「调收口 API」降为「报告状态」，推断错时机的悬挂 claim 从写法上消失
  * **`WebSessionRunner.failStreamTurn` 改身份化判定**：`turn != null`（订阅前捕获的本轮派发事务）替代会话态 `hasQueuedDispatch`（= `currentDispatch(session) != null`）。等价性：正常时序下二者同值（busy 期间无新派发）；Stop 竞态下队列已清，均无实际续发。`WebQueueDispatcher.hasQueuedDispatch` 删除（主代码 0 调用）
  * **goal 恢复职责归位**：`recoverSessionGoals`（35 行 goal 域知识）从 `WebQueueDispatcher` 拆出为独立的 `WebGoalRecovery`——queue 派发器不再携带跨域知识；`WebGate.recoverSessionQueues` 统一接线
  * **护栏**：`PackageBoundaryTest.hostMustNotOwnQueueDispatchState` 新增两条规则——宿主主代码（CliShell/WebQueueDispatcher）禁止 `claim.acknowledge(`/`claim.rollback(`（同步收口归内核）；CLI 禁 `settleDispatch(`（纯同步循环无合法用途）；Web 禁 `hasQueuedDispatch` 回潮。反向验证：改回旧手写 ack 写法，护栏准确报出「手写 claim 收口」
  * **新增测试**：`QueueClaimTest.syncDoneDuringStopReturnsStartFailedWithoutResurrect`（同步完成 + 期间 Stop → START_FAILED、不复活、登记不残留、领取权可复用）与 `syncDoneSettlesInKernelAndClearsQueue`（同步完成正常路径内核当场收口）。反向验证：撤掉内核 ack 分支后准确报出 `expected: <START_FAILED> but was: <STARTED>`
  * **验证**：queue 域定向 74 例全绿；harness 全量 **872 例，33F+5E** 与基线逐项一致（ToolStrategy/Web/Write 权限域 + TaskTalent/Accessor 环境域既有，stash 对照确认）；cli 全量 **1254 例 3F** 与基线逐项一致（settings/挂载域既有）；`PackageBoundaryTest` + `ImBusySteerContractTest` 20 例全绿

* **queue 包收敛：同步/异步双领取路径合一，`drainOne` / `QueueTaskRunner` 删除**（4.2.0 未发布故无适配层；唯一宿主调用点 CLI 已同步改写）:
  * **背景**：`drainOne(runner)` 是 `claimNext` 的布尔型同步变体，四条分支（队空/已被占、runner 抛异常、正常完成、ack 失败）全部可由「claimNext + handler 内 acknowledge + ack 失败抛异常」表达。两条路径曾在「ack 失败时任务去向」上各自漂移（drainOne 静默 requeueFront，claimNext 走 START_FAILED），收敛后只剩一条领取路径
  * **删除清单**：`QueueTaskRunner` 接口（文件删除）、`QueueHelper.drainOne`（22 行）、`QueueExtension.drainOne` 门面；`SessionQueueSteerTest` 的 6 个 drainOne 用例改写为 claimNext 同步写法（含新增断言：启动异常返回 START_FAILED 且任务回队首，不再向外传播）
  * **`CliShell.drainCliQueue` 改写**：`while (queue.claimNext(...claim.acknowledge()...)) == STARTED)`，**ack 失败必须抛异常**——否则与旧 drainOne 在「Stop 后继续跑下一条」上产生偏差（静默吞掉会让 while 停不下来）
  * **契约键登记收敛**：`QueueHelper` 四个 attrs 键字面量改引用 `HarnessAttrs` 常量（双处登记消除）；补登记 `HarnessAttrs.QUEUE`（`session.queue` 本体键，此前四个键登记了它却缺席）
  * **死代码删除**：`QueueHelper.enqueue` 13 参重载（主代码 0 调用，唯一用户是自身委托链）、`QueueItem.fromMap(map, fallbackSource)` 双参版本（fallback 分支不可达：唯一调用传 null）
  * **`QueueCommand` 去 CLI 硬编码**：命令层不再兜底伪造 `"CLI"` 渠道——分发器透传宿主 origin（CLI/Web/IM 各自带渠道身份），本地 NONE 是合法值，展示层用 `channelOr` 自行降级。`localSourceDefaultsToCli` 用例改为锁定新契约（channel 存 null、channelOr 由消费方决定）
  * **`snapshot` / `size` 标注测试专用**：主代码读侧一律 `view()`/`hasItems()`/`pendingSize()`，两者仅供测试断言（javadaoc 已注明），不删（被 6 个测试类 30+ 处使用）
  * **验证**：queue 域定向 68 例全绿；harness 全量 870 例 33F+5E 与基线逐项一致（ToolStrategy/Web/Write 权限域既有）；cli 全量 1254 例 3F 与基线逐项一致（settings/挂载域既有）；README 六处 drainOne 段落同步

* **执行通道归属复位：`ExecutionBridge` 删除，goal 自持通道；补回 CLI / Web 的 goal 流式投递**（破坏性 API 变更，4.2.0 未发布故无适配层）:
  * **删除 `runtime.ExecutionBridge`**（唯一个同时被 harness 的 `GoalEngine` 与宿主 loop 调度器使用的类）。它把五件不相干的东西装在一起：执行投递、忙判定、轮次度量、session 解析、`_loop_*` 键定义。跨域共享 → 两个域都不愿意拥有它 → 只能不停往上挂东西，这就是通用轮次度量被命名为 `_loop_*` 的原因
  * **`GoalEngine` 自持执行通道**：`List<TaskHandler> taskHandlers` + `setDefaultTaskExecutor`（**单独字段**，不靠_注册顺序_保证「宿主优先」）+ `addTaskExecutor` / `hasTaskHandler` / `execute`（包内）/ `sessionOf`。替代 `ExecutionBridge` 的对应能力，无交叉兜底
  * **`TaskHandler` 返回类型 `String` → `RoundResult`**（破坏性）：这是 `_loop_*` 键 与 `reportRoundMetrics` / `resetRoundToolEvidence` 全套时序约定的存在理由——`String` 装不下 token 与工具证据，只能写 attrs 再读回来。改后全部删除（**不是改名**）。`RoundResult` 同时更正 javadoc：只服务 goal，loop 自己用 `String`
  * **`agentName` 参数移除**：`TaskHandler.handle(sessionId, prompt)`。两侧共 4 处调用点原本全部恒传 `null`，宿主里「拼 `@agentName` 前缀」的分支从未执行；子代理归属的真实来源一直是会话属性 `CTX_AGENT_SELECTED`
  * **busy 判定不再由宿主编谓词**：`BusyChecker` 删除。`GoalEngine.shouldSkipSession` 改为从会话快照单向派生（`engine.status(session).isOccupied() || getQueuedCount() > 0`）。`isOccupied()` 含 HITL 挂起，顺带修掉「审批挂起时仍注入回合顶掉未决 trace」这一同型缺陷
  * **`GoalState.lastHasToolCalls` 随轮次提交落投影**：`goal_update(complete)` 的轮内提示性校验读它；**权威判定仍在 `executeRound` 的结果复核**。`executeRound` 在调用 `execute` **之前**先置空该字段——`goal_update` 是工具、在 `execute` 内部读取，不置空就会读到**上一轮**的值（上轮有工具调用、本轮无 → 误放行 → 白跑一轮后被复核拒绝）
  * **无认领不再静默（goal 侧）**：`GoalEngine.executeRound` 在 `result == null`（既无宿主执行器认领、也无缺省）时**不再记 `"ok"`、也不清零连续错误计数**，而是写一条用户可见的 `error [no-executor]` 说明并提交。旧写法有两个后果：① 目标看起来一直在跑（`lastResult` 有值、时间戳在刷）实际什么都没做；② `resetConsecutiveErrors()` 每轮把熔断计数清零，**异常分级熔断永远不触发**。与 loop 侧「无认领不记 ok」同口径。新增 `unclaimedRoundMustNotRecordOkNorResetErrorCounter`，反向验证：改回旧写法时准确报出 `expected: <false> but was: <true>`
  * **验证**：harness 全量 **869 例，33F+5E**，与基线逐项一致（新增 1 例「证据不跨轮泄漏」）；`Goal*Test` 定向全绿

* **SteerInterceptor 拆分：静态内核独立为 SteerHelper，补齐 queue/steer/interrupt 三域「内核 + 门面」分层的最后一块**（零行为变更，纯机械搬移；配套 README、CODE.md 同步）:
  * **拆分边界**：插话的投递/校验/取消/残留转排队（`steer`、`steerBox`、`pending`、`cancel`、`offer`+`OfferResult`、`drain`、`enqueueIntoSessionQueue`）与 7 个公开常量（`ATTR_STEER_BOX`/`ATTR_ACTIVE_RUN_ID`/`MAX_BOX_SIZE`/`MAX_TEXT_LENGTH`/`MAX_ID_LENGTH`/`STEER_PREFIX`/`STEER_SOURCE`）迁入 `SteerHelper`（`public final` + 私有构造，与 `QueueHelper` 同构）；`SteerInterceptor` 瘦身为纯运行时消费边界（onReasonStart 守卫注入 + onAgentEnd 残留兜底，389→142 行），拦截器内部改调 SteerHelper
  * **刻意的取舍**：按「未承诺稳定性、直接改签名」的既定决策，拦截器上不留常量别名（曾一度写入又被删除）；`drain`/`hasOpenToolCalls` 这类仅拦截器使用的成员不外深；`OfferResult` 保持 package-private，测试同包引用随迁零改动
  * **引用收敛**：harness 主代码 `SteerCommand`(3)/`SteerExtension`(11)/`SteerDroppedEvent`(1)；harness 测试 3 个文件 ~100 处纯前缀替换（实例调用 `interceptor.xxx` 保留，不属静态迁移范围）；cli 测试 2 个文件（SteerCommandTest、LastTraceServiceTest 的常量引用）+ import；cli 主代码零依赖（仅 5 处注释改述）；文档：README 5 处静态引用 + §12.1 模块图 steer 包补「SteerHelper：邮箱契约与投递内核；SteerInterceptor：运行时消费边界」
  * **效果**：steer 与 queue/interrupt 结构完全对齐（内核 `*Helper` 仅供门面与测试，宿主一律走 `SteerExtension`/`engine` 门面），为 CODE.md 的 `*Helper` 分层规约补上 steer 一域的落点；拦截器单文件从双主题（运行时消费 + 静态内核 API）回归单一职责，各自并发契约更清晰

* **宿主接入面收敛：Session* 内核类改名 + 引擎级门面补齐**（配套 README §5 全量同步；取代此前「静态工具类 + instanceof 遍历」的接入方式）:
  * **改名清单（零行为变更，纯重命名 + 包内迁移）**：`SessionQueue → QueueHelper`、`SessionQueueItem → QueueItem`、`SessionQueueDrainer → 并入 QueueHelper.drainOne`、`SessionInterrupt → InterruptHelper`（迁至 `session/interrupt/`）；静态方法签名与语义均未变，仅类名与归属包。规约沉淀在 harness `CODE.md`（`Session*` 前缀只留给 `AgentSession`/`SessionStatus`）
  * **新增 `HarnessEngine.getExtension(Class<T>)`**：替代 `getExtensions() + instanceof` 遍历（此前宿主取 QueueExtension/SteerExtension/GoalExtension 只能强转，WebSettingsController 的 instanceof 循环已改直取）
  * **中断四形态**（`InterruptHelper` + 引擎对应入口）：`interrupt`（清队列+停流+补消息，原有）/ 新增 `interruptStream`（只停流，推代数，不写历史——goal 轮次被顶掉时用，避免假「用户已取消」）/ 新增 `interruptStreamActive`（只停流且不推代数——新一轮腾位，避免旧流把新轮收尾牵连）/ 既有 `writeCancel`（只补取消消息，Web 崩溃恢复用）。四入口语义对照表已补进 README §5.4
  * **新增 `HitlCard`（input 包）**：`HITLTask` 的稳定投影值对象。`list/undecided/next/pendingCount` 四个静态入口收拢了宿主四处自写的「首个未决策项、整批已决策回退首项」判定（旧 `HITL.getPendingTasks + getDecision` 过滤不再需要）；`isBash/getCommand` 供 CLI 确认界面直接用；宿主不再接触 React 引擎内部实体 `HITLTask`
  * **新增 `SessionStatus`（session 包）**：`isBusy/hasPendingHitl/queuedCount` 一次读全，取代宿主自拼 `SessionRuns.isBusy || HITL.isHitl || pendingSize>0`；入口 `engine.status(session)`
  * **SteerExtension 补齐**：实例方法 `activeRunId(session)`（取代宿主直读 `SteerInterceptor.ATTR_ACTIVE_RUN_ID`）、`handover(session)`（把「残留插话转排队」的 12 行 attrs 内务收进 harness，WebSessionRunner 原地删）；静态访问器 `maxTextLength/maxIdLength/isSteerMessage/stripSteerPrefix`（取代宿主引用内部常量，LastTraceService 的元数据嗅探改语义化调用）
  * **QueueExtension 补齐**：`generation/isGenerationActive/requeueFront/importItems`（WebQueueDispatcher 两阶段 drain 的代际校验不再穿透静态内核）
  * **宿主修复（本次发现的三处行为收窄）**：`CliShell:450/486`、`DesktopStreamTurn:164` 的 HITL 判据由 `status().hasPendingHitl()` 改为 `HitlCard.next() != null`——残留决策（整批已决策但续流未发生，如崩溃恢复窗口）也会进审批循环透传给引擎，不再直接 done 掉导致决策永远等不到续流
  * **同步**：README 11 处旧类名、§5.4 四形态对照表、FAQ 18 重写（「停当前留队列」从权衡题变为 `interruptStream` 正解）、模块图；harness `CODE.md` 新建（命名规约 + 门面分层 + 域边界提醒）；`InputOrigin` javadoc 同步
  * **验证**：harness `mvn clean install` 通过（38 例失败经 `git stash` 基线比对全部为既有，与本轮无关）；cli `mvn clean test-compile` 零错误；定向契约测试 16 例全绿（DesktopStreamTurnCancelTest 3 + SessionActivityTest 3 + SteerCancelWebContractTest 2 + TaskQueueDockWebContractTest 3 + ImBusySteerContractTest 5）

* **Mcp 服务端：未配置传输安全校验器时启动 warn 提示，并补上 `ServerHttpHeaderValidator` 接入**:
  * **背景**：`mcp-core` 已将 `ServerHttpHeaderValidator` 定为 `ServerTransportSecurityValidator` 的替代接口，但 `solon-ai-mcp` 的三个 Web 传输实现仍只认旧接口；用户按新接口实现校验器会被**静默忽略**，实际生效的是 NOOP，且启动日志完全看不出“到底有没有校验”
  * **新增 `HttpHeaderValidatorAdapter`**（`org.noear.solon.ai.mcp.server`）：把 `ServerHttpHeaderValidator` 适配为旧接口，内置大小写不敏感的 `MapHeaderAccessor`
  * **`McpServerEndpointProvider`**：新增 `Builder.httpHeaderValidator(ServerHttpHeaderValidator)`（刻意**不**重载 `securityValidator`，否则同时实现两接口的 `DefaultServerTransportSecurityValidator` 会调用歧义）；`Builder` 与 `Properties` 构造器的默认值均保持为 `NOOP` 不变（判定只看哨兵常量，无需区分“没配”与“显式配了 NOOP”），故对既有调用方零影响
  * **启动提示**：新增 `McpServerHost.warnIfNoop(...)` 统一判定与文案（`isNoop` 一并从适配器移入此处，适配器只负责接口转换），`StatefulMcpServerHost` / `StatelessMcpServerHost` 在既有 INFO 之后各调一行；仅 HTTP 通道提示（stdio 无 HTTP 头），带 name/channel/endpoint 与建议方案；`build()` 本身幂等，故单实例只提示一次
  * **`McpPlugin`**：`@McpServerEndpoint` bean 若实现新接口，走 `httpHeaderValidator` 接入（旧接口分支保留在前）
  * **验证**：新增 `HttpHeaderValidatorAdapterTest` 3 例（新接口适配生效/大小写不敏感/缺头拦截、哨兵 NOOP 判定、header 访问语义）；`McpStartTest`/`McpPropsTest(2)`/`McpContextPathTest`/`McpHttpAuth2Test` 均绿（24 例）；实测启动日志中 17 个 HTTP 端点告警 15 条，已配校验器的 `McpServerAuth2` 与两个 stdio 端点均不告警；`McpHttpClientMixTest7_sse/10_stateless` 的 `prompt1` 失败经 `git stash -u` 基线对照复现，属既有问题

* **会话域命令自包含：GoalCommand/QueueCommand/SteerCommand/InterruptCommand 迁出 command.builtin，随域包就位（消除 command.builtin ↔ session.* 双向引用环）**:
  * **动机**：域包通过 Extension 装配命令（GoalExtension import GoalCommand…），命令又 import 域类，形成集中在装配点的双向环。每个能力自包含（域 + 命令 + Extension）后，`command.builtin` 只留通用基础命令，职责名副其实
  * **移动**：GoalCommand→`session.goal`、QueueCommand→`session.queue`、SteerCommand→`session.steer`、InterruptCommand→`session.interrupt`（均改 package 声明、删升为同包的 import）；三个 Extension 的对应 import 删除，装配代码零改动（命令注册本就由 Extension 完成）
  * **测试随迁**：四个 CommandTest 同步移入域包；`Fakes`/`ContinueCommandTest`（含 newTrace/setOriginalPrompt）公开为 public 供跨包测试共用；`BuiltinCommandsSystemTest` 补显式 import；`GoalBoundaryTest` 硬编码路径同步；三个 ExtensionTest 的全限定名改简名
  * **宿主（soloncode-cli）**：WorkspaceManager 的 `command.builtin.*` 通配 import 保持不动（InterruptCommand 单独 import，因通配已不覆盖它）；宿主 SteerCommandTest 改 import session.steer；IM SOP 表格同步
  * **包名不重命名**：`command.builtin` 余下 6 个（help/clear/model/rewind/rerun/continue）均为通用基础命令，名实相符；README §4.1 已同步两段式描述
  * **验证**：harness 定向 43 例全绿（四命令测试+系统测试+护栏）；全量 818 例的 31F+5E 经 `git stash -u` 基线对照逐项一致（ToolStrategy/TaskTalent/Accessor，均为既有问题）；宿主 clean 后定向全绿（首次运行的 SteerCommandTest Error 系陈旧字节码，clean 消除）
* **steer 输出改为 AgentEvent：删掉端口接口与全部宿主装配**（净删约 110 行 harness + 60 行宿主，修三处真实缺陷；取代本条此前记录的「端口 + session 打标」中间态）:
  * **问题（设计层）**：`SteerOutputPort` 是为「拦截器要把消息送到前端」而造的一层接口，但同一件事在本仓其他组件里根本不需要接口——HITL、上下文压缩、Run 起止全都直接 `trace.pushAgentEvent(...)`。**steer 是唯一特例**，为此多出泛型令牌 + Supplier + 宿主 instanceof 三层间接。而真正决定「投递到哪」的工作区，其实在事件消费方（`WebEventMapper` 构造时就持有 `wsContext`）手上，根本不需要从 session 里翻
  * **问题（实现层，三处真缺陷）**：
    1. **`steer_applied` 在默认实例路径永不发出**：两条出口守卫不对称（`emitSteerApplied` 判 `wsContext != null`，单参构造恰好传 `null`）。受影响路径：Web 队列派发 / 自动任务 / Loop / 命令触发
    2. **`onAgentEnd` 的 dropped 调用无 try-catch**：`null` 路由送进 `WebGate.emitToClient` 的 `wsContext.getConnections()`（在 try 之外）炸 NPE，并沿 `ReActAgent` 的拦截器遍历冒泡，导致后续拦截器 `onAgentEnd` 全部不执行
    3. **双写路径的投递乱序**：`steer_applied` 走拦截器线程直写 socket，与 flux 管线的 `doOnNext` 是两条独立写入路径，顺序无保证
  * **方案**：新增 `SteerAppliedEvent` / `SteerDroppedEvent`（均继承 `AbsReActEvent`），由 `SteerInterceptor` 直接 `pushAgentEvent`。宿主按事件类型映射（Web 加两个 `instanceof` 分支 → `system.steer_applied` / `system.steer_dropped`），不认识的宿主零成本忽略。投递与 `reason.delta` 走同一条 flux，乱序问题随之消失
  * **展示与持久化分离（关键）**：`pushAgentEvent` 在非流式（无 sink）或订阅已取消时**静默丢弃**，所以事件绝不能承载业务动作。新增 `SteerInterceptor#enqueueIntoSessionQueue`，把「未消费插话转 `QueueHelper`」上移到 harness 并**先落盘、再推事件**。附带收益：CLI / Desktop 也获得了把残留插话转为排队任务的能力（此前是丢弃 + warn）
  * **两个事件刻意不覆写 `getText()`**：它们不属于文本流。一旦给出文本，按文本兜底渲染的宿主（ACP 的 `concatMap` 收尾分支、Desktop 的 frame 转换）会把插话内容顺手打进对话。需要展示的宿主必须显式按类型分支
  * **删除清单（harness）**：`SteerOutputPort` 整个接口（53 行）、`SteerInterceptor` 的 `outputPortSupplier` / `withPort` / `emitSteerApplied`（约 47 行）、`SteerExtension` 的 `(Supplier)` 构造器。`new SteerExtension()` 成为唯一构造器——没有注入入口，也就没有「漏传端口」这种陷阱
  * **删除清单（soloncode-cli）**：`WebGate` 的 `SteerOutputPort` 实现 + `routeOf` + `ATTR_REPLY_WORKSPACE`（约 60 行）、`WorkspaceManager` 的 lambda 装配、`WebStreamBuilder` 的每请求 `new SteerInterceptor(...)` + `interceptorAdd` 样板、`WebSessionRunner#beginStreamTurn` 的 session 打标（改为直接复用 `enqueueIntoSessionQueue`，排队逻辑只有一处）
  * **行为等价性**：正常路径（Web 主对话插话 → applied 落气泡、任务结束 → dropped 转排队并继续执行）一字未变。改变的是原先失效/崩溃的三条路径，外加 CLI/Desktop 的 dropped 从「丢弃」变为「转排队」
  * **测试**：`SteerInterceptorTest` 14 → 22 例（用覆写 `pushAgentEvent` 的记录型 trace，不依赖流式 sink），覆盖 applied 真实推出、守卫跳过不推、dropped 先落盘后广播、**无订阅者时队列仍增长**、队列满回填邮箱且 `failed` 可辨识、入队失败不冒泡。`SteerExtensionTest` 删除名不副实的「惰性端口」空转用例。新增 `WebEventMapperSteerTest`（6 例）覆盖两种事件映射、failed 额外出 error、空 dropped 不出噪音、以及**穿过管线三个过滤器**（防静默过滤）。`WebGateRejectedInputTest` 删除三个针对已下线端口方法的用例（语义已由 harness 侧用例覆盖）
  * 验证：harness 相关 75 例全绿；`soloncode-cli` 全量 1226 例 3 失败，与 `git stash` 后的干净基线逐项一致（ImageReply 异常文案 / AgentSettings 400 / Mount 安装目标数），均为既有问题
  * 文档：README §5.3 装配段与输出契约整体重写为事件形态，§9 拦截器挂载段删除请求级挂载示例
  * **环境坑（值得记录）**：本机环境变量 `CLASSPATH` 残留了旧的 JMeter 路径，会串进 javac 的类路径并**禁用 Lombok 注解处理**，表现为全仓 200 个 `找不到符号 getXxx()`。基线同样复现，与代码无关；构建时用 `env -u CLASSPATH mvn ...` 规避

* **TaskTalent 调度内核重写：信号量 + 轮询分派 → 单队列 + N 个 worker 自取**（有效代码 668 → 562 行，总行数 1000 → 884；旧调度段 250 → 新调度段约 80）:
  * **动机**：`Semaphore` + `acquirePolling` + `dispatchParallel` + `tryAcquireReady` + `ModelSlot` 这一套的存在前提是「槽位引擎级、跨会话共享」。槽位一旦改为「按调用算一个数」，就没有待抢的资源，也就不需要抢——**共享与轮询是耦合的，去掉共享顺带去掉轮询**
  * **新形态**：一批任务进 `ConcurrentLinkedQueue`，起 `min(cap, size)` 个 worker 各自 `poll`；worker 跑完一个立刻取下一个，这正是「首发 cap 个、完成一个补一个」的全部实现，不需要完成回调与计数递减。抽为包级类 `TaskDispatcher`，`TaskTalent#dispatchQueued` 只剩一次调用
  * **生效上限改为按调用**：取**当前会话所选模型**的 `AiConfig#concurrency`（0 = 未设置 → `subagentDefaultConcurrency`）。不再按任务自身模型分组——`AgentDefinition#model` 路由到其它子代理时，其模型上的 `concurrency` 对本次调用不再生效
  * **行为变更（有意识地接受）**：① 多会话之间不再共享额度，同一模型的最坏并发 = 会话数 × cap（原设计正是为消除这一点而引入信号量，`UPDATE_LOG` 前文「5 个会话各跑一个 task」即指此）；② 串行快路径删除，cap=1 也走 worker 线程（多一次线程投递，换来串行/并行不再是两个分支）；③ 「未启动」原因不再区分取消与中断，统一为「主流程已取消，任务未启动」
  * **连带删除**：`CAP_NONE`、`modelSlots`/`ModelSlot`、`semaphoreOf`、`slotKeyOf`、`finalConcurrency`、`isSerialOnly`、`dispatchSerial`、`dispatchParallel`、`tryAcquireReady`、`markNotStarted`、`submitTask`、`acquirePolling`、`releaseQuietly`、`SLOT_WARN_MS`
  * **顺带消失的一整类缺陷**：没有 permit 就没有「提交失败漏还许可」语义（原先要 `catch Throwable` 兜住建线程时的 `OutOfMemoryError`，否则该模型后续全部卡死）；`SynchronousQueue` 那条「core=0 配无界队列会退化成并发 1」的约束也不再是关键路径
  * **保留下来的不变式**：`aggregate` 按下标聚合、`<multitask_results>` 与任务数 1:1（任何一个 future 不完成都会让主流程永久挂住，故正常/取消/提交失败/worker 异常四条退出路径都会把剩余项补成占位）；`resolveConcurrency` 与 `normalizeDefaultConcurrency` 契约不变
  * **顺手改名**：`SLOT_POLL_MS` → `CANCEL_POLL_MS`（它承担的其实是取消检测节拍，旧名是当前最大的认知负担）
  * **测试**：新增 `TaskDispatcherTest` 6 例（窗口真实生效且完成即补位、cap=1 必须串行、整批已取消、执行中途取消、执行器已关闭、任务体抛异常不留下未完成 future）；`TaskTalentSlotTest` 16 → 3 例（只保留执行器真并发/关闭拒绝/上界截断）；`TaskTalentSchedulingTest` 删除按下标写入与许可归还两例（所锁定的缺陷在新结构里不存在），保留取消聚合与坏条目隔离；`TaskTalentParallelCapTest` 删除 `isSerialOnly` 5 例。定向 28 例全绿；harness 全量 **777 例 29F+3E**，失败仍全部集中在 `ToolStrategyDefaultsTest`/`BashToolStrategyTest`/`WebToolStrategyTest`/`WriteToolStrategyTest`，与既有基线逐项一致
  * **复审后四项修正**（第三轮）：
    1. **`resolveConcurrency` 补齐上界截断**（真缺陷）：类注释承诺「上界 `MAX_CONCURRENCY`」，但只对默认值生效——`concurrencyOf` 直返模型配置、`resolveConcurrency` 只判 `> 0`，模型配 200 就会真的 execute 200 个 worker，而执行器是 `core=0/max=MAX_VALUE/SynchronousQueue`，等于真建 200 个平台线程，多会话再按会话数叠加。现改为 `Math.min(modelConcurrency, MAX_CONCURRENCY)`，与默认值归一规则对齐；补两例锁死（`resolveConcurrency(200,5)==MAX_CONCURRENCY`、`resolveConcurrency(Integer.MAX_VALUE,100)<=MAX_CONCURRENCY`）
    2. **`dispatchQueued` 注释与代码对齐**：原注释称「不可解析的任务不入队」，实际全部下标都交给 `TaskDispatcher`，invalid 条目会入队但被 `runTask` 入口立即短路。现改为如实说明「入队但立即短路」，并注明**不要为此加过滤**——让它们留在队列里才能使「下标 → 结果」与入参完全一致
    3. **`aggregate` 删除未使用的 `plans` 参数**（全文无引用）；`notStartedReason` 三层重载压成一层；`multitask` 内 `sessionModelOf(__parentSession)` 提取局部变量（两次调用之间配置被换掉会让上限与实例不一致）
    4. **「未启动」原因注释如实化**：中断判定读的是当前线程标志，worker 线程上的占位补全读到的是 worker 自身状态，可能退化为「主流程已取消」。不引入额外状态传递，但在注释中写明这是有意的取舍（真正常见的只有取消，中断那条是兜底）

* **multitask 并行上限细化：新增模型级 `AiConfig#concurrency`；限流从「超限报错」改为「提交前排队」，并用引擎私有执行器隔离全局共享池**:
  * 问题一：并行上限只有引擎级一个标量（默认 5），一刀切，无法适配不同模型/API Key 的限流配额差异（免费中转可能只抵 2 并发，官方 Key 可抵 10+）
  * 问题二：超限直接返回 `ERROR: 单次最多只能执行 N 个子任务，请分批调用 multitask`，逼 LLM 再耗一整轮工具调用重试，白烧 token 与轮次
  * 问题三（本轮新识别的真缺陷）：`RunUtil.io()` 是 **JVM 全局共享池**（`RunUtil` 静态字段 + `max(cpu*4,16)`），不只属于 harness；goal 调度等其他模块也在用。若 multitask 的分派线程本身就跑在该池上（`GoalExtension` 的 `RunUtil.async` 承载整轮 goal），等待并发空位会把池线程占死，拿到空位的任务永远等不到线程 → 许可永不释放 → 全链路死锁
  * **配置项语义与命名（旧名已发布，保留 `@Deprecated` 桥接）**：
    * `HarnessOptions#multitaskMaxTasks` → **`subagentDefaultConcurrency`**：语义从「单次任务数上限（超限报错）」变为「子代理任务的默认并发上限（超限排队）」，故 `MaxTasks` 这个名字已失真。命名风格对齐同文件既有先例 `compressionDefaultContextLength`（`<模块>Default<属性>`），模块词选 `subagent` 以对齐 `subagentEnabled`。旧名 `multitaskMaxTasks`（自 v4.0.5 已发布）与中间过渡名 `multitaskMaxConcurrency` 均保留 `@Deprecated` 桥接，转发到新名。**但存量语义已变**：旧版该值只影响提示词文案（建议 2-N 个）、并不真正限流；新版它是真实的默认并发上限且不再参与提示词，升级后影响力变强，需复核存量配置
    * `ChatConfig#maxParallelTasks` → **`AiConfig#concurrency`**：字段上移到 `AiConfig` 基类（并发数是所有 AI 接口的通用属性，`EmbeddingConfig`/`GenerateConfig`/`RerankingConfig` 均继承），并去掉 `max` 前缀——「可并发数」本身就是上限，无需再加 max；`ChatConfigReadonly` 同步暴露读取口
  * **语义从「两级取 min」改为「模型优先、默认回退」**（关键设计变更）：
    * `AiConfig#concurrency` 的 **0 表示「未设置」**，与 `contextLength` 的 0=未设置 完全同构；模型未设置时回退 `subagentDefaultConcurrency`
    * 解析规则收敛到单一入口 `resolveConcurrency(model, default)`，与 `ContextCompressionInterceptor#finalContextLength`（先取默认、有配置才覆盖）同构
    * 收益：旧方案里「模型配了 8 却只跑出 5、必须查日志才知道为什么」的静默降级被彻底消除；用户配多少就是多少
    * **不存在「不限制」这个档位（本轮校正）**：默认值是兜底安全网，必须有界。`0` 不能被解释为「不限」——① 物理上不成立：线程数、上游 QPS、堆内存会退化为只受任务数约束，恰好是最容易打爆上游（429）与 OOM 的形态；② 信号量一旦缺席，「模型级 `concurrency`」就形同虚设；③ 0 已被「未设置」占用，同一个 0 不能既是「没配」又是「配成无限」。因此上一版「0 = 全局逃生门」的取舍已推翻：需要高并发时请显式配模型级 `AiConfig#concurrency`（那是用户亲手写下的具体数字，意图与责任都明）
    * **默认并发上限的取值契约归一到一个入口**：`TaskTalent.normalizeDefaultConcurrency(int)`（`HarnessOptions`/`HarnessEngine`/`Builder` 三处共用，避免各层各写一套）+ 常量 `DEFAULT_CONCURRENCY=5` / `MAX_CONCURRENCY=10`：`1~10` 原样；**`0` = 未设置 → 缺省 5**（与 `contextLength`、`concurrency` 的 0=未设置 同构）；`>10` → 上界 10；负数 → `IllegalArgumentException`。归一（0 或超上界）一律打 WARN——否则「配了却没生效」又退回成静默降级。`resolveConcurrency` 因此<b>恒返回正整数</b>，“0 = 不限”这个返回值已不存在；`TaskPlan.cap` 的 0 重定义为 `CAP_NONE`（无槽位：子代理未知/模型不可解析，任务会立即失败，不构成并发压力），与“不限”彻底脱钩；连带可删掉 `semaphoreOf` 的 `limit<=0` 死分支，线程数上界也从“正常情况下”变成“恒定不超过各模型生效上限之和”的可证不变式
    * 连带简化：只剩**一把**信号量，`globalSemaphore`/`globalSemaphoreLimit`/`acquireAll`/`releaseAll` 双许可形态/`globalCapWarned`/诊断日志全部删除。「取槽固定顺序避免死锁」这条约束也随之消失
  * **默认并发不再是「全局 5」而是「每模型 5」**（该方案的固有代价，已记录）：10 个不同模型时总并发可达 50。原全局 5 是总量硬闸门，现降级为单模型兜底
  * **配置可达性照抄 `compressionDefaultContextLength` 的三处同步**：`HarnessOptions` 持字段（保留 yaml 绑定）→ `HarnessEngine#setSubagentDefaultConcurrency` 先落配置再推送到 `TaskTalent` → `Builder#subagentDefaultConcurrency`。构造函数里 `new TaskTalent(this)` 之后立即把 `options.getSubagentDefaultConcurrency()` 推给执行者，否则 Builder/yaml 只写 options 不生效
  * **按任务自身模型取槽**（核心增益）：子任务可能各自路由到不同模型（`AgentDefinition.model` 优先于会话模型）。此前"批大小"是整次调用的单一标量，一个 `cap=1` 的子代理会拖垮整批；现在每个任务按自己会用的模型取槽，低配额模型只约束自己的任务
  * **提交前限流 + 引擎私有执行器**：在调用线程取到许可后才 `execute` 到 `TaskTalent` 私有 `ThreadPoolExecutor`，执行器里永远只有真正在跑的任务，不存在「占着线程等许可」。私有池与 `RunUtil.io()` 隔离，据此消除上述问题三。线程为守护线程，另提供 `TaskTalent.shutdown()` / `HarnessEngine.shutdown()` 显式回收
  * **`resolveTaskModelConfig` 与 `AgentFactory` 逐字对齐**（`AgentDefinition.model` → 会话模型 → 默认模型）：两者一旦分叉，限流用的模型和真正跑的模型会对不上，限流静默失效而不报错
  * **串行快路径的条件是「全部」而非「任一」，且必须用生效上限**：仅当**所有**任务的生效并发上限都为 1 时才在调用线程逐个执行。若写成「任一为 1」，一个 `cap=1` 的子代理会把整批拖成串行；若用裸 `getConcurrency()` 判定，则「模型未配置 + 默认值 1」会被误判为不限而错走并行路径（两点均有单测锁死）
  * **串行快路径也必须取槽**（后补）：信号量是引擎级、跨会话共享的；如果快路径绕开它，「显式配置 `concurrency=1`」的模型在任何走快路径的批次里就完全不受限流约束，而这恰恰是最需要严格限流的场景。cap=1 时首次取槽必定成功，不损快路径意义
  * **两轮取槽：先非阻塞、再阻塞等待**（后补）：第一轮 `tryAcquire` 抢到就立即提交，抢不到的收集起来第二轮才阻塞。若一开始就阻塞等待，一个低配额模型排在前头会把后面所有模型的任务都挡在「尚未提交」状态，整批吞吐退化为该模型的串行度——与「混合模型各自受限、互不拖累」的设计意图相反
  * **结果集合定长 + 按下标写入**：两轮取槽后提交顺序不再等于任务顺序，必须 `futures.set(i, ...)` 而非 `add`，否则 `<index>` 与 `<task_result>` 错位（有单测锁死）
  * **预解析只做一遍**：子代理、目标模型、生效上限先批量解析，串行判定与提交路径共用，「全部而非任一」的判断不会再因两次解析结果分叉
  * **取消响应**：取槽用 `tryAcquire(200ms)` 轮询而非无界阻塞，保证用户按停止后不会僵在原地；未启动任务填占位 `result_status=failure`，保证 `<multitask_results>` 条目数与任务数 1:1、`<index>` 不错位
  * **许可泄漏防护**：提交失败的 `catch` 从 `RejectedExecutionException` 改为 `Throwable`——`ThreadPoolExecutor` 创建线程时抛 `OutOfMemoryError` 属 `Error` 而非 `RejectedExecutionException`，只 catch 后者会让许可永久漏掉，该模型后续全部卡死。释放统一走 `releaseQuietly(Semaphore)`（容忍 null，不限并发时槽位就是 null）
  * **校验对齐参照实现**：`TaskTalent#setDefaultConcurrency` 与 `AiConfig#setConcurrency` 均对负数抛 `IllegalArgumentException`（`ContextCompressionInterceptor#setDefaultContextLength` 即此风格），配错立即暴露而非被静默吞掉
  * **提示词与文档同步**：`getInstruction` 第 1 条不插入任何具体数字（生效上限是「模型配置或默认值」，提示词无法预先得知），第 4 条去掉硬编码的"不超 10 个"改为"规模与体量"引导；`TOOL_MULTITASK` description 与 instruction 由"自动分批并行执行"改为"自动排队"（实现是提交前排队、完成即释放，**没有分批屏障**）；`<multitask_advice>` 三处改为可操作的真实配置名（`concurrency` / `subagentDefaultConcurrency`）；README §6.5/§8.1 同步重写
  * **有意不做**：未设"任务数绝对上限仍报错"——会把核心收益（消除报错→重试往返）重新抵掉，上下文防护交给提示词软引导。跨会话约束由模型级信号量本身覆盖（`modelSlots` 是 `TaskTalent` 字段，而 `TaskTalent` 是 `HarnessEngine` 单例字段，天然全引擎共享，不依赖已删除的全局信号量）
  * **调度复审后的五项修正**（第二轮）:
    1. **第二轮分派改为轮询而非按下标阻塞**：原实现第一轮非阻塞、第二轮 `for (int i : waiting)` 逐个阻塞。若 `waiting=[0(A 已满), 1(B 空闲)]`，下标 1 必须等下标 0 拿到 A 的许可才能提交，B 的空闲配额被白白晾着，整批吞吐退化为 A 的串行度——正是第一轮要消除的缺陷从「第二轮」漏了进来。改为每轮对所有待分派任务 `tryAcquire`，只剩一个都不成功才睡 200ms；抽成纯函数 `tryAcquireReady(waiting, plans)` 便于锁死
    2. **`modelSlots` 改按模型名分组**：原键为 `ChatConfig` 实例（未重写 equals/hashCode）。而 `HarnessOptions#addModel` 以 `getNameOrModel()` 为 Map 键覆盖，因此每次热更新都会遗留一个只剩 `modelSlots` 引用的旧实例，长驻进程里无界增长。改为键取 `getNameOrModel()`（无名配置退回实例身份，避免所有无名配置被误合并）——在已注册模型上两种分组等价
    3. **上限变更改为原地调整而非重建信号量**：原实现在 limit 变化时新建信号量，旧信号量交给在跑任务自然回收。但「旧的在跑 5 + 新的立即放出 2」=瞬时并发 7，恰好是最容易触发上游 429 的时刻。改为 `AdjustableSemaphore` + `reducePermits` 落入「欠账」：调低后要等在跑数降到新上限以下才恢复放行，绝不瞬时超额
    4. **预解析产物一路下行**：新增 `TaskPlan{op, agentDefinition, modelName, slot, cap}`，执行时不再重新走 `getModelOrDef`。原实现预解析与执行各调一次，而「未设 `defaultModel` 且有多个启用模型」时 fallback 走 `ConcurrentHashMap` 迭代取首个，并发修改下不保证稳定 → 限流用的模型可能不是真正跑的那个；顺带消除了每任务三次 `getAgent` 解析
    5. **`task` 单任务也取槽**：原实现只给 `multitask` 限流。信号量是引擎级、跨会话共享的，5 个会话各跑一个 `task` 时同一模型上就是 5 并发，`concurrency=2` 形同虚设。现单元任务走同一 `runTask` 前先取槽（单任务串行，无损并行度）
  * **其余健壮性**：`tasks` 含 null 元素时返回占位错误而非 NPE（并保证下标不错位）；聚合循环遇中断后不再继续 join 剩余项（否则连续抛同一异常刷屏）；debug 日志由「只打印默认值」改为同时打印各任务的实际生效上限；`HarnessOptions#setSubagentDefaultConcurrency` 补负数校验（与 `TaskTalent#setDefaultConcurrency`、`AiConfig#setConcurrency` 一致）；`shutdown()` 的 javadoc 更正——串行快路径不经过执行器，故关闭后并非「所有 multitask 都无法提交」
  * 测试：`TaskTalentParallelCapTest` 17 例（`resolveConcurrency` 锁定模型优先/未设置回退/模型低于默认值/**0 恒回退到缺省 5（返回值不为 0）**/负数防御/**setter 归一（0→5、超上界→10、区间内原样、负数抛异常且不改动已有值）**；`isSerialOnlyByCaps` 锁定"全部而非任一"、"模型未配置+默认 1 必须串行"、不可解析模型、空集合；`AiConfig#concurrency` 缺省 0 / 负数抛异常 / 只读透传 / `toString`）+ `TaskTalentSlotTest` 16 例（**模型未配置时按默认值限流**、**默认 0 回退到缺省 5 并照常建信号量（不再有“不限=不建信号量”形态）**、**超上界按 10 处理且真实作用在信号量上**、**模型配置覆盖默认值**、复用与**上限变更原地调整**、**运行期调低不得瞬时超额**、**同名模型不因替换配置而多出槽位**、**无名配置不得被合并**、运行中许可被占不得重建、中断退出不多许可、释放幂等含 null、执行器真并发、关闭后拒绝提交）+ `TaskTalentSchedulingTest` 9 例（**第二轮谁有空配额就提交谁**、null 槽位视为就绪、按下标写入、提交失败归还许可、未启动原因区分、提示词分条、旧名桥接、负数拦截）+ `HarnessOptionsAccessorTest` 16 例（含 **0 归一为缺省、超上界靠上界、负数报错**）、`HarnessEngineAccessorTest` 22 例、`HarnessEngineBuilderTest` 13 例、`TaskTalentGuardTest` 3 例，共 46 例定向全绿；harness 全量 784 例 29F+3E，失败全部集中于 `ToolStrategyDefaultsTest`/`BashToolStrategyTest`/`WebToolStrategyTest`/`WriteToolStrategyTest` 四个类，与既有基线一致
  * 过程坑（4 个，均属"静默失效"类）：
    1. `ThreadPoolExecutor(core=0, max=∞, 无界队列)` 配 `LinkedBlockingQueue` 时**并发恒为 1**：JDK 只在 `workerCount < corePoolSize` 才新建线程，`core=0` 永不成立，后续任务全部入队排队且不报错。必须用 `SynchronousQueue`（Cached 语义），已有用例锁死
    2. 用 `availablePermits()` 反推"上限是否变了"是错的：任务运行中许可被占，可用数必然小于上限，会被误判为上限变更而反复重建信号量，限流直接失效。改为 `ModelSlot{limit, semaphore}` 显式携带上限；上限变更走 `AdjustableSemaphore#reducePermits` 原地调整（不能用 `compute` 换新实例（会瞬时超额）
    3. `HarnessOptionsAccessorTest`/`HarnessEngineAccessorTest` 各有一次**反射遍历所有 `set*` 方法**的巡检，新 setter 不列入白名单就直接失败。改名/加 setter 时必须同步两处白名单（`@Deprecated` 桥接也是公开 setter，同样要列）
    4. 改 `solon-ai-core` 后必须先 `mvn install -pl solon-ai-core`，否则 harness 测试跑的是本地仓库旧 jar，会出现「测试明明写了负数校验却没抛异常」的假失败
    5. `reducePermits` 降容后 `availablePermits()` 会变负数，`tryAcquire` 自然失败即「欠账回补」；但这意味着**降容后的可用额度不等于 `新上限 - 在跑数`**，断言时必须用 `tryAcquire` 的真假而非直接读数

* **TaskTalent 复审第三轮：HITL 装配边界 + 取消传播 + 输出外壳安全**（不改调度内核；核已收敛为「许可先行 + 私有执行器 + 两轮分派 + 预解析透传」）:
  * **P1（结构隐患，用户明确要求）：HITL 只允许主代理使用**。`AgentFactory.toolAddDo` 的 `case "hitl"` 原对主/子代理一视同仁，而 `TOOL_ALL_FULL("**")` 含 `hitl`，子代理配 `**` 就会意外继承。子代理一旦命中审批规则即挂起，而父代理此刻正阻塞在 `aggregate` 里等它 → 无人能决策、互等不可解（外部只看到「子代理未正常完成：未收到正常响应」这类无线索报错）。现改为**装配期直接不挂**（`AgentFactory#hitlAllowed(metadata)` = `metadata.isPrimary()`，包级可见便于测试），而不是运行期拒绝——后者仍会白烧一轮 LLM 才拿到一句 ERROR
  * **P2：`aggregate` 不能用 `join()` 死等**。`CompletableFuture#join` 不可中断（只记录 interrupt 标志、不会抛），用户按停止后父线程会逐个等到底；原 `aborted` 分支的前提（“join 会立刻再次抛同样异常”）实际不成立，几乎永不触发。改为 `get(SLOT_POLL_MS)` 轮询：每轮检查 `sink.isCancelled()`，一旦取消/中断就 `interruptWorkers()` 中断所有在跑的子任务线程并不再等待后续任务（否则卡在 10 分钟 `bash` 里的子任务会把父代理拖到工具跑完）。工作线程登记在 `MultitaskContext.workers`，执行前先 `Thread.interrupted()` 清残留标志（池线程复用时上一个任务的标志会误伤下一个）
  * **P3：取槽排队可观测性**。`acquirePolling` / `dispatchWaiting` 原来全是静默轮询，被低配额模型堵住时外部唯一表现是「前端没有新内容」（无进展看门狗已移除）。现超过 `SLOT_WARN_MS=30s` 打一条 WARN（每个等待过程只打一次），带模型名/剩余任务数。**有意不做硬超时**——会误杀长任务；也**有意不推事件**——新增事件类型需宿主三端同步，超出本类职责
  * **P4：结果外壳三处注入面 + 无长度上限**（`formatTaskResp`）：①`]]>` 未处理会提前闭合 CDATA，子代理输出含 XML/diff 就会把外壳打散（新增 `toCdata`：**先截断再替换**——顺序反过来会把转义产物切成半个，同时截断边界避开代理对）②`description`/`agent_name` 同样来自模型输出，`<`/`&` 会破坏外层标签（新增 `escapeXml`）③补 `MAX_RESULT_CHARS=20000` 上限，否则单个超长输出就能灌爆父会话上下文并触发压缩
  * **P5：未知子代理的错误绕过了外壳**。`preparePlan` 原返回 `invalidReason=null`，于是 `runTask` 直接返回裸字符串，在 `multitask` 里变成「一条没有 `<index>`/`<description>` 的裸行」混在 `<multitask_results>` 中，模型对位解析易错位；`task` 与 `multitask` 两种形态也不一致。现统一走 `unknownAgentReason` + `formatTaskResp`
  * **P6：清理**：删除不可达的 `errRef` 检查（`doOnError` 只观察不吞信号，`blockLast()` 会先抛）与无调用者的 `ModelSlot#getLimit`；修正 `isSerialOnlyByCaps` 空分支的失真注释（“全部是未知子代理”不成立——那种情况会产生 `cap=CAP_NONE` 条目而非空列表）；`slotKeyOf` 补前提说明（按 `getNameOrModel()` 分组等价的前提是 `HarnessOptions#addModel` 也以该值为键）
  * 测试：新增 `TaskTalentResultFormatTest` 4 例（`]]>` 不得闭合 CDATA（**用极简 CDATA 解析器还原出原文**，比数终止符个数可靠）、结构字段转义、超长截断、正常内容逐字保留）+ `AgentFactoryHitlBoundaryTest` 3 例（主代理可用 / 子代理拒绝 / 配 `**` 也拒绝）+ `TaskTalentSchedulingTest` 增至 10 例（新增 `invalidReasonIsWrappedInEnvelope`）；定向 74 例全绿；harness 全量 **795 例 29F+3E**，失败类与既有基线逐项一致（全在 `ToolStrategyDefaultsTest`/`BashToolStrategyTest`/`WebToolStrategyTest`/`WriteToolStrategyTest` 权限域）
  * 已知但本轮未改：子代理内 `Prompt.attrs` 的 `ATTR_SESSIONID` 是子会话 id，而 `toolContextPut(ATTR_SESSIONID)` 是父会话 id，两者不一致（子代理工具调用归属父会话）。改它会连带影响权限上下文与会话级状态，属独立决策，未纳入本轮
  
  * **TaskTalent 精简轮：砍掉为测试/日志/极端场景而生的 5 处抽象**（不改调度模型，只减复杂度）:
  * 背景：前四轮修正累计把文件从 740 行推到 1329 行（+80%），其中 253 行是注释、93 个定向用例里大量反射锁住私有方法签名。复审判定：6 块核心修正（预解析透传、许可先行、取消轮询、未知 agent 隔离、结果外壳安全、并发契约归一）必须留，另有 5 处属过度设计，本轮清除
  * **砍 1：`interruptWorkers` 与工作线程登记**。收益只是「取消后不必等当前长工具跑完」，风险却是新开一个破坏面——中断正在写文件或读 HTTP 响应的线程可能留下半截文件或让连接进入不可用状态。而「不中断、只是不再等待」本身安全：任务在后台自然结束并自行归还许可，不泄漏。删 `MultitaskContext.workers`/`registerWorker`/`unregisterWorker`/`interruptWorkers` 与执行前的 `Thread.interrupted()` 清理
  * **砍 2：`dispatchParallel` 的双轮实现合并为一轮**。原「第一轮非阻塞抢槽 + `dispatchWaiting` 第二轮轮询」两段重复了「取消检查 → tryAcquire → 提交 → 取不到继续」。合并后：先扫一遍（取消的填占位、能抢到的直接提交、其余进 `waiting`），再进轮询循环；首次轮询不睡，与原第二轮语义等价。少一个方法、少一处重复分支
  * **砍 3：`effectiveCapsOf` 内联**。它唯一用途是把 `TaskPlan` 列表拍平成 `List<Integer>` 再交给 `isSerialOnlyByCaps` 判断，纯粹为「可单独测试」而存在。改为 `isSerialOnly(List<TaskPlan>)` 直接判断，省一次分配与一层抽象
  * **砍 4：`describeCap`/`describeCaps`/`waitingModelNames` 三个日志辅助方法删除**。它们只服务于两条 DEBUG 与一条 WARN 的文案拼接（其中 `describeCaps` 还要把列表格式化成 `[1:5, 2:5]`）。DEBUG 缩为一行任务数，WARN 只打排队数量与通用成因
  * **砍 5：`AdjustableSemaphore` 的欠账机制退回「换新信号量」**。原机制（`reducePermits` 打成负数形成欠账）正确，但 40 行只换「运行期显式改上限时不瞬时超额」这一个场景；而运行期改上限本身极罕见。改为 `ModelSlot{limit, semaphore}` + `compute` 换实例，约 10 行，代价是变更瞬间可能短暂超额——已写入注释
  * **注释压缩**：把「为什么不用 `RunUtil.io`」「为什么不限并发」「不设超时的代价」等长论证从字段 javadoc 收回本文件的段落与类注释，字段注释只留结论。注释行 253 → 178，文件 1329 → **1000 行（-25%）**，其中代码行 482 → 669（增量即前四轮 6 块核心修正的成本）
  * **测试同步瘦身**（定向 93 → 88 例，去反射化）：`TaskTalentParallelCapTest` 17→16（`isSerialOnly` 改吃 `TaskPlan`，删 `effectiveCapsOf` 相关）；`TaskTalentSlotTest` 16→15（删两个随 `AdjustableSemaphore` 报废的「原地调整/不瞬时超额」用例，换 `capChangeTakesEffect`；`finalConcurrency`/`semaphoreOf`/`acquirePolling`/`releaseQuietly` 降为包级可见，测试不再用反射样板）；`TaskTalentSchedulingTest` 12→9（删 `notStartedReason` 直调反射与「反射伪造 `TaskPlan` 再断言外壳」的假绿用例——后者已被真实链路的 `unknownAgentOnlyFailsItsOwnTask` 覆盖；`tryAcquireReady` 合并为一个含 null 槽位的用例）；`TaskTalentResultFormatTest` 4 例改为直接调用，删反射
  * **保留的取舍**：`tryAcquireReady` 仍作为包级纯函数保留（「不能按下标顺序阻塞」是核心且微妙的语义，值得一个用例锁住）；`TaskPlan` 仍是包级内部结构（分派与执行共用同一份预解析结果，是 P1 类修正的载体）
  * **副产品：修掉一处文案漂移（此前引入的静默回归）**。`SingleTaskOp.prompt` 的 `@Param` 描述在工作副本里被改成「任务所需的**全部**背景信息」，而 `JsonSchemaTest.case1` 期望（也是 HEAD 原文）是「**所有**背景信息」→ 全量首跑 30F，多出的一条正来自这里。已恢复原文，基线回到 29F+3E
  * 验证：定向 88 例全绿（`TaskTalentSchedulingTest 9` + `TaskTalentSlotTest 15` + `TaskTalentParallelCapTest 16` + `TaskTalentResultFormatTest 4` + `AgentFactoryHitlBoundaryTest 3` + `TaskTalentGuardTest 3` + `HarnessOptionsAccessorTest 16` + `HarnessEngineAccessorTest 22`）；harness 全量 **792 例 29F+3E**，失败全在 `ToolStrategyDefaultsTest`/`WebToolStrategyTest`/`WriteToolStrategyTest`/`BashToolStrategyTest` 权限策略域，与既有基线逐项一致（用例总数 797→792 即本轮删掉的 5 例）


* **TaskTalent 复审第四轮：无超时约束（子任务可能跑几天）+ 两个契约执行不到底的洞**（不改调度内核）:
  * **新增硬约束：本类不设任何超时**。子任务可能是长时间构建、等外部系统，**可能跑几天**，因此取槽 / 待分派 / 聚合三条等待路径只有轮询节拍、没有截止时间，只会因「主流程取消」或「线程中断」提前结束（已写进类注释）。配套代价：一个跑几天的子任务会长期占着 1 个槽位，因此两条排队 WARN 补上「或已有长任务占着槽位」这个更难排查的成因
  * **P0（回归，本轮引入后自查发现）：`aggregate` 被中断后是零间隔死循环**。`InterruptedException` 分支只做了 `Thread.currentThread().interrupt()` 而**没有 break**，下一轮 `isCancelled` 为 false、`get(200ms)` 因中断标志已置回而立即再抛 → 1 秒 73 万次迭代、线程永不返回，外部表现为「CPU 打满、任务永不结束」。可达路径：`dispatchWaiting` 的 `sleep` 被中断后置回标志并返回，进入 `aggregate` 时标志已是 set。修法：该分支补 `break`（连同上一轮的取消轮询，这条路径此前**零测试覆盖**）
  * **P1：未知子代理会炸掉整批**。`AgentManager#getAgent` 对未知名称是**抛异常**而不是返回 null（`AgentDefinitionTest` 反向锁死了这一点），而 `preparePlan` 直接调它、外层无 try/catch → 模型幻觉一个 agent 名就让整个 `multitask` 抛 `IllegalArgumentException`，把「一条坏、其余好」退化成全废；连带 `agentDefinition == null` 的四处分支全成死代码（含上一轮 P5 修的消费端），`invalidReasonIsWrappedInEnvelope` 因反射伪造 `TaskPlan` 而假绿。现改为 `hasAgent(name) ? getAgent(name) : null`，并对「定义存在但加载失败」单独 catch（只废这一条）
  * **P2：`multitask` 无条数上限，`MAX_RESULT_CHARS` 的注释靠一个不存在的约束**（原写「固定最多 5 个」）。**有意不加条数硬上限**——条数是模型编排意图的一部分，压成固定数字只会逼它多轮往返，且 `instructionNoLongerMentionsBatchSplit` 已明确拿掉了数字型批大小建议；改为把注释改正：单条上限的意义是「任何一条都不能独占上下文」，总量随条数线性增长，由父会话压缩机制兜住
  * **P3：连带小项**：`aggregate` 的 `f==null` 裸行改为带外壳（传递 `plans` 取 `op`）；串行快路径 `runTask` 包 try/catch，保证「先算后 set」不会因抛出而留下 null 下标；`preparePlan` 只解析一次生效上限（`defaultConcurrency` 是 volatile，分两次读会出现「信号量按新值、cap 记旧值」的错配，`semaphoreOf` 改为接收已解析的 limit）；删除无调用者的 `ModelSlot#getLimit`
  * 测试：新增 2 例（`TaskTalentSchedulingTest` 10→12）—— `aggregateReturnsWhenInterrupted`（`assertTimeoutPreemptively(2s)` + 永不完成的 future，锁住「无法靠任务自己结束来逃出循环」）与 `unknownAgentOnlyFailsItsOwnTask`（**走真实 `multitask` 链路**，两个未知 agent 必须各自出外壳，不伪造 `TaskPlan`）；两者均**做过变异验证**：回退 `break` → 用例超时失败；回退 `hasAgent` → 用例因丢原因失败。`TaskTalentSlotTest` 的反射 helper 跟着 `semaphoreOf(ChatConfig,int)` 调整；定向 93 例全绿；harness 全量 **797 例 29F+3E**，与既有基线逐项一致
  * 坑：`edit` 工具 `old_StrStartLine` 必须指向 `old_str` **首行**（本例四处偏移一行就被预检拦下）；改 `MAX_RESULT_CHARS` 注释时缩进被改成 1 空格（`old_str` 首行漏了缩进），事后已修——这类“编译过但格式坏”的问题只能靠 review diff 发现

* **goal 域宿主调用点收敛：宿主内部 41 处 `getGoalEngine()`/`getGoalScheduler()` 清零，全部改走 `GoalExtension` 门面**（P0/P1 落地，仍是宿主内部可维护性改造——Web 端点路径/参数/返回体/状态码一字未改，终端用户无感知）:
  * 背景：上一轮已把「装配」收敛为一个类，但宿主内部仍有 41 处直接取内部实例、6 个文件。其中约 10 组是**手工复刻门面已封装好的配对动作**（`pause`+`unschedule`、`resume`+`schedule(true)`、`clear`+`unschedule`）——同一语义两套写法并存，是「改了状态忘了挂/摘 job 导致 goal job 泄漏」的唯一来源
  * **门面补 8 个方法**：`stateOf(id)` / `sessionOf(id)` / `find(id, taskId)`（任务行分流三连收敛）/ `running(session|id)` / `attach(...)`（只建不挂）/ `commit(...)` / `hasScheduler()` / `resumeScheduleOnly(id)` / `scheduleNow(id)` / `runRoundAsync(session)` / `updateAndReschedule(...)`；`clear()` 内部改为**先 unschedule 再 clear**（消除「清完状态但 job 还在」的窗口期）
  * **两个必须按住的语义（否则会真实改变功能）**：
    * `resume()` 内含 `schedule(runNow=true)`，而服务重启/工作区恢复原用 `schedule(id, false)`——「静默挂上、等下一轮」。故新增 `resumeScheduleOnly(id)` 专用于 `WebQueueDispatcher.recoverSessionGoals`；若用 `resume()` 顶替，每次重启会对每个活跃 goal 立即多跑一轮
    * 改 goal 配置是 `pause → 宿主 interruptSession → 等轮次停(200×20ms) → update → resume`，中断与等待是宿主运行时职责（依赖注入进来的中断端口），**不能整段吞进门面**。`updateAndReschedule` 只覆盖后半段，且保留「`maxDurationMs != null` 才 set+commit」的条件分支（否则「只改 prompt 不动时长」会把时长清空）
  * **宿主改造**（6 文件，41→0）：`LoopWebController` 29 处（pause/resume/clear/status/remove/toggle/trigger/update 分流）、`DesktopGoalController`、`WsGate`、`DesktopInputRouter`、`DesktopGoalBroadcaster`、`WebQueueDispatcher`、`SessionWebController`；`WsGate`/`DesktopGoalController` 字段由 `GoalEngine`+`GoalScheduler` 收敛为单个 `GoalExtension`，`Configurator` 同步；`WorkspaceContext` 新增 `getGoalExtension()` 与 `attachGoal(GoalExtension, bridge)` 重载（旧三参重载保留）
  * **门面不代管的边界**：`getExecutionBridge()` 保留——WsGate/CliShell 还要往桥上注册 TaskHandler/BusyChecker，桥是 loop 与 goal 共享的横切设施；`GoalState` 作为值对象仍在宿主手中传递（`gs.setXxx` + `commit`）。实际消除的是两个**服务类**的实例依赖
  * 测试：`GoalExtensionTest` 由 5 例扩到 9 例（`find` 三态返回 null / `find` 命中返回同一实例 / `updateAndReschedule` 不因 null 清空时长与 token / `clear` 后 `state`/`stateOf`/`hasGoal` 全为 null / 无调度器时 `resumeScheduleOnly`+`scheduleNow`+`unschedule` 全 no-op 不 NPE）；新增 `GoalLoopEndpointTest` 12 例锁死 pause/resume/clear/toggle/trigger 五端点行为（分流命中、400/404 分支、以及「调度配对只由门面负责、控制器不再直接操作调度器」）
  * 验证：`GoalExtensionTest` 9 例、`GoalLoopEndpointTest` 12 例全绿；`soloncode-cli` 全量 **1216 例 4 失败**（= 基线 1204 + 新增 12），失败类与既有基线逐项一致（ImageReply 异常文案 / AgentSettings 400 / Mount 安装目标数 / Skillhub 联网）；harness 全量 736 例 29F+3E 全在 ToolStrategy/Hitl 域，与 goal 域零交集
  * 文档：README §5.1 运行期门面一览重写（补齐 13 个方法与 3 个易错点：`resume` 的 runNow 语义、改配置只吞后半段、`clear` 先摘后清）；第十三章新增坑 16（恢复期别用 `resume`）与坑 17（改配置别整段交给门面）
  * 未做（有意）：`getGoalEngine()`/`getGoalScheduler()` 在 harness 侧保留不标 `@Deprecated`——harness 是多宿主复用的库，其他宿主可能自建 `GoalEngine` 做二次集成；`soloncode-cli` 先清零，观测一两个版本后再决定库侧去留

* **goal / queue / steer 三命令：宿主侧与 solon-ai-harness 的交互体验简化——装配与操作各收敛到一个类**（响应「宿主侧接线太复杂」的反馈，功能效果与 attrs 契约零变化）:
  * 口径：本次改进的是**宿主侧与 solon-ai-harness 的交互面**（宿主开发者要认识并 new 的类、要写的装配样板、运行期要操作的对象），**不是终端用户交互**——`/goal`、`/queue`、`/steer` 三个命令的语法、回执文案、Web/桌面端点与 UI 行为一字未改，终端用户无感知
  * 问题：README 第五章的三个状态型命令，宿主接触面分别是 5 个类（`ExecutionBridge`+`GoalEngine`+`GoalScheduler`+`GoalExtension`+`GoalCommand`，25 行装配）、2 个静态工具类（`QueueHelper`）、以及「每请求 new `SteerInterceptor` + `interceptorAdd`」的样板。依赖链是刚性的，宿主真正能定的只有 workspaceId 与一两个 lambda，其余全是可推导的组装步骤
  * **`GoalExtension` 升级为 builder 一键装配**：`GoalExtension.builder(workspaceId).taskExecutor(...).busyChecker(...).budget(...).stagnation(...).maxErrors(...).validatorEnabled(...).workspace(...).bridge(...).scheduler(...).commandEnabled(...).listener(...).build()` —— `initialize(engine)` 内依次建 `ExecutionBridge`→`GoalEngine`→`GoalScheduler`→注册 `/goal` 命令→预注册 listener，`configure` 仍挂 `GoalTalent` 到主代理。`taskExecutor` 缺省内置 README 原模板（`engine.handle` + `call()`），`busyChecker` 缺省恒 false，其余全有缺省值。既有双参构造保留并标 `@Deprecated`
  * **`QueueExtension` 新建**：`addExtension` 内幂等注册 `/queue` 命令 + 提供完整操作门面（`enqueue`/`drainOne` 两态/`ack`/`view`/`pendingSize`/`cancelItem`/`promote`/`cancelPending`/`hasPersistedItems`/`importItems`），内部中转两个静态类。**保留「不自动 drain」决策**：Web 端 ack 跨越订阅生命周期，自动化会遮住宿主必须显式掌握的时机
  * **`SteerExtension` 新建**：`addExtension` 内幂等注册 `/steer` 命令 + `configure` 把拦截器挂为主代理**默认拦截器**。顺序安全性已核验：`LinkedHashMap<Class, RankEntity>` 按 Class 去重，`AgentFactory` 先挂压缩/StopLoop、后轮到 extension 的 configure，天然排在上下文压缩之后；`ReActOptions.copy()` 的 `interceptors.putAll` 使请求级同 Class 挂载覆盖默认实例（不双份），故 Web 多工作区仍可保留请求级挂载。端口用 `Supplier` 惰性解析（默认工作区可能在入口绑定之前创建）
  * **builder 只给 goal**：goal 有 8 个可配点才值得 builder；queue 零旋钮、steer 一个端口，给它们套 builder 是伪一致性。README 坑 #15 记录此判断
  * 三命令极简宿主终态：`addExtension(GoalExtension.builder(ws).taskExecutor(r).build())` / `addExtension(new QueueExtension())` / `addExtension(new SteerExtension<>(() -> port))`，运行期操作也只在各自那一个实例上
  * 测试：`GoalExtensionTest`(5) / `QueueExtensionTest`(5) / `SteerExtensionTest`(5) 共 15 例全绿，覆盖全链装配、共享桥复用、listener 预注册、命令幂等与关闭、门面语义透传（同步/异步 drain 差异）、惰性端口、旧构造兼容；`scheduler(null)` 三态语义修复（缺省新建 / 自备 / 显式关闭，后者供无 Solon 容器环境）
  * 宿主迁移（`soloncode-cli`）：`WorkspaceManager` 的 goal 四件套改 builder、`/steer`+`/queue` 命令注册改 Extension（WebStreamBuilder 请求级挂载保留，多 wsContext 现实约束）；compile 通过，全量 1204 例 4 失败均为既有基线（Mount/AgentSettings/ImageReply/Skillhub 联网类）
  * 文档：README 第五章表格与 5.1/5.2/5.3 装配段重写、第十三章坑位 11-15 同步

* **修复三处真实缺陷（P0/P1/P2）+ 两处清理（P3/P4）**（按用户"重新确认后再修"的要求，上一轮曾误报 4 项，此处仅保留复核后成立者）:
  * **P0 `HarnessEngine.setMemoryEnabled` 空指针**：`memoryTalent` 只在注入 `memoryProvider` 时构建（否则显式置 null），而该方法无守卫直接解引用；相邻的 `setMemoryRelevanceCount` / `setMemoryPriorityCount` / `setMemorySummaryLength` 三个邻居都有判空。未注入 provider 的宿主调一次开关即 NPE。已补守卫（配置值仍写入 options，无 talent 可开关属正常状态）
  * **P1 `getDefaultModelConfig` 回落不校验 `enabled`**：显式名路径（`getModelOrNil` / `getModelOrDef` 的命中分支）都检查 `isEnabled()`，回落路径却直接返回 `models.get(defaultModel)` 或 `values().iterator().next()`。后果：模型被禁用但仍是 `defaultModel` 或 Map 首项时，`getModelOrDefInstance` 会真的构建并调用已禁用模型；`/model` 的 active 标记还会指向被 `formatModels` 过滤掉的模型。已改为两条路径都跳过 disabled、全禁用时返回 null；**连带** `AgentFactory.create` 对 null 模型给出明确 `IllegalStateException`（此前会落到 `ReActAgentConfig` 的 `chatModel is required`，无线索）
  * **P2 `PermissionContext` null 契约不一致**：`addRule(null)` 把 null 真加进列表（`PermissionEngine` 遍历 `rule.toolName()` 会 NPE）；`addRules/replaceRules/addDirectories/removeDirectories/Builder.rules/Builder.additionalDirs` 直接 NPE。统一为「null 列表 = 无操作、null 单元素 = 忽略」，与 `HarnessEngine.addPermissionRule` 的上游守卫同语义；构造器额外剔除 null 元素做兜底
  * **P3 `GoalState.fromONode` 空状态 NPE**：`fromMap` 缺 status 规范地返回 null，`fromONode` 却 `Status.valueOf(null)` 抛 NPE。已对齐为返回 null；`toONode` 补 javadoc 说明运行时连续性字段（lastFingerprint/lastErrorType/lastErrorSummary/consecutiveSameTypeErrors）**按设计不持久化**——它们描述单一进程内的执行连续性，跨重启保留会误判去重与错误熔断（与 `toMap` 同一取舍）
  * **P4 清理**：`HarnessOptions.workspace` 去掉 `volatile`（唯一写入在构造器，`volatile` 是死修饰）；删除 `getHarnessDownload()`（`HarnessEngine` + `HarnessOptions` 两侧，全仓零消费者，CLI 那份是 `AgentFlags` 自己的同名字段）
  * **已撤回的上一轮误报**（记录以免再次误判）：`recordError` 不递增 `consecutiveErrors` 是有意分工（同类型熔断 vs 总数熔断由 `GoalEngine:612/622` 分别取用）；`toONode` 丢 4 字段是有意设计；`Builder.mountAdd(null)` 与 `HarnessEngine.addMount(null)` 行为一致（都抛 `IllegalArgumentException`）；`InMemoryAgentSession.of` 每次新实例是工厂方法正常语义
  * 测试：`PermissionContextTest` 4 处「锁定 NPE 现状」断言翻转为「锁定正确行为」+ 新增 `constructorStripsNullElements`；`HarnessEngineAccessorTest` 新增 `setMemoryEnabledToleratesMissingMemoryProvider` / `fallbackModelSkipsDisabledConfigs` / `disabledDefaultModelFallsBackToNextEnabled`；`GoalStateTest` 新增 `onodeRoundTripToleratesNullOrMissingStatus` 并改写 2 处旧注释；`HarnessOptionsAccessorTest` 的 disabled 模型断言翻转
  * 验证：受影响 4 类 81 例全绿；harness 全量 594 例 20F+8E，失败类集合与基线逐项一致（AgentDefault / AgentFactoryCommandSession / BashToolStrategy / HarnessAgentMount / HarnessExtensionLifecycle / HarnessSkillCatalog / WebToolStrategy / WriteToolStrategy，均为既有 `sessionProvider` NPE 类）

* **HITL 决策动作枚举化：`HarnessInput.hitl(String, String)` → `hitl(HITLAction, String)`**（新增 `react/intercept/HITLAction`）:
  * 问题：字符串动作约束性太弱——引擎侧 `"approve".equals(action)` 把一切非精确匹配值（含 `"Approve"`、拼写错误）静默判为拒绝执行；且 SKIP 在领域里存在（`HITL.skip` / `HITLDecision.ACTION_SKIP`）却从 `handle` 入口永远表达不出，只能二值
  * 枚举：`APPROVE / REJECT / SKIP` 三值，含边界工厂 `of(String)`——空值返回 null（非决策），小写三值精确匹配，脏值抛 `IllegalArgumentException`；**不做大小写容错**（容错等于把「静默当拒绝」这类 bug 放回来）。放在 `react/intercept` 与 `HITL`/`HITLDecision`/`HITLTask` 同包，依赖方向正确（harness → agent）
  * 引擎：`applyHitlDecision` 改三值 switch，SKIP 分支接 `HITL.skip(session, task[, comment])`（目标方法本就存在）；`isHitlDecision()` 简化为 `hitlAction != null`（枚举时代无空字符串）
  * **转换点按约定放宿主侧**（协议层签名 `WorkspaceRuntimePort`/`WebGate` 保持 String 不动）：Web 在 `WebChatInputHandler`（`.hitl(HITLAction.of(hitlAction), hitlCallId)`，脏值落入既有 try-catch），桌面在 `DesktopInputRouter`（同模式，异常由既有 catch 回 socket 错误），CLI 无线上字符串直接写枚举
  * 顺带修复 CLI 外部预置决策 bug：`existing.isRejected() ? "reject" : "approve"` 漏了 `isSkipped()`，外部预置的 skip 会被升格为 approve 重新提交覆盖原决策；改三态透传
  * 不用 `HITLDecision` 直接作参数的理由：它是管线内可变实体（携 modifiedArgs/alwaysAllow 等 applyHitlDecision 不消费的字段，类型承诺会静默无效）、无 callId 位置、其 action 本身是 int 常量而非枚举
  * 测试：`HarnessInputTest` 新增 `hitlActionOfParsesProtocolValues`（空值/三值/脏值抛异常）；既有 10 处调用点迁移，`hitl("", null)` 用例随空字符串语义消失而删除
  * 行为变化（仅脏值）：非法 action 从「引擎内静默按拒绝执行」变为「宿主解析点显式报错」；合法路径零变化
  * 验证：harness 定向 3 类 19 例全绿；全量 379 例 20F+8E 与基线一致（20F/8E 为 WriteToolStrategyTest 等既有基线）；`soloncode-cli` compile 通过、全量 1204 例 2 失败与基线完全相同

* **HITL 决策与续流合并为一次 `handle`（`InputOutcome.Kind.HITL` 保留为批量审批形态）**：
  * 问题：单卡同步审批的宿主（CLI）必须发两次 `handle`——先 `.hitl(...)` 拿到不携带请求的 `HITL`，再 `.resume(true)` 拿 `TASK`；而 `HITL` 分支原本无条件 `return`，使 `.hitl(...)` 与 `.resume(true)` 不能合用。CLI 因此绕开了唯一入口，直接调 `HITL.approve/reject(session, task)`，连带丢掉 `hitlOutput`（工具返回改写）与引擎的 callId 精确定位能力
  * 改为：HITL 分支写入决策后**自检三项**，全部成立才同一次调用完成续流并返回 `Kind.TASK`，任一不满足返回 `Kind.HITL`。三个条件：① `applyHitlDecision` 返回 true（决策真的命中挂起任务，改为返回 `boolean`）；② 新增 `pendingHitlCount(session)` == 0（本批已齐备）；③ 宿主显式传 `resume`。未新增引擎入口，只在既有分支内增加组合出口
  * 为何必须自检而非仅看 `isResume()`：①无命中时续流会因空提示词回落 `trace.getOriginalPrompt()`，把上一轮问题原样重跑（过期审批卡场景）；②批挂起时过早 resume 会把未决卡一起带走。仅有 `isResume()` 判断会把这两个问题留给宿主各写一份
  * **批量审批语义（旧记录已过时，以本条为准）**：三端统一 `hitl(...).resume(true)` 一次调用，宿主只 `switch (kind)`；不再需要宿主自持 `allHitlDecided`。「无挂起」视为已齐备，故 `resume(true)` 单独使用（宿主批量写完全部决策后统一恢复）的既有语义不变
  * `InputOutcome` 新增三个事实读取（不改 kind 推导，零兼容风险）：`isHitlInput()`、`isHitlApplied()`（null = 非审批输入，false = 过期审批卡）、`getPendingHitlCount()`；新增 `task(..., boolean hitlApplied)` 与 `hitl(agent, model, applied, pending)` 四参工厂（两参版合并后无调用者，已删除——留着会让 `isHitlApplied()` 返回 null 而非 false，宿主 `== FALSE` 判断被静默跳过）；`Kind.HITL` 与 `HarnessInput.resume` 的 javadoc 补全三种进入方式与「resume 只表达意愿、不保证续流」
  * CLI 侧：`performAgentTask` 的 HITL 循环改为 `handleHitlRound(session, agentName, modelName)`，内部走 `engine.handle(HarnessInput.of(session, null).hitl(action, task.getCallUuid()).resume(true).selectedModel(...).selectedAgent(...))`，返回必含请求的 `InputOutcome` 供下一轮订阅。决策按 `task.getCallUuid()` 精确指定（原实现提交 task 对象，语义相同但多依赖了一轮 `getPendingTask` 定位）；已存在的决策（外部预置 / 上轮残留）不再二次提问
  * 顺带修复 CLI 三处：①`runMessageInput` 的 `.terminal(true)` 与 `.commandContextFactory(...)` 是死配置（`commandAware(false)` 已保证 `dispatchCommand` 不会执行，而这两个字段全仓仅 `dispatchCommand` 读取）——合并时去重为单一 `handleInput(session, text, commandAware, model)`，这两个字段只在 `commandAware=true` 时装配；②`currentPrompt` 死变量（只被赋 null 与判空，内容从不使用）改名为 `isFirstRound`；③每轮 `isTaskCompleted.set(false)` 重置，避免上一轮收尾被当成本轮完成信号（原先靠 `currentPrompt = null` 间接表达）
  * 测试：`InputCommandAwareTest` 新增 `hitlDecisionWithResumeReturnsTaskInOneCall`（断言 `Kind.TASK` + 请求非空 + 代理名透传）与 `resumeAloneStillReturnsTask`；`HitlAgentResolutionTest` 3 例与 `InputCommandAwareTest` 另 4 例继续锁定「不传 resume 仍得 HITL」的批量语义
  * 文档（三端合并后重写，旧「单卡一次 / 批量两次」表述已全部过时）：README §2.1 决策步骤（“同一次调用**尝试**续流”）、§2.2 `resume` 开关（补“只表达意愿、不保证续流”）、§2.3 `Kind.HITL` 宿主动作与新增的「HITL 态三种情形」表（齐备未传 resume / 尚有未决卡 / 过期卡，各列判据与宿主动作）、§2.3 第三条「不对 HITL 自动续流」、§3.1 最小骨架 HITL 分支、§3.2 `decide` 改为一次调用 + `onHitl` 按事实分流、§3.5 不变量第一条与第四条（新增「不要用 `HITL.getPendingTasks().isEmpty()` 判齐备——该集合含已决策项，会把已齐备的批永远卡住」）、§13 常见坑第 2 条；`InputOutcome.Kind.HITL` 与 `HarnessInput.resume` 的 Javadoc 同步
  * 验证：harness 定向 8 类 46 例全绿；harness 全量 378 例 20F+8E（比上轮 376 例多 2 例即本次新增两例，失败数与基线一致）；`soloncode-cli` 全量 1204 例 2 失败均为既有基线（`ImageReplyLifecycleTest` 异常文案、`AgentSettingsControllerTest` 返回 400）

* **终端能力上收为输入属性：`HarnessInput.terminal` + `Command.terminalOnly()`**（删除 `CommandContextFactory.isRemote()` 与 `Command.cliOnly()`）：
  * 问题：原来的“远程”标志放在**工厂**上（`BufferedCommandContextFactory(boolean remote)` + 匿名子类覆盖 `isRemote()`），而工厂的职责是“怎么构造上下文”；更糟的是这个名字与实际语义不符——桌面端 `origin=NONE` 且运行在本地却 remote=true，CLI 有 `InputOrigin.channel("CLI")` 且实际具备终端却 remote=false。每次输入都会变的东西被固化在工厂构造参数里，且 `HarnessInput` 已有的 `origin` 无法推导出它（两者正交）
  * 改为：`HarnessInput.terminal(boolean)`（默认 false）作唯一真相源，经 `CommandDispatcher.dispatch(..., boolean terminal)` 同时驱动“拦截”与“上下文自述”。`Command.cliOnly()` → `terminalOnly()`（原名里的 “CLI” 指宿主形态，而命令真正需要的是终端能力）；`CommandContext.isRemote()` → `isTerminal()`（消除双关命名）；`CommandContextFactory.create` 增 `boolean terminal` 参，工厂退化为无状态（删字段、删构造参、删匿名子类）
  * 拦截判据从 `target.cliOnly() && factory.isRemote()` 变为 `target.terminalOnly() && !terminal`——直接可读为“没有终端却要求终端就拦截”；`HelpCommand.formatHelp` 第三参改名为 `terminal` 并取反，两处双重否定（`remote && cliOnly`）一并消除
  * 宿主侧：`CliShell` 两处 `HarnessInput` 补 `.terminal(true)`（否则 `/exit` 会被拦截、`/help` 裁掉 `terminalOnly` 命令——本次唯一可能导致真实回归的点）；`webCommandFactory()` / `desktopCommandFactory()` 退化为无参 `new BufferedCommandContextFactory()` 且不再注入 boolean（行为不变）；`CliCommandContext` 增 `terminalCapable` 字段（避免与 JLine `Terminal` 撞名）并覆写 `isTerminal()`，保证上下文自述与拦截判据同源
  * 连带改名：`ExitCommand.cliOnly()` → `terminalOnly()`；`ChatWebController.hints`、`WebChatInputHandler` 两处忙态判定的 `cliOnly()` 同步；`WebChatInputHandler` 原“远程渠道”注释改为终端语义
  * 存留的 `COMMAND_UNAVAILABLE_HINT`（“仅支持 CLI，Web/IM 端不可用”）未改文案：它是对用户的可读说明，保持稳定
  * 测试：`CommandDispatcherTest` 改断 `assertFalse(ctx.isTerminal())` 并新增 `bufferedContextHonorsTerminalFlag`（9 例）；`HelpCommandTest` 辅助方法 `cliOnlyCommand` → `terminalOnlyCommand`、四个 `formatHelp` 第三参取值全部取反、用例名改 `nonTerminalHelpListsOnlyCommandsAvailableToIm` / `terminalHelpKeepsTerminalOnlyCommands`（3 例）
  * 验证：harness 定向 6 类 37 例全绿；harness 全量 376 例 20F+8E（与改动前基线一致，多出 1 例即新增用例）；`soloncode-cli` 全量 1204 例 3 失败均为既有基线/联网类（`ImageReplyLifecycleTest` 异常文案、`AgentSettingsControllerTest` 返回 400、`SkillhubMarketTest.testInstallRealSlug`）

* **`HarnessInput` 命名收敛 + 命令上下文工厂下沉**：
  * `model(String)` → `selectedModel(String)`（`getModelName` → `getSelectedModel`）、`agent(String)` → `selectedAgent(String)`、`mentionAware(boolean)` → `agentAware(boolean)`（`isMentionAware` → `isAgentAware`）。原本 setter 叫 `model`/`agent` 而 getter 叫 `getModelName`/`getSelectedAgent`、字段叫 `selectedAgent`，三名互不一致；`mentionAware` 实际只解析 `@` 子代理提及，与 `commandAware`（`/` 前缀）不成对。**未保留旧名**（尚未发布），`AgentMentions.Options.mentionAware` 同步改名 `agentAware`（它是同一个开关的内层载体，两者不同名会让插件作者写错）
  * **删除 `handle(HarnessInput, @Nullable CommandContextFactory)` 两参版本**，改为 `HarnessInput.commandContextFactory(...)` 字段由 `handle(HarnessInput)` 唯一入口读取。与 `commandAware` 配套：前者决定「是否识别命令」，后者决定「识别到命令后回执怎么落地」。`HarnessEngine.dispatchCommand` 相应去掉 `factory` 形参，改为 `input.getCommandContextFactory()`，为空时回退 `BufferedCommandContextFactory`
  * 受益点：`handle` 从 2 参归一，宿主不再需要在同一行既构造 input 又补第二个参数（CLI/Web/桌面三端各一处）；`InputBoundaryTest` 的「工厂由宿主实现」护栏不受影响（`CommandContextFactory` 仍是接口且仍在 input 包）
  * 测试：`HarnessInputTest` 改断 `getSelectedModel`，`InputHandleTest.mentionAwareFalseKeepsTextAsIs` → `agentAwareFalseKeepsTextAsIs`，`HitlAgentResolutionTest` 改 `selectedAgent`；harness 定向 10 类 69 例全绿

* **命令渠道来源统一为值对象 `InputOrigin`**（删除 `CommandContext` 上重复的四元组）：
  * 背景：`getSource` / `getSourceUserId` / `getReplyTarget` / `getMessageId` 在全仓以 7 种形状并存（`HarnessInput` 四字段、`CommandContext` 四 default getter、`WebCommandContext` 四 override、`BufferedCommandContextFactory` 四 override、`session.replyRoute` 裸 Map、`QueueItem` 四字段、`SteerMessage` 四字段），且形成「渠道 → 入会话 → 命令 → 队列/插话箱 → 分发器 → 回到 Channel」的闭环，实为同一实体的 7 份副本。实测 28 处签名中 100% 四字段同时出现，不存在单独消费，故合并为单一实体而非拆分
  * 新增 `input.InputOrigin`（不可变，`NONE` 表示本地无渠道来源）：`channel` / `senderId` / `replyTarget` / `messageId`，并提供 `isEmpty` / `isIm`（收编 `WebChatInputHandler.isImSource`）/ `channelOr`（收编 `"CLI"` 与 `"command"` 两处不同兜底）/ `channelIs`（收编各处 `equalsIgnoreCase`）/ `withReplyTarget`（微信 `context_token` 刷新）/ `withChannel` / `toMap` / `fromMap`。`toMap`/`fromMap` **键名沿用历史命名**（source/sourceUserId/replyTarget/messageId），保证队列快照与 `session.replyRoute` 落盘数据向后兼容
  * `HarnessInput` 四字段合为 `origin`；新增 `origin(InputOrigin)`，旧 `route(4 参)` 转调并标 `@Deprecated`，四个旧 getter 同步转为 `origin.getXxx()` 转调
  * **`Command` 新增三参 `execute(engine, ctx, InputOrigin)` 的 default 委托**（`@since 4.2`）：仅 `/queue`、`/steer` 覆写，其余约 20 个实现零改动。`CommandDispatcher.dispatch` 增加 `origin` 参数，由 `HarnessEngine.dispatchCommand` 直接取 `input.getOrigin()` 传入——这是本次核心：删除了「工厂从 input 嗅探元数据」的第二条注入路径（`BufferedCommandContextFactory` 不再持有 `HarnessInput`）
  * 删除 `CommandContext` 的四个 getter、`soloncode-cli` 的 `WebCommandContext` 整个类（改造后无任何逻辑）、`webCommandFactory` 的四参数与匿名实现（缩为 `new BufferedCommandContextFactory(true)`）、桌面端等价匿名工厂、`CliCommandContext.getSource` 硬编码；CLI 入口改由 `CliShell.CLI_ORIGIN` 显式声明来源
  * `QueueItem` / `SteerMessage` 四字段合为 `origin`（旧 getter 转调 + `@Deprecated`），`SessionQueue.enqueue` 与 `SteerInterceptor.steer` 新增 `InputOrigin` 重载。**同时删除 `enqueue(session,text,String)` 与 `steer(...,String source,BooleanSupplier)` 两个 String 重载**：它们与 `InputOrigin` 版本在传 null 时无法区分（编译期 ambiguous），保留即缺陷
  * `WebChatInputHandler.setReplyRoute` 改为接收 `InputOrigin` 并用 `origin.toMap()` 写入 `session.replyRoute`（读取侧仍按 Map，键名不变，避免牵动约 80 处渠道测试断言）；`WebQueueDispatcher` / `WebGate` / `QueueWebController` 改用 `origin`
  * 顺带纠正（已于下一节被取代）：`CommandContext.isRemote()` 当时经核实并非死方法（`HelpCommand` 用它裁剪 `cliOnly` 命令），故保留；本轮已将其连根替换为 `isTerminal()` + `HarnessInput.terminal`，`BufferedCommandContextFactory(boolean)` 与匿名子类覆盖一并删除
  * 测试：新增 `InputOriginTest`（11 例，锁定 legacy 键名往返、渠道大小写、`isIm`、不可变刷新）；`Fakes.StubContext` 去掉四字段改为 `imOrigin()`/`localOrigin()`；`QueueCommandTest`/`SteerCommandTest`/`SessionQueueTest`/`SteerInterceptorTest`/`WebGateRejectedInputTest` 改为 origin 调用；`ImBusySteerContractTest` 源码断言同步（并修正 `indexOf` 起点，避免命中插话之前的注释）
  * 验证：harness 目标域 79 例全绿，全量 375 例 20F+8E 与改动前完全一致（`sessionProvider` NPE 等既有基线）；`soloncode-cli` 全量 1204 例 2 失败均为既有基线（`ImageReplyLifecycleTest` 异常文案、`AgentSettingsControllerTest`）


* 修复 `handle` 的 HITL 决策结果子代理解析：`InputOutcome.hitl(...)` 的 `agentName` 原先只回显入参 `selectedAgent`，而宿主审批请求通常不带该字段（前端只回 callId + action），宿主据此续流会错误回落主代理——trace 按代理名分 key，取错代理取不到挂起的 tool_calls，表现为「点了审批但续跑空转」。现改为「显式入参优先，其次会话级 `CTX_AGENT_SELECTED`」（新增私有 `resolveHitlAgent`；`HitlAgentResolutionTest` 3 例锁定）
* `InputAttachments.isEmpty()` 删除（与 `hasAny()` 同一判断的两种叫法），`HarnessEngine.handle` 的 `final InputAttachments resolved = attachments` 别名一并去除
* `InputHandleTest` +1（`commandWithAttachmentIsTreatedAsMessage`）：锁定「带附件的斜杠文本不当命令」——附件是要交给模型的内容，不能因命令分发被静默丢弃；桌面端原实现把命令识别放在附件处理之前，带附件的 `/cmd` 会丢掉附件，现与 Web/引擎口径统一
* **删除 `InputOutcome.stream()` 与 `InputTextEvent`**（无生产调用者的过度设计，且语义方向与真实宿主需求相反）：
  * 该 API 承诺「回执 + 任务拼成同一条流，宿主无需分两段消费」，但三端真实需求恰好相反——CLI 由命令即时写终端（合成会重复打印）、Web 要求流门复位早于回执（否则回执被前端静默丢弃）、桌面要求 `streamHub` 序号回放与 HITL 拦截器重挂、ACP 需要 `takeWhile` 取消判定。任何替宿主拼好的流都得被拆开重拼
  * 发布包袱核查：`InputOutcome` 与 `stream()` 同属本次重构提交，v4.1.1 中均不存在，删除不破坏外部实现
  * 连带收窄 `InputOutcome`：移除不再需要的 `session` 字段，`command(...)` / `unavailable(...)` 各减一个入参（会话只被删掉的 `stream()` 用来构造回执事件）
  * 取流口径统一为唯一路径：`getRequest().stream()`；回执读 `getCommandOutput()`，先后顺序由宿主掌握。`HarnessEngine#handle` javadoc 与 README（取用方式、成员表、映射规则表、常见坑、模块树）同步纠正
  * 测试：`InputStreamTest` 删除（7 例）、新增 `InputHandleTest`（6 例，聚焦 kind 分派与回执取用），`InputPrefixTest`/`CommandAgentTaskTest` 中依赖 `stream()` 的断言改为直接断言 `getCommandOutput()` / `getKind()`

* `MediaBlock` 新增 `getFilePath()`（附件本地副本路径）：
  * 声明为 `default` 方法返回 null（`@since 4.2.0`），不破坏 3.9.2 以来直接 `implements MediaBlock` 的外部实现；`AbsMedia` 以字段覆盖并提供链式 `filePath(...)`
  * 语义：工作区相对路径，仅用于历史消息的文件名标签，**不参与任何 dialect 的请求载荷**（请求只读 `data`/`url`/`mimeType`）
  * 会随 `ChatMessage.toJson` 落盘并在 `fromJson` 恢复，`MediaBlockFilePathTest` 锁死往返与请求隔离

* 重构 soloncode harness 命令体系：删除 `CommandContext.runAgentTask` / `isAgentTask` 回调模式，改为「命令只做预处理、声明意图」——
  * 新增 `command.AgentTaskIntent`（session/prompt/model 三字段值对象），`CommandContext` 由「回调 + 布尔标记」换成 `requestAgentTask(prompt, model)`、`requestAgentTask(session, prompt, model)`、`getAgentTaskIntent()`；`BufferedCommandContext` 不再持有 `AgentTaskRunner`，CLI/Web 端的上下文实现与工厂也随之瘦身
  * `CommandDispatcher.dispatch` 在命令执行后，把意图重新交给唯一的 `engine.handle(...)` 完成二段装配（`commandAware(false)` 不再识别命令、`resume(空正文即续流)`、`agent(会话级选择)`），结果作为 `InputOutcome.command(..., nested)` 的新参数；kind 由 nested 推导（有任务即 `TASK`，否则 `REPLY`），调用方不再手工传 boolean。**本次不新增引擎入口**，`HarnessEngine` 对外仍只有 `handle(...)` 系列（送审时曾新增过 `commandAgentTurn` 草稿，现已删除）
  * 删除 `InputOutcome.isAgentTask()`（kind 已够），`hasRequest()` 降级为派生判定并修正过期注释；`InputOutcome.command()` 第 5 参由 `boolean agentTask` 改为 `@Nullable InputOutcome nested`
  * 修复 `/continue` 三端静默失效：旧实现把 `(null, null)` 喂给 `input()`，命中空输入判空返回 `IGNORED`，命令只做了 trace 手术、任务从不启动。现在意图带 `resume` 语义，二段走 `input()` 的续流分支（在空输入判空之前生效），三端 `/continue` 从此真正续跑
  * 修复 `/<cmd> <sessionId>` 跨会话错位：命令在 A 会话做手术、宿主把流开在 B 会话。意图带目标会话后二者统一
  * 命令产出的提示词统一做 @ 子代理识别（此前仅 CLI 解析，Web/桌面不解析），模型回退统一为「命令声明 → 会话选择 → 默认」
* `InputOutcome.Kind` 收敛为 4 值：`TASK` / `REPLY` / `HITL` / `IGNORED`（原 6 值 `MESSAGE` / `COMMAND` / `COMMAND_AGENT` / `CLI_ONLY_BLOCKED` / `HITL` / `IGNORED`）。旧设计把两个语义轴混在一个枚举里——`isCommand()` 必须写成三个常量的 OR 才能表达「这是不是命令」这一事实，宿主也因此要写两层派发（先 `isCommand()` 再 `switch(kind)`）。现在 **kind 只回答「宿主该做什么」，命令事实降级为字段谓词**：
  * `isCommand()` 改判 `commandName != null`（与旧三常量 OR 语义等价，一次性判定）；新增 `hasReply()`
  * `REPLY` 同时涵盖「命令已执行」与「命令在本渠道不可用（未执行）」，宿主动作相同故合并；`CLI_ONLY_BLOCKED` 这个名字把渠道名与拒绝原因写进结局名，随之一并删除
  * 命令触发的任务与用户手输的任务共用 `TASK`，差异退化为「有没有回执」这份数据（`hasReply()`），宿主一个 `switch` 即可分完
  * `InputOutcome.blocked(...)` → `InputOutcome.unavailable(...)`；`CommandDispatcher.CLI_ONLY_HINT` → `COMMAND_UNAVAILABLE_HINT`（提示文案不变，仍点名 CLI，因为它面向用户）
  * `stream()` 对 `TASK` 改为「回执（若有）后接请求流」的同一条流，使 `.stream()` 真正成为「一次输入的完整事件流」；无回执时仍直接返回 `request.stream()`（与 `getRequest().stream()` 同构）
* 添加 soloncode harness `CommandAgentTaskTest` 4 例（命令意图 → `TASK` + 请求透传、空正文续流不被 `IGNORED`、无意图仍为 `REPLY`、回执排在任务流之前）与 `CommandDispatcherTest` 4 例（意图记录、续流意图、跨会话语义、kind 推导）。
* 同步 README 命令扩展示例为 `requestAgentTask` 并说明「宿主按 kind 分路由、不要自己再拼 Prompt 或开流」。

* 命名定稿：`InputResult` → **`InputOutcome`**，`HarnessEngine.input(...)` → **`HarnessEngine.handle(...)`**（4.2 未发布，无兼容影响）
  * 理由一（避免与既有语义冲突）：本仓 `turn` 专指「一次 agent 执行轮次」（`maxTurns`、`TurnOutcome`、`turnsExecuted`），而 `IGNORED` / `REPLY` 根本不是轮次，三端变量名 `turn` 属术语错误；`Result` 在 soloncode-cli 侧被 30+ 文件用作 `org.noear.solon.core.handle.Result`（HTTP 响应包装），语义冲突
  * 理由二（对齐产品既有命名家族）：`Outcome` 已是第四处同构用法——`RunOutcome`（`success | interrupt`）、`StreamLoop.TurnOutcome`、`WeChatLink.PollOutcome`，都是「一次处理后归入几类结局」
  * 理由三（动词更诚实）：旧签名 `input(HarnessInput input, ...)` 一个方法内 `input` 出现三次且身份各异，形似 `getInput()`，而实际是「HITL 写入 + 命令识别与执行（有副作用）+ 附件落盘 + 提及解析 + Prompt 构建 + 请求装配」六步流水线；与纯装配、无副作用的 `prompt(...)` 并列时看不出量级差。`handle` 在本模块已有同义先例（`ExecutionBridge.handle`），且不与 `dispatch` / `dispatchCommand` / `execute` 撞车
  * 工厂 `InputOutcome.message(...)` → `InputOutcome.task(...)`（它返回的就是 `Kind.TASK`，旧名与 kind 不一致）
  * 三端变量统一 `turn` → `outcome`（含 `hitlTurn` → `hitlOutcome`）；`InputBoundaryTest` 的源码路径字面量与两个方法名同步（`inputOutcomeMustNotDependOnEngineOrScheduler` / `inputOutcomeStreamMustNotResumeHitl`）
  * 修正 `HarnessInput` 类注释里过期的两分支示例（原 `if (isCommand()) ... else ...` 在「命令触发任务」时会漏掉执行任务，正是四值改造要消灭的写法），改为 `switch (getKind())` 三分支
  * 修正 README 两处过期文案：`input(session, "/help")` 返回 `MESSAGE` → 返回 `TASK`；模块树 `InputResult（六态）` → `InputOutcome（四态）`

### 行为变更

* `InputOutcome.Kind` 的常量名与数量变化属编译期破坏（`4.2` 未发布，无兼容影响）：只看 `getKind()` 不再能区分「命令触发」与「用户手输」，改读 `isCommand()` / `getCommandName()`。
* 远端渠道（Web/IM）现在也会收到 CLI 专属命令的不可用提示的 IM 转发（此前仅 Web 前端可见），属修正：IM 用户执行 `/exit` 至少能看到原因。

### 可测试目标

* Trae
* Dify
* Cherry Studio


### 待定


*  mcp channel 概念改为 transport （并保持兼容）???

### 4.2.0

* 调整 solon-ai-mcp 升为 mcp-java-jdk 2.1.0
* mcp-core 升为 mcp-java-sdk 2.1.0
* mcp-json-jackson2 升为 mcp-java-sdk 2.1.0

### 4.1.1

* 添加 solon-ai-core `ChatAccumulator` 承担事件归并、终态聚合与方言状态；`ChatDialect` 解析入口调整为 `parseResponseJson(ChatStreamContext, String)`。（**不兼容变更**：第三方方言需同步适配）
* 添加 solon-ai-core ToolMessage.isError 字段
* 添加 solon-ai-agent `AbsReActEvent`
* 添加 solon-ai-agent turnId 替代 reasonId (后者标为弃用)
* 添加 solon-ai-harness Command.runnableWhenBusy 方法（标注对话忙时也可运行，默认否）
* 优化 solon-ai-core `AssistantMessage` thinking/text 双字段及取值方法；新增版本化 `protocolStates` 与类型化 `SearchResult`/`Citation`，弃用但保留旧 `contentRaw`/raw 字段反序列化兼容。
* 调整 solon-ai-core `ChatResponse` 为只读终态结果，新增 `getToolCalls()`、`getBlocks()`、`getSearchResults()`、`getCitations()`；移除多候选、累积器、流状态及请求上下文相关旧方法。（**不兼容变更**）
* 调整 solon-ai-harness `MountDir` 重构为 `MountSource` 接口及相关调整
* 修复 solon-ai-core 空正文、静默空流、工具参数多根/尾随数据、Agent 取消/NPE、Flow 流类型适配、Anthropic 缓存率与叠字等问题；协议状态绑定后深层冻结，OpenAI Responses 解析工作区采用私有命名空间。
* 修复 solon-ai-mcp 增强 MCP 工具结果响应异常处理，确保异常可见并保证响应流正常终止

### 4.1.0

* 添加 solon-ai-core  `docs/chat-event-model.md`，说明事件分组、边界约束、取消语义及第三方方言迁移方式。
* 添加 solon-ai-core 事件模型、`ChatEventFilter`、`ChatEventNormalizer`、`ChatStreamSession` 与 `ChatStreamContext`，支持多内容块、并行工具调用、边界补齐和订阅级状态隔离。
* 添加 solon-ai-core `ChatAccumulator` 承担流式累积与方言状态；`ChatDialect` 解析入口调整为 `parseResponseJson(ChatStreamContext, String)`。（**不兼容变更**：第三方方言需同步适配）
* 添加 solon-ai-agent `ContextSizeEvent.contextLength`，并支持 Harness 默认压缩上下文长度配置。
* 添加 solon-ai-ui-aisdk `AgUiStreamWrapper`；重写 `AiSdkStreamWrapper` 多块状态机并完善取消、异常和结束传播，旧响应流适配方法更名为 `toAiSdkStreamOfResponses`。（**不兼容变更**）
* 优化 solon-ai-core  `EmbeddingModel`、`GenerateModel` 标准/provider/model 信息接口；`EmbeddingConfig.toString()` 增加 standard。
* 优化 solon-ai-core  `AssistantMessage` thinking/text 双字段及取值方法
* 优化 solon-ai-agent  `ReasonTask`、`SimpleAgent`、`SupervisorTask` 的思考/正文边界、终态归约、取消处理与异常结束补位；工具和动作事件统一由 `ActionTask` 发出。
* 优化 solon-ai-talents ChatModel 事件生命周期：内容块按 group/itemId/index 独立管理；`STEP_END` 携带单步 Usage，`RESPONSE_END` 携带累计 Usage，终态默认结束原因为 `stop`。
* 优化 solon-ai-llm-dialects OpenAI Responses、Anthropic、Gemini Interactions 等方言的状态、服务端工具、媒体、引用、拒绝、签名、心跳及错误事件映射。
* 优化 solon-ai-talents Code Talent 扫描缓存、LSP 超时体验及 OpenAI/Anthropic 方言兼容性。
* 调整 solon-ai-core `ChatResponse` 为不可变终态结果，新增 `getToolCalls()`、`getBlocks()`、`getAlternatives()`；移除累积器、流状态及请求上下文相关旧方法。
* 调整 solon-ai-core  Agent 流事件直接携带 `ChatEvent`，移除 `ChatDeltaEvent` 及各类旧 Chunk；Harness 终态统一使用 `RunEndEvent`。（**不兼容变更**）
* 调整 solon-ai-core 流式 API：`ChatModel.stream()` 改为 `Flux<ChatEvent>`。
* 调整 solon-ai-agent 移除 `ReasonDeltaEvent.isFinished()/isError()`；完成状态改取 `RunEndEvent`，异常状态改取 `ReActTrace.isAbnormal()`。（**不兼容变更**）
* 修复 solon-ai-core 空正文、静默空流、工具参数重复、Agent 取消/NPE、Flow 流类型适配、Anthropic 缓存率与叠字等问题。


变更说明：

| 旧接口                             | 新接口                          | 说明 |
|------------------------------------|---------------------------------|------|
| `chatModel:<ChatResponse>stream()` | `chatModel:<ChatEvent>stream()` |      |



### 4.0.6

* 添加 solon-ai-agent Metrics 缓存率计算支持
* 优化 solon-ai-llm 方言工具调用的兼容性处理（有些 toolCalls 参数格式不完整）
* 优化 solon-ai-llm gemini 方言兼容性处理
* 优化 solon-ai-mcp McpTalent 兼容性
* 调整 solon-ai-mcp IMcpHttpServerTransport 接口，完善生命周期（更方便动态增减）

### 4.0.5

* 添加 solon-ai-core ModelOptionsAmend http 定制传递（HarnessEngine->ReActAgent->ChatRequest）
* 优化 solon-ai-mcp 兼容处理无事件类型的SSE消息
* 优化 solon-ai-dialect-openai openai-responses 方言适配
* 优化 solon-ai-harness TaskTalent 增加 maxTasks 限制
* 优化 solon-ai-talent-web CodeSearchTalent，WebsearchTalent 适配
* 优化 solon-ai-talent-memory 引导词及搜索能力
* 优化 solon-ai-talent-cli TerminalTalent 引导词
* 优化 solon-ai-talent-mount MountManager 添加 disallowSkills
* 修复 solon-ai-talent-cli Windows 环境变量可能识别失误的问题
* 修复 solon-ai-talent-gateway OpenApiV2Resolver 可能无限递归解析的问题

### 4.0.4

* 添加 solon-ai-core ModelOptionsAmend.reasoning_effort 统一选项 API
* 添加 solon-ai-core ModelOptionsAmend.thinking 思考模式开关统一选项 API
* 添加 solon-ai-core Prompt.copy 方法
* 添加 solon-ai-core ChatRequestDesc.role, instruction, systemPrompt 方法
* 添加 solon-ai-core AssistantMessage 多模态支持
* 添加 solon-ai-agent HITLStrategy 接口，替代 HITLInterceptor.InterventionStrategy（后者标为弃用）
* 添加 solon-ai-agent ActionChunk, ObservationChunk, ToolExchanger callId 属性
* 添加 solon-ai-agent 新流块 RunStartEvent, RunEndEvent, ReasonStartEvent, ReasonEndEvent 事件块
* 添加 solon-ai-agent 新流块 ToolStartEvent、ToolEndEvent
* 添加 solon-ai-agent 新流块 HITLPendingEvent、HITLDecidedEvent
* 添加 solon-ai-agent 新流块 ActionStartEvent、ActionEndEvent、ReasonDeltaEvent（替代 ReasonChunk）
* 添加 solon-ai-agent 新流块 TeamStartEvent、TeamEndEvent（替代 TeamChunk）
* 添加 solon-ai-agent 新流块 SimpleStartEvent、SimpleEndEvent（替代 SimpleChunk）
* 添加 solon-ai-agent ReActInterceptor onActionStart, onActionEnd 事件
* 添加 solon-ai-agent ReActInterceptor onToolCallStart, onToolCallEnd 事件
* 添加 solon-ai-harness 新的工具权限体系
* 添加 solon-ai-harness ToolName 类，替代 ToolPermission（后者标为弃用）
* 添加 solon-ai-harness AgentDefinition 链式构造方法
* 添加 solon-ai-harness TaskWrapChuck 类，包装子代理的流块，提高子代理调用的透明度
* 优化 solon-ai-core AbstractChatDialect 方言适配
* 优化 solon-ai-agent TeamAgent 相关实现代码
* 优化 solon-ai-agent hitl 实现（以支持多工具批量）
* 优化 solon-ai-agent ContextCompressionInterceptor 压缩算法
* 优化 solon-ai-talent-cli bash 脚本处理
* 优化 solon-ai-dialect-anthropic 方言适配
* 优化 solon-ai-dialect-openai Responses 方言适配
* 优化 solon-ai-dialect-dashscope 方言适配
* 优化 solon-ai-dialect-gemini 方言适配
* 优化 solon-ai-harness 增加 skills 和 agents 局部（分组）动态刷新能力
* 调整 solon-ai-agent ToolCallEndEvent 替代 ObservationChunk, ToolCallStartEvent 替代 ActionChunk（后者标为弃用）
* 调整 solon-ai-agent ReActInterceptor onToolCallEnd 替代 onObservation，onToolCallStart 替代 onAction（后者标为弃用）
* 调整 solon-ai-agent ContextCompressionInterceptor 取消 maxTokens，由 maxContextLengthRatio 替代
* 调整 solon-ai-agent ContextCompressionInterceptor 取消 chatModelSupplier 参数（标为弃用）
* 调整 solon-ai-agent AgentChunk 更名为 AgentEvent
* 调整 solon-ai-harness 重构 hitl 实现
* 调整 solon-ai-harness getModelOrMain 标为弃用，由 getModelOrDefInstance 替代
* 调整 solon-ai-harness 取消 compressionMaxTokens，由 compressionMaxContextRatio 替代
* 修复 solon-ai-core ChatModel.stream 内部多次请求可能卡死的问题
* acp-sdk 升为 0.14

### 4.0.3

* 新增 solon-ai-loop 插件
* 新增 solon-ai-talent-code 才能插件（从 solon-ai-harness 分离出来）
* 新增 solon-ai-dialect-gemini gemini-interactions 方言适配
* 添加 solon-ai-core Talent.setEnabled 方法（提供接口级的开关设置）
* 添加 solon-ai-core 方言缓存控制机制
* 添加 solon-ai-agent ReActTrace.agentName 字段（不再从 config 获取）
* 添加 solon-ai-agent SimpleTrace.agentName 字段（不再从 config 获取）
* 添加 solon-ai-talent-cli TerminalTalent.maxCharacterLimit 设置
* 优化 solon-ai-dialect-x 方言解析
* 调整 solon-ai-harness GenerateTool 更名为 GenerateTalent（方便动态启用/禁用）
* 修复 solon-ai-core 方言结束时没有 choice 时，不会触发最后一个 stream 输出的问题（造成无法获取 getUsage）
* mcp McpSchema.Sampling 类添加 `@JsonIgnoreProperties(ignoreUnknown = true)` 增加兼容性

### 4.0.2

* 添加 solon-ai-harness allowToolReset,disallowToolReset 方法
* 添加 solon-ai-harness 子代理 ContextSizeChunk 流传递
* 优化 solon-ai-agent ContextCompressionInterceptor 对单条消息超过 maxTokens/2 进行限制（避免上下文溢出）
* 优化 solon-ai-talent-cli TerminalTalent.grep 参数（采用正则匹配，并增加 include 参数，）
* 优化 solon-ai-talent-cli TerminalTalent.bash 参数（增加 max_output_chars 参数，对输出限流）
* 优化 solon-ai-talent-cli TodoTalent 返回内容，增加各状态数据描述
* 优化 solon-ai-talent-web CodeSearchTalent，WebfetchTalent，WebsearchTalent 返回格式
* 调整 solon-ai-talent-memory 接口 MemorySolution.Factory 改为 MemorySolutionProvider
* 重构 solon-ai-core EmbeddingOptions 参数合并逻辑，将字符串类型的配置值自动转换为布尔、整数或浮点等强类型
* tika 升为 2.9.4

### 4.0.0

* 变更 solon skill “概念”改为 solon talent（避免与 agent skill 的冲突）
* 新增 mcp-core（替换之前的 mcp-sdk，保持与官方相同命名）
* 新增 mcp-json-jackson2
* 新增 solon-ai-sandbox
* 新增 solon-ai-mcp MCP_2025_11_25 协议支持（支持 ServerTransportSecurityValidator 签权）
* 新增 solon-ai-talent-mount 才能插件（原 PoolManager 独立出来）
* 添加 solon-ai-talent-openapi OpenApiSkill.removeApi 方法
* 添加 solon-ai-talent-openapi ApiSource 超时配置支持
* 添加 solon-ai-talent-gateway McpGatewaySkill 专门处理 mcp（ToolGatewaySkill 可以处理所有工具）
* 添加 solon-ai-talent-cli TerminalTalent 系统级沙盒限制支持
* 添加 solon-ai-core ChatOptions:httpCustomize 方法
* 添加 solon-ai-core Talent::isEnabled 方法
* 添加 solon-ai-core AiConfig::standard（替代 provider 之前的作用）
* 添加 solon-ai-core ReActAgent::getModel 方法
* 添加 solon-ai-agent ReActInterceptor.onReasonStart 事件方法
* 添加 solon-ai-agent AgentTrace.getOriginalPrompt, getWorkingMemory 方法
* 添加 solon-ai-agent AgentTrace.getRunId 方法
* 添加 solon-ai-agent AgentChunk.getRunId 方法
* 添加 solon-ai-agent TeamAgent 添加初心标记
* 添加 solon-ai-agent ToolExchanger 类
* 添加 solon-ai-mcp McpClientProvider allowedTools, disallowedTools 工具控制机制
* 添加 solon-ai-harness bashAsyncEnabled 开关
* 添加 solon-ai-harness HarnessEngine.removeApi 方法
* 添加 solon-ai-harness HarnessProperties.mountPools 属性（替代 skillPools，后者标为弃用）
* 添加 solon-ai-harness 用 McpGatewaySkill 替换 ToolGatewaySkill（更有争对性）
* 优化 solon-ai-agent 初始化时（rest）添加快照持久化
* 优化 solon-ai-harness AgentFactory `edit` 权限添加 `read`、`write` 控制
* 优化 solon-ai-agent ContextCompressionInterceptor 压缩时机（从 onObservation 改为 onReasonStart），并增加系统词大小
* 优化 solon-ai-agent ContextCompressionInterceptor 增强上下文压缩拦截器中过期区 tool-use 原子序列的追溯保护，并完善文档注释
* 优化 solon-ai-agent ContextCompressionInterceptor 算法，增加 tools token
* 优化 solon-ai-dialect-openai OpenaiResponses 协议兼容性
* 调整 solon-ai-core 取消 ChatConfig.reasoningFieldName 配置（这个配置不合理）
* 调整 solon-ai-core 移除 SkillProvider 类
* 调整 solon-ai-core 移除 toolAdd(Object) 方法（指向不明，容易出错）
* 调整 solon-ai-core 移除 defaultToolAdd(Object) 方法（指向不明，容易出错）
* 调整 solon-ai-agent 移除 toolAdd(Object) 方法（指向不明，容易出错）
* 调整 solon-ai-agent 移除 defaultToolAdd(Object) 方法（指向不明，容易出错）
* 调整 solon-ai-mcp McpClientProperties 默认不启用心跳（之前默认为 30 心跳一次）
* 调整 solon-ai-mcp McpProviders 更名为 McpClientProviders
* 调整 solon-ai-agent ActionChunk：ActionChunk 更名为 ObservationChunk；ActionStartChunk 更名为 ActionChunk
* 调整 solon-ai-agent ReActInterceptor.onAction,onObservation: toolName,args,result 合并为 toolExchanger（并允许修改）
* 调整 solon-ai-agent ReActInterceptor.onReason 更名为 onReasonEnd（和 onReasonStart 凑成一对）
* 调整 solon-ai-agent ReActAgent.maxSteps 更名为 maxTurns（保持行业习惯）
* 调整 solon-ai-agent SummarizationInterceptor 更名为 ContextCompressionInterceptor（更符合语义）
* 调整 solon-ai-talent-cli PoolManager 实现细节，并添加 refresh(alias)
* 调整 solon-ai-talent-cli 技能标识由 aliasPath 统一改为 name，并优化技能池加载逻辑及代码格式
* 调整 solon-ai-talent-web WebfetchTool 更名为 WebfetchTalent; WebsearchTool 更名为 WebsearchTalent; CodeSearchTool 更名为 CodeSearchTalent
* 调整 solon-ai-talent-diff ApplyDiffTool 更名为 ApplyDiffTalent；ApplyPatchTool 更名为 ApplyPatchTalent
* 调整 solon-ai-harness HarnessEngine 打断与 HarnessProperties 关联（后者删除），避免耦合影响未来的调整
* 调整 solon-ai-harness HarnessEngine 移除 mainModel 字段（减兴缓存麻烦）
* 移除 solon-ai-skill-browser（包太大了，没必要）
* 移除 solon-ai-skill-restapi （由 solon-ai-talent-gateway 替代）
* 移除 solon-ai-skill-toolgateway （由 solon-ai-talent-gateway 替代）
* 移除 mcp-sdk（由 mcp-core 替代）
* 修复 solon-ai-mcp WebRxStreamableHttpTransport 没有 event 的消息会出错的问题（优化非规范兼容）
* 修复 solon-ai-mcp WebRxStreamableHttpTransport 没有 contentType 的消息会出错的问题（优化非规范兼容）
* 修复 solon-ai-agent ContextCompressionInterceptor 压缩后可能会引起 ToolMessage 无法对齐的问题
* mcp-sdk 升为 1.1.3


变更对照表：

| 旧方法或参数                                            | 新方法或参数                                            |   |
|---------------------------------------------------|---------------------------------------------------|---|
| `ReActInterceptor.onAction(,toolName,args,)`      | `ReActInterceptor.onAction(,toolExchanger,)` |   |
| `ReActInterceptor.onObservation(,toolName,args,)` | `ReActInterceptor.onObservation(,toolExchanger,)` |   |
|                                                   |                                                   |   |
| `(toolName, args)` + `trace.lastObservation`      | `ToolExchanger` (thread-safe)                     |   |




### 3.10.7

* 添加 solon-ai-core ChatResponseDefault attrs 附件属性机制（方便方言解析处理），及 openai、anthropic、openai-response 方言调整
* 优化 solon-ai-dialect-anthropic message_stop 处理（增加 isEmpty 检测）
* 修复 solon-ai-skill-memory MemoryMdData 因key格式问题未完全加载的问题
* 修复 solon-ai-agent ReasonTask ‘您上一次的回答是空的’ 可能造成死循环的问题

### 3.10.6

* 调整 ChatDialect.buildRequestJson 返回类型（String 改为 ONode，方便日志定制）
* 修复 solon-ai-skill-web WebfetchTool 超时转换错位问题

### 3.10.5

* 添加 solon-ai-agent AgentTrace.getAgentName, getBeginTimeMs 方法
* 添加 solon-ai-agent 使用 autoRethink 替代 maxStepsAutoExtensible（后者标为弃用）
* 添加 solon-ai-harness command 接口管理
* 添加 solon-ai-harness HarnessEngine.getAgentOrMain 方法
* 添加 solon-ai-core CmdUtil 辅助工具（主要解析参数）
* 添加 solon-ai-core ChatMessage.getCreatedAt 方法
* 添加 solon-ai-core ChatSession.removeLatestMessage 方法（对话回退支持）
* 添加 solon-ai-core Repository.promptAugment 快捷方法
* 添加 solon-ai-core AiConfig.enabled 配置属性
* 添加 solon-ai-skill-cli TerminalSkill.edit old_str 预检处理（llm 可能会传空）
* 优化 solon-ai-agent ReasonTask Missing traceKey 处理
* 优化 solon-ai-core AiConfig.proxy 配置兼容（减少反序列化反射）
* 优化 solon-ai-core ChatModel llm- 日志打印，添加 agentName
* 优化 solon-ai-core EmbeddingModel、RerankingModel、GenerateModel llm- 日志打印，添加 modelName
* 优化 solon-ai-dialect apiUrl 自动补全的#号处理
* 优化 solon-ai-dialect OpenaiChatDialect 添加自动补地址处理
* 优化 solon-ai-harness CodeSkill 拆解语言支持
* 优化 solon-ai-harness taskDo 多层子代理嵌套时的容错处理
* 优化 solon-ai-skill-cli ExpertSkill 引导词
* 优化 solon-ai-skill-cli read 引导词（优先一次读完）
* 优化 solon-ai-skill-cli 关于 py 和 nodejs 环境识别
* 优化 solon-ai-skill-memory 添加 markdown 轻量实现方案（默认不启用会话隔离）
* 调整 solon-ai-core ChatOptions.name 更名为 agentName
* 调整 solon-ai-harness TaskSkill 只输出 ThoughtChunk（不输出 ReasonChunk）
* 调整 solon-ai-agent 移除 ReActOptions.maxStepsLimit 属性 (没什么必要)
* acp-sdk 升为 0.11.0
* roguemap 升为 1.1.5

### 3.10.4

* 添加 solon-ai-agent 动态切换 llm 支持（支持不同会话，在各自请求时切换）
* 添加 solon-ai-harness 重试次数配置支持
* 添加 solon-ai-harness lsp 功能
* 添加 solon-ai-harness HarnessProperties.disallowedTools 配置支持（全局禁用工具配置）
* 添加 solon-ai-harness HarnessProperties.agentPools（子代理池） 配置支持
* 添加 solon-ai-harness HarnessProperties.systemPrompt 配置支持（替代之前的 AGENT.md 预设处理，由外部定制）
* 优化 solon-ai-core embedding 配置默认选项的能力（之前只能在请求时添加）
* 优化 solon-ai-core ChatRequestDesc.internalStream 添加流接收超时处理（避免因 llm 服务原因一直卡着）
* 优化 solon-ai-dialect-anthropic anthropic 方言的异常兼容处理
* 优化 solon-ai-dialect-openai openai-response 方言的异常兼容处理
* 优化 solon-ai-agent 中断传递处理
* 优化 solon-ai-agent ActionTask onAction 日志
* 优化 solon-ai-agent retrying 中断处理（这前的 task 内部的 stream 可能会中断传递）
* 优化 solon-ai-agent AgentSession.updateSnapshot 添加锁机制
* 优化 solon-ai-skill-lsp 重构 LspSkill 实现（之前名为 LspTool）
* 优化 solon-ai-skill-cli ExpertSkill 引导词
* 优化 solon-ai-skill-cli TerminalSkill 安全引导词
* 优化 solon-ai-skill-sys 从 cli 同步 ProcessExecutor
* 优化 solon-ai-harness HarnessEngine 扩展机制（支持为子代理扩展，通过名字识别）
* 调整 solon-ai-harness ReActAgentExtension 更名为 ReActAgentExtension
* 调整 solon-ai-harness 切换 llm 的机制，并取消 switchMainModel 方法（有自由的机制了）
* 调整 solon-ai-harness TerminalSkillProxy 转到 solon-ai-skill-cli
* 修复 solon-ai-skill-toolgateway ToolGatewaySkill call_tool 不能传递 toolContext 的问题
* 修复 solon-ai-core AbstractChatDialect reasoning_field_name 可能传递中断的问题

### 3.10.2

* 新增 solon-ai-repo-dashvector-official 插件
* 添加 solon-ai-core ChatModel.getModel 方法
* 添加 solon-ai-core ChatModel.getProvider 方法
* 添加 solon-ai-core ChatOptions.name 方便 agent 传递 name（打印日志）
* 添加 solon-ai-core 基于语义的分割器 SemanticSplitter
* 添加 solon-ai-core AiConfig.name, description 配置属性（用于管理显示）
* 添加 solon-ai-agent AgentRequest.callAsync 异步调用方法
* 添加 solon-ai-agent AgentResponse.getTrace 方法
* 添加 solon-ai-agent SummarizationInterceptor.copyWith 方法（方便复制实例并调整限制）
* 添加 solon-ai-harness HarnessProperties.models 配置属性（替代 chatModel）
* 添加 solon-ai-harness 子代理切换模型的能力
* 添加 solon-ai-harness HarnessProperties.removeModel 方法
* 优化 solon-ai-core RepositoryTool 增加重排支持
* 优化 solon-ai-core OllamaEmbeddingDialect.matched 添加 endsWith 匹配
* 优化 solon-ai-core MethodToolProvider 允许是否使用自身的 ToolProvider 内容（同时优化 AbsSkill，AbsToolProvider）
* 优化 solon-ai-agent ReActAgent 流块输出
* 优化 solon-ai-cli TodoSkill 提示词
* 调整 solon-ai-cli 取消 TerminalSkill.undo 工具（几乎用不到，由 git 控制）
* 调整 solon-ai-harness 取消 SESSION_DEFAULT（由应用侧处理）
* 调整 solon-ai-harness 去除 getVersion 方法（由应用侧定义）
* 调整 solon-ai-harness CLAUDE.md 更名为 CODE.md（内部自动生成）
* 修复 solon-ai-core MethodExecuteHandler 当 llm.args 嵌套非规范 json 时出错的问题（比如，非规范控制符）
* 修复 solon-ai-core ChatResponseDefault.isEmpty 可能识别失败的问题（不同 llm 情况较多）
* 调整 solon-ai-harness HarnessProperties.restApis 更名为 apiServers；取消 chatModel（由 models 替代）
* 修复 solon-ai-agent FileAgentSession.getSessionId 可能为空的问题
* 修复 solon-ai-skill-web CodeSearchTool 失效的问题（mcp.exa.ai 变了地址）
* 修复 solon-ai-harness TaskOp jsonSchema 问题（可能造成 multitask 调度失败）

### 3.10.1

* 新增 acp-sdk 模块（从 solon-ai-acp 分离出来，方便独立升级）
* 新增 solon-ai-harness 智能体马具框架
* 添加 solon-ai-core ChatModel.of().systemPrompt 方法
* 添加 solon-ai-core 工具执行时的参数注解支持
* 添加 solon-ai-core RepositoryTool 工具，为实现 Agent RAG 提供支持
* 添加 solon-ai-core AbsToolProvider 类（取代之前添加 toolObj 的方式）
* 优化 solon-ai-skill-cli ExpertSkill 原来的二档展示升为三级
* 优化 solon-ai-skill-restapi 原来的三档展示升为四级，并添加分组（可增强注意力）
* 优化 solon-ai-skill-toolgateway 原来的三档展示升为四级，并添加分组（可增强注意力）
* 优化 solon-ai-agent ReActRequest, TeamRequest 支持叠加 options
* 修复 solon-ai-skill-cli ExpertSkill grep 可能失效的问题

### 3.10.0

* 添加 solon-ai-agent ThoughtChunk 流块
* 添加 solon-ai-agent Metrics.addMetrics 锁安全
* 添加 solon-ai-agent AgentChunk.getMeta 流块元数据（方便在传递过程中，做标识）
* 优化 solon-ai-agent ActionTask 执行完后就更新快照（避免可能的长时间没有更新快照）
* 修复 solon-ai-core ChatModel.outputSchema 配置无效的问题
* 修复 solon-ai-core 工具调用时‘可能’出现消息内容重复的问题（重要，v3.9.2 时出现）

### 3.9.7

* 添加 solon-ai-agent ThoughtChunk 流块
* 添加 solon-ai-agent Metrics.addMetrics 锁安全
* 添加 solon-ai-agent AgentChunk.getMeta 流块元数据（方便在传递过程中，做标识）
* 优化 solon-ai-agent ActionTask 执行完后就更新快照（避免可能的长时间没有更新快照）
* 修复 solon-ai-core ChatModel.outputSchema 配置无效的问题
* 修复 solon-ai-core 工具调用时‘可能’出现消息内容重复的问题（重要，v3.9.2 时出现）

### 3.9.6

* 添加 solon-ai-core ChatModel.outputSchema 配置支持（且与 Agent 统一基于方言实现，各方言可定制）
* 添加 solon-ai-core ChatModel.contextLength 配置支持（为 Agent 方便自动计算限制数）
* 添加 solon-ai-core AssistantMessage.getJsonContent 方法
* 添加 solon-ai-core MarkdownUtil 工具类
* 添加 solon-ai-dialect-gemini tool-call 的思考签名支持
* 添加 solon-ai-agent ReActChunk.getMetrics 方法
* 优化 solon-ai-core AbstractChatDialect 解析 tool 参数的兼容性
* 优化 solon-ai-dialect-dashscope 方言 支持流式传输与百炼联网搜索
* 优化 solon-ai-dialect-anthropic tool.args 不规范时的兼容性（比如 json 不完整）
* 优化 solon-ai-dialect-gemini 特定 provider 结束时返回 content 为空的消息才设置finishReason 导致结束标志被提前过滤
* 优化 solon-ai-agent ReActAgent onThought 事件，优先使用 getReasoning 获取 <think> 思考内容
* 优化 solon-ai-agent ReActAgent SummarizationInterceptor 添加 maxContextLength 控制参数（很重要）
* 优化 solon-ai-agent ActionChunk 分为 ActionEndChunk 和 ActionStartChunk（并保留 ActionChunk 标为弃用）
* 优化 solon-ai-agent 流式处理 `catch (Exception`，换成 `catch (Throwable`
* 优化 solon-ai-skill-restapi 每个 api 可以添加独立的验证机制
* 优化 solon-ai-skill-cli 避免 bash 把当前进程给删掉了
* 调整 solon-ai-agent 临时挂起操作，由 XxxTrace 转到 AgentSession（更统一）
* 调整 solon-ai-core Skill 的激活逻辑，如果 getInstruction 为空，则不进入 SystemPrompt（之前还要 description 为空）
* 修复 solon-ai-agent ReActAgent 思考事件触发逻辑，确保在所有情况下都被调用

### 3.9.5

* 新增 mcp-sdk 插件（从 solon-ai-mcp 独立出来）
* 新增 solon-ai-skill-toolgateway 插件
* 新增 solon-ai-search-tavily 插件
* 新增 solon-ai-ui-aisdk 插件
* 添加 solon-ai-core __sessionId 自动作为工具上下文和提示词属性（用于传递会话id）
* 添加 solon-ai-core SkillProvider 接口
* 添加 solon-ai-core AbsTool 类
* 添加 solon-ai-core AiUsage thinkTokens 字段
* 添加 solon-ai-core ChatConfig.getReasoningFieldName 自动处理，兼容 deepseek 新的变化
* 添加 solon-ai-core ChatModel options 配置自动转强类型（llm 不能接收str）
* 添加 solon-ai-agent ReActInterceptor.onObservation durationMs 参数（时长毫秒数）
* 添加 solon-ai-agent ReActChunk isNormal(用于识别是否为正常结束)
* 添加 solon-ai-skill-memory MemSkill 支持会话隔离与共享
* 优化 solon-ai-mcp 独立出 mcp-sdk 包
* 优化 solon-ai-dialect-claude 方言适配（及 api.kimi.com/coding 兼容性）
* 优化 solon-ai-dialect-openai 与 api.minimax.io 的兼容性
* 优化 solon-ai-agent SummarizationInterceptor
* 优化 solon-ai-agent ReActSystemPrompt 描述
* 优化 solon-ai-skill-memory MemSkill 取消 userId 参数，自动从上下文中取
* 优化 solon-ai-skill-cli
* 优化 Solon AI Skill 指令层级控制
* 调整 solon-ai-dialect-claude 更名为 solon-ai-dialect-anthropic（更合适）

### 3.9.4

* 新增 solon-ai-skill-lucene 插件
* 新增 solon-ai-skill-diff 插件
* 新增 solon-ai-skill-memory 插件
* 添加 solon-ai-core toolContext 自动转为 Prompt.attrs 的机制（方便 skill 传递）
* 添加 solon-ai-agent ReActChunk, TeamChunk getTrace 方法
* 添加 solon-ai-agent __sessionId 作为智能体的必要工具上下文（方便工具的会话数据隔离）
* 优化 solon-ai-core chatModel 方言的（不规范的）兼容性处理
* 优化 solon-ai-core buildToolCallBuilder 兼容（有些 llm stream ，会部分全量多次提供）
* 优化 solon-ai-agent SummarizationInterceptor 增加策略机制并内置4个策略
* 修复 solon-ai-core ChatModel.stream 过程异常时会破坏流响应的问题
* 修复 solon-ai-agent ReasonTask.callWithRetry 网络异常时会中断工作流的问题
* 修复 solon-ai-agent ReActAgent 流式请求时，可能无法记忆结果内容的问题

### 3.9.3

* 重构 solon-ai-agent Plan-ReAct 模式（相对之前，新设计智能、态动、按需）
* 优化 solon-ai-skill-cli CliSkill

### 3.9.2


* 新增 solon-ai-acp 插件（可以对接支持 acp 协议的 IDE）
* 添加 solon-ai-core ChatSessionProvider
* 添加 solon-ai-core FunctionTool:call 方法
* 添加 solon-ai-mcp FunctionPrompt:get 方法
* 添加 solon-ai-mcp FunctionResource:read 方法
* 添加 solon-ai-core ToolSchemaUtil.resultConvert 方法（将 tool 转换从内部，转到外部）
* 添加 solon-ai-agent ReActAgent maxStepsExtensible 配置，允许通过 HITL 扩容步数
* 优化 solon-ai-core ChatModel 与 DeepSeek-R1 兼容性
* 优化 solon-ai-agent ReActAgent 与 DeepSeek-R1 兼容性（手造的 AssistantMessage 需要自动补字段）
* 优化 solon-ai-agent FunctionTool 增加 tool 多模态与单模态兼容处理
* 优化 solon-ai-skill-cli CliSkill 进一步与 Claude Code 规范对齐（接近 100%）
* 优化 solon-ai-skill-cli CodeCLI exit 改为进程退出
* 优化 solon-ai-mcp 无心跳时，支持自动复位尝试
* 调整 solon-ai-mcp McpClientProvider 接口，优化多模态适配
* 调整 solon-ai-core 多模态体系（AiMedia 更名为 ContentBlock，）
* 调整 solon-ai-core UserMessage medias 更名为 blocks，hasMedias 更名为 isMultiModal
* 调整 solon-ai-core ToolMessage 添加 blocks，isMultiModal（工具支持多模态）
* 调整 solon-ai-core ChatDialect 接口，对多模态和 R1 更友好
* 移除 solon-ai-core 移除 ImageModel 体系，由 GenerateModel 体系接替（v3.5 时增加)
* 修复 solon-ai-agent ReActAgent 重试时会消息倍增的问题 
* 修复 solon-ai-agent ReActAgent，TeamAgent 在恢复执行时，会重置 Options 的问题


变更说明：

| 旧名（强调多媒体）                                  | 新名（强调多模态内容块）                                          |
|--------------------------------------------|-------------------------------------------------------|
| AiMedia                                    | ContentBlock                                          |
| Text                                       | TextBlock                                             |
| Image                                      | ImageBlock                                            |
| Audio                                      | AudioBlock                                            |
| Video                                      | VideoBlock                                            |
|                                            |                                                       |
| UserMessage.getMedias()                    | getBlocks()                                           |
| UserMessage.hasMedias()                    | isMultiModal()                                        |
| /                                          | ToolMessage.getBlocks()                               |
| /                                          | ToolMessage.isMultiModal()                            |
|                                            |                                                       |
| McpClientProvider.callTool()               | callToolRequest()                                     |
| McpClientProvider.callToolAsText()         | callTool()                                            |
| McpClientProvider.callToolAsImage()        | /                                                     |
| McpClientProvider.callToolAsAudio()        | /                                                     |
| McpClientProvider.readResource()           | readResourceRequest()                                 |
| McpClientProvider.readResourceAsText()     | readResource()                                        |
| McpClientProvider.readResourceAsBlob()     | /                                                     |
| McpClientProvider.getPrompt()              | getPromptRequest()                                    |
| McpClientProvider.getPromptAsMessage()     | getPrompt()                                           |
| `org.noear.solon.ai.mcp.server.prompt.*`   | `org.noear.solon.ai.chat.prompt.*` （prompt 是公用元素）     | 
| `org.noear.solon.ai.mcp.server.resource.*` | `org.noear.solon.ai.chat.resource.*` （resource 是公用元素） | 



### v3.9.1


* 新增 solon-ai-dialect-claude 插件
* 新增 solon-ai-dialect-openai OpenaiResponses 方言适配
* 新增 solon-ai-repo-weaviate 插件
* 新增 solon-ai-skill-cli 插件（含 CliSkill 和 SolonCodeCLI）
* 新增 solon-ai-skill-data 插件（含 RedisSkill）
* 新增 solon-ai-skill-file 插件（含 FileReadWriteSkill，ZipSkill）
* 新增 solon-ai-skill-generation 插件（含 ImageGenerationSkill， VideoGenerationSkill）
* 新增 solon-ai-skill-mail 插件（含 MailSkill）
* 新增 solon-ai-skill-pdf 插件（含 PdfSkill）
* 新增 solon-ai-skill-restapi 插件（含 RestApiSkill），内置 OpenApi3 和 swagger2 兼容适配（可扩展定制）
* 新增 solon-ai-skill-social 插件（含 DingTalkSkill，FeishuSkill，WeComSkill）
* 新增 solon-ai-skill-sys 插件（含 NodejsSkill，PythonSkill， ShellSkill，SystemClockSkill）
* 新增 solon-ai-skill-text2sql 插件（含 Text2SqlSkill）
* 新增 solon-ai-skill-web 插件（含 WebCrawlerSkill，WebSearchSkill）
* 添加 solon-ai-core ChatModel role 和 instruction 支持（提供类似 SimpleAgent 效果）
* 添加 solon-ai-core ChatModel.stream 背压流控支持
* 添加 solon-ai-core FileChatSession，RedisChatSession 聊天会话实现
* 添加 solon-ai-core ChatInterceptor.onPrepare 事件（勾子）
* 添加 solon-ai-core Prompt.removeLastMessage, removeLastAssistantMessage 方法
* 添加 solon-ai-agent FileAgentSession 智能体会话实现
* 添加 solon-ai-agent ReActTrace, TeamTrace pending 机制（方便按需拦截挂机）
* 添加 solon-ai-agent feedbackMode 模式（默认为 false）
* 添加 solon-ai-agent ReActInterceptor.onObservation toolName 参数
* 添加 solon-ai-agent HITL 专属接口，进一步优化人工机制（同意，拒绝，跳过）
* 添加 solon-ai-agent stream 流式输出机制
* 优化 solon-ai-agent ReActAgent 添加主动挂机的能力（处理无法完成的任务时，可避免幻觉轮询）
* 优化 solon-ai-agent description + systemPrompt 标为弃用，改由 role + instruction 替代（简化了）
* 优化 solon-ai-agent 内置的 Team 协议（如 ReActProtocol，SwarmProtocol 等）
* 优化 solon-ai-agent 内置的 ToolRetryInterceptor，ToolSanitizerInterceptor 等
* 优化 solon-ai-agent AgentSession 接口 updateSnapshot 取消入参
* 优化 solon-ai-agent ActionTask 取消正则匹配 json 方式，改用 json 流式读取方式
* 调整 solon-ai-core InMemoryChatSession systemMessages 标为弃用（不建议放到会话里，不方便做结构化的管理）
* 调整 solon-ai-agent 取消 title（减少迷惑），systemMessage 标为弃用。改用 role 和 instruction
* 调整 solon-ai-core ChatModel.stream 返回由 Publisher 改为 Flux
* 调整 solon-ai-agent enablePlanning 更名为 planningMode （前者标为弃用）
* 修复 solon-ai-agent ReActAgent 新话题开始时， ReActTrace 没有重置干净的问题
* 修复 solon-ai-agent SimpleAgent 没有接入协作协议 prepareAgentPrompt 勾子
* qdrant 升为 1.16.2

### v3.9.0

* 添加 solon-ai-core Prompt 更多方法（getLastAssistantMessage, size 等）
* 添加 solon-ai-core ChatSession 更多方法（getLatestMessages 等）
* 添加 solon-ai-agent Agent.call(session) 方法（作为恢复执行的入口）
* 添加 solon-ai-agent Agent.prompt 方法
* 添加 solon-ai-agent AgentResponse 接口，统一响应界面
* 添加 solon-ai-agent Trace.workingMemory 概念
* 优化 solon-ai-core Skill 接口设计，getTools 增加 prompt 参数
* 优化 solon-ai-agent AgentSession 接口，改为扩展自 ChatSession （只需入口 agent 有历史消息）
* 优化 solon-ai-agent 部分接口设计

### v3.8.4

* 新增 `solon-ai-core` Solon AI Skills（技能）体系
* 新增 `solon-ai-search-bocha` 插件
* 添加 `solon-ai-core` defaultToolsContextPut 方法
* 添加 `solon-ai-core` Prompt attrPut, attr 属性相关方法（可以在拦截时控制权限）
* 添加 `solon-ai-core` FunctionTool meta 元数据相关方法（可对描述语进行染色）
* 添加 `solon-ai-mcp` FunctionPrompt meta 元数据相关方法（可对描述语进行染色）
* 添加 `solon-ai-mcp` FunctionResource meta 元数据相关方法（可对描述语进行染色）
* 添加 `solon-ai-agent` NoneProtocol（无协议模式）
* 添加 `solon-ai-agent` ReActAgent Plan 支持（默认为关闭）。
* 添加 `solon-ai-agent`  SimpleInterceptor 替代 ChatInterceptor，方便后续扩展
* 优化 `solon-ai-core` Gemini 方言适配
* 优化 `solon-ai-core` Prompt 接口，方法更丰富
* 优化 `solon-ai-agent` A2AProtocol 协议代码
* 优化 `solon-ai-agent` SwarmProtocol 协议代码
* 优化 `solon-ai-agent` HierarchicalProtocol 协议代码
* 优化 `solon-ai-agent` SequentialProtocol 协议代码，添加专属任务（可节省 token）
* 修复 `solon-ai-core` ToolSchemaUtil `Param` 注解别名没有生效的问题
* 修复 `solon-ai-agent` ReActAgent 没有拦截器时 ReActAgent 不能传递 toolsContext 的问题
* 调整 `solon-ai-agent` Agent 相关接口保持与 ChatModel 一致性

### v3.8.3

* 修复 `solon-ai-agent` ActionTask 与 mcp 的兼容性（取消 trace 作为工具上下文注入，它不能被mcp 序列化）

### v3.8.2

* 修复 `solon-ai-agent` SimpleAgent 没有反回干净内容的问题（带了 think）
* 添加 `solon-ai-agent` SimpleRequest
* 添加 `solon-ai-agent` SimpleAgent.prompt 方法（为三个智能体提供相同体验）


### v3.8.1

* 新增 `solon-ai-agent` 插件
* 新增 `solon-ai-dialect-gemini` 插件
* 添加 `solon-ai-core` autoToolCall 聊天模型选项（默认为 true）
* 添加 `solon-ai-core` ChatResponse:getResultContent
* 添加 `solon-ai-core` AssistantMessage.toBean 方法。
* 优化 `solon-ai-core` AssistantMessage.getResultContent 处理
* 调整 `solon-ai-croe` ChatSession 不再扩展 Prompt（打断两者关系，后者定位偏固定数据


新增三种模式的智能体：

| 智能体          | 模式描述                                      |
|--------------|-------------------------------------------|
| SimpleAgent  | 简单模式                                      | 
| ReActAgent   | 自省模式，思考+行动。                               | 
| TeamAgent    | 协作模式，分工+编排。多智能体系统（multi-agent system，MAS） | 


新特性示例：ReActAgent

```java
public class DemoApp {
  public static void main(String[] args) throws Throwable {
    ChatModel chatModel = LlmUtil.getChatModel();

    SimpleAgent robot = SimpleAgent.of(chatModel)
            .toolAdd(new MethodToolProvider(new TimeTool()))
            .build();

    String answer = robot.prompt("现在几点了？").call().getContent();

    System.out.println("Robot 答复: " + answer);
  }

  public static class TimeTool {
    @ToolMapping(description = "获取当前系统时间")
    public String getTime() {
      return LocalDateTime.now().toString();
    }
  }
}
```

### v3.8.0

重要变化：

* mcp-java-sdk 升为 v0.17 （支持 2025-06-18 版本协议）
* 添加 mcp-server McpChannel.STREAMABLE_STATELESS 通道支持（集群友好）
* 添加 mcp-server 异步支持

具体更新：

* 添加 solon-ai FunctionPrompt:handleAsync（用于 mcp-server 异步支持）
* 添加 solon-ai FunctionResource:handleAsync（用于 mcp-server 异步支持）
* 添加 solon-ai FunctionTool:handleAsync（用于 mcp-server 异步支持）
* 添加 solon-ai-core ChatMessage:toNdjson,fromNdjson 方法（替代 ChatSession:toNdjson, loadNdjson），新方法机制上更自由
* 添加 solon-ai-core ToolSchemaUtil.jsonSchema Publisher 泛型支持
* 添加 solon-ai-core ToolSchemaUtil.isIgnoreOutputSchema Publisher 泛型支持
* 添加 solon-ai-mcp mcp-java-sdk v0.17 适配（支持 2025-06-18 版本协议）
* 添加 solon-ai-mcp mcp-server 异步支持
* 添加 solon-ai-mcp mcp-server streamable_stateless 支持
* 添加 solon-ai-mcp Tool,Resource,Prompt 对 org.reactivestreams.Publisher<X> 异步返回支持
* 添加 solon-ai-mcp McpServerHost 服务宿主接口，用于隔离有状态与无状态服务
* 添加 solon-ai-mcp McpChannel.STREAMABLE_STATELESS （服务端）无状态会话
* 添加 solon-ai-mcp McpClientProvider:customize 方法（用于扩展 roots, sampling 等）
* 添加 solon-ai-mcp mcpServer McpAsyncServerExchange 注入支持（用于扩展 roots, sampling 等）
* 优化 solon-ai-dialect-openai claude 兼容性
* 优化 solon-ai-mcp mcp StreamableHttp 模式下 服务端正常返回时 客户端异常日志打印的情况
* 调整 solon-ai-mcp getResourceTemplates、getResources 不再共享注册
* 调整 solon-ai-mcp McpServerManager 内部接口更名为 McpPrimitivesRegistry （MCP 原语注册器）
* 调整 solon-ai-mcp McpClientProvider 默认不启用心跳机制（随着 mcp-sdk 的成熟，server 都有心跳机制了）
* mcp ToolCapabilities 添加 @JsonIgnoreProperties(ignoreUnknown = true)


新特性展示：1.MCP 无状态会话（STREAMABLE_STATELESS）和 2.CompletableFuture 异步MCP工具

```java
@McpServerEndpoint(channel = McpChannel.STREAMABLE_STATELESS, mcpEndpoint = "/mcp1")
public class McpServerTool {
    @ToolMapping(description = "查询天气预报", returnDirect = true)
    public CompletableFuture<String> getWeather(@Param(description = "城市位置") String location) {
        return CompletableFuture.completedFuture("晴，14度");
    }
}
```

传输方式对应表：（服务端与客户端，须使用对应的传输方式才可通讯）

| 服务端                   | 客户端         |  备注               |
|-----------------------|-------------|-----------------|
| STDIO                 | STDIO       |                 |
| SSE                   | SSE         |                 |
| STREAMABLE            | STREAMABLE  |                 |
| STREAMABLE_STATELESS  | STREAMABLE  | 对 server 集群很友好  |


* STREAMABLE_STATELESS 集群，不需要 ip_hash，但“原语”变化后无法通知 client


### v3.7.4

* 添加 solon-ai-core ChatMessage:toNdjson,fromNdjson 方法（替代 ChatSession:toNdjson, loadNdjson），新方法机制上更自由
* 优化 solon-ai-mcp mcp StreamableHttp 模式下 服务端正常返回时 客户端异常日志打印的情况

### v3.7.3

* 优化 solon-ai-core ToolSchemaUtil 扩展能力，支持注解处理定制（方便第三方集成）
* 优化 solon-ai-mcp MCP 客户端与服务端异常链接状态 立即返回

### v3.7.2

* 引入 snack4-jsonschema 优化 ToolSchemaUtil
* 添加 solon-ai-core AiMedia:metas 方法
* 添加 solon-ai-core GenerateModel defaultOptions 配置支持
* 添加 solon-ai-core ToolSchemaUtil:buildInputParams 方法，支持 @Body 注解
* 添加 solon-ai-flow GenerateModelCom 组件
* 修复 solon-ai bean 有其它框架代理后，不能识别注解的问题
* 修复 solon-ai ToolSchemaUtil 部分泛型识别失败的问题
* 修复 solon-ai-flow ChatModelCom 加载 chatConfig 配置会出错的问题
* 修复 solon-ai-mcp/mcp-sdk listTools 的 nextCursor 为空时，不能正常获取问题


### v3.7.0

* 优化 solon-ai-core ToolSchemaUtil:paramOf 方法，增加泛型支持
* 优化 solon-ai-core ToolSchemaUtil:outputSchema 泛型处理
* 修复 solon-ai parseToolCall 接收 stream 中间消息时可能会异常（添加 hasNestedJsonBlock 检测）
* 修复 solon-ai-mcp 可能出现 Unknown media type 错误（取消 request.contentType 空设置）
* jedis 升为 7.0.0 （通过 redisx）



### 3.6.5


* 修复 solon-ai-mcp/mcp-sdk listTools 的 nextCursor 为空时，不能正常获取问题

### 3.6.4

* 修复 solon-ai parseToolCall 接收 stream 中间消息时可能会异常（添加 hasNestedJsonBlock 检测）

### 3.6.3

* 修复 solon-ai-mcp 可能出现 Unknown media type 错误（取消 request.contentType 空设置）
* 优化 solon-ai-mcp 取消 request.contentType("") 设置


### 3.6.1

* 修复 solon-ai-core chatModel:stream 长流输出可能出错的问题


### 3.6.0

* 优化 solon-ai-core XxxMessageTemplate 使用 SnEL 替代 TmplUtil


### 3.5.9

* 修复 solon-ai-mcp/mcp-sdk listTools 的 nextCursor 为空时，不能正常获取问题

### 3.5.8

* 修复 solon-ai parseToolCall 接收 stream 中间消息时可能会异常（添加 hasNestedJsonBlock 检测）

### 3.5.7

* 修复 solon-ai-mcp 可能出现 Unknown media type 错误（取消 request.contentType 空设置）
* 优化 solon-ai-mcp 取消 request.contentType("") 设置

### 3.5.6

* 修复 solon-ai-core chatModel:stream 长流输出可能出错的问题
* mcp 移除 WebRxSseClientTransport:sendMessage 的 accept 声明（是在 3.5.5 时加的，此处没必要）

### 3.5.5

* 添加 McpClientProvider:httpFactory 默认为 jdkhttp（okhttp 有些平台不兼容），不再随 HttpUtils 的全局走
* 添加 solon-ai-mcp McpClientProvider:httpFactory 默认为 jdkhttp（okhttp 有些平台不兼容），不再随 HttpUtils 的全局走

### 3.5.3

* 优化 solon-ai-core chatModel.stream 与背压处理的兼容性
* 调整 solon-ai-map getPrompt,readResource,callTool 取消自动异常转换（侧重原始返回）
* 调整 solon-ai-map callTool 错误结果传递，自动添加 'Error:' （方便 llm 识别）
* 修复 solon-ai-mcp callTool isError=true 时，不能正常与 llm 交互的问题
* 修复 solon-ai-mcp ToolAnnotations:returnDirect 为 null 时的传递兼容性

### 3.5.2

* 添加 solon-ai-core ToolSchemaUtil 简化方法
* 添加 solon-ai-mcp McpClientProperties:timeout 属性，方便简化超时配置（可省略 httpTimeout, requestTimeout, initializationTimeout）
* 添加 solon-ai-mcp McpClientProvider:toolsChangeConsumer,resourcesChangeConsumer,resourcesUpdateConsumer,promptsChangeConsumer 配置支持
* 添加 solon-ai-mcp McpClientProvider 缓存锁和变更刷新控制
* 添加 solon-ai-mcp IMcpServerEndpoint 接口（方便可批量获取组件）
* 优化 solon-ai-core RepositoryStorable 接口定义，用 save 替代 insert(标为弃用)
* 调整 solon-ai-core FunctionToolDesc:doHandle 改用 ToolHandler 参数类型（之前为 Function），方便传递异常

### 3.5.1

* 新增 solon-ai-a2a 插件
* 新增 solon-ai-core GenerateModel 接口，替代 ImageModel
* 新增 solon-ai-core ChatModel 增加多媒体内容输出（增强感知型模型的兼容，比如输出图片或视频）
* 新增 solon-ai-core ImageModel 增加结构体提示语输入（比如图片编辑模型）
* 添加 solon-ai-core AbstractChatDialect 对多媒体内容输出的支持
* 添加 solon-ai-core AssistantMessage:contentRaw 原生内容（可能是 String、Map、List、null）
* 添加 solon-ai-dialect-dashscope 通过接口地址识别方言
* 添加 solon-ai-mcp McpServerEndpointProvider:Builder 添加 context-path 配置
* 优化 solon-ai-mcp McpClientProvider 配置向 McpServers json 格式上靠
* 修复 solon-ai-core `think-> tool -> think` 时，工具调用的内容无法加入到对话的问题
* 修复 solon-ai-mcp 服务端传输层的会话长连会超时的问题
* 修复 solon-ai-mcp 客户端提供者心跳失效的问题
* 修复 solon-ai-mcp SSE 传输时 message 端点未附加 context-path 的问题
* mcp `McpSchema:*Capabilities` 添加 `@JsonIgnoreProperties(ignoreUnknown = true)` 增强跨协议版本兼容性

### 3.5.0

* 新增 solon-ai-mcp mcp-java-sdk v0.11.0 适配（支持 2025-03-26 版本协议）
* 调整 solon-ai-mcp channel 取消默认值（之前为 sse），且为必填（利于协议升级过度，有明确的开发时、启动时提醒）
  * 如果默认值仍为 sse ，升级后可能忘了修改了升级
  * 如果默认值改为 streamable，升级后会造成不兼容

### 3.4.5

* 优化 solon-ai-core chatModel.stream 与背压处理的兼容性
* 调整 solon-ai-map getPrompt,readResource,callTool 取消自动异常转换（侧重原始返回）
* 调整 solon-ai-map callTool 错误结果传递，自动添加 'Error:' （方便 llm 识别）
* 修复 solon-ai-mcp callTool isError=true 时，不能正常与 llm 交互的问题

### 3.4.4

* 添加 solon-ai-dialect-dashscope 通过接口地址识别方言
* 添加 solon-ai-mcp McpClientProperties:toolsChangeConsumer,resourcesChangeConsumer,resourcesUpdateConsumer,promptsChangeConsumer 配置支持
* 添加 solon-ai-mcp McpClientProvider 缓存锁和变更刷新控制
* 修复 solon-ai-core think-> tool -> think 时，工具调用的内容无法加入到对话的问题
* mcp XxxCapabilities 添加 `ignoreUnknown=true` 增强旧协议兼容


### 3.4.3

* 新增 solon-ai-repo-mysql 插件
* 添加 solon-ai-core InMemoryChatSession（语义清晰） 替代 ChatSessionDefault（标为弃用）
* 优化 solon-ai-core ChatRequestDescDefault http 异常转换描述
* 优化 solon-ai-core 方言的 tool_calls 消息的构建（更好的兼容 vllm）
* 优化 solon-ai-mcp JsonSchema.additionalProperties 兼容性（兼容 bool, map）
* 优化 solon-ai-mcp McpClientProvider 改为 McpAsyncClient（为异步需求提供支持）
* 优化 solon-ai-mcp 初始化控制（禁用 connectOnInit），增加连接打印


### 3.4.1

* 新增 solon-ai-repo-pgvector 插件
* 新增 solon-ai-search-baidu 插件
* 添加 solon-ai-core `TextLoader(byte[])(SupplierEx<InputStream>)` 构造方法
* 添加 solon-ai-core `ChatConfig:defaultToolsContext`（默认工具上下文）, `defaultOptions`（默认选项） 属性
* 添加 solon-ai-core `RepositoryStorable:insert(list,progressCallback)` 和 `asyncInsert(list,progressCallback)` 方法，支持进度获取
* 添加 solon-ai-mcp 客户端 ssl 定制支持
* 优化 solon-ai 方言 think 思考内容和字段的兼容性处理
* 优化 solon-ai 方言处理与 modelscope（魔搭社区）的兼容性
* 优化 solon-ai 方言处理与 siliconflow（硅基流动）的兼容性
* 优化 solon-ai 方言处理的流式节点识别兼容性
* 优化 solon-ai 用户消息的请求构建（当内容为空时，不添加 text）
* 优化 solon-ai-mcp McpClientProvider 心跳间隔控制（5s 以下忽略）
* 优化 solon-ai-mcp McpServerContext 增加 stdio 代理支持（环境变量自动转为 ctx:header）
* mcp WebRxSseClientTransport 添加 debug 日志打印


### 3.4.0

* 新增 solon-ai-repo-opensearch 插件
* 添加 solon-ai Options:toolsContext 方法
* 调整 solon-ai-core ToolCallResultJsonConverter 更名为 ToolCallResultConverterDefault 并添加序列化插件支持
* 调整 solon-ai-mcp PromptMapping，ResourceMapping 取消 resultConverter 属性（没必要）
* 修复 solon-ai-core ChatModel:stream:doOnNext 可能无法获取 isFinished=true 情况

### 3.3.3

* 优化 solon-ai-core ToolSchemaUtil 对 Map 的处理（有些框架，太细不支持）
* 优化 solon-ai-core ToolSchemaUtil 对 Collection 的处理（有些框架，太细不支持）
* 优化 solon-ai-mcp MethodPromptProvider,MethodResourceProvider 改用 clz 构建(兼容外部代理情况)
* 优化 solon-ai-core MethodToolProvider 改用 clz 构建(兼容外部代理情况)
* 添加 solon-ai-core RepositoryStorable:insert(Doc...) 方法
* 添加 solon-ai-mcp McpServerEndpoint:enableOutputSchema 支持（默认为 false）
* 调整 solon-ai-core ToolCallResultConverter 接口定义（增加返回类型参数）
* 调整 solon-ai-core 移除 QueryCondition:doFilter 方法（避免误解）
* 调整 solon-ai-mcp tool,resource 结果默认处理改为 ToolCallResultJsonConverter
* 调整 solon-ai-repo-elasticsearch 搜索类型，默认改为相似搜索（之前为精准，需要脚本权限）
* mcp 优化 WebRxSseClientTransport 连接等待处理（异常时立即结束）
* elasticsearch-rest-high-level-client 升为 7.17.28
* milvus-sdk-java 升为 2.5.10
* vectordatabase-sdk-java 升为 2.4.5

### 3.3.2

* 优化 solon-ai-flow 插件
* 添加 solon-ai-core ChatInterceptor 聊天拦截机制
* 添加 solon-ai-core ChatMessage:ofUserAugment 替代 augment（后者标为弃用）
* 添加 solon-ai-core ProxyDesc 的 Serializable 接口实现
* 添加 solon-ai-core ChatOptions:response_format 方法
* 添加 solon-ai-core AssistantMessage:getSearchResultsRaw 方法
* 添加 solon-ai-mcp McpServerEndpointProvider:getMessageEndpoint 方法
* 添加 solon-ai-mcp McpServerParameters http 参数支持
* 添加 solon-ai-mcp McpClientProvider 本地缓存支持（默认 30秒）
* 添加 solon-ai-mcp 原语处理异常日志
* 优化 solon-ai-core ChatConfig.toString （增加 proxy）
* 优化 solon-ai-core Tool:outputSchema 改为必出
* 优化 solon-ai-core 添加 ToolCallException 异常类型，用于 tool call 异常传递（之前为 ChatException）
* 优化 solon-ai OpenaiChatDialect 方言，tool 消息也附带所有的 tools 元信息（之前被过滤了）
* 优化 solon-ai-mcp McpServerContext 同步连接时的请求参数，方便在 Tool 方法里获取
* 优化 solon-ai-mcp McpProviders 在 sse 时，支持 env 也作为 header 处理（有些服务方的配置，是用 env 的）
* 优化 solon-ai-mcp 取消 RefererFunctionTool（由 FunctionToolDesc 替代）
* 优化 solon-ai-mcp 基于 McpServerParameters 的构建能力
* 修复 solon-ai-flow ChatModel 没有加载 mcpServers 配置的问题
* mcp 适配优化 WebRxSseClientTransport 关闭时同时取消 sse 订阅（避免线程占用）


### 3.3.1

* 新增 solon-ai-flow 插件
* 新增 solon-ai-load-ddl 插件
* 添加 solon-ai-core ChatMessage:ofUser(media) 方法
* 添加 solon-ai-core ChatSession:addMessage(Prompt) 方法
* 添加 solon-ai-core ChatSession:addMessage(Collection) 方法
* 添加 solon-ai-core RerankingConfig,RerankingModel toString 方法
* 添加 solon-ai-core 模型的网络代理支持（支持简单配置，和复杂构建）
* 添加 solon-ai-mcp 客户端的网络代理简单配置支持
* 添加 solon-ai-mcp messageEndpoint 端点配置支持（应对特殊需求，一般自动更好）
* 添加 solon-ai-mcp ToolMapping,ResourceMapping 注解方法对 Produces 注解的支持（用它可指定结果转换处理）
* 添加 solon-ai-mcp ToolCallResultConverter:matched 方法
* 添加 solon-ai-mcp 资源模板的响应适配
* 添加 solon-ai-mcp McpClientProvider:getResourceTemplates 方法
* 添加 solon-ai-mcp 检查原语是否存在的方法（hasTool, hasPrompt, hasResource）
* 添加 solon-ai-mcp 提示语支持 UserMessage 拆解成多条 mcp 内容（如果，同时有媒体和文本的话）
* 优化 solon-ai-core tool 空参数时的不同大模型兼容性
* 优化 solon-ai-core ChatSession 的作用，为限数提供支持
* 优化 solon-ai-core MethodFunctionTool 移除对 Mapping 注解的支持（语意更清楚，之前 MethodToolProvider 已经移除，这个落了）
* 优化 solon-ai-core EmbeddingRequest，ImageRequest，RerankingRequest 当 resp.getError() 非 null 时，直接出抛异常
* 优化 solon-ai-mcp 取消 MethodFunctionResource 对反回类型的限制（增加了 resultConverter 转换处理）
* 优化 solon-ai-mcp McpServerEndpointProvider 支持零添加原语，postStart 后，可添加原语
* 修复 solon-ai ChatRequestDefault:stream 请求 r1 时，可能会产生两次 tink 消息发射

### 3.3.0

* 新增 solon-ai-repo-dashvector 插件
* 插件 solon-ai 三次预览
* 插件 solon-ai-mcp 二次预览
* 调整 solon-ai 移除 ToolParam 注解，改用 `Param` 注解（通用参数注解）
* 调整 solon-ai ToolMapping 注解移到 `org.noear.solon.ai.annotation`
* 调整 solon-ai FunctionToolDesc:param 改为 `paramAdd` 风格
* 调整 solon-ai MethodToolProvider 取消对 Mapping 注解的支持（利于跨生态体验的统一性）
* 调整 solon-ai 拆分为 solon-ai-core 和 solon-ai-model-dialects（方便适配与扩展）
* 调整 solon-ai 模型方言改为插件扩展方式
* 调整 solon-ai-mcp McpClientToolProvider 更名为 McpClientProvider（实现的接口变多了）
* 优化 solon-ai 允许 MethodFunctionTool,MethodFunctionPrompt,MethodFunctionResource 没有 solon 上下文的用况
* 优化 solon-ai-core `model.options(o->{})` 可多次调用
* 优化 solon-ai-mcp McpClientProvider 同时实现 ResourceProvider, PromptProvider 接口
* 优化 solon-ai-repo-redis metadataIndexFields 更名为 `metadataFields` （原名标为弃用）
* 添加 solon-ai-core ChatSubscriberProxy 用于控制外部订阅者，只触发一次 onSubscribe
* 添加 solon-ai-mcp McpClientProperties:httpProxy 配置
* 添加 solon-ai-mcp McpClientToolProvider isStarted 状态位（把心跳开始，转为第一次调用这后）
* 添加 solon-ai-mcp McpClientToolProvider:readResourceAsText,readResource,getPromptAsMessages,getPrompt 方法
* 添加 solon-ai-mcp McpServerEndpointProvider:getVersion,getChannel,getSseEndpoint,getTools,getServer 方法
* 添加 solon-ai-mcp McpServerEndpointProvider:addResource,addPrompt 方法
* 添加 solon-ai-mcp McpServerEndpointProvider:Builder:channel 方法
* 添加 solon-ai-mcp ResourceMapping 和 PromptMapping 注解（支持资源与提示语服务）
* 添加 solon-ai-mcp McpServerEndpoint AOP 支持（可支持 solono auth 注解鉴权）
* 添加 solon-ai-mcp McpServerEndpoint 实体参数支持（可支持 solon web 的实体参数、注解相通）
* 添加 solon-ai-mpc `Tool.returnDirect` 属性透传（前后端都有 solon-ai 时有效，目前还不是规范）

### 3.2.1

* 添加 solon-ai ChatRequestDefault http 状态异常处理
* 添加 solon-ai ToolCallResultConverter 接口（工具调用结果转换器）
* 添加 solon-ai ToolCall 添加 Mapping 和 Param 注解（支持与 web api 打通）
* 添加 solon-ai Tool.returnDirect 属性，用于直接返回给调用者（mcp 目前无法传导此属性，只能地本地用）
* 添加 solon-ai-mcp McpChannel 通道（stdio, sse），实现不通道的支持
* 添加 solon-ai-mcp stdio 通道交换流支持
* 添加 solon-ai-mcp McpClientToolProvider 断线重连机制
* 添加 solon-ai-mcp McpClientProperties:fromMcpServers 方法
* 调整 solon-ai-mcp McpClientToolProvider.Builder:header 更名为 headerSet。保持与 ChatModel:Builder 相同风格
* 调整 solon-ai ToolCallResultConverter 申明不再扩展自 Converter，避免冲突
* 修复 solon-ai-mcp McpClientToolProvider 会丢失 queryString 的问题
* 修复 solon-ai-load-word WordLoader 流使用错误问题
* 修复 solon-ai ollama 方言，在多工具调用时产生 index 混乱的问题

### 3.2.0

* 新增 solon-ai-mcp
* 调整 函数(Function)相关的概念改为工具概念(Tool)。调整后适合 mcp 的相关概念

### v3.1.2

* 新增 solon-ai-repo-chroma 插件
* 优化 solon-ai-repo-tcvectordb 插件相似度处理
* 优化 solon-ai-repo-elasticsearch 插件相似度处理

### v3.1.1

* 新增 solon-ai-load-ppt 插件，添加对 ppt, pptx 文档的解析
* 新增 solon-ai-load-word 插件，添加对 doc, docx 文档的解析
* 新增 solon-ai-repo-qdrant 插件
* 新增 solon-ai-repo-tcvectordb 插件
* 新增solon-ai-repo-elasticsearch 插件
* 添加 solon-ai ChatResponse:getAggregationMessage(), isStream() 方法
* 添加 solon-ai ChatSession 自动登记处理
* 调整 solon-ai ChatDialect:parseResponseJson 定义
* 修复 solon-ai 在流式调用时 function call 出错的问题