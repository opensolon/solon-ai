# HITL 人工介入系统使用说明

> **模块**: `solon-ai-harness` + `solon-ai-agent`
> **since**: 4.0
> **状态**: @Preview

HITL（Human-in-the-Loop）是 solon-ai-harness 提供的人工介入审批框架，在 AI Agent 执行工具调用时自动拦截高危操作，挂起任务等待人工决策，并根据决策结果（批准 / 拒绝 / 跳过）驱动后续流程。

---

## 目录

- [1. 架构总览](#1-架构总览)
- [2. 快速开始](#2-快速开始)
- [3. 权限模式 (PermissionMode)](#3-权限模式-permissionmode)
- [4. 权限规则 (PermissionRule)](#4-权限规则-permissionrule)
- [5. 权限上下文 (PermissionContext)](#5-权限上下文-permissioncontext)
- [6. 权限评估引擎 (PermissionEngine)](#6-权限评估引擎-permissionengine)
- [7. 内置干预策略](#7-内置干预策略)
- [8. Bash 命令分类器 (BashCommandClassifier)](#8-bash-命令分类器-bashcommandclassifier)
- [9. HITL 交互助手 API](#9-hitl-交互助手-api)
- [10. HITLDecision 决策实体](#10-hitldecision-决策实体)
- [11. HITLInterceptor 拦截器](#11-hitlinterceptor-拦截器)
- [12. alwaysAllow 始终允许机制](#12-alwaysallow-始终允许机制)
- [13. 自定义干预策略](#13-自定义干预策略)
- [14. Web 集成示例](#14-web-集成示例)
- [15. API 速查表](#15-api-速查表)

---

## 1. 架构总览

```
                     ┌──────────────────────────────────────────────┐
                     │              HarnessEngine                   │
                     │                                              │
                     │  PermissionContext (mode + rules)            │
                     │        │                                     │
                     │        ▼                                     │
                     │  ┌──────────────────────────────────────┐   │
                     │  │         HITLInterceptor              │   │
                     │  │  ┌────────────┐  ┌────────────────┐  │   │
                     │  │  │ bash       │  │ BashToolStr    │  │   │
                     │  │  │ write/edit │  │ WriteToolStr   │  │   │
                     │  │  │ webfetch   │  │ WebToolStr     │  │   │
                     │  │  │ websearch  │  │ SensitiveStr   │  │   │
                     │  │  └────────────┘  └───────────────┘  │   │
                     │  └──────────────┬───────────────────────┘   │
                     │                 │                            │
                     │     onAction    │ onObservation              │
                     │         ▼       ▼                            │
                     │  挂起 → 等待决策 → 执行/拒绝/跳过 → 清理       │
                     └──────────────────────────────────────────────┘
                                        │
                       ┌────────────────┼────────────────┐
                       ▼                ▼                ▼
                  HITL.approve     HITL.reject      HITL.skip
                  (批准+可选修正)   (拒绝+终止)      (跳过+继续)
```

### 核心组件

| 组件 | 模块 | 职责 |
|------|------|------|
| `HITLInterceptor` | solon-ai-agent | 拦截器，通过 ReAct 协议钩子实现挂起/恢复 |
| `HITL` | solon-ai-agent | 交互助手，提供 approve/reject/skip 便捷 API |
| `HITLDecision` | solon-ai-agent | 决策实体（批准/拒绝/跳过 + 参数修正 + alwaysAllow） |
| `HITLTask` | solon-ai-agent | 挂起任务快照（工具名 + 参数 + 拦截理由） |
| `PermissionEngine` | solon-ai-harness | 权限评估引擎（DENY > ALLOW > ASK > 模式降级） |
| `PermissionContext` | solon-ai-harness | 权限上下文（模式 + 规则 + 工作目录，不可变） |
| `PermissionRule` | solon-ai-harness | 权限规则（工具名 + glob 模式 + 行为 + 来源） |
| `PermissionMode` | solon-ai-harness | 权限模式（6 种） |
| `BashToolStrategy` | solon-ai-harness | Bash 工具策略（P0 防御 + 只读分类 + 规则引擎） |
| `WriteToolStrategy` | solon-ai-harness | Write/Edit 工具策略（路径防御 + 规则引擎） |
| `WebToolStrategy` | solon-ai-harness | Web 工具策略（域名黑名单 + 规则引擎） |
| `BashCommandClassifier` | solon-ai-harness | Bash 只读命令分类器（50+ 条白名单） |

---

## 2. 快速开始

### 2.1 在 HarnessEngine 中启用 HITL

```java
HarnessEngine engine = HarnessEngine.of("/workspace", ".solon")
    .sessionProvider(sessionProvider)
    .hitlEnabled(true)                              // 启用 HITL
    .permissionMode(PermissionMode.DEFAULT)         // 设置权限模式
    .permissionRuleAdd(                             // 添加自定义规则
        PermissionRule.deny("bash", "rm -rf *", RuleSource.PROJECT_SETTINGS)
    )
    .build();
```

启用后，HarnessEngine 会自动注册以下策略到 HITLInterceptor：

| 工具 | 策略 | 说明 |
|------|------|------|
| `bash` | `BashToolStrategy` | P0 硬编码防御 + 只读命令白名单 + PermissionEngine |
| `write` | `WriteToolStrategy` | 路径回溯防御 + PermissionEngine |
| `edit` | `WriteToolStrategy` | 路径回溯防御 + PermissionEngine |
| `webfetch` | `WebToolStrategy` | 域名黑名单 + PermissionEngine |
| `websearch` | `WebToolStrategy` | 域名黑名单 + PermissionEngine |

### 2.2 在 ReActAgent 中独立使用

```java
HITLInterceptor hitlInterceptor = new HITLInterceptor()
    .onTool("transfer", (trace, args) -> {
        double amount = Double.parseDouble(args.get("amount").toString());
        return amount > 1000 ? "大额转账审批" : null;  // null = 放行
    });

ReActAgent agent = ReActAgent.of(chatModel)
    .role("银行专员")
    .defaultToolAdd(new BankTools())
    .defaultInterceptorAdd(hitlInterceptor)
    .build();

AgentSession session = InMemoryAgentSession.of("user_001");

// 1. 发起请求 — 触发拦截
ReActResponse resp1 = agent.prompt("给张三转账 5000 元").session(session).call();
assert resp1.getSession().isPending();  // 会话挂起

// 2. 人工决策 — 批准并修正金额
HITLTask task = HITL.getPendingTask(session);
HITLDecision decision = HITLDecision.approve()
    .comment("同意转账，但修正了金额")
    .modifiedArgs(Map.of("to", "张三", "amount", 800.0));
HITL.submit(session, task.getToolName(), decision);

// 3. 恢复执行
ReActResponse resp2 = agent.prompt().session(session).call();
assert !resp2.getSession().isPending();  // 流程正常结束
```

---

## 3. 权限模式 (PermissionMode)

`PermissionMode` 控制无规则匹配时的默认行为。

| 模式 | 写操作 | 读操作 | 适用场景 |
|------|--------|--------|----------|
| `DEFAULT` | ASK（人工确认） | ASK | 日常开发，安全优先 |
| `READ_ONLY` | DENY（拒绝） | ALLOW | 只读分析模式，不修改任何文件 |
| `BYPASS` | ALLOW | ALLOW | 完全信任，跳过所有审批 |
| `ACCEPT_EDITS` | ALLOW | ASK | 自动接受文件编辑，网络等仍需确认 |
| `DONT_ASK` | ALLOW | ALLOW | 免打扰，不弹确认 |
| `AUTO` | ALLOW | ALLOW | 自动模式，使用分类器自动决策 |

### 设置方式

**Builder 阶段：**

```java
HarnessEngine.of(workspace, home)
    .permissionMode(PermissionMode.DEFAULT)
    .build();
```

**运行时动态切换：**

```java
engine.setPermissionMode(PermissionMode.READ_ONLY);  // 切换到只读模式
```

---

## 4. 权限规则 (PermissionRule)

`PermissionRule` 将工具名（含可选 glob 模式）映射到一种权限行为。

### 4.1 权限行为 (PermissionBehavior)

| 行为 | 说明 |
|------|------|
| `ALLOW` | 放行工具执行 |
| `DENY` | 拒绝工具执行 |
| `ASK` | 交给人工确认 |
| `PASSTHROUGH` | 跳过此规则，继续匹配下一条 |

### 4.2 规则来源 (RuleSource)

| 来源 | 说明 |
|------|------|
| `USER_SETTINGS` | 用户级配置 |
| `PROJECT_SETTINGS` | 项目级配置 |
| `LOCAL_SETTINGS` | 本地项目配置（不提交到版本控制） |
| `FLAG_SETTINGS` | 命令行标志参数 |
| `POLICY_SETTINGS` | 组织策略配置 |
| `CLI_ARG` | CLI 参数 |
| `COMMAND` | 命令行（运行时） |
| `SESSION` | 当前会话（运行时，由 alwaysAllow 自动注入） |

### 4.3 创建规则

```java
// 无模式规则 — 匹配该工具的所有调用
PermissionRule allowBash = PermissionRule.allow("bash", RuleSource.USER_SETTINGS);
PermissionRule denyWrite = PermissionRule.deny("write", RuleSource.PROJECT_SETTINGS);
PermissionRule askWeb   = PermissionRule.ask("webfetch", RuleSource.USER_SETTINGS);

// 带 glob 模式规则 — 匹配特定参数模式
PermissionRule allowGitPush = PermissionRule.allow("bash", "git push *", RuleSource.PROJECT_SETTINGS);
PermissionRule denyRmRf     = PermissionRule.deny("bash", "rm -rf *", RuleSource.PROJECT_SETTINGS);
PermissionRule allowWriteSrc = PermissionRule.allow("write", "src/*.java", RuleSource.LOCAL_SETTINGS);

// PASSTHROUGH — 跳过此规则，继续匹配下一条
PermissionRule passthrough = new PermissionRule("bash", PermissionBehavior.PASSTHROUGH,
    RuleSource.USER_SETTINGS, null);
```

### 4.4 glob 模式说明

模式通过 `globToRegex()` 转换为正则表达式，与工具参数中提取的文本进行匹配：

| glob 模式 | 正则 | 匹配示例 |
|-----------|------|----------|
| `git push *` | `git\ push\ .*` | `git push origin main` |
| `src/*.java` | `src/.*\.java` | `src/Main.java` |
| `cat *` | `cat\ .*` | `cat README.md` |
| `rm -rf *` | `rm\ \-rf\ .*` | `rm -rf /tmp/cache` |

**参数提取字段优先级**：`command` > `file_path` > `path` > `content` > `url` > `link`

### 4.5 添加规则

**Builder 阶段：**

```java
HarnessEngine.of(workspace, home)
    .permissionRuleAdd(PermissionRule.deny("bash", "rm -rf *", RuleSource.PROJECT_SETTINGS))
    .permissionRuleAdd(PermissionRule.allow("bash", "git push *", RuleSource.PROJECT_SETTINGS))
    .build();
```

**运行时动态添加：**

```java
engine.addPermissionRule(PermissionRule.allow("webfetch", "https://api.example.com/*",
    RuleSource.SESSION));
```

---

## 5. 权限上下文 (PermissionContext)

`PermissionContext` 是不可变对象，包含权限模式、规则列表、工作目录和附加目录。所有变更方法返回新实例。

### 5.1 创建

```java
// 默认上下文（工作目录为当前目录，DEFAULT 模式，无规则）
PermissionContext ctx = PermissionContext.create();

// 指定工作目录
PermissionContext ctx = PermissionContext.of(Path.of("/workspace"));

// 使用 Builder
PermissionContext ctx = PermissionContext.builder()
    .workingDirectory(Path.of("/workspace"))
    .mode(PermissionMode.DEFAULT)
    .addRule(PermissionRule.deny("bash", "rm *", RuleSource.PROJECT_SETTINGS))
    .addDir(Path.of("/workspace/external"))
    .build();
```

### 5.2 不可变更新

```java
PermissionContext ctx = PermissionContext.create();

// 切换模式
PermissionContext ctx2 = ctx.withMode(PermissionMode.READ_ONLY);

// 添加规则
PermissionContext ctx3 = ctx.addRule(PermissionRule.allow("bash", RuleSource.SESSION));

// 批量添加
PermissionContext ctx4 = ctx.addRules(List.of(
    PermissionRule.allow("write", RuleSource.SESSION),
    PermissionRule.deny("bash", "rm *", RuleSource.PROJECT_SETTINGS)
));

// 替换全部规则
PermissionContext ctx5 = ctx.replaceRules(newRules);

// 按条件移除规则
PermissionContext ctx6 = ctx.removeRules(rule -> rule.source() == RuleSource.SESSION);

// 添加附加目录
PermissionContext ctx7 = ctx.addDirectories(List.of(Path.of("/shared")));
```

> **注意**：所有变更方法不会修改原对象，而是返回新实例。在 HarnessEngine 中通过 `options.setPermissionContext()` 更新引用。

---

## 6. 权限评估引擎 (PermissionEngine)

`PermissionEngine` 是权限决策的核心，评估工具调用是否应被放行、拒绝或需要人工介入。

### 6.1 评估优先级

```
1. DENY 最高优先级 — 遍历所有规则，任何匹配的 DENY 规则立即拒绝
2. ALLOW / ASK 按规则顺序 — 首个匹配的非 DENY 规则决定结果
   （PASSTHROUGH 规则匹配后跳过，继续评估下一条）
3. 模式降级 — 无规则匹配时，按 PermissionMode 返回默认决策
```

### 6.2 模式降级表

| 模式 | 写工具 | 非写工具 |
|------|--------|----------|
| `DEFAULT` | ASK | ASK |
| `READ_ONLY` | DENY | ALLOW |
| `BYPASS` | ALLOW | ALLOW |
| `ACCEPT_EDITS` | ALLOW | ASK |
| `DONT_ASK` | ALLOW | ALLOW |
| `AUTO` | ALLOW | ALLOW |

**写工具白名单**：`bash`, `write`, `edit`, `rm`, `mv`, `cp`, `mkdir`

### 6.3 直接使用

```java
PermissionEngine engine = new PermissionEngine();
PermissionContext ctx = PermissionContext.builder()
    .mode(PermissionMode.DEFAULT)
    .addRule(PermissionRule.deny("bash", "rm -rf *", RuleSource.PROJECT_SETTINGS))
    .addRule(PermissionRule.allow("bash", "git status", RuleSource.SESSION))
    .build();

Map<String, Object> args = Map.of("command", "rm -rf /tmp");

PermissionDecision decision = engine.evaluate("bash", args, ctx);
// 返回 DENY — 匹配到 deny 规则
```

### 6.4 决策结果 (PermissionDecision)

| 结果 | 说明 |
|------|------|
| `ALLOW` | 放行工具执行 |
| `DENY` | 拒绝工具执行 |
| `ASK` | 需要人工介入确认 |

---

## 7. 内置干预策略

### 7.1 BashToolStrategy（Bash 工具）

专用于 `bash` 工具，三层评估链：

```
P0 硬编码防御（始终生效，不依赖规则引擎）
  ├── A. 注入与子 Shell 防御：` ` `, `$(`, `/dev/`
  ├── B. 系统特权指令：sudo, su, chown, chmod, chgrp, passwd, visudo
  └── C. 路径边界检查：../, ..\, /etc/, /var/, /root/, ~/.ssh/, ~/.bashrc, ~/.zshrc
         ↓（未命中 P0）
内置只读命令分类（BashCommandClassifier）
  ├── DENY — 空命令或不完整命令
  ├── ALLOW — 只读命令，直接放行（不触发人工确认）
  └── ASK — 其他命令，继续往下
         ↓
PermissionEngine（规则 + 模式决策）
  ├── 规则匹配 → DENY / ALLOW / ASK
  └── 无规则匹配 → 模式降级
```

```java
BashToolStrategy strategy = new BashToolStrategy()
    .permissionContextSupplier(options::getPermissionContext);
```

### 7.2 WriteToolStrategy（Write/Edit 工具）

适用于 `write`、`edit` 等文件写入工具：

```
P0 硬编码防御（路径回溯 + 系统敏感目录）
  ├── ../, ..\
  └── /etc/, /var/, /root/, ~/.ssh/, ~/.bashrc, ~/.zshrc
         ↓
PermissionEngine（规则 + 模式决策）
  ├── 规则匹配 → DENY / ALLOW / ASK
  └── 无规则匹配 → 模式降级
```

```java
WriteToolStrategy writeStrategy = new WriteToolStrategy("write")
    .permissionContextSupplier(options::getPermissionContext);

WriteToolStrategy editStrategy = new WriteToolStrategy("edit")
    .permissionContextSupplier(options::getPermissionContext);
```

### 7.3 WebToolStrategy（Web 工具）

适用于 `webfetch`、`websearch` 等网络访问工具：

```
域名级风险检查（内置黑名单）
  ├── facebook.com, twitter.com, x.com
  ├── tiktok.com, reddit.com, linkedin.com
  └── 支持子域名匹配（www.facebook.com 也命中）
         ↓（未命中黑名单）
PermissionEngine（规则 + 模式决策）
  ├── 规则匹配 → DENY / ALLOW / ASK
  └── 无规则匹配 → 模式降级
```

```java
WebToolStrategy webfetchStrategy = new WebToolStrategy("webfetch")
    .permissionContextSupplier(options::getPermissionContext);

WebToolStrategy websearchStrategy = new WebToolStrategy("websearch")
    .permissionContextSupplier(options::getPermissionContext);
```

### 7.4 HITLSensitiveStrategy（敏感工具一刀切）

最简单的策略 — 无条件返回拦截理由，所有调用都需要人工确认。

```java
HITLInterceptor interceptor = new HITLInterceptor()
    .onSensitiveTool("transfer", "delete", "drop");
```

或自定义拦截文案：

```java
HITLInterceptor interceptor = new HITLInterceptor()
    .onTool("transfer", new HITLInterceptor.HITLSensitiveStrategy()
        .comment("敏感操作：转账需要人工审批"));
```

---

## 8. Bash 命令分类器 (BashCommandClassifier)

`BashCommandClassifier` 是 `BashToolStrategy` 的内置组件，对 bash 命令进行三级分类。

### 8.1 只读命令白名单（50+ 条）

| 分类 | 命令 |
|------|------|
| 搜索类 | `grep`, `egrep`, `fgrep`, `rg`, `ag`, `ack`, `find`, `fd`, `locate` |
| 列目录类 | `ls`, `dir`, `tree`, `exa` |
| 查看文件类 | `cat`, `bat`, `less`, `more`, `head`, `tail` |
| 统计信息类 | `wc`, `file`, `which`, `whereis`, `whence`, `type`, `stat`, `du`, `df` |
| 输出类 | `echo`, `printf` |
| 文本处理类 | `diff`, `comm`, `sort`, `uniq`, `cut`, `tr`, `awk`, `sed` |
| 格式化工具 | `jq`, `yq`, `xmllint` |
| Git 只读 | `git log`, `git show`, `git diff`, `git status`, `git branch`, `git tag`, `git remote`, `git rev-parse`, `git ls-files`, `git blame`, `git shortlog` |
| 系统信息 | `pwd`, `env`, `printenv`, `id`, `whoami`, `hostname`, `uname`, `date`, `cal` |

### 8.2 安全检测

| 检测项 | 说明 | 示例 |
|--------|------|------|
| 不完整命令 | 尾部有 `\|`, `&&`, `\|\|`, `;` | `cat file \|` → DENY |
| `sed -i` 原地修改 | 检测 `-i` / `--in-place` 标志 | `sed -i 's/a/b/' file` → ASK |
| 输出重定向 `>` | 逐字符检测，排除 `2>` stderr | `cat file > out.txt` → ASK |
| `git branch -d/-D` | 检测删除分支标志 | `git branch -D feature` → ASK |
| 管道分段检查 | 所有段都必须是只读命令 | `cat file \| grep x` → ALLOW |

### 8.3 分类结果

| 结果 | 条件 |
|------|------|
| `DENY` | 空命令或不完整命令 |
| `ALLOW` | 只读命令（含管道，所有段都是只读） |
| `ASK` | 其他所有命令 |

### 8.4 直接使用

```java
BashCommandClassifier classifier = new BashCommandClassifier();

classifier.classify("ls -la");           // ALLOW
classifier.classify("cat README.md");    // ALLOW
classifier.classify("git status");       // ALLOW
classifier.classify("cat file | grep x"); // ALLOW
classifier.classify("sed -i 's/a/b/' f"); // ASK
classifier.classify("rm -rf /tmp");      // ASK
classifier.classify("cat file |");       // DENY (不完整)
classifier.classify("");                 // DENY (空命令)
```

---

## 9. HITL 交互助手 API

`HITL` 是面向业务层（Controller / Service）的静态工具类，用于任务探知和决策回填。

### 9.1 任务探知

```java
// 检查会话是否有挂起任务
boolean isHitl = HITL.isHitl(session);

// 获取当前挂起的任务（含工具名、参数快照、拦截理由）
HITLTask task = HITL.getPendingTask(session);
if (task != null) {
    String toolName = task.getToolName();    // "bash"
    Map<String, Object> args = task.getArgs(); // {"command": "rm -rf /tmp"}
    String comment = task.getComment();      // "高危操作，需要人工介入确认。"
}
```

### 9.2 决策回填

```java
// 批准（直接放行）
HITL.approve(session, "bash");

// 批准 + 始终允许（后续同类操作自动放行，注入会话级规则）
HITL.approve(session, "bash", true);

// 批准 + 备注
HITL.approve(session, "bash", "已确认安全");

// 拒绝（终止流程，Agent 输出拒绝理由）
HITL.reject(session, "bash");

// 拒绝 + 理由
HITL.reject(session, "bash", "禁止在生产环境执行此操作");

// 跳过（不执行工具，但向 Agent 返回跳过原因，流程继续）
HITL.skip(session, "bash");

// 跳过 + 原因
HITL.skip(session, "bash", "该步骤暂不执行，请继续下一步");
```

### 9.3 高级决策（参数修正）

```java
// 批准并修正参数 — 覆盖 AI 生成的原始参数
HITLDecision decision = HITLDecision.approve()
    .comment("同意执行，但修正了金额")
    .modifiedArgs(Map.of("amount", 800.0));
HITL.submit(session, "transfer", decision);
```

### 9.4 状态管理

```java
// 获取指定工具的决策
HITLDecision decision = HITL.getDecision(session, "bash");

// 清理状态（通常不需要手动调用，onObservation 会自动清理）
HITL.clear(session, task);
```

---

## 10. HITLDecision 决策实体

`HITLDecision` 定义了人工对 AI 行为的最终裁决。

### 10.1 三种决策动作

| 动作 | 常量 | 说明 |
|------|------|------|
| 批准 | `ACTION_APPROVE` | 执行工具，支持参数修正和备注注入 |
| 拒绝 | `ACTION_REJECT` | 强制终止，流程路由至 END，Agent 输出拒绝理由 |
| 跳过 | `ACTION_SKIP` | 不执行工具，但向 Agent 返回跳过原因的 Observation |

### 10.2 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `action` | `int` | 决策动作（APPROVE / REJECT / SKIP） |
| `comment` | `String` | 审批意见（拒绝理由或操作备注） |
| `modifiedArgs` | `Map<String, Object>` | 修正后的参数（覆盖 AI 原参数） |
| `alwaysAllow` | `boolean` | 是否"始终允许"（触发会话级规则注入） |

### 10.3 工厂方法

```java
// 批准
HITLDecision.approve();
HITLDecision.approve(true);  // alwaysAllow = true

// 拒绝
HITLDecision.reject("操作不允许");

// 跳过
HITLDecision.skip("暂不执行");
```

### 10.4 链式 API

```java
HITLDecision decision = HITLDecision.approve()
    .comment("同意，但修正了参数")
    .modifiedArgs(Map.of("path", "/safe/path", "content", "safe content"))
    .alwaysAllow(true);
```

### 10.5 查询方法

```java
decision.isApproved();    // true
decision.isRejected();    // false
decision.isSkipped();     // false
decision.isAlwaysAllow(); // true
decision.getComment();    // "同意，但修正了参数"
decision.getModifiedArgs(); // {path=/safe/path, content=safe content}
```

---

## 11. HITLInterceptor 拦截器

`HITLInterceptor` 通过 ReAct 协议的生命周期钩子实现流程管控。

### 11.1 生命周期

```
Agent 推理 → 工具调用 → onAction → ... → onObservation → 下一轮推理
                            │              │
                            │              └── 清理状态 + 注入备注
                            │
                            ├── 无决策 → 挂起任务（session.pending = true）
                            ├── 已批准 → 执行工具（可选参数修正 + alwaysAllow 回调）
                            ├── 已跳过 → 返回跳过原因的 Observation
                            └── 已拒绝 → 设置 FinalAnswer + 路由到 END
```

### 11.2 注册策略

```java
HITLInterceptor interceptor = new HITLInterceptor()
    // 注册自定义策略
    .onTool("transfer", (trace, args) -> {
        double amount = (Double) args.get("amount");
        return amount > 1000 ? "大额转账审批" : null;
    })
    // 注册敏感工具（一刀切策略）
    .onSensitiveTool("delete", "drop")
    // 注册 alwaysAllow 回调
    .onApproved((toolName, args) -> {
        System.out.println("工具 " + toolName + " 被批准，参数: " + args);
    });
```

### 11.3 InterventionStrategy 接口

```java
@FunctionalInterface
public interface InterventionStrategy {
    /**
     * 评估是否需要干预
     *
     * @param trace  ReAct 执行轨迹
     * @param args   工具参数
     * @return 拦截理由文案（触发拦截）；null（不拦截，直接执行）
     */
    String evaluate(ReActTrace trace, Map<String, Object> args);
}
```

返回值语义：
- `null` — 放行，不拦截
- 非 null 字符串 — 拦截，字符串作为拦截理由展示给用户

### 11.4 启用/禁用

```java
// 运行时动态启用/禁用
interceptor.setEnabled(false);  // 禁用 HITL（所有工具直接放行）
interceptor.setEnabled(true);   // 重新启用

// 通过 HarnessEngine
engine.setHitlEnabled(false);
engine.setHitlEnabled(true);
```

---

## 12. alwaysAllow 始终允许机制

当用户通过 `HITL.approve(session, toolName, true)` 批准并标记 `alwaysAllow = true` 时，系统会自动向 `PermissionContext` 注入一条会话级规则，使后续同类操作自动放行。

### 12.1 注入逻辑

HarnessEngine 默认注册的 `onApproved` 回调会从工具参数中提取模式：

| 工具 | 提取字段 | 注入规则示例 |
|------|----------|-------------|
| `bash` | `command` | `PermissionRule.allow("bash", "git push origin main", SESSION)` |
| `write` | `file_path` | `PermissionRule.allow("write", "src/Main.java", SESSION)` |
| `edit` | `file_path` | `PermissionRule.allow("edit", "src/Main.java", SESSION)` |
| `webfetch` | `url` | `PermissionRule.allow("webfetch", "https://api.example.com/*", SESSION)` |
| 其他 | 无 | `PermissionRule.allow(toolName, SESSION)`（工具级放行） |

### 12.2 细粒度放行

```java
// 用户批准了 bash: git push origin main
HITL.approve(session, "bash", true);

// 注入规则：PermissionRule.allow("bash", "git push origin main", SESSION)
// 后续 "git push origin main" 自动放行
// 但 "rm -rf /" 仍会触发人工确认
```

### 12.3 自定义 onApproved 回调

```java
HITLInterceptor interceptor = new HITLInterceptor()
    .onTool("transfer", strategy)
    .onApproved((toolName, args) -> {
        // 自定义规则注入逻辑
        String pattern = (String) args.get("command");
        if (pattern != null) {
            engine.addPermissionRule(
                PermissionRule.allow(toolName, pattern, RuleSource.SESSION));
        }
    });
```

---

## 13. 自定义干预策略

### 13.1 实现 InterventionStrategy

```java
public class MyStrategy implements HITLInterceptor.InterventionStrategy {
    @Override
    public String evaluate(ReActTrace trace, Map<String, Object> args) {
        String command = (String) args.get("command");

        // 检查是否在工作时间内
        int hour = LocalTime.now().getHour();
        if (hour < 9 || hour > 18) {
            return "非工作时间，高危操作需要主管审批。";
        }

        // 检查命令是否包含危险关键词
        if (command.contains("DROP") || command.contains("DELETE")) {
            return "检测到数据库危险操作，需要 DBA 审批。";
        }

        return null;  // 放行
    }
}
```

### 13.2 注册到拦截器

```java
HITLInterceptor interceptor = new HITLInterceptor()
    .onTool("execute_sql", new MyStrategy());
```

### 13.3 结合 PermissionEngine

```java
public class DatabaseStrategy implements HITLInterceptor.InterventionStrategy {
    private final PermissionEngine engine = new PermissionEngine();
    private Supplier<PermissionContext> ctxSupplier = () -> PermissionContext.create();

    public DatabaseStrategy permissionContextSupplier(Supplier<PermissionContext> supplier) {
        this.ctxSupplier = supplier;
        return this;
    }

    @Override
    public String evaluate(ReActTrace trace, Map<String, Object> args) {
        // 自定义 P0 防御
        String sql = (String) args.get("sql");
        if (sql != null && sql.toUpperCase().contains("DROP TABLE")) {
            return "禁止 DROP TABLE 操作。";
        }

        // 委托 PermissionEngine
        PermissionDecision decision = engine.evaluate("execute_sql", args, ctxSupplier.get());
        switch (decision) {
            case ALLOW: return null;
            case DENY:  return "操作被权限策略拒绝。";
            case ASK:   return "数据库操作需要人工确认。";
            default:    return null;
        }
    }
}
```

---

## 14. Web 集成示例

### 14.1 Controller 模式

```java
@Controller
@Path("/api/agent")
public class AgentController {

    @Inject
    HarnessEngine engine;

    @Inject
    AgentSessionProvider sessionProvider;

    /**
     * 发起对话
     */
    @Post
    @Mapping("/chat")
    public String chat(@Body ChatRequest req) {
        AgentSession session = sessionProvider.getSession(req.getSessionId());
        ReActResponse resp = engine.prompt(req.getMessage())
            .session(session)
            .call();

        if (session.isPending()) {
            // 任务被 HITL 拦截，返回审批任务信息
            HITLTask task = HITL.getPendingTask(session);
            return JSON.toJSONString(Map.of(
                "status", "pending",
                "task", task
            ));
        }

        return JSON.toJSONString(Map.of(
            "status", "done",
            "content", resp.getContent()
        ));
    }

    /**
     * 人工审批 — 批准
     */
    @Post
    @Mapping("/approve")
    public String approve(@Body ApproveRequest req) {
        AgentSession session = sessionProvider.getSession(req.getSessionId());
        HITLTask task = HITL.getPendingTask(session);

        if (task == null) {
            return "无挂起任务";
        }

        // 构建决策
        HITLDecision decision = HITLDecision.approve(req.isAlwaysAllow());
        if (req.getComment() != null) {
            decision.comment(req.getComment());
        }
        if (req.getModifiedArgs() != null) {
            decision.modifiedArgs(req.getModifiedArgs());
        }

        HITL.submit(session, task.getToolName(), decision);

        // 恢复执行
        ReActResponse resp = engine.prompt().session(session).call();

        if (session.isPending()) {
            // 恢复后又被拦截（可能是下一个工具）
            HITLTask nextTask = HITL.getPendingTask(session);
            return JSON.toJSONString(Map.of(
                "status", "pending",
                "task", nextTask
            ));
        }

        return JSON.toJSONString(Map.of(
            "status", "done",
            "content", resp.getContent()
        ));
    }

    /**
     * 人工审批 — 拒绝
     */
    @Post
    @Mapping("/reject")
    public String reject(@Body RejectRequest req) {
        AgentSession session = sessionProvider.getSession(req.getSessionId());
        HITLTask task = HITL.getPendingTask(session);

        if (task == null) {
            return "无挂起任务";
        }

        HITL.reject(session, task.getToolName(), req.getReason());

        ReActResponse resp = engine.prompt().session(session).call();

        return JSON.toJSONString(Map.of(
            "status", "done",
            "content", resp.getContent()
        ));
    }

    /**
     * 人工审批 — 跳过
     */
    @Post
    @Mapping("/skip")
    public String skip(@Body SkipRequest req) {
        AgentSession session = sessionProvider.getSession(req.getSessionId());
        HITLTask task = HITL.getPendingTask(session);

        if (task == null) {
            return "无挂起任务";
        }

        HITL.skip(session, task.getToolName(), req.getReason());

        ReActResponse resp = engine.prompt().session(session).call();

        if (session.isPending()) {
            HITLTask nextTask = HITL.getPendingTask(session);
            return JSON.toJSONString(Map.of(
                "status", "pending",
                "task", nextTask
            ));
        }

        return JSON.toJSONString(Map.of(
            "status", "done",
            "content", resp.getContent()
        ));
    }
}
```

### 14.2 前端交互流程

```
1. POST /api/agent/chat      → { status: "pending", task: { toolName, args, comment } }
2. 用户在 UI 上查看任务详情
3. POST /api/agent/approve   → { status: "done", content: "..." }
   或
   POST /api/agent/reject    → { status: "done", content: "操作被拒绝..." }
   或
   POST /api/agent/skip      → { status: "done", content: "..." }
```

---

## 15. API 速查表

### HITL 静态方法

| 方法 | 说明 |
|------|------|
| `HITL.isHitl(session)` | 检查是否有挂起任务 |
| `HITL.getPendingTask(session)` | 获取挂起任务实体 |
| `HITL.getDecision(session, task)` | 获取指定任务的决策 |
| `HITL.getDecision(session, toolName)` | 获取指定工具的决策 |
| `HITL.approve(session, toolName)` | 批准 |
| `HITL.approve(session, toolName, alwaysAllow)` | 批准 + 始终允许 |
| `HITL.approve(session, toolName, comment)` | 批准 + 备注 |
| `HITL.reject(session, toolName)` | 拒绝 |
| `HITL.reject(session, toolName, comment)` | 拒绝 + 理由 |
| `HITL.skip(session, toolName)` | 跳过 |
| `HITL.skip(session, toolName, comment)` | 跳过 + 原因 |
| `HITL.submit(session, toolName, decision)` | 提交完整决策实体 |
| `HITL.clear(session, task)` | 清理状态 |

### HITLDecision 工厂方法

| 方法 | 说明 |
|------|------|
| `HITLDecision.approve()` | 创建批准决策 |
| `HITLDecision.approve(alwaysAllow)` | 创建批准决策（带 alwaysAllow） |
| `HITLDecision.reject(comment)` | 创建拒绝决策 |
| `HITLDecision.skip(comment)` | 创建跳过决策 |

### HITLDecision 链式方法

| 方法 | 说明 |
|------|------|
| `.comment(String)` | 设置审批意见 |
| `.modifiedArgs(Map)` | 设置修正参数 |
| `.alwaysAllow(boolean)` | 设置始终允许标志 |

### HITLInterceptor 方法

| 方法 | 说明 |
|------|------|
| `.onTool(toolName, strategy)` | 注册工具策略 |
| `.onSensitiveTool(toolNames...)` | 注册敏感工具（一刀切） |
| `.onApproved(callback)` | 注册批准回调（alwaysAllow 用） |
| `.setEnabled(boolean)` | 启用/禁用 |

### PermissionRule 工厂方法

| 方法 | 说明 |
|------|------|
| `PermissionRule.allow(toolName, source)` | 放行（无模式） |
| `PermissionRule.allow(toolName, pattern, source)` | 放行（带 glob 模式） |
| `PermissionRule.deny(toolName, source)` | 拒绝（无模式） |
| `PermissionRule.deny(toolName, pattern, source)` | 拒绝（带 glob 模式） |
| `PermissionRule.ask(toolName, source)` | 询问（无模式） |
| `PermissionRule.ask(toolName, pattern, source)` | 询问（带 glob 模式） |

### HarnessEngine 权限方法

| 方法 | 说明 |
|------|------|
| `getPermissionContext()` | 获取当前权限上下文 |
| `setPermissionMode(mode)` | 切换权限模式 |
| `addPermissionRule(rule)` | 添加权限规则 |
| `addPermissionRules(rules)` | 批量添加权限规则 |
| `setHitlEnabled(boolean)` | 启用/禁用 HITL |
| `getHitlInterceptor()` | 获取 HITL 拦截器 |

### HarnessEngine.Builder 权限方法

| 方法 | 说明 |
|------|------|
| `.permissionMode(mode)` | 设置初始权限模式 |
| `.permissionRuleAdd(rule)` | 添加初始权限规则 |
| `.hitlEnabled(boolean)` | 启用/禁用 HITL |
| `.hitlInterceptor(interceptor)` | 自定义 HITL 拦截器 |
