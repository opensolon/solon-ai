# MountSource 重构兼容及迁移说明

## 1. 文档目的

本文说明 `MountSource` 与 `SkillCatalog` 统一资源体系重构后的 API 变化、行为变化和迁移方式，供使用 `solon-ai-talent-mount`、`solon-ai-talent-cli` 或 `solon-ai-harness` 的代码升级时参考。

本次重构的核心目标是将工作区、本地目录、Classpath/Jar 等内容来源统一抽象为 `MountSource`：

```text
SkillTalent   -> SkillCatalog   -> MountManager -> MountSource
TerminalTalent                         -> MountSource
AgentCatalog/AgentManager              -> MountSource
```

重构后的资源来源不再由 Skill 专用 Provider 表达，终端文件工具也不再假设所有资源都对应真实的本地 `Path`。

> 本文对应 `MountSource` 重构的已实施版本。相关公共类型当前标记为 `@Preview("4.1.1")` 的 API，应以实际发布版本的 API 文档为准。

## 2. 迁移结论

这次调整属于**不保留旧接口的结构性迁移**：

- `MountDir`、`SkillDir` 已删除；
- `SkillProvider`、`MountSkillProvider` 已从生产代码删除；
- 旧的仅接受本地 `Path` 的资源模型不再作为虚拟来源的主入口；
- `SkillCatalog`、`SkillDescriptor`、`SkillContent` 和 `MountSource` 成为新的公共模型；
- 原先直接依赖旧包名或旧类型的代码需要修改 import、构造方式和资源读取方式。

如果应用只使用本地工作区和已有的 `HarnessEngine`/`SkillTalent` 高层 API，通常只需要调整挂载注册代码和包引用；如果应用实现了自定义 Skill Provider 或直接操作本地路径，则需要按本文对应章节迁移。

## 3. API 变化总览

### 3.1 旧类型到新类型

| 旧模型 | 新模型 | 迁移说明 |
|---|---|---|
| `MountDir` | `Mount` + `MountSource` | 用 `Mount` 保存别名、类型、启用及写入策略，用 `MountSource` 提供内容操作 |
| `SkillDir` | `Mount` + `FileMountSource` | 技能目录作为普通本地挂载注册，不再使用专用目录类型 |
| `SkillProvider` | `SkillCatalog` | Provider 的来源适配职责由 `MountSource` 承担，技能索引与读取职责由 Catalog 承担 |
| `MountSkillProvider` | `DefaultSkillCatalog` | 默认 Catalog 通过 `MountManager` 扫描已注册的技能挂载 |
| `AgentMd` | `AgentDescriptor` | 表示已发现的 Agent 来源描述，不表示 `AgentDefinition` 或运行时 Agent |
| `Path` 资源条目 | `MountEntry` | 来源无关的虚拟文件/目录条目，不暴露真实本地路径 |
| 本地路径解析 | `ResolvedResource` | 同时保存逻辑路径、来源挂载、`MountSource` 和来源内路径 |

### 3.2 包名变化

新的类型位于以下包：

```java
org.noear.solon.ai.talents.mount.*
org.noear.solon.ai.talents.mount.source.*
org.noear.solon.ai.talents.mount.catalog.*
```

重点类型如下：

```java
import org.noear.solon.ai.talents.mount.Mount;
import org.noear.solon.ai.talents.mount.MountEntry;
import org.noear.solon.ai.talents.mount.MountManager;
import org.noear.solon.ai.talents.mount.MountType;
import org.noear.solon.ai.talents.mount.ResolvedResource;

import org.noear.solon.ai.talents.mount.source.ClasspathMountSource;
import org.noear.solon.ai.talents.mount.source.FileMountSource;
import org.noear.solon.ai.talents.mount.source.FindOptions;
import org.noear.solon.ai.talents.mount.source.MountCapabilities;
import org.noear.solon.ai.talents.mount.source.MountSource;
import org.noear.solon.ai.talents.mount.source.MoveOptions;
import org.noear.solon.ai.talents.mount.source.WriteOptions;

import org.noear.solon.ai.talents.mount.catalog.AgentCatalog;
import org.noear.solon.ai.talents.mount.catalog.AgentDescriptor;
import org.noear.solon.ai.talents.mount.catalog.SkillCatalog;
import org.noear.solon.ai.talents.mount.catalog.SkillContent;
import org.noear.solon.ai.talents.mount.catalog.SkillDescriptor;
```

