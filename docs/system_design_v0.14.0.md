# AgentCLI v0.14.0 · MCP @mention 注入 + Server→Client 双向通知 — 系统架构设计 + 任务分解

> 角色：架构师（高见远）｜阶段：架构设计（交付工程师实现，QA 测试）
> 项目：AgentCLI（Java 17 + Maven，ReAct 范式命令行 AI 编程助手；根 `D:/myfiles/share/study/java_project/paicli`）
> 配套图：类图 `docs/class-diagram.mermaid`；时序图 `docs/sequence-diagram.mermaid`
> 前置核对：下列结论均来自**实际 Read 源码**，非凭空假设（详见第 0 节）。

---

## 0. 范围、硬约束与源码核对结论（已 Read 源码，非凭空假设）

**目标（v0.14.0）**：补齐 MCP 的两大能力——
1. **@mention 文件引用（大上下文处理）**：用户/模型在 REPL 自由文本或工具参数中以 `@path` 把项目文件注入 MCP 工具调用；
2. **server→client 双向通知**：实时、非阻塞地展示 server 推送的 `window/logMessage` / `notifications/progress` / `notifications/message`。

**硬约束（来自团队共识 + PRD，贯穿始终）**
1. **零新增依赖**：仅用现有 MCP/Jackson/OkHttp/JLine 依赖，**不引入任何新 `<dependency>`**。
2. **不破坏公开契约**：`McpTransport` 接口契约**不变**（零改动）；`McpClient` 公开方法**新增重载/新方法**，不改既有签名；`JsonRpcClient.onNotification` 直接复用。
3. **降级兼容**：server 不发通知也能正常用；`@mention` 不写也不影响既有调用；解析/通知失败绝不冒泡到主流程。

### 0.1 源码核对结论（决定设计的关键事实）

| # | 待核实项 | 核实结果 | 对设计的影响 |
|---|----------|----------|--------------|
| 1 | `JsonRpcClient` 是否真有 `onNotification(Consumer<JsonNode>)` 通道？ | ✅ **存在**（`jsonrpc/JsonRpcClient.java:85`）。内部 `notificationListeners` 为 `CopyOnWriteArrayList`；`handleMessage(JsonNode)` 判定 `id==null` → 视为通知 → 遍历 `notificationListeners`（`JsonRpcClient.java:91-98`）。 | P0-3 **直接复用**该通道，**无需扩展 McpTransport 契约**（见下条）。 |
| 2 | `McpTransport` 契约能否不动？ | ✅ 契约为 `send` / `onReceive(Consumer)` / `close`，外加 default `stderrLines` / `processId` / `transportName`。两种传输 `StdioTransport`、`HttpTransport` 的 `onReceive` 都把入站消息交给 `JsonRpcClient.handleMessage`（Stdio 在 reader 守护线程；Http 在 `send()` 内直接 iterate listeners），**两条路径都汇入 `handleMessage` → `onNotification`**。 | **完全不碰 McpTransport**，且通知路由对 stdio/http 传输**天然透明**。 |
| 3 | `McpToolBridge` 是否存在？注入落点？ | ✅ **存在**（`McpToolBridge.java:15`）。`execute(Map<String,String>)`（`:45`）把 Map 转 JSON 字符串后调用 `client.callTool(descriptor.name(), argumentsJson)`（`:68`）。 | P1-3（工具参内 @mention）**经 `McpClient.callTool` 的 JSON 注入点自动覆盖**，无需改 `McpToolBridge` 落点。PRD 假设成立，**无需修正**。 |
| 4 | `McpClient.callTool` 签名？ | ✅ `String callTool(String toolName, String argumentsJson)`（`McpClient.java:82`）。当前**无 `projectDir`**、**未注册任何通知监听器**。 | 注入落点 = `callTool` 内对 `argumentsJson` 做 mention 解析后再 `rpc.request("tools/call",...)`；`projectDir` 经构造重载注入。 |
| 5 | `McpServerManager` 如何持有/创建 `McpClient`？ | ✅ `start(server)`（`McpServerManager.java:97`）内 `new McpClient(server.name(), transport)`（`:110`）→ `client.initialize()`（`:111`）。持有 `Path projectDir`（已 `toAbsolutePath().normalize()`）。 | **唯一自然接线点**：改用 4 参构造 `new McpClient(name, transport, projectDir, presenter)`，并在 `initialize()` 后/中注册 `NotificationDispatcher`。 |
| 6 | 现有路径安全机制？ | ✅ `common/PathGuard.resolveSafe(String)`（`PathGuard.java:51`）已实现越界拒绝（抛 `PolicyException`），覆盖相对/绝对/`..` 穿越/符号链接逃逸，并 `toRealPath` 归一化。 | `MentionResolver` **复用 `PathGuard`**，不自造安全逻辑；仅额外补 `~` 展开（PathGuard 不处理 `~`）。 |
| 7 | 终端打印/样式机制？ | ✅ `common/AnsiStyle`（颜色/isEnabled）可用；`StreamingConsoleListener` 用 `PrintStream out` 渲染。REPL 输入经 `ReplReader`（jline `Terminal`）。 | 终端展示**复用 `AnsiStyle` 着色 + 经 `ReplReader.printAsync` 安全打印**（新增 jline 安全打印方法，避免抢占输入）。 |

