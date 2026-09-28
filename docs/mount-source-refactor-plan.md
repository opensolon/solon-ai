# MountSource 与 SkillCatalog 统一资源体系重构方案

## 1. 目标

将工作区、本地目录、Classpath/Jar 等内容来源统一抽象为 `MountSource`。用户未来可以自行扩展其他来源（例如数据库），但数据库来源不属于本轮交付。工作区由 `FileMountSource` 包装；Terminal 对非本地来源通过 `MountSource` 执行文件操作，本地来源保留已有的 `Path/Files` 安全实现，统一使用逻辑路径解析。bash、LSP、文件诊断等需要真实本地路径的能力仅使用本地路径能力。

原 `SkillProvider` 的来源适配职责由 `MountSource` 取代，技能领域职责由 `SkillCatalog`/`DefaultSkillCatalog` 取代。新调用关系：

```text
SkillTalent -> SkillCatalog -> MountManager -> MountSource
TerminalTalent -> MountManager.resolveResource() -> MountSource
AgentCatalog/AgentManager -> MountManager -> MountSource
```

## 2. 最终分层

- `MountSource`：资源来源和文件树操作，不理解 Skill/Agent。
- `Mount`：alias、description、type、enabled、writeable 等挂载配置，以及一个 `MountSource`。
- `MountManager`：工作区来源、挂载注册/删除、别名和逻辑路径解析、权限与生命周期；当前仍保留技能禁用配置和 Agent 查询/刷新兼容入口，见第 13 节。
- `ResolvedResource`：用户输入解析后的 `source + sourcePath + logicalPath`。
- `MountEntry`：来源中的虚拟文件或目录条目，不暴露 `Path`。
- `DefaultSkillCatalog` 与 `DefaultAgentCatalog`：各自在内部扫描来源，不单独引入通用 `MountScanner`。
- `SkillCatalog`：技能索引、搜索、读取、刷新和冲突处理；技能禁用状态暂由 `MountManager` 保管。
- `DefaultSkillCatalog`：默认 SkillCatalog 实现。
- `SkillDescriptor`/`SkillContent`：来源无关的技能元数据和读取结果。

## 3. 核心 API

```java
public interface MountSource {
    String getScheme();
    String normalize(String path);
    MountEntry stat(String path) throws IOException;
    default boolean exists(String path) throws IOException { return stat(path) != null; }
    default boolean isDirectory(String path) throws IOException {
        MountEntry e = stat(path);
        return e != null && e.isDirectory();
    }
    List<MountEntry> list(String path) throws IOException;
    List<MountEntry> find(String path, FindOptions options) throws IOException;
    InputStream openRead(String path) throws IOException;
    OutputStream openWrite(String path, WriteOptions options) throws IOException;
    void delete(String path) throws IOException;
    void move(String source, String target, MoveOptions options) throws IOException;
    MountCapabilities capabilities();
    default Optional<Path> getLocalPath(String path) { return Optional.empty(); }
    default Optional<Path> materialize(String path, Path targetDirectory) throws IOException {
        return Optional.empty();
    }
    default void close() throws IOException { }
}
```

`MountEntry` 至少包含 `path/name/directory/size/lastModified/version`；`MountCapabilities` 至少描述 readable、writable、searchable、editable、deletable、movable、watchable、shellAccessible、localPathAccessible、materializable。

## 4. 来源实现

### FileMountSource

- 根目录绑定一个真实 `Path`，内部负责规范化、`..` 防穿越、符号链接和 `toRealPath` 检查。
- 实现 stat/list/find/read/write/delete/move/getLocalPath。
- 工作区和普通本地挂载都使用此实现。
- 支持真实 Path 和 bash；是否写入仍受 Mount.writeable 控制。是否增加 WatchService 取决于实际使用需求。

### ClasspathMountSource

- 读取普通 classpath 目录、Jar 条目和可选资源索引。
- 默认只读，不支持 write/edit/delete/move/watch/bash。
- 支持 `META-INF/solon/ai/skills/index` 或约定的 basePath index；没有索引时兼容 JarEntry 扫描。
- `scripts/` 可以被 read/ls/glob/grep 访问，但执行前必须显式物化到本地临时目录。

### 未来扩展来源

`MountSource` 允许用户按需实现数据库等来源；本轮不提供 JDBC 实现或独立数据库模块。

## 5. 工作区和 MountManager

`MountManager` 持有 `FileMountSource workspaceSource`。空路径、`.`、普通相对路径和 `./xxx` 解析到工作区；`@alias/xxx` 解析到对应 Mount 的 Source。可以提供 `@workspace` 保留别名，但不得维护第二份配置。

