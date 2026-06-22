# solon-ai-sandbox

**solon-ai-sandbox** 是 Solon AI 生态中的**沙箱安全隔离模块**，为 AI Agent 的代码执行提供跨平台（macOS / Linux / Windows）的文件系统、网络和进程隔离能力。该模块源自 Claude Code 的 `sandbox-runtime`（TypeScript → Java 移植），提供了一套完整、可嵌入的沙箱运行时。

---

## 核心能力

| 能力 | 说明 |
|------|------|
| **文件系统隔离** | 基于 `deny-then-allow-back`（读）和 `allow-only`（写）策略，精细控制进程的文件访问范围 |
| **网络隔离** | 内置 HTTP / SOCKS5 正向代理，支持域名白名单/黑名单，实时动态更新规则 |
| **跨平台支持** | macOS 使用 `sandbox-exec` + Seatbelt 配置文件；Linux 使用 `bubblewrap (bwrap)` + seccomp；Windows 使用 `srt-win.exe` + WFP |
| **交互式授权** | 提供 `SandboxAskCallback` 接口，可在网络请求未匹配规则时询问用户 |
| **违例追踪** | `SandboxViolationStore` 线程安全地记录所有沙箱违例，支持按类别过滤忽略 |
| **父级代理** | 支持上游 HTTP/HTTPS 代理（HTTP_PROXY 等环境变量）和 NO_PROXY CIDR/域名匹配 |
| **实时配置更新** | 网络白名单支持运行时热更新，无需重启代理服务器 |

---

## 项目结构