`SkillCatalog` 及其 DTO 不再属于 `org.noear.solon.ai.talents.cli` 包。`FileMountSource`、`ClasspathMountSource` 和来源操作选项位于 `mount.source` 子包。

## 4. MountDir/SkillDir 迁移

### 4.1 本地目录挂载

旧代码通常以专用目录对象表达一个技能或 Agent 目录。现在统一使用 `Mount` 和 `FileMountSource`：

```java
Path skillsPath = Paths.get("./skills");

MountManager mounts = new MountManager(workDir);
mounts.register(Mount.builder()
        .alias("@skills")
        .type(MountType.SKILLS)
        .description("本地技能目录")
        .enabled(true)
        .writeable(false)
        .source(FileMountSource.of(skillsPath))
        .build());
```

说明：

- `alias` 可以写成 `skills` 或 `@skills`，`MountManager` 会规范化为 `@skills`；
- `@workspace` 是工作区保留逻辑别名，不能作为普通挂载别名注册；
- `type` 用于表达挂载用途，例如 `MountType.SKILLS` 或 `MountType.AGENTS`；
- `writeable` 是当前挂载的授权配置，不等同于来源本身的技术能力；
- 同一别名再次注册会替换原挂载，并触发 Catalog 刷新。

### 4.2 工作区

工作区不需要再创建第二个目录配置。`MountManager` 内部持有工作区的 `FileMountSource`：

```java
FileMountSource workspace = mounts.getWorkspaceSource();
```

相对路径、`.`、`./path` 和 `@workspace/path` 都会解析到工作区来源。普通挂载则使用 `@alias/path`。

## 5. ClasspathMountSource 用法

`ClasspathMountSource` 用于把应用自身或依赖 Jar 中的 classpath 资源挂载为只读来源。它通过指定的 `ClassLoader` 和 `basePath` 枚举资源；来源内路径相对于 `basePath`，统一使用 `/` 分隔，不需要也不应写成文件系统绝对路径。

例如，工程中存在以下资源：

```text
src/main/resources/bundled-skills/demo/SKILL.md
src/main/resources/bundled-skills/demo/references/api.md
```

可以直接创建来源并读取资源：

```java
import org.noear.solon.ai.talents.mount.source.ClasspathMountSource;
import org.noear.solon.ai.talents.mount.MountEntry;

import java.io.InputStream;
import java.util.List;

ClasspathMountSource source = ClasspathMountSource.of("bundled-skills");

List<MountEntry> children = source.list("demo");
try (InputStream input = source.openRead("demo/SKILL.md")) {
    // 按应用需要消费 InputStream；例如复制到 ByteArrayOutputStream 后再按 UTF-8 解码。
    byte[] buffer = new byte[8192];
    int count;
    while ((count = input.read(buffer)) >= 0) {
        // 处理 buffer[0..count)
    }
}
```

也可以显式传入 ClassLoader。应用需要读取指定依赖或插件的资源时，应使用能够加载该资源的 ClassLoader，而不要默认假设线程上下文 ClassLoader 一定可见：

```java
ClassLoader pluginLoader = MyPlugin.class.getClassLoader();
ClasspathMountSource source = ClasspathMountSource.of(pluginLoader, "skills");
```

### 5.1 注册为普通挂载

如果要让 `MountManager`、`SkillCatalog` 或终端工具使用 Classpath 来源，应将它和普通 `Mount` 一起注册：

```java
MountManager mounts = new MountManager("./workspace");
mounts.register(Mount.builder()
        .alias("@bundled-skills")
        .type(MountType.SKILLS)
        .description("随应用发布的内置技能")
        .enabled(true)
        .writeable(false)
        .source(ClasspathMountSource.of("bundled-skills"))
        .build());

try (InputStream input = mounts.resolveResource("@bundled-skills/demo/SKILL.md")
        .getSource()
        .openRead("demo/SKILL.md")) {
    // 读取 classpath 中的 SKILL.md
}
```