---

## 1. 实现方案 + 框架选型

### 1.1 框架/库选型（全部复用现有，零新增依赖）

| 能力 | 复用项（已在依赖树） | 说明 |
|------|----------------------|------|
| JSON 解析/序列化 | `com.fasterxml.jackson.databind`（jackson-databind 2.16.0） | mention 解析 JSON 参数、通知报文解析 |
| JSON-RPC 通知通道 | `JsonRpcClient.onNotification(Consumer<JsonNode>)`（**已有**） | 不碰 `McpTransport` 契约 |
| 路径安全 | `common.PathGuard` + `common.PolicyException`（**已有**） | 越界拒绝单一真相源 |
| 终端样式 | `common.AnsiStyle`（**已有**） | 通知级别着色 |
| REPL 安全打印 | `cli.ReplReader`（jline Terminal，**已有**，需加 `printAsync`） | 非阻塞、不抢占输入 |
| 单元测试 | `org.junit.jupiter`（**已有 test scope**）+ 现有 `FakeTransport` 模式（见 §2） | 无新测试依赖 |

### 1.2 架构总览（为什么这样不破坏抽象）

```
                          ┌─────────────────────────────────────────┐
   REPL 自由文本 @path ──► │ InteractiveSession.run()                 │
   (NONE 命令才解析)       │   line = mentionResolver.resolve(line)    │
                          └───────────────┬─────────────────────────┘
                                          │ agent.run(resolved)
                                          ▼
  模型生成工具参 @path ──► McpToolBridge.execute(Map) ──► McpClient.callTool(name, json)
                                          │                │ MentionResolver.resolveJson(json)
                                          │                ▼
                                          │         JsonRpcClient.request("tools/call")
                                          │                │
   ┌──────────────────────────────────────┴────────────────┴──────────────────────────┐
   │ MCP 客户端层 (com.agentcli.mcp)                                               │
   │  McpClient ──持有──> MentionResolver (复用 PathGuard)                          │
   │  McpClient ──持有──> NotificationDispatcher ──► NotificationPresenter(接口)      │
   │                              │ 解析 method→level→NotificationRecord              │
   │                              ▼                                                   │
   │                    TerminalNotificationPresenter (复用 AnsiStyle + printAsync)  │
   └──────────────────────────────────────┬──────────────────────────────────────────┘
                                          │ rpc.onNotification(dispatcher::accept)
                                          ▼
   server 推送通知 ──► McpTransport(onReceive) ──► JsonRpcClient.handleMessage ──► notificationListeners
   (stdio: reader线程 / http: 请求线程，均经 handleMessage 路由，对传输透明)
```

**关键设计决策（满足硬约束）**
- **零改动 `McpTransport`**：通知完全走既有 `onReceive`→`JsonRpcClient.handleMessage`→`onNotification` 链路。我们**只在 `McpClient` 内注册一个监听器**，不新增传输接口/方法。
- **`McpClient` 兼容**：保留 2 参构造 `McpClient(String, McpTransport)`（既有行为，mention/presenter 均为 null → 完全降级）；**新增** 4 参构造 `McpClient(String, McpTransport, Path projectDir, NotificationPresenter)`。既有 `callTool` 等签名不变，仅内部增加“若无 mentionResolver 则跳过解析”的兼容分支。
- **单一注入落点（callTool）**：`@mention` 解析集中在 `McpClient.callTool` 对 `argumentsJson` 的处理（`resolveJson`），REPL 自由文本走 `MentionResolver.resolve` 同一工具类 → **两个入口，一套解析逻辑**，便于测试与维护。P1-3 因 `McpToolBridge.execute` 最终调用 `callTool` 而自动覆盖。
- **终端展示复用既有通道**：着色用 `AnsiStyle`；打印经 `ReplReader.printAsync(String)`（新增 default 方法，jline 实现用 `terminal.writer().println + flush`，Buffered 实现直接 `out.println`），**不新建打印通道**，且不抢占 REPL 输入焦点。
- **非阻塞**：通知在传输 reader 线程 / 请求线程上同步派发到 `notificationListeners`，`NotificationDispatcher` 仅做轻量解析后交给 `TerminalNotificationPresenter`，后者做级别过滤 + progress 去抖后一行打印即返回，绝不阻塞 request/response（handleMessage 对通知是 fire-and-forget）。

