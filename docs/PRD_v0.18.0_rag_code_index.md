# PRD · AgentCLI v0.18.0 —— RAG 向量索引 + 类关系图谱（/index /search /graph）

> 产品经理：许清楚　|　状态：待架构评审　|　上游依据：ROADMAP v0.18.0 规划原文
> 前置版本：v0.17.0（讯飞 Xfyun / Agnes 客户端 + Prompt Cache 元数据，364 用例全绿，零新增依赖）

---

## 1. 项目信息

| 项 | 内容 |
|---|---|
| Language | 中文 |
| Programming Language | Java 17 + Maven（沿用现有技术栈，不引入新语言/框架） |
| Project Name | `agentcli_rag_code_index` |
| 新增包 | `com.agentcli.rag` |
| 改造点 | `cli/CliCommandParser`、`cli/InteractiveSession` |

**原始需求复述**：当前 AgentCLI 的代码检索仅依赖 Ripgrep / JavaCodeSearchEngine 的字面与正则匹配，在大代码库下「按意图找代码」和「理清类之间依赖」两件事效率低。v0.18.0 需引入 RAG 能力：将源码分块后做 embedding 存入既有 SQLite，提供 `/search` 向量语义检索（混合关键词）、`/index` 重建索引、`/graph` 类关系图谱三条命令；全程尽力零新增依赖，无 embedding key 时优雅降级回 Ripgrep。

---

## 2. 产品定义

### 2.1 Product Goals

1. **语义可检索**：让用户用自然语言描述意图（"用户登录怎么实现的"）即可定位到具体类/方法，而不必先猜到关键词。
2. **结构可视化**：让用户无需通读代码即可看清某个类的 contains / extends / implements / calls 关系，快速建立代码库心智模型。
3. **零负担可降级**：RAG 是增强而非依赖——没有 embedding key、索引失败、网络不通时，检索能力必须无声退回既有 Ripgrep 路径，绝不阻塞主流程。

> **简历导向价值**：引入 RAG 代码索引与类关系图谱：将源码分块 embedding 后存于既有 SQLite，支持向量语义检索与 import/extends/implements/calls 依赖关系可视化，提升大代码库定位效率；向量计算内存完成，不引入独立向量服务，并实现无 key 场景的全链路优雅降级。

### 2.2 User Stories

1. **As a** 接手陌生代码库的开发者，**I want** 执行 `/index` 一次性建立全库语义索引，**so that** 后续所有检索都能基于语义而非字面匹配。
2. **As a** 正在排查问题的开发者，**I want** 用 `/search 用户登录实现` 这样的自然语言检索，**so that** 即使我不知道类名叫 `AuthService` 还是 `LoginHandler` 也能找到正确位置。
3. **As a** 准备重构的开发者，**I want** 用 `/graph ReactAgent` 查看该类的继承、实现与调用关系，**so that** 我能评估改动的影响范围。
4. **As a** 没有配置 embedding key 的用户，**I want** `/search` 自动退回关键词检索并给出明确提示，**so that** 我不会因为缺少配置而完全无法使用检索功能。
5. **As a** 关注依赖体积的维护者，**I want** 该特性不引入独立向量数据库或新的重量级依赖，**so that** 项目保持轻量、构建与分发成本不变。

---

## 3. 需求池

> 优先级定义：**P0 = Must have（本版本必须交付）**；**P1 = Should have（本版本目标交付）**；**P2 = Nice to have（可延后至后续版本）**

### P0 · 核心闭环（缺一不可）