注册后，资源的逻辑路径是 `@bundled-skills/...`，来源内路径仍然是 `demo/SKILL.md`。更推荐先保存 `ResolvedResource`，避免手工拆分逻辑路径：

```java
ResolvedResource resource = mounts.resolveResource("@bundled-skills/demo/SKILL.md");
try (InputStream input = resource.getSource().openRead(resource.getSourcePath())) {
    // 读取来源内容
}
```

当挂载类型为 `MountType.SKILLS` 且资源布局符合 Catalog 的技能发现约定时，`SkillCatalog` 会从该挂载建立技能索引；不需要再为 classpath 技能创建专用 Provider。

### 5.2 查找、刷新与限制

`ClasspathMountSource` 支持 `stat`、`list`、`find` 和 `openRead`，例如：

```java
MountEntry entry = source.stat("demo/SKILL.md");
List<MountEntry> markdownFiles = source.find("demo", FindOptions.builder()
        .glob("**/*.md")
        .filesOnly(true)
        .maxDepth(3)
        .maxEntries(100)
        .build());
```

来源会缓存已经枚举出的 classpath 条目。应用在运行期间新增或替换了 classpath 资源后，应显式调用：

```java
source.refresh();
mounts.getSkillCatalog().refreshByMount("@bundled-skills");
```

正常的应用打包场景中，classpath/Jar 资源在进程启动后不会改变，因此通常不需要刷新。`refresh()` 只会丢弃来源索引，不会修改 Jar 或资源文件。

该来源是只读、虚拟来源：

- `capabilities()` 返回 `MountCapabilities.readOnlyVirtual()`；
- `openWrite`、`delete`、`move` 会抛出 `UnsupportedOperationException`；
- `getLocalPath(...)` 默认返回空 `Optional`；
- 不能直接将来源内路径交给 `Path`、`Files` 或 `bash`；
- 需要本地文件时，当前实现不会自动物化，调用方必须另行复制 `openRead(...)` 的内容到明确的临时目录，并重新执行相应的安全检查。

`basePath` 应使用 classpath 资源路径，例如 `bundled-skills` 或 `META-INF/solon/skills`，不要使用 `src/main/resources`、磁盘绝对路径或以 `/` 开头的路径。来源会规范化反斜杠和前导/尾随 `/`，但来源内的 `..` 路径会被拒绝。

## 6. SkillProvider 迁移到 SkillCatalog

### 6.1 目录查询

旧代码如果直接依赖 Skill Provider 的扫描、查找或读取方法，应改为依赖 `SkillCatalog`：

```java
SkillCatalog catalog = mounts.getSkillCatalog();

Collection<SkillDescriptor> all = catalog.getDescriptors();
Collection<SkillDescriptor> matches = catalog.searchDescriptors("markdown");
SkillDescriptor descriptor = catalog.getDescriptor("@skills/example");
SkillContent content = catalog.readContent("@skills/example");
```

`SkillDescriptor` 只包含来源无关的技能元数据：

```java
String id = descriptor.getId();
String name = descriptor.getName();
String description = descriptor.getDescription();
String version = descriptor.getVersion();
```

读取 Markdown 和渲染后的内容通过 `SkillContent` 完成：

```java
String markdown = content.getText();
String rendered = content.getRenderedText();
SkillDescriptor owner = content.getDescriptor();
```

不要再通过 `Path` 推断技能来源。技能可以来自本地目录、Classpath/Jar 或未来的其他 `MountSource`。

### 6.2 刷新语义

```java
catalog.refresh();
catalog.refreshByMount("@skills");
```

`MountManager.register()` 和 `MountManager.remove()` 会自动刷新受影响的 SkillCatalog 和 AgentCatalog。挂载内容在外部发生变化时，仍需显式调用 `refresh()` 或通过上层 `HarnessEngine.refreshMount(...)` 刷新。

`SkillTalent` 现在只依赖 `SkillCatalog`：

```java
SkillTalent talent = new SkillTalent(mounts.getSkillCatalog());
```