### 1.3 @mention 默认注入格式（内联文本块）

默认以**内联文本块**注入（简单、兼容所有 server），格式常量集中在 `MentionPolicy`：

```
<file path="<相对 projectDir 的路径>">
<文件原文内容>
</file>
```

超限（>32KB）追加 `[truncated: N bytes]` 标记（P1-1）。解析失败/越界：保留原文或在参数内插入 `[mention error: @token — 原因]` 标记并继续，**绝不抛异常到主流程**（P0-1 验收）。

---

## 2. 文件列表及相对路径（新增 + 修改）

> 包约定：`com/agentcli/mcp`（MCP 客户端）、`com/agentcli/mcp/mention`（@mention）、`com/agentcli/mcp/notify`（通知）、`com/agentcli/cli`（REPL）、`com/agentcli/common`（既有）。测试在 `src/test/java/...` 同包结构。

### 2.1 新增文件
| 路径 | 类型 | 职责 |
|------|------|------|
| `src/main/java/com/agentcli/mcp/mention/MentionResolver.java` | 新增类 | @mention 解析/读取/安全/截断的**唯一入口**：`resolve(String)`（自由文本）、`resolveJson(String)`（工具参 JSON）。内部复用 `PathGuard`。 |
| `src/main/java/com/agentcli/mcp/mention/MentionPolicy.java` | 新增常量类 | 所有共享常量：32KB 阈值、`[truncated: N bytes]` 标记、`MENTION_PATTERN` 正则、内联块格式、`~` 展开规则。 |
| `src/main/java/com/agentcli/mcp/notify/NotificationLevel.java` | 新增枚举 | `ERROR/WARNING/INFO/DEBUG/LOG/PROGRESS` + `fromString`、`label()`。 |
| `src/main/java/com/agentcli/mcp/notify/NotificationRecord.java` | 新增 record | 值对象：`serverName, level, method, message, progress(Long), total(Long)`。 |
| `src/main/java/com/agentcli/mcp/notify/NotificationPresenter.java` | 新增接口 | `void show(NotificationRecord record)`。 |
| `src/main/java/com/agentcli/mcp/notify/NotificationDispatcher.java` | 新增类 | 实现 `Consumer<JsonNode>`；`accept(JsonNode)` 解析 method/params → `NotificationRecord` → `presenter.show(...)`。集中维护 method→level 映射表。 |
| `src/main/java/com/agentcli/mcp/notify/NotificationFormat.java` | 新增类 | `[mcp:server] <level> <msg>` 格式 + 级别着色 + 去抖窗口/默认启用级别常量。 |
| `src/main/java/com/agentcli/mcp/notify/TerminalNotificationPresenter.java` | 新增类 | `implements NotificationPresenter`；级别过滤 + progress 去抖（200ms）+ 经 `Consumer<String> printer` 打印。 |
| `src/test/java/com/agentcli/mcp/mention/MentionResolverTest.java` | 新增测试 | 解析/安全拒绝/`~` 展开/截断断言（临时文件，确定性）。 |
| `src/test/java/com/agentcli/mcp/testutil/FakeMcpTransport.java` | 新增测试工具 | 顶层 `McpTransport` 桩：记录 `tools/call` 入参（供断言注入）、`pushNotification(JsonNode)` 模拟 server→client 通知。 |
| `src/test/java/com/agentcli/mcp/McpClientMentionTest.java` | 新增测试 | 用 `FakeMcpTransport` 断言 `callTool` 的 `argumentsJson` 含文件内容；无 mention 时原文不变。 |
| `src/test/java/com/agentcli/mcp/notify/NotificationDispatcherTest.java` | 新增测试 | 用桩 + `pushNotification` 断言 `window/logMessage`/`notifications/message`/`notifications/progress` 正确映射 level 与 message。 |
| `src/test/java/com/agentcli/mcp/notify/TerminalNotificationPresenterTest.java` | 新增测试 | 用 `Consumer<String>` 捕获输出，断言去抖合并 + 级别过滤。 |
| `src/test/java/com/agentcli/mcp/McpNotificationIntegrationTest.java` | 新增测试 | 端到端：`FakeMcpTransport` 推送通知 → 断言终端行被打印；server 不发通知 → 零异常、主流程不变。 |