```
solon-ai-sandbox/
├── pom.xml                                                      # Maven 构建配置（依赖 solon-ai-core）
│
├── src/main/java/org/noear/solon/ai/sandbox/
│   │
│   ├── SandboxManager.java                                     # ★ 核心入口：全局沙箱管理器（单例模式）
│   ├── SandboxLog.java                                         # 调试日志工具（-Dsandbox.debug=true 开启）
│   ├── SandboxException.java                                   # 沙箱运行时异常
│   ├── SandboxAskCallback.java                                 # 网络权限用户询问回调接口
│   ├── SandboxViolationStore.java                              # 线程安全的违例记录存储
│   ├── FilterRequestCallback.java                              # HTTP 请求级过滤回调
│   │
│   ├── FsReadRestrictionConfig.java                            # 文件读限制配置（deny + allow-back）
│   ├── FsWriteRestrictionConfig.java                           # 文件写限制配置（allow-only）
│   ├── NetworkRestrictionConfig.java                           # 网络限制配置（allow/deny 列表）
│   ├── NetworkHostPattern.java                                 # 主机:端口值对象
│   │
│   ├── config/                                                 # --- 配置层 ---
│   │   ├── SandboxRuntimeConfig.java                           # 运行时总配置（聚合所有子配置）
│   │   ├── FilesystemConfig.java                               # 文件系统规则：denyRead/allowRead/allowWrite/denyWrite
│   │   ├── NetworkConfig.java                                  # 网络规则：域名白/黑名单、代理端口、TLS 等
│   │   ├── MitmProxyConfig.java                                # MITM 代理配置（socketPath + domains）
│   │   ├── ParentProxyConfig.java                              # 上游代理配置（http/https/noProxy）
│   │   ├── TlsTerminateConfig.java                             # TLS 终端配置（CA 证书/密钥路径）
│   │   ├── SeccompConfig.java                                  # Linux seccomp 配置（applyPath/argv0）
│   │   ├── RipgrepConfig.java                                  # Linux ripgrep 配置（用于查找危险文件）
│   │   ├── WindowsConfig.java                                  # Windows 组名/SID/WFP 等配置
│   │   └── SandboxConfigValidator.java                         # 配置完整性校验
│   │
│   ├── net/                                                    # --- 网络过滤层 ---
│   │   ├── NetworkFilter.java                                  # 核心网络请求过滤器（白/黑名单 + 回调）
│   │   ├── DomainPatternMatcher.java                           # 域名模式匹配（支持 *.example.com）
│   │   ├── FilterRequestCallback.java                          # HTTP 请求级过滤回调接口
│   │   ├── HostUtils.java                                      # 主机名校验与规范化（IPv4/IPv6/域名）
│   │   └── ParentProxyResolver.java                            # 上游代理解析（env → URL + NO_PROXY）
│   │
│   ├── platform/                                               # --- 平台后端层 ---
│   │   ├── Platform.java                                       # 平台枚举（MACOS / LINUX / WINDOWS / UNKNOWN）
│   │   ├── PlatformDetector.java                               # 平台自动检测
│   │   ├── MacOSSandboxBackend.java                            # macOS：sandbox-exec + Seatbelt 配置文件生成
│   │   ├── LinuxSandboxBackend.java                            # Linux：bubblewrap (bwrap) + socat 网络桥接
│   │   ├── WindowsSandboxBackend.java                          # Windows：srt-win.exe 进程包装
│   │   ├── DependencyChecker.java                              # 平台依赖检查
│   │   ├── CommandLookup.java                                  # 可执行文件查找（which/where）
│   │   └── SandboxDependencyCheck.java                         # 依赖检查结果模型
│   │
│   ├── proxy/                                                  # --- 代理服务层 ---
│   │   ├── HttpProxyServer.java                                # HTTP 正向代理服务器（CONNECT + 普通 HTTP）
│   │   └── SocksProxyServer.java                               # SOCKS5 代理服务器
│   │
│   ├── util/                                                   # --- 工具类 ---
│   │   ├── SandboxPathUtils.java                               # 路径规范化、危险文件/目录列表
│   │   ├── GlobUtils.java                                      # Glob 模式 ↔ 正则转换、模式展开
│   │   ├── ProxyEnvUtils.java                                  # 代理环境变量生成（HTTP_PROXY 等）
│   │   ├── ShellQuote.java                                     # POSIX Shell 安全参数引用
│   │   └── Base64Utils.java                                    # Base64 编解码（日志标记用）
│   │
│   └── windows/                                                # --- Windows 模型 ---
│       ├── WindowsGroupStatus.java                             # 组状态枚举
│       ├── WindowsGroupStatusResult.java                       # 组状态查询结果
│       ├── WindowsInstallOptions.java                          # Windows 沙箱安装选项
│       ├── WindowsInstallResult.java                           # 安装结果
│       ├── WindowsWfpStatus.java                               # WFP 状态枚举
│       └── WindowsWfpStatusResult.java                         # WFP 状态查询结果
│
└── src/test/java/org/noear/solon/ai/sandbox/
    ├── SandboxE2eManagerTest.java                              # Manager 端到端测试
    ├── SandboxE2eMacOSTest.java                                # macOS E2E 测试
    ├── SandboxE2eLogicTest.java                                # 逻辑 E2E 测试
    ├── SandboxWrapTest.java                                    # 命令包装测试
    ├── SandboxWrapGitLogTest.java                              # Git 日志包装测试
    ├── config/SandboxConfigValidatorTest.java                  # 配置校验测试
    ├── net/DomainPatternMatcherTest.java                       # 域名匹配测试
    ├── net/HostUtilsTest.java                                  # HostUtils 测试
    ├── net/NetworkFilterTest.java                              # 网络过滤测试
    ├── util/GlobUtilsTest.java                                 # Glob 工具测试
    ├── util/SandboxPathUtilsTest.java                          # 路径工具测试
    └── util/ShellQuoteTest.java                                # Shell 引用测试
```

---

## 架构概览

```
┌────────────────────────────────────────────────────────────┐
│                    SandboxManager (单例)                     │
│   initialize() · reset() · wrapWithSandbox() · updateConfig() │
└───────┬──────────────────────────────┬──────────────────────┘
        │                              │
   ┌────▼────┐                   ┌─────▼─────┐
   │ 配置层   │                   │ 平台后端   │
   │ config/ │                   │ platform/ │
   └────┬────┘                   └─────┬─────┘
        │                              │
   ┌────▼──────────────────────────────▼─────┐
   │           平台分发（按 Platform 路由）     │
   │                                          │
   │   macOS                Linux     Windows │
   │   sandbox-exec        bwrap     srt-win  │
   │   + Seatbelt 配置     + socat    + WFP   │
   │                       + seccomp          │
   └──────────────────────────────────────────┘
                       │
          ┌────────────┴────────────┐
          │                         │
    ┌─────▼─────┐           ┌──────▼──────┐
    │  代理服务   │           │  网络过滤    │
    │  proxy/   │           │  net/       │
    │ HTTP/SOCKS│           │ 白/黑名单    │
    └───────────┘           └─────────────┘
```

### 工作流程