应用通常不需要再自行创建或注入 `SkillProvider`。

## 7. Agent 迁移

Agent 来源条目使用 `AgentDescriptor`：

```java
AgentCatalog agents = mounts.getAgentCatalog();
Collection<AgentDescriptor> descriptors = agents.getAgents();
AgentDescriptor descriptor = agents.getAgent("reviewer");
```

`AgentDescriptor` 保存：

- Agent 名称；
- 挂载别名；
- `MountSource`；
- 来源内路径。

读取内容时使用：

```java
try (InputStream in = descriptor.open()) {
    // 读取 Agent Markdown 内容
}
```

`getFilePath()` 只适用于能够提供本地路径的来源。对于 Classpath/Jar 或其他虚拟来源，返回值可能为 `null`。需要解析为可执行定义时，仍由 `AgentManager` 负责，不要将 `AgentDescriptor` 当作运行时 Agent 或 `AgentDefinition` 使用。

## 8. MountSource 自定义来源迁移

实现自定义来源时，实现 `MountSource`，而不是实现 Skill 专用 Provider：

```java
public final class DatabaseMountSource implements MountSource {
    @Override
    public String getScheme() {
        return "db";
    }

    @Override
    public String normalize(String path) {
        // 返回来源内的规范化路径
        return path == null ? "" : path;
    }

    @Override
    public MountEntry stat(String path) throws IOException { /* ... */ }

    @Override
    public List<MountEntry> list(String path) throws IOException { /* ... */ }

    @Override
    public List<MountEntry> find(String path, FindOptions options) throws IOException { /* ... */ }

    @Override
    public InputStream openRead(String path) throws IOException { /* ... */ }

    @Override
    public OutputStream openWrite(String path, WriteOptions options) throws IOException { /* ... */ }

    @Override
    public void delete(String path) throws IOException { /* ... */ }

    @Override
    public void move(String source, String target, MoveOptions options) throws IOException { /* ... */ }

    @Override
    public MountCapabilities capabilities() { /* ... */ }
}
```

如果来源不支持写入、删除或移动，应在 `MountCapabilities` 中声明不支持，并让对应操作明确失败。`MountCapabilities` 描述的是来源的技术能力，不代表某个 `Mount` 当前是否被授权；实际操作还会经过挂载启用状态、`writeable` 和 Terminal 策略检查。

## 9. Terminal 文件工具迁移

非本地来源不能再通过 `Path` 或 `Files` 访问。文件工具应先解析 `ResolvedResource`，再调用 `MountSource`：

| 操作 | 推荐 API |
|---|---|
| `ls` | `MountSource.list` / `find` |
| `read` | `MountSource.openRead` |
| `write` | `MountSource.openWrite` |
| `edit` | 读取、文本编辑、写回 `openWrite` |
| `glob` | `MountSource.find` |
| `grep` | `find` + `openRead` |
| `bash` | 仅在来源支持本地路径且 `shellAccessible` 时执行 |

典型解析方式：

```java
ResolvedResource resource = mountManager.resolveResource("@skills/example/SKILL.md");
MountSource source = resource.getSource();
String sourcePath = resource.getSourcePath();

try (InputStream in = source.openRead(sourcePath)) {
    // 读取来源内容
}
```

如果确实需要本地路径，例如调用 bash、LSP 或 Path 诊断，应使用：

```java
Optional<Path> localPath = mountManager.resolveResource("@local/file.txt").getLocalPath();
```

不能假设任意 `MountSource` 都能返回 `Path`。Classpath 来源默认只读、只支持虚拟访问，不能直接进入 bash；如来源提供 `materialize(...)`，必须先显式物化到本地临时目录。

## 10. 重要行为变化

### 10.1 Classpath/Jar 来源

`ClasspathMountSource` 支持从 Classpath 目录和 Jar 条目读取资源，默认具备以下特征：

- 只读；
- 不支持 write/edit/delete/move/watch/bash；
- 可以被 `ls`、`read`、`glob`、`grep` 访问；
- 不提供真实本地路径；
- 需要执行脚本时，必须先物化到本地目录，并重新经过执行策略检查。

### 10.2 路径与安全