### 2.2 修改文件（最小变更，向下兼容）
| 路径 | 改动点 |
|------|--------|
| `src/main/java/com/agentcli/mcp/McpClient.java` | ①新增字段 `MentionResolver mentionResolver`、`NotificationDispatcher notificationDispatcher`；②**新增** 4 参构造 `(serverName, transport, Path projectDir, NotificationPresenter presenter)`（2 参构造保留并委托，mention/presenter=null）；③`callTool` 内对 `argumentsJson` 调 `mentionResolver.resolveJson`（null 则跳过）；④`initialize()` 内若 `notificationDispatcher!=null` 则 `rpc.onNotification(notificationDispatcher::accept)`；⑤**新增** `registerNotificationListener(Consumer<JsonNode>)` 委派 `rpc.onNotification`（测试/灵活接线用）。 |
| `src/main/java/com/agentcli/mcp/McpServerManager.java` | ①新增字段 `Consumer<String> notificationPrinter` + `setNotificationPrinter(...)`（新方法，兼容）；②`start(server)` 改用 4 参构造 `new McpClient(name, transport, projectDir, presenter)`，其中 `presenter = new TerminalNotificationPresenter(notificationPrinter)`，`dispatcher` 由 `McpClient` 内部构建并自动注册。 |
| `src/main/java/com/agentcli/cli/InteractiveSession.java` | ①新增字段 `MentionResolver mentionResolver` + `setMentionResolver(...)`（新方法）；②`run()` 循环中，对**自然语言轮（命令解析为 NONE）** 在 `runTurn` 前 `line = mentionResolver.resolve(line)`；`/命令` 轮不解析（避免破坏命令语法）。 |
| `src/main/java/com/agentcli/cli/Launcher.java` | ①构造 `Consumer<String> printer = input::printAsync`（jline 安全打印）；②`mcpManager.setNotificationPrinter(printer)`；③`session.setMentionResolver(new MentionResolver(Path.of(projectPath)))`。 |
| `src/main/java/com/agentcli/cli/ReplReader.java` | **新增** `default void printAsync(String line) { System.out.println(line); }`，并在 `JlineReplReader` 中覆写为 `terminal.writer().println(line); terminal.flush();`（jline 安全、不抢占输入）。 |

---

## 3. 数据结构和接口（类图见 `docs/class-diagram.mermaid`）

### 3.1 核心类契约（方法签名 / 入参 / 返回 / 异常行为）

**`MentionResolver`（新增，`com.agentcli.mcp.mention`）**
```
MentionResolver(Path projectDir)              // 内部构建 PathGuard(projectDir)
String resolve(String text)                    // 自由文本：扫描 @path 并内联替换；null/blank 原样返回
String resolveJson(String argumentsJson)       // 工具参 JSON：解析后遍历字符串叶子节点做 resolve；
                                               //   解析/读取失败 -> 回退返回原始 argumentsJson（降级，不抛）
```
- 异常行为：`resolve` 命中越界（`PolicyException`）/IO 错误/非文件 → 该 token 替换为 `[mention error: @token — 原因]` 标记并继续；**绝不中断主流程**。
- 依赖：`com.agentcli.common.PathGuard`、`MentionPolicy` 常量。

**`MentionPolicy`（新增，常量）**
```
static final int MAX_INLINE_BYTES = 32 * 1024;                       // P1-1 截断阈值
static final String TRUNCATED_MARKER = "[truncated: %d bytes]";      // 超限标注
static final Pattern MENTION_PATTERN = Pattern.compile("(?<!\\p{Alnum})@([^\\s@,;]+)");
                                                       // 避免匹配 user@host；支持 @a,@b 逗号分隔
static String inlineFormat(String relPath, String content); // -> <file path="relPath">\n..content..\n</file>
```
- 正则设计理由：`(?<!\\p{Alnum})` 负向后顾排除 `user@host` 等邮箱式 token；捕获组排除空白/`@`/`,`/`;` 以兼容逗号分隔多引用。

**`NotificationLevel`（新增，枚举）**
```
enum NotificationLevel { ERROR, WARNING, INFO, DEBUG, LOG, PROGRESS }
static NotificationLevel fromString(String s)   // error/warn*->WARNING, info->INFO, debug->DEBUG, log->LOG, 缺省 INFO
String label()                                   // 用于对齐的定宽标签（progress/warning 等）
```

**`NotificationRecord`（新增，record）**
```
record NotificationRecord(String serverName, NotificationLevel level,
                          String method, String message, Long progress, Long total) {}
```

**`NotificationPresenter`（新增，接口）**
```
void show(NotificationRecord record);
```

**`NotificationDispatcher`（新增，`com.agentcli.mcp.notify`）**
```
NotificationDispatcher(String serverName, NotificationPresenter presenter)
void accept(JsonNode notification)              // 实现 Consumer<JsonNode>；供 rpc.onNotification 注册
// 内部 parse(notification):
//   method == "window/logMessage" | "notifications/message":
//       level = NotificationLevel.fromString(params.path("level").asText())
//       message = params.path("message").asText() 或 data 字符串化
//   method == "notifications/progress":
//       level = PROGRESS; progress = params.path("progress").asLong();
//       total = params.has("total") ? asLong : null
//       message = (total!=null ? progress+"/"+total : ""+progress) + 可选 params.message
//   其余 method -> 忽略（不抛）
```