1. **初始化**：调用 `SandboxManager.initialize(config, callback)` 加载配置、检查依赖、启动代理
2. **包装命令**：`SandboxManager.wrapWithSandbox("command")` 根据平台生成包裹命令
   - macOS → `env ... sandbox-exec -p <profile> bash -c 'command'`
   - Linux → `bwrap --unshare-net --ro-bind ... bash -c 'command'`
   - Windows → `srt-win.exe exec ... cmd.exe /c command`
3. **执行**：包装后的命令在受限环境中运行，所有文件/网络操作受沙箱约束
4. **热更新**：调用 `updateConfig(newConfig)` 可动态切换网络白名单（文件系统变更需 reset）
5. **清理**：`cleanupAfterCommand()` 清理 bwrap 残留挂载点；`reset()` 完全重置

---

## 配置说明

### SandboxRuntimeConfig 核心配置项

| 分组 | 配置 | 类型 | 说明 |
|------|------|------|------|
| **network** | `allowedDomains` | `List<String>` | 允许访问的域名白名单（空列表=禁止所有网络）|
| | `deniedDomains` | `List<String>` | 禁止访问的域名黑名单 |
| | `allowUnixSockets` | `List<String>` | 允许的 Unix Socket 路径 |
| | `allowAllUnixSockets` | `Boolean` | 是否允许所有 Unix Socket |
| | `allowLocalBinding` | `Boolean` | 是否允许本地网络绑定 |
| | `httpProxyPort` / `socksProxyPort` | `Integer` | 代理端口 |
| | `mitmProxy` | `MitmProxyConfig` | MITM 代理配置（socketPath + domains）|
| | `parentProxy` | `ParentProxyConfig` | 上游代理（http/https/noProxy）|
| | `tlsTerminate` | `TlsTerminateConfig` | TLS 终端（CA 证书/密钥）|
| **filesystem** | `denyRead` | `List<String>` | 禁止读取的路径 |
| | `allowRead` | `List<String>` | 在 denyRead 区域内允许回读的路径 |
| | `allowWrite` | `List<String>` | 允许写入的路径（空列表=禁止所有写入）|
| | `denyWrite` | `List<String>` | 在 allowWrite 区域内禁止写入的路径 |
| | `allowGitConfig` | `Boolean` | 是否允许写入 .git/config |
| **其他** | `enableWeakerNestedSandbox` | `Boolean` | 弱化嵌套沙箱（Linux 容器内）|
| | `enableWeakerNetworkIsolation` | `Boolean` | 允许 trustd.agent（Go TLS 证书验证）|
| | `allowAppleEvents` | `Boolean` | 允许 Apple Events（open/osascript）|
| | `allowPty` | `Boolean` | 允许伪终端 |
| | `seccomp` | `SeccompConfig` | Linux seccomp 配置 |
| | `ripgrep` | `RipgrepConfig` | ripgrep 配置（危险文件扫描）|
| | `bwrapPath` / `socatPath` | `String` | 自定义 bwrap/socat 路径 |
| | `windows` | `WindowsConfig` | Windows 组名/SID/WFP 子层等 |

### 文件系统限制策略

- **读取**：`deny-then-allow-back` 模式
  - 默认开放所有读取 → 对 `denyRead` 路径禁止读取 → 对 `allowRead` 路径重新允许
  - 空 `denyRead` = 无读取限制