新增主 API：

```java
ResolvedResource resolveResource(String path);
Optional<Path> resolveLocalPath(String path);
FileMountSource getWorkspaceSource();
Mount register(Mount mount);
Mount getMount(String alias);
Collection<Mount> getMounts();
```

`MountDir` 已删除；本地目录直接注册 `Mount` 并配置 `FileMountSource`。`resolve(Path,String)` 如仍存在，仅供本地路径调用方使用，不作为虚拟资源的主入口。

## 6. SkillCatalog 替代 SkillProvider

删除或 deprecated：

- `SkillProvider`
- `MountSkillProvider`

新增：

```java
public interface SkillCatalog {
    void refresh();
    default void refreshByMount(String mountAlias) { refresh(); }
    int getSkillCount();
    Collection<SkillDescriptor> getDescriptors();
    Collection<SkillDescriptor> searchDescriptors(String query);
    SkillDescriptor getDescriptor(String name);
    SkillContent readContent(String name);
    boolean isAllowed(SkillDescriptor descriptor);
}
```

`DefaultSkillCatalog` 通过 `MountManager` 遍历启用的 SKILLS 挂载，使用 `MountSource.find/openRead` 发现和读取 `SKILL.md`，以 aliasPath 建立索引，处理短名称冲突、禁用规则、刷新和关联文件展示。扫描逻辑保留在 Catalog 内，无需另设扫描器。`SkillTalent` 只依赖 SkillCatalog。

`SkillDescriptor` 保留调用方需要的 id/name/description/version；来源定位由 Catalog 内部记录维护。`SkillContent` 保存 descriptor、markdown 和渲染后的文本；关联文件可在读取时从来源生成展示，不要求暴露 `MountSource` 或 `List<MountEntry>`。未来的来源扩展只需实现 `MountSource`，不要求额外定义技能 Provider。

## 7. Agent

Agent 扫描由 `AgentCatalog` 承担；`MountManager` 持有单份默认 Catalog，供兼容查询入口和 `AgentManager` 共用。`AgentManager` 保留内置及运行时代理管理；挂载代理按当前目录读取，不缓存解析结果。`AgentMd` 保存 `MountSource + sourcePath`，通过 `openRead` 读取。

## 8. TerminalTalent

非本地来源通过 `MountManager.resolveResource` 得到 `ResolvedResource`，然后调用下列 Source 操作；本地来源沿用现有 `Path/Files` 安全实现：

| 工具 | Source 操作 |
|---|---|
| ls | list/find |
| read | openRead |
| write | openWrite |
| edit | openRead + MountTextEditService + openWrite |
| glob | find + MountGlobMatcher |
| grep | find + openRead |
| bash | capabilities.shellAccessible + getLocalPath |

保留本地编辑的匹配、BOM、换行符及安全语义；虚拟编辑复用匹配逻辑，来源提供版本时将 `expectedVersion` 传给写入操作，以防覆盖较新内容。FileMountSource 的本地安全逻辑继续保留；Classpath 只走虚拟路径。虚拟来源不得进入 shell 环境变量或 OS 沙箱白名单；只有可获取本地路径的来源才能用于 bash/LSP/Path 诊断。

## 9. 安全与权限

所有操作检查挂载存在/启用状态、逻辑路径规范化、防 `..` 穿越、Mount.writeable、Source capability 与 Terminal 策略；FileMountSource 额外检查真实路径和符号链接。虚拟来源的 read/edit/grep 按实际读取字节数限制每文件 10MB（不限于 `stat.size`），grep 超限时跳过并提示；find 限制条目数与递归深度，输出受既有长度配置限制。

## 10. 实施顺序

1. 冻结 Mount/MountSource/MountEntry/ResolvedResource/SkillCatalog API。
2. 新增 FileMountSource，并用它包装工作区。
3. MountManager 改为 Mount/Source/Resource 模型，移除 MountDir。
4. 将技能表示改为 SkillDescriptor/SkillContent，AgentMd 去 Path 化。
5. 新增 SkillCatalog/DefaultSkillCatalog，删除 SkillProvider 来源职责。
6. 改造 SkillTalent 和 AgentManager。
7. Terminal 的 read/ls/glob/grep 迁移到 Source。
8. Terminal 的 write/edit 迁移到 Source，隔离 bash。
9. 实现 ClasspathMountSource 和相关测试。
10. 迁移 HarnessEngine/LSP/诊断 API。
11. 删除 MountDir/SkillDir、迁移所有调用方并完成文档和回归验证。