**`NotificationFormat`（新增，常量 + 格式）**
```
static final String PREFIX = "[mcp:%s]";        // [mcp:<serverName>]
static final int DEBOUNCE_WINDOW_MS = 200;      // P1-2 progress 去抖窗口
static final Set<NotificationLevel> DEFAULT_ENABLED = EnumSet.allOf(NotificationLevel.class); // 默认全显示
static String format(NotificationRecord r)      // -> 着色后的 "[mcp:server] LEVEL  message"
```

**`TerminalNotificationPresenter`（新增）**
```
TerminalNotificationPresenter(Consumer<String> printer)
TerminalNotificationPresenter(Consumer<String> printer,
                              Set<NotificationLevel> enabled, long debounceWindowMs)
void show(NotificationRecord r)                 // 1) enabled 不含 r.level -> 丢弃
                                               // 2) r.level==PROGRESS 且距上次同 server 打印 < 窗口 -> 丢弃(去抖)
                                               // 3) printer.accept(NotificationFormat.format(r))  // 打印+flush 由 printer 负责
```

**`McpClient`（修改，关键增量）**
```
// 保留：McpClient(String serverName, McpTransport transport)   // mention/presenter=null（完全降级）
McpClient(String serverName, McpTransport transport, Path projectDir, NotificationPresenter presenter)
void initialize()                              // 既有逻辑 + 若 notificationDispatcher!=null: rpc.onNotification(dispatcher::accept)
String callTool(String toolName, String argumentsJson)   // 既有签名；内部: if(mentionResolver!=null) argumentsJson = mentionResolver.resolveJson(argumentsJson) (失败回退原文)
void registerNotificationListener(Consumer<JsonNode> listener)  // 委派 rpc.onNotification（新增，测试/灵活接线）
```

**`McpServerManager`（修改）**
```
void setNotificationPrinter(Consumer<String> printer)   // 新增
// start(server): McpClient client = new McpClient(server.name(), transport, projectDir,
//                   new TerminalNotificationPresenter(notificationPrinter));
//                client.initialize();   // 内部自动注册 dispatcher
```

**`InteractiveSession`（修改）**
```
void setMentionResolver(MentionResolver r)     // 新增
// run(): if (command.type()==NONE && mentionResolver!=null) line = mentionResolver.resolve(line); 再 runTurn(line, out)
```

**`ReplReader`（修改）**
```
default void printAsync(String line) { System.out.println(line); }   // 新增 default
// JlineReplReader 覆写: terminal.writer().println(line); terminal.flush();
```

### 3.2 类图（Mermaid，详见 `docs/class-diagram.mermaid`）

要点：`McpClient` *持有* `MentionResolver` 与 `NotificationDispatcher`；`NotificationDispatcher` *持有* `NotificationPresenter`（→`TerminalNotificationPresenter` 实现）；`McpServerManager` *创建* `McpClient`（注入 projectDir+presenter）与 `TerminalNotificationPresenter`；`McpToolBridge` *调用* `McpClient.callTool`；`InteractiveSession` *使用* `MentionResolver`；`MentionResolver` *使用* `PathGuard`（common）；`McpClient` *委派注册* `JsonRpcClient.onNotification`（已有）。

---

## 4. 程序调用流程（时序图见 `docs/sequence-diagram.mermaid`）

### 场景 A — REPL 用户输入 `@path` 触发工具调用（P0-1 + P0-2 + P1-3）
```
User -> InteractiveSession.run: 输入 "帮我看 @src/main/App.java 的 main"
InteractiveSession -> MentionResolver.resolve(line): 自由文本解析，内联文件内容
InteractiveSession -> ReActAgent.run(resolved): 模型决定调用工具
ReActAgent -> McpToolBridge.execute(args): 模型生成参数字符串可能含 @path
McpToolBridge -> McpClient.callTool(name, argumentsJson)
McpClient -> MentionResolver.resolveJson(argumentsJson): 遍历 JSON 字符串叶子做 @mention 解析
McpClient -> JsonRpcClient.request("tools/call", params)
JsonRpcClient -> McpTransport.send: 发送 tools/call
McpTransport --> JsonRpcClient: 响应 (id 非空) -> 返回结果给 callTool
```
> P1-3（工具参内 @mention）即在 `callTool.resolveJson` 这一步被覆盖，无需改 `McpToolBridge`。