- **写入**：`allow-only` 模式
  - 默认禁止所有写入 → 仅 `allowWrite` 路径可写 → `denyWrite` 内例外禁止
  - 空 `allowWrite` = 禁止所有写入（最严格）
  - 始终包含默认系统写入路径（/dev/*、/tmp、用户临时目录等）

---

## 使用示例

### 1. 引入依赖

```xml
<dependency>
    <groupId>org.noear</groupId>
    <artifactId>solon-ai-sandbox</artifactId>
    <version>${solon-ai.version}</version>
</dependency>
```

### 2. 基础用法：文件系统隔离

```java
import org.noear.solon.ai.sandbox.*;
import org.noear.solon.ai.sandbox.config.*;

// 允许写入 /tmp 和当前工作目录，但禁止写入 .git 相关文件
FilesystemConfig fs = new FilesystemConfig(
    null,                       // denyRead: 无读取限制
    null,                       // allowRead: 无回读白名单
    Arrays.asList("/tmp", "."), // allowWrite: 允许写入的路径
    null,                       // denyWrite: 无额外写入黑名单
    false                       // allowGitConfig: 禁止修改 .git/config
);

SandboxRuntimeConfig config = new SandboxRuntimeConfig(
    null, fs, null, null, null, null, null, null, null,
    null, null, null
);

SandboxManager.initialize(config, null);
SandboxManager.setProxyPorts(18080, 18081);

// 包装命令 → 自动根据平台生成沙箱包裹命令
String wrapped = SandboxManager.wrapWithSandbox("git status");
// macOS 输出类似：
//   env ... sandbox-exec -p '(version 1)...' bash -c 'git status'

// 执行包装后的命令
Process p = Runtime.getRuntime().exec(new String[]{"/bin/bash", "-c", wrapped});
```

### 3. 网络白名单 + 交互式授权

```java
NetworkConfig network = new NetworkConfig(
    Arrays.asList("api.openai.com", "*.github.com"), // 白名单
    Arrays.asList("malware.example.com"),            // 黑名单
    null, null, null, null, null, null, null, null, null, null
);

SandboxRuntimeConfig config = new SandboxRuntimeConfig(
    network, null, null, null, null, null, null, null, null,
    null, null, null
);

// 交互式回调（白名单未匹配时询问用户）
SandboxAskCallback callback = (hostPattern) -> {
    System.out.println("允许访问 " + hostPattern.getHost() + ":" + hostPattern.getPort() + "？[y/N]");
    Scanner sc = new Scanner(System.in);
    return sc.nextLine().trim().equalsIgnoreCase("y");
};

SandboxManager.initialize(config, callback);
```

### 4. 运行时热更新网络规则

```java
// 初始配置：只允许 example.com
NetworkConfig oldNet = new NetworkConfig(
    Arrays.asList("example.com"), null, null, null, null, null, null, null, null, null, null);
SandboxManager.initialize(new SandboxRuntimeConfig(oldNet, ...), null);

// 动态切换为允许 other.com（无需重启代理进程）
NetworkConfig newNet = new NetworkConfig(
    Arrays.asList("other.com"), null, null, null, null, null, null, null, null, null, null);
SandboxManager.updateConfig(new SandboxRuntimeConfig(newNet, ...));

// ⚠️ 文件系统变更需要 reset() 后重新 initialize()
```

### 5. 违例追踪

```java
SandboxViolationStore store = new SandboxViolationStore(null);
store.record("file_read", "尝试读取 /etc/shadow");
store.record("network", "尝试连接 banned.example.com:443");

if (store.hasViolations()) {
    for (String cat : store.getCategories()) {
        System.out.println(cat + ": " + store.getViolations(cat));
    }
}
```

### 6. 平台检测与依赖检查

```java
import org.noear.solon.ai.sandbox.platform.*;

Platform p = PlatformDetector.detect(); // MACOS / LINUX / WINDOWS
boolean supported = PlatformDetector.isSupportedPlatform();

SandboxDependencyCheck deps = SandboxManager.checkDependencies();
if (deps.hasErrors()) {
    System.err.println("沙箱依赖缺失: " + deps.getErrors());
}
```

---

## 平台依赖

| 平台 | 依赖 | 说明 |
|------|------|------|
| **macOS** | `sandbox-exec`（系统内置） | 零外部依赖，macOS 原生支持 |
| **Linux** | `bwrap`（bubblewrap）、`socat`、可选 `ripgrep` + `apply-seccomp` | `apt install bubblewrap socat ripgrep` |
| **Windows** | `srt-win.exe`（需构建或安装） | 需一次 UAC 提权安装 WFP 过滤器和组策略 |

---

## 设计原则

1. **平台中立性**：`SandboxManager` 提供统一 API，所有平台差异封装在 `platform/` 子包中
2. **最小权限**：默认禁止所有写入和网络，按需开放特定路径/域名
3. **防绕过**：使用移动阻断规则（`generateMoveBlockingRules`）防止通过 `mv`/`rename` 绕过文件限制
4. **符号链接保护**：自动检测路径中的符号链接，防止符号链接替换攻击
5. **安全默认值**：自动保护危险文件（`.bashrc`、`.gitconfig` 等）和目录（`.git`、`.vscode` 等）
6. **Fail-Closed**：回调抛出异常或配置为空时默认拒绝请求

---

## 测试

```bash
# 运行所有测试
mvn test -pl solon-ai-sandbox

# 仅运行特定测试
mvn test -pl solon-ai-sandbox -Dtest=SandboxE2eManagerTest

# 开启沙箱调试日志
mvn test -pl solon-ai-sandbox -Dsandbox.debug=true
```