| ID | 需求 | 验收标准 |
|---|---|---|
| P0-1 | **EmbeddingClient 抽象 + 1 个 OpenAI 兼容实现** | must 提供统一 `embed(text)` 能力；must 支持通过 provider / model / baseUrl / apiKey 四要素配置；must 复用既有 OkHttp + Jackson，pom.xml 零新增依赖；单测覆盖正常返回、HTTP 错误、空文本三种情况。 |
| P0-2 | **配置接入 AppConfig** | embedding 的 provider/model/baseUrl/key must 可从 `~/.agentcli/config.json` 读取，并 should 支持环境变量回退（与既有 providers map 风格一致）；未配置时不抛异常，返回「不可用」状态。 |
| P0-3 | **VectorStore 基于既有 SQLite** | must 复用 v0.9.0 的 `SqliteStore`（`~/.agentcli/db/agentcli.db`），幂等建表；向量以 JSON 文本存储；must 支持按项目路径隔离数据；must not 引入独立向量库或外部服务。 |
| P0-4 | **内存余弦相似度检索** | must 在内存中完成向量相似度计算并返回 topK；单测验证相似度排序正确性与 topK 截断。 |
| P0-5 | **`/search` 向量 + 关键词混合检索** | must 合并语义检索与关键词检索结果并按 ID 去重；must 对双路命中的结果加分；输出 must 包含文件路径、名称、行号区间、得分、代码片段预览。 |
| P0-6 | **无 key / 索引失败优雅降级** | 当 embedding 不可用（无 key、请求失败、索引为空）时：`/search` must 自动退回纯 Ripgrep/关键词检索并返回结果，must 打印一次性降级提示；进程 must not 崩溃或抛栈；**must 有专门单测覆盖「无 embedding key」降级路径**。 |
| P0-7 | **CLI 命令接入** | `/index`、`/search`、`/graph` must 在 `CliCommandParser` 注册并在 `InteractiveSession` 正确派发；must 补充 `/help` 与命令补全提示。 |
| P0-8 | **测试与回归** | 既有 364 用例 must 全绿；新增模块 should 有对应单测；pom.xml 依赖树 must 无新增条目（JavaParser 若引入需走 P2 决策流程，见开放问题②）。 |

### P1 · 完整体验

| ID | 需求 | 验收标准 |
|---|---|---|
| P1-1 | **`/index [路径]` 建立/重建索引** | must 遍历源码树、过滤忽略目录（`target/`、`.git/`、`build/`、`node_modules/` 等）；must 支持重复执行覆盖旧索引（幂等）；must 输出进度与最终统计（文件数 / 分块数 / 耗时 / 失败数）。 |
| P1-2 | **代码分块（CodeChunker）** | Java 文件 should 按类/方法粒度分块，非 Java 文件按大小分段；单块 must 有字符上限以适配 embedding 上下文；分块结果 must 携带文件路径、类型、名称、起止行号。 |
| P1-3 | **CodeRelation 关系抽取（正则方案）** | must 抽取 `contains` / `extends` / `implements` / `calls` 四类关系；优先使用正则 / Ripgrep 实现以满足零依赖红线；抽取失败的文件 must 跳过而非中断整体索引。 |
| P1-4 | **`/graph <类名>` 展示类关系** | must 以树形箭头格式输出关系列表；无匹配时 must 给出友好空态提示；**`/graph` must not 依赖 embedding**（见开放问题④）。 |
| P1-5 | **索引增量与失效提示** | should 在文件变更后提示索引可能过期，建议重新 `/index`（可用简单的 mtime / 文件数比对，不要求完整增量索引）。 |
| P1-6 | **结果格式化（SearchResultFormatter）** | 检索结果 should 有统一的 CLI 友好排版（高亮、截断、序号），长片段 must 截断避免刷屏。 |

### P2 · 可选增强（本版本可不做）