### 场景 B — server 推送 `window/logMessage` 被终端展示（P0-3 + P0-4）
```
McpServer -> McpTransport: 入站消息 (无 id, method=window/logMessage)
McpTransport -> JsonRpcClient.handleMessage: 经 onReceive 监听（stdio reader线程 / http 请求线程）
JsonRpcClient -> NotificationDispatcher.accept: id==null -> 遍历 onNotification 监听器
NotificationDispatcher -> NotificationDispatcher.parse: method/params -> NotificationRecord(level=INFO,...)
NotificationDispatcher -> TerminalNotificationPresenter.show(record)
TerminalNotificationPresenter -> NotificationFormat.format + 级别过滤 + (非progress) 即时打印
TerminalNotificationPresenter -> printer.accept(line): 经 ReplReader.printAsync -> 终端一行（不抢输入）
```

### 场景 C — server 不发通知时正常调用（降级兼容）
```
InteractiveSession -> McpClient.callTool(name, argumentsJson)  // 无 @mention
McpClient -> MentionResolver.resolveJson: mentionResolver==null 或 无 @token -> 原文不变
McpClient -> JsonRpcClient.request("tools/call") -> 正常响应
JsonRpcClient.handleMessage: notificationListeners 为空 -> 无通知处理，零额外开销/异常
```
> 验证点：无通知时无额外输出、无异常；`callTool` 无 mention 时 `argumentsJson` 与改造前逐字节一致。

---

## 5. 任务列表（有序、含依赖、按实现顺序；覆盖 P0 全部 + P1 全部，P2 标注可选）

> 说明：本仓库为 Java/Maven 且**零新增依赖**，无 vite/tsconfig 之类构建配置基建任务（与通用 Web 模板的“T01 基础设施”不同），故首个任务即为**共享解析内核**。任务按“特征模块”分组以复用逻辑、减少线性链；T1/T3 相互独立，T2 依赖 T1，T4 依赖 T3，T5 集成全部。

### T1 — @mention 解析内核（`MentionResolver` + 常量 + 单测）【P0-1, P1-1】
- **改/建文件**：`mention/MentionResolver.java`、`mention/MentionPolicy.java`、`test/.../mention/MentionResolverTest.java`
- **做什么**：
  - `MentionPolicy` 落地全部共享常量（32KB、`[truncated: N bytes]`、`MENTION_PATTERN`、内联块格式）。
  - `MentionResolver`：`resolve(String)` 扫描 `@token` → 展开 `~`（仅领先 `~/`）→ `PathGuard.resolveSafe` 校验（越界抛 `PolicyException` 则产 `[mention error:...]` 标记）→ 读取内容，超 `MAX_INLINE_BYTES` 截断加标记 → `inlineFormat` 内联替换；`resolveJson(String)` 解析 JSON、递归遍历字符串叶子节点调用 `resolve`、重序列化（失败回退原文）。
- **依赖**：无（仅依赖既有 `PathGuard`/`PolicyException`）。**优先级 P0**。

### T2 — @mention 注入 MCP 工具调用（`callTool` + 单测）【P0-2, P1-3】
- **改/建文件**：`mcp/McpClient.java`（新增 4 参构造 + `callTool` 内 `resolveJson` + `registerNotificationListener`）、`test/.../mcp/McpClientMentionTest.java`、`test/.../mcp/testutil/FakeMcpTransport.java`
- **做什么**：
  - `McpClient` 新增 `MentionResolver mentionResolver` 字段与 4 参构造（2 参保留委托、mention=null）；`callTool` 内 `if(mentionResolver!=null) argumentsJson = mentionResolver.resolveJson(argumentsJson)`（try/catch 回退原文）。
  - `FakeMcpTransport`：记录最近一次 `tools/call` 的 `argumentsJson`、提供 `pushNotification`；`McpClientMentionTest` 断言含 `@README.md` 的调用参数携带文件内容、不含 mention 时原文不变、模型生成多 `@path` 均解析。
  - **P1-3 自动覆盖**：因 `McpToolBridge.execute` 经 `callTool` 落点，工具参内 `@path` 一并解析，无需改 `McpToolBridge`（在测试里补一条经 `McpToolBridge` 的用例加固）。
- **依赖**：T1。**优先级 P0**。

### T3 — 通知路由框架（`NotificationDispatcher` + 单测）【P0-3】
- **改/建文件**：`notify/NotificationDispatcher.java`、`notify/NotificationLevel.java`、`notify/NotificationRecord.java`、`notify/NotificationPresenter.java`、`test/.../notify/NotificationDispatcherTest.java`
- **做什么**：
  - `NotificationLevel`/`NotificationRecord`/`NotificationPresenter` 定义。
  - `NotificationDispatcher(String serverName, NotificationPresenter)` 实现 `Consumer<JsonNode>`：`accept` 解析 `window/logMessage` / `notifications/message` / `notifications/progress` → `NotificationRecord` → `presenter.show(...)`；未知 method 忽略、解析异常忽略（不冒泡）。
  - `NotificationDispatcherTest`：用 `FakeMcpTransport.pushNotification` + 内存 `NotificationPresenter` 桩，断言三类通知正确映射 level/message/progress，且主流程零异常。