## 11. 验收标准

- 工作区和普通本地目录均由 FileMountSource 表示、解析；Terminal 可保留本地 Path 快速路径。
- Terminal 非 bash 工具可访问 File/Classpath 来源及遵循 SPI 的自定义来源。
- bash 仅对真实本地来源或显式物化目录可用。
- SkillCatalog 可扫描、搜索、读取、刷新来自已注册来源的技能。
- SkillProvider 不再是新代码依赖。
- SkillDir 已删除；SkillDescriptor/SkillContent 不依赖真实 Path，AgentMd 不强制持有 Path。
- 技能与 Agent 的扫描分别由 Catalog 承担；MountManager 当前保留禁用状态及 Agent 兼容入口，不作为本轮收口阻碍。
- 原有本地路径、Windows、BOM、符号链接、沙箱和编辑测试不回归。
- Classpath 默认只读；数据库来源不是本轮验收项。

## 12. API 清理策略

`SkillProvider`、`MountSkillProvider`、`MountDir` 和 `SkillDir` 不保留兼容接口。挂载统一使用 `Mount + MountSource`；技能查询统一使用 `SkillCatalog + SkillDescriptor/SkillContent`。旧的仅接受真实本地路径的解析 API 如有保留，不用于虚拟来源。

## 13. 当前实施状态（2026-09-28）

主要功能已落地；2026-09-28 复审后按最小必要范围补强了版本冲突与虚拟读取限制。架构取舍以本节为准，不为满足原草案的接口清单额外扩充类与 DTO。

已落地：

- `MountSource`、`Mount`、`MountEntry`、`MountCapabilities`、`ResolvedResource` 及 File/Classpath 来源实现。
- 工作区由 `FileMountSource` 包装；相对路径、`@workspace` 和普通挂载统一解析为 `ResolvedResource`。
- Classpath 来源支持目录/Jar、虚拟目录、只读访问、资源刷新，并明确拒绝 bash。
- `SkillCatalog`/`DefaultSkillCatalog` 使用 `SkillDescriptor`/`SkillContent` 公共模型；技能来源统一由 MountSource 提供，短名称冲突不会静默覆盖。
- `SkillProvider` 和 `MountSkillProvider` 已从生产代码删除；Harness、SkillTalent 改用 `SkillCatalog`。
- Agent 发现已由 `AgentCatalog`/`DefaultAgentCatalog` 承担；`AgentMd` 和 AgentManager 使用 `MountSource + sourcePath`。
- MountManager 的注册、查询、移除 API 统一返回 `Mount`；`MountDir`、`SkillDir` 和旧技能扫描 facade 均已移除。
- Terminal 的虚拟来源已支持 `ls/read/write/edit/grep/glob`；无本地路径来源不能进入 bash、LSP 或 Path 诊断。
- 修复虚拟 Terminal 输出中的字面量 `\\n` 问题，恢复真实换行输出。
- FileMountSource 的扫描不会因越界符号链接中断整个挂载发现；真实文件访问仍执行根目录和符号链接安全检查。

API 说明：

- `MountDir`、`SkillDir` 及其适配入口均已删除；Harness、Terminal、测试和示例已改为使用 `Mount`、`FileMountSource` 与 `SkillDescriptor`。
- Terminal 的本地 `Path` 快速路径仍保留，不影响非本地来源访问；不要求把本地读写重写一遍。
- `MountManager` 仍持有 `disallowSkills` 和 Agent 兼容查询入口；Agent 目录只保留一份，并与 `AgentManager` 共用。不为形式上的分层纯度单独迁移禁用 API。
- 不新增 `MountScanner`，也不强制给公共技能 DTO 添加来源对象和文件列表。

本轮验证：已移除 JDBC 扩展，以下 Mount/CLI/Harness 联合定向回归退出码为 0；根项目尚未做无条件全量测试验收。

```text
mvn -pl solon-ai-talents/solon-ai-talent-mount,solon-ai-talents/solon-ai-talent-cli,solon-ai-harness -am test -Dtest=MountManagerTest,ClasspathMountSourceTest,TerminalTalentVirtualMountTest,SkillCatalogTest,HarnessSkillCatalogTest,HarnessAgentMountTest,AgentDefaultTest,TerminalTalentSandboxPolicyTest,TerminalTalentEditTest,TerminalTalentGrepTest -Dsurefire.failIfNoSpecifiedTests=false -q
```



根项目未做无条件全量测试验收；项目中既有 Redis、Embedding 和外部 MCP 测试依赖外部服务，不能将这些外部依赖失败归因于本次 MountSource 重构。