所有来源都必须处理来源内路径规范化和 `..` 防穿越。File 来源还会检查真实路径、符号链接和挂载根目录边界。

虚拟来源的读取限制以实际读取字节数为准，不应只依赖 `MountEntry.size`。Terminal 的发现结果、递归深度和输出长度仍受既有限制约束。

### 10.3 写入和版本

`WriteOptions` 支持 `append`、`createParents` 和可选的 `expectedVersion`：

```java
WriteOptions options = WriteOptions.builder()
        .expectedVersion(entry.getVersion())
        .build();
```

`expectedVersion` 是否生效由具体 `MountSource` 实现决定。调用方不能假设所有来源都提供版本冲突检测；实现自定义来源时，应在文档中说明版本生成和冲突行为。

### 10.4 Catalog 的刷新

注册、替换或删除挂载会自动触发两个 Catalog 的刷新。挂载内容本身被外部修改时不会自动感知，除非来源实现了相应的监听能力；此时使用：

```java
mountManager.getSkillCatalog().refresh();
mountManager.getAgentCatalog().refresh();
```

或使用：

```java
harness.refreshMount("@skills");
```

`HarnessEngine` 除了刷新目录，还会清理挂载 Agent 的运行时缓存；仅使用 `MountManager` 时需要自行考虑运行时 Agent 生命周期。

## 11. 迁移检查清单

升级依赖或合并重构代码后，建议按以下顺序检查：

- [ ] 删除旧的 `MountDir`、`SkillDir`、`SkillProvider`、`MountSkillProvider` import；
- [ ] 用 `Mount.builder().source(...)` 替换旧目录或 Provider 注册方式；
- [ ] 本地目录使用 `FileMountSource.of(path)`；Classpath/Jar 使用 `ClasspathMountSource.of(...)`；
- [ ] 将 Skill 查询、搜索、读取改为 `SkillCatalog`；
- [ ] 将 `AgentMd` 引用改为 `AgentDescriptor`；
- [ ] 将 `Path` 资源条目改为 `MountEntry` 或 `ResolvedResource`；
- [ ] 非本地文件访问改用 `MountSource`，不要调用 `Path/Files`；
- [ ] 只有在需要 bash、LSP 或 Path 诊断时才请求本地路径；
- [ ] 检查 `MountCapabilities` 与 `Mount.writeable` 是否同时符合预期；
- [ ] 对自定义来源实现并测试路径规范化、查找上限、读取、写入和能力声明；
- [ ] 验证挂载替换、删除后的 Catalog 内容和运行时 Agent 缓存；
- [ ] 运行 Mount、CLI、Harness 相关回归测试。

## 12. 推荐回归测试

至少执行以下定向测试：

```bash
mvn -pl solon-ai-talents/solon-ai-talent-mount,solon-ai-talents/solon-ai-talent-cli,solon-ai-harness -am test \
  -Dtest=MountManagerTest,ClasspathMountSourceTest,TerminalTalentVirtualMountTest,SkillCatalogTest,HarnessSkillCatalogTest,HarnessAgentMountTest,AgentDefaultTest,TerminalTalentSandboxPolicyTest,TerminalTalentEditTest,TerminalTalentGrepTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

如果根项目存在依赖 Redis、Embedding 或外部 MCP 服务的测试，应将这些外部环境失败与本次 MountSource 迁移造成的编译或功能失败区分处理。

## 13. 不建议的迁移方式

不要采用以下方式绕过新模型：

```java
// 不建议：把所有 MountSource 强制转换为 FileMountSource
FileMountSource source = (FileMountSource) mount.getSource();

// 不建议：从虚拟资源构造不存在的本地 Path
Path path = Paths.get(resource.getSourcePath());

// 不建议：继续为每一种 Skill 来源创建专用 Provider
SkillProvider provider = ...;
```

正确做法是保留来源无关的调用链：

```text
逻辑路径
  -> MountManager.resolveResource
  -> ResolvedResource
  -> MountSource 操作
```

这样未来增加数据库、远程对象存储或其他来源时，不需要重新改造 SkillTalent、AgentCatalog 和 Terminal 的核心逻辑。