- **依赖**：无（仅复用既有 `JsonRpcClient.onNotification`）。**优先级 P0**。

### T4 — 终端展示（`TerminalNotificationPresenter` + 去抖/级别过滤 + 单测）【P0-4, P1-2】
- **改/建文件**：`notify/TerminalNotificationPresenter.java`、`notify/NotificationFormat.java`、`test/.../notify/TerminalNotificationPresenterTest.java`
- **做什么**：
  - `NotificationFormat`：`[mcp:%s]` 前缀、定宽 level 标签、`DEBOUNCE_WINDOW_MS=200`、`DEFAULT_ENABLED=全显示`、`format(record)` 复用 `AnsiStyle` 着色。
  - `TerminalNotificationPresenter`：`show` 先按 `enabled` 过滤（P1-2 级别过滤）；`PROGRESS` 按 `serverName` 做 200ms 去抖（窗口内丢弃，P1-2）；其余即时经 `Consumer<String> printer` 打印。`printer` 线程安全由接线层（`ReplReader.printAsync`）保证。
  - `TerminalNotificationPresenterTest`：捕获输出断言连续 progress 被去抖、低于阈值级别被过滤、info/warn/error 全显示。
- **依赖**：T3。**优先级 P0**。

### T5 — 集成接线与降级兼容（构造注入 + 注册 + REPL 自由文本 + Launcher 接线 + 端到端测试）【P0-1~P0-4, P1-1~P1-3 集成】
- **改/建文件**：`mcp/McpClient.java`（`initialize()` 注册 dispatcher）、`mcp/McpServerManager.java`（`setNotificationPrinter` + 4 参构造 + presenter）、`cli/InteractiveSession.java`（`setMentionResolver` + NONE 轮解析）、`cli/Launcher.java`（printer + 接线）、`cli/ReplReader.java`（`printAsync`）、`test/.../mcp/McpNotificationIntegrationTest.java`
- **做什么**：
  - `ReplReader` 加 `printAsync`（jline 安全打印）。
  - `McpServerManager.start()` 改用 `new McpClient(name, transport, projectDir, new TerminalNotificationPresenter(notificationPrinter))`；`McpClient.initialize()` 内 `rpc.onNotification(notificationDispatcher::accept)`。
  - `InteractiveSession` 仅对 NONE 轮 `mentionResolver.resolve(line)`；`Launcher` 组装 `printer = input::printAsync` 注入 `mcpManager` 与 `session`。
  - `McpNotificationIntegrationTest`：端到端断言 server 推送通知被打印为 `[mcp:server] ...` 一行；server 不发通知时 `callTool` 零异常、`argumentsJson` 不变（降级）。
- **依赖**：T1, T2, T3, T4。**优先级 P0**。

### P2（可选，不阻塞本次发布）
- **P2-1 多 server 通知去重聚合**：行已带 `serverName`；本项指跨 server 相同通知去重聚合。建议加 `NotificationDispatcher` 的 content-hash 去重（可选任务，独立文件/方法）。
- **P2-2 `@mention` 支持 glob/目录**：如 `@src/**/*.java`、`@docs/`。在 `MentionResolver` 增加 glob/目录展开分支（复用 `FileSystem`/`PathMatcher`），不影响既有单文件逻辑。

---

## 6. 依赖包列表

**无新增 Maven 依赖。** 全部复用现有依赖树（见 `pom.xml`）：
- `com.fasterxml.jackson.core:jackson-databind:2.16.0`（JSON 解析/序列化）
- `com.squareup.okhttp3:okhttp:4.12.0`（HTTP 传输，已用）
- `org.jline:jline:3.26.3`（REPL/Terminal，`ReplReader.printAsync` 复用）
- `ch.qos.logback:logback-classic`（日志，已用）
- 测试：`org.junit.jupiter:junit-jupiter:5.10.2`、`com.squareup.okhttp3:mockwebserver:4.12.0`（test scope，已用）

> 关键结论：`JsonRpcClient.onNotification` 已存在 → **McpTransport 契约零改动**；`PathGuard` 已存在 → 路径安全零自研。

---

## 7. 共享知识（跨文件约定，工程师落地务必统一）