| ID | 需求 | 验收标准 |
|---|---|---|
| P2-1 | **JavaParser 可选升级** | 若决策引入，must 在 pom.xml 统一版本管理；分块与关系抽取精度提升至 AST 级；must 保留正则方案作为 fallback。**依赖开放问题②拍板。** |
| P2-2 | **跨语言支持** | 扩展至 Python / TypeScript / Go 等语言的分块与关系抽取。 |
| P2-3 | **RAG 结果注入 Agent 上下文** | 将 `/search` 结果自动作为 ReAct 循环的上下文补充，而非仅供人查看。 |
| P2-4 | **真正的增量索引** | 基于文件 hash / mtime 只重新索引变更文件，降低大库重建成本。 |
| P2-5 | **`/graph` 导出 Mermaid** | 支持将关系图谱导出为 Mermaid classDiagram 文本，便于贴入文档。 |

---

## 4. CLI 命令与交互设计

### 4.1 `/index` —— 建立/重建索引

```
/index              # 索引当前目录
/index <路径>        # 索引指定路径
```

**期望输出（成功）**
```
📦 开始索引: /path/to/project
   扫描文件 ... 128 个源文件
   分块 ......... 642 个代码块
   向量化 ....... 642/642
✅ 索引完成：128 文件 / 642 块 / 耗时 12.4s
```

**期望输出（无 embedding key，降级）**
```
📦 开始索引: /path/to/project
⚠️ 未检测到 embedding 配置，跳过向量化，仅建立结构索引（类关系图谱可用）
   提示：在 ~/.agentcli/config.json 配置 embedding 后重新 /index 可启用语义检索
✅ 结构索引完成：128 文件 / 312 条关系 / 耗时 1.8s
```

### 4.2 `/search` —— 语义 + 关键词混合检索

```
/search <自然语言查询>
```

- 默认返回 topK = 5
- 结果按混合得分降序；`method` / `class` 类型的块享有类型加分

**期望输出**
```
🔍 检索: 用户登录实现
📋 找到 3 条结果：

 1. [method] AuthService#login  (src/main/java/.../AuthService.java:42-78)  score 0.87
    public LoginResult login(String username, String password) { ...

 2. [class]  LoginController    (src/main/java/.../LoginController.java:15-96)  score 0.71
    ...
```

**空参数**
```
❌ 请提供检索关键词，例如 /search 用户登录实现
```

**未索引**
```
⚠️ 代码库尚未索引，请先执行 /index
```

**降级提示（无 embedding key）**
```
🔍 检索: 用户登录实现
⚠️ 语义检索不可用（未配置 embedding），已降级为关键词检索
📋 找到 2 条结果：
...
```

**无结果**
```
📭 未找到相关代码
```

### 4.3 `/graph` —— 类关系图谱

```
/graph <类名>
```

**期望输出**
```
🕸️ 查询类关系图谱: ReactAgent
📋 找到 6 条关系:

   ReactAgent ├── contains   --> think()
   ReactAgent ├── contains   --> act()
   ReactAgent └── extends    --> BaseAgent
   ReactAgent └── implements --> Agent
   ReactAgent ├── calls      --> ToolRegistry.invoke
   ReactAgent ├── calls      --> MemoryManager.recall
```

**空参数**
```
❌ 请提供类名，例如 /graph ReactAgent
```

**无结果**
```
📭 未找到相关关系（请确认类名拼写，或先执行 /index）
```

### 4.4 交互设计通则

- 所有命令 must 遵循既有 `InteractiveSession` 的输出风格（emoji 前缀 + 中文提示）。
- 所有失败路径 must 输出单行友好错误（`❌ xxx 失败: <原因>`），must not 打印异常堆栈到用户界面。
- 降级提示在单次会话内 should 只提示一次，避免每次检索都刷屏。
- `/index` `/search` `/graph` must 加入命令补全提示列表。

---

## 5. 待确认问题（需架构师 / 主理人拍板）

> 以下问题均已对照 `_reference/src/main/java/com/paicli/rag/*` 参考实现核对，**参考快照实际存在于仓库中**（团队此前认为未随附，特此更正），下方结论基于真实代码。

### ① 默认 embedding provider 与端点选择

