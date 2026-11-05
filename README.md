# AgentCLI

[![CI](https://github.com/Hongli-L/agentcli/actions/workflows/ci.yml/badge.svg)](https://github.com/Hongli-L/agentcli/actions/workflows/ci.yml)

基于 **ReAct** 模式的轻量级命令行 AI Agent。纯 Java 单机应用，无数据库与中间件依赖，聚焦后端架构设计、并发编程与工程化交付。

## 核心能力

| 模块 | 说明 |
|------|------|
| ReAct 调度 | Thought → Action（工具调用）→ Observation 循环，带轮数 / Token / 死循环预算兜底 |
| 工具框架 | 可扩展 `Tool` 接口 + `ToolRegistry` 注册表，内置文件 / 命令 / 代码检索 / 联网搜索 |
| 并行工具 | 同一轮多个 `tool_call` 线程池并发执行（上限 4），批次超时 90s，结果按序回灌 |
| 多模型抽象 | `LlmClient` 统一接口 + `OpenAiCompatibleClient` 模板基类，支持 GLM / DeepSeek / 通用 OpenAI 兼容网关 |
| 流式输出 | SSE 流式解析，终端分区渲染 reasoning 与正文 |
| 对话记忆 | 短期对话历史 + 长期事实记忆 + Token 预算 + LLM 摘要压缩 |
| 安全围栏 | 路径沙箱、命令黑名单、写文件大小上限、命令超时与输出截断 |
| 联网搜索 | 多 Provider 抽象（智谱 / SerpAPI / SearXNG），结果截断与异常降级 |
| 并发任务底座 | `AgentTask` CAS 状态机（PENDING→RUNNING→{DONE\|FAILED\|CANCELLED\|TIMEOUT}）+ 固定线程池 + 单任务/全局双层超时 + `finally` 资源回收 |
| 三层协作式取消 | ① 协作轮询取消标志 ② `Future.cancel(true)` 线程中断 ③ HTTP 句柄级 `Call.cancel()`，父子令牌级联，`/cancel` 一键收手 |
| 多 Agent 编排 | planner 拆解依赖 DAG → 多 worker 并行执行（上下文相互隔离）→ reviewer 一次复核 → 结果聚合，部分失败降级不丢已有产出 |

> **关于多 Agent 编排的口径**：这不是首创设计。多 Agent 编排（planner/worker/reviewer）与协作式取消
> 是业界已有的通行做法，本项目参考实现中亦有等价类。本版的工程增量在于：worker **串行预创建后池化**
> 避免并行任务体内构造共享注册表、取消做到**三层**（协作标志 / 线程中断 / HTTP 句柄）并用 `ThreadLocal`
> 作用域下沉到传输层而不改动 `LlmClient` 接口签名、部分失败**收敛为降级结果**而非让 `allOf` 整批抛出。

## 架构

```
┌─────────────────────────────────────────────────────────┐
│  cli          Launcher / InteractiveSession / 斜杠命令   │
├─────────────────────────────────────────────────────────┤
│  agent        ReActAgent / AgentBudget / SystemPrompt    │
├──────────────┬──────────────────────┬───────────────────┤
│  tool        │  llm                 │  memory           │
│  ToolRegistry│  LlmClientFactory    │  MemoryManager    │
│  builtin/*   │  Glm/DeepSeek/...    │  TokenBudget      │
│  web/*       │  OpenAiCompatible    │  HistoryCompactor │
├──────────────┴──────────────────────┴───────────────────┤
│  common       AppConfig / EnvLoader / PathGuard / ...    │
└─────────────────────────────────────────────────────────┘
```

依赖方向：`cli → agent → {tool, llm, memory} → common`，禁止反向依赖。

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.9+

### 1. 配置 API Key

```bash
cp .env.example .env
# 编辑 .env，至少填写一个 provider 的 Key
```

也支持 `~/.agentcli/config.json` 持久化配置。

### 2. 构建

```bash
mvn clean package -DskipTests=false
# 或使用脚本
./scripts/package.sh        # Linux / macOS
./scripts/package.ps1       # Windows
```

不想本地构建时，可直接从 [Releases](https://github.com/Hongli-L/agentcli/releases) 下载已构建的 fat-jar（`agentcli-1.0.0.jar`），配置好 API Key 即可运行。

### 3. 运行

```bash
# 交互模式
java -jar target/agentcli-1.0.0.jar

# 单次提问
java -jar target/agentcli-1.0.0.jar -p "列出当前目录下的 Java 文件"

# 指定 provider 与项目根目录
java -jar target/agentcli-1.0.0.jar --provider deepseek -C /path/to/project
```

## 配置说明

| 变量 | 说明 |
|------|------|
| `GLM_API_KEY` | 智谱 GLM API Key（默认 provider） |
| `DEEPSEEK_API_KEY` | DeepSeek API Key |
| `OPENAI_API_KEY` | 通用 OpenAI 兼容网关 Key |
| `OPENAI_BASE_URL` / `OPENAI_MODEL` | 自定义兼容网关地址与模型 |
| `SERPAPI_KEY` / `SEARXNG_URL` | 联网搜索 Provider |
| `AGENTCLI_MEMORY_DIR` | 长期记忆存储目录（默认 `~/.agentcli/memory/`） |
| `AGENTCLI_LOG_LEVEL` / `AGENTCLI_LOG_DIR` | 日志级别与目录 |

完整示例见 [`.env.example`](.env.example)。

## 交互命令

| 命令 | 作用 |
|------|------|
| `/help` | 显示帮助 |
| `/clear` | 清空当前对话历史 |
| `/model [provider]` | 查看或切换模型（glm / deepseek / openai） |
| `/memory` | 查看记忆状态 |
| `/memory list` | 列出长期记忆 |
| `/memory search <词>` | 搜索长期记忆 |
| `/memory delete <id>` | 删除指定记忆 |
| `/memory clear` | 清空长期记忆 |
| `/compact` | 手动压缩对话历史 |
| `/plan <目标>` | 单 Agent 先规划再分步顺序执行（共享一份对话历史） |
| `/team <目标>` | 多 Agent 并行协作完成目标并复核结果（各 worker 历史隔离） |
| `/tasks` | 查看最近一轮编排的任务快照（状态 / 角色 / 耗时 / 错误摘要） |
| `/cancel [任务ID]` | 取消全部 / 指定编排任务（三层取消同时下手） |
| `/exit` | 退出 |

> `/plan` 与 `/team` 共用同一个 `Planner` 做任务拆解，区别只在执行阶段：`/plan` 由**单个** Agent
> 顺序跑完全部子任务、上下文连续；`/team` 把子任务分发给**多个隔离的 worker** 并行跑、末尾统一复核。
> 子任务彼此独立时 `/team` 更快，需要连续上下文推理时 `/plan` 更稳。

## 内置工具

| 工具 | 功能 |
|------|------|
| `read_file` / `write_file` | 读写项目内文件 |
| `list_dir` / `glob_files` | 目录浏览与 glob 查找 |
| `grep_code` | 代码内容搜索（ripgrep 优先，Java 回退） |
| `execute_command` | 执行 shell 命令（带安全策略） |
| `create_project` | 创建项目脚手架 |
| `web_search` | 联网搜索（多 Provider + 降级） |
| `save_memory` | 保存用户明确要求的长期事实 |

## 技术栈

- Java 17
- Maven（shade 打 fat-jar）
- OkHttp 4（HTTP / SSE 流式）
- Jackson（JSON）
- Logback（滚动日志）
- 终端 Markdown 轻量渲染（标题 / 列表 / 表格 / 代码块 + ANSI 配色）
- JUnit 5（单元测试）

终端渲染可通过 `NO_COLOR=1` 关闭彩色输出，或通过 `-Dagentcli.render.color=false` 禁用 ANSI。

## 开发与测试

```bash
# 编译
mvn compile

# 全量测试（Windows 内存不足时可加 -DforkCount=0）
mvn test -DskipTests=false -DforkCount=0

# 构建与质量检查
./scripts/release-check.ps1    # Windows
./scripts/release-check.sh   # Linux / macOS
```

## 目录结构

```
src/main/java/com/agentcli/
├── cli/        入口、REPL、命令解析、流式控制台
├── agent/      ReAct 循环、预算控制、系统提示词
│   ├── concurrent/     任务状态机、执行器、取消令牌与作用域
│   └── orchestration/  编排门面、worker 池、reviewer、结果聚合
├── llm/        多模型抽象与 SSE 客户端
├── tool/       工具框架、内置工具、代码搜索引擎
├── web/        联网搜索 Provider
├── memory/     对话记忆、Token 预算、历史压缩
└── common/     配置、环境变量、安全围栏、统一异常
```

## License

[MIT](LICENSE)