1. **@mention 解析唯一入口**：`MentionResolver.resolve(String)`（自由文本）/ `resolveJson(String)`（工具参 JSON）。任何注入需求都走这里，内部复用 `common.PathGuard`。
2. **路径安全单真相源**：越界判定只在 `PathGuard.resolveSafe`；`MentionResolver` 捕获 `PolicyException` 后转 `[mention error: ...]` 标记，不自行校验。
3. **通知 method→level 映射表位置**：集中在 `NotificationDispatcher.parse(JsonNode)`（`window/logMessage`/`notifications/message`/`notifications/progress`）；枚举 `NotificationLevel.fromString`。**新增通知类型只改此处**。
4. **`[mcp:server]` 格式常量位置**：`NotificationFormat.PREFIX = "[mcp:%s]"` + `format(NotificationRecord)`；级别着色复用 `AnsiStyle`。
5. **去抖窗口常量位置**：`NotificationFormat.DEBOUNCE_WINDOW_MS = 200`（仅对 `PROGRESS` 生效）。
6. **默认级别过滤**：`NotificationFormat.DEFAULT_ENABLED = EnumSet.allOf(NotificationLevel.class)`（默认全显示）。
7. **32KB 截断常量位置**：`MentionPolicy.MAX_INLINE_BYTES = 32 * 1024` + `MentionPolicy.TRUNCATED_MARKER`。
8. **@mention 正则**：`MentionPolicy.MENTION_PATTERN = Pattern.compile("(?<!\\p{Alnum})@([^\\s@,;]+)")`（避免 `user@host`，支持逗号分隔多引用）。
9. **内联块格式**：`MentionPolicy.inlineFormat(relPath, content)` → `<file path="relPath">\n..\n</file>`。
10. **注入失败行为**：解析/读取/安全异常 → 保留原文或插 `[mention error:...]`，**绝不抛到主流程**；通知解析异常 → `NotificationDispatcher` 静默忽略。
11. **终端打印安全**：一律经 `ReplReader.printAsync(String)`（jline 用 `terminal.writer().println + flush`，Buffered 用 `out.println`），**不新建打印通道、不抢占 REPL 输入**。
12. **测试命名/位置**：测试类放 `src/test/java/com/agentcli/mcp/{mention,notify}` 同包；桩 `FakeMcpTransport` 放 `testutil`。QA 用 `mvn clean test -DskipTests=false`（pom 默认 `skipTests=true`）；Surefire `-Dtest` 用类名列表或前缀（如 `-Dtest='MentionResolverTest,McpClientMentionTest,Notification*Test,McpNotificationIntegrationTest'`），**勿用纯排除式**（会全跳过）。

---

## 8. 待明确事项（PRD 假设核实结果与修正建议）

1. ✅ **`JsonRpcClient.onNotification` 真实存在**（`JsonRpcClient.java:85`），且经 `handleMessage` 对 stdio/http 传输统一路由。**无需扩展 McpTransport 契约**——可零改动实现 P0-3。PRD 假设成立。
2. ✅ **`McpToolBridge` 真实存在**，`execute(Map<String,String>)` → `callTool(name, argumentsJson)`。**P1-3 经 `callTool` 注入点自动覆盖，PRD 落点无需修正**。
3. ⚠️ **`~` 展开受 `projectDir` 约束的可用性问题**：按 PRD 默认（Q5），`~/x` 展开为 `user.home/x` 后仍需落在 `projectDir` 归一化范围内，否则拒绝。多数用户 home 不在项目目录内，意味着 `~/x` 几乎总被拒。**本次按 PRD 实现并标注**；建议后续产品确认是否放宽“仅允许 `~` 指向项目内子路径”，或允许 `~` 指向项目内的已知子目录（如 `.agentcli/`）。
4. ⚠️ **REPL 自由文本 @mention 的作用域**：仅在**自然语言轮（命令解析为 NONE）** 做 `resolve`；`/命令` 轮（含 `/tool`、`/mcp` 等）不解析，以免破坏命令语法。这是为兼容性与最小变更做的取舍，已在 T5 明确。
5. ⚠️ **现有嵌套 `FakeTransport`**（`McpResourcesPromptsTest` 内，package-private）只回答预设响应、**不模拟 server→client 通知**。本设计新增顶层 `FakeMcpTransport`（记录 `tools/call` 入参 + `pushNotification`），不与既有桩冲突，工程师可直接复用。
6. ⚠️ **通知线程模型**：stdio 通知在 reader 守护线程、HTTP 通知在请求调用线程；两者均经 `JsonRpcClient.handleMessage` → `onNotification` 派发。`TerminalNotificationPresenter` 不做线程同步，线程安全完全由 `printer`（`ReplReader.printAsync`）保证，已在 T4/T5 落实。
7. ✅ **`McpClient` 既有调用方兼容**：除 `McpServerManager`（改用 4 参）与测试 `McpResourcesPromptsTest`（`new McpClient("test", new FakeTransport(...))`，2 参，仍可用，mention/presenter=null → 完全降级）外，未见其他 2 参调用点；2 参构造保留，故既有测试与未来调用方不受影响。

---

> 配套图：`docs/class-diagram.mermaid`（类关系）、`docs/sequence-diagram.mermaid`（场景 A/B/C 时序）。本设计聚焦**集成点与最小变更**，未含具体代码实现（由工程师落地）。