- **现状冲突**：ROADMAP 写的是「OpenAI/Moonshot embedding 兼容端点」，但参考实现 `EmbeddingClient` 的**默认 provider 是 Ollama**（`nomic-embed-text:latest`，本地端点），OpenAI/zhipu/glm 走同一套 OpenAI 兼容分支。
- **需决策**：默认值取 Ollama（本地、无需 key、但要求用户装 Ollama）还是 OpenAI 兼容远程端点（需 key，但零本地环境依赖）？是否两者都实现并以配置切换？
- **影响面**：直接决定「无 key 降级」的触发条件定义，以及 P0-6 单测的构造方式。

### ② 是否引入 JavaParser 依赖

- **现状冲突**：这是本版本最大的红线冲突。参考实现的 `CodeChunker` **和** `CodeAnalyzer` **两者都硬依赖 JavaParser**（`com.github.javaparser.*`）。若坚持「零新增依赖」，则分块与关系抽取都必须用正则重写，**无法与参考实现保持一致**。
- **需决策**：三选一 ——
  - (a) 严守零依赖，正则实现，接受精度下降（`calls` 关系尤其难准确抽取）；
  - (b) 引入 JavaParser，放宽红线，与参考对齐，pom 统一版本管理；
  - (c) 正则为默认实现 + JavaParser 作为可选 profile / 后续版本升级。
- **影响面**：决定 P1-2、P1-3 的实现方案与工作量，以及 P2-1 是否还有存在必要。**建议优先拍板此项**，它阻塞后续多项设计。

### ③ 代码分块粒度

- **现状**：参考实现为「Java 文件按类 + 方法分块（AST 驱动），非 Java 文件按字符上限分段」，单块上限 2000 字符。
- **需决策**：若②选择正则方案，方法级分块的准确性会下降，是否退化为「按文件 / 按顶层声明」粒度？单块字符上限是否沿用 2000？
- **影响面**：分块粒度直接影响检索精度与 embedding 调用次数（成本）。

### ④ `/graph` 是否依赖 embedding

- **明确建议：不应依赖。** 关系抽取是纯静态分析，与向量无关。
- **需注意的坑**：参考实现中 `/graph` 的前置校验用的是 `stats.chunkCount() == 0`（向量块数量），这意味着**无 embedding key 导致向量块为空时，`/graph` 会被误拦截**。本版本 must 修正为：关系数据独立落库、`/graph` 只校验关系表记录数，使其在无 embedding 场景下完全可用。
- **需确认**：是否认可上述修正？

### ⑤ SQLite 表结构与存储归属（新增问题）

- **现状冲突**：ROADMAP 写的是「复用 v0.9.0 的 SQLite，建 `vector_entries` 表」；但参考实现自建了 `code_chunks` + `code_relations` **两张表**，且**自己开了独立的 `DriverManager.getConnection`**，并未复用项目的 `SqliteStore`。
- **需决策**：表名与拆分方式（单张 `vector_entries` vs 双表 `code_chunks`/`code_relations`）？是否强制走 `SqliteStore` 统一管理连接与 busy_timeout？
- **建议**：走 `SqliteStore` 统一连接（保持 v0.9.0 的并发写保护），表结构采用双表拆分（向量与关系解耦，正好支撑④的降级诉求）。

### ⑥ `/index` 的副作用范围（新增问题）

- **现状**：参考实现在 `/index` 后会同步 `ToolRegistry.setProjectPath()` 与 `MemoryManager.setProjectPath()`。
- **需确认**：AgentCLI 是否也要保留这一副作用？它会改变既有工具的项目路径上下文，属于跨模块影响，需架构师确认是否符合当前分层设计。

---

## 6. 范围边界（Out of Scope）

本版本 **不做**：独立向量数据库 / 向量服务、真正的增量索引、跨语言（非 Java）关系抽取、RAG 结果自动注入 Agent 推理链路、图谱可视化 GUI。以上均归入 P2 或后续版本。
